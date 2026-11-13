package com.phantom.pairing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrPolicyTest {

    @Test
    fun testNeverVisibleWhenFullscreen() {
        assertFalse(qrVisible(paired = false, sinceLaunchMs = 1000, clientConnected = false, manual = true, fullscreen = true, mediaPlaying = false))
        assertFalse(qrVisible(paired = true, sinceLaunchMs = 5000, clientConnected = false, manual = false, fullscreen = true, mediaPlaying = false))
    }

    @Test
    fun testNeverVisibleWhenMediaPlaying() {
        assertFalse(qrVisible(paired = false, sinceLaunchMs = 1000, clientConnected = false, manual = true, fullscreen = false, mediaPlaying = true))
        assertFalse(qrVisible(paired = true, sinceLaunchMs = 5000, clientConnected = false, manual = false, fullscreen = false, mediaPlaying = true))
    }

    @Test
    fun testNeverVisibleWhenClientConnected() {
        assertFalse(qrVisible(paired = false, sinceLaunchMs = 1000, clientConnected = true, manual = true, fullscreen = false, mediaPlaying = false))
        assertFalse(qrVisible(paired = true, sinceLaunchMs = 5000, clientConnected = true, manual = false, fullscreen = false, mediaPlaying = false))
    }

    @Test
    fun testVisibleWhenNeverPaired() {
        assertTrue(qrVisible(paired = false, sinceLaunchMs = 50_000, clientConnected = false, manual = false, fullscreen = false, mediaPlaying = false))
    }

    @Test
    fun testVisibleDuringStartup20Seconds() {
        // Paired, within 20s
        assertTrue(qrVisible(paired = true, sinceLaunchMs = 0, clientConnected = false, manual = false, fullscreen = false, mediaPlaying = false))
        assertTrue(qrVisible(paired = true, sinceLaunchMs = 10_000, clientConnected = false, manual = false, fullscreen = false, mediaPlaying = false))
        assertTrue(qrVisible(paired = true, sinceLaunchMs = 19_999, clientConnected = false, manual = false, fullscreen = false, mediaPlaying = false))

        // After 20s, disappears
        assertFalse(qrVisible(paired = true, sinceLaunchMs = 20_000, clientConnected = false, manual = false, fullscreen = false, mediaPlaying = false))
        assertFalse(qrVisible(paired = true, sinceLaunchMs = 60_000, clientConnected = false, manual = false, fullscreen = false, mediaPlaying = false))
    }

    @Test
    fun testHiddenOnDisconnectAfterStartup() {
        // Paired device was connected, then disconnected at 45s. Must NOT show QR card!
        assertFalse(qrVisible(paired = true, sinceLaunchMs = 45_000, clientConnected = false, manual = false, fullscreen = false, mediaPlaying = false))
    }

    @Test
    fun testManualToggleOverridesAfterStartup() {
        // Remote manual trigger allows showing even after 20s
        assertTrue(qrVisible(paired = true, sinceLaunchMs = 60_000, clientConnected = false, manual = true, fullscreen = false, mediaPlaying = false))
    }

    @Test
    fun testRejectBurst() {
        // Reject burst shows QR even after 20s when not connected
        assertTrue(qrVisible(paired = true, sinceLaunchMs = 60_000, clientConnected = false, manual = false, fullscreen = false, mediaPlaying = false, rejectBurst = true))

        // But never when client connected, fullscreen, or media playing
        assertFalse(qrVisible(paired = true, sinceLaunchMs = 60_000, clientConnected = true, manual = false, fullscreen = false, mediaPlaying = false, rejectBurst = true))
        assertFalse(qrVisible(paired = true, sinceLaunchMs = 60_000, clientConnected = false, manual = false, fullscreen = true, mediaPlaying = false, rejectBurst = true))
        assertFalse(qrVisible(paired = true, sinceLaunchMs = 60_000, clientConnected = false, manual = false, fullscreen = false, mediaPlaying = true, rejectBurst = true))
    }
}
