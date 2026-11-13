package com.phantom.input

import java.util.concurrent.atomic.AtomicLong

class CursorState(@Volatile var width: Int, @Volatile var height: Int) {
    private val state = AtomicLong(pack(width / 2, height / 2))

    fun pack(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)

    fun unpackX(v: Long): Int = (v ushr 32).toInt()

    fun unpackY(v: Long): Int = v.toInt()

    fun getX(): Int = unpackX(state.get())

    fun getY(): Int = unpackY(state.get())

    fun setPosition(x: Int, y: Int) {
        val clampedX = x.coerceIn(0, (width - 1).coerceAtLeast(0))
        val clampedY = y.coerceIn(0, (height - 1).coerceAtLeast(0))
        val next = pack(clampedX, clampedY)
        while (true) {
            val cur = state.get()
            if (state.compareAndSet(cur, next)) break
        }
    }

    fun resize(newWidth: Int, newHeight: Int) {
        width = newWidth
        height = newHeight
        while (true) {
            val cur = state.get()
            val curX = unpackX(cur)
            val curY = unpackY(cur)
            val clampedX = curX.coerceIn(0, (newWidth - 1).coerceAtLeast(0))
            val clampedY = curY.coerceIn(0, (newHeight - 1).coerceAtLeast(0))
            val next = pack(clampedX, clampedY)
            if (state.compareAndSet(cur, next)) break
        }
    }

    fun moveBy(dx: Int, dy: Int): Boolean {
        while (true) {
            val cur = state.get()
            val newX = (unpackX(cur) + dx).coerceIn(0, (width - 1).coerceAtLeast(0))
            val newY = (unpackY(cur) + dy).coerceIn(0, (height - 1).coerceAtLeast(0))
            val next = pack(newX, newY)
            if (state.compareAndSet(cur, next)) return true
        }
    }
}
