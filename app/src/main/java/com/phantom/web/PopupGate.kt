package com.phantom.web

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import com.phantom.input.InputController
import com.phantom.server.Hub
import com.phantom.server.buildPopup
import com.phantom.server.buildPopupGone
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class PopupGate(
    private val context: Context,
    private val webContainer: ViewGroup,
    private val blocklist: Blocklist,
    private val mainWebView: WebView,
    private val assetLoader: WebViewAssetLoader,
    private val onPopupStateChanged: (popupOpen: Boolean) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    var inputController: InputController? = null

    data class PopupSession(
        val id: String,
        val childWebView: WebView,
        var url: String = "",
        var isVisible: Boolean = false,
        var timeoutRunnable: Runnable? = null
    )

    var activePopup: PopupSession? = null
        private set

    val isPopupVisible: Boolean
        get() = activePopup?.isVisible == true

    fun canPopupGoBack(): Boolean {
        val session = activePopup ?: return false
        return session.isVisible && session.childWebView.canGoBack()
    }

    fun popupGoBack() {
        val session = activePopup ?: return
        if (session.isVisible && session.childWebView.canGoBack()) {
            session.childWebView.goBack()
        }
    }

    fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message?
    ): Boolean {
        if (activePopup != null) {
            Log.w("PHANTOM", "POPUP_REJECT: Already has active popup")
            return false
        }

        val id = UUID.randomUUID().toString().substring(0, 8)
        val child = WebView(context).apply {
            visibility = View.GONE
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically = true
                mediaPlaybackRequiresUserGesture = false
            }
        }

        val session = PopupSession(id = id, childWebView = child)
        activePopup = session
        webContainer.addView(child, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val firstRequestHandled = AtomicBoolean(false)

        child.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val uri = request?.url ?: return null
                val scheme = uri.scheme?.lowercase() ?: ""
                val host = uri.host ?: ""
                val urlStr = uri.toString()

                if (firstRequestHandled.compareAndSet(false, true)) {
                    if ((scheme != "http" && scheme != "https") || blocklist.isBlocked(host)) {
                        Log.i("PHANTOM", "POPUP_BLOCKED $urlStr")
                        mainHandler.post {
                            destroyPopup(session, "blocked")
                        }
                        return WebResourceResponse("text/plain", "UTF-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    } else {
                        session.url = urlStr
                        mainHandler.post {
                            child.onPause()
                            Log.i("PHANTOM", "POPUP_HELD $urlStr")
                            Hub.sendText(buildPopup(session.id, urlStr, isUserGesture))
                            val timeout = Runnable {
                                if (activePopup === session && !session.isVisible) {
                                    destroyPopup(session, "timeout")
                                }
                            }
                            session.timeoutRunnable = timeout
                            mainHandler.postDelayed(timeout, 12_000)
                        }
                    }
                }
                val assetResp = assetLoader.shouldInterceptRequest(uri)
                if (assetResp != null) return assetResp

                if (blocklist.isBlocked(host)) {
                    return WebResourceResponse(
                        "text/plain",
                        "UTF-8",
                        204,
                        "No Content",
                        emptyMap(),
                        ByteArrayInputStream(ByteArray(0))
                    )
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

                if ((scheme != "http" && scheme != "https") || blocklist.isBlocked(host)) {
                    Log.i("PHANTOM", "POPUP_BLOCKED $urlStr")
                    destroyPopup(session, "blocked")
                    return true
                }
                return false
            }
        }

        child.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView?) {
                destroyPopup(session, "closed")
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                if (com.phantom.BuildConfig.VERBOSE_LOGS && consoleMessage != null && consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    val msg = consoleMessage.message()
                    if (!msg.contains("[object Object]") && !msg.contains("[object HTML")) {
                        val cleanMsg = if (msg.length > 500) msg.substring(0, 500) else msg
                        Log.i("PHANTOM", "JS_ERR $cleanMsg")
                    }
                }
                return true
            }
        }

        val transport = resultMsg?.obj as? WebView.WebViewTransport
        if (transport != null) {
            transport.webView = child
            resultMsg.sendToTarget()
            return true
        }

        destroyPopup(session, "closed")
        return false
    }

    fun onDecision(id: String, action: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { onDecision(id, action) }
            return
        }
        val session = activePopup
        if (session == null || session.id != id) return

        session.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }

        when (action) {
            "open" -> {
                session.isVisible = true
                session.childWebView.visibility = View.VISIBLE
                session.childWebView.onResume()
                inputController?.targetView = session.childWebView
                onPopupStateChanged(true)
            }
            "deny" -> {
                destroyPopup(session, "closed")
            }
            "newtab" -> {
                val targetUrl = session.url
                destroyPopup(session, "closed")
                if (targetUrl.isNotEmpty()) {
                    mainWebView.loadUrl(targetUrl)
                }
            }
        }
    }

    fun closeActivePopup(reason: String = "closed") {
        val session = activePopup ?: return
        destroyPopup(session, reason)
    }

    private fun destroyPopup(session: PopupSession, reason: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { destroyPopup(session, reason) }
            return
        }
        session.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }

        Hub.sendText(buildPopupGone(session.id, reason))
        Log.i("PHANTOM", "POPUP_DESTROYED reason=$reason")

        if (activePopup === session) {
            activePopup = null
            inputController?.targetView = mainWebView
            onPopupStateChanged(false)
        }

        webContainer.removeView(session.childWebView)
        session.childWebView.destroy()
    }
}
