package com.phantom.onboarding

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * Simple line drawings for the first-run showcase pages (0 = logo mark, 1..4 = feature pages).
 * Drawn on a 100x100 unit square centred in the view; white/grey plus the accent blue only.
 */
class ShowcaseArt(context: Context, private val page: Int) : View(context) {

    private val accent = 0xFF0A84FF.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val grey = 0xFF8E8E96.toInt()
    private val dim = 0xFF2C2C2E.toInt()

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private var u = 1f

    init {
        alpha = 0f
        animate().alpha(1f).setDuration(250L).start() // the only motion: a short fade-in
    }

    private fun line(c: Int, w: Float = 1.2f, dashed: Boolean = false): Paint = stroke.apply {
        color = c; strokeWidth = w * u
        pathEffect = if (dashed) DashPathEffect(floatArrayOf(1.6f * u, 2.2f * u), 0f) else null
    }

    private fun solid(c: Int): Paint = fill.apply { color = c }

    private fun rr(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, p: Paint) =
        c.drawRoundRect(RectF(l, t, r, b), rad, rad, p)

    override fun onDraw(canvas: Canvas) {
        val side = minOf(width, height).toFloat()
        u = side / 100f
        canvas.save()
        canvas.translate((width - side) / 2f, (height - side) / 2f)
        canvas.scale(u, u) // unit coordinates below; stroke widths are divided back via u in line()
        u = 1f // inside the scaled canvas one unit is one unit
        when (page) {
            0 -> drawLogo(canvas)
            1 -> drawPhoneAndTv(canvas)
            2 -> drawVideo(canvas)
            3 -> drawAdBlock(canvas)
            else -> drawGaugeAndLock(canvas)
        }
        canvas.restore()
    }

    private fun drawLogo(c: Canvas) {
        c.drawCircle(50f, 50f, 34f, line(grey, 1.6f))
        c.drawCircle(50f, 50f, 22f, line(white, 2f))
        c.drawCircle(50f, 50f, 8f, solid(accent))
    }

    private fun drawTv(c: Canvas, l: Float, t: Float, r: Float, b: Float) {
        rr(c, l, t, r, b, 3f, line(white, 1.6f))
        val cx = (l + r) / 2f
        c.drawLine(cx, b, cx, b + 7f, line(grey, 1.6f))
        c.drawLine(cx - 12f, b + 7f, cx + 12f, b + 7f, line(grey, 1.6f))
    }

    private fun drawPhoneAndTv(c: Canvas) {
        drawTv(c, 34f, 12f, 96f, 62f)
        // phone with a trackpad area on its screen
        rr(c, 6f, 46f, 28f, 92f, 4f, line(white, 1.6f))
        rr(c, 10f, 52f, 24f, 80f, 2f, line(grey, 1f))
        c.drawLine(15f, 87f, 19f, 87f, line(grey, 1.2f))
        // dotted link phone -> cursor
        val link = Path().apply { moveTo(24f, 66f); quadTo(48f, 70f, 62f, 40f) }
        c.drawPath(link, line(accent, 1.6f, dashed = true))
        // cursor on the TV
        val cur = Path().apply {
            moveTo(64f, 34f); lineTo(64f, 52f); lineTo(68.5f, 47.5f); lineTo(72f, 55f)
            lineTo(75f, 53.5f); lineTo(71.5f, 46.5f); lineTo(77f, 46.5f); close()
        }
        c.drawPath(cur, solid(white))
        c.drawPath(cur, line(accent, 1f))
    }

    private fun drawVideo(c: Canvas) {
        drawTv(c, 4f, 14f, 96f, 74f)
        rr(c, 8f, 18f, 92f, 70f, 1.5f, solid(0xFF000000.toInt()))
        // 21:9 picture (84 x 36) centred; the bands above and below stay black
        c.drawRect(8f, 26f, 92f, 62f, solid(dim))
        c.drawRect(8f, 26f, 92f, 62f, line(grey, 0.9f))
        // play icon
        c.drawCircle(50f, 44f, 8f, solid(accent))
        val tri = Path().apply { moveTo(47.5f, 39.5f); lineTo(47.5f, 48.5f); lineTo(55f, 44f); close() }
        c.drawPath(tri, solid(white))
        // subtitle bar in the lower black band
        rr(c, 28f, 63.5f, 72f, 67.5f, 2f, solid(white))
    }

    private fun drawAdBlock(c: Canvas) {
        rr(c, 12f, 6f, 70f, 94f, 4f, line(white, 1.6f))
        c.drawLine(18f, 14f, 50f, 14f, line(grey, 1.4f))
        for ((top, bottom) in listOf(22f to 42f, 48f to 68f)) {
            rr(c, 18f, top, 64f, bottom, 2.5f, line(grey, 1.2f, dashed = true))
            text.color = grey; text.textSize = 9f
            c.drawText("AD", 41f, (top + bottom) / 2f + 3f, text)
            c.drawLine(20f, top + 2f, 62f, bottom - 2f, line(accent, 2f))
            c.drawLine(62f, top + 2f, 20f, bottom - 2f, line(accent, 2f))
        }
        c.drawLine(18f, 78f, 56f, 78f, line(grey, 1.4f))
        c.drawLine(18f, 85f, 46f, 85f, line(grey, 1.4f))
        // shield with a check mark
        val shield = Path().apply {
            moveTo(80f, 52f); lineTo(95f, 58f); lineTo(95f, 74f)
            quadTo(95f, 88f, 80f, 96f); quadTo(65f, 88f, 65f, 74f); lineTo(65f, 58f); close()
        }
        c.drawPath(shield, solid(accent))
        c.drawPath(shield, line(0xFF101014.toInt(), 1.4f))
        val check = Path().apply { moveTo(72f, 74f); lineTo(78f, 80f); lineTo(89f, 66f) }
        c.drawPath(check, line(white, 2.4f))
    }

    private fun drawGaugeAndLock(c: Canvas) {
        val oval = RectF(14f, 10f, 86f, 82f)
        c.drawArc(oval, 180f, 180f, false, line(grey, 2.2f))
        c.drawArc(oval, 180f, 128f, false, line(accent, 2.6f))
        // ticks
        for (i in 0..6) {
            val a = Math.toRadians(180.0 + i * 30.0)
            val x1 = 50f + 30f * Math.cos(a).toFloat(); val y1 = 46f + 30f * Math.sin(a).toFloat()
            val x2 = 50f + 34f * Math.cos(a).toFloat(); val y2 = 46f + 34f * Math.sin(a).toFloat()
            c.drawLine(x1, y1, x2, y2, line(white, 1.2f))
        }
        val na = Math.toRadians(180.0 + 128.0)
        c.drawLine(50f, 46f, 50f + 26f * Math.cos(na).toFloat(), 46f + 26f * Math.sin(na).toFloat(), line(white, 2f))
        c.drawCircle(50f, 46f, 3.2f, solid(white))
        // lock
        c.drawArc(RectF(40f, 62f, 60f, 84f), 180f, 180f, false, line(white, 2.2f))
        rr(c, 36f, 73f, 64f, 96f, 4f, solid(accent))
        c.drawCircle(50f, 82f, 2.6f, solid(white))
        c.drawLine(50f, 83f, 50f, 89f, line(white, 2f))
    }
}
