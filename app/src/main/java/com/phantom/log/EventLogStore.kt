package com.phantom.log

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object EventLogStore {
    const val MAX_SIZE = 512 * 1024 // 512 KB
    const val TRIM_SIZE = 256 * 1024 // 256 KB
    const val LOG_FILE_NAME = "event_log.txt"

    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private val ALLOWED_PREFIXES = arrayOf(
        "PAGE_FINISHED",
        "MEDIA",
        "PLAYER",
        "TRACK",
        "NAV_BLOCKED",
        "JS_ERR",
        "RENDER_GONE",
        "CRASH",
        "APP_START"
    )

    fun init(context: Context) {
        synchronized(this) {
            logFile = File(context.filesDir, LOG_FILE_NAME)
        }
    }

    fun init(dir: File) {
        synchronized(this) {
            logFile = File(dir, LOG_FILE_NAME)
        }
    }

    fun shouldLog(tag: String, message: String): Boolean {
        if (message.startsWith("REQ ") || message.startsWith("REQ_")) return false
        for (prefix in ALLOWED_PREFIXES) {
            if (message.startsWith(prefix) || tag == prefix || tag.startsWith(prefix)) {
                return true
            }
        }
        return false
    }

    fun log(tag: String, message: String) {
        if (!shouldLog(tag, message)) return
        val timestamp = synchronized(dateFormat) { dateFormat.format(Date()) }
        val line = "[$timestamp] $tag: $message\n"
        write(line)
    }

    fun i(message: String) {
        android.util.Log.i("PHANTOM", message)
        log("PHANTOM", message)
    }

    fun e(message: String, tr: Throwable? = null) {
        if (tr != null) {
            android.util.Log.e("PHANTOM", message, tr)
            log("PHANTOM", "$message: ${tr.message}")
        } else {
            android.util.Log.e("PHANTOM", message)
            log("PHANTOM", message)
        }
    }

    @Synchronized
    private fun write(line: String) {
        val file = logFile ?: return
        try {
            val bytes = line.toByteArray(Charsets.UTF_8)
            if (file.exists() && file.length() + bytes.size > MAX_SIZE) {
                trimFile(file)
            }
            file.appendText(line, Charsets.UTF_8)
        } catch (_: Exception) {
            // Ignore write failures to protect main app flow
        }
    }

    private fun trimFile(file: File) {
        try {
            val len = file.length()
            if (len <= TRIM_SIZE) return
            val skip = len - TRIM_SIZE
            val raf = RandomAccessFile(file, "r")
            raf.seek(skip)
            // Skip partial line
            raf.readLine()
            val remainingLen = (file.length() - raf.filePointer).toInt()
            if (remainingLen > 0) {
                val remaining = ByteArray(remainingLen)
                raf.readFully(remaining)
                raf.close()
                file.writeBytes(remaining)
            } else {
                raf.close()
                file.delete()
            }
        } catch (_: Exception) {
            file.delete()
        }
    }

    @Synchronized
    fun readAll(): String {
        val file = logFile ?: return ""
        return if (file.exists()) file.readText(Charsets.UTF_8) else ""
    }

    @Synchronized
    fun readLines(limit: Int = 1000): List<String> {
        val file = logFile ?: return emptyList()
        if (!file.exists()) return emptyList()
        val all = file.readLines(Charsets.UTF_8)
        return if (all.size > limit) all.takeLast(limit) else all
    }

    @Synchronized
    fun clear() {
        logFile?.delete()
    }
}
