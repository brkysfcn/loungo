package com.phantom

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The TV browser needs Android 12 (the phone remote runs from Android 10, so the package's minSdk is 29).
 * On an older TV [BrowserActivity] opens this screen instead; no service, server or mDNS is started.
 */
class UnsupportedTvActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (48 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.rgb(0x0F, 0x12, 0x1C))
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.tv_requires_android12)
            setTextColor(Color.WHITE)
            textSize = 24f
            gravity = Gravity.CENTER
        })
        val close = Button(this).apply {
            text = getString(R.string.btn_close)
            setOnClickListener { finish() }
        }
        root.addView(close, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = pad / 2 })
        setContentView(root)
        close.requestFocus()
    }

    companion object {
        /** The TV side (browser, player, server) requires Android 9 (API 28). */
        val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

        /** Extracts the major version number from a WebView versionName (e.g. "74.0.3729.185" -> 74). */
        fun parseWebViewMajor(versionName: String?): Int? {
            if (versionName.isNullOrBlank()) return null
            val head = versionName.substringBefore('.').trim()
            return head.toIntOrNull()
        }
    }
}
