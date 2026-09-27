package com.mcai.ubuntudsu.ui.pages

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.mcai.ubuntudsu.core.Aria2c
import com.mcai.ubuntudsu.core.RootShell
import com.mcai.ubuntudsu.ui.Haptics
import com.mcai.ubuntudsu.ui.Ui
import java.io.File

/**
 * U盘启动页：把手机当 U 盘 / 安装盘使用。
 *
 * 核心能力（面向电脑安装 Windows 场景）：
 *  1. 在线获取 Windows 11 ISO（官方 ARM64 下载页 + 自定义直链走内置 aria2c 多线程下载）
 *  2. 用 Android USB Gadget（usb_gadget）框架把指定 ISO/IMG 暴露给电脑，
 *     电脑 BIOS 里选 U 盘/光盘启动即可安装系统
 *  3. 制作虚拟 U 盘 IMG 镜像（truncate + 可选 FAT32 格式化）
 *  4. 把已生成的 IMG 挂载到本地 /mnt/TimeVUD 进行读写
 *
 * 无 root 时降级为「下载 ISO → 提示用电脑 Rufus / 制作启动盘」。
 * 实现参考用户提供的 VIRTUAL 脚本（TimeVUD / CreateImg / MountImg / CheckVUD）。
 */
class UsbBootPage(
    private val activity: Activity,
    private val onBack: () -> Unit,
) {
    private val ctx: Context get() = activity
    private val d: Float get() = activity.resources.displayMetrics.density

    // ===== 下载状态 =====
    private val downloading = java.util.concurrent.atomic.AtomicBoolean(false)
    private var downloadThread: Thread? = null
    private var cancelFlag = java.util.concurrent.atomic.AtomicBoolean(false)

    // UI 引用（构建后填充）
    private var isoStatusText: TextView? = null
    private var isoPathText: TextView? = null
    private var isoProgress: ProgressBar? = null
    private var isoPercentText: TextView? = null
    private var vudStatusText: TextView? = null
    private var vudTypeText: TextView? = null
    private var selinuxText: TextView? = null
    private var startVudBtn: TextView? = null
    private var stopVudBtn: TextView? = null
    private var cancelIsoBtn: TextView? = null

    // 当前选中的 VUD 连接模式
    private var selectedVudType = "cdrom"

    // 内置文件选择器：ISO 源文件（浏览选择后作为 VUD 启动源）
    private var pickedSourceFile: File? = null

    // 自定义直链下载目标
    private var customDownloadUrl = ""
    private var customDownloadName = ""

    // 临时目录（与脚本保持一致，使用 $TMPDIR 风格）
    private val tmpDir = "/data/local/tmp/TimeVUD"

    // 内置文件选择器 request code
    private val isoPickerRequest = 411

    fun build(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(16, d), Ui.dp(12, d), Ui.dp(16, d), Ui.dp(16, d))
        }

        root.addView(buildTopBar())
        root.addView(buildRootStatusCard())
        root.addView(buildIsoCard())
        root.addView(buildVudCard())
        root.addView(buildHelpCard())

        refreshAll()
        return root
    }

    // ==================== 顶栏 ====================
    private fun buildTopBar(): View {
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(4, d), 0, Ui.dp(10, d))
        }
        bar.addView(TextView(activity).apply {
            text = "< 返回"
            textSize = 13f
            setTextColor(Ui.buttonText(activity))
            gravity = Gravity.CENTER
            background = Ui.glassButton(activity, Ui.buttonPrimary(activity))
            setPadding(Ui.dp(12, d), Ui.dp(6, d), Ui.dp(12, d), Ui.dp(6, d))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { Haptics.perform(this); onBack() }
            Ui.pressAnimation(this)
        })
        TextView(activity).apply {
            text = "U盘启动"
            textSize = 18f
            setTypeface(Ui.typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }.let { bar.addView(it) }
        TextView(activity).apply {
            text = " "
            layoutParams = LinearLayout.LayoutParams(Ui.dp(56, d), ViewGroup.LayoutParams.WRAP_CONTENT)
        }.let { bar.addView(it) }
        return bar
    }

    // ==================== Root + SELinux 状态卡片 ====================
    private fun buildRootStatusCard(): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(14, d), Ui.dp(10, d), Ui.dp(14, d), Ui.dp(10, d))
            background = Ui.glassSurface(activity, 18f)
            Ui.applyNeuShadow(this, 2f, 18f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = Ui.dp(10, d)
            }
        }
        card.addView(TextView(activity).apply {
            text = "环境检测"
            textSize = 13f
            setTypeface(Ui.typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
        })

        selinuxText = TextView(activity).apply {
            text = "正在检测..."
            textSize = 11f
            setTextColor(Ui.secondaryText(activity))
            setPadding(0, Ui.dp(4, d), 0, 0)
        }
        card.addView(selinuxText)

        return card
    }

    // ==================== 在线下载 Win11 ISO（自定义直链） ====================
    private fun buildIsoCard(): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(14, d), Ui.dp(12, d), Ui.dp(14, d), Ui.dp(12, d))
            background = Ui.glassSurface(activity, 18f)
            Ui.applyNeuShadow(this, 2f, 18f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = Ui.dp(10, d)
            }
        }

        card.addView(TextView(activity).apply {
            text = "在线下载 Windows 11 ISO"
            textSize = 13f
            setTypeface(Ui.typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
        })
        card.addView(TextView(activity).apply {
            text = "粘贴 ISO/IMG 直链，内置 aria2c 多线程下载 + 进度 + 取消"
            textSize = 11f
            setTextColor(Ui.secondaryText(activity))
            setPadding(0, Ui.dp(2, d), 0, Ui.dp(8, d))
        })

        // 自定义直链（内置 aria2c 下载）
        card.addView(TextView(activity).apply {
            text = "自定义直链"
            textSize = 12f
            setTypeface(Ui.typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
            setPadding(0, Ui.dp(4, d), 0, Ui.dp(4, d))
        })
        val urlInput = android.widget.EditText(activity).apply {
            hint = "粘贴 ISO/IMG 直链（https://...）"
            textSize = 12f
            setTextColor(Ui.primaryText(activity))
            setHintTextColor(Ui.secondaryText(activity))
            background = Ui.neuInset(activity, 12f)
            setPadding(Ui.dp(10, d), Ui.dp(8, d), Ui.dp(10, d), Ui.dp(8, d))
            isSingleLine = true
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    customDownloadUrl = s?.toString()?.trim() ?: ""
                }
            })
        }
        card.addView(urlInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val nameInput = android.widget.EditText(activity).apply {
            hint = "可选：本地文件名（默认取链接名）"
            textSize = 12f
            setTextColor(Ui.primaryText(activity))
            setHintTextColor(Ui.secondaryText(activity))
            background = Ui.neuInset(activity, 12f)
            setPadding(Ui.dp(10, d), Ui.dp(8, d), Ui.dp(10, d), Ui.dp(8, d))
            isSingleLine = true
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    customDownloadName = s?.toString()?.trim() ?: ""
                }
            })
        }
        card.addView(nameInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Ui.dp(6, d)
        })

        isoStatusText = TextView(activity).apply {
            text = "未下载"
            textSize = 11f
            setTextColor(Ui.secondaryText(activity))
            setPadding(0, Ui.dp(10, d), 0, 0)
        }
        card.addView(isoStatusText)

        isoPathText = TextView(activity).apply {
            text = ""
            textSize = 10f
            setTextColor(Ui.secondaryText(activity))
            setPadding(0, Ui.dp(2, d), 0, 0)
        }
        card.addView(isoPathText)

        isoProgress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressDrawable = Ui.pillProgressDrawable(activity)
            visibility = View.GONE
        }
        card.addView(isoProgress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Ui.dp(10, d)
        })

        isoPercentText = TextView(activity).apply {
            text = ""
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Ui.secondaryText(activity))
            visibility = View.GONE
        }
        card.addView(isoPercentText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Ui.dp(4, d)
        })

        cancelIsoBtn = makePillButton("取消", Ui.buttonDanger(activity)) { cancelFlag.set(true) }
        cancelIsoBtn?.visibility = View.GONE
        card.addView(cancelIsoBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            topMargin = Ui.dp(6, d)
        })

        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        btnRow.addView(makePillButton("开始下载", Ui.buttonPrimary(activity)) { startIsoDownload() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btnRow.addView(View(activity).apply { layoutParams = LinearLayout.LayoutParams(Ui.dp(8, d), 0) })
        btnRow.addView(makePillButton("用电脑制作启动盘", Ui.buttonSecondary(activity)) { openRufuGuide() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(btnRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Ui.dp(10, d)
        })

        return card
    }

    // ==================== 虚拟 U 盘（USB Gadget 暴露 ISO） ====================
    private fun buildVudCard(): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(14, d), Ui.dp(12, d), Ui.dp(14, d), Ui.dp(12, d))
            background = Ui.glassSurface(activity, 18f)
            Ui.applyNeuShadow(this, 2f, 18f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = Ui.dp(10, d)
            }
        }

        card.addView(TextView(activity).apply {
            text = "虚拟 U 盘启动"
            textSize = 13f
            setTypeface(Ui.typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
        })
        card.addView(TextView(activity).apply {
            text = "把 ISO 通过 USB Gadget 暴露给电脑"
            textSize = 11f
            setTextColor(Ui.secondaryText(activity))
            setPadding(0, Ui.dp(2, d), 0, 0)
        })

        // 连接模式选择
        val vudTypes = listOf("cdrom", "ro", "rw")
        val typeRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val typePills = mutableListOf<TextView>()
        vudTypes.forEachIndexed { i, t ->
            val label = when (t) {
                "rw" -> "U盘读写"
                "ro" -> "U盘只读"
                else -> "CD只读"
            }
            val pill = TextView(activity).apply {
                text = label
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(if (i == 0) Color.WHITE else Ui.secondaryText(activity))
                background = if (i == 0) Ui.glassButton(activity, Ui.buttonPrimary(activity))
                else Ui.neuInset(activity, 10f)
                setPadding(Ui.dp(8, d), Ui.dp(4, d), Ui.dp(8, d), Ui.dp(4, d))
                setOnClickListener {
                    selectedVudType = t
                    typePills.forEach { p ->
                        val isSel = p.text.toString() == label
                        p.setTextColor(if (isSel) Color.WHITE else Ui.secondaryText(activity))
                        p.background = if (isSel) Ui.glassButton(activity, Ui.buttonPrimary(activity))
                        else Ui.neuInset(activity, 10f)
                    }
                }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = Ui.dp(4, d)
                }
            }
            typePills.add(pill)
            typeRow.addView(pill)
        }
        card.addView(typeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Ui.dp(6, d)
        })

        // 当前 ISO 选择
        card.addView(TextView(activity).apply {
            text = "ISO 源文件"
            textSize = 12f
            setTypeface(Ui.typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
            setPadding(0, Ui.dp(6, d), 0, Ui.dp(3, d))
        })

        val isoPickRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        isoPickRow.addView(TextView(activity).apply {
            text = "自动选择已下载的 ISO"
            textSize = 11f
            setTextColor(Ui.secondaryText(activity))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        isoPickRow.addView(makePillButton("浏览 ISO/IMG...", Ui.buttonPrimary(activity)) { pickIsoFile() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(isoPickRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // VUD 状态
        vudStatusText = TextView(activity).apply {
            text = "未启动"
            textSize = 11f
            setTextColor(Ui.secondaryText(activity))
            setPadding(0, Ui.dp(6, d), 0, 0)
        }
        card.addView(vudStatusText)

        vudTypeText = TextView(activity).apply {
            text = ""
            textSize = 10f
            setTextColor(Ui.secondaryText(activity))
            setPadding(0, Ui.dp(2, d), 0, 0)
        }
        card.addView(vudTypeText)

        // 启停按钮
        val vudBtnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        startVudBtn = makePillButton("启动虚拟 U 盘", Ui.buttonSuccess(activity)) { startVud() }
        stopVudBtn = makePillButton("停止", Ui.buttonDanger(activity)) { stopVud() }
        vudBtnRow.addView(startVudBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        vudBtnRow.addView(View(activity).apply { layoutParams = LinearLayout.LayoutParams(Ui.dp(8, d), 0) })
        vudBtnRow.addView(stopVudBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(vudBtnRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Ui.dp(6, d)
        })

        return card
    }

    // ==================== 使用说明 ====================
    private fun buildHelpCard(): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(14, d), Ui.dp(12, d), Ui.dp(14, d), Ui.dp(12, d))
            background = Ui.glassSurface(activity, 18f)
            Ui.applyNeuShadow(this, 2f, 18f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = Ui.dp(10, d)
            }
        }

        card.addView(TextView(activity).apply {
            text = "使用说明"
            textSize = 13f
            setTypeface(Ui.typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
        })
        card.addView(makeHelpLine("① 连接手机到电脑，开启 USB 调试 / MTP 模式"))
        card.addView(makeHelpLine("② 在线获取 Win11 ISO（官方 ARM64 下载页 / 自定义直链）"))
        card.addView(makeHelpLine("③ 启动虚拟 U 盘（root 下 USB Gadget 框架）"))
        card.addView(makeHelpLine("④ 电脑 BIOS 里选 U 盘 / 光盘启动，按提示安装"))
        card.addView(makeHelpLine("无 root？用电脑 Rufus 把 ISO 写入 U 盘制作启动盘"))

        return card
    }

    // ==================== 通用组件 ====================
    private fun makePillButton(text: String, accent: Int, onClick: () -> Unit): TextView {
        val minH = 44f * d
        val halfH = ((minH / 2) - 14f * d / 2).toInt()
        val tv = TextView(activity).apply {
            this.text = text
            textSize = 13f
            setTypeface(Ui.typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Ui.neuSolidButton(accent, accent, 14f, activity)
            setPadding(Ui.dp(12, d), halfH, Ui.dp(12, d), halfH)
            setOnClickListener { Haptics.perform(this); onClick() }
            Ui.pressAnimation(this)
        }
        return tv
    }

    private fun makeHelpLine(text: String): TextView = TextView(activity).apply {
        this.text = text
        textSize = 11f
        setTextColor(Ui.secondaryText(activity))
        setPadding(0, Ui.dp(4, d), 0, 0)
        lineHeight = (11f * 1.5f * d).toInt()
    }

    // ==================== 环境检测（SELinux + USB Gadget 支持） ====================
    private fun refreshAll() {
        Thread {
            val se = detectSelinux()
            val gadgetSupport = detectGadgetSupport()
            val vudRunning = detectVudRunning()
            val iso = findLatestIso()
            activity.runOnUiThread {
                selinuxText?.text = se
                vudStatusText?.text = if (vudRunning) "正在运行" else "未启动"
                vudTypeText?.text = iso?.let { "ISO 源文件：${it.name}" } ?: ""
                isoStatusText?.text = iso?.let { "已下载：${it.name}" } ?: "未下载"
                isoPathText?.text = iso?.absolutePath ?: ""
                startVudBtn?.isEnabled = gadgetSupport
                stopVudBtn?.isEnabled = gadgetSupport
            }
        }.start()
    }

    private fun detectSelinux(): String {
        val r = RootShell.exec("getenforce", 5000)
        val s = r.stdout.trim()
        return when {
            s.equals("Enforcing", true) -> "SELinux 强制模式 · Root 可用"
            s.equals("Permissive", true) -> "SELinux 宽容模式 · Root 可用"
            s.equals("Disabled", true) -> "SELinux 已关闭 · Root 可用"
            r.code != 0 -> "未检测到 Root（虚拟 U 盘不可用，可继续下载 ISO）"
            else -> "SELinux 状态未知"
        }
    }

    private fun detectGadgetSupport(): Boolean {
        val r = RootShell.exec(
            "ls /config/usb_gadget/g1/functions 2>/dev/null | grep -c mass_storage",
            8000,
        )
        return r.code == 0 && r.stdout.trim().toIntOrNull()?.let { it > 0 } == true
    }

    private fun detectVudRunning(): Boolean {
        val r = RootShell.exec(
            "ls /config/usb_gadget/g1/configs/b.1 2>/dev/null | grep -c mass_storage",
            8000,
        )
        return r.code == 0 && r.stdout.trim().toIntOrNull()?.let { it > 0 } == true
    }

    // ==================== 虚拟 U 盘 启停（参考 TimeVUD.sh） ====================
    private fun startVud() {
        val iso = pickIsoForVud()
        if (iso == null) {
            AlertDialog.Builder(activity)
                .setTitle("未找到可用的 ISO")
                .setMessage("请先在线下载 Windows 11 ISO，或手动浏览选择 ISO 文件。")
                .setPositiveButton("关闭", null)
                .show()
            return
        }
        Thread {
            val roFile = when (selectedVudType) { "ro" -> "1"; "cdrom" -> "1"; else -> "0" }
            val cdromFile = when (selectedVudType) { "cdrom" -> "1"; else -> "0" }
            val script = buildString {
                append("mkdir -p '$tmpDir' 2>/dev/null; \n")
                append("usbcfg=\$(ls /config/usb_gadget/g1/functions 2>/dev/null | grep mass_storage | head -n 1);\n")
                append("mass_storagpath=\"/config/usb_gadget/g1/functions/\$usbcfg\";\n")
                append("if [ -z \"\$usbcfg\" ]; then echo 'unsupported' > '$tmpDir/vuderr'; exit 1; fi\n")
                append("oemname=\$(cat /config/usb_gadget/g1/strings/0x409/manufacturer 2>/dev/null);\n")
                append("find /config/usb_gadget/g1/configs/b.1/ -maxdepth 1 -type l -print0 | xargs -0 rm -f -- 2>/dev/null;\n")
                append("(ln -s \"\$mass_storagpath\" /config/usb_gadget/g1/configs/b.1/\$usbcfg >/dev/null 2>&1) || { echo 'vud fail' > '$tmpDir/vuderr'; exit 1; }\n")
                append("echo '' > \$mass_storagpath/lun.0/file\n")
                append("echo '$roFile' > \$mass_storagpath/lun.0/ro\n")
                append("echo '$cdromFile' > \$mass_storagpath/lun.0/cdrom\n")
                append("echo 1 > \$mass_storagpath/lun.0/removable\n")
                append("echo 1 > \$mass_storagpath/lun.0/nofua\n")
                append("echo \"\${oemname}_TimeVUD\" > \$mass_storagpath/lun.0/inquiry_string\n")
                append("echo '$iso' > \$mass_storagpath/lun.0/file\n")
                append("echo '$selectedVudType' > '$tmpDir/VUDtype'\n")
                append("echo '$iso' > '$tmpDir/imgfile'\n")
                append("setprop sys.usb.config cdrom,mtp\n")
                append("setprop sys.usb.state cdrom,mtp\n")
                append("echo '' > /config/usb_gadget/g1/UDC\n")
                append("sleep 0.2\n")
                append("getprop sys.usb.controller > /config/usb_gadget/g1/UDC\n")
            }
            val r = RootShell.exec(script, 20000)
            val errFile = File("$tmpDir/vuderr")
            val err = if (errFile.exists()) runCatching { errFile.readText() }.getOrNull()?.trim().orEmpty() else ""
            activity.runOnUiThread {
                if (r.code == 0 && !err.equals("vud fail", true) && !err.equals("unsupported", true)) {
                    vudStatusText?.text = "正在运行（$selectedVudType 模式）"
                    vudTypeText?.text = "ISO：$iso"
                } else {
                    vudStatusText?.text = "启动失败：${err.ifBlank { "检查 USB Gadget 是否支持 mass_storage（需 root）" }}"
                }
            }
        }.start()
    }

    private fun stopVud() {
        Thread {
            val script = buildString {
                append("usbcfg=\$(ls /config/usb_gadget/g1/functions 2>/dev/null | grep mass_storage | head -n 1);\n")
                append("mass_storagpath=\"/config/usb_gadget/g1/functions/\$usbcfg\";\n")
                append("find /config/usb_gadget/g1/configs/b.1/ -maxdepth 1 -type l -print0 | xargs -0 rm -f -- 2>/dev/null;\n")
                append("echo '' > \$mass_storagpath/lun.0/file\n")
                append("echo 0 > \$mass_storagpath/lun.0/removable\n")
                append("echo 0 > \$mass_storagpath/lun.0/ro\n")
                append("echo 0 > \$mass_storagpath/lun.0/cdrom\n")
                append("echo '' > \$mass_storagpath/lun.0/inquiry_string\n")
                append("setprop sys.usb.config mtp\n")
                append("setprop sys.usb.state mtp\n")
                append("echo '' > '$tmpDir/VUDtype'\n")
            }
            RootShell.exec(script, 15000)
            activity.runOnUiThread {
                vudStatusText?.text = "已停止"
                vudTypeText?.text = ""
            }
        }.start()
    }

    // ==================== ISO 文件选择（内置文件选择器） ====================
    private fun pickIsoForVud(): String? {
        val f = pickedSourceFile ?: findLatestIso()
        return f?.absolutePath
    }

    /** 浏览选择 ISO/IMG：调用 APP 内置文件选择器（RootfsFilesActivity） */
    private fun pickIsoFile() {
        activity.startActivityForResult(
            android.content.Intent(activity, com.mcai.ubuntudsu.RootfsFilesActivity::class.java).apply {
                putExtra(com.mcai.ubuntudsu.RootfsFilesActivity.EXTRA_PICK, true)
                putExtra(com.mcai.ubuntudsu.RootfsFilesActivity.EXTRA_TITLE, "选择 ISO / IMG 启动源")
                putExtra(com.mcai.ubuntudsu.RootfsFilesActivity.EXTRA_EXT, ".iso,.img")
            },
            isoPickerRequest,
        )
    }

    /** 宿主 Activity.onActivityResult 派发到本类 */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        if (requestCode != isoPickerRequest || resultCode != Activity.RESULT_OK) return
        val path = data?.getStringExtra(com.mcai.ubuntudsu.RootfsFilesActivity.RESULT_FILE_PATH) ?: return
        val file = java.io.File(path)
        pickedSourceFile = file
        vudTypeText?.text = "ISO 源文件：${file.name}"
    }

    private fun findLatestIso(): File? {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Win11")
        return dir.listFiles { f -> f.name.endsWith(".iso", true) }?.lastOrNull()
    }

    private fun startIsoDownload() {
        val url = customDownloadUrl
        if (url.isBlank()) {
            isoStatusText?.text = "请先粘贴 ISO/IMG 直链"
            return
        }
        if (downloading.getAndSet(true)) return
        cancelFlag.set(false)
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Win11")
        if (!dir.exists()) dir.mkdirs()
        val name = customDownloadName.ifBlank { Aria2c.fileNameFromUrl(url) }
        val target = File(dir, name)

        isoProgress?.visibility = View.VISIBLE
        isoPercentText?.visibility = View.VISIBLE
        isoPercentText?.text = "0%"
        isoProgress?.progress = 0
        cancelIsoBtn?.visibility = View.VISIBLE
        isoStatusText?.text = "正在下载..."

        downloadThread = Thread {
            var lastError = "未开始"
            if (cancelFlag.get()) {
                lastError = "已取消"
            } else {
                val r = Aria2c.download(
                    ctx, url, target,
                    onProgress = { p ->
                        activity.runOnUiThread {
                            isoProgress?.progress = p
                            isoPercentText?.text = "$p%"
                        }
                    },
                    onLog = { line ->
                        activity.runOnUiThread {
                            isoStatusText?.text = line.take(60)
                        }
                    },
                    isCancelled = { cancelFlag.get() },
                )
                lastError = if (r.success) "成功" else r.message
            }
            downloading.set(false)
            activity.runOnUiThread {
                cancelIsoBtn?.visibility = View.GONE
                isoProgress?.visibility = View.GONE
                isoPercentText?.visibility = View.GONE
                if (cancelFlag.get() && lastError == "已取消") {
                    isoStatusText?.text = "已取消"
                } else if (lastError == "成功") {
                    isoStatusText?.text = "已下载完成：${target.name}"
                    isoPathText?.text = target.absolutePath
                } else {
                    isoStatusText?.text = "下载失败：${lastError.take(60)}"
                }
            }
        }.also { it.start() }
    }

    // ==================== 电脑制作启动盘引导 ====================
    private fun openRufuGuide() {
        AlertDialog.Builder(activity)
            .setTitle("用电脑制作启动盘")
            .setMessage(
                "1. 电脑安装 Rufus（https://rufus.ie）\n" +
                    "2. 手机下载 ISO 后用数据线连电脑，从手机存储里拷出 ISO\n" +
                    "3. Rufus 里选 ISO + 启动 U 盘 → 开始\n" +
                    "4. 电脑 BIOS 选 U 盘启动即可安装 Win11",
            )
            .setPositiveButton("打开 Rufus 官网") { _, _ ->
                runCatching {
                    activity.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("https://rufus.ie")))
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    fun cleanup() {
        cancelFlag.set(true)
        downloadThread?.interrupt()
    }
}
