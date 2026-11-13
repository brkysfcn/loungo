package com.phantom.input

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CursorStateTest {

    @Test
    fun testInitialPosition() {
        val state = CursorState(100, 200)
        assertEquals(50, state.getX())
        assertEquals(100, state.getY())
    }

    @Test
    fun testClamp() {
        val state = CursorState(100, 100)
        state.setPosition(-50, -50)
        assertEquals(0, state.getX())
        assertEquals(0, state.getY())

        state.setPosition(200, 200)
        assertEquals(99, state.getX())
        assertEquals(99, state.getY())

        state.moveBy(-200, -200)
        assertEquals(0, state.getX())
        assertEquals(0, state.getY())

        state.moveBy(500, 500)
        assertEquals(99, state.getX())
        assertEquals(99, state.getY())
    }

    @Test
    fun testResizePreservesAndClamps() {
        val state = CursorState(1000, 1000)
        state.setPosition(400, 400)
        assertEquals(400, state.getX())
        assertEquals(400, state.getY())

        // Resize larger preserves position
        state.resize(1920, 1080)
        assertEquals(400, state.getX())
        assertEquals(400, state.getY())

        // Move near bottom-right
        state.setPosition(1900, 1000)
        assertEquals(1900, state.getX())
        assertEquals(1000, state.getY())

        // Resize smaller clamps position
        state.resize(800, 600)
        assertEquals(799, state.getX())
        assertEquals(599, state.getY())
    }

    @Test
    fun testThreadSafety() {
        val state = CursorState(1000, 1000)
        val pool = Executors.newFixedThreadPool(16)
        val tasks = 1000

        for (i in 0 until tasks) {
            pool.submit {
                state.moveBy(1, 1)
            }
        }

        pool.shutdown()
        pool.awaitTermination(5, TimeUnit.SECONDS)

        val x = state.getX()
        val y = state.getY()
        // Started at (500, 500), moved by (1, 1) 1000 times, clamped at 999.
        assertEquals(999, x)
        assertEquals(999, y)
    }
}
