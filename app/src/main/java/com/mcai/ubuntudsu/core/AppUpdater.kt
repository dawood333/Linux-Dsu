package com.mcai.ubuntudsu.core

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

// 在线更新：GitHub Release API + 可用代理 API 兜底；下载时测速择优并校验资产摘要
object AppUpdater {
    // Gitee 镜像仓库目前不存在；国内网络通过已用于 ROM 工具链的 gh-proxy.com 代理 API 兜底。
    private const val GITHUB_API = "https://api.github.com/repos/hetianming/Linux-Dsu/releases/latest"
    private const val GH_PROXY_PREFIX = "https://gh-proxy.com/"
    private val RELEASE_APIS = listOf(GITHUB_API, GH_PROXY_PREFIX + GITHUB_API)
    private const val API_TIMEOUT_MS = 7000
    private const val API_DEADLINE_MS = 10000L
    private const val API_RESULT_GRACE_MS = 1200L

    data class ReleaseInfo(
        val version: String,        // tag 名，如 v1.0.3
        val notes: String,          // release 说明
        val apkUrl: String,         // apk 下载直链
        val apkName: String,        // apk 文件名
        val apkSize: Long,          // apk 大小（字节）
        val apkSha256: String = "", // GitHub Release asset digest（缺省时跳过摘要校验）
    )

    // 并行查询 GitHub API 与已验证可用的代理 API；某一源先成功后给另一个源短暂补充时间。
    // 用 completion queue + 总截止时间，避免串行 join 超时或遗留后台线程造成误报“无更新”。
    fun fetchLatest(): ReleaseInfo? {
        val executor = Executors.newFixedThreadPool(RELEASE_APIS.size)
        val completion = ExecutorCompletionService<ReleaseInfo?>(executor)
        val requests: List<Future<ReleaseInfo?>> = RELEASE_APIS.map { apiUrl ->
            completion.submit(Callable { fetchFrom(apiUrl) })
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(API_DEADLINE_MS)
        var best: ReleaseInfo? = null
        var firstSuccessAt = 0L
        try {
            while (true) {
                val until = if (firstSuccessAt == 0L) deadline else minOf(
                    deadline,
                    firstSuccessAt + TimeUnit.MILLISECONDS.toNanos(API_RESULT_GRACE_MS),
                )
                val remaining = until - System.nanoTime()
                if (remaining <= 0L) break
                val future = completion.poll(remaining, TimeUnit.NANOSECONDS) ?: break
                val candidate = runCatching { future.get() }.getOrNull() ?: continue
                val current = best
                if (current == null || isNewer(current.version, candidate.version)) best = candidate
                if (firstSuccessAt == 0L) firstSuccessAt = System.nanoTime()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            requests.forEach { if (!it.isDone) it.cancel(true) }
            executor.shutdownNow()
        }
        return best
    }

    // 拉取单个 GitHub-compatible latest Release API；保留大小与 SHA-256，供下载后完整性校验。
    private fun fetchFrom(apiUrl: String): ReleaseInfo? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(apiUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = API_TIMEOUT_MS
            connection.readTimeout = API_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "UbuntuDSU-Updater")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.connect()
            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val json = JSONObject(body)
            val version = json.optString("tag_name").removePrefix("v")
            if (version.isBlank() || version == "null") return null
            val assets = json.optJSONArray("assets") ?: return null
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                val name = asset.optString("name")
                if (name.endsWith(".apk", true)) {
                    val apkUrl = asset.optString("browser_download_url")
                    if (!apkUrl.startsWith("https://")) continue
                    val rawDigest = asset.optString("digest").removePrefix("sha256:")
                        .lowercase(java.util.Locale.ROOT)
                    return ReleaseInfo(
                        version = version,
                        notes = json.optString("body"),
                        apkUrl = apkUrl,
                        apkName = name,
                        apkSize = asset.optLong("size", -1L),
                        apkSha256 = rawDigest.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: "",
                    )
                }
            }
            null
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    // 本地版本是否落后于线上版本（按 x.y.z 逐段比较）
    fun isNewer(local: String, remote: String): Boolean {
        val lhs = local.split('.').map { it.toIntOrNull() ?: 0 }
        val rhs = remote.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(lhs.size, rhs.size)) {
            val a = lhs.getOrElse(i) { 0 }
            val b = rhs.getOrElse(i) { 0 }
            if (b > a) return true
            if (b < a) return false
        }
        return false
    }

    // 与 ROM 工具链保持一致：删除已失效的 mirror.ghproxy.com / kkgithub，补入 gh-proxy.com。
    // 下载前用 Range 小样本并行测速，优先选当前网络最快且返回大小匹配的线路。
    private val gitHubMirrors = listOf(
        GH_PROXY_PREFIX,
        "https://ghfast.top/",
        "https://ghproxy.net/",
    )

    // GitHub 资产保留原始直链，并追加下载镜像；实际尝试顺序由并行测速决定。
    fun urlCandidates(apkUrl: String): List<String> {
        val list = mutableListOf(apkUrl)
        if (apkUrl.startsWith("https://github.com/")) {
            gitHubMirrors.forEach { list.add(it + apkUrl) }
        }
        return list
    }

    private const val ROUTE_PROBE_TIMEOUT_MS = 3000
    private const val ROUTE_PROBE_BYTES = 128 * 1024

    private fun orderCandidates(
        urls: List<String>,
        expectedSize: Long,
        isCancelled: () -> Boolean,
        onLog: ((String) -> Unit)?,
    ): List<String> {
        if (urls.size <= 1 || isCancelled()) return urls
        onLog?.invoke("Testing ${urls.size} update routes")
        val executor = Executors.newFixedThreadPool(urls.size)
        val probes = urls.map { url -> url to executor.submit(Callable { probeRoute(url, expectedSize, isCancelled) }) }
        val speeds = LinkedHashMap<String, Long>()
        try {
            probes.forEach { (url, future) ->
                speeds[url] = runCatching { future.get(ROUTE_PROBE_TIMEOUT_MS + 1000L, TimeUnit.MILLISECONDS) }
                    .getOrDefault(0L)
            }
        } finally {
            probes.forEach { (_, future) -> if (!future.isDone) future.cancel(true) }
            executor.shutdownNow()
        }
        if (isCancelled() || speeds.values.all { it <= 0L }) return urls
        val ordered = urls.sortedByDescending { speeds[it] ?: 0L }
        onLog?.invoke("Route speed test results:" + ordered.joinToString(" ") {
            "${routeName(it)} ${(speeds[it] ?: 0L) / 1024}KB/s"
        })
        return ordered
    }

    private fun probeRoute(url: String, expectedSize: Long, isCancelled: () -> Boolean): Long {
        var connection: HttpURLConnection? = null
        return try {
            if (isCancelled()) return 0L
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = ROUTE_PROBE_TIMEOUT_MS
            connection.readTimeout = ROUTE_PROBE_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "UbuntuDSU-Updater")
            connection.setRequestProperty("Range", "bytes=0-${ROUTE_PROBE_BYTES - 1}")
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..299) return 0L
            val declaredSize = if (code == HttpURLConnection.HTTP_PARTIAL) {
                Regex("""bytes\s+\d+-\d+/(\d+)""")
                    .find(connection.getHeaderField("Content-Range") ?: "")
                    ?.groupValues?.get(1)?.toLongOrNull() ?: -1L
            } else connection.contentLengthLong
            if (expectedSize > 0L && declaredSize > 0L && declaredSize != expectedSize) return 0L
            val startedAt = System.nanoTime()
            var bytes = 0L
            val buffer = ByteArray(16 * 1024)
            connection.inputStream.use { input ->
                while (bytes < ROUTE_PROBE_BYTES && !isCancelled()) {
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), ROUTE_PROBE_BYTES - bytes).toInt())
                    if (count < 0) break
                    bytes += count
                }
            }
            val elapsed = (System.nanoTime() - startedAt).coerceAtLeast(1L)
            if (bytes == 0L) 0L else bytes * 1_000_000_000L / elapsed
        } catch (_: Exception) {
            0L
        } finally {
            connection?.disconnect()
        }
    }

    private fun routeName(url: String): String = when {
        url.startsWith(GH_PROXY_PREFIX) -> "gh-proxy.com"
        url.startsWith("https://ghfast.top/") -> "ghfast.top"
        url.startsWith("https://ghproxy.net/") -> "ghproxy.net"
        else -> "Direct"
    }

    // 下载 apk：逐线路尝试 aria2c（多线程提速）→ HttpURLConnection 兜底；校验文件大小和 API digest。
    fun download(
        ctx: Context,
        info: ReleaseInfo,
        targetDir: File,
        onProgress: (Int) -> Unit,
        onLog: ((String) -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
    ): File? {
        val target = File(targetDir, "update-${info.version}.apk")
        if (!targetDir.exists() && !targetDir.mkdirs()) return null
        // GitHub 资产自带 size；若源缺失则用 Range 探测真实大小
        // （供无 Content-Length 的线路按已下载字节计算百分比，并做完整性校验）
        val expectedSize = if (info.apkSize > 0) info.apkSize else probeContentLength(info.apkUrl)
        val candidates = urlCandidates(info.apkUrl)
        val orderedCandidates = orderCandidates(candidates, expectedSize, isCancelled, onLog)
        for ((index, url) in orderedCandidates.withIndex()) {
            if (isCancelled()) return null
            if (index > 0) {
                onLog?.invoke("Current route failed, switching to backup route $index/${orderedCandidates.size - 1}")
                // 跨线路不复用断点文件，避免续传错位导致文件损坏
                runCatching { target.delete() }
                runCatching { File(targetDir, "${target.name}.aria2").delete() }
            }
            onProgress(0)
            onLog?.invoke("线路 ${index + 1}/${orderedCandidates.size} · ${routeName(url)}：开始下载")
            val ariaResult = downloadWithAria2(ctx, url, target, expectedSize, onProgress, onLog, isCancelled)
            if (isCancelled()) return null
            var file = ariaResult.file?.takeIf { ariaResult.success }
            val routeStalled = ariaResult.message.startsWith("No network progress") || ariaResult.message.startsWith("Download timed out")
            if (file == null && ariaResult.message != "Cancelled" && !routeStalled) {
                file = downloadWithHttp(url, target, expectedSize, onProgress, isCancelled)
            }
            if (isCancelled()) return null
            if (file != null && file.isFile && file.length() > 0) {
                val sizeMatches = expectedSize <= 0L || file.length() == expectedSize
                val digestMatches = info.apkSha256.isBlank() ||
                    runCatching { sha256(file).equals(info.apkSha256, ignoreCase = true) }.getOrDefault(false)
                if (sizeMatches && digestMatches) {
                    onProgress(100)
                    return file
                }
                if (!sizeMatches) {
                    onLog?.invoke("File size check failed（${file.length()} / $expectedSize），切换备用线路")
                } else {
                    onLog?.invoke("SHA-256 verification failed; file may be incomplete, switching to backup route")
                }
            }
            runCatching { target.delete() }
            runCatching { File(targetDir, "${target.name}.aria2").delete() }
        }
        return null
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    // Range 0-0 探测真实大小（部分服务器拒绝 HEAD）；最终完整性优先由 Release digest 校验。
    private fun probeContentLength(url: String): Long {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "UbuntuDSU-Updater")
            connection.setRequestProperty("Range", "bytes=0-0")
            connection.connect()
            if (connection.responseCode == 206) {
                Regex("""bytes\s+\d+-\d+/(\d+)""")
                    .find(connection.getHeaderField("Content-Range") ?: "")
                    ?.groupValues?.get(1)?.toLongOrNull() ?: -1L
            } else if (connection.responseCode in 200..299) {
                connection.contentLengthLong
            } else -1L
        } catch (e: Exception) {
            -1L
        } finally {
            connection?.disconnect()
        }
    }

    // aria2c 下载（内置二进制免 root 优先，失败经 root 兜底；目标在 app 私有目录始终可写）
    private fun downloadWithAria2(
        ctx: Context,
        url: String,
        target: File,
        expectedSize: Long,
        onProgress: (Int) -> Unit,
        onLog: ((String) -> Unit)?,
        isCancelled: () -> Boolean,
    ): Aria2c.Result {
        val result = Aria2c.download(
            ctx, url, target, onProgress,
            isCancelled = isCancelled,
            onLog = onLog,
            expectedSize = expectedSize,
            stallTimeoutMs = 12000L,
        )
        if (!result.success) onLog?.invoke("aria2c failed: ${result.message}")
        return result
    }

    // HttpURLConnection 下载（兜底，aria2c 不可用/服务端拒绝分段时）
    private fun downloadWithHttp(
        url: String,
        target: File,
        expectedSize: Long,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean,
    ): File? {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            // 快速放弃被静默丢包的线路，及时切换国内代理；避免单一路线等待 60 秒。
            connection.readTimeout = 15000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "UbuntuDSU-Updater")
            connection.connect()
            if (connection.responseCode !in 200..299) return null
            val total = if (expectedSize > 0) expectedSize else connection.contentLengthLong
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var done = 0L
                    var lastPercent = -1
                    while (true) {
                        if (isCancelled()) return null
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            return target
        } catch (e: Exception) {
            runCatching { target.delete() }
            return null
        } finally {
            connection?.disconnect()
        }
    }

    data class InstallResult(val success: Boolean, val message: String)

    // root 静默安装（应用商店式后台安装）：su -c pm install -r；无 root 或失败返回 false + 原因
    // pm 以 root 身份读取 app 私有目录中的 apk 并流式写入安装会话，绕开私目录权限限制
    fun silentInstall(apk: File, timeoutMs: Long = 180000): InstallResult {
        if (!apk.isFile) return InstallResult(false, "Installation package not found")
        val result = runCatching {
            RootShell.exec("pm install -r '${apk.absolutePath}'", timeoutMs = timeoutMs)
        }.getOrNull() ?: return InstallResult(false, "Unable to execute su (root permission not granted)")
        val output = (result.stdout + "\n" + result.stderr).trim()
        val ok = result.success && output.contains("Success", ignoreCase = true)
        if (ok) return InstallResult(true, "Installation successful")
        // 常见失败给出人话提示，便于定位（签名不一致是本项目自构建包覆盖官方包时的典型问题）
        val friendly = when {
            output.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") || output.contains("signatures do not match") ->
                "Signature differs from installed version; uninstall the old version or use the same signing key."
            output.contains("INSTALL_FAILED_VERSION_DOWNGRADE") ->
                "Online package version is not newer; the system rejected the downgrade."
            output.contains("INSTALL_PARSE_FAILED") -> "Package is damaged or incomplete; please download it again."
            else -> output.ifBlank { "安装失败（退出码 ${result.code}）" }
        }
        return InstallResult(false, friendly.take(300))
    }

    // 触发系统安装器安装 apk（Android 7.0+ 经 FileProvider 暴露）
    fun install(activity: Activity, apk: File) {
        val uri = if (Build.VERSION.SDK_INT >= 24) {
            androidx.core.content.FileProvider.getUriForFile(
                activity, "${activity.packageName}.fileprovider", apk,
            )
        } else {
            Uri.fromFile(apk)
        }
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }
}
