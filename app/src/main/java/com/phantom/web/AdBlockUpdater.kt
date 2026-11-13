package com.phantom.web

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object AdBlockUpdater {
    const val HOSTS_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
    private const val PREFS_NAME = "phantom_adblock"
    private const val KEY_LAST_UPDATE = "last_update_millis"
    private const val SEVEN_DAYS_MS = 7L * 24 * 3600 * 1000L

    @Volatile
    private var isUpdating = false

    fun checkAndTriggerUpdate(context: Context, adBlocker: AdBlocker?, force: Boolean = false) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastUpdate = prefs.getLong(KEY_LAST_UPDATE, 0L)
        val now = System.currentTimeMillis()

        if (!force && (now - lastUpdate < SEVEN_DAYS_MS)) {
            return
        }

        synchronized(this) {
            if (isUpdating) return
            isUpdating = true
        }

        Thread {
            val t0 = SystemClock.elapsedRealtime()
            try {
                val url = URL(HOSTS_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.requestMethod = "GET"

                val responseCode = conn.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    Log.w("PHANTOM", "ADBLOCK_UPDATE fail http_$responseCode")
                    return@Thread
                }

                val hashes = conn.inputStream.bufferedReader().use { reader ->
                    AdBlocker.parseHosts(reader)
                }

                if (hashes.size < 10_000) {
                    Log.w("PHANTOM", "ADBLOCK_UPDATE fail count_${hashes.size}")
                    return@Thread
                }

                val targetFile = File(context.filesDir, "adblock.bin")
                val tmpFile = File(context.filesDir, "adblock.bin.tmp")

                FileOutputStream(tmpFile).use { fos ->
                    AdBlocker.writeBinary(hashes, fos)
                }

                if (!tmpFile.renameTo(targetFile)) {
                    // Fallback if atomic rename fails
                    tmpFile.copyTo(targetFile, overwrite = true)
                    tmpFile.delete()
                }

                adBlocker?.updateHashes(hashes)

                prefs.edit().putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply()
                val duration = SystemClock.elapsedRealtime() - t0
                Log.i("PHANTOM", "ADBLOCK_UPDATE ok n=${hashes.size} ms=$duration")
            } catch (e: Exception) {
                Log.e("PHANTOM", "ADBLOCK_UPDATE fail ${e.message}")
            } finally {
                synchronized(this) {
                    isUpdating = false
                }
            }
        }.start()
    }
}
