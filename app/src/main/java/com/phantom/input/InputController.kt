package com.phantom.input

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.WebView

class InputController(val context: Context, var targetView: android.view.View, val cursorState: CursorState) {
    val webView: android.view.View get() = targetView
    private val handler = Handler(Looper.getMainLooper())
    private var isDown = false
    private var downTime = 0L
    @Volatile private var hoverDirty = false

    private val density = context.resources.displayMetrics.density
    private val vc = ViewConfiguration.get(context)
    private val vf = try { vc.scaledVerticalScrollFactor } catch (e: NoSuchMethodError) { 64f * density }
    private val hf = try { vc.scaledHorizontalScrollFactor } catch (e: NoSuchMethodError) { 64f * density }

    private val pointerProperties = MotionEvent.PointerProperties().apply {
        id = 0
        toolType = MotionEvent.TOOL_TYPE_MOUSE
    }

    private val setActionButtonMethod = try {
        MotionEvent::class.java.getMethod("setActionButton", Int::class.javaPrimitiveType)
    } catch (e: Throwable) {
        null
    }

    private fun setActionButton(event: MotionEvent, button: Int) {
        try {
            setActionButtonMethod?.invoke(event, button)
        } catch (e: Throwable) {}
    }

    private fun mouseEvent(
        action: Int,
        x: Float,
        y: Float,
        buttons: Int,
        dTime: Long,
        vScroll: Float = 0f,
        hScroll: Float = 0f
    ): MotionEvent {
        val now = SystemClock.uptimeMillis()
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            this.pressure = if (action == MotionEvent.ACTION_HOVER_MOVE || action == MotionEvent.ACTION_SCROLL) 0f else 1f
            this.size = 1f
            if (vScroll != 0f || hScroll != 0f) {
                setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
                setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll)
            }
        }
        return MotionEvent.obtain(
            dTime, now, action,
            1, arrayOf(pointerProperties), arrayOf(coords),
            0, buttons, 1f, 1f, 0, 0,
            InputDevice.SOURCE_MOUSE, 0
        )!!
    }

    fun onFrame() {
        if (hoverDirty && !isDown) {
            hoverDirty = false
            val x = cursorState.getX().toFloat()
            val y = cursorState.getY().toFloat()
            val ev = mouseEvent(MotionEvent.ACTION_HOVER_MOVE, x, y, 0, downTime)
            targetView.dispatchGenericMotionEvent(ev)
            ev.recycle()
        }
    }

    fun onPointer(px: Float, py: Float) {
        handler.post {
            cursorState.setPosition(px.toInt(), py.toInt())
            if (!isDown) {
                hoverDirty = false
                val x = cursorState.getX().toFloat()
                val y = cursorState.getY().toFloat()
                val ev = mouseEvent(MotionEvent.ACTION_HOVER_MOVE, x, y, 0, downTime)
                targetView.dispatchGenericMotionEvent(ev)
                ev.recycle()
            }
        }
    }

    fun onMove(dx: Short, dy: Short) {
        cursorState.moveBy(dx.toInt(), dy.toInt())
        handler.post {
            if (isDown) {
                val x = cursorState.getX().toFloat()
                val y = cursorState.getY().toFloat()
                val ev = mouseEvent(MotionEvent.ACTION_MOVE, x, y, MotionEvent.BUTTON_PRIMARY, downTime)
                targetView.dispatchTouchEvent(ev)
                ev.recycle()
            } else {
                hoverDirty = true
            }
        }
    }

    fun onButton(down: Boolean, btn: Int) {
        handler.post {
            if (btn == 1) {
                Log.i("PHANTOM", "IGNORED right")
                return@post
            }
            val x = cursorState.getX().toFloat()
            val y = cursorState.getY().toFloat()
            val now = SystemClock.uptimeMillis()
            if (down && !isDown) {
                isDown = true
                hoverDirty = false
                downTime = now
                val pressEv = mouseEvent(MotionEvent.ACTION_BUTTON_PRESS, x, y, MotionEvent.BUTTON_PRIMARY, downTime)
                setActionButton(pressEv, MotionEvent.BUTTON_PRIMARY)
                targetView.dispatchGenericMotionEvent(pressEv)
                pressEv.recycle()

                val ev = mouseEvent(MotionEvent.ACTION_DOWN, x, y, MotionEvent.BUTTON_PRIMARY, downTime)
                targetView.dispatchTouchEvent(ev)
                ev.recycle()
            } else if (!down && isDown) {
                isDown = false
                val ev = mouseEvent(MotionEvent.ACTION_UP, x, y, 0, downTime)
                targetView.dispatchTouchEvent(ev)
                ev.recycle()

                val releaseEv = mouseEvent(MotionEvent.ACTION_BUTTON_RELEASE, x, y, 0, downTime)
                setActionButton(releaseEv, MotionEvent.BUTTON_PRIMARY)
                targetView.dispatchGenericMotionEvent(releaseEv)
                releaseEv.recycle()
            }
        }
    }

    fun onScroll(sx: Short, sy: Short) {
        handler.post {
            val x = cursorState.getX().toFloat()
            val y = cursorState.getY().toFloat()
            val vScroll = -sy * density / vf
            val hScroll = sx * density / hf
            val ev = mouseEvent(MotionEvent.ACTION_SCROLL, x, y, 0, downTime, vScroll, hScroll)
            targetView.dispatchGenericMotionEvent(ev)
            ev.recycle()
        }
    }

    fun onClientChanged(connected: Boolean) {
        handler.post {
            if (!connected && isDown) {
                isDown = false
                hoverDirty = false
                val x = cursorState.getX().toFloat()
                val y = cursorState.getY().toFloat()
                val ev = mouseEvent(MotionEvent.ACTION_UP, x, y, 0, downTime)
                targetView.dispatchTouchEvent(ev)
                ev.recycle()

                val releaseEv = mouseEvent(MotionEvent.ACTION_BUTTON_RELEASE, x, y, 0, downTime)
                setActionButton(releaseEv, MotionEvent.BUTTON_PRIMARY)
                targetView.dispatchGenericMotionEvent(releaseEv)
                releaseEv.recycle()
            }
        }
    }
}
