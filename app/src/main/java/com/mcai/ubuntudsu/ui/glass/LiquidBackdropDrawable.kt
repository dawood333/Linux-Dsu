package com.mcai.ubuntudsu.ui.glass

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator

/**
 * 动态弥散光斑背景（Liquid Backdrop）。
 *
 * 移植自参考项目的 TrueGlass.LiquidBackdrop：底层上浅下深的蓝灰渐变，
 * 叠加 6 个缓慢漂移 + 轻微呼吸缩放的彩色弥散光斑。玻璃面板的"折射感"
 * 正是来自这些背景色彩在玻璃后方流动。
 *
 * 以 Drawable 形式实现，可直接作为任意 View 的 background；当 Drawable 可见
 * 时自动开始动画，不可见时自动停止，无需外部管理生命周期。
 */
class LiquidBackdropDrawable(private val dark: Boolean) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 16000L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidateSelf()
        }
    }

    /** 光斑定义：cx, cy（比例）, 半径比例 */
    private val blobs = arrayOf(
        floatArrayOf(0.22f, 0.16f, 0.62f),
        floatArrayOf(0.86f, 0.10f, 0.55f),
        floatArrayOf(0.72f, 0.88f, 0.66f),
        floatArrayOf(0.12f, 0.74f, 0.58f),
        floatArrayOf(0.50f, 0.46f, 0.50f),
        floatArrayOf(0.55f, 1.12f, 0.70f),
    )

    /** 日间：柔和的天空冷暖光斑（天蓝 / 青绿 / 淡紫 / 纯白高光 / 亮青 / 底部深蓝压暗） */
    private val dayBlobs = intArrayOf(
        0x9FA8D4F5.toInt(), 0x7A79D9CE.toInt(), 0x60B9A8EC.toInt(),
        0x80FFFFFF.toInt(), 0x5296DCE8.toInt(), 0x4051789E.toInt(),
    )

    /** 夜间：低透明霓虹光斑（蓝 / 青绿 / 紫 / 柔白 / 冰青 / 深蓝压暗） */
    private val nightBlobs = intArrayOf(
        0x665B9DFF.toInt(), 0x5534D399.toInt(), 0x4A8B5CF6.toInt(),
        0x38FFFFFF.toInt(), 0x4022D3EE.toInt(), 0x401E3A5F.toInt(),
    )

    private val blobColors = if (dark) nightBlobs else dayBlobs

    private val baseColors: IntArray = if (dark) {
        intArrayOf(0xFF151B28.toInt(), 0xFF1A2230.toInt(), 0xFF212B3C.toInt(), 0xFF171E2B.toInt())
    } else {
        intArrayOf(0xFFE9F0F8.toInt(), 0xFFD3DFEC.toInt(), 0xFFB8C9DC.toInt(), 0xFFA3B8CF.toInt())
    }
    private val basePositions = floatArrayOf(0f, 0.42f, 0.78f, 1f)

    private val topLight: Int = if (dark) 0x1EFFFFFF else 0x5AFFFFFF

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val left = bounds.left.toFloat()
        val top = bounds.top.toFloat()

        // 1. 基底：上浅下深的蓝灰渐变
        paint.shader = LinearGradient(
            0f, top, 0f, top + h,
            baseColors, basePositions, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(left, top, left + w, top + h, paint)

        // 2. 弥散光斑（缓慢漂移 + 轻微呼吸缩放）
        val t = phase * Math.PI.toFloat() * 2f
        val maxDim = Math.max(w, h)
        for (i in blobs.indices) {
            val b = blobs[i]
            val sway = Math.sin((t + i * 1.9f).toDouble()).toFloat()
            val breath = 0.92f + 0.08f * Math.cos((t + i * 0.7f).toDouble()).toFloat()
            val cx = left + (b[0] + 0.05f * sway) * w
            val cy = top + (b[1] + 0.04f * Math.cos((t + i).toDouble()).toFloat()) * h
            val radius = b[2] * maxDim * breath
            paint.shader = RadialGradient(cx, cy, radius, blobColors[i], 0x00000000, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, radius, paint)
        }
        paint.shader = null

        // 3. 顶部整片柔光，让玻璃有"天空反光"
        paint.shader = LinearGradient(
            0f, top, 0f, top + h * 0.4f,
            topLight, 0x00FFFFFF, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(left, top, left + w, top + h * 0.4f, paint)
        paint.shader = null
    }

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (visible) {
            if (!animator.isStarted) animator.start()
        } else {
            animator.cancel()
        }
        return changed
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
