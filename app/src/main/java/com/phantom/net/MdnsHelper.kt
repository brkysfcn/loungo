package com.phantom.net

import android.content.Context
import android.content.SharedPreferences
import kotlin.random.Random

object MdnsHelper {
    const val PREFS_NAME = "phantom_mdns"
    const val KEY_MDNS_ID = "mdns_id"

    /**
     * Retrieves existing 4-digit ID or creates a persistent one in SharedPreferences.
     */
    fun getOrCreatePersistentId(prefs: SharedPreferences): String {
        val existing = prefs.getString(KEY_MDNS_ID, null)
        if (!existing.isNullOrEmpty() && existing.length == 4 && existing.all { it.isDigit() }) {
            return existing
        }
        val newId = (Random.nextInt(9000) + 1000).toString()
        prefs.edit().putString(KEY_MDNS_ID, newId).apply()
        return newId
    }

    fun getOrCreatePersistentId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return getOrCreatePersistentId(prefs)
    }

    /**
     * Backward-compatible deterministic 4-digit ID from seed.
     */
    fun generate4DigitId(token: String): String {
        if (token.isEmpty()) return "1000"
        val hash = kotlin.math.abs(token.hashCode())
        val id = (hash % 9000) + 1000
        return id.toString()
    }

    /**
     * Constructs the mDNS hostname: loungo-<4digit>.local
     */
    fun getHostname(id: String): String = "loungo-$id.local"

    /**
     * Pre-rename hostname (phantom-<4digit>.local). Still answered so phones that saved
     * the old address keep working.
     */
    fun getLegacyHostname(id: String): String = "phantom-$id.local"

    /** [hostname] plus its legacy twin when it is a loungo-XXXX.local name. */
    fun allHostnames(hostname: String): List<String> {
        val legacy = if (hostname.startsWith("loungo-")) "phantom-" + hostname.removePrefix("loungo-") else null
        return listOfNotNull(hostname, legacy)
    }

    /**
     * Builds the pairing URL with primary mDNS host and fallback IP parameter.
     */
    fun buildPairUrl(hostname: String, ip: String, port: Int, token: String): String {
        return "http://$hostname:$port/?k=$token&ip=$ip"
    }

    /**
     * Selects target host/URL: returns primary URL, or fallback IP URL if mdns fails.
     */
    fun selectUrl(pairUrl: String, useFallback: Boolean): String {
        if (!useFallback) return pairUrl
        val uri = java.net.URI(pairUrl)
        val query = uri.query ?: ""
        val params = query.split("&").associate {
            val parts = it.split("=", limit = 2)
            parts[0] to (parts.getOrNull(1) ?: "")
        }
        val ip = params["ip"] ?: return pairUrl
        val token = params["k"] ?: ""
        val port = if (uri.port > 0) uri.port else 8765
        return "http://$ip:$port/?k=$token"
    }
}
