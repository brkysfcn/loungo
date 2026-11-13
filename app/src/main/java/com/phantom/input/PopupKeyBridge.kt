package com.phantom.input

import android.app.Instrumentation
import android.view.KeyEvent
import java.util.concurrent.Executors

/**
 * Drives a window that sits above the browser (a page's <select> list, an alert) with key
 * events, because the cursor and its injected mouse events only reach the browser window.
 * Phone trackpad movement becomes Up/Down steps, a tap becomes OK. Keys are injected with
 * Instrumentation, which is allowed for windows of this app; it must run off the main thread.
 */
class PopupKeyBridge(private val stepPx: Int) {
    private val executor = Executors.newSingleThreadExecutor()
    private val instrumentation = Instrumentation()
    private var accumulated = 0

    @Synchronized
    fun move(dy: Int) {
        accumulated += dy
        while (accumulated >= stepPx) {
            accumulated -= stepPx
            key(KeyEvent.KEYCODE_DPAD_DOWN)
        }
        while (accumulated <= -stepPx) {
            accumulated += stepPx
            key(KeyEvent.KEYCODE_DPAD_UP)
        }
    }

    @Synchronized
    fun reset() {
        accumulated = 0
    }

    fun key(code: Int) {
        executor.execute {
            try {
                instrumentation.sendKeyDownUpSync(code)
            } catch (_: Throwable) {
                // The focused window belongs to another app (e.g. the system UI): nothing to drive.
            }
        }
    }
}
