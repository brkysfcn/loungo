package com.phantom.web

import com.phantom.player.QualityLabel

/**
 * Pages pick a video codec by asking the browser what it can play. WebView says "yes" to AV1 and
 * VP9 even on TVs that can only decode them in software, and a weak TV CPU cannot keep up with
 * 1080p in software: video freezes and the page stutters. This script hides the codecs that have
 * no hardware decoder on this TV, so pages fall back to one that does (usually H.264).
 * Generic: applies to every site, decided only by the TV's decoders.
 */
object CodecGate {
    /** Codec families a page may ask for: Android MIME type to the codec-string prefixes. */
    private val FAMILIES = linkedMapOf(
        "video/av01" to listOf("av01"),
        "video/x-vnd.on2.vp9" to listOf("vp09", "vp9"),
        "video/hevc" to listOf("hev1", "hvc1"),
    )

    /** Codec-string prefixes with no hardware decoder on this device. */
    fun blockedPrefixes(hasHardware: (String) -> Boolean = QualityLabel::hasHardwareDecoder): List<String> =
        FAMILIES.filterKeys { !hasHardware(it) }.values.flatten()

    /** Returns the script, or null when every family has a hardware decoder. */
    fun script(prefixes: List<String> = blockedPrefixes()): String? {
        if (prefixes.isEmpty()) return null
        val list = prefixes.joinToString(",") { "'" + it + "'" }
        return """
            (function(){
              if (window.__phantomCodecGate) return;
              window.__phantomCodecGate = true;
              var P = [$list];
              function blocked(t){
                if (typeof t !== 'string') return false;
                var m = /codecs\s*=\s*"?([^";]+)/i.exec(t);
                if (!m) return false;
                var cs = m[1].split(',');
                for (var i = 0; i < cs.length; i++) {
                  var c = cs[i].trim().toLowerCase();
                  for (var j = 0; j < P.length; j++) if (c.indexOf(P[j]) === 0) return true;
                }
                return false;
              }
              function wrapStatic(C){
                if (!C || !C.isTypeSupported) return;
                var o = C.isTypeSupported;
                C.isTypeSupported = function(t){ return blocked(t) ? false : o.call(C, t); };
              }
              wrapStatic(window.MediaSource);
              wrapStatic(window.ManagedMediaSource);
              wrapStatic(window.WebKitMediaSource);
              var E = window.HTMLMediaElement && HTMLMediaElement.prototype;
              if (E && E.canPlayType) {
                var cp = E.canPlayType;
                E.canPlayType = function(t){ return blocked(t) ? '' : cp.call(this, t); };
              }
              var mc = navigator.mediaCapabilities;
              if (mc && mc.decodingInfo) {
                var di = mc.decodingInfo.bind(mc);
                mc.decodingInfo = function(cfg){
                  var v = cfg && cfg.video;
                  if (v && blocked(v.contentType)) {
                    return Promise.resolve({ supported: false, smooth: false, powerEfficient: false });
                  }
                  return di(cfg);
                };
              }
            })();
        """.trimIndent()
    }
}
