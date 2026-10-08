package com.mcai.ubuntudsu.ui.pages

import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.mcai.ubuntudsu.R
import com.mcai.ubuntudsu.RootfsFilesActivity
import com.mcai.ubuntudsu.RootfsInstallActivity
import com.mcai.ubuntudsu.TerminalActivity
import com.mcai.ubuntudsu.core.Env
import com.mcai.ubuntudsu.core.RootShell
import com.mcai.ubuntudsu.core.TerminalSessionStore
import com.mcai.ubuntudsu.ui.Ui
import androidx.appcompat.app.AlertDialog
import java.util.concurrent.Executor

class LinuxPage(
    private val activity: android.app.Activity,
    private val executor: Executor,
) {
    private lateinit var infoText: TextView
    private lateinit var installHint: TextView

    fun build(): View {
        val d = activity.resources.displayMetrics.density
        val page = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(16, d), Ui.dp(12, d), Ui.dp(16, d), Ui.dp(16, d))
        }
        // 标题行：左侧"Linux ARM® Architecture"（设置入口仅在首页）
        page.addView(TextView(activity).apply {
            text = "Linux ARM® Architecture"
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
            setPadding(0, 0, 0, Ui.dp(14, d))
        })

        val infoCard = card()
        infoText = TextView(activity).apply {
            textSize = 12f
            setTextColor(Ui.primaryText(activity))
        }
        infoCard.addView(infoText)
        page.addView(infoCard)

        installHint = TextView(activity).apply {
            text = "Ubuntu rootfs Not installed，Tap below to“Install rootfs System”to start。"
            textSize = 11f
            setTextColor(if (Ui.isDark(activity)) Color.parseColor("#FFB4A8") else Color.parseColor("#B5473B"))
            setPadding(Ui.dp(4, d), Ui.dp(6, d), Ui.dp(4, d), 0)
        }
        page.addView(installHint)

        page.addView(spacer(6))
        // Install/Uninstall二合一卡片：一张拟态玻璃卡内两行入口，水晶玻璃渲染条分格
        val manageCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.glassSurface(activity, 18f)
            Ui.applyNeuShadow(this, 3f, 18f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = Ui.dp(8, d) }
        }
        manageCard.addView(
            actionRow(R.drawable.icon_install_rootfs, "Install rootfs System", "Local install · Cloud download · Backup") {
                activity.startActivity(Intent(activity, RootfsInstallActivity::class.java))
            },
        )
        manageCard.addView(
            Ui.crystalDivider(activity, d),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(2, d)),
        )
        manageCard.addView(
            actionRow(R.drawable.icon_trash_rootfs, "Uninstall rootfs System", "Delete the installed Ubuntu System") { confirmUninstall() },
        )
        page.addView(manageCard)

        page.addView(
            sectionLabel("Runtime environment", d),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(10, d); bottomMargin = Ui.dp(8, d) },
        )
        // 大图标入口：一排两个往下排（Container terminal / Desktop Environment），第三项File manager独占一排
        // 大图标直接悬浮在卡片/页面上（已去图标底色），仅靠大字号 emoji 图标 + 标题 + 描述
        val tileRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        tileRow.addView(
            Ui.iconTile(activity, "Container terminal", "Chroot Container · Termux style", R.drawable.icon_terminal_runner, Color.parseColor("#E95420")) {
                requireRootfs {
                    activity.startActivity(Intent(activity, TerminalActivity::class.java))
                }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        tileRow.addView(
            Ui.iconTile(activity, "Desktop Environment", "XFCE · KDE · GNOME + VNC", R.drawable.icon_linux_modern, Color.parseColor("#2D64AA")) {
                requireRootfs {
                    activity.startActivity(
                        Intent(activity, TerminalActivity::class.java).apply {
                            putExtra(TerminalActivity.EXTRA_DESKTOP, true)
                        },
                    )
                }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Ui.dp(8, d) },
        )
        page.addView(tileRow)
        page.addView(
            Ui.iconTile(activity, "File manager", "Browse · edit files inside rootfs", R.drawable.ic_folder_manager, Color.parseColor("#6C4AC2")) {
                requireRootfs {
                    activity.startActivity(Intent(activity, RootfsFilesActivity::class.java))
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(8, d) },
        )

        refreshInfo()
        return page
    }

    // rootfs Not installed时自动弹出Install界面，Installed则执行后续动作
    private fun requireRootfs(action: () -> Unit) {
        if (Env.ubuntuInstalled(activity)) {
            action()
        } else {
            Toast.makeText(activity, "Ubuntu rootfs Not installed，complete the installation first", Toast.LENGTH_SHORT).show()
            activity.startActivity(Intent(activity, RootfsInstallActivity::class.java))
        }
    }

    fun refreshInfo() {
        val installed = Env.ubuntuInstalled(activity)
        val path = Env.rootfs(activity).path
        installHint.visibility = if (installed) View.GONE else View.VISIBLE
        if (!installed) {
            infoText.text = "System version information：Not installed\nStatus: Not installed\nAfter installation, access Linux through the terminal (Chroot + Root)"
            return
        }
        val version = rootfsVersion()
        infoText.text = "System version information：$version\nStatus: Installed\nPath: $path"
    }

    private fun rootfsVersion(): String {
        val rootfs = Env.rootfs(activity)
        val candidates = listOf("etc/version_info", "etc/version", "etc/ubuntu_version", "etc/os-release")
        val file = candidates.asSequence().map { java.io.File(rootfs, it) }.firstOrNull { it.isFile }
        return runCatching { file?.readText(Charsets.UTF_8)?.trim() }.getOrNull()
            ?.lineSequence()?.firstOrNull { it.isNotBlank() }?.take(120)
            ?.ifBlank { "Version information file not found" } ?: "Version information file not found"
    }

    private fun confirmUninstall() {
        if (!Env.ubuntuInstalled(activity)) {
            Toast.makeText(activity, "No Linux rootfs is installed", Toast.LENGTH_SHORT).show()
            return
        }
        val running = TerminalSessionStore.takeRunning()
        if (running != null) {
            AlertDialog.Builder(activity)
                .setTitle("Terminal is running")
                .setMessage("End the terminal process and unmount first, then delete rootfs.")
                .setPositiveButton("Close terminal") { _, _ ->
                    running.finishIfRunning()
                    Toast.makeText(activity, "Terminal close requested; uninstall after it closes", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("Uninstall Linux")
            .setMessage("This will delete the rootfs directory and all data. This cannot be undone. Continue?")
            .setPositiveButton("Uninstall") { _, _ ->
                executor.execute {
                    val rootfs = Env.rootfs(activity)
                    val ref = rootfs.absolutePath
                    // rootfs 内的 /dev /dev/pts /proc /sys 是宿主内核的全局 bind/内核挂载，
                    // umount 任何一个都会破坏宿主内核挂载表导致定屏，绝不 umount。
                    // 先杀掉占用挂载的 VNC/X 进程，然后只删非挂载子目录内容，
                    // 挂载点目录本身保留（EBUSY 删不掉，|| true 吞掉）。
                    val killLines = """toybox pkill -9 Xvnc 2>/dev/null || true
toybox pkill -9 -f startxfce4 2>/dev/null || true
toybox pkill -9 -f startplasma-x11 2>/dev/null || true
toybox pkill -9 -f startplasma 2>/dev/null || true
sleep 1""".trimIndent()
                    // 只删 rootfs 内非挂载点的子目录，挂载点目录本身不动
                    val rmLine = "toybox rm -rf '$ref'/* 2>/dev/null; toybox rm -rf '$ref' 2>/dev/null || true"
                    val verify = "toybox test -e '$ref' && echo __REMAIN__ || echo __GONE__"
                    val script = "$killLines\n$rmLine\n$verify"
                    val result = RootShell.exec(script, timeoutMs = 120000)
                    val gone = result.stdout.contains("__GONE__") && !result.stdout.contains("__REMAIN__")
                    activity.runOnUiThread {
                        if (gone) {
                            Toast.makeText(activity, "Linux rootfs uninstalled", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(activity, "Uninstall failed：the directory still exists，close the terminal and try again", Toast.LENGTH_LONG).show()
                        }
                        refreshInfo()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun card(): LinearLayout = LinearLayout(activity).apply {
        val d = activity.resources.displayMetrics.density
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

    private fun spacer(height: Int): View {
        val d = activity.resources.displayMetrics.density
        return View(activity).also { it.layoutParams = Ui.layoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(height, d)) }
    }

    // 分区小标题：Runtime environment / System管理等网格区头部
    private fun sectionLabel(text: String, d: Float): TextView = TextView(activity).apply {
        this.text = text
        textSize = 12f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Ui.secondaryText(activity))
    }

    // 二合一卡片内的入口行：无独立背景，靠外层玻璃卡 + 水晶分隔条分格
    private fun actionRow(iconRes: Int, heading: String, detail: String, onClick: () -> Unit): View {
        val d = activity.resources.displayMetrics.density
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(12, d), Ui.dp(9, d), Ui.dp(10, d), Ui.dp(9, d))
            setOnClickListener { onClick() }
            Ui.pressAnimation(this)
            addView(ImageView(activity).apply {
                setImageResource(iconRes)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(Ui.dp(36, d), Ui.dp(36, d))
            })
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = Ui.dp(10, d)
                }
                addView(TextView(activity).apply {
                    text = heading
                    textSize = 14f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(Ui.primaryText(activity))
                })
                addView(TextView(activity).apply {
                    text = detail
                    textSize = 11f
                    setTextColor(Ui.secondaryText(activity))
                    setPadding(0, Ui.dp(1, d), 0, 0)
                })
            })
            addView(TextView(activity).apply {
                text = "›"
                textSize = 22f
                setTextColor(Ui.secondaryText(activity))
            })
        }
    }
}
