package com.phantom

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Choreographer
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebViewAssetLoader
import com.phantom.input.CursorState
import com.phantom.input.CursorView
import com.phantom.input.InputController
import com.phantom.input.RemoteMotionController
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import com.phantom.server.buildEditable
import com.phantom.server.buildFullscreen
import com.phantom.web.BackAction
import com.phantom.web.SitePrefs
import com.phantom.web.backAction
import com.phantom.web.buildTextScript
import com.phantom.web.toDesktopUserAgent
import com.phantom.server.BinIn
import com.phantom.server.Hub
import com.phantom.server.JsonIn
import com.phantom.server.buildErr
import com.phantom.server.buildHello
import com.phantom.server.buildState
import com.phantom.web.normalizeUrl
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.phantom.pairing.PairingHelper
import com.phantom.server.TokenManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.PixelCopy
import com.phantom.server.buildSnap
import com.phantom.server.encodeThumb
import com.phantom.server.buildMedia
import com.phantom.web.MediaDetector
import com.phantom.pairing.qrVisible

private const val FOCUS_SCRIPT = """
(function() {
    if (window.__phantomInjected) return;
    window.__phantomInjected = true;

    var isTop = (window.self === window.top);
    var reportedMediaUrls = {};

    function reportMedia(url, kind) {
        if (!url || typeof url !== 'string') return;
        if (reportedMediaUrls[url]) return;
        reportedMediaUrls[url] = true;
        if (window.phantomBridge && window.phantomBridge.postMessage) {
            try {
                window.phantomBridge.postMessage(JSON.stringify({
                    t: 'media',
                    url: url,
                    kind: kind || 'hls',
                    frameOrigin: location.origin,
                    frameUrl: location.href
                }));
            } catch(e) {}
        }
    }

    // --- Console error & window.onerror formatting (TV4p) ---
    function formatArg(arg) {
        if (arg === null) return 'null';
        if (arg === undefined) return 'undefined';
        if (typeof arg === 'string') return arg;
        if (arg instanceof Error) return arg.stack || arg.message || String(arg);
        try {
            var s = JSON.stringify(arg);
            return s.length > 500 ? s.substring(0, 500) + '...' : s;
        } catch(e) {
            return String(arg);
        }
    }

    if (window.console && window.console.error) {
        var origConsoleError = window.console.error;
        window.console.error = function() {
            try {
                var parts = [];
                for (var i = 0; i < arguments.length; i++) {
                    parts.push(formatArg(arguments[i]));
                }
                var formatted = parts.join(' ');
                if (window.phantomBridge && window.phantomBridge.postMessage) {
                    window.phantomBridge.postMessage(JSON.stringify({
                        t: 'js_err',
                        msg: formatted.substring(0, 500)
                    }));
                }
            } catch(e) {}
            return origConsoleError.apply(this, arguments);
        };
    }

    window.addEventListener('error', function(e) {
        try {
            var msg = (e.error && (e.error.stack || e.error.message)) || e.message || 'Script error';
            if (window.phantomBridge && window.phantomBridge.postMessage) {
                window.phantomBridge.postMessage(JSON.stringify({
                    t: 'js_err',
                    msg: String(msg).substring(0, 500)
                }));
            }
        } catch(err) {}
    });

    // --- Content-based media detection (fetch & XHR) ---
    if (window.fetch) {
        var origFetch = window.fetch;
        window.fetch = function(input, init) {
            var p = origFetch.apply(this, arguments);
            try {
                p.then(function(res) {
                    try {
                        if (res && res.status >= 200 && res.status < 300) {
                            var cl = res.headers ? res.headers.get('content-length') : null;
                            var clNum = cl ? parseInt(cl, 10) : 0;
                            if (!cl || isNaN(clNum) || clNum < 2097152) {
                                var targetUrl = res.url || (typeof input === 'string' ? input : (input && input.url ? input.url : ''));
                                if (targetUrl && (targetUrl.indexOf('http://') === 0 || targetUrl.indexOf('https://') === 0)) {
                                    res.clone().text().then(function(text) {
                                        if (text && text.indexOf('#EXTM3U') === 0) {
                                            reportMedia(targetUrl, text.indexOf('#EXT-X-STREAM-INF') !== -1 ? 'hls-master' : 'hls');
                                        }
                                    }).catch(function() {});
                                }
                            }
                        }
                    } catch(e) {}
                }).catch(function() {});
            } catch(e) {}
            return p;
        };
    }

    if (window.XMLHttpRequest) {
        var origOpen = XMLHttpRequest.prototype.open;
        var origSend = XMLHttpRequest.prototype.send;
        XMLHttpRequest.prototype.open = function(method, url) {
            try {
                this.__phantomUrl = new URL(url, location.href).href;
            } catch(e) {
                this.__phantomUrl = url;
            }
            return origOpen.apply(this, arguments);
        };
        XMLHttpRequest.prototype.send = function() {
            var xhr = this;
            xhr.addEventListener('load', function() {
                try {
                    if (xhr.status >= 200 && xhr.status < 300) {
                        var rType = xhr.responseType;
                        if (!rType || rType === 'text') {
                            var text = xhr.responseText;
                            if (text && text.indexOf('#EXTM3U') === 0) {
                                var targetUrl = xhr.responseURL || xhr.__phantomUrl;
                                if (targetUrl && (targetUrl.indexOf('http://') === 0 || targetUrl.indexOf('https://') === 0)) {
                                    reportMedia(targetUrl, text.indexOf('#EXT-X-STREAM-INF') !== -1 ? 'hls-master' : 'hls');
                                }
                            }
                        }
                    }
                } catch(e) {}
            });
            return origSend.apply(this, arguments);
        };
    }

    // --- Video tag listeners ---
    document.addEventListener('loadedmetadata', function(e) {
        try {
            var el = e.target;
            if (el && el.tagName && el.tagName.toLowerCase() === 'video') {
                var src = el.currentSrc || el.src;
                if (src && src.indexOf('blob:') !== 0 && src.indexOf('data:') !== 0) {
                    var kind = 'mp4';
                    var clean = src.split('?')[0].split('#')[0].toLowerCase();
                    if (clean.endsWith('.m3u8')) kind = 'hls';
                    else if (clean.endsWith('.mpd')) kind = 'dash';
                    reportMedia(src, kind);
                }
            }
        } catch(e) {}
    }, true);

    document.addEventListener('timeupdate', function(e) {
        try {
            var el = e.target;
            if (el && el.tagName && el.tagName.toLowerCase() === 'video') {
                var now = Date.now();
                if (!el.__phantomLastVpos || (now - el.__phantomLastVpos >= 10000)) {
                    el.__phantomLastVpos = now;
                    var src = el.currentSrc || el.src;
                    if (src && window.phantomBridge && window.phantomBridge.postMessage) {
                        window.phantomBridge.postMessage(JSON.stringify({
                            t: 'vpos',
                            src: src,
                            pos: Math.floor((el.currentTime || 0) * 1000),
                            dur: Math.floor((el.duration || 0) * 1000)
                        }));
                    }
                }
            }
        } catch(e) {}
    }, true);

    // --- Media play/pause state for screen-on ---
    function checkMediaPlaying() {
        var mediaEls = document.querySelectorAll('video, audio');
        for (var i = 0; i < mediaEls.length; i++) {
            var m = mediaEls[i];
            if (!m.paused && !m.ended && m.readyState > 2) {
                return true;
            }
        }
        return false;
    }

    function sendMediaState(playing) {
        if (window.phantomBridge && window.phantomBridge.postMessage) {
            try {
                window.phantomBridge.postMessage(JSON.stringify({ t: 'media_state', playing: playing }));
            } catch(e) {}
        }
    }

    document.addEventListener('play', function(e) {
        var tag = e.target && e.target.tagName ? e.target.tagName.toLowerCase() : '';
        if (tag === 'video' || tag === 'audio') {
            sendMediaState(true);
        }
    }, true);

    document.addEventListener('pause', function(e) {
        var tag = e.target && e.target.tagName ? e.target.tagName.toLowerCase() : '';
        if (tag === 'video' || tag === 'audio') {
            sendMediaState(checkMediaPlaying());
        }
    }, true);

    document.addEventListener('ended', function(e) {
        var tag = e.target && e.target.tagName ? e.target.tagName.toLowerCase() : '';
        if (tag === 'video' || tag === 'audio') {
            sendMediaState(checkMediaPlaying());
        }
    }, true);

    // --- Keyboard & focus handling (TOP frame only) ---
    if (isTop) {
        function isEditable(el) {
            if (!el) return false;
            if (el.isContentEditable) return true;
            var tag = el.tagName ? el.tagName.toLowerCase() : '';
            if (tag === 'textarea') return true;
            if (tag === 'input') {
                var type = (el.type || 'text').toLowerCase();
                var nonText = ['button', 'checkbox', 'color', 'file', 'hidden', 'image', 'radio', 'range', 'reset', 'submit'];
                return nonText.indexOf(type) === -1;
            }
            return false;
        }

        function sendFocus(on, el) {
            var info = { on: on };
            if (on && el) {
                var val = el.isContentEditable ? (el.innerText || '') : (el.value || '');
                var inputType = el.isContentEditable ? 'text' : ((el.type || 'text').toLowerCase());
                var enterHint = el.getAttribute('enterkeyhint');
                var enter = enterHint ? enterHint.toLowerCase() : (inputType === 'search' ? 'search' : 'go');
                info.value = val;
                info.input = inputType;
                info.enter = enter;
            }
            if (window.phantomBridge && window.phantomBridge.postMessage) {
                try {
                    window.phantomBridge.postMessage(JSON.stringify(info));
                } catch(e) {}
            }
        }

        document.addEventListener('focusin', function(e) {
            if (isEditable(e.target)) {
                sendFocus(true, e.target);
            }
        }, true);

        document.addEventListener('focusout', function(e) {
            if (isEditable(e.target)) {
                sendFocus(false, e.target);
            }
        }, true);
    }
})();
"""

class BrowserActivity : Activity() {

    private lateinit var rootLayout: FrameLayout
    private var customView: android.view.View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private var lastInputTime: Long = SystemClock.uptimeMillis()
    private var isMediaPlaying: Boolean = false
    private var isClientConnected: Boolean = false
    private var lastScreenOn: Boolean? = null
    private var lastScreenReason: String? = null
    private val screenHandler = Handler(Looper.getMainLooper())
    private val screenCheckRunnable = object : Runnable {
        override fun run() {
            updateKeepScreenOn()
            screenHandler.postDelayed(this, 60_000)
        }
    }

    private fun updateKeepScreenOn() {
        val now = SystemClock.uptimeMillis()
        val isIdle = (now - lastInputTime) >= 30 * 60 * 1000L

        val keepOn: Boolean
        val reason: String

        if (isMediaPlaying) {
            keepOn = true
            reason = "media"
        } else if (isClientConnected && !isIdle) {
            keepOn = true
            reason = "client"
        } else if (isClientConnected && isIdle) {
            keepOn = false
            reason = "idle"
        } else {
            keepOn = false
            reason = "client"
        }

        if (lastScreenOn != keepOn || lastScreenReason != reason) {
            lastScreenOn = keepOn
            lastScreenReason = reason
            runOnUiThread {
                if (keepOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                Log.i("PHANTOM", "SCREEN_ON ${if (keepOn) "on" else "off"} reason=$reason")
            }
        }
    }

    private fun hideCustomView() {
        val view = customView ?: return
        rootLayout.removeView(view)
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        updateQrVisibility()
        Hub.sendText(buildFullscreen(false))
        Log.i("PHANTOM", "fullscreen off")
        onCursorActivity()
        showCursor()
        inputController?.targetView = if (popupGate?.isPopupVisible == true) {
            popupGate?.activePopup?.childWebView ?: webView ?: rootLayout
        } else {
            webView ?: rootLayout
        }
    }

    private fun handleBack(fromRemote: Boolean) {
        val action = backAction(
            playerOpen = Hub.activePlayerListener != null,
            fullscreen = customView != null,
            popupVisible = popupGate?.isPopupVisible == true,
            popupCanBack = popupGate?.canPopupGoBack() == true,
            mainCanBack = webView?.canGoBack() == true,
            fromRemote = fromRemote
        )
        Log.i("PHANTOM", "BACK_ACTION action=$action fromRemote=$fromRemote")
        when (action) {
            BackAction.CLOSE_PLAYER -> Hub.activePlayerListener?.onClosePlayer()
            BackAction.CLOSE_FULLSCREEN -> hideCustomView()
            BackAction.POPUP_GO_BACK -> popupGate?.popupGoBack()
            BackAction.CLOSE_POPUP -> popupGate?.closeActivePopup()
            BackAction.MAIN_GO_BACK -> webView?.goBack()
            BackAction.MOVE_TASK_TO_BACK -> moveTaskToBack(true)
            BackAction.NONE -> { /* do nothing */ }
        }
    }

    private lateinit var webContainer: FrameLayout
    private var webView: WebView? = null
    private lateinit var clickRingView: com.phantom.input.ClickRingView
    private lateinit var cursorView: CursorView
    private var cursorState: CursorState? = null
    @Volatile private var lastCursorActivityTime = SystemClock.uptimeMillis()
    @Volatile private var lastClickTime: Long = 0L

    // True while a window of ours covers the browser (a page's <select> list, an alert):
    // phone input is turned into keys for it, since the cursor cannot reach it.
    @Volatile private var popupOpen = false
    private val popupKeys by lazy { com.phantom.input.PopupKeyBridge((48 * resources.displayMetrics.density).toInt()) }

    /**
     * Cookies (site sign-ins), site storage, cache, form data, the back list and the event log.
     * Pairing, ad-block settings and resume positions are kept.
     */
    private fun clearBrowsingData() {
        val cookies = android.webkit.CookieManager.getInstance()
        cookies.removeAllCookies {
            cookies.flush()
            Hub.sendText(com.phantom.server.buildCleared())
        }
        android.webkit.WebStorage.getInstance().deleteAllData()
        android.webkit.WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword()
        webView?.clearCache(true)
        webView?.clearFormData()
        webView?.clearHistory()
        com.phantom.log.EventLogStore.clear()
        webView?.reload()
        Log.i("PHANTOM", "DATA_CLEARED")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        popupOpen = !hasFocus
        popupKeys.reset()
        if (::cursorView.isInitialized) {
            cursorView.visibility = if (hasFocus) android.view.View.VISIBLE else android.view.View.INVISIBLE
        }
    }
    @Volatile private var isCursorHidden = false
    @Volatile private var isNativePlayerOpen = false

    private fun onCursorActivity() {
        lastCursorActivityTime = SystemClock.uptimeMillis()
        wakeFrameLoop()
        if (isCursorHidden) {
            runOnUiThread { showCursor() }
        }
    }

    private fun showCursor() {
        if (!isCursorHidden) return
        isCursorHidden = false
        Log.i("PHANTOM", "CURSOR show")
        cursorView.animate().cancel()
        cursorView.animate().alpha(1f).setDuration(200L).start()
        clickRingView.visibility = android.view.View.VISIBLE
    }

    private fun hideCursor() {
        if (isCursorHidden) return
        isCursorHidden = true
        Log.i("PHANTOM", "CURSOR hide")
        cursorView.animate().cancel()
        cursorView.animate().alpha(0f).setDuration(200L).start()
        clickRingView.animate().cancel()
        clickRingView.alpha = 0f
        clickRingView.visibility = android.view.View.INVISIBLE
    }
    private val remoteMotionController = RemoteMotionController { dx, dy ->
        val ic = inputController
        if (ic != null) {
            // Holding a direction at the screen edge scrolls the page instead of stopping.
            val cs = ic.cursorState
            val y = cs.getY()
            val over = when {
                dy > 0 && y + dy >= cs.height - EDGE_PX -> dy
                dy < 0 && y + dy <= EDGE_PX -> dy
                else -> 0
            }
            if (over != 0) ic.onScroll(0, (over * EDGE_SCROLL_FACTOR).toInt().toShort())
            ic.onMove(dx.toShort(), dy.toShort())
        }
    }

    private var lastUrl: String = "https://www.google.com"
    private var pendingScrollY: Int? = null
    private var inputController: InputController? = null
    private val crashTimestamps = mutableListOf<Long>()

    private lateinit var blocklist: com.phantom.web.AdBlocker
    private var blockedCount: Int = 0
    private var totalReqCount: Int = 0
    private var pageStartTime: Long = 0L
    private var lastBlockedSendTime: Long = 0L
    private var adblockCss: String = ""
    private var popupGate: com.phantom.web.PopupGate? = null
    @Volatile private var cachedPopupOpen: Boolean = false

    private lateinit var qrCardLayout: LinearLayout
    private lateinit var qrImageView: ImageView
    private lateinit var qrUrlTextView: TextView
    private lateinit var disconnectToastView: TextView
    private lateinit var mediaToastView: TextView
    private var mediaToastPageUrl: String? = null
    // Build id of the bundled phone UI (written by the PWA build); sent in hello so phones reload.
    private val pwaBuildId: String? by lazy {
        try { assets.open("pwa/build.txt").bufferedReader().use { it.readText().trim() } } catch (_: Exception) { null }
    }
    private val hideMediaToast = Runnable { mediaToastView.visibility = android.view.View.GONE }

    private val qrHandler = Handler(Looper.getMainLooper())
    private val okHandler = Handler(Looper.getMainLooper())

    // --- First-run intro/tutorial (see docs/plan_tutorial.md) ---
    private var onboardingView: com.phantom.onboarding.OnboardingView? = null
    private var onboardingDeferredLoad = false

    private val onboardingHost = object : com.phantom.onboarding.OnboardingView.Host {
        override fun pairInfo(): Pair<String, String> = buildPairInfo()
        override fun onOnboardingFinished(state: com.phantom.onboarding.OnboardingState) {
            val ov = onboardingView ?: return
            onboardingView = null
            ov.release()
            rootLayout.removeView(ov)
            webContainer.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            getSharedPreferences(ONBOARDING_PREFS, MODE_PRIVATE).edit().putInt(ONBOARDING_KEY, ONBOARDING_VERSION).apply()
            val kind = if (state.finishReason == com.phantom.onboarding.FinishReason.COMPLETED) "done" else "skip"
            com.phantom.log.EventLogStore.log("PHANTOM", "ONBOARDING $kind step=${state.finishedAtStep}")
            if (onboardingDeferredLoad) {
                onboardingDeferredLoad = false
                webView?.loadUrl(lastUrl) // start page was held back while the guide was visible
            }
            updateQrVisibility()
        }
    }

    /** Shows the overlay above the page but below the cursor and click ring. */
    private fun startOnboarding(replay: Boolean) {
        if (onboardingView != null) return
        val state = if (replay) com.phantom.onboarding.OnboardingState.replay(isClientConnected)
        else com.phantom.onboarding.OnboardingState(phoneConnected = isClientConnected)
        val ov = com.phantom.onboarding.OnboardingView(this, state, onboardingHost)
        onboardingView = ov
        if (isClientConnected) Hub.sendText(com.phantom.server.buildTips())
        webContainer.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        mediaToastView.visibility = android.view.View.GONE
        disconnectToastView.visibility = android.view.View.GONE
        val ringIndex = if (::clickRingView.isInitialized) rootLayout.indexOfChild(clickRingView) else -1
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        if (ringIndex >= 0) rootLayout.addView(ov, ringIndex, lp) else rootLayout.addView(ov, lp)
        updateQrVisibility()
    }

    /** Click detection hook for the CURSOR step: OK press or phone tap release. */
    private fun onboardingCursorClick() {
        val ov = onboardingView ?: return
        val cs = cursorState ?: return
        ov.onCursorClick(cs.getX(), cs.getY())
    }

    private var launchTimeMs: Long = 0L
    private var manualQrOpen: Boolean = false
    private var okLongPressTriggered: Boolean = false

    private val okLongPressRunnable = Runnable {
        okLongPressTriggered = true
        showTvMenu()
    }
    private val manualQrTimeoutRunnable = Runnable {
        manualQrOpen = false
        updateQrVisibility()
    }
    private val startupQrTimeoutRunnable = Runnable {
        updateQrVisibility()
    }
    private val hideDisconnectToastRunnable = Runnable {
        disconnectToastView.visibility = android.view.View.GONE
    }
    private val rejectTimestamps = mutableListOf<Long>()
    private var rejectBurstUntil: Long = 0L
    private val rejectBurstTimeoutRunnable = Runnable {
        updateQrVisibility()
    }
    private val wsRejectListener: () -> Unit = {
        runOnUiThread {
            val now = SystemClock.uptimeMillis()
            rejectTimestamps.removeAll { now - it > 60_000L }
            rejectTimestamps.add(now)
            if (rejectTimestamps.size >= 3) {
                rejectBurstUntil = now + 60_000L
                updateQrVisibility()
                qrHandler.removeCallbacks(rejectBurstTimeoutRunnable)
                qrHandler.postDelayed(rejectBurstTimeoutRunnable, 60_000L)
            }
        }
    }

    private fun updateQrVisibility() {
        runOnUiThread {
            val sinceLaunch = SystemClock.uptimeMillis() - launchTimeMs
            val isFullscreen = customView != null
            val paired = TokenManager.isPaired(this)
            val rejectBurst = (rejectBurstUntil > SystemClock.uptimeMillis())
            val shouldShow = qrVisible(
                paired = paired,
                sinceLaunchMs = sinceLaunch,
                clientConnected = isClientConnected,
                manual = manualQrOpen,
                fullscreen = isFullscreen,
                mediaPlaying = isMediaPlaying,
                rejectBurst = rejectBurst
            )
            if (shouldShow && onboardingView == null) { // QR card hidden while the guide is visible
                updateQrCard()
                qrCardLayout.visibility = android.view.View.VISIBLE
            } else {
                qrCardLayout.visibility = android.view.View.GONE
            }
        }
    }

    private fun toggleManualQr() {
        manualQrOpen = !manualQrOpen
        qrHandler.removeCallbacks(manualQrTimeoutRunnable)
        if (manualQrOpen) {
            qrHandler.postDelayed(manualQrTimeoutRunnable, 60_000L)
        }
        updateQrVisibility()
    }

    private val clientListener: (Boolean) -> Unit = { connected ->
        runOnUiThread {
            isClientConnected = connected
            if (connected) {
                TokenManager.setPaired(this@BrowserActivity, true)
                manualQrOpen = false
                qrHandler.removeCallbacks(manualQrTimeoutRunnable)
                rejectBurstUntil = 0L
                qrHandler.removeCallbacks(rejectBurstTimeoutRunnable)
                disconnectToastView.visibility = android.view.View.GONE
                onboardingView?.onPhoneConnected()
                // A phone that joins during the TV tour gets its own short tips right away.
                if (onboardingView != null) Hub.sendText(com.phantom.server.buildTips())
            } else {
                onboardingView?.onPhoneDisconnected()
                val isFullscreen = customView != null
                if (!isFullscreen && !isMediaPlaying && onboardingView == null) {
                    disconnectToastView.visibility = android.view.View.VISIBLE
                    qrHandler.removeCallbacks(hideDisconnectToastRunnable)
                    qrHandler.postDelayed(hideDisconnectToastRunnable, 3000L)
                }
            }
            updateQrVisibility()
        }
    }
    private val tokenListener: () -> Unit = {
        runOnUiThread {
            updateQrVisibility()
        }
    }

    private var displayDensity: Float = 2.0f
    private val mediaDetector = MediaDetector { record ->
        val subJsonList = record.subtitles.map { sub ->
            JSONObject().apply {
                put("url", sub.url)
                put("lang", sub.lang)
            }
        }
        lastDetectedMediaUrl = record.url
        Hub.sendText(buildMedia(record.url, record.kind, subJsonList))
        // Once per page URL: sites may switch films without a main-frame load (onPageStarted),
        // so a boolean reset there missed the second film.
        runOnUiThread {
            val page = webView?.url ?: cachedUrl
            if (page != mediaToastPageUrl && !isNativePlayerOpen && onboardingView == null) {
                mediaToastPageUrl = page
                mediaToastView.visibility = android.view.View.VISIBLE
                mediaToastView.removeCallbacks(hideMediaToast)
                mediaToastView.postDelayed(hideMediaToast, 10_000L)
            }
        }
        val mediaMsg = "MEDIA url=${record.url} kind=${record.kind} subs=${record.subtitles.size}"
        Log.i("PHANTOM", mediaMsg)
        com.phantom.log.EventLogStore.log("PHANTOM", mediaMsg)
    }
    private var lastDetectedMediaUrl: String? = null
    private var cursorMoved: Boolean = false
    private var lastHoverCheckTime: Long = 0L
    private var lastHoverEditable: Boolean = false

    @Volatile private var cachedUrl: String = "https://www.google.com"
    @Volatile private var cachedTitle: String = ""
    @Volatile private var cachedCanBack: Boolean = false
    @Volatile private var cachedCanFwd: Boolean = false
    @Volatile private var cachedLoading: Boolean = false
    @Volatile private var cachedProgress: Int = 0
    private var lastProgressSendTime: Long = 0L

    private lateinit var sitePrefs: SitePrefs
    private var defaultUserAgent: String = ""
    private var desktopUserAgent: String = ""
    @Volatile private var cachedUa: String = "mobile"
    @Volatile private var cachedZoom: Int = 100

    private fun applyUaForHost(host: String): Boolean {
        if (host.isEmpty()) return false
        val pref = sitePrefs.getPref(host)
        val targetUa = pref.ua
        cachedUa = targetUa
        val targetUaString = if (targetUa == "desktop") desktopUserAgent else defaultUserAgent
        val wv = webView ?: return false
        if (wv.settings.userAgentString != targetUaString) {
            wv.settings.userAgentString = targetUaString
            Log.i("PHANTOM", "UA $targetUa")
            return true
        }
        return false
    }

    private fun getVolState(): JSONObject {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return JSONObject().apply {
            put("level", am.getStreamVolume(AudioManager.STREAM_MUSIC))
            put("max", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            put("muted", am.isStreamMute(AudioManager.STREAM_MUSIC))
        }
    }

    private fun updateAndSendState() {
        val wv = webView ?: return
        cachedUrl = wv.url ?: lastUrl
        cachedTitle = wv.title ?: cachedTitle
        cachedCanBack = wv.canGoBack()
        cachedCanFwd = wv.canGoForward()
        Log.i("PHANTOM", "STATE_OUT $cachedUrl")
        val host = Uri.parse(cachedUrl).host ?: ""
        val pref = if (host.isNotEmpty()) sitePrefs.getPref(host) else com.phantom.web.SitePref()
        val msg = buildState(
            cachedUrl,
            cachedTitle,
            cachedCanBack,
            cachedCanFwd,
            cachedLoading,
            cachedProgress,
            ua = cachedUa,
            zoom = cachedZoom,
            popupOpen = cachedPopupOpen,
            adblock = pref.adblock,
            blocked = blockedCount,
            vol = getVolState()
        )
        Hub.sendText(msg)
    }

    private fun checkHoverEditable(cssX: Float, cssY: Float) {
        val script = """
            (function() {
                var el = document.elementFromPoint($cssX, $cssY);
                if (!el) return false;
                if (el.isContentEditable) return true;
                var tag = el.tagName ? el.tagName.toLowerCase() : '';
                if (tag === 'textarea') return true;
                if (tag === 'input') {
                    var type = (el.type || 'text').toLowerCase();
                    var nonText = ['button', 'checkbox', 'color', 'file', 'hidden', 'image', 'radio', 'range', 'reset', 'submit'];
                    return nonText.indexOf(type) === -1;
                }
                return false;
            })()
        """.trimIndent()
        webView?.evaluateJavascript(script) { result ->
            val isEditable = result == "true"
            if (isEditable != lastHoverEditable) {
                lastHoverEditable = isEditable
                Hub.sendText(buildEditable(isEditable, "hover"))
            }
        }
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            remoteMotionController.onFrame(frameTimeNanos)
            val state = cursorState
            if (state != null) {
                val x = state.getX().toFloat()
                val y = state.getY().toFloat()
                val ringOffset = 20f * displayDensity
                clickRingView.translationX = x - ringOffset
                clickRingView.translationY = y - ringOffset
                if (cursorView.translationX != x || cursorView.translationY != y) {
                    cursorView.translationX = x
                    cursorView.translationY = y
                    cursorMoved = true
                    onCursorActivity()
                }
                val now = SystemClock.uptimeMillis()
                if (cursorMoved && (now - lastHoverCheckTime >= 100)) {
                    cursorMoved = false
                    lastHoverCheckTime = now
                    val cssX = x / displayDensity
                    val cssY = y / displayDensity
                    checkHoverEditable(cssX, cssY)
                }
                val idleMs = now - lastCursorActivityTime
                val shouldBeVisible = com.phantom.input.cursorVisible(
                    fullscreen = (customView != null),
                    playerOpen = isNativePlayerOpen,
                    idleMs = idleMs
                )
                if (shouldBeVisible && isCursorHidden) {
                    showCursor()
                } else if (!shouldBeVisible && !isCursorHidden) {
                    hideCursor()
                }
            }
            inputController?.onFrame()
            // Sleep when nothing moves: a frame callback every 16 ms keeps the main thread busy
            // for nothing. Any input wakes it again (onCursorActivity -> wakeFrameLoop).
            val busy = remoteMotionController.pressedDirections.isNotEmpty() ||
                SystemClock.uptimeMillis() - lastCursorActivityTime < FRAME_LOOP_IDLE_MS
            if (busy && frameLoopResumed) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                frameLoopRunning = false
            }
        }
    }

    private var frameLoopRunning = false

    // --- Light look (see LiteMode) ---
    private var liteMode = com.phantom.web.LiteMode.Mode.AUTO
    @Volatile private var liteAppliedForPage = false
    @Volatile private var jankResetPending = true
    private var isActivityStarted = false
    private var pendingRendererReload: String? = null
    private val weakDevice by lazy { com.phantom.web.LiteMode.isWeakDevice(this) }

    private fun applyLite(reason: String) {
        liteAppliedForPage = true
        webView?.evaluateJavascript(com.phantom.web.LiteMode.SCRIPT) { n ->
            val msg = "LITE reason=$reason blurred=$n url=$cachedUrl"
            Log.i("PHANTOM", msg)
            com.phantom.log.EventLogStore.log("PHANTOM", msg)
        }
    }

    private fun setLiteMode(mode: com.phantom.web.LiteMode.Mode) {
        val wasApplied = liteAppliedForPage
        liteMode = mode
        com.phantom.web.LiteMode.save(this, mode)
        Log.i("PHANTOM", "LITE_MODE ${mode.wire}")
        when {
            mode == com.phantom.web.LiteMode.Mode.ON -> applyLite("on")
            wasApplied -> webView?.reload() // undo the page changes
        }
    }

    /** AUTO mode: on a weak TV, un-blur the current page once it is seen to stutter. */
    private fun startJankWatch() {
        if (!weakDevice) return
        val thread = android.os.HandlerThread("PhantomJank").apply { start() }
        val meter = com.phantom.web.JankMeter()
        window.addOnFrameMetricsAvailableListener({ _, metrics, _ ->
            if (jankResetPending) {
                jankResetPending = false
                meter.reset()
            }
            if (liteMode != com.phantom.web.LiteMode.Mode.AUTO || liteAppliedForPage ||
                isNativePlayerOpen || customView != null) return@addOnFrameMetricsAvailableListener
            val frameMs = metrics.getMetric(android.view.FrameMetrics.TOTAL_DURATION) / 1_000_000.0
            if (meter.record(frameMs, SystemClock.uptimeMillis())) {
                liteAppliedForPage = true
                runOnUiThread { applyLite("auto") }
            }
        }, android.os.Handler(thread.looper))
    }
    private var frameLoopResumed = false

    private fun wakeFrameLoop() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            runOnUiThread { wakeFrameLoop() }
            return
        }
        if (frameLoopResumed && !frameLoopRunning) {
            frameLoopRunning = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        rootLayout = root
        webContainer = FrameLayout(this)
        root.addView(webContainer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val density = resources.displayMetrics.density
        displayDensity = density
        val initWidth = resources.displayMetrics.widthPixels.let { if (it > 0) it else 1920 }
        val initHeight = resources.displayMetrics.heightPixels.let { if (it > 0) it else 1080 }

        blocklist = com.phantom.web.AdBlocker()
        Hub.adBlocker = blocklist
        try {
            val localBin = java.io.File(filesDir, "adblock.bin")
            if (localBin.exists() && localBin.length() > 8) {
                blocklist.loadFromStreamAsync(java.io.FileInputStream(localBin))
            } else {
                blocklist.loadFromStreamAsync(assets.open("adblock.bin"))
            }
        } catch (e: Exception) {
            Log.e("PHANTOM", "Failed to load adblock.bin", e)
        }
        try {
            blocklist.loadExtraFromStream(assets.open("adblock_extra.txt"))
        } catch (e: Exception) {
            Log.e("PHANTOM", "Failed to load adblock_extra.txt", e)
        }
        adblockCss = try {
            assets.open("adblock.css").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }

        cursorState = CursorState(initWidth, initHeight)

        val cardMargin = (24 * density).toInt()
        val cardPadding = (16 * density).toInt()
        val qrSize = (200 * density).toInt()

        qrCardLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(cardPadding, cardPadding, cardPadding, cardPadding)
            background = GradientDrawable().apply {
                setColor(0xFF1C1C1E.toInt())
                cornerRadius = 16 * density
                setStroke((1 * density).toInt(), 0xFF333336.toInt())
            }
            elevation = 20 * density
        }

        qrImageView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(qrSize, qrSize)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        qrUrlTextView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (8 * density).toInt()
            }
            setTextColor(0xFFFFD60A.toInt())
            textSize = 12f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }

        val qrCaptionView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (4 * density).toInt()
            }
            setText(R.string.qr_scan_caption)
            setTextColor(0xFF8E8E93.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
        }

        qrCardLayout.addView(qrImageView)
        qrCardLayout.addView(qrUrlTextView)
        qrCardLayout.addView(qrCaptionView)

        val cardParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(0, 0, cardMargin, cardMargin)
        }
        root.addView(qrCardLayout, cardParams)

        Hub.clientListeners.add(clientListener)
        Hub.tokenListeners.add(tokenListener)
        Hub.wsRejectListeners.add(wsRejectListener)

        disconnectToastView = TextView(this).apply {
            setText(R.string.toast_phone_disconnected)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            val p = (10 * density).toInt()
            setPadding((14 * density).toInt(), p, (14 * density).toInt(), p)
            background = GradientDrawable().apply {
                setColor(0xCC1C1C1E.toInt())
                cornerRadius = 10 * density
                setStroke((1 * density).toInt(), 0x66FFFFFF.toInt())
            }
            visibility = android.view.View.GONE
        }
        val toastParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            setMargins(0, (24 * density).toInt(), (24 * density).toInt(), 0)
        }
        root.addView(disconnectToastView, toastParams)

        // Remote-only users never see the phone's "open in player" banner, so the TV shows its own.
        mediaToastView = TextView(this).apply {
            setText(R.string.toast_media_found)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            val p = (14 * density).toInt()
            setPadding((22 * density).toInt(), p, (22 * density).toInt(), p)
            background = GradientDrawable().apply {
                setColor(0xF00A84FF.toInt())
                cornerRadius = 14 * density
            }
            elevation = 8 * density
            visibility = android.view.View.GONE
        }
        root.addView(mediaToastView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            setMargins(0, 0, 0, (40 * density).toInt())
        })

        launchTimeMs = SystemClock.uptimeMillis()
        qrHandler.postDelayed(startupQrTimeoutRunnable, 20_000L)
        updateQrVisibility()

        // First launch (or older guide version): show the guide and hold the start page back.
        onboardingDeferredLoad = getSharedPreferences(ONBOARDING_PREFS, MODE_PRIVATE)
            .getInt(ONBOARDING_KEY, 0) < ONBOARDING_VERSION
        if (onboardingDeferredLoad) startOnboarding(replay = false)

        val ringSizeDp = (40 * density).toInt()
        clickRingView = com.phantom.input.ClickRingView(this)
        val ringOffset = 20f * density
        clickRingView.translationX = (initWidth / 2f) - ringOffset
        clickRingView.translationY = (initHeight / 2f) - ringOffset
        root.addView(clickRingView, FrameLayout.LayoutParams(ringSizeDp, ringSizeDp))

        cursorView = CursorView(this)
        cursorView.translationX = initWidth / 2f
        cursorView.translationY = initHeight / 2f
        val cursorSizeDp = (24 * density).toInt()
        root.addView(cursorView, FrameLayout.LayoutParams(cursorSizeDp, cursorSizeDp))

        setContentView(root)

        sitePrefs = SitePrefs(this)
        liteMode = com.phantom.web.LiteMode.load(this)
        startJankWatch()
        createAndConfigureWebView(lastUrl, deferLoad = onboardingDeferredLoad)

        Hub.sink = object : Hub.Sink {
            override fun onBinary(m: BinIn) {
                lastInputTime = SystemClock.uptimeMillis()
                updateKeepScreenOn()
                onCursorActivity()
                if (Hub.activePlayerListener != null) {
                    when (m) {
                        is BinIn.Button -> Hub.activePlayerListener?.onButton(m.down, m.btn)
                        is BinIn.Scroll -> Hub.activePlayerListener?.onScroll(m.sx, m.sy)
                        else -> {}
                    }
                    return
                }
                if (popupOpen) {
                    when (m) {
                        is BinIn.Move -> popupKeys.move(m.dy.toInt())
                        is BinIn.Scroll -> popupKeys.move(m.sy.toInt())
                        is BinIn.Button -> if (m.btn == 0 && !m.down) popupKeys.key(KeyEvent.KEYCODE_DPAD_CENTER)
                    }
                    return
                }
                when (m) {
                    is BinIn.Move -> inputController?.onMove(m.dx, m.dy)
                    is BinIn.Button -> {
                        Log.i("PHANTOM", "HUB_BUTTON down=${m.down} btn=${m.btn}")
                        if (m.btn == 0 && m.down) {
                            lastClickTime = SystemClock.uptimeMillis()
                        }
                        if (m.btn == 0) {
                            runOnUiThread {
                                if (m.down) clickRingView.onDown() else clickRingView.onUp()
                            }
                        }
                        if (m.btn == 0 && !m.down && onboardingView != null) runOnUiThread { onboardingCursorClick() }
                        inputController?.onButton(m.down, m.btn)
                    }
                    is BinIn.Scroll -> inputController?.onScroll(m.sx, m.sy)
                }
            }

            override fun onJson(m: JsonIn) {
                if (m !is JsonIn.Ping) {
                    lastInputTime = SystemClock.uptimeMillis()
                    updateKeepScreenOn()
                }
                when (m) {
                    is JsonIn.Nav -> {
                        val normalized = normalizeUrl(m.url, BuildConfig.DEBUG)
                        if (normalized == null) {
                            Log.w("PHANTOM", "NAV_ERR bad_url: ${m.url}")
                            Hub.sendText(buildErr("bad_url", "Invalid URL"))
                            return
                        }
                        val host = Uri.parse(normalized).host ?: ""
                        runOnUiThread {
                            applyUaForHost(host)
                            webView?.loadUrl(normalized)
                        }
                        Log.i("PHANTOM", "NAV $normalized")
                    }
                    is JsonIn.Back -> {
                        if (popupOpen) {
                            popupKeys.key(KeyEvent.KEYCODE_BACK)
                            return
                        }
                        runOnUiThread {
                            handleBack(fromRemote = false)
                        }
                    }
                    is JsonIn.Fwd -> {
                        runOnUiThread {
                            if (webView?.canGoForward() == true) {
                                webView?.goForward()
                            }
                        }
                    }
                    is JsonIn.Reload -> {
                        runOnUiThread {
                            webView?.reload()
                        }
                    }
                    is JsonIn.ClearData -> {
                        runOnUiThread { clearBrowsingData() }
                    }
                    is JsonIn.Stop -> {
                        runOnUiThread {
                            webView?.stopLoading()
                        }
                    }
                    is JsonIn.Ua -> {
                        val host = Uri.parse(cachedUrl).host ?: ""
                        if (host.isNotEmpty()) {
                            sitePrefs.setUa(host, m.mode)
                            runOnUiThread {
                                val wv = webView ?: return@runOnUiThread
                                val targetUaString = if (m.mode == "desktop") desktopUserAgent else defaultUserAgent
                                if (wv.settings.userAgentString != targetUaString) {
                                    wv.settings.userAgentString = targetUaString
                                    cachedUa = m.mode
                                    Log.i("PHANTOM", "UA ${m.mode}")
                                    wv.reload()
                                }
                                updateAndSendState()
                            }
                        }
                    }
                    is JsonIn.Zoom -> {
                        val host = Uri.parse(cachedUrl).host ?: ""
                        if (host.isNotEmpty()) {
                            val updated = sitePrefs.setZoom(host, m.text)
                            cachedZoom = updated.zoom
                            runOnUiThread {
                                webView?.settings?.textZoom = updated.zoom
                                updateAndSendState()
                            }
                        }
                    }
                    is JsonIn.Lite -> {
                        val mode = com.phantom.web.LiteMode.fromWire(m.mode) ?: return
                        runOnUiThread { setLiteMode(mode) }
                    }
                    is JsonIn.Adblock -> {
                        val host = Uri.parse(cachedUrl).host ?: ""
                        if (host.isNotEmpty()) {
                            sitePrefs.setAdblock(host, m.on)
                            runOnUiThread {
                                webView?.reload()
                                updateAndSendState()
                            }
                        }
                    }
                    is JsonIn.Text -> {
                        val script = buildTextScript(m.value)
                        runOnUiThread {
                            webView?.evaluateJavascript(script, null)
                        }
                    }
                    is JsonIn.Key -> {
                        val keyCode = when (m.k) {
                            "Enter" -> KeyEvent.KEYCODE_ENTER
                            "Tab" -> KeyEvent.KEYCODE_TAB
                            "Escape" -> KeyEvent.KEYCODE_ESCAPE
                            "Backspace" -> KeyEvent.KEYCODE_DEL
                            "ArrowUp" -> KeyEvent.KEYCODE_DPAD_UP
                            "ArrowDown" -> KeyEvent.KEYCODE_DPAD_DOWN
                            "ArrowLeft" -> KeyEvent.KEYCODE_DPAD_LEFT
                            "ArrowRight" -> KeyEvent.KEYCODE_DPAD_RIGHT
                            else -> null
                        }
                        if (keyCode != null) {
                            runOnUiThread {
                                val now = SystemClock.uptimeMillis()
                                webView?.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
                                webView?.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
                            }
                        }
                    }
                    is JsonIn.Pointer -> {
                        onCursorActivity()
                        val w = webView?.width?.takeIf { it > 0 } ?: initWidth
                        val h = webView?.height?.takeIf { it > 0 } ?: initHeight
                        inputController?.onPointer((m.x * w).toFloat(), (m.y * h).toFloat())
                    }
                    is JsonIn.Popup -> {
                        runOnUiThread {
                            popupGate?.onDecision(m.id, m.action)
                        }
                    }
                    is JsonIn.Tab -> {
                        val normalized = normalizeUrl(m.url, BuildConfig.DEBUG) ?: m.url
                        val host = Uri.parse(normalized).host ?: ""
                        runOnUiThread {
                            pendingScrollY = m.scrollY
                            applyUaForHost(host)
                            webView?.loadUrl(normalized)
                        }
                        Log.i("PHANTOM", "TAB $normalized scrollY=${m.scrollY}")
                    }
                    is JsonIn.Snap -> {
                        handleSnap(m.reqId)
                    }
                    is JsonIn.AdblockUpdate -> {
                        com.phantom.web.AdBlockUpdater.checkAndTriggerUpdate(this@BrowserActivity, blocklist, force = true)
                    }
                    is JsonIn.MediaOpen -> {
                        Log.i("PHANTOM", "MEDIA_OPEN ${m.url}")
                        runOnUiThread {
                            openNativePlayer(m.url)
                        }
                    }
                    is JsonIn.PlayerCmd -> {
                        Hub.activePlayerListener?.onPlayerCmd(m.a, m.ms, m.type, m.id)
                    }
                    is JsonIn.Vol -> {
                        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        val direction = when (m.a) {
                            "up" -> AudioManager.ADJUST_RAISE
                            "down" -> AudioManager.ADJUST_LOWER
                            "mute" -> AudioManager.ADJUST_TOGGLE_MUTE
                            else -> 0
                        }
                        if (direction != 0) {
                            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
                            Log.i("PHANTOM", "VOL adjust=${m.a} level=${am.getStreamVolume(AudioManager.STREAM_MUSIC)}")
                        }
                        runOnUiThread {
                            updateAndSendState()
                        }
                    }
                    is JsonIn.Diag -> {
                        if (m.rtt != null && BuildConfig.DEBUG) {
                            Log.i("PHANTOM", "DIAG ${m.rtt}")
                        }
                        if (m.view != null) {
                            Log.i("PHANTOM", "DIAG view: ${m.view}")
                        }
                    }
                    else -> {
                        Log.i("PHANTOM", "IGNORED ${m.javaClass.simpleName}")
                    }
                }
            }

            override fun onClientChanged(connected: Boolean) {
                isClientConnected = connected
                if (connected) {
                    lastInputTime = SystemClock.uptimeMillis()
                }
                updateKeepScreenOn()
                inputController?.onClientChanged(connected)
            }
        }

        Hub.helloProvider = {
            val w = webView?.width?.takeIf { it > 0 } ?: initWidth
            val h = webView?.height?.takeIf { it > 0 } ?: initHeight
            // tour=true while the first-run tour is on screen: the phone then shows its tips. It is
            // in hello (not only a separate message) so it survives the phone reloading its page.
            buildHello(1, w, h, density, cachedUa, pwaBuildId, liteMode.wire, tour = onboardingView != null)
        }

        Hub.stateProvider = {
            buildState(cachedUrl, cachedTitle, cachedCanBack, cachedCanFwd, cachedLoading, cachedProgress, ua = cachedUa, zoom = cachedZoom, popupOpen = cachedPopupOpen, vol = getVolState())
        }

        startService(Intent(this, CoreService::class.java))
        updateKeepScreenOn()
        screenHandler.postDelayed(screenCheckRunnable, 60_000)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createAndConfigureWebView(url: String, deferLoad: Boolean = false) {
        val wv = WebView(this)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false
        }
        defaultUserAgent = wv.settings.userAgentString
        desktopUserAgent = toDesktopUserAgent(defaultUserAgent)

        val initHost = Uri.parse(url).host ?: ""
        val initPref = sitePrefs.getPref(initHost)
        cachedUa = initPref.ua
        cachedZoom = initPref.zoom
        wv.settings.userAgentString = if (cachedUa == "desktop") desktopUserAgent else defaultUserAgent
        wv.settings.textZoom = cachedZoom
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                wv,
                "phantomBridge",
                setOf("*")
            ) { _, message, _, isMainFrame, _ ->
                val data = message.data
                if (data != null) {
                    try {
                        val json = JSONObject(data)
                        val type = json.optString("t")
                        when (type) {
                            "media_state" -> {
                                val playing = json.optBoolean("playing", false)
                                isMediaPlaying = playing
                                updateKeepScreenOn()
                                updateQrVisibility()
                            }
                            "media" -> {
                                val bridgeMedia = com.phantom.web.parseAndValidateBridgeMedia(data)
                                if (bridgeMedia != null) {
                                    mediaDetector.registerMedia(
                                        url = bridgeMedia.url,
                                        kind = bridgeMedia.kind,
                                        frameReferer = bridgeMedia.frameUrl,
                                        frameOrigin = bridgeMedia.frameOrigin,
                                        pageUrl = cachedUrl
                                    )
                                }
                            }
                            "vpos" -> {
                                val src = json.optString("src")
                                val pos = json.optLong("pos", 0L)
                                val dur = json.optLong("dur", 0L)
                                if (src.isNotEmpty()) {
                                    val pageUrl = cachedUrl.ifEmpty { wv.url ?: "" }
                                    val prefs = getSharedPreferences(com.phantom.player.ResumeStore.PREFS_NAME, Context.MODE_PRIVATE)
                                    val key = com.phantom.player.ResumeStore.computeKey(pageUrl, src)
                                    com.phantom.player.ResumeStore.savePosition(prefs, key, pos, dur)
                                }
                            }
                            "js_err" -> {
                                val msg = json.optString("msg")
                                if (msg.isNotEmpty()) {
                                    val cleanMsg = if (msg.length > 500) msg.substring(0, 500) else msg
                                    if (BuildConfig.VERBOSE_LOGS) {
                                        Log.i("PHANTOM", "JS_ERR $cleanMsg")
                                    }
                                    com.phantom.log.EventLogStore.log("PHANTOM", "JS_ERR $cleanMsg")
                                }
                            }
                            else -> {
                                if (isMainFrame) {
                                    val on = json.optBoolean("on", false)
                                    val value = if (json.has("value")) json.optString("value") else null
                                    val input = if (json.has("input")) json.optString("input") else null
                                    val enter = if (json.has("enter")) json.optString("enter") else null
                                    Hub.sendText(buildEditable(on, "focus", value, input, enter))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("PHANTOM", "phantomBridge parse error: ${e.message}")
                    }
                }
            }
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(wv, FOCUS_SCRIPT, setOf("*"))
            if (adblockCss.isNotEmpty()) {
                val escapedCss = org.json.JSONObject.quote(adblockCss)
                val adblockStartScript = """
                    (function() {
                        if (document.getElementById('__phantom_adblock')) return;
                        var target = document.head || document.documentElement;
                        if (!target) return;
                        var el = document.createElement('style');
                        el.id = '__phantom_adblock';
                        el.textContent = $escapedCss;
                        target.appendChild(el);
                    })();
                """.trimIndent()
                WebViewCompat.addDocumentStartJavaScript(wv, adblockStartScript, setOf("*"))
            }
        }

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        popupGate = com.phantom.web.PopupGate(
            this,
            webContainer,
            blocklist,
            wv,
            assetLoader
        ) { isOpen ->
            cachedPopupOpen = isOpen
            updateAndSendState()
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (request == null) return null
                totalReqCount++
                val uri = request.url
                val urlStr = uri.toString()
                val headers = request.requestHeaders
                if (!request.isForMainFrame && request.method == "GET") mediaDetector.rememberHeaders(urlStr, headers)
                mediaDetector.inspectRequest(urlStr, headers, cachedUrl)
                val referer = headers?.entries?.firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value ?: ""
                val origin = headers?.entries?.firstOrNull { it.key.equals("Origin", ignoreCase = true) }?.value ?: ""
                if (BuildConfig.VERBOSE_LOGS) {
                    Log.i("PHANTOM", "REQ url=$urlStr isMain=${request.isForMainFrame} ref=$referer origin=$origin")
                }
                val lowerUrl = urlStr.lowercase()
                if (lowerUrl.contains(".m3u8") || lowerUrl.contains(".mp4") || lowerUrl.contains(".mpd") ||
                    lowerUrl.contains(".vtt") || lowerUrl.contains(".srt") || lowerUrl.contains(".ass") ||
                    lowerUrl.contains(".ts") || lowerUrl.contains("manifest") || lowerUrl.contains("playlist") ||
                    lowerUrl.contains("hls") || lowerUrl.contains("/stream") || lowerUrl.contains("video")) {
                    if (BuildConfig.VERBOSE_LOGS) {
                        Log.i("PHANTOM", "REQ_HDR $urlStr referer=$referer origin=$origin")
                    }
                }
                val accept = headers?.entries?.firstOrNull { it.key.equals("Accept", ignoreCase = true) }?.value ?: ""
                if (accept.contains("text/html") && !request.isForMainFrame) {
                    if (BuildConfig.VERBOSE_LOGS) {
                        Log.i("PHANTOM", "IFRAME_REQ $urlStr referer=$referer")
                    }
                }

                val assetResp = assetLoader.shouldInterceptRequest(uri)
                if (assetResp != null) return assetResp

                val pageHost = Uri.parse(cachedUrl).host ?: ""
                val pref = if (pageHost.isNotEmpty()) sitePrefs.getPref(pageHost) else com.phantom.web.SitePref()
                if (pref.adblock && !request.isForMainFrame) {
                    val reqHost = uri.host ?: ""
                    if (blocklist.isBlocked(reqHost)) {
                        blockedCount++
                        val now = SystemClock.uptimeMillis()
                        if (now - lastBlockedSendTime >= 500) {
                            lastBlockedSendTime = now
                            runOnUiThread {
                                updateAndSendState()
                            }
                        }
                        return WebResourceResponse(
                            "text/plain",
                            "UTF-8",
                            204,
                            "No Content",
                            emptyMap(),
                            java.io.ByteArrayInputStream(ByteArray(0))
                        )
                    }
                }
                return null
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme?.lowercase() ?: ""
                val host = uri.host ?: ""
                val urlStr = uri.toString()
                if (scheme != "http" && scheme != "https" && scheme != "about") {
                    Log.i("PHANTOM", "NAV_BLOCKED $urlStr")
                    com.phantom.log.EventLogStore.log("PHANTOM", "NAV_BLOCKED $urlStr")
                    return true
                }
                if (!request.isForMainFrame && blocklist.isBlocked(host)) {
                    Log.i("PHANTOM", "NAV_BLOCKED $urlStr")
                    com.phantom.log.EventLogStore.log("PHANTOM", "NAV_BLOCKED $urlStr")
                    return true
                }
                if (request.isForMainFrame) {
                    val current = webView?.url ?: cachedUrl
                    if (com.phantom.web.NavigationFilter.shouldBlockCrossSiteRedirect(urlStr, current,
                            request.hasGesture() || SystemClock.uptimeMillis() - lastClickTime < 3000)) {
                        val navMsg = "NAV_BLOCKED reason=cross_site_redirect target=$urlStr current=$current"
                        Log.i("PHANTOM", navMsg)
                        com.phantom.log.EventLogStore.log("PHANTOM", navMsg)
                        return true
                    }
                    val uaChanged = applyUaForHost(host)
                    if (uaChanged) {
                        view?.loadUrl(urlStr)
                        return true
                    }
                }
                return false
            }

            override fun onPageStarted(view: WebView?, urlStr: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, urlStr, favicon)
                liteAppliedForPage = false
                jankResetPending = true
                lastDetectedMediaUrl = null
                mediaDetector.reset()
                blockedCount = 0
                totalReqCount = 0
                pageStartTime = SystemClock.uptimeMillis()
                val host = Uri.parse(urlStr ?: "").host ?: ""
                if (host.isNotEmpty()) {
                    val pref = sitePrefs.getPref(host)
                    cachedUa = pref.ua
                    cachedZoom = pref.zoom
                    view?.settings?.textZoom = pref.zoom
                }
                isMediaPlaying = false
                updateKeepScreenOn()
                updateQrVisibility()
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    view?.evaluateJavascript(FOCUS_SCRIPT, null)
                }
                injectAdblockCss(view, urlStr)
                cachedLoading = true
                cachedProgress = 0
                if (urlStr != null) {
                    val lower = urlStr.lowercase()
                    if (lower.startsWith("http://") || lower.startsWith("https://") || lower.contains("appassets.androidplatform.net")) {
                        lastUrl = urlStr
                    }
                }
                updateAndSendState()
            }

            override fun onPageFinished(view: WebView?, urlStr: String?) {
                super.onPageFinished(view, urlStr)
                injectAdblockCss(view, urlStr)
                if (liteMode == com.phantom.web.LiteMode.Mode.ON) applyLite("on")
                val duration = if (pageStartTime > 0L) SystemClock.uptimeMillis() - pageStartTime else 0L
                val finishMsg = "PAGE_FINISHED ms=$duration total=$totalReqCount blocked=$blockedCount url=$urlStr"
                Log.i("PHANTOM", finishMsg)
                com.phantom.log.EventLogStore.log("PHANTOM", finishMsg)
                cachedLoading = false
                cachedProgress = 100
                if (urlStr != null) {
                    val lower = urlStr.lowercase()
                    if (lower.startsWith("http://") || lower.startsWith("https://") || lower.contains("appassets.androidplatform.net")) {
                        lastUrl = urlStr
                    }
                }
                val targetScrollY = pendingScrollY
                if (targetScrollY != null && targetScrollY > 0) {
                    view?.evaluateJavascript("window.scrollTo(0, $targetScrollY);", null)
                    view?.postDelayed({
                        view?.evaluateJavascript("window.scrollTo(0, $targetScrollY);", null)
                    }, 500)
                    pendingScrollY = null
                }
                updateAndSendState()
            }

            override fun doUpdateVisitedHistory(view: WebView?, urlStr: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, urlStr, isReload)
                updateAndSendState()
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                val didCrash = detail?.didCrash() ?: true
                Log.i("PHANTOM", "RENDER_GONE crashed=$didCrash")
                com.phantom.log.EventLogStore.log("PHANTOM", "RENDER_GONE crashed=$didCrash")

                val currentWv = webView
                if (currentWv != null) {
                    webContainer.removeView(currentWv)
                    currentWv.destroy()
                }

                // The system reclaimed the page's memory while we were in the background: rebuild
                // it when the user comes back, not now (that would take the memory right back).
                if (!didCrash && !isActivityStarted) {
                    webView = null
                    pendingRendererReload = lastUrl
                    return true
                }

                val now = System.currentTimeMillis()
                crashTimestamps.removeIf { now - it > 30000 }
                crashTimestamps.add(now)

                val reloadUrl: String
                if (crashTimestamps.size >= 3) {
                    reloadUrl = "about:blank"
                    Log.i("PHANTOM", "RECOVER_LOOP")
                } else {
                    reloadUrl = lastUrl
                    Log.i("PHANTOM", "RECOVERED $lastUrl")
                }

                createAndConfigureWebView(reloadUrl)
                return true
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: android.view.View?, callback: CustomViewCallback?) {
                if (view == null) return
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                updateQrVisibility()
                val ringIndex = rootLayout.indexOfChild(clickRingView)
                val cursorIndex = rootLayout.indexOfChild(cursorView)
                val insertIndex = if (ringIndex >= 0) ringIndex else (if (cursorIndex >= 0) cursorIndex else rootLayout.childCount)
                rootLayout.addView(
                    view,
                    insertIndex,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
                clickRingView.bringToFront()
                cursorView.bringToFront()
                inputController?.targetView = view
                Hub.sendText(buildFullscreen(true))
                Log.i("PHANTOM", "fullscreen on")
                onCursorActivity()
            }

            override fun onHideCustomView() {
                hideCustomView()
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                return popupGate?.onCreateWindow(view, isDialog, isUserGesture, resultMsg) ?: false
            }

            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                if (consoleMessage != null && consoleMessage.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                    val msg = consoleMessage.message()
                    if (!msg.contains("[object Object]") && !msg.contains("[object HTML")) {
                        val cleanMsg = if (msg.length > 500) msg.substring(0, 500) else msg
                        if (BuildConfig.VERBOSE_LOGS) {
                            Log.i("PHANTOM", "JS_ERR $cleanMsg")
                        }
                        com.phantom.log.EventLogStore.log("PHANTOM", "JS_ERR $cleanMsg")
                    }
                }
                return true
            }

            override fun onReceivedTitle(view: WebView?, titleStr: String?) {
                super.onReceivedTitle(view, titleStr)
                if (titleStr != null) {
                    cachedTitle = titleStr
                }
                updateAndSendState()
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                cachedProgress = newProgress
                val now = android.os.SystemClock.uptimeMillis()
                if (newProgress == 100 || now - lastProgressSendTime >= 200) {
                    lastProgressSendTime = now
                    updateAndSendState()
                }
            }
        }

        wv.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val w = right - left
            val h = bottom - top
            if (w > 0 && h > 0 && (w != (oldRight - oldLeft) || h != (oldBottom - oldTop))) {
                cursorState?.resize(w, h)
            }
        }

        // Let the system reclaim the page renderer while Loungo is not visible (TV low on
        // memory); it is rebuilt on return (onRenderProcessGone / onStart).
        wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        webView = wv
        webContainer.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        cursorState?.let {
            inputController = InputController(this, wv, it)
            popupGate?.inputController = inputController
        }
        if (!deferLoad) wv.loadUrl(url)
    }

    /** Pair URL (for the QR code) and the "host:port" text shown under it; shared by the QR card and the guide. */
    private fun buildPairInfo(): Pair<String, String> {
        val ip = PairingHelper.getLocalIpAddress(this)
        val token = TokenManager.getOrInitToken(this)
        val id = com.phantom.net.MdnsHelper.getOrCreatePersistentId(this)
        val hostname = com.phantom.net.MdnsHelper.getHostname(id)
        val pairUrl = com.phantom.net.MdnsHelper.buildPairUrl(hostname, ip, BuildConfig.WS_PORT, token)
        return pairUrl to "$hostname:${BuildConfig.WS_PORT}\n($ip:${BuildConfig.WS_PORT})"
    }

    private fun updateQrCard() {
        val (pairUrl, hostText) = buildPairInfo()
        qrUrlTextView.text = hostText
        Thread {
            try {
                val bitmap = PairingHelper.generateQrBitmap(pairUrl, 480)
                runOnUiThread {
                    qrImageView.setImageBitmap(bitmap)
                }
            } catch (e: Exception) {
                Log.w("PHANTOM", "QR generation failed: ${e.message}")
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        frameLoopResumed = true
        lastCursorActivityTime = SystemClock.uptimeMillis()
        wakeFrameLoop()
    }

    override fun onPause() {
        super.onPause()
        remoteMotionController.reset()
        frameLoopResumed = false
        frameLoopRunning = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    // In the background (Home pressed, another app opened) the page must not keep running its
    // scripts and timers: on a TV that slows down every other app.
    override fun onStop() {
        super.onStop()
        isActivityStarted = false
        if (!isNativePlayerOpen) {
            webView?.onPause()
            webView?.pauseTimers()
        }
    }

    override fun onStart() {
        super.onStart()
        isActivityStarted = true
        pendingRendererReload?.let { url ->
            pendingRendererReload = null
            Log.i("PHANTOM", "RENDER_REBUILD $url")
            createAndConfigureWebView(url)
            webView?.resumeTimers() // timers are process-wide and were paused in onStop
            return
        }
        if (!isNativePlayerOpen) {
            webView?.onResume()
            webView?.resumeTimers()
        }
    }

    private fun openNativePlayer(url: String) {
        val wv = webView
        wv?.onPause()
        wv?.pauseTimers()
        val record = mediaDetector.getRecord(url)
        val referer = record?.referer ?: wv?.url
        val origin = record?.origin
        val kind = record?.kind ?: com.phantom.web.kindOf(url)
        val currentPage = cachedUrl.ifEmpty { wv?.url ?: "" }
        val subList = com.phantom.web.resolveSubtitles(record, mediaDetector.getSubtitles(), currentPage)
        val subsJson = org.json.JSONArray().apply {
            subList.forEach { sub ->
                put(JSONObject().apply {
                    put("url", sub.url)
                    put("lang", sub.lang)
                    put("referer", sub.referer ?: referer)
                })
            }
        }.toString()
        val intent = Intent(this@BrowserActivity, com.phantom.player.NativePlayerActivity::class.java).apply {
            putExtra(com.phantom.player.NativePlayerActivity.EXTRA_URL, url)
            val pageUrl = record?.pageUrl ?: cachedUrl.ifEmpty { wv?.url ?: "" }
            putExtra(com.phantom.player.NativePlayerActivity.EXTRA_PAGE_URL, pageUrl)
            putExtra(com.phantom.player.NativePlayerActivity.EXTRA_KIND, kind)
            putExtra(com.phantom.player.NativePlayerActivity.EXTRA_REFERER, referer)
            putExtra(com.phantom.player.NativePlayerActivity.EXTRA_ORIGIN, origin)
            putExtra(com.phantom.player.NativePlayerActivity.EXTRA_SUBS, subsJson)
            putExtra(com.phantom.player.NativePlayerActivity.EXTRA_USER_AGENT, wv?.settings?.userAgentString)
            val cookie = android.webkit.CookieManager.getInstance().getCookie(url)
            putExtra(com.phantom.player.NativePlayerActivity.EXTRA_COOKIE, cookie)
            putExtra(com.phantom.player.NativePlayerActivity.EXTRA_TITLE, cachedTitle.ifEmpty { wv?.title ?: "" })
            mediaDetector.headersFor(url)?.let { h ->
                putExtra(com.phantom.player.NativePlayerActivity.EXTRA_HEADERS, JSONObject(h).toString())
            }
        }
        isNativePlayerOpen = true
        startActivityForResult(intent, REQUEST_CODE_PLAYER)
    }

    private fun showTvMenu() {
        onboardingView?.onMenuOpened() // MENU step of the guide completes when the real menu opens
        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        options.add(getString(R.string.menu_enter_url))
        actions.add { showUrlInputDialog() }

        val mediaUrl = lastDetectedMediaUrl
        if (mediaUrl != null && !isNativePlayerOpen) {
            options.add(getString(R.string.menu_open_in_player))
            actions.add { openNativePlayer(mediaUrl) }
        }

        options.add(getString(R.string.menu_back))
        actions.add {
            if (webView?.canGoBack() == true) webView?.goBack()
        }

        options.add(getString(R.string.menu_forward))
        actions.add {
            if (webView?.canGoForward() == true) webView?.goForward()
        }

        options.add(getString(R.string.menu_reload))
        actions.add { webView?.reload() }

        options.add(if (manualQrOpen) getString(R.string.menu_qr_close) else getString(R.string.menu_qr_pair))
        actions.add { toggleManualQr() }

        options.add(getString(R.string.menu_how_to_use))
        actions.add { startOnboarding(replay = true) }

        options.add(getString(R.string.menu_about_phantom))
        actions.add { startOnboarding(replay = false) }

        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.menu_title))
            .setItems(options.toTypedArray()) { _, which ->
                actions.getOrNull(which)?.invoke()
            }
            .setOnDismissListener { onboardingView?.onMenuDismissed() }
            .setNegativeButton(getString(R.string.btn_close), null)
            .show()
    }

    private fun showUrlInputDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "https://example.com"
            setText(webView?.url ?: "")
            selectAll()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val container = android.widget.FrameLayout(this).apply {
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(input)
        }

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_enter_url_title))
            .setView(container)
            .setPositiveButton(getString(R.string.btn_go)) { _, _ ->
                val raw = input.text.toString().trim()
                if (raw.isNotEmpty()) {
                    val target = com.phantom.web.normalizeUrl(raw, BuildConfig.DEBUG) ?: "https://$raw"
                    webView?.loadUrl(target)
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .create()

        dialog.show()
        input.requestFocus()
        input.postDelayed({
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 200L)
    }

    private fun showFavoritesDialog() {
        val favs = listOf(
            "Google" to "https://www.google.com",
            "Wikipedia" to "https://www.wikipedia.org",
            "YouTube" to "https://www.youtube.com",
            getString(R.string.fav_hls_stream) to "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
        )
        val names = favs.map { it.first }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_favorites_title))
            .setItems(names) { _, which ->
                val url = favs.getOrNull(which)?.second ?: return@setItems
                webView?.loadUrl(url)
            }
            .setNegativeButton(getString(R.string.btn_back), null)
            .show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        onCursorActivity()
        val media = lastDetectedMediaUrl
        if (media != null && !isNativePlayerOpen && event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY)) {
            mediaToastView.visibility = android.view.View.GONE
            openNativePlayer(media)
            return true
        }
        if (BuildConfig.DEBUG) {
            Log.i("PHANTOM", "KEY ${event.keyCode} ${event.action} ${event.repeatCount}")
        }
        lastInputTime = SystemClock.uptimeMillis()
        updateKeepScreenOn()

        // First-run guide: BACK steps back; on steps with buttons the D-pad drives button focus.
        onboardingView?.let { ov ->
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) ov.onBack()
                return true
            }
            if (ov.hasFocusableButtons() && (event.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                    event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN || event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                    event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    event.keyCode == KeyEvent.KEYCODE_ENTER)) {
                return super.dispatchKeyEvent(event)
            }
        }

        if (customView != null) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) {
                    handleBack(fromRemote = true)
                }
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (event.action == KeyEvent.ACTION_UP) {
                    handleBack(fromRemote = true)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    remoteMotionController.onKeyDown(event.keyCode, event.repeatCount, System.nanoTime())
                } else if (event.action == KeyEvent.ACTION_UP) {
                    remoteMotionController.onKeyUp(event.keyCode, System.nanoTime())
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    lastClickTime = SystemClock.uptimeMillis()
                    if (event.repeatCount == 0) {
                        okLongPressTriggered = false
                        okHandler.postDelayed(okLongPressRunnable, 800L)
                        clickRingView.onDown()
                    }
                } else if (event.action == KeyEvent.ACTION_UP) {
                    okHandler.removeCallbacks(okLongPressRunnable)
                    clickRingView.onUp()
                    if (!okLongPressTriggered) {
                        onboardingCursorClick()
                        inputController?.onButton(true, 0)
                        inputController?.onButton(false, 0)
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_PROG_RED,
            KeyEvent.KEYCODE_INFO -> {
                if (event.action == KeyEvent.ACTION_UP) {
                    showTvMenu()
                }
                return true
            }
            KeyEvent.KEYCODE_PROG_GREEN,
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (event.action == KeyEvent.ACTION_UP) {
                    val m = lastDetectedMediaUrl
                    if (m != null && !isNativePlayerOpen) {
                        openNativePlayer(m)
                    }
                }
                return true
            }
        }

        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        if (ev?.action == android.view.MotionEvent.ACTION_DOWN) {
            lastClickTime = SystemClock.uptimeMillis()
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun injectAdblockCss(view: WebView?, urlStr: String?) {
        val host = Uri.parse(urlStr ?: "").host ?: ""
        val pref = if (host.isNotEmpty()) sitePrefs.getPref(host) else com.phantom.web.SitePref()
        if (!pref.adblock) {
            view?.evaluateJavascript("(function(){var el=document.getElementById('__phantom_adblock');if(el)el.remove();})();", null)
            return
        }
        if (adblockCss.isEmpty()) return
        val escaped = org.json.JSONObject.quote(adblockCss)
        val script = """
            (function() {
                if (document.getElementById('__phantom_adblock')) return;
                var target = document.head || document.documentElement;
                if (!target) return;
                var el = document.createElement('style');
                el.id = '__phantom_adblock';
                el.textContent = $escaped;
                target.appendChild(el);
            })();
        """.trimIndent()
        view?.evaluateJavascript(script, null)
    }

    private fun handleSnap(reqId: Int) {
        runOnUiThread {
            val wv = webView
            val currentUrl = wv?.url ?: lastUrl
            val currentTitle = wv?.title ?: ""
            val startTime = System.currentTimeMillis()

            if (wv == null) {
                Log.w("PHANTOM", "SNAP_FAIL no_webview")
                Hub.sendText(buildSnap(reqId, currentUrl, currentTitle, 0))
                return@runOnUiThread
            }

            wv.evaluateJavascript("Math.round(window.scrollY || window.pageYOffset || 0)") { scrollResult ->
                val scrollY = scrollResult?.replace("\"", "")?.trim()?.toDoubleOrNull()?.toInt() ?: 0
                val destBitmap = try {
                    Bitmap.createBitmap(320, 180, Bitmap.Config.RGB_565)
                } catch (e: Throwable) {
                    Log.e("PHANTOM", "OOM/error creating bitmap for snap", e)
                    Log.w("PHANTOM", "SNAP_FAIL oom")
                    Hub.sendText(buildSnap(reqId, currentUrl, currentTitle, scrollY))
                    return@evaluateJavascript
                }

                val loc = IntArray(2)
                wv.getLocationInWindow(loc)
                val rect = if (wv.width > 0 && wv.height > 0) {
                    Rect(loc[0], loc[1], loc[0] + wv.width, loc[1] + wv.height)
                } else null

                val copyListener = PixelCopy.OnPixelCopyFinishedListener { copyResult ->
                    if (copyResult == PixelCopy.SUCCESS) {
                        Thread {
                            try {
                                val bos = java.io.ByteArrayOutputStream()
                                destBitmap.compress(Bitmap.CompressFormat.JPEG, 70, bos)
                                val jpegBytes = bos.toByteArray()
                                destBitmap.recycle()
                                val totalMs = System.currentTimeMillis() - startTime

                                val snapJson = buildSnap(reqId, currentUrl, currentTitle, scrollY)
                                Hub.sendText(snapJson)

                                val thumbBin = encodeThumb(reqId, jpegBytes)
                                Hub.sendBinary(thumbBin)

                                Log.i("PHANTOM", "SNAP reqId=$reqId ms=$totalMs bytes=${jpegBytes.size}")
                            } catch (e: Exception) {
                                Log.e("PHANTOM", "Failed to compress/send thumb", e)
                                destBitmap.recycle()
                                Hub.sendText(buildSnap(reqId, currentUrl, currentTitle, scrollY))
                            }
                        }.start()
                    } else {
                        destBitmap.recycle()
                        Log.w("PHANTOM", "SNAP_FAIL $copyResult")
                        Hub.sendText(buildSnap(reqId, currentUrl, currentTitle, scrollY))
                    }
                }

                try {
                    if (rect != null) {
                        PixelCopy.request(window, rect, destBitmap, copyListener, Handler(Looper.getMainLooper()))
                    } else {
                        PixelCopy.request(window, destBitmap, copyListener, Handler(Looper.getMainLooper()))
                    }
                } catch (e: Exception) {
                    Log.e("PHANTOM", "PixelCopy exception", e)
                    destBitmap.recycle()
                    Log.w("PHANTOM", "SNAP_FAIL exception")
                    Hub.sendText(buildSnap(reqId, currentUrl, currentTitle, scrollY))
                }
            }
        }
    }

    companion object {
        /** Keep the frame loop running this long after the last input (cursor auto-hide is 3 s). */
        private const val FRAME_LOOP_IDLE_MS = 4000L
        private const val EDGE_PX = 8
        private const val EDGE_SCROLL_FACTOR = 1.5
        private const val REQUEST_CODE_PLAYER = 101
        private const val ONBOARDING_PREFS = "phantom_onboarding"
        private const val ONBOARDING_KEY = "seenVersion"
        private const val ONBOARDING_VERSION = 3 // 3: showcase pages + short tour (shown again once)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_PLAYER) {
            isNativePlayerOpen = false
            onCursorActivity()
            runOnUiThread {
                showCursor()
                webView?.onResume()
                webView?.resumeTimers()
                Hub.sendText(buildFullscreen(false))
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Hub.clientListeners.remove(clientListener)
        onboardingView?.release()
        Hub.tokenListeners.remove(tokenListener)
        Hub.wsRejectListeners.remove(wsRejectListener)
        qrHandler.removeCallbacks(rejectBurstTimeoutRunnable)
        qrHandler.removeCallbacks(startupQrTimeoutRunnable)
        qrHandler.removeCallbacks(manualQrTimeoutRunnable)
        qrHandler.removeCallbacks(hideDisconnectToastRunnable)
        okHandler.removeCallbacks(okLongPressRunnable)
        screenHandler.removeCallbacks(screenCheckRunnable)
        customView?.let { rootLayout.removeView(it) }
        customView = null
        webView?.destroy()
        webView = null
        Hub.adBlocker = null
        Hub.sink = null
        Hub.stateProvider = null
    }
}
