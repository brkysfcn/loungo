package com.phantom.web

import android.app.ActivityManager
import android.content.Context

/**
 * "Light look": turns off CSS backdrop blur, which is very expensive on weak TV GPUs.
 * - AUTO (default): only on weak TVs, and only on a page that is actually stuttering.
 * - ON: on every page.  OFF: never.
 * Panels that relied on the blur get a near-opaque background in their own colour, so text
 * on them stays readable.
 */
object LiteMode {
    enum class Mode(val wire: String) { AUTO("auto"), ON("on"), OFF("off") }

    private const val PREFS = "phantom_lite"
    private const val KEY_MODE = "mode"
    private const val WEAK_TOTAL_MEM_BYTES = 3L * 1024 * 1024 * 1024

    fun fromWire(s: String?): Mode? = Mode.values().firstOrNull { it.wire == s }

    fun load(context: Context): Mode =
        fromWire(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, null)) ?: Mode.AUTO

    fun save(context: Context, mode: Mode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODE, mode.wire).apply()
    }

    fun isWeakDevice(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (am.isLowRamDevice) return true
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem in 1 until WEAK_TOTAL_MEM_BYTES
    }

    /** Returns the number of blurred elements found (they are all un-blurred). Runs once per document. */
    val SCRIPT = """
        (function(){
          if (window.__phantomLite !== undefined) return window.__phantomLite;
          var n = 0, els = document.querySelectorAll('*');
          function rgba(s){ var m = /rgba?\(([^)]+)\)/.exec(s || ''); if (!m) return null;
            var p = m[1].split(',').map(function(x){ return parseFloat(x); });
            return { r: p[0], g: p[1], b: p[2], a: p.length > 3 ? p[3] : 1 }; }
          for (var i = 0; i < els.length; i++) {
            var cs = getComputedStyle(els[i]);
            var bf = cs.backdropFilter || cs.webkitBackdropFilter;
            if (!bf || bf === 'none') continue;
            n++;
            var bg = rgba(cs.backgroundColor);
            if (bg && bg.a >= 0.75) continue;
            // Keep the panel's own colour only when the text stays readable on it; otherwise
            // pick dark or light by the text colour.
            var fg = rgba(cs.color) || { r: 255, g: 255, b: 255 };
            var lum = function(c){ return (c.r * 299 + c.g * 587 + c.b * 114) / 1000; };
            var fill;
            if (bg && bg.a > 0 && Math.abs(lum(bg) - lum(fg)) > 110) {
              fill = 'rgba(' + bg.r + ',' + bg.g + ',' + bg.b + ',0.92)';
            } else {
              fill = lum(fg) > 128 ? 'rgba(22,22,26,0.92)' : 'rgba(245,245,247,0.94)';
            }
            els[i].style.setProperty('background-color', fill, 'important');
          }
          var st = document.createElement('style');
          st.id = '__phantom_lite';
          st.textContent = '*,*::before,*::after{backdrop-filter:none!important;-webkit-backdrop-filter:none!important}';
          (document.head || document.documentElement).appendChild(st);
          window.__phantomLite = n;
          return n;
        })()
    """.trimIndent()
}

/**
 * Decides whether the page is stuttering from frame durations. Janky = most frames in the last
 * [windowMs] took longer than [slowFrameMs], with at least [minFrames] frames to judge from.
 * Idle pages draw no frames, so they never count as janky.
 */
class JankMeter(
    private val windowMs: Long = 3000L,
    private val slowFrameMs: Double = 34.0,
    private val minFrames: Int = 20,
    private val slowShare: Double = 0.5,
) {
    private val times = ArrayDeque<Long>()
    private val slow = ArrayDeque<Boolean>()
    private var slowCount = 0

    fun reset() {
        times.clear()
        slow.clear()
        slowCount = 0
    }

    /** Records one frame; returns true when the page counts as janky. */
    fun record(frameMs: Double, nowMs: Long): Boolean {
        times.addLast(nowMs)
        val isSlow = frameMs > slowFrameMs
        slow.addLast(isSlow)
        if (isSlow) slowCount++
        while (times.isNotEmpty() && nowMs - times.first() > windowMs) {
            times.removeFirst()
            if (slow.removeFirst()) slowCount--
        }
        return times.size >= minFrames && slowCount >= times.size * slowShare
    }
}
