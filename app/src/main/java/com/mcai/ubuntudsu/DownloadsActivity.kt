package com.mcai.ubuntudsu

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.mcai.ubuntudsu.core.JavaDownloader
import com.mcai.ubuntudsu.service.DownloadService
import com.mcai.ubuntudsu.ui.Haptics
import com.mcai.ubuntudsu.ui.Ui
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Download Manager[translated] / Neumorphism + Glassmorphism[translated]
 *
 * [translated]
 *  - [translated]All / Downloading / Completed / Paused
 *  - Task[translated]File[translated]File[translated]Download/[translated]Size[translated]Progress[translated]Speed[translated]Status
 *  - [translated]Task[translated]Pause / Resume / Delete
 *  - [translated]Select[translated]Start all / AllPause / Delete Selected[translated]
 *  - New Download[translated]Enter URL [translated]DownloadTask
 *  - [translated]Browse Files[translated]OpenDownloadDirectory
 *  - [translated] / [translated]
 *
 * Status[translated]0=idle 1=downloading 2=done 3=cancelled 4=failed 5=paused
 */
class DownloadsActivity : androidx.appcompat.app.AppCompatActivity() {

    companion object {
        const val ACTION_PAUSE = "com.mcai.ubuntudsu.PAUSE_DOWNLOAD"
        const val ACTION_RESUME = "com.mcai.ubuntudsu.RESUME_DOWNLOAD"

        data class DownloadTask(
            val id: String,
            var fileName: String = "",
            var deviceName: String = "",
            var progress: Int = 0,
            var speed: String = "",
            var status: String = "Waiting",
            var state: Int = 0,
            var savedPath: String = "",
            var startTime: Long = 0,
            var totalSize: Long = 0,
            var downloadedBytes: Long = 0,
            var url: String = "",
            var selected: Boolean = false,
        )

        private val tasks = ConcurrentHashMap<String, DownloadTask>()
        fun getTasks(): Map<String, DownloadTask> = tasks.toMap()
        fun addTask(task: DownloadTask) { tasks[task.id] = task }
        fun removeTask(id: String) { tasks.remove(id) }
        fun getTask(id: String): DownloadTask? = tasks[id]
    }

    // [translated]
    private enum class Tab(val label: String) { ALL("All"), DOWNLOADING("Downloading"), DONE("Completed"), PAUSED("Paused") }

    private lateinit var taskContainer: LinearLayout
    private lateinit var emptyView: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var selectedCountText: TextView
    private lateinit var tabButtons: Array<TextView>
    private var currentTab = Tab.ALL

    private var downloadReceiver: BroadcastReceiver? = null
    private val taskViews = mutableMapOf<String, TaskViewHolder>()
    private var selectionMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        Ui.animateLiquidBackground(root)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val d = resources.displayMetrics.density
            setPadding(Ui.dp(16, d), Ui.dp(12, d), Ui.dp(16, d), Ui.dp(8, d))
        }

        val d = resources.displayMetrics.density

        // ===== [translated]Back / Download Manager / New Download[translated] =====
        val titleRow = FrameLayout(this).apply {
            setPadding(0, 0, 0, Ui.dp(12, d))
        }
        titleRow.addView(TextView(this).apply {
            text = "‹ Back"
            textSize = 13f
            setTextColor(Ui.buttonText(this@DownloadsActivity))
            background = Ui.glassButton(this@DownloadsActivity, Ui.buttonSuccess(this@DownloadsActivity))
            Ui.pressAnimation(this)
            setPadding(Ui.dp(12, d), Ui.dp(6, d), Ui.dp(12, d), Ui.dp(6, d))
            setOnClickListener {
                Haptics.perform(this)
                finish()
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.CENTER_VERTICAL,
            )
        })
        titleRow.addView(TextView(this).apply {
            text = "Download Manager"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(this@DownloadsActivity))
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        })
        titleRow.addView(TextView(this).apply {
            text = "New Download"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = primaryButtonBg()
            Ui.pressAnimation(this)
            setPadding(Ui.dp(12, d), Ui.dp(7, d), Ui.dp(12, d), Ui.dp(7, d))
            setOnClickListener {
                Haptics.perform(this)
                showNewDownloadDialog()
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL,
            )
        })
        page.addView(titleRow)

        // ===== [translated] =====
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, Ui.dp(10, d))
        }
        tabButtons = Array(Tab.values().size) { i ->
            val tab = Tab.values()[i]
            TextView(this).apply {
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(Ui.dp(14, d), Ui.dp(7, d), Ui.dp(14, d), Ui.dp(7, d))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = Ui.dp(6, d)
                }
                setOnClickListener {
                    Haptics.perform(this)
                    selectTab(tab)
                }
            }
        }
        tabButtons.forEach { tabRow.addView(it) }
        page.addView(tabRow)

        // ===== [translated]Status =====
        emptyView = buildEmptyView()
        page.addView(emptyView)

        // ===== Task[translated] =====
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        taskContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        scrollView.addView(taskContainer)
        page.addView(scrollView)

        // ===== [translated] =====
        bottomBar = buildBottomBar()
        page.addView(bottomBar)

        root.addView(page)
        setContentView(root)
        Ui.enableEdgeToEdge(this, root)

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            androidx.core.view.ViewCompat.requestApplyInsets(page)
            insets
        }

        registerDownloadReceiver()
        selectTab(Tab.ALL)
        refreshTaskList()
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadReceiver?.let { unregisterReceiver(it) }
    }

    // ==================== [translated]System[translated] ====================

    private fun isDark(): Boolean = Ui.isDark(this)

    /** [translated] APP [translated]/[translated] */
    private fun primaryButtonBg(): android.graphics.drawable.Drawable =
        if (isDark()) {
            Ui.neuSolidButton(Color.parseColor("#34D399"), Color.parseColor("#15803D"), 12f, this)
        } else {
            Ui.neuSolidButton(Color.parseColor("#4ADE80"), Color.parseColor("#16A34A"), 12f, this)
        }

    /** Success[translated]Start all[translated] */
    private fun successButtonBg(): android.graphics.drawable.Drawable =
        if (isDark()) {
            Ui.neuSolidButton(Color.parseColor("#34D399"), Color.parseColor("#15803D"), 12f, this)
        } else {
            Ui.neuSolidButton(Color.parseColor("#4ADE80"), Color.parseColor("#16A34A"), 12f, this)
        }

    /** Warning[translated]AllPause[translated] */
    private fun warningButtonBg(): android.graphics.drawable.Drawable =
        if (isDark()) {
            Ui.neuSolidButton(Color.parseColor("#FBBF24"), Color.parseColor("#B45309"), 12f, this)
        } else {
            Ui.neuSolidButton(Color.parseColor("#FBBF24"), Color.parseColor("#D97706"), 12f, this)
        }

    /** [translated]Delete[translated] */
    private fun dangerButtonBg(): android.graphics.drawable.Drawable =
        if (isDark()) {
            Ui.neuSolidButton(Color.parseColor("#F87171"), Color.parseColor("#B91C1C"), 12f, this)
        } else {
            Ui.neuSolidButton(Color.parseColor("#F87171"), Color.parseColor("#DC2626"), 12f, this)
        }

    /** [translated]Pause / Resume / Delete [translated]Task[translated]accent [translated] + [translated] */
    private fun outlineButtonBg(color: Int): GradientDrawable {
        val d = resources.displayMetrics.density
        return GradientDrawable().apply {
            setColor(Color.argb(if (isDark()) 30 else 40, Color.red(color), Color.green(color), Color.blue(color)))
            cornerRadius = Ui.dp(10, d).toFloat()
            setStroke(Ui.dp(1, d), Color.argb(if (isDark()) 200 else 220, Color.red(color), Color.green(color), Color.blue(color)))
        }
    }

    /** Task[translated] + accent [translated] */
    private fun taskCardBg(accent: Int): android.graphics.drawable.Drawable =
        Ui.neuCard(this, 16f, accent)

    // ==================== [translated] ====================

    private fun selectTab(tab: Tab) {
        currentTab = tab
        val d = resources.displayMetrics.density
        Tab.values().forEachIndexed { i, t ->
            val btn = tabButtons[i]
            val active = t == tab
            if (active) {
                btn.setTextColor(Color.WHITE)
                btn.background = if (isDark()) {
                    GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor("#10B981"), Color.parseColor("#047857"))).apply {
                        cornerRadius = Ui.dp(10, d).toFloat()
                        setStroke(Ui.dp(1, d), Color.argb(220, 52, 211, 153))
                    }
                } else {
                    GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor("#22C55E"), Color.parseColor("#16A34A"))).apply {
                        cornerRadius = Ui.dp(10, d).toFloat()
                    }
                }
            } else {
                btn.setTextColor(Ui.secondaryText(this))
                btn.background = Ui.glassSurface(this, 10f)
            }
        }
        refreshTaskList()
    }

    private fun updateTabLabels() {
        val all = tasks.values
        val counts = mapOf(
            Tab.ALL to all.size,
            Tab.DOWNLOADING to all.count { it.state == 1 },
            Tab.DONE to all.count { it.state == 2 },
            Tab.PAUSED to all.count { it.state == 5 },
        )
        Tab.values().forEachIndexed { i, t ->
            tabButtons[i].text = "${t.label} ${counts[t]}"
        }
    }

    // ==================== [translated]Status ====================

    private fun buildEmptyView(): LinearLayout {
        val d = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, Ui.dp(40, d), 0, Ui.dp(40, d))
            visibility = View.GONE

            // [translated]
            addView(TextView(this@DownloadsActivity).apply {
                text = "📄"
                textSize = 48f
                gravity = Gravity.CENTER
            })
            addView(TextView(this@DownloadsActivity).apply {
                text = "No downloads"
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Ui.primaryText(this@DownloadsActivity))
                gravity = Gravity.CENTER
                setPadding(0, Ui.dp(12, d), 0, 0)
            })
            addView(TextView(this@DownloadsActivity).apply {
                text = "Browse ROM Store or start a new download"
                textSize = 12f
                setTextColor(Ui.secondaryText(this@DownloadsActivity))
                gravity = Gravity.CENTER
                setPadding(0, Ui.dp(4, d), 0, Ui.dp(16, d))
            })

            // [translated]
            val btnRow = LinearLayout(this@DownloadsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            btnRow.addView(TextView(this@DownloadsActivity).apply {
                text = "Browse ROM Store"
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                background = primaryButtonBg()
                Ui.pressAnimation(this)
                setPadding(Ui.dp(16, d), Ui.dp(8, d), Ui.dp(16, d), Ui.dp(8, d))
                setOnClickListener {
                    Haptics.perform(this)
                    openDownloadFolder()
                }
            })
            btnRow.addView(TextView(this@DownloadsActivity).apply {
                text = "New Download"
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                background = primaryButtonBg()
                Ui.pressAnimation(this)
                setPadding(Ui.dp(16, d), Ui.dp(8, d), Ui.dp(16, d), Ui.dp(8, d))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = Ui.dp(10, d) }
                setOnClickListener {
                    Haptics.perform(this)
                    showNewDownloadDialog()
                }
            })
            addView(btnRow)
        }
    }

    // ==================== [translated] ====================

    private fun buildBottomBar(): LinearLayout {
        val d = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(12, d), Ui.dp(8, d), Ui.dp(12, d), Ui.dp(8, d))
            background = if (isDark()) {
                GradientDrawable().apply {
                    setColor(Color.argb(220, 18, 22, 36))
                    cornerRadius = Ui.dp(14, d).toFloat()
                    setStroke(Ui.dp(1, d), Color.argb(120, 120, 130, 160))
                }
            } else {
                Ui.glassSurface(this@DownloadsActivity, 14f)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(8, d) }
            visibility = View.GONE

            selectedCountText = TextView(this@DownloadsActivity).apply {
                text = "Selected 0 items"
                textSize = 12f
                setTextColor(Ui.primaryText(this@DownloadsActivity))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            addView(selectedCountText)

            val btnStart = TextView(this@DownloadsActivity).apply {
                text = "Start all"
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                background = successButtonBg()
                Ui.pressAnimation(this)
                setPadding(Ui.dp(12, d), Ui.dp(7, d), Ui.dp(12, d), Ui.dp(7, d))
                setOnClickListener {
                    Haptics.perform(this)
                    batchAction(BatchAction.START)
                }
            }
            val btnPause = TextView(this@DownloadsActivity).apply {
                text = "AllPause"
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                background = warningButtonBg()
                Ui.pressAnimation(this)
                setPadding(Ui.dp(12, d), Ui.dp(7, d), Ui.dp(12, d), Ui.dp(7, d))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = Ui.dp(6, d) }
                setOnClickListener {
                    Haptics.perform(this)
                    batchAction(BatchAction.PAUSE)
                }
            }
            val btnDelete = TextView(this@DownloadsActivity).apply {
                text = "Delete Selected"
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                background = dangerButtonBg()
                Ui.pressAnimation(this)
                setPadding(Ui.dp(12, d), Ui.dp(7, d), Ui.dp(12, d), Ui.dp(7, d))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = Ui.dp(6, d) }
                setOnClickListener {
                    Haptics.perform(this)
                    batchAction(BatchAction.DELETE)
                }
            }
            addView(btnStart)
            addView(btnPause)
            addView(btnDelete)
        }
    }

    private enum class BatchAction { START, PAUSE, DELETE }

    private fun batchAction(action: BatchAction) {
        val selected = tasks.values.filter { it.selected }
        if (selected.isEmpty()) {
            Toast.makeText(this, "No tasks selected", Toast.LENGTH_SHORT).show()
            return
        }
        when (action) {
            BatchAction.START -> {
                selected.filter { it.state == 5 }.forEach { task ->
                    task.state = 1
                    task.status = "Downloading"
                    sendBroadcast(Intent(ACTION_RESUME).apply {
                        setPackage(packageName)
                        putExtra(DownloadService.EXTRA_TASK_ID, task.id)
                    })
                }
                Toast.makeText(this, "Resumed ${selected.count { it.state == 1 }}  tasks", Toast.LENGTH_SHORT).show()
            }
            BatchAction.PAUSE -> {
                selected.filter { it.state == 1 }.forEach { task ->
                    task.state = 5
                    task.status = "Paused"
                    sendBroadcast(Intent(ACTION_PAUSE).apply {
                        setPackage(packageName)
                        putExtra(DownloadService.EXTRA_TASK_ID, task.id)
                    })
                }
                Toast.makeText(this, "Paused ${selected.count { it.state == 5 }}  tasks", Toast.LENGTH_SHORT).show()
            }
            BatchAction.DELETE -> {
                AlertDialog.Builder(this)
                    .setTitle("Delete Selected")
                    .setMessage("Delete selected ${selected.size}  download tasks？Downloaded files will not be deleted.")
                    .setPositiveButton("Delete") { _, _ ->
                        selected.forEach { task ->
                            // [translated]Task[translated]Cancel[translated]Task[translated]
                            if (task.state == 0 || task.state == 1 || task.state == 5) {
                                sendBroadcast(Intent(DownloadService.ACTION_CANCEL).apply {
                                    setPackage(packageName)
                                    putExtra(DownloadService.EXTRA_TASK_ID, task.id)
                                })
                            }
                            tasks.remove(task.id)
                        }
                        exitSelectionMode()
                        Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                return
            }
        }
        refreshTaskList()
    }

    private fun updateBottomBar() {
        val count = tasks.values.count { it.selected }
        selectedCountText.text = "Selected $count items"
        bottomBar.visibility = if (selectionMode) View.VISIBLE else View.GONE
    }

    private fun exitSelectionMode() {
        selectionMode = false
        tasks.values.forEach { it.selected = false }
        updateBottomBar()
        refreshTaskList()
    }

    // ==================== [translated] ====================

    private fun registerDownloadReceiver() {
        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent ?: return
                val fileName = intent.getStringExtra(DownloadService.EXTRA_FILE_NAME) ?: ""
                val deviceName = intent.getStringExtra(DownloadService.EXTRA_DEVICE) ?: ""
                // [translated]Task[translated] task_id[translated]Task[translated] fileName
                val taskId = intent.getStringExtra(DownloadService.EXTRA_TASK_ID)
                    ?: fileName.ifBlank { "unknown" }

                when {
                    intent.hasExtra(DownloadService.EXTRA_LOG) -> {
                        val log = intent.getStringExtra(DownloadService.EXTRA_LOG) ?: ""
                        val task = tasks[taskId] ?: return
                        // aria2c [translated] CN: [translated]Status[translated]/FailedReason/Directory[translated]
                        if (log.contains("CN:") || log.isBlank()) return
                        if (task.state == 0 || task.state == 1) {
                            task.status = log.take(60)
                            refreshTaskList()
                        }
                    }
                    intent.hasExtra(DownloadService.EXTRA_DONE) -> {
                        val success = intent.getBooleanExtra(DownloadService.EXTRA_SUCCESS, false)
                        val msg = intent.getStringExtra(DownloadService.EXTRA_MESSAGE) ?: ""
                        val savedPath = intent.getStringExtra(DownloadService.EXTRA_SAVED_PATH) ?: ""
                        if (tasks.containsKey(taskId)) {
                            val task = tasks[taskId]!!
                            task.state = if (success) 2 else 4
                            task.status = if (success) "Completed" else "Failed: $msg"
                            task.savedPath = savedPath
                            task.progress = if (success) 100 else task.progress
                            if (success && task.totalSize > 0) task.downloadedBytes = task.totalSize
                            refreshTaskList()
                        }
                    }
                    intent.hasExtra(DownloadService.EXTRA_STATE) -> {
                        val state = intent.getIntExtra(DownloadService.EXTRA_STATE, 0)
                        val progress = intent.getIntExtra(DownloadService.EXTRA_PROGRESS, 0)
                        val speed = intent.getStringExtra(DownloadService.EXTRA_SPEED) ?: ""
                        val status = intent.getStringExtra(DownloadService.EXTRA_STATUS_TEXT) ?: ""
                        val total = intent.getLongExtra(DownloadService.EXTRA_TOTAL_SIZE, 0L)
                        val downloaded = intent.getLongExtra(DownloadService.EXTRA_DOWNLOADED_SIZE, 0L)
                        val task = tasks[taskId] ?: DownloadTask(taskId).also { tasks[taskId] = it }
                        task.fileName = fileName
                        task.deviceName = deviceName
                        task.progress = progress
                        task.speed = speed
                        // LocalPaused[translated]state=5[translated]DownloadProgress[translated]state=1[translated]
                        // [translated]Pause[translated]"[translated]Pause"[translated]
                        if (state == 1 && task.state == 5) {
                            task.status = "Paused"
                        } else {
                            task.status = status
                            task.state = state
                        }
                        if (total > 0) task.totalSize = total
                        if (downloaded > 0) task.downloadedBytes = downloaded
                        if (state == 1 && task.startTime == 0L) {
                            task.startTime = System.currentTimeMillis()
                        }
                        refreshTaskList()
                    }
                }
            }
        }
        val filter = IntentFilter(DownloadService.BROADCAST_UPDATE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(downloadReceiver, filter)
        }
    }

    // ==================== [translated] ====================

    /** [translated]tab + [translated]Task[translated] id/state/Select[translated] = [translated]content[translated]
     *  Progress[translated] removeAllViews [translated] touch [translated]
     *  —— [translated]Pause[translated] */
    private var lastStructureKey: String? = null

    private fun structureKey(filtered: List<DownloadTask>): String = buildString {
        append(currentTab.name).append('|')
        append(if (selectionMode) "S" else "s").append('|')
        filtered.forEach { append(it.id).append(':').append(it.state).append(':').append(if (it.selected) 1 else 0).append('|') }
    }

    private fun refreshTaskList() {
        updateTabLabels()

        // [translated]OK[translated]startTime [translated]APP[translated]Task/[translated]
        // [translated] HashMap values() [translated]Task[translated] rehash [translated]
        // —— [translated]"[translated]"Pause[translated]Task[translated] id [translated]
        val all = tasks.values.sortedWith(
            compareByDescending<DownloadTask> { it.startTime }.thenBy { it.id },
        )
        val filtered = when (currentTab) {
            Tab.ALL -> all
            Tab.DOWNLOADING -> all.filter { it.state == 1 }
            Tab.DONE -> all.filter { it.state == 2 }
            Tab.PAUSED -> all.filter { it.state == 5 }
        }

        emptyView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE

        val key = structureKey(filtered)
        if (key == lastStructureKey) {
            // [translated]Progress/Speed[translated]content[translated]
            filtered.forEach { task -> taskViews[task.id]?.update(task) }
            updateBottomBar()
            return
        }
        lastStructureKey = key

        taskContainer.removeAllViews()
        taskViews.clear()

        if (filtered.isEmpty()) {
            updateBottomBar()
            return
        }

        val d = resources.displayMetrics.density

        // [translated]Status[translated]
        // [translated]Downloading[translated]/Paused[translated]Pause/Resume[translated]
        // [translated] → "[translated]2Pause[translated]1"[translated]
        // [translated] startTime [translated] + id[translated]Status[translated]
        filtered.forEach { task ->
            addTaskCard(task, d)
        }

        updateBottomBar()
    }

    private fun addTaskCard(task: DownloadTask, d: Float) {
        val holder = taskViews.getOrPut(task.id) { TaskViewHolder(this, task.id) }
        holder.update(task)
        // [translated]
        val accent = holder.currentAccent
        Ui.applyNeuShadow(holder.rootView, 4f, 16f, accent)
        taskContainer.addView(holder.rootView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = Ui.dp(8, d) })
    }

    // ==================== New Download ====================

    private fun showNewDownloadDialog() {
        val d = resources.displayMetrics.density
        val input = EditText(this).apply {
            hint = "Enter download URL (http/https)"
            textSize = 13f
            setTextColor(Ui.primaryText(this@DownloadsActivity))
            setHintTextColor(Ui.secondaryText(this@DownloadsActivity))
            setPadding(Ui.dp(12, d), Ui.dp(10, d), Ui.dp(12, d), Ui.dp(10, d))
            background = Ui.glassSurface(this@DownloadsActivity, 10f)
            setSingleLine()
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(20, d), Ui.dp(8, d), Ui.dp(20, d), Ui.dp(4, d))
            addView(input)
            addView(TextView(this@DownloadsActivity).apply {
                text = "Built-in aria2c multi-thread engine · Save to /sdcard/Downloads\nSupports resume and parallel ROM downloads"
                textSize = 10f
                setTextColor(Ui.secondaryText(this@DownloadsActivity))
                setPadding(0, Ui.dp(6, d), 0, 0)
            })
        }
        AlertDialog.Builder(this)
            .setTitle("New Download")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("StartDownload") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isBlank()) {
                    Toast.makeText(this, "URL cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                startUrlDownload(url)
            }
            .show()
    }

    private fun startUrlDownload(url: String) {
        val filename = JavaDownloader.fileNameFromUrl(url)
        // [translated]Task[translated]Download/Pause[translated]
        tasks[filename]?.let {
            if (it.state == 0 || it.state == 1 || it.state == 5) {
                Toast.makeText(this, "Task \"$filename\" is already in the Download list", Toast.LENGTH_SHORT).show()
                return
            }
        }
        val intent = Intent(this, DownloadService::class.java).apply {
            action = DownloadService.ACTION_START
            putExtra(DownloadService.EXTRA_URL, url)
            putExtra(DownloadService.EXTRA_FILENAME, filename)
            putExtra(DownloadService.EXTRA_VERSION, "")
            putExtra(DownloadService.EXTRA_NODE_INDEX, 3)
            putExtra(DownloadService.EXTRA_LABEL, "Custom")
            putExtra(DownloadService.EXTRA_DEVICE_NAME, "Custom Download")
            // Custom[translated]Built-in aria2c [translated]16 Connection[translated] + [translated]
            putExtra(DownloadService.EXTRA_USE_ARIA2, true)
        }
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        addTask(DownloadTask(
            id = filename,
            fileName = filename,
            deviceName = "Custom Download",
            status = "Preparing download",
            state = 0,
            url = url,
            startTime = System.currentTimeMillis(),
        ))
        Toast.makeText(this, "Added to download queue", Toast.LENGTH_SHORT).show()
        refreshTaskList()
    }

    // ==================== Browse Files ====================

    private fun openDownloadFolder() {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "")
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", dir)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "resource/folder")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }.onFailure {
            // [translated]File[translated]Open
            val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            runCatching { startActivity(Intent.createChooser(fallback, "Browse Files")) }
        }
    }

    // ==================== Tool ====================

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = -1
        while (value >= 1024 && unit < units.size - 1) {
            value /= 1024
            unit++
        }
        return String.format("%.1f %s", value, units[unit])
    }

    override fun onBackPressed() {
        if (selectionMode) {
            exitSelectionMode()
            return
        }
        super.onBackPressed()
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        ev?.let { Haptics.onTouch(window.decorView, it) }
        return super.dispatchTouchEvent(ev)
    }

    // ==================== Task[translated] ViewHolder ====================

    private inner class TaskViewHolder(
        private val ctx: Context,
        private val taskId: String,
    ) {
        lateinit var rootView: LinearLayout
        private lateinit var iconView: TextView
        private lateinit var fileNameText: TextView
        private lateinit var sizeText: TextView
        private lateinit var progressBar: ProgressBar
        private lateinit var progressPercentText: TextView
        private lateinit var speedText: TextView
        private lateinit var statusText: TextView
        private lateinit var checkBox: View
        private lateinit var pauseBtn: TextView
        /** CurrentTask accent [translated] */
        var currentAccent: Int = Color.parseColor("#64748B")
        private lateinit var deleteBtn: TextView
        private lateinit var openBtn: TextView

        init { buildView() }

        private fun buildView() {
            val d = ctx.resources.displayMetrics.density
            rootView = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Ui.dp(12, d), Ui.dp(10, d), Ui.dp(12, d), Ui.dp(10, d))
                isClickable = true
                isFocusable = true
            }

            // [translated] + [translated] + File[translated] + Size
            val row1 = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            checkBox = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(Ui.dp(18, d), Ui.dp(18, d)).apply {
                    marginEnd = Ui.dp(8, d)
                }
                visibility = View.GONE
            }
            row1.addView(checkBox)

            iconView = TextView(ctx).apply {
                textSize = 16f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(Ui.dp(36, d), Ui.dp(36, d)).apply {
                    marginEnd = Ui.dp(10, d)
                }
            }
            row1.addView(iconView)

            fileNameText = TextView(ctx).apply {
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Ui.primaryText(ctx as Activity))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row1.addView(fileNameText)

            sizeText = TextView(ctx).apply {
                textSize = 11f
                setTextColor(Ui.secondaryText(ctx as Activity))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = Ui.dp(8, d) }
            }
            row1.addView(sizeText)

            rootView.addView(row1)

            // [translated]Progress[translated] + [translated]
            val progressRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, Ui.dp(6, d), 0, Ui.dp(4, d))
            }
            progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams = LinearLayout.LayoutParams(0, Ui.dp(8, d), 1f)
                max = 100
            }
            progressPercentText = TextView(ctx).apply {
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Ui.primaryText(ctx as Activity))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = Ui.dp(8, d) }
            }
            progressRow.addView(progressBar)
            progressRow.addView(progressPercentText)
            rootView.addView(progressRow)

            // [translated]Speed + Status
            val infoRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            speedText = TextView(ctx).apply {
                textSize = 11f
                setTextColor(Ui.secondaryText(ctx as Activity))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            statusText = TextView(ctx).apply {
                textSize = 11f
                setTextColor(Ui.secondaryText(ctx as Activity))
            }
            infoRow.addView(speedText)
            infoRow.addView(statusText)
            rootView.addView(infoRow)

            // [translated]
            val actionRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, Ui.dp(6, d), 0, 0)
            }
            pauseBtn = TextView(ctx).apply {
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(Ui.dp(14, d), Ui.dp(6, d), Ui.dp(14, d), Ui.dp(6, d))
                setOnClickListener {
                    Haptics.perform(this)
                    togglePauseResume()
                }
            }
            deleteBtn = TextView(ctx).apply {
                text = "Delete"
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(Ui.dp(14, d), Ui.dp(6, d), Ui.dp(14, d), Ui.dp(6, d))
                setOnClickListener {
                    Haptics.perform(this)
                    doDelete()
                }
            }
            openBtn = TextView(ctx).apply {
                text = "Open"
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(Ui.dp(14, d), Ui.dp(6, d), Ui.dp(14, d), Ui.dp(6, d))
                setOnClickListener {
                    Haptics.perform(this)
                    openFile()
                }
                visibility = View.GONE
            }
            actionRow.addView(pauseBtn)
            actionRow.addView(openBtn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = Ui.dp(6, d) })
            actionRow.addView(deleteBtn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = Ui.dp(6, d) })
            rootView.addView(actionRow)

            // [translated]Select[translated]Select[translated]
            rootView.setOnClickListener {
                if (selectionMode) {
                    toggleSelection()
                }
            }
            // [translated]Select[translated]
            rootView.setOnLongClickListener {
                if (!selectionMode) {
                    selectionMode = true
                }
                toggleSelection()
                true
            }
        }

        private fun toggleSelection() {
            val task = tasks[taskId] ?: return
            task.selected = !task.selected
            update(task)
            updateBottomBar()
        }

        private fun fileAccent(name: String): Pair<String, Int> {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when {
                ext in listOf("mp4", "mkv", "avi", "mov", "flv", "mpf", "webm") -> "🎬" to Color.parseColor("#8B5CF6")
                ext in listOf("zip", "rar", "7z", "tar", "gz", "tgz") -> "📦" to Color.parseColor("#F59E0B")
                ext in listOf("pdf") -> "📕" to Color.parseColor("#EF4444")
                ext in listOf("doc", "docx") -> "📘" to Color.parseColor("#22C55E")
                ext in listOf("xls", "xlsx") -> "📗" to Color.parseColor("#22C55E")
                ext in listOf("ppt", "pptx") -> "📙" to Color.parseColor("#F97316")
                ext in listOf("mp3", "wav", "flac", "aac") -> "🎵" to Color.parseColor("#EC4899")
                ext in listOf("img", "iso") -> "💿" to Color.parseColor("#06B6D4")
                else -> "📄" to Color.parseColor("#64748B")
            }
        }

        private fun progressColors(state: Int): Pair<Int, Int> {
            // Back (Progress[translated], [translated])
            return when (state) {
                1 -> Color.parseColor("#22C55E") to Color.parseColor("#DCFCE7") // Downloading: green
                2 -> Color.parseColor("#22C55E") to Color.parseColor("#DCFCE7") // Completed: green
                5 -> Color.parseColor("#EF4444") to Color.parseColor("#FEE2E2") // Paused: red
                else -> Color.parseColor("#94A3B8") to Color.parseColor("#E2E8F0")
            }
        }

        private fun darkProgressColors(state: Int): Pair<Int, Int> {
            return when (state) {
                1 -> Color.parseColor("#4ADE80") to Color.argb(80, 40, 80, 60)
                2 -> Color.parseColor("#4ADE80") to Color.argb(80, 40, 80, 60)
                5 -> Color.parseColor("#F87171") to Color.argb(80, 120, 50, 50)
                else -> Color.parseColor("#94A3B8") to Color.argb(80, 80, 80, 90)
            }
        }

        fun update(task: DownloadTask) {
            val d = ctx.resources.displayMetrics.density
            val (icon, accent) = fileAccent(task.fileName)
            currentAccent = accent

            rootView.background = taskCardBg(accent)

            // [translated]
            if (selectionMode) {
                checkBox.visibility = View.VISIBLE
                checkBox.background = if (task.selected) {
                    GradientDrawable().apply {
                        setColor(Ui.buttonSuccess(ctx as Activity))
                        cornerRadius = Ui.dp(4, d).toFloat()
                        setStroke(Ui.dp(1, d), Ui.buttonSuccess(ctx as Activity))
                    }
                } else {
                    GradientDrawable().apply {
                        setColor(Color.TRANSPARENT)
                        cornerRadius = Ui.dp(4, d).toFloat()
                        setStroke(Ui.dp(1, d), Color.argb(150, 150, 150, 150))
                    }
                }
            } else {
                checkBox.visibility = View.GONE
            }

            // [translated]
            iconView.text = icon
            iconView.background = GradientDrawable().apply {
                setColor(Color.argb(40, Color.red(accent), Color.green(accent), Color.blue(accent)))
                cornerRadius = Ui.dp(10, d).toFloat()
                setStroke(Ui.dp(1, d), Color.argb(180, Color.red(accent), Color.green(accent), Color.blue(accent)))
            }

            fileNameText.text = task.fileName.ifBlank { "Unknown file" }

            // Size
            val sizeStr = if (task.totalSize > 0) {
                "${formatBytes(task.downloadedBytes)} / ${formatBytes(task.totalSize)}"
            } else if (task.state == 2 && task.savedPath.isNotBlank()) {
                val f = File(task.savedPath)
                if (f.exists()) formatBytes(f.length()) else "Completed"
            } else {
                "—"
            }
            sizeText.text = sizeStr

            // Progress
            val (progressColor, trackColor) = if (isDark()) darkProgressColors(task.state) else progressColors(task.state)
            progressBar.progress = task.progress
            val track = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = Ui.dp(99, d).toFloat()
                setColor(trackColor)
            }
            val fill = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = Ui.dp(99, d).toFloat()
                setColor(progressColor)
            }
            val clip = android.graphics.drawable.ClipDrawable(
                fill,
                Gravity.START,
                android.graphics.drawable.ClipDrawable.HORIZONTAL,
            )
            val layer = android.graphics.drawable.LayerDrawable(arrayOf(track, clip))
            layer.setId(0, android.R.id.background)
            layer.setId(1, android.R.id.progress)
            progressBar.progressDrawable = layer
            progressPercentText.text = "${task.progress}%"
            progressPercentText.setTextColor(progressColor)

            // Speed / Status
            speedText.text = if (task.state == 1 && task.speed.isNotBlank()) task.speed else ""
            statusText.text = when (task.state) {
                1 -> "Downloading"
                2 -> "✓ Completed"
                5 -> "Paused"
                3 -> "Cancelled"
                4 -> "Failed"
                else -> task.status
            }
            statusText.setTextColor(when (task.state) {
                2 -> Color.parseColor(if (isDark()) "#4ADE80" else "#16A34A")
                4 -> Color.parseColor(if (isDark()) "#F87171" else "#DC2626")
                5 -> Color.parseColor(if (isDark()) "#F87171" else "#DC2626")
                else -> Ui.secondaryText(ctx as Activity)
            })

            // [translated]
            when (task.state) {
                1 -> { // Downloading
                    pauseBtn.text = "Pause"
                    pauseBtn.setTextColor(Color.parseColor("#D97706"))
                    pauseBtn.background = outlineButtonBg(Color.parseColor("#F59E0B"))
                    pauseBtn.visibility = View.VISIBLE
                    openBtn.visibility = View.GONE
                    deleteBtn.visibility = View.VISIBLE
                    deleteBtn.setTextColor(Color.parseColor("#DC2626"))
                    deleteBtn.background = outlineButtonBg(Color.parseColor("#EF4444"))
                }
                5 -> { // Paused
                    pauseBtn.text = "Resume"
                    pauseBtn.setTextColor(Color.parseColor("#16A34A"))
                    pauseBtn.background = outlineButtonBg(Color.parseColor("#22C55E"))
                    pauseBtn.visibility = View.VISIBLE
                    openBtn.visibility = View.GONE
                    deleteBtn.visibility = View.VISIBLE
                    deleteBtn.setTextColor(Color.parseColor("#DC2626"))
                    deleteBtn.background = outlineButtonBg(Color.parseColor("#EF4444"))
                }
                2 -> { // Completed
                    pauseBtn.visibility = View.GONE
                    openBtn.visibility = View.VISIBLE
                    openBtn.setTextColor(Color.parseColor("#15803D"))
                    openBtn.background = outlineButtonBg(Color.parseColor("#22C55E"))
                    deleteBtn.visibility = View.VISIBLE
                    deleteBtn.setTextColor(Color.parseColor("#DC2626"))
                    deleteBtn.background = outlineButtonBg(Color.parseColor("#EF4444"))
                }
                else -> {
                    pauseBtn.visibility = View.GONE
                    openBtn.visibility = View.GONE
                    deleteBtn.visibility = View.VISIBLE
                    deleteBtn.setTextColor(Color.parseColor("#DC2626"))
                    deleteBtn.background = outlineButtonBg(Color.parseColor("#EF4444"))
                }
            }
        }

        private fun togglePauseResume() {
            val task = tasks[taskId] ?: return
            when (task.state) {
                1 -> {
                    task.state = 5
                    task.status = "Paused"
                    sendBroadcast(Intent(ACTION_PAUSE).apply {
                        setPackage(packageName)
                        putExtra(DownloadService.EXTRA_TASK_ID, taskId)
                    })
                }
                5 -> {
                    task.state = 1
                    task.status = "Downloading"
                    sendBroadcast(Intent(ACTION_RESUME).apply {
                        setPackage(packageName)
                        putExtra(DownloadService.EXTRA_TASK_ID, taskId)
                    })
                }
            }
            refreshTaskList()
        }

        private fun doDelete() {
            val task = tasks[taskId] ?: return
            AlertDialog.Builder(ctx)
                .setTitle("Delete download tasks")
                .setMessage("OKDelete「${task.fileName.ifBlank { "Unknown file" }}」？\nDownloaded files will not be deleted.")
                .setPositiveButton("Delete") { _, _ ->
                    // Downloading/Pause[translated]TaskDelete[translated]Cancel[translated]Task[translated]Task[translated]
                    if (task.state == 0 || task.state == 1 || task.state == 5) {
                        sendBroadcast(Intent(DownloadService.ACTION_CANCEL).apply {
                            setPackage(packageName)
                            putExtra(DownloadService.EXTRA_TASK_ID, taskId)
                        })
                    }
                    tasks.remove(taskId)
                    taskViews.remove(taskId)
                    refreshTaskList()
                    Toast.makeText(ctx, "Deleted", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        private fun openFile() {
            val task = tasks[taskId] ?: return
            val path = task.savedPath.ifBlank {
                File(JavaDownloader.defaultSaveDir(), task.fileName).absolutePath
            }
            val file = File(path)
            if (!file.exists()) {
                Toast.makeText(ctx, "File not found", Toast.LENGTH_SHORT).show()
                return
            }
            val mime = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension) ?: "*/*"
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { ctx.startActivity(intent) }.onFailure {
                Toast.makeText(ctx, "Cannot open this file type", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
