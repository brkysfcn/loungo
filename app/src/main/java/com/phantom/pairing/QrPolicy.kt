package com.phantom.pairing

/**
 * Pure policy function for determining QR card visibility.
 *
 * Rules:
 * - Never visible if fullscreen or media is playing.
 * - Never visible if a client is connected.
 * - Visible if manually triggered via remote (manual == true).
 * - Visible if never paired (!paired), until client connects.
 * - Visible on app launch for 20 seconds (sinceLaunchMs in 0..20000) if no client connected.
 * - Otherwise hidden (e.g. on client disconnect after 20s).
 */
fun qrVisible(
    paired: Boolean,
    sinceLaunchMs: Long,
    clientConnected: Boolean,
    manual: Boolean,
    fullscreen: Boolean,
    mediaPlaying: Boolean,
    rejectBurst: Boolean = false
): Boolean {
    if (fullscreen || mediaPlaying) {
        return false
    }

    if (clientConnected) {
        return false
    }

    if (manual) {
        return true
    }

    if (rejectBurst) {
        return true
    }

    if (!paired) {
        return true
    }

    if (sinceLaunchMs in 0 until 20_000) {
        return true
    }

    return false
}
