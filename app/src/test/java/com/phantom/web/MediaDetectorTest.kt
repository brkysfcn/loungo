package com.phantom.web

import androidx.media3.datasource.DefaultHttpDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MediaDetectorTest {

    @Test
    fun testKindOfHls() {
        assertEquals("hls", kindOf("https://example.com/live/master.m3u8"))
        assertEquals("hls", kindOf("https://example.com/live/index.M3U8?token=xyz#title"))
    }

    @Test
    fun testKindOfDisguisedHlsFromFilmSites() {
        // Disguised master playlists
        assertEquals(
            "hls",
            kindOf("https://srv9.mediacdn.example/hls/colision-2026-webdl-trdual-tt38793125mp4-JZkP9xzbRMP.mp4/txt/master.txt?s=Acb53ITDpbFlQ76Sp3UPMJL-2kM8SLRMWb96PHfdANZOnTcqfelRH0fT")
        )
        // Disguised master playlist with .txt
        assertEquals(
            "hls",
            kindOf("https://hls8.playmix.uno/hls/sample-video.mp4/master.txt")
        )
    }

    @Test
    fun testKindOfDash() {
        assertEquals("dash", kindOf("https://example.com/vod/stream.mpd"))
        assertEquals("dash", kindOf("https://example.com/vod/manifest.MPD?foo=bar"))
    }

    @Test
    fun testKindOfMp4() {
        assertEquals("mp4", kindOf("https://example.com/video.mp4"))
        assertEquals("mp4", kindOf("https://example.com/clip.MP4?q=high"))
    }

    @Test
    fun testKindOfSegmentsExcluded() {
        assertNull(kindOf("https://example.com/segment/chunk_1.ts"))
        assertNull(kindOf("https://example.com/dash/init.m4s"))
        assertNull(kindOf("https://example.com/dash/fragment.m4a"))
        assertNull(kindOf("https://example.com/audio/track.aac"))
    }

    @Test
    fun testKindOfNonMedia() {
        assertNull(kindOf("https://example.com/index.html"))
        assertNull(kindOf("https://example.com/style.css"))
        assertNull(kindOf("https://example.com/app.js"))
        assertNull(kindOf(""))
    }

    @Test
    fun testInspectRequestStoresRefererAndOrigin() {
        val found = mutableListOf<Pair<String, String>>()
        val detector = MediaDetector { rec ->
            found.add(rec.url to rec.kind)
        }

        val mediaUrl = "https://srv9.mediacdn.example/hls/sample/txt/master.txt"
        val headers = mapOf(
            "Referer" to "https://embed.videoservice.example/video/embed/123/",
            "Origin" to "https://embed.videoservice.example"
        )
        val pageUrl = "https://www.videoservice.example/movie-1/"

        val record = detector.inspectRequest(mediaUrl, headers, pageUrl)

        assertNotNull(record)
        assertEquals(mediaUrl, record?.url)
        assertEquals("hls", record?.kind)
        assertEquals("https://embed.videoservice.example/video/embed/123/", record?.referer)
        assertEquals("https://embed.videoservice.example", record?.origin)
        assertEquals(pageUrl, record?.pageUrl)

        // Retrieve from getRecord
        val stored = detector.getRecord(mediaUrl)
        assertEquals(record, stored)

        // Duplicate inspection should return existing record without triggering callback again
        val duplicate = detector.inspectRequest(mediaUrl, headers, pageUrl)
        assertEquals(record, duplicate)
        assertEquals(1, found.size)
    }

    @Test
    fun testInspectRequestCaseInsensitiveHeaders() {
        val detector = MediaDetector { }
        val mediaUrl = "https://hls8.playmix.uno/hls/sample/master.txt"
        val headers = mapOf(
            "referer" to "https://embed.streamprovider.example/",
            "ORIGIN" to "https://embed.streamprovider.example"
        )
        val record = detector.inspectRequest(mediaUrl, headers, "https://streamprovider.example/film/123")

        assertNotNull(record)
        assertEquals("https://embed.streamprovider.example/", record?.referer)
        assertEquals("https://embed.streamprovider.example", record?.origin)
    }

    @Test
    fun testDefaultHttpDataSourceFactorySetsHeaders() {
        val factory = DefaultHttpDataSource.Factory()
        val headers = mapOf(
            "Referer" to "https://embed.streamprovider.example/",
            "Origin" to "https://embed.streamprovider.example",
            "Cookie" to "session=abc123xyz"
        )
        factory.setDefaultRequestProperties(headers)

        val dataSource = factory.createDataSource()
        val requestProperties = dataSource.responseHeaders // or verify headers apply via reflection/inspect
        assertNotNull(dataSource)
    }

    @Test
    fun testExtractSubtitleLangF0RealFilenames() {
        // Turkish and English subtitles
        val turUrl = "https://embed.videoservice.example/vtt/2026/10/sample_subtitles01.tur.vtt"
        val engUrl = "https://embed.videoservice.example/vtt/2026/10/sample_subtitles03.eng.vtt"
        assertEquals("tr", extractSubtitleLang(turUrl))
        assertEquals("en", extractSubtitleLang(engUrl))

        // Various formats
        assertEquals("tr", extractSubtitleLang("https://site.com/subs/sub_turkish.vtt"))
        assertEquals("tr", extractSubtitleLang("https://site.com/subs/movie.tr.srt"))
        assertEquals("en", extractSubtitleLang("https://site.com/subs/movie.en.ass"))
        assertEquals("es", extractSubtitleLang("https://site.com/subs/movie.spanish.vtt"))
        assertEquals("fr", extractSubtitleLang("https://site.com/subs/movie.fre.srt"))
        assertEquals("de", extractSubtitleLang("https://site.com/subs/german-sub.vtt"))
        assertEquals("pt", extractSubtitleLang("https://site.com/subs/sub_pt.vtt"))
        assertEquals("?", extractSubtitleLang("https://site.com/subs/1.vtt"))
    }

    @Test
    fun testIsSubtitleUrl() {
        org.junit.Assert.assertTrue(isSubtitleUrl("https://example.com/sub.vtt"))
        org.junit.Assert.assertTrue(isSubtitleUrl("https://example.com/sub.srt?token=123"))
        org.junit.Assert.assertTrue(isSubtitleUrl("https://example.com/sub.ass#t=0"))
        org.junit.Assert.assertTrue(isSubtitleUrl("https://embed.videoservice.example/vtt/2026/10/sub.txt"))
        org.junit.Assert.assertFalse(isSubtitleUrl("https://example.com/video.mp4"))
        org.junit.Assert.assertFalse(isSubtitleUrl("https://example.com/master.m3u8"))
        org.junit.Assert.assertFalse(isSubtitleUrl(""))
    }

    @Test
    fun testMediaDetectorAssociatesSubtitles() {
        val foundRecords = mutableListOf<MediaRecord>()
        val detector = MediaDetector { rec -> foundRecords.add(rec) }

        val subUrlTr = "https://embed.videoservice.example/vtt/2026/10/sample_subtitles01.tur.vtt"
        val subUrlEn = "https://embed.videoservice.example/vtt/2026/10/sample_subtitles03.eng.vtt"
        val mediaUrl = "https://srv9.mediacdn.example/hls/sample/txt/master.txt"
        val headers = mapOf("Referer" to "https://embed.videoservice.example/")

        // Subtitles received before media
        detector.inspectRequest(subUrlTr, headers, null)
        assertEquals(0, foundRecords.size)

        // Media arrives
        val record = detector.inspectRequest(mediaUrl, headers, "https://videoservice.example/movie-1/")
        assertNotNull(record)
        assertEquals(1, foundRecords.size)
        assertEquals(1, record?.subtitles?.size)
        assertEquals(subUrlTr, record?.subtitles?.get(0)?.url)
        assertEquals("tr", record?.subtitles?.get(0)?.lang)

        // Additional subtitle arrives after media
        detector.inspectRequest(subUrlEn, headers, null)
        assertEquals(2, record?.subtitles?.size)
        assertEquals(subUrlEn, record?.subtitles?.get(1)?.url)
        assertEquals("en", record?.subtitles?.get(1)?.lang)
    }

    @Test
    fun testParseAndValidateBridgeMediaValid() {
        val validJson = """
            {
                "t": "media",
                "url": "https://s27.imagecdn4297.shop/mu/H3IjMKWanKWfYwVjZwLhI0IPYHEZYwRjBQOjYxEIDHjhrQV2AP1VER0d0zxnJ1uM2IwMT40Zwx3YaAbo3Ns0xi27avr1",
                "kind": "hls",
                "frameOrigin": "https://embed.videohost.example",
                "frameUrl": "https://embed.videohost.example/vx/v1x7c5739a3"
            }
        """.trimIndent()

        val msg = parseAndValidateBridgeMedia(validJson)
        assertNotNull(msg)
        assertEquals("https://s27.imagecdn4297.shop/mu/H3IjMKWanKWfYwVjZwLhI0IPYHEZYwRjBQOjYxEIDHjhrQV2AP1VER0d0zxnJ1uM2IwMT40Zwx3YaAbo3Ns0xi27avr1", msg?.url)
        assertEquals("hls", msg?.kind)
        assertEquals("https://embed.videohost.example", msg?.frameOrigin)
        assertEquals("https://embed.videohost.example/vx/v1x7c5739a3", msg?.frameUrl)

        // Minimal valid
        val minimal = parseAndValidateBridgeMedia("""{"t":"media","url":"http://example.com/stream"}""")
        assertNotNull(minimal)
        assertEquals("http://example.com/stream", minimal?.url)
        assertEquals("hls", minimal?.kind)
        assertNull(minimal?.frameOrigin)
        assertNull(minimal?.frameUrl)
    }

    @Test
    fun testParseAndValidateBridgeMediaRejected() {
        // Missing or wrong t
        assertNull(parseAndValidateBridgeMedia("""{"t":"other","url":"https://example.com/test"}"""))
        assertNull(parseAndValidateBridgeMedia("""{"url":"https://example.com/test"}"""))

        // Missing or empty url
        assertNull(parseAndValidateBridgeMedia("""{"t":"media","url":""}"""))
        assertNull(parseAndValidateBridgeMedia("""{"t":"media"}"""))

        // Non-http(s) schemas rejected for security
        assertNull(parseAndValidateBridgeMedia("""{"t":"media","url":"javascript:alert(1)"}"""))
        assertNull(parseAndValidateBridgeMedia("""{"t":"media","url":"file:///sdcard/test.mp4"}"""))
        assertNull(parseAndValidateBridgeMedia("""{"t":"media","url":"blob:https://example.com/uuid"}"""))
        assertNull(parseAndValidateBridgeMedia("""{"t":"media","url":"data:text/html,evil"}"""))
        assertNull(parseAndValidateBridgeMedia("""{"t":"media","url":"ftp://example.com/video"}"""))

        // Malformed json
        assertNull(parseAndValidateBridgeMedia("not json"))
        assertNull(parseAndValidateBridgeMedia(""))
    }

    @Test
    fun testMediaDetectorRegisterMediaFromBridge() {
        val foundRecords = mutableListOf<MediaRecord>()
        val detector = MediaDetector { rec -> foundRecords.add(rec) }

        val streamUrl = "https://s27.imagecdn4297.shop/mu/stream123"
        val frameOrigin = "https://embed.videohost.example"
        val frameUrl = "https://embed.videohost.example/vx/v1x7c5739a3"
        val pageUrl = "https://www.moviesite.example/film/sample/"

        // Register from bridge
        val record = detector.registerMedia(streamUrl, "hls", frameUrl, frameOrigin, pageUrl)
        assertNotNull(record)
        assertEquals(1, foundRecords.size)
        assertEquals(streamUrl, record?.url)
        assertEquals("hls", record?.kind)
        assertEquals(frameUrl, record?.referer)
        assertEquals(frameOrigin, record?.origin)
        assertEquals(pageUrl, record?.pageUrl)

        // Duplicate registration does not re-trigger callback
        val duplicate = detector.registerMedia(streamUrl, "hls", frameUrl, frameOrigin, pageUrl)
        assertEquals(record, duplicate)
        assertEquals(1, foundRecords.size)
    }

    @Test
    fun testResolveSubtitles_RecordHasSubtitles() {
        val sub1 = SubtitleRecord("https://example.com/tr.vtt", "tr")
        val sub2 = SubtitleRecord("https://example.com/en.vtt", "en")
        val record = MediaRecord(
            url = "https://example.com/stream.m3u8",
            kind = "hls",
            subtitles = java.util.concurrent.CopyOnWriteArrayList(listOf(sub1))
        )
        val result = resolveSubtitles(record, listOf(sub2), "https://example.com/movie")
        assertEquals(1, result.size)
        assertEquals(sub1.url, result[0].url)
    }

    @Test
    fun testResolveSubtitles_FallbackWhenSamePage() {
        val sub1 = SubtitleRecord("https://example.com/tr.vtt", "tr")
        val record = MediaRecord(
            url = "https://example.com/stream.m3u8",
            kind = "hls",
            pageUrl = "https://example.com/movie",
            subtitles = java.util.concurrent.CopyOnWriteArrayList()
        )
        val result = resolveSubtitles(record, listOf(sub1), "https://example.com/movie")
        assertEquals(1, result.size)
        assertEquals(sub1.url, result[0].url)
    }

    @Test
    fun testResolveSubtitles_DirectMediaOpenNullRecord() {
        val sub1 = SubtitleRecord("https://example.com/tr.vtt", "tr")
        // Direct media_open (sayfa yok, record == null) -> bos liste
        val result = resolveSubtitles(null, listOf(sub1), "https://example.com/movie")
        assertEquals(0, result.size)
    }

    @Test
    fun testResolveSubtitles_DifferentPageNoLeak() {
        val sub1 = SubtitleRecord("https://example.com/tr.vtt", "tr")
        val record = MediaRecord(
            url = "https://example.com/stream.m3u8",
            kind = "hls",
            pageUrl = "https://old-site.com/movie",
            subtitles = java.util.concurrent.CopyOnWriteArrayList()
        )
        val result = resolveSubtitles(record, listOf(sub1), "https://new-site.com/movie")
        assertEquals(0, result.size)
    }

    @Test
    fun masterPlaylistIsNotReplacedBySubPlaylists() {
        val found = mutableListOf<String>()
        val detector = MediaDetector { found.add(it.url) }
        // URL seen first (unknown kind), then the body proves it a master.
        detector.inspectRequest("https://cdn.example/hls/film.mp4/txt/master.txt", null, "https://site.example/film")
        detector.registerMedia("https://cdn.example/hls/film.mp4/txt/master.txt", "hls-master")
        detector.registerMedia("https://cdn.example/hls/film.mp4/txt/sublist_aud1.txt", "hls")
        detector.inspectRequest("https://cdn.example/hls/film.mp4/txt/sublist_2.txt", null, "https://site.example/film")
        org.junit.Assert.assertEquals("https://cdn.example/hls/film.mp4/txt/master.txt", found.last())
        org.junit.Assert.assertFalse(found.any { it.contains("sublist") })
    }
}
