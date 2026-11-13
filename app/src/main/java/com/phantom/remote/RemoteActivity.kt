package com.phantom.remote

import android.annotation.SuppressLint
import android.app.Activity
import android.app.UiModeManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.phantom.BrowserActivity
import com.phantom.BuildConfig
import com.phantom.R
import com.phantom.onboarding.ShowcaseArt
import java.util.concurrent.Executors

/**
 * Phone entry point: the TV's remote, never the browser.
 * - Info screen (what Loungo is, how to install it on the TV) while it looks for a Loungo TV.
 * - Pairs without a QR: the TV asks "Connect this phone?" and its remote approves.
 * - Then shows the remote page served by that TV, so phone and TV always run the same version.
 * On a TV it hands over to [BrowserActivity]. Never starts CoreService (see PhantomApplication).
 */
class RemoteActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "loungo-remote").apply { isDaemon = true } }
    private lateinit var store: RemoteStore
    private lateinit var finder: TvFinder

    private lateinit var container: FrameLayout
    private lateinit var infoRoot: View
    private lateinit var rings: SearchRingsView
    private lateinit var statusText: TextView
    private lateinit var hintText: TextView
    private lateinit var tvList: LinearLayout

    private var webView: WebView? = null
    /** The TV the remote page was showing; reopened on return from the background. */
    private var remoteTv: Pair<FoundTv, String>? = null
    private val found = LinkedHashMap<String, FoundTv>()
    private var firstFoundAt = 0L
    private var connecting = false
    private var autoPick = true
    /** Bumped on stop/back-to-search so stale background work is ignored. */
    private var generation = 0

    private val showHint = Runnable { hintText.visibility = View.VISIBLE }
    private val evaluate = Runnable { decide() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isTv()) {
            startActivity(Intent(this, BrowserActivity::class.java))
            finish()
            return
        }
        store = RemoteStore(this)
        finder = TvFinder(this, ::onFound, ::onLost)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        container = FrameLayout(this).apply { setBackgroundColor(BG) }
        // Android 15 draws edge to edge: keep content clear of the bars and the keyboard.
        container.setOnApplyWindowInsetsListener { v, insets ->
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            WindowInsets.CONSUMED // the remote page must not add its own safe-area padding on top
        }
        infoRoot = buildInfoScreen()
        container.addView(infoRoot)
        setContentView(container)
    }

    override fun onStart() {
        super.onStart()
        if (isFinishing) return
        val last = remoteTv
        if (last != null) openRemote(last.first, last.second) else startSearch()
    }

    override fun onStop() {
        super.onStop()
        if (!::finder.isInitialized) return
        generation++
        stopSearch()
        connecting = false
        // In the background the remote lets go of the TV: only one phone can be connected, and a
        // hidden page reconnecting would push out the phone the user is actually holding.
        closeRemotePage()
    }

    private fun closeRemotePage() {
        val wv = webView ?: return
        webView = null
        container.removeView(wv)
        wv.destroy()
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        io.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Activity back handling; Back must reach the TV, not close the remote")
    override fun onBackPressed() {
        val wv = webView
        if (wv == null) {
            @Suppress("DEPRECATION") super.onBackPressed()
            return
        }
        // The page closes its own sheet first, otherwise sends Back to the TV.
        wv.evaluateJavascript("(window.__loungoBack&&window.__loungoBack())===true", null)
    }

    // ---------- search & pairing ----------

    private fun startSearch() {
        generation++
        connecting = false
        autoPick = true
        found.clear()
        tvList.removeAllViews()
        hintText.visibility = View.GONE
        statusText.setText(R.string.remote_searching)
        rings.searching()
        main.removeCallbacks(showHint)
        main.postDelayed(showHint, 15_000L)
        debugHost()?.let { connect(it); return }
        finder.start()
    }

    private fun stopSearch() {
        finder.stop()
        rings.pause()
        main.removeCallbacks(showHint)
        main.removeCallbacks(evaluate)
    }

    private fun onFound(tv: FoundTv) {
        if (found.isEmpty()) firstFoundAt = System.currentTimeMillis()
        found[tv.service] = tv
        Log.i(TAG, "found ${tv.service} ${tv.host}:${tv.port}")
        decide()
        main.postDelayed(evaluate, 850L)
    }

    private fun onLost(service: String) {
        found.remove(service)
        if (!connecting) showTvButtons(found.values.toList())
    }

    private fun decide() {
        if (connecting || webView != null) return
        if (!autoPick) { showTvButtons(found.values.toList()); return }
        when (val d = TvPick.decide(found.values.toList(), store.lastService, firstFoundAt, System.currentTimeMillis())) {
            is TvPick.Decision.Connect -> connect(d.tv)
            is TvPick.Decision.Choose -> {
                statusText.setText(R.string.remote_choose)
                showTvButtons(d.tvs)
            }
            TvPick.Decision.Wait -> {}
        }
    }

    private fun showTvButtons(tvs: List<FoundTv>) {
        tvList.removeAllViews()
        for (tv in tvs) {
            tvList.addView(Button(this).apply {
                text = tv.name
                isAllCaps = false
                setTextColor(WHITE)
                background = round(PANEL, 12f)
                setOnClickListener { connect(tv) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) })
        }
    }

    private fun connect(tv: FoundTv) {
        if (connecting) return
        connecting = true
        tvList.removeAllViews()
        main.removeCallbacks(showHint)
        hintText.visibility = View.GONE
        rings.found()
        statusText.text = getString(R.string.remote_found, tv.name)
        val gen = generation
        val client = PairClient(tv.host, tv.port)
        io.execute {
            val saved = store.token(tv.service)
            if (saved != null && client.check(saved)) {
                post(gen) { main.postDelayed({ if (gen == generation) openRemote(tv, saved) }, 500L) }
                return@execute
            }
            val id = when (val r = client.request(deviceName())) {
                is PairClient.Result.Started -> r.id
                else -> { post(gen) { failed(r) }; return@execute }
            }
            post(gen) { statusText.setText(R.string.remote_confirm_on_tv) }
            val deadline = System.currentTimeMillis() + 35_000L
            while (gen == generation && System.currentTimeMillis() < deadline) {
                // The screen closing mid-pairing interrupts this thread (shutdownNow): just stop.
                try { Thread.sleep(1000L) } catch (_: InterruptedException) { return@execute }
                when (val s = client.status(id)) {
                    is PairClient.Result.Token -> {
                        store.saveToken(tv.service, s.token)
                        post(gen) { openRemote(tv, s.token) }
                        return@execute
                    }
                    PairClient.Result.Pending -> {}
                    else -> { post(gen) { failed(s) }; return@execute }
                }
            }
            post(gen) { failed(PairClient.Result.Denied) }
        }
    }

    private fun failed(r: PairClient.Result) {
        connecting = false
        autoPick = false // a failed TV is not retried by itself: the TV would keep asking
        rings.searching()
        statusText.setText(
            when (r) {
                PairClient.Result.Busy -> R.string.remote_busy
                PairClient.Result.RateLimited -> R.string.remote_rate_limited
                PairClient.Result.TvBusy -> R.string.remote_tv_busy
                PairClient.Result.Denied -> R.string.remote_denied
                else -> R.string.remote_conn_error
            }
        )
        val tvs = found.values.toList()
        showTvButtons(tvs)
        debugHost()?.let { if (tvs.isEmpty()) showTvButtons(listOf(it)) }
    }

    private fun post(gen: Int, block: () -> Unit) = main.post { if (gen == generation) block() }

    // ---------- remote page ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun openRemote(tv: FoundTv, token: String) {
        stopSearch()
        connecting = false
        val origin = "http://${tv.host}:${tv.port}"
        val wv = WebView(this).apply {
            setBackgroundColor(BG)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Without a chrome client the page's confirm()/alert() silently return "no"
            // (e.g. "Reset pairing" did nothing).
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                    val url = req.url
                    if (url.scheme == "loungo-app") {
                        if (url.host == "unpaired") {
                            store.forget(tv.service)
                            backToSearch()
                        }
                        return true
                    }
                    if (url.toString().startsWith(origin)) return false
                    try { startActivity(Intent(Intent.ACTION_VIEW, url)) } catch (_: Exception) {}
                    return true
                }
            }
        }
        webView = wv
        remoteTv = tv to token
        container.removeAllViews()
        container.addView(wv)
        wv.loadUrl("$origin/?k=${Uri.encode(token)}&app=1")
        Log.i(TAG, "REMOTE_OPEN ${tv.service}")
    }

    private fun backToSearch() {
        remoteTv = null
        closeRemotePage()
        container.removeAllViews()
        container.addView(infoRoot)
        startSearch()
    }

    // ---------- helpers ----------

    private fun debugHost(): FoundTv? {
        if (!BuildConfig.DEBUG) return null
        val h = intent?.getStringExtra("host") ?: return null // e.g. 10.0.2.2:8766 (emulator tests)
        val host = h.substringBefore(':')
        val port = h.substringAfter(':', "").toIntOrNull() ?: BuildConfig.WS_PORT
        return FoundTv("debug-$host", host, host, port)
    }

    private fun deviceName(): String =
        Settings.Global.getString(contentResolver, "device_name")?.takeIf { it.isNotBlank() } ?: Build.MODEL

    private fun isTv(): Boolean =
        getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

    private fun openPlayPage() {
        val pkg = "com.phantom.browser"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")))
            } catch (_: Exception) {}
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun round(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = radiusDp * resources.displayMetrics.density
    }

    private fun text(resId: Int, sizeSp: Float, color: Int = WHITE, bold: Boolean = false, top: Int = 0) =
        TextView(this).apply {
            setText(resId)
            textSize = sizeSp
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.15f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(top) }
        }

    private fun art(page: Int, sizeDp: Int) = ShowcaseArt(this, page).apply {
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { gravity = Gravity.CENTER_HORIZONTAL }
    }

    private fun card(page: Int, title: Int, body: Int?, bullets: List<Pair<Int, Int>> = emptyList()) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(PANEL, 16f)
            setPadding(dp(18), dp(16), dp(18), dp(18))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(12) }
            addView(art(page, 96))
            addView(text(title, 18f, bold = true, top = 8))
            body?.let { addView(text(it, 15f, DIM, top = 6)) }
            for ((t, b) in bullets) {
                addView(text(t, 15f, bold = true, top = 12))
                addView(text(b, 15f, DIM, top = 2))
            }
        }

    private fun buildInfoScreen(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(32))
        }
        // Status area: rings, status line, 15 s hint, TV buttons.
        rings = SearchRingsView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120))
        }
        col.addView(rings)
        statusText = text(R.string.remote_searching, 16f, bold = true).apply { gravity = Gravity.CENTER }
        hintText = text(R.string.remote_hint, 14f, DIM, top = 4).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        tvList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(statusText)
        col.addView(hintText)
        col.addView(tvList)

        col.addView(text(R.string.info_title, 26f, bold = true, top = 32))
        col.addView(text(R.string.info_sub, 18f, DIM, top = 6))
        col.addView(text(R.string.info_light, 30f, bold = true, top = 24))
        col.addView(text(R.string.info_simple, 30f, bold = true))
        col.addView(text(R.string.info_fast, 30f, bold = true))

        col.addView(text(R.string.info_on_tv, 20f, bold = true, top = 32))
        col.addView(card(4, R.string.card1_title, null, listOf(
            R.string.card1_b1_t to R.string.card1_b1,
            R.string.card1_b2_t to R.string.card1_b2,
            R.string.card1_b3_t to R.string.card1_b3,
            R.string.card1_b4_t to R.string.card1_b4,
        )))
        col.addView(card(2, R.string.card2_title, R.string.card2_body))
        col.addView(card(3, R.string.card3_title, R.string.card3_body))
        col.addView(card(0, R.string.card4_title, R.string.card4_body))
        col.addView(card(1, R.string.info_phone_title, R.string.info_phone_body))

        col.addView(text(R.string.info_install_title, 20f, bold = true, top = 32))
        col.addView(text(R.string.info_supported_t, 16f, bold = true, top = 10))
        col.addView(text(R.string.info_supported, 15f, DIM, top = 2))
        col.addView(text(R.string.info_opt1_t, 16f, bold = true, top = 14))
        col.addView(text(R.string.info_opt1, 15f, DIM, top = 2))
        col.addView(text(R.string.info_opt2_t, 16f, bold = true, top = 14))
        col.addView(text(R.string.info_opt2, 15f, DIM, top = 2))
        col.addView(Button(this).apply {
            setText(R.string.info_install_btn)
            isAllCaps = false
            textSize = 16f
            setTextColor(WHITE)
            background = round(ACCENT, 12f)
            setOnClickListener { openPlayPage() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(14) })

        col.addView(text(R.string.info_connect_title, 20f, bold = true, top = 32))
        col.addView(text(R.string.info_connect_1, 15f, DIM, top = 8))
        col.addView(text(R.string.info_connect_2, 15f, DIM, top = 4))

        return ScrollView(this).apply { addView(col) }
    }

    companion object {
        private const val TAG = "LOUNGO_REMOTE"
        private const val BG = 0xFF0E0E12.toInt()
        private const val PANEL = 0xFF1C1C21.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val DIM = 0xFFB0B0B8.toInt()
        private const val ACCENT = 0xFF0A84FF.toInt()
    }
}
