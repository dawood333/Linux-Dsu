package com.mcai.ubuntudsu

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.mcai.ubuntudsu.ui.Haptics
import com.mcai.ubuntudsu.ui.Ui

/**
 * 全屏Settings页（参考 Dsu-Manager 的 SettingsActivity 布局）：
 * Back条 + 「Appearance」分区 + 「About」分区，整体沿用主应用拟态玻璃架构。
 *
 * 主题偏好与主界面共用 MainActivity 的私有 SharedPreferences 文件
 * （MainActivity 用 getPreferences() 读写，文件名即类名 "MainActivity"），
 * 保证Settings页改主题后主界面立即生效。
 */
class SettingsActivity : AppCompatActivity() {

    private var finishing = false

    companion object {
        const val THEME_PREFS = "MainActivity"
        const val KEY_THEME_MODE = "theme_mode"
    }

    private val d by lazy { resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.transparent)

        val root = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        root.background = Ui.liquidBackground(this)
        Ui.animateLiquidBackground(root)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(buildContent())
        }
        Ui.applyContentInsets(scroll)
        root.addView(
            scroll,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        setContentView(root)
        Ui.enableEdgeToEdge(this, root)
    }

    private fun buildContent(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(16, d), Ui.dp(12, d), Ui.dp(16, d), Ui.dp(24, d))
        }

        // ===== Back条：‹ Back / Settings（标题真正居中） =====
        val titleRow = FrameLayout(this).apply {
            setPadding(0, 0, 0, Ui.dp(16, d))
        }
        titleRow.addView(TextView(this).apply {
            text = "‹ Back"
            textSize = 13f
            setTextColor(Ui.buttonText(this@SettingsActivity))
            background = Ui.glassButton(this@SettingsActivity, Ui.buttonPrimary(this@SettingsActivity))
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
            text = "Settings"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(this@SettingsActivity))
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        })
        page.addView(titleRow)

        // ===== 分区一：About（置顶，使用主色绿替代蓝字） =====
        val aboutInfoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.neuCard(this@SettingsActivity, 18f)
            setPadding(Ui.dp(16, d), Ui.dp(14, d), Ui.dp(16, d), Ui.dp(14, d))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = Ui.dp(12, d) }
        }
        aboutInfoCard.addView(TextView(this).apply {
            text = "About"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(this@SettingsActivity))
            setPadding(0, 0, 0, Ui.dp(6, d))
        })
        aboutInfoCard.addView(TextView(this).apply {
            text = "Linux - Dsu"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(this@SettingsActivity))
            setPadding(0, 0, 0, Ui.dp(2, d))
        })
        aboutInfoCard.addView(TextView(this).apply {
            val ver = runCatching {
                packageManager.getPackageInfo(packageName, 0).versionName
            }.getOrNull() ?: "--"
            text = "Version v$ver  ·  TMUI build  ·  Copyright © 2026"
            textSize = 11f
            setTextColor(Ui.secondaryText(this@SettingsActivity))
            setPadding(0, 0, 0, Ui.dp(10, d))
        })
        aboutInfoCard.addView(TextView(this).apply {
            text = "All-in-one Ubuntu Chroot / DSU / ROM porting toolkit. Open About from the top-right of the Home page for the full feature overview."
            textSize = 12f
            setTextColor(Ui.secondaryText(this@SettingsActivity))
            setLineSpacing(Ui.dp(3, d).toFloat(), 1f)
        })
        page.addView(aboutInfoCard)

        // ===== 分区二：Appearance =====
        val themeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.neuCard(this@SettingsActivity, 18f)
            setPadding(Ui.dp(16, d), Ui.dp(14, d), Ui.dp(16, d), Ui.dp(14, d))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = Ui.dp(12, d) }
        }
        themeCard.addView(TextView(this).apply {
            text = "Theme"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(this@SettingsActivity))
            setPadding(0, 0, 0, Ui.dp(10, d))
        })
        themeCard.addView(buildThemeModeRow())
        page.addView(themeCard)

        // ===== 分区三：Credits =====
        val aboutCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.neuCard(this@SettingsActivity, 18f)
            setPadding(Ui.dp(16, d), Ui.dp(14, d), Ui.dp(16, d), Ui.dp(14, d))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = Ui.dp(12, d) }
            isClickable = true
            isFocusable = true
            Ui.pressAnimation(this)
            setOnClickListener {
                Haptics.perform(this)
                startActivity(Intent(this@SettingsActivity, ThanksActivity::class.java))
            }
        }
        aboutCard.addView(TextView(this).apply {
            text = "View Credits"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(this@SettingsActivity))
            setPadding(0, 0, 0, Ui.dp(6, d))
        })
        aboutCard.addView(TextView(this).apply {
            text = "Thanks to the open-source projects, toolchains, and community contributors. View the full credits."
            textSize = 12f
            setTextColor(Ui.secondaryText(this@SettingsActivity))
            setLineSpacing(Ui.dp(3, d).toFloat(), 1f)
        })
        page.addView(aboutCard)

        return page
    }

    /** Theme三选一：Follow System / Light / Dark（选中项高亮，切换后立即重建生效） */
    private fun buildThemeModeRow(): View {
        val prefs = getSharedPreferences(THEME_PREFS, Activity.MODE_PRIVATE)
        val current = prefs.getInt(KEY_THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        val checked = when (current) {
            AppCompatDelegate.MODE_NIGHT_NO -> 1
            AppCompatDelegate.MODE_NIGHT_YES -> 2
            else -> 0
        }
        val modes = arrayOf("Follow System", "Light", "Dark")
        val group = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        modes.forEachIndexed { index, label ->
            group.addView(TextView(this).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(if (index == checked) Ui.buttonText(this@SettingsActivity) else Ui.secondaryText(this@SettingsActivity))
                background = if (index == checked) {
                    Ui.glassButton(this@SettingsActivity, Ui.buttonPrimary(this@SettingsActivity))
                } else {
                    Ui.rounded(Color.TRANSPARENT, 10f, d)
                }
                setPadding(Ui.dp(12, d), Ui.dp(9, d), Ui.dp(12, d), Ui.dp(9, d))
                Ui.pressAnimation(this)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) marginStart = Ui.dp(6, d)
                }
                setOnClickListener {
                    val mode = when (index) {
                        1 -> AppCompatDelegate.MODE_NIGHT_NO
                        2 -> AppCompatDelegate.MODE_NIGHT_YES
                        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    }
                    prefs.edit().putInt(KEY_THEME_MODE, mode).apply()
                    AppCompatDelegate.setDefaultNightMode(mode)
                    recreate()
                }
            })
        }
        return group
    }

    override fun finish() {
        if (finishing) return
        finishing = true
        super.finish()
        overridePendingTransition(R.anim.zoom_in, R.anim.zoom_out)
    }
}
