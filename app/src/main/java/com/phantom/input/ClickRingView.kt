package com.phantom.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class ClickRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    // Outer 1.5 dp #000 @ 60% opacity (drawn with 6 dp width under 3 dp white stroke)
    private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(153, 0, 0, 0)
        style = Paint.Style.STROKE
        strokeWidth = 6f * density
    }

    // Inner 3 dp white stroke
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }

    private var isHeld = false
    private val holdRunnable = Runnable {
        if (isHeld) {
            animate().cancel()
            scaleX = 18f / 40f
            scaleY = 18f / 40f
            alpha = 0.5f
        }
    }

    init {
        visibility = View.VISIBLE
        alpha = 0f
        isClickable = false
        isFocusable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pivotX = w / 2f
        pivotY = h / 2f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val radius = (Math.min(w, h) - 6f * density) / 2f
        if (radius > 0f) {
            canvas.drawCircle(w / 2f, h / 2f, radius, outerPaint)
            canvas.drawCircle(w / 2f, h / 2f, radius, innerPaint)
        }
    }

    fun onDown() {
        isHeld = true
        removeCallbacks(holdRunnable)
        animate().cancel()
        scaleX = 12f / 40f
        scaleY = 12f / 40f
        alpha = 0.7f
        animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .alpha(0f)
            .setDuration(250L)
            .start()
        postDelayed(holdRunnable, 150L)
    }

    fun onUp() {
        isHeld = false
        removeCallbacks(holdRunnable)
        if (alpha > 0f && scaleX < 0.8f) {
            animate()
                .alpha(0f)
                .setDuration(150L)
                .start()
        }
    }
}
