package com.mcai.ubuntudsu.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.mcai.ubuntudsu.core.Aria2c
import com.mcai.ubuntudsu.core.DownloadNode
import com.mcai.ubuntudsu.core.JavaDownloader
import com.mcai.ubuntudsu.core.RomApi
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 多任务并发Download前台服务：
 * - 每个任务独立线程 / Pause / Cancel / 通知（互不干扰）
 * - ROM 走 JavaDownloader 多线程分块；自定义链接走内置 aria2c 引擎（libaria2c.so）
 * - aria2c Pause = 终止进程并保留 .aria2 断点文件，Resume = 断点续传
 * - 通知栏小窗口：每任务 [Pause/Resume] [Cancel]，Cancel立即撤下该任务通知
 * - 通过广播实时更新 UI，每条广播携带 task_id
 */
class DownloadService : Service() {

    companion object {
        const val CHANNEL_ID = "rom_download"
        const val NOTIF_ID_BASE = 2001

        const val ACTION_START = "com.mcai.ubuntudsu.START_DOWNLOAD"
        const val ACTION_CANCEL = "com.mcai.ubuntudsu.CANCEL_DOWNLOAD"
        const val ACTION_PAUSE = "com.mcai.ubuntudsu.PAUSE_DOWNLOAD"
        const val ACTION_RESUME = "com.mcai.ubuntudsu.RESUME_DOWNLOAD"

        const val EXTRA_URL = "url"
        const val EXTRA_FILENAME = "filename"
        const val EXTRA_VERSION = "version"
        const val EXTRA_NODE_INDEX = "node_index"
        const val EXTRA_LABEL = "label"
        const val EXTRA_DEVICE_NAME = "device_name"
        const val EXTRA_TASK_ID = "task_id"
        /** 自定义直链：true 走内置 aria2c 引擎 */
        const val EXTRA_USE_ARIA2 = "use_aria2"

        // 广播
        const val BROADCAST_UPDATE = "com.mcai.ubuntudsu.DOWNLOAD_UPDATE"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_STATUS_TEXT = "status_text"
        const val EXTRA_FILE_NAME = "file_name"
        const val EXTRA_DEVICE = "device_name"
        const val EXTRA_LOG = "log"
        const val EXTRA_DONE = "done"
        const val EXTRA_SUCCESS = "success"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_SAVED_PATH = "saved_path"
        const val EXTRA_STATE = "state" // 0=idle 1=downloading 2=done 3=cancelled 4=failed 5=paused
        const val EXTRA_TOTAL_SIZE = "total_size"
        const val EXTRA_DOWNLOADED_SIZE = "downloaded_size"
    }

    /** 单个Download任务的全部独立状态 */
    private inner class TaskCtx(
        val id: String,
        val url: String,
        val fileName: String,
        val label: String,
        val deviceName: String,
        val version: String,
        val nodeIndex: Int,
        val useAria2: Boolean,
    ) {
        val cancelled = AtomicBoolean(false)
        val paused = AtomicBoolean(false)
        @Volatile var progress = 0
        @Volatile var totalSize = 0L
        @Volatile var downloadedSize = 0L
        /** 当前时速文本（如 "3.2 MB/s"），由引擎回调或字节差计算更新 */
        @Volatile var speed = ""
        /** 时速采样：上次统计时间与字节，用于 JavaDownloader 字节差测速 */
        @Volatile var sampleAt = 0L
        @Volatile var sampleBytes = 0L
        /** 该任务通知是否存活（Cancel/完成后撤下，拦截在途回调 re-post） */
        @Volatile var notifActive = true
        /** 终态（成功/失败/Cancel）；Pause不算终态 */
        @Volatile var done = false
        @Volatile var thread: Thread? = null
        val notifId: Int = NOTIF_ID_BASE + (id.hashCode() and 0x7FFF)
    }

    private val tasks = ConcurrentHashMap<String, TaskCtx>()
    private var actionReceiver: android.content.BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        actionReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_PAUSE -> targets(intent).forEach { pauseTask(it) }
                    ACTION_RESUME -> targets(intent).forEach { resumeTask(it) }
                    ACTION_CANCEL -> targets(intent).forEach { cancelTask(it) }
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(ACTION_CANCEL)
            addAction(ACTION_PAUSE)
            addAction(ACTION_RESUME)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(actionReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(actionReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
                val filename = intent.getStringExtra(EXTRA_FILENAME) ?: "download.zip"
                val useAria2 = intent.getBooleanExtra(EXTRA_USE_ARIA2, false)
                val id = filename

                // 去重：同 id 任务仍在运行（含Pause）时忽略重复启动
                val existing = tasks[id]
                if (existing != null && !existing.done) return START_NOT_STICKY

                val task = TaskCtx(
                    id = id,
                    url = url,
                    fileName = filename,
                    label = intent.getStringExtra(EXTRA_LABEL) ?: "Download",
                    deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "",
                    version = intent.getStringExtra(EXTRA_VERSION) ?: "",
                    nodeIndex = intent.getIntExtra(EXTRA_NODE_INDEX, 3),
                    useAria2 = useAria2,
                )
                tasks[id] = task

                // startForegroundService 要求尽快 startForeground：本任务通知即前台通知
                startForeground(task.notifId, buildNotif(task))
                // 其余在途任务的通知补充 post（多任务小窗口并存）
                postOtherNotifs(task)
                launch(task)
            }
            ACTION_PAUSE -> targets(intent).forEach { pauseTask(it) }
            ACTION_RESUME -> targets(intent).forEach { resumeTask(it) }
            ACTION_CANCEL -> targets(intent).forEach { cancelTask(it) }
        }
        return START_NOT_STICKY
    }

    /** 控制指令的目标任务：带 task_id 精确命中；无 id 时作用于全部未终态任务（批量语义） */
    private fun targets(intent: Intent): List<TaskCtx> {
        val id = intent.getStringExtra(EXTRA_TASK_ID)
        if (id != null) return listOfNotNull(tasks[id])
        return tasks.values.filter { !it.done }
    }

    // ==================== 任务执行 ====================

    private fun launch(task: TaskCtx, fromSelf: Boolean = false) {
        // fromSelf：由即将退出的旧线程发起的重启（Resume指令与杀进程竞态），忽略自身存活检查
        if (!fromSelf) {
            task.thread?.let { if (it.isAlive) return }
        }
        task.thread = Thread {
            if (task.useAria2) runAria2(task) else runRom(task)
        }.apply { start() }
    }

    /** ROM Download：RomApi 线路 + JavaDownloader 多线程分块（Pause为原地等待） */
    private fun runRom(task: TaskCtx) {
        broadcastTask(task, 1, 0, "开始Download")
        val nodes = DownloadNode.values()
        val node = nodes.getOrElse(task.nodeIndex) { nodes[3] }
        val downloadUrl = RomApi.getDownloadUrl(task.fileName, task.version, node)
        val targetFile = File(JavaDownloader.defaultSaveDir(), task.fileName)

        val result = JavaDownloader.download(
            this,
            downloadUrl,
            targetFile,
            onProgress = { pct ->
                task.progress = pct
                if (task.notifActive) updateNotif(task)
                broadcastTask(task, 1, pct, "$pct%")
            },
            isCancelled = { task.cancelled.get() },
            isPaused = { task.paused.get() },
            onLog = { log -> broadcastLog(task, log) },
            onSizeInfo = { total, downloaded ->
                task.totalSize = total
                task.downloadedSize = downloaded
                updateSpeedByDelta(task, downloaded)
                broadcastTask(task, 1, task.progress, "${task.progress}%")
            },
        )

        when {
            result.success -> finishTask(task, true, "Download完成", result.file?.absolutePath ?: "")
            task.cancelled.get() -> finishTask(task, false, "Cancelled", "")
            else -> finishTask(task, false, "Download失败: ${result.message}", "")
        }
    }

    /** 按已Download字节差计算时速（JavaDownloader 每 ~1s 回调一次 onSizeInfo） */
    private fun updateSpeedByDelta(task: TaskCtx, downloaded: Long) {
        val now = System.currentTimeMillis()
        if (task.sampleAt == 0L) {
            task.sampleAt = now
            task.sampleBytes = downloaded
            return
        }
        val dt = now - task.sampleAt
        if (dt < 500) return
        val bytesPerSec = (downloaded - task.sampleBytes) * 1000 / dt
        task.sampleAt = now
        task.sampleBytes = downloaded
        task.speed = if (bytesPerSec > 0) formatSpeed(bytesPerSec) else ""
    }

    private fun formatSpeed(bytesPerSec: Long): String = when {
        bytesPerSec >= 1 shl 20 -> String.format(java.util.Locale.US, "%.1f MB/s", bytesPerSec / 1048576.0)
        bytesPerSec >= 1 shl 10 -> String.format(java.util.Locale.US, "%.1f KB/s", bytesPerSec / 1024.0)
        else -> "$bytesPerSec B/s"
    }

    /**
     * 解析保存文件：优先 /sdcard/Downloads（实测可写），不可写时回退应用私有目录。
     * 与 JavaDownloader 的回退策略一致，保证无「所有文件访问权限」时 aria2c 也能直接落盘
     */
    private fun resolveSaveFile(fileName: String): File {
        val dir = JavaDownloader.defaultSaveDir()
        val writable = runCatching {
            if (!dir.exists()) dir.mkdirs()
            dir.isDirectory && dir.canWrite() && run {
                val t = File(dir, ".dl_write_test")
                t.createNewFile(); t.delete(); true
            }
        }.getOrDefault(false)
        if (writable) return File(dir, fileName)
        val fb = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: filesDir
        return File(fb, fileName)
    }

    /** 自定义直链：内置 aria2c 引擎。Pause=杀进程保留断点，Resume=断点续传重启进程 */
    private fun runAria2(task: TaskCtx) {
        broadcastTask(task, 1, task.progress, if (task.progress > 0) "Resuming download" else "开始Download")
        val targetFile = resolveSaveFile(task.fileName)
        if (targetFile.parentFile?.absolutePath != JavaDownloader.defaultSaveDir().absolutePath) {
            broadcastLog(task, "⚠️ /sdcard/Downloads is not writable; saving to: ${targetFile.parent}")
        }

        val result = Aria2c.download(
            this,
            task.url,
            targetFile,
            onProgress = { pct ->
                task.progress = pct
                if (task.notifActive) updateNotif(task)
                broadcastTask(task, 1, pct, "$pct%")
            },
            isCancelled = { task.cancelled.get() || task.paused.get() },
            onLog = { log -> broadcastLog(task, log) },
            onStats = { speedText ->
                // aria2c 每秒回传实测速度，直接刷新界面时速
                task.speed = speedText
                if (task.notifActive) updateNotif(task)
                broadcastTask(task, 1, task.progress, "${task.progress}%")
            },
        )

        when {
            task.cancelled.get() -> finishTask(task, false, "Cancelled", "")
            result.success -> finishTask(task, true, "Download完成", result.file?.absolutePath ?: "")
            result.message == "Cancelled" -> {
                // 进程被主动终止（Pause或Resume竞态）：
                // paused 仍为 true → 真Pause：广播Pause态，断点文件保留；
                // paused 已被置 false → Resume指令与杀进程竞态，立即断点续传重启
                if (task.paused.get()) {
                    broadcastTask(task, 5, task.progress, "Paused")
                    if (task.notifActive) updateNotif(task)
                    promoteForeground()
                } else {
                    launch(task, fromSelf = true)
                }
            }
            else -> {
                // aria2c 全灭（引擎缺失/服务器怪异响应）：降级 JavaDownloader HTTP 引擎兜底
                broadcastLog(task, "aria2c failed: ${result.message.take(80)}，switching to HTTP engine and retrying")
                val httpResult = JavaDownloader.download(
                    this,
                    task.url,
                    targetFile,
                    onProgress = { pct ->
                        task.progress = pct
                        if (task.notifActive) updateNotif(task)
                        broadcastTask(task, 1, pct, "$pct%")
                    },
                    isCancelled = { task.cancelled.get() },
                    isPaused = { task.paused.get() },
                    onLog = { log -> broadcastLog(task, log) },
                    onSizeInfo = { total, downloaded ->
                        task.totalSize = total
                        task.downloadedSize = downloaded
                        updateSpeedByDelta(task, downloaded)
                        broadcastTask(task, 1, task.progress, "${task.progress}%")
                    },
                )
                when {
                    task.cancelled.get() -> finishTask(task, false, "Cancelled", "")
                    httpResult.success -> finishTask(task, true, "Download完成", httpResult.file?.absolutePath ?: "")
                    // HTTP 兜底期间被Pause：线程即将退出，交由 resumeTask 重启（走 aria2c 断点续传）
                    task.paused.get() -> {
                        broadcastTask(task, 5, task.progress, "Paused")
                        if (task.notifActive) updateNotif(task)
                        promoteForeground()
                    }
                    else -> finishTask(task, false, "Download失败: ${httpResult.message}", "")
                }
            }
        }
    }

    // ==================== 控制指令 ====================

    private fun pauseTask(task: TaskCtx) {
        if (task.done) return
        task.paused.set(true)
        // Pause即时清空时速并重置采样基准，避免恢复瞬间出现虚假峰值
        task.speed = ""
        task.sampleAt = 0L
        task.sampleBytes = 0L
        broadcastTask(task, 5, task.progress, "Paused")
        if (task.notifActive) updateNotif(task)
    }

    private fun resumeTask(task: TaskCtx) {
        if (task.done) return
        task.paused.set(false)
        task.speed = ""
        task.sampleAt = 0L
        task.sampleBytes = 0L
        broadcastTask(task, 1, task.progress, "ResumeDownload")
        if (task.notifActive) updateNotif(task)
        // aria2 路径：Pause时线程已随进程退出，这里断点续传重启；
        // JavaDownloader 路径：原地解冻；若线程意外死亡同样重启
        task.thread?.let { if (it.isAlive) return }
        launch(task)
    }

    private fun cancelTask(task: TaskCtx) {
        task.cancelled.set(true)
        task.paused.set(false)
        task.done = true
        // 立即撤下通知并拦住在途回调 re-post
        task.notifActive = false
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(task.notifId)
        broadcastTask(task, 3, 0, "Cancelled")
        broadcastDone(task, false, "Cancelled", "")
        promoteForeground()
    }

    /** 终态收尾：更新通知、广播完成、前台交接、空闲自停（已被Cancel的任务直接跳过，避免双重广播） */
    private fun finishTask(task: TaskCtx, success: Boolean, message: String, savedPath: String) {
        if (task.done) return
        task.done = true
        task.notifActive = false
        task.speed = ""
        val nm = getSystemService(NotificationManager::class.java)
        if (success) {
            task.progress = 100
            if (task.totalSize > 0) task.downloadedSize = task.totalSize
            // 完成通知短暂展示 3 秒后撤下
            nm.notify(task.notifId, buildNotif(task).apply {
                flags = flags and Notification.FLAG_ONGOING_EVENT.inv()
            })
            broadcastTask(task, 2, 100, "Download完成")
            broadcastDone(task, true, message, savedPath)
            Thread {
                Thread.sleep(3000)
                nm.cancel(task.notifId)
                promoteForeground()
            }.start()
        } else {
            nm.cancel(task.notifId)
            broadcastTask(task, 4, task.progress, message)
            broadcastDone(task, false, message, "")
            promoteForeground()
        }
    }

    // ==================== 前台通知管理 ====================

    /** 前台通知交接：取第一个在途任务的通知为前台，其余任务通知补充 post；无在途则停服务 */
    private fun promoteForeground() {
        val active = tasks.values.filter { it.notifActive && !it.done }
        if (active.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        startForeground(active.first().notifId, buildNotif(active.first()))
        val nm = getSystemService(NotificationManager::class.java)
        active.drop(1).forEach { nm.notify(it.notifId, buildNotif(it)) }
    }

    /** 新任务启动时，为其余在途任务补发通知（各自独立小窗口） */
    private fun postOtherNotifs(exclude: TaskCtx) {
        val nm = getSystemService(NotificationManager::class.java)
        tasks.values
            .filter { it !== exclude && it.notifActive && !it.done }
            .forEach { nm.notify(it.notifId, buildNotif(it)) }
    }

    private fun updateNotif(task: TaskCtx) {
        if (!task.notifActive) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(task.notifId, buildNotif(task))
    }

    // ==================== 广播 ====================

    private fun broadcastTask(task: TaskCtx, state: Int, progress: Int, status: String) {
        // Pause已受理（未终态）时丢弃在途的「Download中」进度广播：
        // 进程真正被杀前回调仍在触发，这些广播会把界面/通知翻回Download中，造成"Pause did not take effect; tap twice"的错觉
        if (state == 1 && !task.done && task.paused.get()) return
        val intent = Intent(BROADCAST_UPDATE).apply {
            putExtra(EXTRA_STATE, state)
            putExtra(EXTRA_PROGRESS, progress)
            putExtra(EXTRA_SPEED, if (state == 1) task.speed else "")
            putExtra(EXTRA_STATUS_TEXT, status)
            putExtra(EXTRA_FILE_NAME, task.fileName)
            putExtra(EXTRA_TASK_ID, task.id)
            putExtra(EXTRA_DEVICE, task.deviceName)
            putExtra(EXTRA_TOTAL_SIZE, task.totalSize)
            putExtra(EXTRA_DOWNLOADED_SIZE, task.downloadedSize)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun broadcastLog(task: TaskCtx, log: String) {
        val intent = Intent(BROADCAST_UPDATE).apply {
            putExtra(EXTRA_LOG, log)
            putExtra(EXTRA_FILE_NAME, task.fileName)
            putExtra(EXTRA_TASK_ID, task.id)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun broadcastDone(task: TaskCtx, success: Boolean, message: String, savedPath: String) {
        val intent = Intent(BROADCAST_UPDATE).apply {
            putExtra(EXTRA_DONE, true)
            putExtra(EXTRA_SUCCESS, success)
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_SAVED_PATH, savedPath)
            putExtra(EXTRA_FILE_NAME, task.fileName)
            putExtra(EXTRA_TASK_ID, task.id)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    // ==================== 通知 ====================

    private fun actionIntent(task: TaskCtx, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            this,
            task.notifId * 10 + code,
            Intent(action).apply {
                setPackage(packageName)
                putExtra(EXTRA_TASK_ID, task.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "固件与文件Download",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "显示Download任务进度（支持多任务并行）"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotif(task: TaskCtx): Notification {
        val paused = task.paused.get() && !task.done
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("${task.label} · ${task.fileName.takeLast(38)}")
            .setContentText(
                when {
                    paused -> "Paused · ${task.progress}%"
                    task.speed.isNotBlank() -> "${task.progress}% · ${task.speed}"
                    else -> "${task.progress}%"
                },
            )
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            // 每任务独立小窗口：Download中 → [Pause][Cancel]；Paused → [Resume][Cancel]
            .addAction(
                if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (paused) "Resume" else "Pause",
                actionIntent(task, if (paused) ACTION_RESUME else ACTION_PAUSE, if (paused) 2 else 1),
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                actionIntent(task, ACTION_CANCEL, 0),
            )

        if (task.progress in 1..99) {
            builder.setProgress(100, task.progress, false)
        } else if (task.progress >= 100 && task.done) {
            builder.setProgress(0, 0, false)
            builder.setContentText("Download完成")
            builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
        } else {
            builder.setProgress(100, 0, false)
        }

        return builder.build()
    }

    override fun onDestroy() {
        actionReceiver?.let { unregisterReceiver(it) }
        actionReceiver = null
        super.onDestroy()
    }
}
