package com.mcai.ubuntudsu.ui.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import com.mcai.ubuntudsu.ui.Ui

/**
 * 底部导航的滑动液态玻璃透镜（Liquid Glass Indicator）。
 *
 * 移植自参考项目 LiquidGlassIndicator：一块圆角玻璃透镜悬浮在导航项后方，
 * 随选中项左右滑动并轻微变形/放大。多层绘制：
 *  ① 上亮下暗的竖向玻璃主体渐变 + 柔和投影
 *  ② 顶部内侧反光弧
 *  ③ 菲涅尔描边
 *  ④ 内侧青色折射
 *  ⑤ 上下两段弧线高光 + 底部内侧压暗
 *
 * 双主题：日间偏白玻璃、夜间偏青玻璃（配合霓虹背景可读）。
 */
class LiquidGlassIndicator @JvmOverloads constructor(
    context: Context,
    private val dark: Boolean = Ui.isDark(context),
) : View(context) {

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlight = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG)
    private val refraction = Paint(Paint.ANTI_ALIAS_FLAG)
    private val innerShadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private val topReflection = RectF()
    private val inner = RectF()
    private val lowerEdge = RectF()
    private var pressed = false

    // 日间白玻璃 / 夜间青玻璃
    private val bodyGradient: IntArray = if (dark) {
        intArrayOf(0x7A9FD4FF.toInt(), 0x3A5C93C0.toInt(), 0x243A6686.toInt())
    } else {
        intArrayOf(0x68FFFFFF.toInt(), 0x32FFFFFF.toInt(), 0x1DAFAFAF.toInt())
    }
    private val rimColor: Int = if (dark) 0xC8F4FBFF.toInt() else 0xB8FFFFFF.toInt()
    private val refractionColor: Int = if (dark) 0x6A5C93C0.toInt() else 0x6A7EBFDE.toInt()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        highlight.style = Paint.Style.STROKE
        highlight.strokeWidth = d
        rim.style = Paint.Style.STROKE
        rim.strokeWidth = d
        innerShadow.style = Paint.Style.STROKE
        innerShadow.strokeWidth = 2f * d
    }

    fun setLiquidPressed(pressed: Boolean) {
        if (this.pressed != pressed) {
            this.pressed = pressed
            invalidate()
        }
    }

    private fun dp(v: Float) = v * d

    override fun onDraw(canvas: Canvas) {
        bounds.set(dp(1f), dp(1f), width - dp(1f), height - dp(1f))
        val radius = bounds.height() / 2f

        // ① 玻璃主体 + 投影
        fill.shader = null
        fill.setShadowLayer(
            if (pressed) dp(9f) else dp(4f), 0f,
            if (pressed) dp(5f) else dp(2f),
            0x48000000.toInt(),
        )
        fill.shader = LinearGradient(
            0f, bounds.top, 0f, bounds.bottom,
            bodyGradient, null, Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(bounds, radius, radius, fill)
        fill.shader = null

        // ② 顶部内侧反光弧
        highlight.color = 0xD0FFFFFF.toInt()
        highlight.strokeWidth = dp(if (pressed) 2f else 1f)
        topReflection.set(
            bounds.left + dp(7f), bounds.top + dp(3f),
            bounds.right - dp(7f), bounds.top + dp(14f),
        )
        canvas.drawArc(topReflection, 198f, 144f, false, highlight)

        // ③ 菲涅尔描边
        rim.color = rimColor
        canvas.drawRoundRect(bounds, radius, radius, rim)

        // ④ 内侧青色折射
        inner.set(
            bounds.left + dp(4f), bounds.top + dp(4f),
            bounds.right - dp(4f), bounds.bottom - dp(4f),
        )
        refraction.style = Paint.Style.FILL
        refraction.shader = RadialGradient(
            bounds.centerX(), bounds.top + dp(4f), bounds.width() * 0.68f,
            intArrayOf(0x52FFFFFF.toInt(), 0x1D8DD6EB.toInt(), 0x008088BB),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(inner, radius - dp(3f), radius - dp(3f), refraction)
        refraction.shader = null

        // ⑤ 上下弧线高光 + 底部压暗
        refraction.style = Paint.Style.STROKE
        refraction.strokeWidth = d
        refraction.color = refractionColor
        canvas.drawArc(bounds, 8f, 164f, false, refraction)
        refraction.color = if (dark) 0x465C93C0.toInt() else 0x468EBADB.toInt()
        canvas.drawArc(bounds, 188f, 150f, false, refraction)

        innerShadow.color = 0x3D3D3D00.toInt()
        lowerEdge.set(
            bounds.left + dp(3f), bounds.top + dp(3f),
            bounds.right - dp(3f), bounds.bottom - dp(1f),
        )
        canvas.drawArc(lowerEdge, 12f, 156f, false, innerShadow)
    }
}
