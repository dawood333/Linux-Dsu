package com.mcai.ubuntudsu

import android.os.Bundle
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.mcai.ubuntudsu.ui.Haptics
import com.mcai.ubuntudsu.ui.Ui
import com.mcai.ubuntudsu.ui.pages.RomPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class RomActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.IO)
    private var romPage: RomPage? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.transparent)

        val page = RomPage(this, { finish() }, scope)
        romPage = page
        val content = page.build()

        val root = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        root.background = Ui.liquidBackground(this)
        root.clipToOutline = true
        root.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                val r = (32 * view.resources.displayMetrics.density)
                outline.setRoundRect(0, 0, view.width, view.height, r)
            }
        }
        Ui.animateLiquidBackground(root)
        root.addView(content)
        setContentView(root)
        Ui.enableEdgeToEdge(this, root)

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            androidx.core.view.ViewCompat.requestApplyInsets(content)
            insets
        }
    }

    override fun onDestroy() {
        romPage?.cleanup()
        super.onDestroy()
    }

    private var finishing = false

    override fun finish() {
        if (finishing) return
        finishing = true
        super.finish()
        overridePendingTransition(R.anim.zoom_in, R.anim.zoom_out)
    }

    // 全局触摸震动反馈
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        ev?.let { Haptics.onTouch(window.decorView, it) }
        return super.dispatchTouchEvent(ev)
    }
}
