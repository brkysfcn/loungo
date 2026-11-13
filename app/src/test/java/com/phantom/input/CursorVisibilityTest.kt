package com.phantom.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CursorVisibilityTest {

    @Test
    fun cursorAlwaysVisibleWhenNotFullscreenAndNotPlayer() {
        assertTrue(cursorVisible(fullscreen = false, playerOpen = false, idleMs = 0L))
        assertTrue(cursorVisible(fullscreen = false, playerOpen = false, idleMs = 2999L))
        assertTrue(cursorVisible(fullscreen = false, playerOpen = false, idleMs = 3000L))
        assertTrue(cursorVisible(fullscreen = false, playerOpen = false, idleMs = 10000L))
    }

    @Test
    fun cursorHidesAfter3SecondsInFullscreen() {
        assertTrue(cursorVisible(fullscreen = true, playerOpen = false, idleMs = 0L))
        assertTrue(cursorVisible(fullscreen = true, playerOpen = false, idleMs = 2999L))
        assertFalse(cursorVisible(fullscreen = true, playerOpen = false, idleMs = 3000L))
        assertFalse(cursorVisible(fullscreen = true, playerOpen = false, idleMs = 4500L))
    }

    @Test
    fun cursorHidesAfter3SecondsWhenPlayerOpen() {
        assertTrue(cursorVisible(fullscreen = false, playerOpen = true, idleMs = 1500L))
        assertFalse(cursorVisible(fullscreen = false, playerOpen = true, idleMs = 3000L))
        assertFalse(cursorVisible(fullscreen = false, playerOpen = true, idleMs = 5000L))
    }

    @Test
    fun cursorHidesWhenBothFullscreenAndPlayerOpen() {
        assertTrue(cursorVisible(fullscreen = true, playerOpen = true, idleMs = 1000L))
        assertFalse(cursorVisible(fullscreen = true, playerOpen = true, idleMs = 3000L))
    }
}
