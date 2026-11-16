package com.phantom.web

import android.net.Uri
import org.json.JSONObject

data class BridgeMediaMessage(
    val url: String,
    val kind: String,
    val frameOrigin: String? = null,
    val frameUrl: String? = null
)

/**
 * Validates a bridge message from JavaScript.
 * Ensures security: only t="media" is accepted, and url must start with http:// or https://.
 */
fun parseAndValidateBridgeMedia(jsonStr: String): BridgeMediaMessage? {
    return try {
        val json = JSONObject(jsonStr)
        if (json.optString("t") != "media") return null
        val url = json.optString("url")
        if (url.isEmpty()) return null
        val lowerUrl = url.lowercase()
        if (!lowerUrl.startsWith("http://") && !lowerUrl.startsWith("https://")) return null
        val kind = json.optString("kind", "hls").ifEmpty { "hls" }
        val frameOrigin = if (json.has("frameOrigin") && !json.isNull("frameOrigin")) json.optString("frameOrigin").ifEmpty { null } else null
        val frameUrl = if (json.has("frameUrl") && !json.isNull("frameUrl")) json.optString("frameUrl").ifEmpty { null } else null
        BridgeMediaMessage(url, kind, frameOrigin, frameUrl)
    } catch (e: Exception) {
        null
    }
}

data class SubtitleRecord(
    val url: String,
    val lang: String,
    val referer: String? = null
)

data class MediaRecord(
    val url: String,
    val kind: String,
    val referer: String? = null,
    val origin: String? = null,
    val pageUrl: String? = null,
    val subtitles: MutableList<SubtitleRecord> = java.util.concurrent.CopyOnWriteArrayList()
)

/**
 * Extracts language code (e.g. "tr", "en", "es", "fr", "de", "pt", "it", "ru")
 * from subtitle file name, or returns "?" if undetermined.
 */
fun extractSubtitleLang(url: String): String {
    val clean = url.substringBefore('?').substringBefore('#')
    val fileName = clean.substringAfterLast('/').lowercase()
    val delimiters = charArrayOf('-', '_', '.')
    val tokens = fileName.split(*delimiters)

    for (t in tokens) {
        when (t) {
            "tur", "tr", "turkish", "turkce" -> return "tr"
            "eng", "en", "english" -> return "en"
            "spa", "es", "spanish" -> return "es"
            "fre", "fra", "fr", "french" -> return "fr"
            "ger", "deu", "de", "german" -> return "de"
            "por", "pt", "portuguese" -> return "pt"
            "ita", "it", "italian" -> return "it"
            "rus", "ru", "russian" -> return "ru"
            "ara", "ar", "arabic" -> return "ar"
        }
    }

    return when {
        fileName.contains("tur") || fileName.contains("turk") -> "tr"
        fileName.contains("eng") -> "en"
        fileName.contains("spa") -> "es"
        fileName.contains("fre") || fileName.contains("fra") -> "fr"
        fileName.contains("ger") || fileName.contains("deu") -> "de"
        fileName.contains("por") -> "pt"
        fileName.contains("ita") -> "it"
        fileName.contains("rus") -> "ru"
        else -> "?"
    }
}

/**
 * Checks whether a URL is a subtitle request (.vtt, .srt, .ass, or path contains /vtt/).
 */
fun isSubtitleUrl(url: String): Boolean {
    if (url.isEmpty()) return false
    val clean = url.substringBefore('?').substringBefore('#').lowercase()
    return clean.endsWith(".vtt") || clean.endsWith(".srt") || clean.endsWith(".ass") || clean.contains("/vtt/")
}

/**
 * Returns media kind ("hls", "dash", "mp4") if the URL represents a playable media stream/file,
 * or null if it's not media or if it's a segment file (.ts, .m4s).
 */
fun kindOf(url: String): String? {
    if (url.isEmpty()) return null

    val cleanUrl = url.substringBefore('?').substringBefore('#')
    val path = try {
        val uri = Uri.parse(cleanUrl)
        uri.path ?: cleanUrl
    } catch (e: Exception) {
        cleanUrl
    }

    val lower = path.lowercase()

    // Exclude segments
    if (lower.endsWith(".ts") || lower.endsWith(".m4s") || lower.endsWith(".m4a") || lower.endsWith(".aac")) {
        return null
    }

    return when {
        lower.endsWith(".m3u8") -> "hls"
        lower.endsWith(".mpd") -> "dash"
        lower.endsWith(".mp4") -> "mp4"
        lower.endsWith(".txt") && (lower.contains("/hls/") || lower.contains("master") || lower.contains("playlist")) -> "hls"
        else -> null
    }
}

class MediaDetector(private val onMediaFound: (record: MediaRecord) -> Unit) {
    private val reportedUrls = java.util.concurrent.ConcurrentHashMap<String, MediaRecord>()
    private val pendingSubtitles = java.util.concurrent.CopyOnWriteArrayList<SubtitleRecord>()

    fun reset() {
        reportedUrls.clear()
        pendingSubtitles.clear()
        hasMaster = false
        hasManifest = false
    }

    // Once a master playlist (#EXT-X-STREAM-INF) is known for the page, its variant and
    // audio-only sub-playlists must not replace it: opening one gives sound without picture.
    @Volatile private var hasMaster = false

    // Once a manifest (HLS/DASH) is known for the page, a plain MP4 seen afterwards is almost
    // always a teaser, an ad or a placeholder clip and must not replace the film.
    @Volatile private var hasManifest = false

    private fun announce(record: MediaRecord, isMaster: Boolean) {
        if (isMaster) hasMaster = true
        else if (hasMaster && record.kind == "hls") return
        if (record.kind == "hls" || record.kind == "dash") hasManifest = true
        else if (hasManifest && record.kind == "mp4") return
        onMediaFound(record)
    }

    fun inspectSubtitle(
        url: String,
        headers: Map<String, String>? = null
    ): SubtitleRecord? {
        if (!isSubtitleUrl(url)) return null
        val lang = extractSubtitleLang(url)
        val referer = headers?.entries?.firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value
        val sub = SubtitleRecord(url, lang, referer)
        if (pendingSubtitles.none { it.url == url }) {
            pendingSubtitles.add(sub)
            reportedUrls.values.forEach { media ->
                if (media.subtitles.none { it.url == url }) {
                    media.subtitles.add(sub)
                }
            }
        }
        return sub
    }

    fun inspectRequest(
        url: String,
        headers: Map<String, String>? = null,
        pageUrl: String? = null
    ): MediaRecord? {
        if (isSubtitleUrl(url)) {
            inspectSubtitle(url, headers)
            return null
        }
        val kind = kindOf(url) ?: return null
        val referer = headers?.entries?.firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value
        val origin = headers?.entries?.firstOrNull { it.key.equals("Origin", ignoreCase = true) }?.value
        val record = MediaRecord(
            url = url,
            kind = kind,
            referer = referer,
            origin = origin,
            pageUrl = pageUrl,
            subtitles = java.util.concurrent.CopyOnWriteArrayList(pendingSubtitles)
        )
        val existing = reportedUrls.putIfAbsent(url, record)
        if (existing == null) {
            announce(record, isMaster = false)
            return record
        }
        return existing
    }

    fun inspectUrl(url: String) {
        inspectRequest(url, null, null)
    }

    /**
     * Registers a media stream detected content-based via JS bridge (T42b).
     * Uses frameUrl as Referer and frameOrigin as Origin, falling back to network headers if empty.
     * Deduplicates per page/session.
     */
    fun registerMedia(
        url: String,
        kind: String,
        frameReferer: String? = null,
        frameOrigin: String? = null,
        pageUrl: String? = null
    ): MediaRecord? {
        val lowerUrl = url.lowercase()
        if (!lowerUrl.startsWith("http://") && !lowerUrl.startsWith("https://")) return null

        val existingReq = reportedUrls[url]
        val referer = frameReferer?.ifEmpty { null } ?: existingReq?.referer
        val origin = frameOrigin?.ifEmpty { null } ?: existingReq?.origin
        val isMaster = kind == "hls-master"
        val record = MediaRecord(
            url = url,
            kind = if (isMaster) "hls" else kind,
            referer = referer,
            origin = origin,
            pageUrl = pageUrl ?: existingReq?.pageUrl,
            subtitles = java.util.concurrent.CopyOnWriteArrayList(existingReq?.subtitles ?: pendingSubtitles)
        )
        val existing = reportedUrls.putIfAbsent(url, record)
        if (existing == null) {
            announce(record, isMaster)
            return record
        }
        // Already seen by URL (before its body proved it a master): announce it again so it
        // replaces any sub-playlist announced in between.
        if (isMaster && !hasMaster) announce(existing, isMaster = true)
        return existing
    }

    fun getRecord(url: String): MediaRecord? = reportedUrls[url]

    // Request headers the page itself sent for recent URLs, so the native player can repeat the
    // request the way the browser made it (a .txt playlist is only known to be media later).
    private val recentHeaders = object : LinkedHashMap<String, Map<String, String>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, String>>?) = size > 64
    }

    fun rememberHeaders(url: String, headers: Map<String, String>?) {
        if (headers.isNullOrEmpty()) return
        synchronized(recentHeaders) { recentHeaders[url] = HashMap(headers) }
    }

    fun headersFor(url: String): Map<String, String>? = synchronized(recentHeaders) { recentHeaders[url] }

    fun getSubtitles(): List<SubtitleRecord> = pendingSubtitles.toList()
}

/**
 * Resolves the subtitle list for native player intent.
 * 1. If record.subtitles is not empty, returns record.subtitles.
 * 2. If record.subtitles is empty, but the media was detected on the current page
 *    (record != null and record.pageUrl matches currentPageUrl, or neither is specified),
 *    falls back to detectorSubtitles.
 * 3. If direct media_open (record == null / no page) or page mismatch, returns emptyList().
 */
fun resolveSubtitles(
    record: MediaRecord?,
    detectorSubtitles: List<SubtitleRecord>,
    currentPageUrl: String?
): List<SubtitleRecord> {
    if (!record?.subtitles.isNullOrEmpty()) {
        return record!!.subtitles
    }
    if (record != null) {
        val samePage = record.pageUrl.isNullOrEmpty() || currentPageUrl.isNullOrEmpty() || record.pageUrl == currentPageUrl
        if (samePage && detectorSubtitles.isNotEmpty()) {
            return detectorSubtitles
        }
    }
    return emptyList()
}
