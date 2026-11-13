package com.phantom.remote

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator

/** Small TV glyph with soft rings spreading out while searching; a check mark once found. */
class SearchRingsView(context: Context) : View(context) {
    private val accent = 0xFF0A84FF.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val green = 0xFF30D158.toInt()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private var phase = 0f
    private var found = false
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    fun searching() { found = false; if (!anim.isStarted) anim.start(); invalidate() }
    fun found() { found = true; anim.cancel(); invalidate() }
    fun pause() { anim.cancel() }

    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val u = minOf(width, height) / 100f
        if (!found) {
            for (i in 0 until 3) {
                val t = (phase + i / 3f) % 1f
                p.color = accent
                p.alpha = ((1f - t) * 160).toInt()
                p.strokeWidth = 1.5f * u
                c.drawCircle(cx, cy, (16f + t * 32f) * u, p)
            }
        }
        // TV glyph
        p.alpha = 255
        p.color = if (found) green else white
        p.strokeWidth = 2.2f * u
        c.drawRoundRect(RectF(cx - 14 * u, cy - 9 * u, cx + 14 * u, cy + 8 * u), 2.5f * u, 2.5f * u, p)
        c.drawLine(cx - 6 * u, cy + 12 * u, cx + 6 * u, cy + 12 * u, p)
        if (found) {
            val path = Path().apply {
                moveTo(cx - 6 * u, cy)
                lineTo(cx - 1.5f * u, cy + 4 * u)
                lineTo(cx + 7 * u, cy - 5 * u)
            }
            c.drawPath(path, p)
        }
    }
}
