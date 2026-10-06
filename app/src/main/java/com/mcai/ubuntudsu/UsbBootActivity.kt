package com.mcai.ubuntudsu

import android.os.Bundle
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.mcai.ubuntudsu.ui.Haptics
import com.mcai.ubuntudsu.ui.Ui
import com.mcai.ubuntudsu.ui.pages.UsbBootPage

/**
 * U盘启动 - 薄脚手架 Activity
 *
 * 界面与交互全部交由 [UsbBootPage]（主应用拟态 + 液态玻璃架构），
 * 本类只负责生命周期接线：
 *  - onCreate：挂液态玻璃背景 + edge-to-edge，构建页面
 *  - onDestroy：释放页面持有的下载线程与标志
 *
 * 震动反馈与主题适配由页面内的 [com.mcai.ubuntudsu.ui.Haptics] / [Ui] 承担。
 */
class UsbBootActivity : AppCompatActivity() {

    private lateinit var page: UsbBootPage
    private var finishing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.enableEdgeToEdge(this, window.decorView)
        window.setBackgroundDrawableResource(android.R.color.transparent)

        val root = FrameLayout(this)
        root.background = Ui.liquidBackground(this)
        root.clipToOutline = true
        root.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                val r = (32 * view.resources.displayMetrics.density)
                outline.setRoundRect(0, 0, view.width, view.height, r)
            }
        }
        Ui.animateLiquidBackground(root)

        page = UsbBootPage(this) { finish() }
        val content = page.build()
        Ui.applyContentInsets(content)
        root.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        )
        setContentView(root)
    }

    override fun onDestroy() {
        page.cleanup()
        super.onDestroy()
    }

    override fun finish() {
        if (finishing) return
        finishing = true
        super.finish()
        overridePendingTransition(R.anim.zoom_in, R.anim.zoom_out)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        page.onActivityResult(requestCode, resultCode, data)
    }

    // 全局触摸震动反馈
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        ev?.let { Haptics.onTouch(window.decorView, it) }
        return super.dispatchTouchEvent(ev)
    }
}
