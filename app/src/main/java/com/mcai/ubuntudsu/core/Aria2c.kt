package com.mcai.ubuntudsu.core

import android.content.Context
import android.os.Environment
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

// aria2c 多线程直链下载封装：
// - 优先使用 app 内置二进制（jniLibs: libaria2c.so，免 root）
// - 其次使用 root 环境 PATH 中的 aria2c
// - 写入公共目录（/storage/emulated/0/...）且 app 无权限时自动经 su 以 root 运行
object Aria2c {
    // 多连接数：直链大文件满速下载
    private const val CONNECTIONS = 16
    // 进度长时间无变化视为线路假死（GitHub 资产域名在国内常被静默丢包），及时失败以便切换备用线路
    private const val STALL_TIMEOUT_MS = 45_000L
    // 单次尝试硬上限，避免任何异常情况下界面永久卡在下载中
    private const val OVERALL_TIMEOUT_MS = 15 * 60_000L
    private val progressRegex = Regex("""\(([0-9]{1,3})%\)""")
    val progressPattern = progressRegex
    // summary 中已下载字节片段（如 "1.0MiB/"）：服务器无 Content-Length 时据此与预期大小计算百分比
    private val byteRegex = Regex("""\s([0-9]+(?:\.[0-9]+)?)(B|KiB|MiB|GiB)/""")
    private val byteUnits = mapOf("B" to 1L, "KiB" to 1024L, "MiB" to 1048576L, "GiB" to 1073741824L)
    // summary 行下载速度片段（如 "DL:5.2MiB"）：解析后换算为用户熟悉的 KB/s、MB/s
    private val dlRegex = Regex("""DL:([0-9]+(?:\.[0-9]+)?)(B|KiB|MiB|GiB)""")

    data class Result(
        val success: Boolean,
        val file: File?,
        val message: String,
    )

    // rootfs / 直链下载默认保存目录：/storage/emulated/0/Downloads/Aria2c downloads
    fun defaultSaveDir(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "Aria2c downloads",
    )

    // 从 URL 推断文件名：优先 HTTP 响应的 Content-Disposition，回退 URL 末段
    fun fileNameFromUrl(ctx: Context?, url: String): String {
        // 1) 尝试从响应头 Content-Disposition 取
        var fromHeader: String? = null
        runCatching {
            val conn = java.net.URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android)")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            val cd = conn.getHeaderField("Content-Disposition")
            if (cd != null) {
                val m = Regex("""filename\*?="?(?:;)?([^";]+)""", RegexOption.IGNORE_CASE).find(cd)
                    ?: Regex("""filename="?([^";]+)"?""", RegexOption.IGNORE_CASE).find(cd)
                m?.groupValues?.get(1)?.let {
                    fromHeader = runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
                }
            }
            conn.disconnect()
        }
        if (fromHeader != null && fromHeader.isNotBlank()) return sanitizeFileName(fromHeader)
        // 2) 回退：URL 末段（去 query/fragment，解码，清洗非法字符）
        val raw = url.substringBefore('#').substringBefore('?').trimEnd('/').substringAfterLast('/')
        val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        return sanitizeFileName(decoded)
    }

    // 兼容旧调用：仅从 URL 字符串推断（不发请求）
    fun fileNameFromUrl(url: String): String = fileNameFromUrl(null, url)

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifBlank { "aria2_${System.currentTimeMillis()}" }

    // 探测可用的 aria2c：
    // 1) nativeLibraryDir 已解压的 libaria2c.so（extractNativeLibs=true 时存在）
    // 2) APK 内嵌的 lib/arm64-v8a/libaria2c.so 运行时解压到 filesDir（useLegacyPackaging=false 时 so 不落盘，必须解压）
    // 3) root 环境 PATH 中的 aria2c（仅在无内置二进制时探测；结果缓存，避免每次启动/继续都阻塞等 su）
    @Volatile private var suAria2Path: String? = null
    @Volatile private var suAria2Probed = false

    fun candidates(ctx: Context): List<String> {
        val list = mutableListOf<String>()
        runCatching {
            val so = File(ctx.applicationInfo.nativeLibraryDir, "libaria2c.so")
            if (so.isFile) list.add(so.absolutePath)
        }
        runCatching {
            val extracted = extractBundled(ctx)
            if (extracted != null) list.add(extracted.absolutePath)
        }
        // 内置二进制可用时完全不碰 su：su 未授权/弹窗确认时探测会阻塞至超时（~10s），
        // 这正是「点继续后 10 秒才动」的来源
        if (list.isNotEmpty()) return list.distinct()
        if (!suAria2Probed) {
            suAria2Probed = true
            runCatching {
                if (RootShell.available()) {
                    suAria2Path = RootShell.exec("command -v aria2c", timeoutMs = 10000)
                        .stdout.trim().lineSequence()
                        .firstOrNull { it.isNotBlank() && it.startsWith("/") }
                }
            }
        }
        suAria2Path?.let { list.add(it) }
        return list.distinct()
    }

    // 从 APK 内解压 aria2c 到 filesDir/aria2c/aria2c 并赋予执行权限
    // useLegacyPackaging=false 时 so 不解压落盘，nativeLibraryDir 下无文件，必须从 APK zip 读取
    // 首次解压后缓存复用；APK 更新（版本变化）时自动重新解压
    private fun extractBundled(ctx: Context): File? {
        val outDir = File(ctx.filesDir, "aria2c")
        if (!outDir.exists() && !outDir.mkdirs()) return null
        val bin = File(outDir, "aria2c")
        val versionCode = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode
        }.getOrDefault(0)
        val marker = File(outDir, ".v$versionCode")
        if (bin.isFile && bin.canExecute() && marker.isFile) return bin

        return try {
            val apkPath = ctx.applicationInfo.sourceDir
            java.util.zip.ZipFile(apkPath).use { zip ->
                val entry = zip.entries().asSequence()
                    .firstOrNull { it.name == "lib/arm64-v8a/libaria2c.so" }
                    ?: return null
                val tmp = File(outDir, "aria2c.tmp")
                zip.getInputStream(entry).use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
                }
                if (!tmp.setExecutable(true, false)) {
                    runCatching { RootShell.exec("chmod 755 '${tmp.absolutePath}'", timeoutMs = 10000) }
                }
                if (!tmp.renameTo(bin)) return null
            }
            // 清理旧版本标记（保留当前）
            outDir.listFiles()?.forEach {
                if (it.name.startsWith(".v") && it.name != ".v$versionCode") it.delete()
            }
            marker.createNewFile()
            bin
        } catch (e: Exception) {
            null
        }
    }

    fun available(ctx: Context): Boolean = candidates(ctx).isNotEmpty()

    // 下载直链到 target；forceRoot=true 时强制经 su 运行（用于 app 无权限的公共目录）
    // onLog 回调实时输出 aria2c 的 summary 行（下载速度/ETA 等诊断信息）
    fun download(
        ctx: Context,
        url: String,
        target: File,
        onProgress: (Int) -> Unit,
        forceRoot: Boolean = false,
        isCancelled: () -> Boolean = { false },
        onLog: ((String) -> Unit)? = null,
        expectedSize: Long = -1L,
        referer: String? = null,
        userAgent: String? = null,
        onStats: ((speedText: String) -> Unit)? = null,
        stallTimeoutMs: Long = STALL_TIMEOUT_MS,
    ): Result {
        val dir = target.parentFile ?: return Result(false, null, "Invalid save path")
        // 目录准备：app 可写则直建，否则经 root 创建
        val appCanWrite = ensureAppDir(dir)
        if (!appCanWrite) {
            runCatching { RootShell.exec("mkdir -p '${dir.absolutePath}'", timeoutMs = 15000) }
        }

        val binaries = candidates(ctx)
        if (binaries.isEmpty()) {
            return Result(false, null, "No usable aria2c found (built-in component missing and not installed on the system)")
        }
        onLog?.invoke("使用 ${binaries.first()}")

        var lastError = "Download failed"
        val tried = mutableSetOf<String>()
        for (binary in binaries) {
            val appBinary = isAppBinary(binary)
            // app 内置且 app 有写权限：先免 root 直跑；其余/失败后经 su 以 root 兜底
            val plans = mutableListOf<Boolean>()
            if (!forceRoot && appCanWrite && appBinary) plans.add(false)
            plans.add(true)
            for (useRoot in plans) {
                if (!tried.add("$binary|$useRoot")) continue
                val result = runOnce(
                    ctx, binary, useRoot, url, target, onProgress, isCancelled, onLog,
                    expectedSize, referer, userAgent, onStats, stallTimeoutMs,
                )
                if (result.success) return result
                if (result.message == "Cancelled") return result
                lastError = result.message
                onLog?.invoke("尝试失败（${if (useRoot) "root" else "直跑"}）：${result.message.take(120)}")
                // 网络类故障与运行身份/二进制无关，立即交给外层换线路，避免重复等待同一坏链路
                if (result.message.startsWith("网络无进展") || result.message.startsWith("下载超时")) return result
            }
        }
        return Result(false, null, lastError)
    }

    private fun runOnce(
        ctx: Context,
        binary: String,
        useRoot: Boolean,
        url: String,
        target: File,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean,
        onLog: ((String) -> Unit)? = null,
        expectedSize: Long = -1L,
        referer: String? = null,
        userAgent: String? = null,
        onStats: ((speedText: String) -> Unit)? = null,
        stallTimeoutMs: Long,
    ): Result {
        val dir = target.parentFile?.absolutePath ?: return Result(false, null, "Invalid save path")
        if (isCancelled()) return Result(false, null, "Cancelled")
        // URL/文件名清洗：屏幕 OCR/粘贴常混入空格与换行，aria2c 对畸形 URL 会静默 0B 退出
        val cleanUrl = url.replace(Regex("\\s+"), "")
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            return Result(false, null, "Invalid URL (after cleanup: $cleanUrl）")
        }
        // aria2c 日志放 app 私有目录（任何运行身份都经 ctx 可写），失败时读取首条 ERROR。
        // 按目标文件名隔离：多任务并行时共用一个 session.log 会互相删除/串写
        // （任务B启动会删掉任务A正在写的日志，A失败时读到的却是B的错误）
        val logDir = File(ctx.filesDir, "aria2c")
        if (!logDir.exists()) runCatching { logDir.mkdirs() }
        val logFile = File(logDir, "session-${Integer.toHexString(target.name.hashCode())}.log")
        runCatching { logFile.delete() }
        val args = mutableListOf(
            "--no-conf=true",
            "--log=${logFile.absolutePath}", "--log-level=notice",
            "--allow-overwrite=true", "--auto-file-renaming=false", "--continue=true",
            "--max-connection-per-server=$CONNECTIONS", "--split=$CONNECTIONS", "--min-split-size=1M",
            "--file-allocation=none", "--summary-interval=1",
            "--connect-timeout=60", "--timeout=120",
            "--max-tries=3", "--retry-wait=3",
        )
        if (userAgent != null) {
            args.add("--user-agent=$userAgent")
        } else {
            args.add("--user-agent=Mozilla/5.0 (Linux; Android) AppleWebKit/537.36")
        }
        if (referer != null) {
            args.add("--referer=$referer")
        }
        args.addAll(listOf(
            "--dir=$dir", "--out=${target.name}",
            cleanUrl,
        ))
        return try {
            val process = when {
                useRoot -> {
                    // root shell 环境相对干净，直接经 su -c 执行（与 RootShell.exec 同款写法）
                    ProcessBuilder("su", "-c", shellCommand(binary, args))
                        .redirectErrorStream(true).start()
                }
                else -> {
                    // 与 OtgAssistantCore 调 libpayload_extract.so 的写法保持一致：
                    // 直接 ProcessBuilder(so 路径, args)，让 Android ELF loader 走动态链接器。
                    // 此前套 env -i / sh -c 'exec' 反而破坏了可执行路径与参数解析。
                    ProcessBuilder(binary, *args.toTypedArray())
                        .redirectErrorStream(true).start()
                }
            }
            val lastLine = AtomicReference("")
            val lastBytes = AtomicLong(0L)
            val lastPercent = AtomicInteger(-1)
            val lastProgressAt = AtomicLong(System.currentTimeMillis())
            val startedAt = System.currentTimeMillis()
            val reader = Thread {
                runCatching {
                    BufferedReader(InputStreamReader(process.inputStream)).forEachLine { line ->
                        if (line.isNotBlank()) {
                            lastLine.set(line.trim())
                            // summary 行含速度/连接数，实时回传界面，避免看起来像卡死
                            if (onLog != null && line.contains("CN:")) onLog(line.trim())
                            // 解析 DL: 速度片段回传界面时速显示（summary-interval=1s，每秒一条）
                            if (onStats != null && line.contains("DL:")) {
                                dlRegex.find(line)?.let { m ->
                                    val value = m.groupValues[1].toDoubleOrNull() ?: 0.0
                                    val text = when (m.groupValues[2]) {
                                        "GiB" -> String.format(java.util.Locale.US, "%.2f GB/s", value * 1024)
                                        "MiB" -> String.format(java.util.Locale.US, "%.1f MB/s", value)
                                        "KiB" -> String.format(java.util.Locale.US, "%.1f KB/s", value)
                                        else -> String.format(java.util.Locale.US, "%d B/s", value.toLong())
                                    }
                                    onStats(text)
                                }
                            }
                        }
                        // 已下载字节：直链被墙时空转行（0B/0B）不会变化，只有真实数据才推进看门狗与进度
                        val bytesNow = byteRegex.find(line)?.let { m ->
                            val value = m.groupValues[1].toDoubleOrNull() ?: 0.0
                            (value * (byteUnits[m.groupValues[2]] ?: 0L)).toLong()
                        }
                        if (bytesNow != null && bytesNow != lastBytes.get()) {
                            lastBytes.set(bytesNow)
                            lastProgressAt.set(System.currentTimeMillis())
                        }
                        // 百分比优先取 (N%)；无 Content-Length 时回退为已下载字节 / 预期大小
                        val percent = progressRegex.find(line)?.groupValues?.get(1)?.toIntOrNull()
                            ?: bytesNow?.let { bytes ->
                                if (expectedSize <= 0) null else ((bytes * 100 / expectedSize).toInt()).coerceIn(0, 100)
                            }
                        if (percent != null) {
                            if (percent != lastPercent.get()) {
                                lastPercent.set(percent)
                                lastProgressAt.set(System.currentTimeMillis())
                            }
                            // 连接阶段（0 字节）不推进百分比，界面保持"连接中"状态
                            if (percent > 0 || (bytesNow ?: 0L) > 0L) onProgress(percent.coerceIn(0, 100))
                        }
                    }
                }
            }.apply { isDaemon = true; start() }

            while (true) {
                // 200ms 轮询：暂停/取消指令最多 0.2 秒内被感知并杀掉进程（原 1 秒粒度是"点暂停迟迟不停"的来源之一）
                if (process.waitFor(200, TimeUnit.MILLISECONDS)) break
                if (isCancelled()) {
                    process.destroyForcibly()
                    reader.join(300)
                    return Result(false, null, "Cancelled")
                }
                val now = System.currentTimeMillis()
                if (now - startedAt > OVERALL_TIMEOUT_MS) {
                    process.destroyForcibly()
                    reader.join(300)
                    return Result(false, null, "Download timed out (over 15 minutes)")
                }
                if (now - lastProgressAt.get() > stallTimeoutMs.coerceAtLeast(1000L)) {
                    process.destroyForcibly()
                    reader.join(300)
                    return Result(false, null, "No network progress (${stallTimeoutMs / 1000}s without data); switching route and retrying")
                }
            }
            reader.join(2000)
            val code = process.exitValue()
            if (code == 0 && target.isFile && target.length() > 0) {
                runCatching { controlFile(target).delete() }
                runCatching { logFile.delete() }
                onProgress(100)
                Result(true, target, "Completed")
            } else {
                // 区分进程根本没起来（code 非 0 且无任何输出/文件未生成）vs 网络失败
                val detail = readAria2Error(logFile)
                if (detail == null && lastLine.get().isEmpty() && !target.exists()) {
                    Result(false, null, "Built-in aria2c did not start (exit code $code); retry or use root")
                } else {
                    Result(false, null, detail ?: lastLine.get().ifBlank { "aria2c 退出码 $code" })
                }
            }
        } catch (e: Exception) {
            Result(false, null, e.message ?: "aria2c execution failed")
        } finally {
            runCatching { logFile.delete() }
        }
    }

    // 从 aria2c log 文件提取真实失败原因（errorCode 描述行优先，其次首条 ERROR）
    private fun readAria2Error(logFile: File): String? = runCatching {
        if (!logFile.isFile) return@runCatching null
        val lines = logFile.readLines().map { it.trim() }
        // 优先取人话描述：errorCode=N <描述>，如 "errorCode=3 Resource not found"
        val codeDesc = lines.mapNotNull { line ->
            Regex("""errorCode=\d+ (.+)$""").find(line)?.groupValues?.get(1)?.trim()
        }.firstOrNull { it.isNotBlank() }
        if (codeDesc != null) {
            // errorCode=1 的描述只有 URI=...，对用户无意义，转成连接类失败描述
            val clean = if (codeDesc.startsWith("URI=")) "Connection failed or timed out" else codeDesc
            return@runCatching clean.take(160)
        }
        val errorLine = lines.firstOrNull {
            it.startsWith("ERROR") || it.startsWith("[ERROR]") || it.contains("Exception")
        }
        // 去掉 "2026-09-14 20:27:51.292541 [ERROR] [AbstractCommand.cc:349]" 时间戳/位置前缀
        errorLine?.replace(Regex("""^\[?\d{4}-\d{2}-\d{2} [\d:.]+\]?\s*"""), "")
            ?.replace(Regex("""\[(ERROR|NOTICE)\] \[[^\]]+\] ?"""), "")
            ?.take(160)
    }.getOrNull()

    // su -c 命令拼接：二进制与参数统一单引号转义，防注入
    private fun shellCommand(binary: String, args: List<String>): String = buildString {
        append('"').append(binary).append('"')
        args.forEach { arg -> append(" '").append(arg.replace("'", "'\\''")).append("'") }
    }

    private fun controlFile(target: File): File = File(target.parentFile, "${target.name}.aria2")

    private fun isAppBinary(binary: String): Boolean =
        binary.startsWith("/data/app/") || binary.startsWith("/data/data/")

    // 尝试 app 侧创建并写入目录；返回 app 是否可直接写入
    // 注意：File.canWrite() 在 FUSE 挂载的公共目录上可能误报（返回 true 但实际写入被拒，
    // 或反之），必须以「实际创建并删除测试文件」为准——误判直接导致只剩 su 计划、无 root 时下载全灭
    private fun ensureAppDir(dir: File): Boolean {
        fun writeTest(): Boolean = runCatching {
            val t = File(dir, ".aria2_write_test")
            t.createNewFile()
            t.delete()
            true
        }.getOrDefault(false)
        if (!dir.exists()) runCatching { dir.mkdirs() }
        if (!dir.isDirectory) return false
        if (dir.canWrite() && writeTest()) return true
        return writeTest()
    }
}
