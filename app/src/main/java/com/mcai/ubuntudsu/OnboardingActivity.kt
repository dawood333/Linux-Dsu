package com.mcai.ubuntudsu

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mcai.ubuntudsu.ui.Ui

/**
 * DNA NEXT style onboarding flow — full-screen immersive setup wizard.
 *
 * Pages:
 *  0. Welcome     – pink-blue gradient background, centered combined logo, "TMUI OSv1.0", bottom circular arrow
 *  1. Agreement   – white card with user agreement, checkbox, continue button
 *  2. Permissions – notification / storage / root verification with switches
 *  3. Settings    – color source, dark/light mode, UI scale slider
 *  4. Done        – app icon, "TMUI OSv1.0", "Setup complete", "Get started" button
 */
class OnboardingActivity : AppCompatActivity() {

    companion object {
        private const val PREF_ONBOARDING = "onboarding"
        private const val KEY_COMPLETED = "completed"
        private const val KEY_AGREED = "agreed"
        private const val KEY_UI_SCALE = "ui_scale"
        private const val PAGE_COUNT = 7

        fun isCompleted(ctx: android.content.Context): Boolean =
            ctx.getSharedPreferences(PREF_ONBOARDING, MODE_PRIVATE).getBoolean(KEY_COMPLETED, false)
    }

    private val pages = mutableListOf<View>()
    private var currentPage = 0
    private lateinit var pageContainer: FrameLayout
    private lateinit var indicatorContainer: LinearLayout
    private lateinit var rootLayout: FrameLayout
    private val indicatorDots = mutableListOf<View>()
    /** [translated] rootLayout[translated] 0 [translated]Display[translated] */
    private lateinit var rainbowFlow: RainbowFlowView

    // Swipe tracking
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var isSwiping = false

    // Animated background
    private var bgAnimator: ValueAnimator? = null
    private var bgDrawable: GradientDrawable? = null

    // State
    private var agreementChecked = false
    private var notificationGranted = false
    private var storageGranted = false
    private var usageAccessGranted = false
    private var rootVerified = false
    private var uiScale = 100
    private val permissionSwitches = linkedMapOf<String, Switch>()
    private val pendingPermissionKeys = linkedSetOf<String>()
    private var syncingPermissionRows = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

        if (isCompleted(this)) {
            goToMain()
            return
        }

        buildUi()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != 1001 && requestCode != 1002 && requestCode != 1003) return
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val requestedKeys = linkedSetOf<String>()
        if (permissions.contains(android.Manifest.permission.POST_NOTIFICATIONS)) requestedKeys += "notification"
        if (
            permissions.contains(android.Manifest.permission.READ_EXTERNAL_STORAGE) ||
            permissions.contains(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        ) {
            requestedKeys += "storage"
        }
        refreshPermissionState()
        requestedKeys.forEach { key ->
            pendingPermissionKeys.remove(key)
        }
    }

    private fun markPendingPermission(key: String) {
        pendingPermissionKeys += key
    }

    private fun refreshPermissionState() {
        notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        storageGranted = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        usageAccessGranted = hasUsageAccess()
        rootVerified = runCatching { com.mcai.ubuntudsu.core.RootShell.available() }.getOrDefault(false)
        syncPermissionRows()
    }

    private fun hasUsageAccess(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return runCatching {
                val appOps = getSystemService(android.app.AppOpsManager::class.java)
                val flag = appOps.checkOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    packageName
                )
                flag == android.app.AppOpsManager.MODE_ALLOWED
            }.getOrDefault(false)
        }
        return false
    }

    private fun syncPermissionRows() {
        syncingPermissionRows = true
        try {
            permissionSwitches.entries.forEach { (key, sw) ->
                if (pendingPermissionKeys.contains(key)) {
                    return@forEach
                }
                sw.isChecked = isRowGranted(key)
            }
        } finally {
            syncingPermissionRows = false
        }
    }

    private fun applyPermissionSwitchState(key: String, checked: Boolean) {
        val sw = permissionSwitches[key] ?: return
        syncingPermissionRows = true
        try {
            sw.isChecked = checked
        } finally {
            syncingPermissionRows = false
        }
    }

    private fun isRowGranted(key: String): Boolean = when (key) {
        "notification" -> notificationGranted
        "storage" -> storageGranted
        "usage" -> usageAccessGranted
        "root" -> rootVerified
        else -> false
    }

    private fun trackPermissionRow(switch: Switch, key: String) {
        switch.tag = key
        permissionSwitches[key] = switch
    }

    private fun requestOneTapPermissions() {
        refreshPermissionState()
        val pending = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationGranted) {
            pending += android.Manifest.permission.POST_NOTIFICATIONS
            markPendingPermission("notification")
        }
        val storagePermission = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (!storageGranted) {
            pending += storagePermission
            markPendingPermission("storage")
        }
        if (pending.isNotEmpty()) {
            requestPermissions(pending.toTypedArray(), 1003)
        }
        if (!usageAccessGranted) {
            runCatching {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }.onFailure {
                Toast.makeText(this, "System settings page unavailable", Toast.LENGTH_SHORT).show()
            }
        }
        if (!rootVerified) {
            rootVerified = runCatching { com.mcai.ubuntudsu.core.RootShell.available() }.getOrDefault(false)
            applyPermissionSwitchState("root", rootVerified)
        }
        refreshPermissionState()
        Toast.makeText(this, "Automatic authorization has been requested. Return to the app to check the actual status.", Toast.LENGTH_SHORT).show()
    }

    // ==================== UI Construction ====================

    private fun buildUi() {
        val d = resources.displayMetrics.density
        rootLayout = FrameLayout(this)

        // 1. Animated gradient background (full screen)
        setupGradientBackground(rootLayout)

        // 1.5 [translated]Status[translated]/[translated]Display
        rainbowFlow = RainbowFlowView(this).apply {
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        rootLayout.addView(rainbowFlow)

        // 2. Page container[translated] systemBars padding[translated]content[translated]
        pageContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        rootLayout.addView(pageContainer)

        // 3. Bottom indicators
        indicatorContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            )
        }
        for (i in 0 until PAGE_COUNT) {
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(Ui.dp(6, d), Ui.dp(6, d)).apply {
                    marginStart = if (i == 0) 0 else Ui.dp(4, d)
                    marginEnd = if (i == PAGE_COUNT - 1) 0 else Ui.dp(4, d)
                }
                background = makeDotDrawable(false)
            }
            indicatorDots.add(dot)
            indicatorContainer.addView(dot)
        }
        rootLayout.addView(indicatorContainer)

        setContentView(rootLayout)
        Ui.enableEdgeToEdge(this, rootLayout)

        // Edge-to-edge insets[translated]padding [translated] pageContainer / [translated]
        // rootLayout [translated]Status[translated]
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            pageContainer.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            (indicatorContainer.layoutParams as FrameLayout.LayoutParams).bottomMargin =
                bars.bottom + Ui.dp(40, d)
            indicatorContainer.requestLayout()
            insets
        }
        ViewCompat.requestApplyInsets(rootLayout)

        showPage(0, animate = false)
    }

    private fun setupGradientBackground(root: FrameLayout) {
        // [translated] / [translated]
        Ui.animateLiquidBackground(root)
    }

    // ==================== Page Builders ====================

    private fun createPage(index: Int): View = when (index) {
        0 -> createWelcomePage()
        1 -> createAgreementPage()
        2 -> createFeaturePage(
            title = "DSU & Linux Desktop",
            subtitle = "No unlocking or changes to the stock system; temporarily boot new system images",
            items = listOf(
                Triple(R.drawable.icon_dsu_modern, "DSU Dynamic System Update", "Install GSI images directly with ROOT, customize userdata size, clear old cache, and reboot with one tap"),
                Triple(R.drawable.icon_linux_modern, "Linux ARM® Architecture", "Install Ubuntu rootfs in Chroot (local / cloud image), with uninstall support"),
                Triple(R.drawable.icon_terminal_runner, "Container Terminal", "Termux-style Chroot terminal with apt package installation"),
                Triple(R.drawable.ic_desktop_start, "Remote Desktop", "XFCE / KDE Desktop + VNC 远程Connection，音频桥接、分辨率自选"),
            ),
        )
        3 -> createFeaturePage(
            title = "ROM Firmware & Porting",
            subtitle = "Dual-source firmware downloads and all-in-one DNA porting tools",
            items = listOf(
                Triple(R.drawable.icon_rom_firmware, "ROM Firmware Download", "HyperOS and ColorOS / FlymeOS / realme UI sources, brand/device filtering, aria2c acceleration"),
                Triple(R.drawable.icon_rom_port, "ROM Porting", "DNA Toolbox for SUPER unpacking/repacking, payload extraction, and image format conversion"),
                Triple(R.drawable.icon_otg, "OTG Flashing Assistant", "Detect USB device ADB / Fastboot status with real-time flashing logs"),
                Triple(R.drawable.icon_usb_boot, "USB Boot", "Create a USB-drive IMG image locally and expose it to the computer"),
            ),
        )
        4 -> createPermissionsPage()
        5 -> createEnvCheckPage()
        6 -> createDonePage()
        else -> createWelcomePage()
    }

    /** [translated] + [translated] + [translated] + [translated]Next[translated] */
    private fun createFeaturePage(
        title: String,
        subtitle: String,
        items: List<Triple<Int, String, String>>,
    ): View {
        val d = resources.displayMetrics.density
        val container = FrameLayout(this)

        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ).apply {
                marginStart = Ui.dp(16, d)
                marginEnd = Ui.dp(16, d)
                topMargin = Ui.dp(60, d)
                bottomMargin = Ui.dp(16, d)
            }
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(20, d), Ui.dp(24, d), Ui.dp(20, d), Ui.dp(20, d))
            background = Ui.neuCard(this@OnboardingActivity, 20f)
            Ui.applyNeuShadow(this, 5f, 20f)
        }

        card.addView(TextView(this).apply {
            text = title
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(this@OnboardingActivity))
        })
        card.addView(TextView(this).apply {
            text = subtitle
            textSize = 13f
            setTextColor(Ui.secondaryText(this@OnboardingActivity))
            setPadding(0, Ui.dp(8, d), 0, Ui.dp(14, d))
            setLineSpacing(Ui.dp(4, d).toFloat(), 1f)
        })

        items.forEachIndexed { index, (iconRes, name, desc) ->
            if (index > 0) {
                card.addView(
                    Ui.crystalDivider(this, d),
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Ui.dp(2, d),
                    ).apply { topMargin = Ui.dp(2, d); bottomMargin = Ui.dp(2, d) },
                )
            }
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, Ui.dp(12, d), 0, Ui.dp(12, d))
                addView(ImageView(this@OnboardingActivity).apply {
                    setImageResource(iconRes)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = LinearLayout.LayoutParams(Ui.dp(40, d), Ui.dp(40, d))
                })
                addView(LinearLayout(this@OnboardingActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = Ui.dp(13, d)
                    }
                    addView(TextView(this@OnboardingActivity).apply {
                        text = name
                        textSize = 15f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(Ui.primaryText(this@OnboardingActivity))
                    })
                    addView(TextView(this@OnboardingActivity).apply {
                        text = desc
                        textSize = 12f
                        setTextColor(Ui.secondaryText(this@OnboardingActivity))
                        setPadding(0, Ui.dp(3, d), 0, 0)
                        setLineSpacing(Ui.dp(2, d).toFloat(), 1f)
                    })
                })
            })
        }

        scroll.addView(card)
        container.addView(scroll)
        container.addView(bottomNextButton())
        return container
    }

    /** [translated]Next[translated]Total [translated] + [translated] */
    private fun bottomNextButton(): View {
        val d = resources.displayMetrics.density
        return TextView(this).apply {
            text = "Next"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Ui.neuSolidButton(
                if (Ui.isDark(this@OnboardingActivity)) android.graphics.Color.parseColor("#62A8FF") else android.graphics.Color.parseColor("#5EA0FF"),
                if (Ui.isDark(this@OnboardingActivity)) android.graphics.Color.parseColor("#2E6CF0") else android.graphics.Color.parseColor("#2F6BF0"),
                12f, this@OnboardingActivity,
            )
            Ui.applyNeuShadow(this, 5f, 12f, Ui.buttonPrimary(this@OnboardingActivity))
            setPadding(0, Ui.dp(14, d), 0, Ui.dp(14, d))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ).apply {
                marginStart = Ui.dp(32, d)
                marginEnd = Ui.dp(32, d)
                bottomMargin = Ui.dp(56, d)
            }
            isClickable = true
            isFocusable = true
            Ui.pressAnimation(this)
            setOnClickListener { goToNextPage() }
        }
    }

    /**
     * Page 0: Welcome
     * Full-screen DNA NEXT style: animated gradient, colored logo, 
     * "BOX 🧰 TM®" rainbow text, circular arrow button with ripple + scale effect.
     */
    private fun createWelcomePage(): View {
        val d = resources.displayMetrics.density
        val container = FrameLayout(this)

        // [translated] rootLayout [translated] rainbowFlow [translated]Status[translated]/[translated]

        // Central content: logo + rainbow text — centered vertically, slightly above center
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL,
            ).apply {
                // Slightly above true center (~6% of screen height upward)
                topMargin = -Ui.dp(48, d)
            }
        }

        // Logo (same launcher icon, unchanged display size)
        val logoSize = Ui.dp(140, d)
        content.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            layoutParams = LinearLayout.LayoutParams(logoSize, logoSize).apply {
                bottomMargin = Ui.dp(4, d)
            }
        })

        // "Welcome" — custom TextView with left-to-right rainbow gradient flowing animation
        val welcomeText = object : TextView(this) {
            private var gradientShader: android.graphics.LinearGradient? = null
            fun setGradientShader(shader: android.graphics.LinearGradient) {
                gradientShader = shader
                invalidate()
            }
            override fun onDraw(canvas: android.graphics.Canvas) {
                gradientShader?.let { paint.shader = it }
                super.onDraw(canvas)
            }
        }.apply {
            text = "Welcome"
            textSize = 40f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            // [translated]radius=1.5f, dx=0, dy=1f[translated]
            setShadowLayer(1.5f, 0f, 1f, Color.argb(50, 0, 0, 0))
        }
        content.addView(welcomeText)

        // Start flowing rainbow gradient animation after layout
        welcomeText.post {
            val paint = welcomeText.paint
            val textWidth = paint.measureText("Welcome")
            // [translated]Material 400 [translated]
            val colors = intArrayOf(
                Color.parseColor("#FF8A80"),
                Color.parseColor("#FFB74D"),
                Color.parseColor("#FFD54F"),
                Color.parseColor("#81C784"),
                Color.parseColor("#4FC3F7"),
                Color.parseColor("#9575CD"),
                Color.parseColor("#F06292"),
                Color.parseColor("#FF8A80"),
            )
            val animator = ValueAnimator.ofFloat(0f, textWidth * 2).apply {
                duration = 4000L
                repeatMode = ValueAnimator.RESTART
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { anim ->
                    val offset = anim.animatedValue as Float
                    welcomeText.setGradientShader(android.graphics.LinearGradient(
                        -textWidth + offset, 0f,
                        textWidth + offset, 0f,
                        colors,
                        null,
                        android.graphics.Shader.TileMode.CLAMP
                    ))
                }
            }
            animator.start()
        }

        container.addView(content)

        // Bottom circular arrow button — position matching video (~100dp from bottom)
        val arrowBtnSize = Ui.dp(56, d)
        val arrowBtn = FrameLayout(this).apply {
            // [translated] + [translated]/[translated] + [translated]
            background = RippleDrawable(
                ColorStateList.valueOf(Color.argb(60, 90, 160, 255)),
                Ui.neuCard(this@OnboardingActivity, 28f, Ui.buttonPrimary(this@OnboardingActivity)),
                null
            )
            // [translated]
            Ui.applyNeuShadow(this, 7f, 28f, Ui.buttonPrimary(this@OnboardingActivity))
            layoutParams = FrameLayout.LayoutParams(arrowBtnSize, arrowBtnSize, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = Ui.dp(100, d)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                // Scale down then up animation
                animate()
                    .scaleX(0.85f)
                    .scaleY(0.85f)
                    .setDuration(100)
                    .withEndAction {
                        animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(150)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                        goToNextPage()
                    }
                    .start()
            }
        }

        // Arrow icon
        arrowBtn.addView(TextView(this).apply {
            text = "→"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        })

        container.addView(arrowBtn)
        return container
    }

    // Rainbow text: each character gets a different pastel color
    private fun applyRainbowText(tv: TextView) {
        val text = tv.text.toString()
        val spannable = SpannableString(text)
        val rainbowColors = intArrayOf(
            Color.rgb(255, 255, 255), // white
            Color.rgb(255, 182, 193), // pink
            Color.rgb(176, 196, 222), // steel blue
            Color.rgb(230, 190, 220), // light purple
            Color.rgb(255, 218, 185), // peach
            Color.rgb(174, 214, 241), // light blue
            Color.rgb(221, 160, 221), // plum
            Color.rgb(200, 230, 201), // mint
        )
        var colorIdx = 0
        for (i in text.indices) {
            val c = text[i]
            if (!c.isWhitespace() && c != '\uFE0F' && c != '\u200D') {
                spannable.setSpan(
                    ForegroundColorSpan(rainbowColors[colorIdx % rainbowColors.size]),
                    i, i + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                colorIdx++
            }
        }
        tv.text = spannable
    }

    // Wave animation: smooth color gradient cycle using ArgbEvaluator
    // Red -> Blue -> Green -> Red, 4s cycle
    private var waveAnimator: ValueAnimator? = null
    private fun startWaveAnimation(tv: TextView, colors: IntArray) {
        val evaluator = android.animation.ArgbEvaluator()
        waveAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4000L
            repeatMode = ValueAnimator.RESTART
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                val fraction = anim.animatedValue as Float
                // Cycle: Red(0.0) -> Blue(0.33) -> Green(0.66) -> Red(1.0)
                val color = when {
                    fraction < 0.33f -> evaluator.evaluate(fraction / 0.33f, Color.RED, Color.BLUE) as Int
                    fraction < 0.66f -> evaluator.evaluate((fraction - 0.33f) / 0.33f, Color.BLUE, Color.GREEN) as Int
                    else -> evaluator.evaluate((fraction - 0.66f) / 0.34f, Color.GREEN, Color.RED) as Int
                }
                tv.setTextColor(color)
            }
        }
        waveAnimator?.start()
    }

    /**
     * [translated] → [translated] → [translated] → [translated]
     * [translated]
     * [translated]
     */
    private class RainbowFlowView(context: android.content.Context) : View(context) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private var w = 0
        private var h = 0

        // [translated] → [translated] → [translated] → [translated]
        private val colors = intArrayOf(
            Color.parseColor("#34383F"), // 浅黑
            Color.parseColor("#2F7A5B"), // 绿
            Color.parseColor("#2A5A8E"), // 蓝
            Color.parseColor("#D9DFE7"), // 白
        )
        private val stops = floatArrayOf(0f, 0.42f, 0.72f, 1f)

        override fun onSizeChanged(width: Int, height: Int, oldw: Int, oldh: Int) {
            w = width
            h = height
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            paint.shader = android.graphics.LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                colors, stops,
                android.graphics.Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            paint.shader = null
        }
    }

    private fun createAgreementPage(): View {
        val d = resources.displayMetrics.density
        val container = FrameLayout(this)

        // Scrollable white card
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ).apply {
                marginStart = Ui.dp(16, d)
                marginEnd = Ui.dp(16, d)
                topMargin = Ui.dp(60, d)
                bottomMargin = Ui.dp(16, d)
            }
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(20, d), Ui.dp(24, d), Ui.dp(20, d), Ui.dp(24, d))
            // [translated] + [translated]/[translated]
            background = Ui.neuCard(this@OnboardingActivity, 20f)
            Ui.applyNeuShadow(this, 5f, 20f)
        }

        // Title
        card.addView(TextView(this).apply {
            text = "User Agreement"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(this@OnboardingActivity))
        })

        // Agreement text
        card.addView(TextView(this).apply {
            text = "Welcome to TMUI OS. This app provides Android devices with Linux desktop capabilities, including DSU GSI installation, Chroot Linux containers, terminal emulation, and VNC remote desktop.\n\nThis app requires ROOT access and may perform system-level operations. Please read the following terms carefully before continuing."
            textSize = 14f
            setTextColor(Ui.secondaryText(this@OnboardingActivity))
            setPadding(0, Ui.dp(16, d), 0, 0)
            setLineSpacing(Ui.dp(4, d).toFloat(), 1f)
        })

        // Agreement item row
        val agreementRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(20, d), 0, Ui.dp(20, d))
        }

        val checkBox = CheckBox(this).apply {
            isChecked = agreementChecked
            setOnCheckedChangeListener { _, isChecked ->
                agreementChecked = isChecked
            }
        }
        agreementRow.addView(checkBox)

        agreementRow.addView(TextView(this).apply {
            text = "I have read and agree to the User Agreement and Privacy Policy"
            textSize = 14f
            setTextColor(Ui.primaryText(this@OnboardingActivity))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Ui.dp(8, d)
            }
        })

        card.addView(agreementRow)

        scroll.addView(card)
        container.addView(scroll)

        // Bottom "Next" button — placed outside the card, below the indicators
        val nextBtn = TextView(this).apply {
            text = "Next"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            // [translated]accent [translated] + [translated] + [translated]
            background = Ui.neuSolidButton(
                if (Ui.isDark(this@OnboardingActivity)) android.graphics.Color.parseColor("#62A8FF") else android.graphics.Color.parseColor("#5EA0FF"),
                if (Ui.isDark(this@OnboardingActivity)) android.graphics.Color.parseColor("#2E6CF0") else android.graphics.Color.parseColor("#2F6BF0"),
                12f, this@OnboardingActivity
            )
            Ui.applyNeuShadow(this, 5f, 12f, Ui.buttonPrimary(this@OnboardingActivity))
            setPadding(0, Ui.dp(14, d), 0, Ui.dp(14, d))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ).apply {
                marginStart = Ui.dp(32, d)
                marginEnd = Ui.dp(32, d)
                bottomMargin = Ui.dp(56, d)
            }
            isClickable = true
            isFocusable = true
            Ui.pressAnimation(this)
            setOnClickListener {
                if (agreementChecked) {
                    goToNextPage()
                } else {
                    Toast.makeText(this@OnboardingActivity, "Please accept the User Agreement first", Toast.LENGTH_SHORT).show()
                }
            }
        }
        container.addView(nextBtn)
        return container
    }

    /**
     * Page 2: Permissions
     * Notification / Storage / Root verification
     */
    private fun createPermissionsPage(): View {
        val d = resources.displayMetrics.density
        val container = FrameLayout(this)

        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ).apply {
                marginStart = Ui.dp(16, d)
                marginEnd = Ui.dp(16, d)
                topMargin = Ui.dp(60, d)
                bottomMargin = Ui.dp(16, d)
            }
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(20, d), Ui.dp(24, d), Ui.dp(20, d), Ui.dp(24, d))
            // [translated] + [translated]/[translated]
            background = Ui.neuCard(this@OnboardingActivity, 20f)
            Ui.applyNeuShadow(this, 5f, 20f)
        }

        // Title
        card.addView(TextView(this).apply {
            text = "Environment & Permissions"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(this@OnboardingActivity))
        })

        // Description
        card.addView(TextView(this).apply {
            text = "Choose the conditions to check now. Other settings can be changed later."
            textSize = 14f
            setTextColor(Ui.secondaryText(this@OnboardingActivity))
            setPadding(0, Ui.dp(8, d), 0, Ui.dp(16, d))
            setLineSpacing(Ui.dp(4, d).toFloat(), 1f)
        })

        // Notification permission
        card.addView(makePermissionRow("Notification Permission", "Allow the app to send notifications", "notification", notificationGranted) { checked ->
            if (checked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                markPendingPermission("notification")
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        })

        card.addView(makePermissionRow("Storage Access", "Allow access to device storage", "storage", storageGranted) { checked ->
            if (checked) {
                markPendingPermission("storage")
                requestPermissions(
                    arrayOf(
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    ),
                    1002
                )
            }
        })

        card.addView(makePermissionRow("Usage Access", "Allows process management to read background apps and foreground activity", "usage", usageAccessGranted) { checked ->
            if (checked) {
                runCatching {
                    startActivity(android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }.onFailure {
                    Toast.makeText(this@OnboardingActivity, "System settings page unavailable", Toast.LENGTH_SHORT).show()
                }
            }
        })

        // Root notice (not a real permission request, just status display)
        card.addView(TextView(this).apply {
            text = "Root is not requested automatically. Tap the item below to verify the granted UID."
            textSize = 12f
            setTextColor(Ui.secondaryText(this@OnboardingActivity))
            setPadding(0, Ui.dp(8, d), 0, Ui.dp(4, d))
        })

        card.addView(makePermissionRow("Verify Root", "Check ROOT availability", "root", rootVerified) { checked ->
            if (checked) {
                rootVerified = runCatching { com.mcai.ubuntudsu.core.RootShell.available() }.getOrDefault(false)
                applyPermissionSwitchState("root", rootVerified)
                if (!rootVerified) {
                    Toast.makeText(this@OnboardingActivity, "Root verification failed", Toast.LENGTH_SHORT).show()
                }
            }
        })

        val oneTapBtn = TextView(this).apply {
            text = "Grant Access"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Ui.neuSolidButton(
                if (Ui.isDark(this@OnboardingActivity)) android.graphics.Color.parseColor("#22C55E") else android.graphics.Color.parseColor("#4ADE80"),
                if (Ui.isDark(this@OnboardingActivity)) android.graphics.Color.parseColor("#15803D") else android.graphics.Color.parseColor("#16A34A"),
                12f, this@OnboardingActivity
            )
            Ui.applyNeuShadow(this, 5f, 12f, Ui.buttonSuccess(this@OnboardingActivity))
            setPadding(0, Ui.dp(12, d), 0, Ui.dp(12, d))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = Ui.dp(12, d)
            }
            isClickable = true
            isFocusable = true
            Ui.pressAnimation(this)
            setOnClickListener {
                requestOneTapPermissions()
            }
        }
        card.addView(oneTapBtn)

        scroll.addView(card)
        container.addView(scroll)
        val nextBtn = TextView(this).apply {
            text = "Next"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            // [translated]accent [translated] + [translated] + [translated]
            background = Ui.neuSolidButton(
                if (Ui.isDark(this@OnboardingActivity)) android.graphics.Color.parseColor("#62A8FF") else android.graphics.Color.parseColor("#5EA0FF"),
                if (Ui.isDark(this@OnboardingActivity)) android.graphics.Color.parseColor("#2E6CF0") else android.graphics.Color.parseColor("#2F6BF0"),
                12f, this@OnboardingActivity
            )
            Ui.applyNeuShadow(this, 5f, 12f, Ui.buttonPrimary(this@OnboardingActivity))
            setPadding(0, Ui.dp(14, d), 0, Ui.dp(14, d))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ).apply {
                marginStart = Ui.dp(32, d)
                marginEnd = Ui.dp(32, d)
                bottomMargin = Ui.dp(56, d)
            }
            isClickable = true
            isFocusable = true
            Ui.pressAnimation(this)
            setOnClickListener { goToNextPage() }
        }
        container.addView(nextBtn)
        return container
    }

    private fun makePermissionRow(title: String, desc: String, key: String, initial: Boolean, onToggle: (Boolean) -> Unit): View {
        val d = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(12, d), Ui.dp(12, d), Ui.dp(12, d), Ui.dp(12, d))
            // [translated]Permission[translated]
            background = Ui.neuInset(this@OnboardingActivity, 12f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = Ui.dp(8, d)
            }
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(this@OnboardingActivity))
        })
        textCol.addView(TextView(this).apply {
            text = desc
            textSize = 12f
            setTextColor(Ui.secondaryText(this@OnboardingActivity))
            setPadding(0, Ui.dp(2, d), 0, 0)
        })
        row.addView(textCol)

        val switch = Switch(this).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, isChecked ->
                if (!syncingPermissionRows) onToggle(isChecked)
            }
        }
        trackPermissionRow(switch, key)
        row.addView(switch)

        return row
    }

    /**
     * Page 4: Environment Check — Install[translated] ROOT Authorized / CPU Architecture / Storage
     * [translated]Auto[translated]Detect[translated]Execute[translated]Status[translated]
     */
    private fun createEnvCheckPage(): View {
        val d = resources.displayMetrics.density
        val container = FrameLayout(this)

        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ).apply {
                marginStart = Ui.dp(16, d)
                marginEnd = Ui.dp(16, d)
                topMargin = Ui.dp(60, d)
                bottomMargin = Ui.dp(80, d)
            }
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(20, d), Ui.dp(24, d), Ui.dp(20, d), Ui.dp(16, d))
            // [translated] + [translated]/[translated]
            background = Ui.neuCard(this@OnboardingActivity, 20f)
        }

        // Title
        card.addView(TextView(this).apply {
            text = "Environment Check"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(this@OnboardingActivity))
        })

        // Description
        card.addView(TextView(this).apply {
            text = "Before starting, we will check ROOT access, CPU architecture, and storage space. Passing all checks provides the best installation experience."
            textSize = 13f
            setTextColor(Ui.secondaryText(this@OnboardingActivity))
            setPadding(0, Ui.dp(8, d), 0, Ui.dp(10, d))
            setLineSpacing(Ui.dp(4, d).toFloat(), 1f)
        })

        // [translated]Status[translated] + [translated] + [translated]Status[translated]
        fun checkRow(title: String): Pair<View, TextView> {
            val dot = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(70, 130, 140, 165))
                }
                layoutParams = LinearLayout.LayoutParams(Ui.dp(12, d), Ui.dp(12, d)).apply {
                    topMargin = Ui.dp(5, d)
                }
            }
            val status = TextView(this).apply {
                text = "Checking…"
                textSize = 12f
                setTextColor(Ui.secondaryText(this@OnboardingActivity))
                setPadding(0, Ui.dp(2, d), 0, 0)
            }
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, Ui.dp(12, d), 0, Ui.dp(12, d))
                addView(dot)
                addView(LinearLayout(this@OnboardingActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = Ui.dp(12, d)
                    }
                    addView(TextView(this@OnboardingActivity).apply {
                        text = title
                        textSize = 15f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(Ui.primaryText(this@OnboardingActivity))
                    })
                    addView(status)
                })
            })
            return dot to status
        }

        val rootRow = checkRow("ROOT Access")
        val archRow = checkRow("CPU Architecture")
        val storageRow = checkRow("Storage")

        // Status[translated]=[translated]=[translated]Available[translated]=[translated]
        fun mark(row: Pair<View, TextView>, level: Int, msg: String) {
            runOnUiThread {
                row.first.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(
                        when (level) {
                            0 -> Ui.buttonSuccess(this@OnboardingActivity)
                            1 -> Ui.buttonWarning(this@OnboardingActivity)
                            else -> Ui.buttonDanger(this@OnboardingActivity)
                        },
                    )
                }
                row.second.text = msg
            }
        }

        // [translated]
        Thread {
            Thread.sleep(400)
            val rootOk = com.mcai.ubuntudsu.core.StatusDetector.rootAvailable()
            mark(
                rootRow,
                if (rootOk) 0 else 1,
                if (rootOk) "ROOT access granted; all features are available" else "ROOT access not granted; core features are limited",
            )
            Thread.sleep(400)
            val archOk = Build.SUPPORTED_ABIS.contains("arm64-v8a")
            mark(
                archRow,
                if (archOk) 0 else 2,
                if (archOk) "arm64-v8a · Compatible with common rootfs images" else "arm64 not detected; compatibility is limited",
            )
            Thread.sleep(400)
            val freeBytes = runCatching {
                android.os.StatFs(android.os.Environment.getDataDirectory().path).availableBytes
            }.getOrDefault(0L)
            val freeGb = freeBytes / 1024f / 1024f / 1024f
            mark(
                storageRow,
                when {
                    freeGb >= 5f -> 0
                    freeGb >= 2f -> 1
                    else -> 2
                },
                "%.1f GB free · Recommended ≥ 5 GB".format(freeGb),
            )
        }.start()

        scroll.addView(card)
        container.addView(scroll)

        // Bottom "Next" button
        val nextBtn = TextView(this).apply {
            text = "Next"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            // [translated]accent [translated] + [translated] + [translated]
            background = Ui.neuSolidButton(
                if (Ui.isDark(this@OnboardingActivity)) android.graphics.Color.parseColor("#62A8FF") else android.graphics.Color.parseColor("#5EA0FF"),
                if (Ui.isDark(this@OnboardingActivity)) android.graphics.Color.parseColor("#2E6CF0") else android.graphics.Color.parseColor("#2F6BF0"),
                12f, this@OnboardingActivity
            )
            setPadding(0, Ui.dp(14, d), 0, Ui.dp(14, d))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ).apply {
                marginStart = Ui.dp(32, d)
                marginEnd = Ui.dp(32, d)
                bottomMargin = Ui.dp(56, d)
            }
            isClickable = true
            isFocusable = true
            Ui.pressAnimation(this)
            setOnClickListener { goToNextPage() }
        }
        container.addView(nextBtn)
        return container
    }

    /**
     * Page 4: Done — All Set + [translated]
     * [translated]All Set[translated]+ [translated] 4 [translated] + [translated] + [translated]+ Get started[translated]
     */
    private fun createDonePage(): View {
        val d = resources.displayMetrics.density
        val container = FrameLayout(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL or Gravity.TOP,
            ).apply {
                topMargin = Ui.dp(84, d)
                marginStart = Ui.dp(28, d)
                marginEnd = Ui.dp(28, d)
            }
        }

        // [translated] + [translated]
        content.addView(TextView(this).apply {
            text = "All Set"
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(this@OnboardingActivity))
            gravity = Gravity.CENTER
        })
        content.addView(TextView(this).apply {
            text = "Your pocket Linux toolbox is ready, with four core capabilities at your fingertips"
            textSize = 13f
            setTextColor(Ui.secondaryText(this@OnboardingActivity))
            gravity = Gravity.CENTER
            setPadding(Ui.dp(12, d), Ui.dp(6, d), Ui.dp(12, d), Ui.dp(18, d))
        })

        // [translated]4 [translated]
        val highlightCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(16, d), Ui.dp(14, d), Ui.dp(16, d), Ui.dp(14, d))
            background = Ui.neuCard(this@OnboardingActivity, 20f)
            Ui.applyNeuShadow(this, 5f, 20f)
        }
        listOf(
            Triple(R.drawable.icon_terminal_runner, "Container Terminal", "Chroot container · Termux-style · apt package management"),
            Triple(R.drawable.icon_linux_modern, "Desktop Environment", "XFCE / KDE / GNOME + VNC Remote Desktop"),
            Triple(R.drawable.icon_dsu_modern, "DSU Manager", "Install GSI images directly with ROOT · Reboot to switch"),
            Triple(R.drawable.ic_download, "Download Manager", "Parallel downloads · Resume support · Direct image downloads"),
        ).forEachIndexed { index, (iconRes, title, desc) ->
            if (index > 0) {
                // [translated]Partition[translated]
                highlightCard.addView(
                    Ui.crystalDivider(this, d),
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Ui.dp(2, d),
                    ).apply { topMargin = Ui.dp(2, d); bottomMargin = Ui.dp(2, d) },
                )
            }
            highlightCard.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, Ui.dp(10, d), 0, Ui.dp(10, d))
                addView(ImageView(this@OnboardingActivity).apply {
                    setImageResource(iconRes)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = LinearLayout.LayoutParams(Ui.dp(34, d), Ui.dp(34, d))
                })
                addView(LinearLayout(this@OnboardingActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = Ui.dp(12, d)
                    }
                    addView(TextView(this@OnboardingActivity).apply {
                        text = title
                        textSize = 14f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(Ui.primaryText(this@OnboardingActivity))
                    })
                    addView(TextView(this@OnboardingActivity).apply {
                        text = desc
                        textSize = 11f
                        setTextColor(Ui.secondaryText(this@OnboardingActivity))
                        setPadding(0, Ui.dp(1, d), 0, 0)
                    })
                })
            })
        }
        content.addView(highlightCard)

        // Open-source repository[translated]Copy[translated]
        content.addView(TextView(this).apply {
            text = "Open-source repository  github.com/hetianming/Linux-Dsu"
            textSize = 12f
            setTextColor(Ui.buttonPrimary(this@OnboardingActivity))
            gravity = Gravity.CENTER
            setPadding(Ui.dp(12, d), Ui.dp(14, d), Ui.dp(12, d), 0)
            isClickable = true
            Ui.pressAnimation(this)
            setOnClickListener {
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("repo", "https://github.com/hetianming/Linux-Dsu"))
                Toast.makeText(this@OnboardingActivity, "Repository address copied", Toast.LENGTH_SHORT).show()
            }
        })
        container.addView(content)

        // Bottom "Get started" button — placed directly below the indicators
        val startBtn = TextView(this).apply {
            text = "Get started"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            // [translated]accent [translated] + [translated] + [translated]
            background = Ui.neuSolidButton(
                if (Ui.isDark(this@OnboardingActivity)) android.graphics.Color.parseColor("#62A8FF") else android.graphics.Color.parseColor("#5EA0FF"),
                if (Ui.isDark(this@OnboardingActivity)) android.graphics.Color.parseColor("#2E6CF0") else android.graphics.Color.parseColor("#2F6BF0"),
                12f, this@OnboardingActivity
            )
            Ui.applyNeuShadow(this, 5f, 12f, Ui.buttonPrimary(this@OnboardingActivity))
            setPadding(0, Ui.dp(14, d), 0, Ui.dp(14, d))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ).apply {
                marginStart = Ui.dp(32, d)
                marginEnd = Ui.dp(32, d)
                bottomMargin = Ui.dp(56, d)
            }
            isClickable = true
            isFocusable = true
            Ui.pressAnimation(this)
            setOnClickListener {
                saveSettings()
                markCompleted()
                goToMain()
            }
        }
        container.addView(startBtn)
        return container
    }

    // ==================== Page Navigation ====================

    private fun showPage(index: Int, animate: Boolean) {
        if (index !in 0 until PAGE_COUNT) return
        currentPage = index

        // [translated]Display[translated] RainbowFlowView [translated]Auto[translated]
        if (::rainbowFlow.isInitialized) {
            rainbowFlow.visibility = if (index == 0) View.VISIBLE else View.GONE
        }

        if (index >= pages.size) {
            while (pages.size <= index) {
                pages.add(createPage(pages.size))
            }
        }
        val page = pages[index]

        pageContainer.removeAllViews()
        page.alpha = if (animate) 0f else 1f
        page.translationX = if (animate) resources.displayMetrics.density * 40f else 0f
        pageContainer.addView(page, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        if (animate) {
            page.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(350)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        updateIndicators(index)
    }

    private fun updateIndicators(activeIndex: Int) {
        val d = resources.displayMetrics.density
        for (i in indicatorDots.indices) {
            val dot = indicatorDots[i]
            val isActive = i == activeIndex
            val size = if (isActive) Ui.dp(8, d) else Ui.dp(6, d)
            val lp = dot.layoutParams as LinearLayout.LayoutParams
            lp.width = size
            lp.height = size
            dot.layoutParams = lp
            dot.background = makeDotDrawable(isActive)
            dot.animate()
                .scaleX(if (isActive) 1.2f else 1f)
                .scaleY(if (isActive) 1.2f else 1f)
                .setDuration(200)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun makeDotDrawable(active: Boolean): GradientDrawable {
        // [translated] = accent [translated] + [translated] = [translated]
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (active) {
                setColor(Ui.buttonPrimary(this@OnboardingActivity))
                setStroke(Ui.dp(1, resources.displayMetrics.density), Color.argb(150, 255, 255, 255))
            } else {
                setColor(Color.argb(110, 255, 255, 255))
            }
        }
    }

    private fun goToNextPage() {
        // [translated]Next[translated]
        if (currentPage == 1 && !agreementChecked) {
            Toast.makeText(this, "Please accept the User Agreement first", Toast.LENGTH_SHORT).show()
            return
        }
        if (currentPage < PAGE_COUNT - 1) {
            val current = pageContainer.getChildAt(0)
            current?.animate()
                ?.alpha(0f)
                ?.translationX(-resources.displayMetrics.density * 30f)
                ?.setDuration(250)
                ?.setInterpolator(DecelerateInterpolator())
                ?.start()
            showPage(currentPage + 1, animate = true)
        }
    }

    private fun goToPreviousPage() {
        if (currentPage > 0) {
            val current = pageContainer.getChildAt(0)
            current?.animate()
                ?.alpha(0f)
                ?.translationX(resources.displayMetrics.density * 30f)
                ?.setDuration(250)
                ?.setInterpolator(DecelerateInterpolator())
                ?.start()
            showPage(currentPage - 1, animate = true)
        }
    }

    // ==================== Swipe Gesture ====================

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeStartX = event.x
                swipeStartY = event.y
                isSwiping = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isSwiping) {
                    val dx = event.x - swipeStartX
                    val dy = event.y - swipeStartY
                    val threshold = 48f * resources.displayMetrics.density
                    if (kotlin.math.abs(dx) > threshold && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 2) {
                        isSwiping = true
                        if (dx < 0) goToNextPage() else goToPreviousPage()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isSwiping = false
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            Ui.dispatchHaptic(window.decorView, event)
        }
        return super.dispatchTouchEvent(event)
    }

    // ==================== Persistence ====================

    private fun saveSettings() {
        getSharedPreferences(PREF_ONBOARDING, MODE_PRIVATE).edit().apply {
            putBoolean(KEY_AGREED, agreementChecked)
            putInt(KEY_UI_SCALE, uiScale)
            apply()
        }
    }

    private fun markCompleted() {
        getSharedPreferences(PREF_ONBOARDING, MODE_PRIVATE).edit().apply {
            putBoolean(KEY_COMPLETED, true)
            apply()
        }
    }

    // ==================== Navigation to Main ====================

    private fun goToMain() {
        bgAnimator?.cancel()
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    // ==================== Lifecycle ====================

    override fun onDestroy() {
        bgAnimator?.cancel()
        bgAnimator = null
        super.onDestroy()
    }
}
