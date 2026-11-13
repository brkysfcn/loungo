package com.phantom.input

/**
 * Returns whether the cursor should be visible based on fullscreen/player state
 * and idle duration in milliseconds.
 *
 * Rules:
 * - When in fullscreen or native player is open, cursor hides after 3 seconds of inactivity.
 * - Outside of fullscreen/player, cursor is always visible.
 */
fun cursorVisible(fullscreen: Boolean, playerOpen: Boolean, idleMs: Long): Boolean {
    if (!fullscreen && !playerOpen) return true
    return idleMs < 3000L
}
