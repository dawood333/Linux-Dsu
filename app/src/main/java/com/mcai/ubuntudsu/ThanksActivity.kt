package com.mcai.ubuntudsu

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.mcai.ubuntudsu.ui.Haptics
import com.mcai.ubuntudsu.ui.Ui

/**
 * Special Thanks页：内容移植自 Dsu-Manager DNA界面 / 设置 / Special Thanks。
 * 沿用本应用拟态玻璃架构，避免引入 Dsu-Manager 的液态玻璃资源依赖。
 */
class ThanksActivity : AppCompatActivity() {

    private var finishing = false

    companion object {
        private data class Credit(
            val icon: Int,
            val title: String,
            val desc: String,
            val url: String,
        )

        private val CREDITS = listOf(
            Credit(
                R.drawable.credit_icon_author,
                "Project Author",
                "CoolApk @菜鸟_曾经的天明\nhttps://github.com/hetianming/Linux-Dsu",
                "https://www.coolapk.com/u/2705572",
            ),
            Credit(
                R.drawable.credit_icon_dna_port,
                "DNA Ported Source",
                "DNA port source author: 小你可兰\nDsu-Manager project developer",
                "https://www.coolapk.com/u/3347561",
            ),
            Credit(
                R.drawable.credit_icon_tik,
                "TIK Toolbox",
                "Some code comes from the TIK2 source; thanks to its author",
                "https://gitee.com/yeliqin666/TIK",
            ),
            Credit(
                R.drawable.credit_icon_magiskboot,
                "magiskboot",
                "Core image unpacking/packing tools",
                "https://github.com/topjohnwu/Magisk",
            ),
            Credit(
                R.drawable.credit_icon_sdat,
                "sdat2img and img2sdat",
                "transfer.list conversion tool",
                "https://github.com/xpirt",
            ),
            Credit(
                R.drawable.credit_icon_erofs,
                "erofs-extract",
                "EROFS image extraction tool",
                "https://github.com/sekaiacg/erofs-extract",
            ),
            Credit(
                R.drawable.credit_icon_dna,
                "DNA",
                "The DNA Toolbox name was inspired by @温柔的慈悲; respect to the author!",
                "https://gitee.com/sharpeter/DNA",
            ),
            Credit(
                R.drawable.credit_icon_dna_maintainer,
                "CoolApk: @相见即是缘",
                "Thanks for maintaining the DNA tools; the DNA packing feature was ported from the 20260530 version",
                "https://www.coolapk.com/u/1614257",
            ),
            Credit(
                R.drawable.credit_icon_gjj,
                "Device Modding Assistant",
                "Device Modding Assistant原作者@情非得已c，提取了Device Modding Assistant部分代码文件使用！",
                "",
            ),
            Credit(
                R.drawable.credit_icon_ffix,
                "affggh",
                "Uses @affggh's fspatch.py and open-source GitHub tools to patch permission files",
                "https://github.com/affggh/fspatch",
            ),
            Credit(
                R.drawable.icon_dsu_modern,
                "DSU-Sideloader",
                "This app's GSI installation flow is based on approaches from the DSU-Sideloader project",
                "https://github.com/VegaBobo/DSU-Sideloader",
            ),
        )
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
            setPadding(Ui.dp(16, d), Ui.dp(12, d), Ui.dp(16, d), Ui.dp(28, d))
        }

        val titleRow = FrameLayout(this).apply {
            setPadding(0, 0, 0, Ui.dp(16, d))
        }
        titleRow.addView(TextView(this).apply {
            text = "‹ Back"
            textSize = 13f
            setTextColor(Ui.buttonText(this@ThanksActivity))
            background = Ui.glassButton(this@ThanksActivity, Ui.buttonPrimary(this@ThanksActivity))
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
            text = "Special Thanks"
            textSize = 20f
            setTextColor(Ui.primaryText(this@ThanksActivity))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        })
        page.addView(titleRow)

        val headCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = Ui.neuCard(this@ThanksActivity, 20f, Ui.buttonSuccess(this@ThanksActivity))
            Ui.applyNeuShadow(this, 5f, 18f, Ui.buttonSuccess(this@ThanksActivity))
            setPadding(Ui.dp(18, d), Ui.dp(18, d), Ui.dp(18, d), Ui.dp(16, d))
        }
        headCard.addView(TextView(this).apply {
            text = "Special Thanks"
            textSize = 24f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        headCard.addView(TextView(this).apply {
            text = "In no particular order; please let us know if anyone was missed"
            textSize = 13f
            setTextColor(android.graphics.Color.argb(210, 255, 255, 255))
            setPadding(0, Ui.dp(5, d), 0, 0)
        })
        headCard.addView(TextView(this).apply {
            text = "This app would not exist without the contributions of these open-source projects and developers"
            textSize = 11f
            setTextColor(android.graphics.Color.argb(180, 255, 255, 255))
            setPadding(0, Ui.dp(4, d), 0, 0)
        })
        page.addView(headCard)

        CREDITS.forEachIndexed { index, credit ->
            page.addView(buildCreditCard(credit, index))
        }

        page.addView(TextView(this).apply {
            text = "Open source makes the world better.\nThanks to everyone who has contributed code, tutorials, and time to the Chinese Android modding community."
            textSize = 12f
            setTextColor(Ui.secondaryText(this@ThanksActivity))
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(20, d), 0, 0)
            setLineSpacing(Ui.dp(4, d).toFloat(), 1f)
        })

        return page
    }

    private fun buildCreditCard(credit: Credit, index: Int): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.neuCard(this@ThanksActivity, 20f, Ui.buttonSuccess(this@ThanksActivity))
            Ui.applyNeuShadow(this, 5f, 18f, Ui.buttonSuccess(this@ThanksActivity))
            setPadding(Ui.dp(14, d), Ui.dp(14, d), Ui.dp(14, d), Ui.dp(14, d))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = if (index == 0) Ui.dp(14, d) else Ui.dp(12, d) }
        }

        val badge = android.widget.ImageView(this).apply {
            setImageResource(credit.icon)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            background = Ui.neuInset(this@ThanksActivity, 12f)
            layoutParams = LinearLayout.LayoutParams(Ui.dp(42, d), Ui.dp(42, d))
        }
        card.addView(badge)

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(Ui.dp(12, d), 0, Ui.dp(6, d), 0)
        }
        textCol.addView(TextView(this).apply {
            text = credit.title
            textSize = 13f
            setTextColor(Ui.primaryText(this@ThanksActivity))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        if (credit.desc.isNotEmpty()) {
            textCol.addView(TextView(this).apply {
                text = credit.desc
                textSize = 11f
                setTextColor(Ui.secondaryText(this@ThanksActivity))
                setPadding(0, Ui.dp(2, d), 0, 0)
                setLineSpacing(Ui.dp(2, d).toFloat(), 1f)
            })
        }
        card.addView(textCol)

        if (credit.url.isNotEmpty()) {
            card.addView(TextView(this).apply {
                text = "↗"
                textSize = 16f
                setTextColor(Ui.buttonSuccess(this@ThanksActivity))
                background = Ui.neuInset(this@ThanksActivity, 12f)
                gravity = Gravity.CENTER
                isClickable = true
                Ui.pressAnimation(this)
                layoutParams = LinearLayout.LayoutParams(Ui.dp(34, d), Ui.dp(34, d))
                setOnClickListener {
                    Haptics.perform(this)
                    openUrl(credit.url)
                }
            })
        }

        return card
    }

    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(this, "No app available to open the link", Toast.LENGTH_SHORT).show()
        }
    }

    override fun finish() {
        if (finishing) return
        finishing = true
        super.finish()
        overridePendingTransition(R.anim.zoom_in, R.anim.zoom_out)
    }
}
