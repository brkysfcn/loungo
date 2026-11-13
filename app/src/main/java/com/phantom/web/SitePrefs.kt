package com.phantom.web

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

data class SitePref(val ua: String = "mobile", val zoom: Int = 100, val adblock: Boolean = true)

class SitePrefs(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.getSharedPreferences("phantom_site_prefs", Context.MODE_PRIVATE)
    )

    fun getPref(host: String): SitePref {
        val cleanHost = host.lowercase().trim()
        if (cleanHost.isEmpty()) return SitePref()
        val jsonStr = prefs.getString(cleanHost, null) ?: return SitePref()
        return try {
            val json = JSONObject(jsonStr)
            val ua = json.optString("ua", "mobile")
            val zoom = json.optInt("zoom", 100)
            val adblock = json.optBoolean("adblock", true)
            SitePref(
                if (ua == "desktop") "desktop" else "mobile",
                zoom.coerceIn(50, 300),
                adblock
            )
        } catch (e: Exception) {
            SitePref()
        }
    }

    fun setUa(host: String, ua: String): SitePref {
        val cleanHost = host.lowercase().trim()
        if (cleanHost.isEmpty()) return SitePref()
        val current = getPref(cleanHost)
        val validUa = if (ua == "desktop") "desktop" else "mobile"
        val updated = current.copy(ua = validUa)
        savePref(cleanHost, updated)
        return updated
    }

    fun setZoom(host: String, zoom: Int): SitePref {
        val cleanHost = host.lowercase().trim()
        if (cleanHost.isEmpty()) return SitePref()
        val current = getPref(cleanHost)
        val clampedZoom = zoom.coerceIn(50, 300)
        val updated = current.copy(zoom = clampedZoom)
        savePref(cleanHost, updated)
        return updated
    }

    fun setAdblock(host: String, on: Boolean): SitePref {
        val cleanHost = host.lowercase().trim()
        if (cleanHost.isEmpty()) return SitePref()
        val current = getPref(cleanHost)
        val updated = current.copy(adblock = on)
        savePref(cleanHost, updated)
        return updated
    }

    private fun savePref(cleanHost: String, pref: SitePref) {
        val json = JSONObject().apply {
            put("ua", pref.ua)
            put("zoom", pref.zoom)
            put("adblock", pref.adblock)
        }
        prefs.edit().putString(cleanHost, json.toString()).apply()
    }
}
