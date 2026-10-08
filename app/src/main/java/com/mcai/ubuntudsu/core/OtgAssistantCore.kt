package com.mcai.ubuntudsu.core

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

object OtgAssistantCore {

    data class DeviceInfo(
        val deviceName: String,
        val vendorId: Int,
        val productId: Int,
        val deviceClass: Int,
        val protocol: String,
    )

    data class ProtocolDeviceStatus(
        val adb: String,
        val fastboot: String,
    )

    fun isAndroidProtocolDevice(device: android.hardware.usb.UsbDevice): Boolean {
        return (0 until device.interfaceCount).any { index ->
            val usbInterface = device.getInterface(index)
            usbInterface.interfaceClass == 0xFF && usbInterface.interfaceSubclass == 0x42
        }
    }

    data class CommandResult(val exitCode: Int, val output: String, val timedOut: Boolean = false)

    data class PartitionInfo(val name: String, val size: String?)

    data class ImageInfo(
        val partition: String,
        val uri: Uri?,
        val file: File?,
        val name: String,
        val sizeBytes: Long,
        val sha256: String,
        val highRisk: Boolean,
        var selected: Boolean = true,
    )

    private const val TAG = "OtgAssistantCore"
    @Volatile private var rootAccess: Boolean? = null

    fun getAdbPath(ctx: Context): String? {
        return nativeToolPath(ctx, "libadb.so")
    }

    fun getFastbootPath(ctx: Context): String? {
        return nativeToolPath(ctx, "libfastboot.so")
    }

    private fun nativeToolPath(ctx: Context, fileName: String): String? {
        val file = File(ctx.applicationInfo.nativeLibraryDir, fileName)
        Log.i(TAG, "native tool: ${file.absolutePath}, exists=${file.exists()}, executable=${file.canExecute()}")
        return file.takeIf { it.isFile && it.canExecute() }?.absolutePath
    }

    fun listUsbDevices(ctx: Context): List<DeviceInfo> {
        val usbManager = ctx.getSystemService(Context.USB_SERVICE) as? android.hardware.usb.UsbManager
            ?: return emptyList()
        return usbManager.deviceList.values.map { dev ->
            DeviceInfo(
                deviceName = dev.deviceName ?: "Unknown",
                vendorId = dev.vendorId,
                productId = dev.productId,
                deviceClass = dev.deviceClass,
                protocol = when (dev.deviceProtocol) {
                    1 -> "RNDIS"
                    2 -> "ACM"
                    else -> "Unknown"
                }
            )
        }
    }

    fun detectProtocolDevices(ctx: Context): ProtocolDeviceStatus {
        val adb = executeCommandDetailed(getAdbPath(ctx), "adb devices", 10000).output
        val fastboot = executeCommandDetailed(getFastbootPath(ctx), "fastboot devices", 10000).output
        return ProtocolDeviceStatus(
            adb = parseProtocolOutput(adb, "adb"),
            fastboot = parseProtocolOutput(fastboot, "fastboot"),
        )
    }

    private fun parseProtocolOutput(output: String, tool: String): String {
        val lines = output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.startsWith("List of devices attached") }
            .filterNot { it.startsWith("Error", ignoreCase = true) }
            .filterNot { it.startsWith("* daemon", ignoreCase = true) }
            .filterNot { it.contains("waiting for any device", ignoreCase = true) }
        val devices = lines.filter { line ->
            when (tool) {
                "adb" -> line.contains("\tdevice") || line.contains("\tunauthorized") || line.contains("\toffline")
                else -> line.split(Regex("\\s+")).size >= 2
            }
        }.toList()
        return devices.joinToString("\n").ifBlank { "No devices found" }
    }

    fun executeCommand(
        toolPath: String?,
        command: String,
        timeoutMs: Long = 30000,
        onOutput: (String) -> Unit = {},
    ): String {
        return executeCommandDetailed(toolPath, command, timeoutMs, onOutput).output
    }

    fun executeCommandDetailed(
        toolPath: String?,
        command: String,
        timeoutMs: Long = 30000,
        onOutput: (String) -> Unit = {},
    ): CommandResult {
        if (toolPath == null) return CommandResult(-1, "Error: Tool not found; check nativeLibraryDir")
        return try {
            if (hasRootAccess()) {
                return executeCommandAsRoot(toolPath, command, timeoutMs, onOutput)
            }
            val arguments = command.trim().split(Regex("\\s+")).drop(1)
            val process = ProcessBuilder(listOf(toolPath) + arguments)
                .redirectErrorStream(true)
                .start()

            val output = StringBuilder()
            val outputThread = Thread {
                process.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(output) { output.append(line).append('\n') }
                    onOutput(line)
                }
            }.apply {
                name = "${toolPath.substringAfterLast('/')}-output"
                isDaemon = true
                start()
            }

            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                outputThread.join(1000)
                CommandResult(-1, output.toString() + "Timeout: $command\n", true)
            } else {
                outputThread.join(1000)
                CommandResult(process.exitValue(), output.toString())
            }
        } catch (e: Exception) {
            CommandResult(-1, "Error: ${e.message}")
        }
    }

    private fun hasRootAccess(): Boolean {
        rootAccess?.let { return it }
        return RootShell.available().also { rootAccess = it }
    }

    private fun executeCommandAsRoot(
        toolPath: String?,
        command: String,
        timeoutMs: Long,
        onOutput: (String) -> Unit,
    ): CommandResult {
        if (toolPath == null) return CommandResult(-1, "Error: Tool not found; check nativeLibraryDir")
        val arguments = command.trim().split(Regex("\\s+")).drop(1)
        val script = (listOf(shellQuote(toolPath)) + arguments.map(::shellQuote)).joinToString(" ")
        val result = RootShell.exec(script, timeoutMs, onOutput)
        return CommandResult(result.code, result.stdout + result.stderr, result.code == -1)
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\\"'\\\"'")}'"

    fun parseFastbootPartitions(output: String): List<PartitionInfo> {
        val regex = Regex("partition-size:([^:\\s]+)\\s*:\\s*(0x[0-9a-fA-F]+)")
        return regex.findAll(output)
            .map { PartitionInfo(it.groupValues[1], it.groupValues[2]) }
            .distinctBy { it.name }
            .sortedBy { it.name }
            .toList()
    }

    fun listImages(context: Context, treeUri: Uri): List<ImageInfo> {
        val resolver = context.contentResolver
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val result = mutableListOf<ImageInfo>()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex) ?: continue
                if (!name.endsWith(".img", ignoreCase = true)) continue
                val id = cursor.getString(idIndex)
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                val size = if (cursor.isNull(sizeIndex)) 0L else cursor.getLong(sizeIndex)
                val partition = name.removeSuffix(name.substring(name.lastIndexOf('.')))
                result += ImageInfo(partition, uri, null, name, size, sha256(context, uri), isHighRisk(partition))
            }
        }
        return result.sortedBy { it.partition }
    }

    fun extractOtaPackage(
        context: Context,
        uri: Uri,
        onProgress: (Int, String) -> Unit = { _, _ -> },
    ): OtaExtractionResult {
        val root = File(context.filesDir, "ota")
        root.deleteRecursively()
        if (!root.mkdirs() && !root.isDirectory) throw IllegalStateException("Unable to create private OTA directory")
        val input = File(root, "payload.bin")
        val source = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Unable to read OTA/BIN file")
        val totalInputBytes = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            ?.takeIf { it > 0L }
        source.use { stream ->
            val buffered = stream.buffered()
            buffered.mark(4)
            val signature = ByteArray(4)
            val signatureSize = buffered.read(signature)
            buffered.reset()
            onProgress(1, "Reading OTA file...")
            if (signatureSize == 4 && signature.contentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04))) {
                ZipInputStream(buffered).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null && !entry.name.equals("payload.bin", ignoreCase = true)) {
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                    if (entry == null) throw IllegalStateException("payload.bin not found in ZIP")
                    copyWithProgress(zip, input, totalInputBytes) { percent ->
                        onProgress(percent * 20 / 100, "Reading payload.bin from ZIP...")
                    }
                }
            } else {
                copyWithProgress(buffered, input, totalInputBytes) { percent ->
                    onProgress(percent * 20 / 100, "Reading BIN file...")
                }
            }
        }
        onProgress(20, "Input file read; starting partition extraction...")
        val extracted = File(root, "extracted").apply { mkdirs() }
        val extractor = File(context.applicationInfo.nativeLibraryDir, "libpayload_extract.so")
        if (!extractor.isFile || !extractor.canExecute()) {
            throw IllegalStateException("payload_extract is not executable: ${extractor.absolutePath}")
        }
        val process = ProcessBuilder(
            extractor.absolutePath,
            input.absolutePath,
            "--out",
            extracted.absolutePath,
            "--no-verify",
        ).redirectErrorStream(true).start()
        val output = StringBuilder()
        val outputReader = Thread {
            val current = StringBuilder()
            val buffer = ByteArray(4096)
            while (true) {
                val count = process.inputStream.read(buffer)
                if (count < 0) break
                for (index in 0 until count) {
                    val value = buffer[index].toInt() and 0xff
                    if (value == '\r'.code || value == '\n'.code) {
                        if (current.isNotEmpty()) {
                            val line = current.toString()
                            current.setLength(0)
                            synchronized(output) { output.append(line).append('\n') }
                            Log.i(TAG, "payload_extract: $line")
                        }
                    } else {
                        current.append(value.toChar())
                    }
                }
            }
            if (current.isNotEmpty()) {
                val line = current.toString()
                synchronized(output) { output.append(line).append('\n') }
            }
        }.apply { isDaemon = true; start() }
        var lastObservedBytes = 0L
        var lastObservedImages = 0
        while (process.isAlive) {
            val files = extracted.walkTopDown().filter { it.isFile && it.extension.equals("img", true) }.toList()
            val writtenBytes = files.sumOf { it.length() }
            if (writtenBytes != lastObservedBytes || files.size != lastObservedImages) {
                lastObservedBytes = writtenBytes
                lastObservedImages = files.size
            }
            onProgress(-1, "Extracting partitions: generated ${files.size}  images，written  ${formatBytes(writtenBytes)}")
            Thread.sleep(500)
        }
        outputReader.join(2000)
        val exitCode = process.waitFor()
        val outputText = synchronized(output) { output.toString() }
        if (exitCode != 0) throw IllegalStateException("payload_extract failed (exit code $exitCode）: ${outputText.takeLast(1200)}")
        val images = extracted.listFiles()?.filter { it.isFile && it.extension.equals("img", true) }.orEmpty()
        if (images.isEmpty()) throw IllegalStateException("payload_extract produced no images: ${outputText.takeLast(1200)}")
        onProgress(100, "Partition extraction complete; generated ${images.size}  images")
        return OtaExtractionResult(
            root = root,
            containsPayload = true,
            payloadPartitionCount = images.size,
            payloadPath = input.absolutePath,
            payloadSize = input.length(),
        )
    }

    private fun copyWithProgress(
        source: java.io.InputStream,
        destination: File,
        totalBytes: Long?,
        onProgress: (Int) -> Unit,
    ) {
        destination.outputStream().buffered().use { output ->
            val buffer = ByteArray(1024 * 1024)
            var copied = 0L
            var lastPercent = -1
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                copied += count
                val percent = totalBytes?.let { (copied * 100 / it).toInt().coerceIn(0, 100) } ?: 0
                if (percent != lastPercent) {
                    lastPercent = percent
                    onProgress(percent)
                }
            }
        }
    }

    private fun emitNativeProgress(line: String, onProgress: (Int, String) -> Unit) {
        val cleanLine = line.replace(Regex("\\u001B\\[[;\\d]*[ -/]*[@-~]"), "")
        val percent = Regex("(\\d{1,3})\\s*%")
            .find(cleanLine)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?.coerceIn(0, 100)
            ?: -1
        if (percent >= 0) {
            onProgress(20 + percent * 80 / 100, cleanLine.trim())
        }
    }

    private fun formatBytes(value: Long): String {
        if (value < 1024) return "$value B"
        if (value < 1024 * 1024) return "${value / 1024} KB"
        return "${value / (1024 * 1024)} MB"
    }

    data class OtaExtractionResult(
        val root: File,
        val containsPayload: Boolean,
        val payloadPartitionCount: Int,
        val payloadPath: String?,
        val payloadSize: Long,
    )

    fun listExtractedImages(root: File): List<ImageInfo> {
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && it.extension.equals("img", ignoreCase = true) }
            .map { file ->
                val partition = file.nameWithoutExtension
                ImageInfo(partition, null, file, file.name, file.length(), "", isHighRisk(partition))
            }
            .sortedBy { it.partition }
            .toList()
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            var count: Int
            while (input.read(buffer).also { count = it } >= 0) {
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun deleteOtaDirectory(context: Context) {
        File(context.filesDir, "ota").deleteRecursively()
    }

    fun sha256(context: Context, uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(1024 * 1024)
            var count: Int
            while (input.read(buffer).also { count = it } >= 0) {
                if (count > 0) digest.update(buffer, 0, count)
            }
        } ?: throw IllegalStateException("Unable to read image file")
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun copyToCache(context: Context, uri: Uri, name: String): File {
        val dir = File(context.cacheDir, "flash-images").apply { mkdirs() }
        val file = File(dir, "${System.currentTimeMillis()}-$name")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(file).use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Unable to read image file")
        return file
    }

    /** 内置文件选择器（RootfsFilesActivity）返回真实路径时，从本地文件复制进缓存目录 */
    fun copyLocalFileToCache(context: Context, source: File, name: String): File {
        if (!source.isFile) throw IllegalStateException("Source file not found: ${source.absolutePath}")
        val dir = File(context.cacheDir, "flash-images").apply { mkdirs() }
        val file = File(dir, "${System.currentTimeMillis()}-$name")
        source.copyTo(file, overwrite = false)
        return file
    }

    /** 内置文件选择器（RootfsFilesActivity）返回真实路径时，从本地文件解包 OTA */
    fun extractOtaPackage(
        context: Context,
        sourceFile: File,
        onProgress: (Int, String) -> Unit = { _, _ -> },
    ): OtaExtractionResult {
        if (!sourceFile.isFile) throw IllegalStateException("Source file not found: ${sourceFile.absolutePath}")
        val root = File(context.filesDir, "ota")
        root.deleteRecursively()
        if (!root.mkdirs() && !root.isDirectory) throw IllegalStateException("Unable to create private OTA directory")
        val payload = File(root, "payload.bin")
        // 先读取源文件头部判断是 ZIP 还是裸 payload，再抽取到私有目录的 payload.bin
        sourceFile.inputStream().use { source ->
            val buffered = source.buffered()
            buffered.mark(4)
            val signature = ByteArray(4)
            val signatureSize = buffered.read(signature)
            buffered.reset()
            if (signatureSize == 4 && signature.contentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04))) {
                ZipInputStream(buffered).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null && !entry.name.equals("payload.bin", ignoreCase = true)) {
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                    if (entry == null) throw IllegalStateException("payload.bin not found in ZIP")
                    copyWithProgress(zip, payload, sourceFile.length()) { percent ->
                        onProgress(percent * 20 / 100, "Reading payload.bin from ZIP...")
                    }
                }
            } else {
                copyWithProgress(buffered, payload, sourceFile.length()) { percent ->
                    onProgress(percent * 20 / 100, "Reading BIN file...")
                }
            }
        }
        return runPayloadExtract(context, payload, onProgress)
    }

    private fun runPayloadExtract(
        context: Context,
        payload: File,
        onProgress: (Int, String) -> Unit,
    ): OtaExtractionResult {
        onProgress(20, "Input file read; starting partition extraction...")
        val extracted = File(File(context.filesDir, "ota"), "extracted").apply { mkdirs() }
        val extractor = File(context.applicationInfo.nativeLibraryDir, "libpayload_extract.so")
        if (!extractor.isFile || !extractor.canExecute()) {
            throw IllegalStateException("payload_extract is not executable: ${extractor.absolutePath}")
        }
        val process = ProcessBuilder(
            extractor.absolutePath,
            payload.absolutePath,
            "--out",
            extracted.absolutePath,
            "--no-verify",
        ).redirectErrorStream(true).start()
        val output = StringBuilder()
        val outputReader = Thread {
            val current = StringBuilder()
            val buffer = ByteArray(4096)
            while (true) {
                val count = process.inputStream.read(buffer)
                if (count < 0) break
                for (index in 0 until count) {
                    val value = buffer[index].toInt() and 0xff
                    if (value == '\r'.code || value == '\n'.code) {
                        if (current.isNotEmpty()) {
                            val line = current.toString()
                            current.setLength(0)
                            synchronized(output) { output.append(line).append('\n') }
                        }
                    } else {
                        current.append(value.toChar())
                    }
                }
            }
            if (current.isNotEmpty()) {
                synchronized(output) { output.append(current).append('\n') }
            }
        }.apply { isDaemon = true; start() }
        var lastObservedBytes = 0L
        var lastObservedImages = 0
        while (process.isAlive) {
            val files = extracted.walkTopDown().filter { it.isFile && it.extension.equals("img", true) }.toList()
            val writtenBytes = files.sumOf { it.length() }
            if (writtenBytes != lastObservedBytes || files.size != lastObservedImages) {
                lastObservedBytes = writtenBytes
                lastObservedImages = files.size
            }
            onProgress(-1, "Extracting partitions: generated ${files.size}  images，written  ${formatBytes(writtenBytes)}")
            Thread.sleep(500)
        }
        outputReader.join(2000)
        val exitCode = process.waitFor()
        val outputText = synchronized(output) { output.toString() }
        if (exitCode != 0) throw IllegalStateException("payload_extract failed (exit code $exitCode）: ${outputText.takeLast(1200)}")
        val images = extracted.listFiles()?.filter { it.isFile && it.extension.equals("img", true) }.orEmpty()
        if (images.isEmpty()) throw IllegalStateException("payload_extract produced no images: ${outputText.takeLast(1200)}")
        onProgress(100, "Partition extraction complete; generated ${images.size}  images")
        return OtaExtractionResult(
            root = File(context.filesDir, "ota"),
            containsPayload = true,
            payloadPartitionCount = images.size,
            payloadPath = payload.absolutePath,
            payloadSize = payload.length(),
        )
    }

    private fun isHighRisk(partition: String): Boolean {
        return partition.lowercase() in setOf("bootloader", "radio", "modem", "abl", "xbl", "tz")
    }
}
