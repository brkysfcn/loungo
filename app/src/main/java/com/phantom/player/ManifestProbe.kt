package com.phantom.player

import java.net.HttpURLConnection
import java.net.URL

/**
 * Diagnostics for "playlist could not be parsed" errors: fetches the playlist again with the
 * player's own headers and describes what the server actually returned. For HLS it follows the
 * first variant too, since the player does not say which playlist failed. Runs off the main thread.
 */
object ManifestProbe {
    private const val PREVIEW_BYTES = 160

    fun describe(url: String, userAgent: String?, headers: Map<String, String>): String {
        val first = fetch(url, userAgent, headers)
        val sb = StringBuilder("PROBE master ").append(first.summary)
        val variant = first.body?.let { firstVariant(it, first.finalUrl) }
        if (variant != null) {
            sb.append(" | PROBE variant ").append(fetch(variant, userAgent, headers).summary)
        }
        return sb.toString()
    }

    private class Result(val summary: String, val body: String?, val finalUrl: String)

    private fun fetch(url: String, userAgent: String?, headers: Map<String, String>): Result {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
                if (!userAgent.isNullOrEmpty()) setRequestProperty("User-Agent", userAgent)
                for ((k, v) in headers) setRequestProperty(k, v)
            }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val bytes = stream?.use { readUpTo(it, 64 * 1024) } ?: ByteArray(0)
            val text = String(bytes, Charsets.UTF_8)
            val preview = text.take(PREVIEW_BYTES).replace("\r", "\\r").replace("\n", "\\n")
            val hex = bytes.take(8).joinToString(" ") { "%02x".format(it) }
            val finalUrl = conn.url.toString()
            val summary = "code=$code type=${conn.contentType} enc=${conn.contentEncoding} " +
                "len=${bytes.size} redirected=${finalUrl != url} host=${conn.url.host} " +
                "hex=[$hex] text=\"$preview\""
            Result(summary, text, finalUrl)
        } catch (t: Throwable) {
            Result("failed ${t.javaClass.simpleName}: ${t.message}", null, url)
        } finally {
            conn?.disconnect()
        }
    }

    private fun readUpTo(input: java.io.InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < limit) {
            val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun firstVariant(body: String, baseUrl: String): String? {
        if (!body.trimStart().startsWith("#EXTM3U") || !body.contains("#EXT-X-STREAM-INF")) return null
        val line = body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?: return null
        return try {
            URL(URL(baseUrl), line).toString()
        } catch (_: Throwable) {
            null
        }
    }
}
