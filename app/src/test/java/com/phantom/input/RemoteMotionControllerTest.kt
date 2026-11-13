package com.phantom.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMotionControllerTest {

    @Test
    fun testVelocityCurve() {
        assertEquals(160.0, RemoteMotionController.calculateVelocity(0.0), 0.001)
        assertEquals(400.0, RemoteMotionController.calculateVelocity(0.5), 0.001)
        assertEquals(640.0, RemoteMotionController.calculateVelocity(1.0), 0.001)
        assertEquals(800.0, RemoteMotionController.calculateVelocity(2.0), 0.001)
        assertEquals(160.0, RemoteMotionController.calculateVelocity(-0.5), 0.001)
    }

    @Test
    fun testOneSecondHoldTotalDistanceApproximates400Px() {
        // Integral of (160 + 480*t) from 0 to 1 = 160 + 240 = 400 px.
        val totalDistance = RemoteMotionController.simulateTotalDistance(1.0, 60.0)
        // Check within +-10% (360 to 440)
        assertTrue("Distance $totalDistance should be >= 360", totalDistance >= 360)
        assertTrue("Distance $totalDistance should be <= 440", totalDistance <= 440)
    }

    @Test
    fun testControllerFrameSteppingWithAccumulator() {
        var movedX = 0
        var movedY = 0
        val controller = RemoteMotionController { dx, dy ->
            movedX += dx
            movedY += dy
        }

        val startNanos = 1_000_000_000L
        controller.pressDirection(RemoteMotionController.Direction.RIGHT, startNanos)

        val frameDurationNanos = 16_666_666L // ~60 FPS
        var currentNanos = startNanos

        for (i in 1..60) {
            currentNanos += frameDurationNanos
            controller.onFrame(currentNanos)
        }

        assertTrue("movedX $movedX should be >= 360", movedX >= 360)
        assertTrue("movedX $movedX should be <= 440", movedX <= 440)
        assertEquals(0, movedY)
    }

    @Test
    fun testDiagonalMovement() {
        var movedX = 0
        var movedY = 0
        val controller = RemoteMotionController { dx, dy ->
            movedX += dx
            movedY += dy
        }

        val startNanos = 1_000_000_000L
        controller.pressDirection(RemoteMotionController.Direction.DOWN, startNanos)
        controller.pressDirection(RemoteMotionController.Direction.RIGHT, startNanos)

        var currentNanos = startNanos

        for (i in 1..60) {
            currentNanos += 16_666_666L
            controller.onFrame(currentNanos)
        }

        assertEquals(movedX, movedY)
        assertTrue(movedX >= 360)
    }
}
