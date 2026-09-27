package com.mcai.ubuntudsu

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.mcai.ubuntudsu.ui.Ui
import com.mcai.ubuntudsu.ui.pages.OtgAssistantPage

/**
 * OTG 助手 - 薄脚手架 Activity
 *
 * 界面与交互全部交由 [OtgAssistantPage]（主应用拟态 + 液态玻璃架构），
 * 本类只负责生命周期接线：
 *  - onCreate：挂液态玻璃背景 + edge-to-edge，构建页面并注册 USB 插拔广播
 *  - onActivityResult：派发到页面的文件选择处理
 *  - onDestroy：注销广播并释放页面持有的处理器
 *
 * 震动反馈与主题适配由页面内的 [com.mcai.ubuntudsu.ui.Haptics] / [Ui] 承担。
 */
class OtgAssistantActivity : AppCompatActivity() {

    private lateinit var page: OtgAssistantPage

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.enableEdgeToEdge(this, window.decorView)
        window.setBackgroundDrawableResource(android.R.color.transparent)

        val root = android.widget.FrameLayout(this)
        root.background = Ui.liquidBackground(this)
        root.clipToOutline = true
        root.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                val r = (32 * view.resources.displayMetrics.density)
                outline.setRoundRect(0, 0, view.width, view.height, r)
            }
        }
        Ui.animateLiquidBackground(root)

        page = OtgAssistantPage(this) { finish() }
        val content = page.build()
        Ui.applyContentInsets(content)
        root.addView(
            content,
            android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)

        // USB 插拔自动刷新：需在页面构建后、onCreate 内注册广播
        page.registerReceiver()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        page.onActivityResult(requestCode, resultCode, data)
    }

    override fun onDestroy() {
        page.destroy()
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.activity_scale_up_enter, R.anim.activity_scale_down_exit)
    }
}
