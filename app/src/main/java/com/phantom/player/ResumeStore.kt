package com.phantom.player

import android.content.SharedPreferences
import java.security.MessageDigest

object ResumeStore {
    const val PREFS_NAME = "phantom_resume"
    const val MAX_ENTRIES = 50
    const val MIN_RESUME_THRESHOLD_MS = 60_000L
    const val END_THRESHOLD_OFFSET_MS = 120_000L

    /**
     * Computes SHA-1 hash of pageUrl (without fragment).
     * mediaUrl is not included.
     */
    fun computeKey(pageUrl: String, mediaUrl: String = ""): String {
        val input = pageUrl.substringBefore('#')
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Resume condition: savedMs > 60_000 ms and < (durationMs - 120_000 ms).
     */
    fun shouldResume(savedMs: Long, durationMs: Long): Boolean {
        if (savedMs <= MIN_RESUME_THRESHOLD_MS) return false
        if (durationMs <= END_THRESHOLD_OFFSET_MS) return false
        return savedMs < (durationMs - END_THRESHOLD_OFFSET_MS)
    }

    /**
     * Clear condition: posMs >= (durationMs - 120_000 ms).
     */
    fun shouldClear(posMs: Long, durationMs: Long): Boolean {
        if (durationMs > END_THRESHOLD_OFFSET_MS && posMs >= (durationMs - END_THRESHOLD_OFFSET_MS)) {
            return true
        }
        return false
    }

    /**
     * Saves playback position into SharedPreferences.
     * If posMs >= durationMs - 120_000 ms, deletes the record.
     * Enforces the max 50 entries limit by deleting the oldest entry (by key + "_time").
     */
    fun savePosition(
        prefs: SharedPreferences,
        key: String,
        posMs: Long,
        durationMs: Long,
        timestamp: Long = System.currentTimeMillis()
    ) {
        if (shouldClear(posMs, durationMs)) {
            clearPosition(prefs, key)
            return
        }
        if (posMs <= 0L) return

        val editor = prefs.edit()
        val all = prefs.all
        val posKeys = all.keys.filter { !it.endsWith("_time") }

        if (!posKeys.contains(key) && posKeys.size >= MAX_ENTRIES) {
            val sortedKeys = posKeys.map { k ->
                val time = (all[k + "_time"] as? Number)?.toLong() ?: 0L
                k to time
            }.sortedBy { it.second }

            val toRemoveCount = posKeys.size - MAX_ENTRIES + 1
            for (i in 0 until toRemoveCount) {
                val oldKey = sortedKeys[i].first
                editor.remove(oldKey)
                editor.remove(oldKey + "_time")
            }
        }

        editor.putLong(key, posMs)
        editor.putLong(key + "_time", timestamp)
        editor.apply()
    }

    fun getPosition(prefs: SharedPreferences, key: String): Long {
        return prefs.getLong(key, 0L)
    }

    fun getResumePosition(prefs: SharedPreferences, key: String, durationMs: Long): Long? {
        val savedMs = getPosition(prefs, key)
        return if (shouldResume(savedMs, durationMs)) savedMs else null
    }

    fun clearPosition(prefs: SharedPreferences, key: String) {
        prefs.edit()
            .remove(key)
            .remove(key + "_time")
            .apply()
    }
}
