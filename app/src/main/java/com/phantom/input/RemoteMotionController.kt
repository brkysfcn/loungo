package com.phantom.input

class RemoteMotionController(
    private val onMove: (dx: Int, dy: Int) -> Unit
) {
    enum class Direction { UP, DOWN, LEFT, RIGHT }

    val pressedDirections = mutableSetOf<Direction>()
    private var pressStartTimeNanos: Long = 0L
    private var lastFrameTimeNanos: Long = 0L
    private var accumX: Double = 0.0
    private var accumY: Double = 0.0

    fun pressDirection(dir: Direction, timeNanos: Long) {
        if (pressedDirections.isEmpty()) {
            pressStartTimeNanos = timeNanos
            lastFrameTimeNanos = timeNanos
            accumX = 0.0
            accumY = 0.0
        }
        pressedDirections.add(dir)
    }

    fun releaseDirection(dir: Direction) {
        pressedDirections.remove(dir)
        if (pressedDirections.isEmpty()) {
            reset()
        }
    }

    fun onKeyDown(keyCode: Int, repeatCount: Int, timeNanos: Long): Boolean {
        if (repeatCount > 0) return true
        val dir = directionFor(keyCode) ?: return false
        pressDirection(dir, timeNanos)
        return true
    }

    fun onKeyUp(keyCode: Int, timeNanos: Long): Boolean {
        val dir = directionFor(keyCode) ?: return false
        releaseDirection(dir)
        return true
    }

    fun reset() {
        pressedDirections.clear()
        pressStartTimeNanos = 0L
        lastFrameTimeNanos = 0L
        accumX = 0.0
        accumY = 0.0
    }

    fun onFrame(frameTimeNanos: Long) {
        if (pressedDirections.isEmpty()) {
            lastFrameTimeNanos = 0L
            return
        }
        if (lastFrameTimeNanos == 0L) {
            lastFrameTimeNanos = frameTimeNanos
            return
        }
        val dtSec = (frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000.0
        lastFrameTimeNanos = frameTimeNanos
        if (dtSec <= 0.0 || dtSec > 0.1) {
            return
        }

        val tSec = (frameTimeNanos - pressStartTimeNanos) / 1_000_000_000.0
        val v = calculateVelocity(tSec)
        val distance = v * dtSec

        var dirX = 0.0
        var dirY = 0.0
        if (Direction.RIGHT in pressedDirections) dirX += 1.0
        if (Direction.LEFT in pressedDirections) dirX -= 1.0
        if (Direction.DOWN in pressedDirections) dirY += 1.0
        if (Direction.UP in pressedDirections) dirY -= 1.0

        if (dirX == 0.0 && dirY == 0.0) return

        accumX += dirX * distance
        accumY += dirY * distance

        val stepX = accumX.toInt()
        val stepY = accumY.toInt()

        accumX -= stepX
        accumY -= stepY

        if (stepX != 0 || stepY != 0) {
            onMove(stepX, stepY)
        }
    }

    companion object {
        fun calculateVelocity(tSec: Double): Double {
            val t = if (tSec < 0.0) 0.0 else tSec
            // Tuned on a 1080p TV with user feedback (twice slowed down from 400 + 1600t, max 2000).
            return minOf(160.0 + 480.0 * t, 800.0)
        }

        fun directionFor(keyCode: Int): Direction? = when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_UP -> Direction.UP
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> Direction.DOWN
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> Direction.LEFT
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> Direction.RIGHT
            else -> null
        }

        /**
         * Simulates holding a direction for [totalDurationSec] at [fps] frames per second,
         * returning the total integer pixels moved using accumulation logic.
         */
        fun simulateTotalDistance(totalDurationSec: Double, fps: Double = 60.0): Int {
            val dtSec = 1.0 / fps
            val frameCount = (totalDurationSec * fps).toInt()
            var accum = 0.0
            var totalMoved = 0

            for (i in 0 until frameCount) {
                val tSec = i * dtSec
                val v = calculateVelocity(tSec)
                val dist = v * dtSec
                accum += dist
                val step = accum.toInt()
                accum -= step
                totalMoved += step
            }
            return totalMoved
        }
    }
}
