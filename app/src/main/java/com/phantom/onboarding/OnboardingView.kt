package com.phantom.onboarding

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.phantom.R
import com.phantom.pairing.PairingHelper

/**
 * Full-screen first-run overlay (showcase + tutorial). Built in code, no layout XML or images.
 * The host activity owns the [OnboardingState] life cycle through [Host]; this view only renders
 * the current step and forwards button presses to the state machine.
 */
class OnboardingView(
    context: Context,
    val state: OnboardingState,
    private val host: Host,
) : FrameLayout(context) {

    interface Host {
        /** Pair URL for the QR code and the "host:port" text shown under it. */
        fun pairInfo(): Pair<String, String>
        /** The flow reached DONE; the host should remove the view and persist "seen". */
        fun onOnboardingFinished(state: OnboardingState)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val d = resources.displayMetrics.density
    private val screenW = resources.displayMetrics.widthPixels
    private val screenH = resources.displayMetrics.heightPixels

    private val accent = 0xFF0A84FF.toInt()
    private val panel = 0xFF2C2C2E.toInt()
    private val textMain = 0xFFFFFFFF.toInt()
    private val textDim = 0xFFB0B0B8.toInt()
    private val green = 0xFF30D158.toInt()

    private var targetView: TargetView? = null
    private var skipButton: TextView? = null
    private val buttons = mutableListOf<TextView>()
    private var defaultFocus: View? = null
    private var hasButtonsNow = false
    private var qrBitmap: Bitmap? = null
    private var qrRequested = false
    private var advanceScheduled = false

    private val advanceRunnable = Runnable {
        advanceScheduled = false
        state.advance()
        render()
    }

    init {
        setBackgroundColor(0xF2101014.toInt())
        isClickable = true // swallow touch so nothing reaches the page underneath
        isFocusable = false
        render()
    }

    /** True while the current step shows buttons that must receive D-pad focus (not the cursor). */
    fun hasFocusableButtons(): Boolean = hasButtonsNow

    /**
     * Showcase navigation (INTRO). BrowserActivity hands D-pad keys to the normal view dispatch on
     * steps with buttons; this runs on the way down to the focused view, so left/right change the
     * page even while a button has focus. OK = next page unless a button has focus (then the
     * button's own click runs).
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (state.step == OnboardingStep.INTRO) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) state.prevPage() else state.nextPage()
                        render()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    val f = findFocus()
                    if (f == null || f !in buttons) {
                        if (event.action == KeyEvent.ACTION_UP) { state.nextPage(); render() }
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Screen rect of the glowing target (CURSOR step), or null. */
    private fun targetScreenRect(): Rect? {
        val t = targetView ?: return null
        if (!t.isShown) return null
        val loc = IntArray(2)
        t.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + t.width, loc[1] + t.height)
    }

    /** A click happened with the cursor at (x, y) screen px (OK press or phone tap release). */
    fun onCursorClick(x: Int, y: Int) {
        if (state.step == OnboardingStep.CURSOR && targetScreenRect()?.contains(x, y) == true) {
            state.onCursorClickInside()
            render()
            scheduleAdvance(900L)
            return
        }
        // The cursor (phone trackpad / remote cursor) can also press any visible button.
        for (b in buttons) {
            if (!b.isShown) continue
            val loc = IntArray(2)
            b.getLocationOnScreen(loc)
            if (Rect(loc[0], loc[1], loc[0] + b.width, loc[1] + b.height).contains(x, y)) {
                b.performClick()
                return
            }
        }
    }

    fun onPhoneDisconnected() = state.onPhoneDisconnected()

    fun onPhoneConnected() {
        val before = state.step
        state.onPhoneConnected()
        if (before != OnboardingStep.PHONE && before != OnboardingStep.INTRO) {
            android.widget.Toast.makeText(context, R.string.onb_phone_connected, android.widget.Toast.LENGTH_SHORT).show()
        }
        render()
        if (state.step == OnboardingStep.PHONE) scheduleAdvance(1500L)
    }

    fun onMenuOpened() {
        if (state.step != OnboardingStep.MENU) return
        state.onMenuOpened()
        render()
    }

    fun onMenuDismissed() {
        if (state.step == OnboardingStep.MENU && state.completed) scheduleAdvance(500L)
    }

    fun onBack() {
        cancelAdvance()
        state.back()
        render()
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        removeAllViews()
        targetView = null
        skipButton = null
        defaultFocus = null
        qrBitmap = null
    }

    private fun cancelAdvance() {
        handler.removeCallbacks(advanceRunnable)
        advanceScheduled = false
    }

    private fun scheduleAdvance(ms: Long) {
        if (advanceScheduled) return
        advanceScheduled = true
        handler.postDelayed(advanceRunnable, ms)
    }

    // ---- rendering ----

    private fun render() {
        if (state.isDone) {
            handler.removeCallbacksAndMessages(null)
            host.onOnboardingFinished(state)
            return
        }
        removeAllViews()
        targetView = null
        skipButton = null
        defaultFocus = null
        buttons.clear()

        val column = LinearLayout(this.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((screenW * 0.05f).toInt(), (screenH * 0.05f).toInt(), (screenW * 0.05f).toInt(), (screenH * 0.05f).toInt())
        }
        val content = LinearLayout(this.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        column.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        when (state.step) {
            OnboardingStep.INTRO -> buildIntro(content)
            OnboardingStep.PHONE -> buildPhone(content)
            OnboardingStep.CURSOR -> buildRemoteCursor(content)
            OnboardingStep.MENU -> buildRemoteMenu(content)
            OnboardingStep.VIDEO -> buildVideo(content)
            OnboardingStep.DONE -> {}
        }

        // Bottom bar: progress dots centered, Skip on the right (every step, including the showcase).
        val bar = FrameLayout(this.context)
        val dots = LinearLayout(this.context).apply { orientation = LinearLayout.HORIZONTAL }
        state.progress()?.let { (idx, total) ->
            for (i in 0 until total) {
                val dot = View(this.context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(if (i == idx) accent else 0xFF555560.toInt())
                    }
                }
                dots.addView(dot, LinearLayout.LayoutParams(dp(12), dp(12)).apply { setMargins(dp(6), 0, dp(6), 0) })
            }
        }
        bar.addView(dots, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        val skip = button(context.getString(R.string.onb_skip), primary = false) { cancelAdvance(); state.skip(); render() }
        skipButton = skip
        bar.addView(skip, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
        column.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        addView(column, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Buttons take D-pad focus only on steps that have real choices; CURSOR/MENU keep the cursor.
        hasButtonsNow = state.step != OnboardingStep.CURSOR && state.step != OnboardingStep.MENU
        skip.isFocusable = hasButtonsNow
        if (hasButtonsNow) {
            (defaultFocus ?: skip).let { f -> f.post { f.requestFocus() } }
        } else {
            clearFocus()
        }

        // PHONE step satisfied already (phone connected before the step was shown).
        if (state.step == OnboardingStep.PHONE && state.completed) scheduleAdvance(1500L)
    }

    /** Showcase: page 0 = WELCOME, 1..4 = feature pages (left: text, right: drawing). */
    private fun buildIntro(c: LinearLayout) {
        val page = state.page
        if (page == 0) {
            c.addView(ShowcaseArt(context, 0), LinearLayout.LayoutParams(dp(110), dp(110)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            c.addView(label(R.string.onb_welcome_title, 72f, true, textMain, center = true), lp(top = 4, bottom = 4))
            c.addView(label(R.string.onb_welcome_line, 28f, false, textDim, center = true), lp(bottom = 28))
            val explore = button(context.getString(R.string.onb_btn_explore), primary = true) { state.nextPage(); render() }
            defaultFocus = explore
            c.addView(explore, lp())
            return
        }
        val titles = intArrayOf(0, R.string.onb_p1_title, R.string.onb_p2_title, R.string.onb_p3_title, R.string.onb_p4_title)
        val phrases = arrayOf(
            intArrayOf(),
            intArrayOf(R.string.onb_p1_a, R.string.onb_p1_b, R.string.onb_p1_c),
            intArrayOf(R.string.onb_p2_a, R.string.onb_p2_b, R.string.onb_p2_c),
            intArrayOf(R.string.onb_p3_a, R.string.onb_p3_b),
            intArrayOf(R.string.onb_p4_a, R.string.onb_p4_b, R.string.onb_p4_c),
        )
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val left = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(0, 0, dp(16), 0)
            // Focus anchor on pages without buttons: keeps key events inside this view so
            // left/right/OK page navigation reaches dispatchKeyEvent.
            isFocusable = true
            defaultFocusHighlightEnabled = false
        }
        val halfW = (screenW * 0.42f).toInt()
        left.addView(label(titles[page], 40f, true, textMain).apply { maxWidth = halfW }, leftLp(bottom = 18))
        for (p in phrases[page]) {
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            line.addView(View(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) }
            }, LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(14) })
            line.addView(label(p, 26f, false, textMain).apply { maxWidth = halfW - dp(24) })
            left.addView(line, leftLp(bottom = 12))
        }
        if (page == OnboardingState.LAST_PAGE) {
            val tour = button(context.getString(R.string.onb_btn_tour), primary = true) { state.startTour(); render() }
            val open = button(context.getString(R.string.onb_btn_open_browser), primary = false) { state.openBrowser(); render() }
            defaultFocus = tour
            // Left-aligned buttons grow to the right when focused, so the enlarged outline is
            // not clipped at the column's left edge.
            tour.pivotX = 0f; open.pivotX = 0f
            left.addView(tour, leftLp(top = 20))
            left.addView(open, leftLp(top = 12))
        } else {
            defaultFocus = left
        }
        row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        row.addView(ShowcaseArt(context, page), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        c.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun buildPhone(c: LinearLayout) {
        c.addView(title(R.string.onb_phone_title), lp(bottom = 8))
        c.addView(label(R.string.onb_phone_line, 26f, false, textMain, center = true), lp(bottom = 16))
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val (pairUrl, hostText) = host.pairInfo()
        val qr = ImageView(context).apply {
            setBackgroundColor(Color.WHITE)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        qrBitmap?.let { qr.setImageBitmap(it) }
        val qrCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        qrCol.addView(qr, LinearLayout.LayoutParams(dp(220), dp(220)))
        qrCol.addView(TextView(context).apply {
            text = hostText
            setTextColor(textDim)
            textSize = 16f
            gravity = Gravity.CENTER
        }, lp(top = 8))
        row.addView(qrCol, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (qrBitmap == null && !qrRequested) {
            qrRequested = true
            Thread {
                try {
                    val bmp = PairingHelper.generateQrBitmap(pairUrl, 480)
                    post { qrBitmap = bmp; qr.setImageBitmap(bmp) }
                } catch (_: Exception) { }
            }.start()
        }
        val side = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (state.completed) {
            side.addView(label(R.string.onb_phone_connected, 28f, true, green), leftLp())
        } else {
            val later = button(context.getString(R.string.onb_btn_later), primary = true) { cancelAdvance(); state.later(); render() }
            defaultFocus = later
            side.addView(later, leftLp())
        }
        row.addView(side, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(32) })
        c.addView(row)
    }

    /** REMOTE step, part A: cursor target. */
    private fun buildRemoteCursor(c: LinearLayout) {
        c.addView(title(R.string.onb_remote_title), lp(bottom = 12))
        c.addView(label(R.string.onb_remote_line, 26f, false, textMain, center = true), lp(bottom = 18))
        val t = TargetView(context, green).also { it.done = state.completed }
        targetView = t
        // Off-centre on purpose: the cursor starts in the middle of the screen, so the user has
        // to actually move it.
        c.addView(t, LinearLayout.LayoutParams(dp(130), dp(130)).apply {
            gravity = Gravity.END
            marginEnd = (screenW * 0.12f).toInt()
        })
    }

    /** REMOTE step, part B (same step, same dots): open the long-press menu. */
    private fun buildRemoteMenu(c: LinearLayout) {
        c.addView(title(R.string.onb_remote_title), lp(bottom = 12))
        c.addView(label(R.string.onb_remote_menu_line, 26f, false, textMain, center = true), lp(bottom = 18))
        if (state.completed) c.addView(TextView(context).apply {
            text = "✓"; textSize = 40f; setTextColor(green); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })
    }

    private fun buildVideo(c: LinearLayout) {
        c.addView(title(R.string.onb_video_title), lp(bottom = 12))
        c.addView(label(R.string.onb_video_body, 26f, false, textMain, center = true), lp(bottom = 24))
        val start = button(context.getString(R.string.onb_btn_open_browser), primary = true) { state.advance(); render() }
        defaultFocus = start
        c.addView(start, lp())
    }

    // ---- small view helpers ----

    private fun dp(v: Int) = (v * d).toInt()

    private fun lp(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(top); bottomMargin = dp(bottom)
        }

    private fun leftLp(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.START
            topMargin = dp(top); bottomMargin = dp(bottom)
        }

    private fun title(res: Int) = label(res, 36f, true, textMain, center = true)

    private fun label(res: Int, sp: Float, bold: Boolean, color: Int, center: Boolean = false) =
        TextView(context).apply {
            setText(res)
            textSize = sp
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            if (center) gravity = Gravity.CENTER
            maxWidth = (screenW * 0.8f).toInt()
        }

    private fun button(text: String, primary: Boolean, onClick: () -> Unit): TextView {
        fun shape(fill: Int, strokeColor: Int, strokeDp: Int) = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(fill)
            setStroke(dp(strokeDp), strokeColor)
        }
        val normal = shape(if (primary) accent else panel, Color.TRANSPARENT, 3)
        val focused = shape(if (primary) accent else panel, Color.WHITE, 3)
        return TextView(context).apply {
            this.text = text
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textMain)
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(14), dp(28), dp(14))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), focused)
                addState(intArrayOf(), normal)
            }
            isFocusable = true
            isClickable = true
            contentDescription = text
            setOnClickListener { onClick() }
            buttons.add(this)
            setOnFocusChangeListener { v, has -> v.animate().scaleX(if (has) 1.08f else 1f).scaleY(if (has) 1.08f else 1f).setDuration(120L).start() }
        }
    }

    /** Glowing target circle for the CURSOR step; turns green with a check mark when done. */
    private class TargetView(context: Context, private val doneColor: Int) : View(context) {
        var done: Boolean = false
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Color.WHITE
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(width, height) / 2f
            val base = if (done) doneColor else 0xFF0A84FF.toInt()
            glow.shader = RadialGradient(cx, cy, r, intArrayOf(base and 0x00FFFFFF or 0x99000000.toInt(), base and 0x00FFFFFF), floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r, glow)
            ring.color = base
            ring.strokeWidth = r * 0.08f
            canvas.drawCircle(cx, cy, r * 0.62f, ring)
            if (done) {
                mark.strokeWidth = r * 0.12f
                canvas.drawLine(cx - r * 0.28f, cy, cx - r * 0.08f, cy + r * 0.22f, mark)
                canvas.drawLine(cx - r * 0.08f, cy + r * 0.22f, cx + r * 0.3f, cy - r * 0.22f, mark)
            }
        }
    }
}
