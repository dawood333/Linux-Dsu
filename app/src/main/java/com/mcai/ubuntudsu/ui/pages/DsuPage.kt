package com.mcai.ubuntudsu.ui.pages

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.mcai.ubuntudsu.IPrivilegedService
import com.mcai.ubuntudsu.PrivilegedRootService
import com.mcai.ubuntudsu.R
import com.mcai.ubuntudsu.core.DsuManager
import com.mcai.ubuntudsu.ui.Ui
import com.topjohnwu.superuser.ipc.RootService
import java.util.concurrent.Executor
import java.util.zip.ZipInputStream
import android.os.Environment
import android.os.StatFs

class DsuPage(
    private val activity: AppCompatActivity,
    private val executor: Executor,
    private val pickZipLauncher: ActivityResultLauncher<android.content.Intent>,
) {
    private var selectedUserdataGB = DsuManager.userdataOptions.first()
    private var clearUserdata = false
    private var selectedZip: Uri? = null
    private var selectedZipName: String? = null
    private var privilegedService: IPrivilegedService? = null
    private val rootConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            privilegedService = IPrivilegedService.Stub.asInterface(service)
            log("ROOT DSU installer connected")
        }
        override fun onServiceDisconnected(name: ComponentName) {
            privilegedService = null
            log("ROOT DSU installer disconnected")
        }
    }
    private lateinit var fileNameText: TextView
    private lateinit var installProgressLabel: TextView
    private lateinit var installProgressBar: ProgressBar
    private lateinit var installPercentText: TextView
    private lateinit var customCapacityInput: EditText

    private fun availableGB(): Long = runCatching {
        val stat = StatFs(Environment.getDataDirectory().path)
        (stat.availableBytes / 1024 / 1024 / 1024)
    }.getOrDefault(0L)

    /** 参考 DSU-Sideloader：Android 限制 userdata 最多占用 40% 剩余空间，超过则警告 */
    private fun userdataWarning(GB: Int): String {
        val availGB = availableGB()
        if (availGB <= 0) return ""
        val maxSafeGB = (availGB * 40 / 100).toInt()
        return when {
            GB > maxSafeGB * 2 -> "Warning: ${GB} GB far exceeds available device space (${availGB} GB)，installation is very likely to fail，recommended to use ${maxSafeGB.coerceAtLeast(8)} GB"
            GB > maxSafeGB -> "Notice: ${GB} GB exceeds the device safe capacity (about  ${maxSafeGB} GB)，installation may fail"
            else -> ""
        }
    }

    /** 动态上限：40% of available space (Android 官方限制)，下限 8 GB，不设硬顶 (覆盖 1TB 设备) */
    private fun maxAllowedGB(): Int = ((availableGB() * 40 / 100).toInt()).coerceAtLeast(8)

    fun onZipPicked(uri: Uri?) {
        uri?.let {
            selectedZip = it
            selectedZipName = it.path?.substringAfterLast('/') ?: "gsi.zip"
            // 持久化选中路径：选择器期间进程被杀重建后仍能恢复
            activity.getPreferences(android.app.Activity.MODE_PRIVATE).edit()
                .putString("dsu_selected_zip", it.path).apply()
            if (::fileNameText.isInitialized) fileNameText.text = selectedZipName
            log("Selected GSI package:  $selectedZipName")
            Toast.makeText(activity, "Selected $selectedZipName", Toast.LENGTH_SHORT).show()
        }
    }

    // 进程重建后恢复上次选中的 GSI 包路径
    private fun restoreSelectedZip() {
        val path = activity.getPreferences(android.app.Activity.MODE_PRIVATE).getString("dsu_selected_zip", null)
        if (path != null && java.io.File(path).isFile && selectedZip == null) {
            selectedZip = Uri.fromFile(java.io.File(path))
            selectedZipName = path.substringAfterLast('/')
        }
    }

    fun bindRootService() {
        RootService.bind(Intent(activity, PrivilegedRootService::class.java), rootConnection)
    }

    fun unbindRootService() {
        runCatching { RootService.unbind(rootConnection) }
    }

    fun build(): View {
        val d = activity.resources.displayMetrics.density
        val page = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(16, d), Ui.dp(12, d), Ui.dp(16, d), Ui.dp(16, d))
        }
        // 标题：左侧"DSU Manager" (设置入口仅在首页)
        page.addView(TextView(activity).apply {
            text = "DSU Manager"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
            setPadding(0, 0, 0, Ui.dp(14, d))
        })

        // 恢复上次选中的 GSI 包 (进程重建场景，fileNameText 创建时同步显示)
        restoreSelectedZip()

        // 进度卡 (紧凑单行：状态文字与百分比同行 + 8dp 细进度条，给下方 DSU Tools图标留空间)
        val progressCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(12, d), Ui.dp(6, d), Ui.dp(12, d), Ui.dp(6, d))
            background = Ui.glassSurface(activity, 18f)
            Ui.applyNeuShadow(this, 3f, 18f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = Ui.dp(8, d) }
        }
        val statusRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        installProgressLabel = label("Status: Not started", 12f).apply {
            setTextColor(Ui.secondaryText(activity))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        installPercentText = Ui.percentTextView(activity).apply {
            gravity = Gravity.END
        }
        statusRow.addView(installProgressLabel)
        statusRow.addView(installPercentText)
        installProgressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressDrawable = Ui.pillProgressDrawable(activity)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(8, d)).apply { topMargin = Ui.dp(3, d) }
        }
        progressCard.addView(statusRow)
        progressCard.addView(installProgressBar)
        page.addView(progressCard)

        // 安装参数 + 镜像管理合并卡
        val parameterCard = card(d)
        parameterCard.addView(label("userdata size", 13f, bold = true))
        val sizeRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Ui.dp(4, d), 0, 0)
        }
        DsuManager.userdataOptions.forEachIndexed { index, size ->
            val chip = TextView(activity).apply {
                text = "$size GB"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(if (index == 0) Ui.buttonText(activity) else Ui.primaryText(activity))
                background = Ui.glassButton(activity, if (index == 0) Ui.buttonPrimary(activity) else null)
                layoutParams = LinearLayout.LayoutParams(0, Ui.dp(30, d), 1f).apply {
                    marginEnd = if (index == DsuManager.userdataOptions.lastIndex) 0 else Ui.dp(5, d)
                }
                setOnClickListener {
                    selectedUserdataGB = size
                    selectSizeChip(sizeRow, size)
                    // 点选预设时清空自定义输入，保证「唯一生效值」清晰
                    customCapacityInput.setText("")
                    val w = userdataWarning(size)
                    if (w.isNotEmpty()) {
                        Toast.makeText(activity, w, Toast.LENGTH_LONG).show()
                    }
                }
            }
            sizeRow.addView(chip)
        }
        parameterCard.addView(sizeRow)
        // 剩余空间提示 (参考 DSU-Sideloader 40% 安全限制说明)
        parameterCard.addView(label(
            "Free space: ${availableGB()} GB · recommended limit：${maxAllowedGB()} GB (40% of available space)",
            11f,
        ).apply {
            setTextColor(Ui.secondaryText(activity))
            setPadding(0, Ui.dp(4, d), 0, 0)
        })
        // 自定义Capacity：输入框 + OK按钮二合一 (免弹框，直接输入 GB 数)
        val customRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Ui.dp(5, d), 0, 0)
        }
        customCapacityInput = EditText(activity).apply {
            hint = "Custom size (GB)"
            textSize = 12f
            setTextColor(Ui.primaryText(activity))
            setHintTextColor(Ui.secondaryText(activity))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            maxLines = 1
            background = Ui.glassButton(activity)
            setPadding(Ui.dp(10, d), 0, Ui.dp(10, d), 0)
            layoutParams = LinearLayout.LayoutParams(0, Ui.dp(30, d), 1f)
        }
        customRow.addView(customCapacityInput)
        customRow.addView(
            smallAction("Set Size", Ui.buttonPrimary(activity), minWidthDp = 88) {
                val value = customCapacityInput.text.toString().toIntOrNull()
                when {
                    value == null || value <= 0 -> {
                        Toast.makeText(activity, "Enter a valid size", Toast.LENGTH_SHORT).show()
                    }
                    value > maxAllowedGB() -> {
                        Toast.makeText(activity, "Capacity ${value} GB exceeds the recommended limit ${maxAllowedGB()} GB (available space ${availableGB()} GB 40%)，installation may fail", Toast.LENGTH_LONG).show()
                    }
                    else -> {
                        selectedUserdataGB = value
                        selectSizeChip(sizeRow, value)
                        val w = userdataWarning(value)
                        Toast.makeText(activity, if (w.isNotEmpty()) "Set ${value} GB · $w" else "Set userdata size：${value} GB", Toast.LENGTH_LONG).show()
                    }
                }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = Ui.dp(8, d)
            },
        )
        parameterCard.addView(customRow)

        parameterCard.addView(label("GSI package (zip)", 13f, bold = true).apply { setPadding(0, Ui.dp(8, d), 0, Ui.dp(3, d)) })
        val fileRow = LinearLayout(activity)
        fileNameText = label(selectedZipName ?: "No file selected", 12f).apply {
            setTextColor(if (selectedZipName != null) Ui.primaryText(activity) else Ui.secondaryText(activity))
            layoutParams = LinearLayout.LayoutParams(0, Ui.dp(30, d), 1f)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(10, d), 0, 0, 0)
            background = Ui.glassButton(activity)
        }
        fileRow.addView(fileNameText)
        fileRow.addView(
            smallAction("Select ZIP", Ui.buttonSecondary(activity), minWidthDp = 88) {
                // 内置文件选择器：根目录 /sdcard，选择 GSI zip
                pickZipLauncher.launch(
                    android.content.Intent(activity, com.mcai.ubuntudsu.RootfsFilesActivity::class.java).apply {
                        putExtra(com.mcai.ubuntudsu.RootfsFilesActivity.EXTRA_PICK, true)
                        putExtra(com.mcai.ubuntudsu.RootfsFilesActivity.EXTRA_TITLE, "Select GSI installation package")
                        putExtra(com.mcai.ubuntudsu.RootfsFilesActivity.EXTRA_EXT, ".zip,.img,.gz,.xz")
                    },
                )
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = Ui.dp(8, d)
            },
        )
        parameterCard.addView(fileRow)

        parameterCard.addView(CheckBox(activity).apply {
            text = "Clear old cache before installation (required after a failed install)"
            textSize = 12f
            setTextColor(Ui.primaryText(activity))
            buttonTintList = android.content.res.ColorStateList.valueOf(Ui.secondaryText(activity))
            setPadding(Ui.dp(2, d), Ui.dp(4, d), 0, Ui.dp(2, d))
            setOnCheckedChangeListener { _, checked -> clearUserdata = checked }
        })
        parameterCard.addView(
            smallAction("Start Installation", Ui.buttonSuccess(activity)) { startInstall() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(6, d) },
        )
        page.addView(parameterCard)

        // 工具入口：2x2 大图标网格 (Reboot to DSU / Repair Environment / Remove Installed GSI / Wipe userdata)
        page.addView(
            label("DSU Tools", 12f, bold = true).apply { setTextColor(Ui.secondaryText(activity)) },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(10, d); bottomMargin = Ui.dp(8, d) },
        )
        val toolsRow1 = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        toolsRow1.addView(
            Ui.iconTile(activity, "Reboot to DSU", "Reboot into the GSI system", R.drawable.ic_dsu_restart, Ui.buttonWarning(activity)) {
                confirmAction("Reboot to DSU", "The device will reboot immediately into the GSI system.") {
                    executor.execute {
                        val service = privilegedService
                        if (service == null) {
                            log("ROOT DSU service is not connected")
                        } else if (!service.setEnable(true, true)) {
                            log("One-shot DSU boot setup failed")
                        } else if (!service.boot()) {
                            log("Failed to reboot to DSU")
                        }
                    }
                }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        toolsRow1.addView(
            Ui.iconTile(activity, "Repair Environment", "Clear metadata and prepare again", R.drawable.ic_dsu_repair) {
                confirmAction(
                    "Repair DSU environment",
                    "This will delete /metadata/gsi/dsu 和 /metadata/vold/metadata_encryption/dsu。This clears state left by the previous failed installation; selected GSI files will not be deleted.",
                ) {
                    executor.execute {
                        val result = DsuManager.restartDsuService(::log)
                        log(if (result.success) "DSU environment repaired" else "DSU environment repair failed: ${result.stderr}")
                    }
                }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Ui.dp(8, d) },
        )
        page.addView(toolsRow1)
        val toolsRow2 = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(8, d) }
        }
        toolsRow2.addView(
            Ui.iconTile(activity, "Remove Installed GSI", "Remove GSI and return to stock system", R.drawable.ic_dsu_undo, Ui.buttonDanger(activity)) {
                confirmAction("Remove GSI", "Delete /data/gsi/dsu/dsu，remove the installed GSI.") { executor.execute { DsuManager.wipe(::log) } }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        toolsRow2.addView(
            Ui.iconTile(activity, "Wipe userdata", "Wipe the userdata partition", R.drawable.ic_dsu_clear_userdata) {
                confirmAction("Wipe userdata", "Run gsi_tool wipe-data，clear only userdata partition data.") {
                    executor.execute {
                        val result = DsuManager.wipeData(::log)
                        log(if (result.success) "userdata cleared" else "userdata Cleanup failed: ${result.stderr}")
                    }
                }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Ui.dp(8, d) },
        )
        page.addView(toolsRow2)

        return page
    }

    private fun card(d: Float): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(Ui.dp(14, d), Ui.dp(12, d), Ui.dp(14, d), Ui.dp(12, d))
        background = Ui.glassSurface(activity, 18f)
        // 圆角 outline 投影：裸 elevation 对 LayerDrawable 背景会渲染成方形影子
        Ui.applyNeuShadow(this, 3f, 18f)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = Ui.dp(8, d) }
    }

    private fun label(text: String, size: Float, bold: Boolean = false): TextView = TextView(activity).apply {
        this.text = text
        textSize = size
        setTextColor(Ui.primaryText(activity))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun smallAction(text: String, accent: Int, minWidthDp: Int = 0, onClick: () -> Unit): TextView = TextView(activity).apply {
        this.text = text
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(Ui.buttonText(activity))
        background = Ui.glassButton(activity, accent)
        val d = activity.resources.displayMetrics.density
        setPadding(Ui.dp(14, d), Ui.dp(10, d), Ui.dp(14, d), Ui.dp(10, d))
        if (minWidthDp > 0) minimumWidth = Ui.dp(minWidthDp, d)
        Ui.pressAnimation(this)
        setOnClickListener { onClick() }
    }

    private fun selectSizeChip(row: LinearLayout, selected: Int) {
        for (index in 0 until row.childCount) {
            val chip = row.getChildAt(index) as? TextView ?: continue
            val isSelected = chip.text.toString().removeSuffix(" GB").toIntOrNull() == selected
            chip.setTextColor(if (isSelected) Ui.buttonText(activity) else Ui.primaryText(activity))
            chip.background = Ui.strokeRounded(
                if (isSelected) Ui.buttonPrimary(activity) else Ui.surfaceGlass(activity),
                if (isSelected) Ui.buttonPrimary(activity) else Ui.border(activity),
                1f,
                activity.resources.displayMetrics.density,
                15f,
            )
        }
    }

    private fun confirmAction(title: String, message: String, action: () -> Unit) {
        showDialog(
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK") { _, _ -> action() }
                .setNegativeButton("Cancel", null)
        )
    }

    private fun showDialog(builder: AlertDialog.Builder) {
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(Ui.rounded(Ui.dsuCard(activity), 24f, activity.resources.displayMetrics.density))
            dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(Ui.secondaryText(activity))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Ui.buttonPrimary(activity))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Ui.secondaryText(activity))
        }
        dialog.show()
    }

    // 统一更新安装进度条与下方百分比
    private fun setInstallProgress(percent: Int, text: String) {
        installProgressBar.progress = percent
        installProgressLabel.text = text
        installPercentText.text = "$percent %"
    }    private fun startInstall() {        val zipUri = selectedZip ?: run {
            Toast.makeText(activity, "Select a GSI ZIP package first", Toast.LENGTH_SHORT).show()
            return
        }
        val service = privilegedService ?: run {
            Toast.makeText(activity, "ROOT DSU installer is not connected. Grant ROOT access and try again.", Toast.LENGTH_SHORT).show()
            return
        }
        confirmAction("Start DSU Installation", "将通过 ROOT DSU ROOT DSU installer will create the partitions directly and write the GSI image.") {
            executor.execute {
                if (clearUserdata) {
                    log("Clearing old cache: /metadata/gsi/dsu/dsu/lp_metadata")
                    val clearResult = DsuManager.clearInstallCache(::log)
                    check(clearResult.success) { "Failed to clean old cache: ${clearResult.stderr}" }
                }
                runCatching {
                    val path = zipUri.path ?: error("Unable to read GSI ZIP")
                    // 先试普通流 (可直读秒开)；失败或无权限降级 root 流 (su cat，零拷贝)
                    val input = runCatching {
                        java.io.File(path).takeIf { it.canRead() }?.inputStream()
                    }.getOrNull()
                        ?: com.mcai.ubuntudsu.core.RootShell.openStream(path)
                    log("Reading GSI ZIP directly from the selected location")
                    input.use { installWithRootService(it, service) }
                }.onFailure {
                    log("Failed to read GSI ZIP: ${it.message}")
                    activity.runOnUiThread { installProgressLabel.text = "GSI installation: read failed" }
                }
            }
        }
    }

    private fun installWithRootService(input: java.io.InputStream, service: IPrivilegedService) {
        activity.runOnUiThread { setInstallProgress(0, "GSI installation: parsing images") }
        var started = false
        var completed = false
        try {
            ZipInputStream(java.io.BufferedInputStream(input, 1024 * 1024)).use { zip ->
                if (!service.isAvailable()) error("System dynamic_system service is unavailable")
                if (!service.startInstallation("dsu")) error("dynamic_system rejected start installation")
                started = true
                val partitions = HashSet<String>()
                var imageCount = 0
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.isDirectory || !entry.name.substringAfterLast('/').endsWith(".img", true)) {
                        zip.closeEntry()
                        entry = zip.nextEntry
                        continue
                    }
                    val name = entry.name.substringAfterLast('/').removeSuffix(".img").lowercase()
                    if (!name.matches(Regex("[a-z0-9_-]+")) || !partitions.add(name)) error("Invalid or duplicate partition: $name")
                    val size = entry.size
                    if (size <= 0) error("Image is empty: $name")
                    val partitionSize = if (name == "userdata") maxOf(size, selectedUserdataGB.toLong() * 1024 * 1024 * 1024) else size
                    val result = service.createPartition(name, partitionSize, name != "userdata")
                    if (result != 0) error("Failed to create partition: $name ($result)")
                    streamImage(zip, size, name, service, imageCount)
                    if (!service.closePartition()) error("Failed to close partition: $name")
                    imageCount++
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
                if (imageCount == 0) error("No usable img images in ZIP")
                if (!partitions.contains("userdata")) {
                    if (service.createPartition("userdata", selectedUserdataGB.toLong() * 1024 * 1024 * 1024, false) != 0) error("Failed to create userdata")
                    if (!service.closePartition()) error("Failed to close userdata")
                }
                if (!service.finishInstallation()) error("Failed to finish DSU installation")
                if (!service.setEnable(false, false)) error("Failed to disable DSU auto-boot")
                completed = true
            }
            activity.runOnUiThread { setInstallProgress(100, "GSI installation: complete"); Toast.makeText(activity, "GSI installed. Tap “Reboot to DSU” when needed", Toast.LENGTH_LONG).show() }
        } catch (e: Exception) {
            if (started && !completed) runCatching { service.abort() }
            log("ROOT DSU Installation failed: ${e.message}")
            activity.runOnUiThread { installProgressLabel.text = "GSI installation: failed"; Toast.makeText(activity, "Installation failed: ${e.message}", Toast.LENGTH_LONG).show() }
        }
    }

    private fun streamImage(input: java.io.InputStream, size: Long, name: String, service: IPrivilegedService, index: Int) {
        val bufferSize = 4 * 1024 * 1024
        SharedMemory.create("ubuntudsu-$name", bufferSize).use { memory ->
            sharedMemoryFd(memory).use { fd ->
                if (!service.setAshmem(fd, bufferSize.toLong())) error("Shared memory initialization failed: $name")
                val mapped = memory.mapReadWrite()
                try {
                    val buffer = ByteArray(1024 * 1024)
                    var written = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        mapped.position(0)
                        mapped.put(buffer, 0, count)
                        if (!service.submitFromAshmem(count.toLong())) error("Write failed: $name")
                        written += count
                        val imageProgress = if (size > 0) (written * 80L / size).toInt() else 0
                        val progress = (10 + imageProgress).coerceIn(10, 90)
                        activity.runOnUiThread { setInstallProgress(progress, "GSI installation: writing $name") }
                    }
                } finally { SharedMemory.unmap(mapped) }
            }
        }
    }

    private fun sharedMemoryFd(memory: SharedMemory): ParcelFileDescriptor {
        return runCatching { SharedMemory::class.java.getMethod("getFdDup").invoke(memory) as ParcelFileDescriptor }
            .getOrElse { ParcelFileDescriptor.fromFd(SharedMemory::class.java.getDeclaredMethod("getFd").apply { isAccessible = true }.invoke(memory) as Int) }
    }

    private fun log(line: String) {
        android.util.Log.i("DsuPage", line)
    }
}
