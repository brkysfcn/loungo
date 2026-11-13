package com.phantom.player

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.phantom.server.Hub
import com.phantom.server.buildErr
import com.phantom.server.buildFullscreen
import com.phantom.server.buildPlayer
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class NativePlayerActivity : Activity(), Hub.PlayerInputListener {

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var videoTitle: String? = null
    private var mediaUrl: String? = null
    private var pageUrl: String? = null
    private var resumeKey: String? = null
    private var initialResumeMs: Long? = null
    private var loggedFirstCue = false
    private var cueView: SubtitleView? = null
    private var recoveredFromDecoderError = false
    private var resumeOfferLogged = false
    private var lastLoggedTextTrack: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateTicker = object : Runnable {
        override fun run() {
            // Nobody to tell when no phone is connected; skip the JSON work.
            if (Hub.activeSocket != null) broadcastPlayerState()
            mainHandler.postDelayed(this, 1000L)
        }
    }
    private val saveTicker = object : Runnable {
        override fun run() {
            saveCurrentPosition()
            mainHandler.postDelayed(this, 10_000L)
        }
    }

    private fun logP(msg: String) {
        Log.i("PHANTOM", msg)
        com.phantom.log.EventLogStore.log("PHANTOM", msg)
    }

    private fun logPErr(msg: String, tr: Throwable? = null) {
        if (tr != null) {
            Log.e("PHANTOM", msg, tr)
            com.phantom.log.EventLogStore.log("PHANTOM", "$msg: ${tr.message}")
        } else {
            Log.e("PHANTOM", msg)
            com.phantom.log.EventLogStore.log("PHANTOM", msg)
        }
    }

    companion object {
        // A bar of ~1/10 of the screen (108 px at 1080p) holds two lines at 4.5% text size.
        private const val BAR_MIN_FRACTION = 0.10f
        private const val BAR_TEXT_FRACTION = 0.045f
        private val HANDLED_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_STEP_FORWARD,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_CAPTIONS,
            KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_BACK,
        )
        const val EXTRA_URL = "extra_url"
        const val EXTRA_PAGE_URL = "extra_page_url"
        const val EXTRA_KIND = "extra_kind"
        const val EXTRA_REFERER = "extra_referer"
        const val EXTRA_ORIGIN = "extra_origin"
        const val EXTRA_SUBS = "extra_subs"
        const val EXTRA_USER_AGENT = "extra_user_agent"
        const val EXTRA_COOKIE = "extra_cookie"
        const val EXTRA_TITLE = "extra_title"
        /** JSON object: the headers the page sent when it requested this URL. */
        const val EXTRA_HEADERS = "extra_headers"

        /** Headers that must not be replayed: per-request (Range) or set by the HTTP stack. */
        private val SKIP_HEADERS = setOf("range", "cookie", "accept-encoding", "host", "connection", "content-length")
    }

    private fun getTracksJson(): Pair<JSONObject, JSONObject> {
        val p = player ?: return JSONObject() to JSONObject()
        val currentTracks = p.currentTracks
        val tracksObj = JSONObject()
        val selObj = JSONObject()

        val audioArray = JSONArray()
        val textArray = JSONArray()
        val videoArray = JSONArray()

        var selAudio: String? = null
        var selText: String? = "off"
        var selVideo: String? = null

        for (group in currentTracks.groups) {
            val type = group.type
            for (i in 0 until group.length) {
                val isSelected = group.isTrackSelected(i)
                val format = group.getTrackFormat(i)
                val id = format.id ?: "${group.type}_$i"
                val lang = format.language ?: ""
                val label = format.label ?: lang.ifEmpty { "Track $i" }

                when (type) {
                    C.TRACK_TYPE_AUDIO -> {
                        val item = JSONObject().apply {
                            put("id", id)
                            put("label", label)
                            put("lang", lang)
                        }
                        audioArray.put(item)
                        if (isSelected) selAudio = id
                    }
                    C.TRACK_TYPE_TEXT -> {
                        val item = JSONObject().apply {
                            put("id", id)
                            put("label", label)
                            put("lang", lang)
                        }
                        textArray.put(item)
                        if (isSelected) selText = id
                    }
                    C.TRACK_TYPE_VIDEO -> {
                        // Skip what this TV cannot decode in hardware (AV1 here); choosing it stops playback.
                        if (!QualityLabel.isSelectableVideo(format)) continue
                        // Quality class from the width: letterboxed 1920x800 is shown as 1080p.
                        val height = QualityLabel.of(format.width, format.height)?.removeSuffix("p")?.toIntOrNull() ?: 0
                        val bitrate = if (format.bitrate > 0) format.bitrate else 0
                        val item = JSONObject().apply {
                            put("id", id)
                            put("height", height)
                            put("bitrate", bitrate)
                        }
                        videoArray.put(item)
                        if (isSelected) selVideo = id
                    }
                }
            }
        }

        tracksObj.put("audio", audioArray)
        tracksObj.put("text", textArray)
        tracksObj.put("video", videoArray)

        selObj.put("audio", selAudio ?: "")
        selObj.put("text", selText ?: "off")
        selObj.put("video", selVideo ?: "")

        return tracksObj to selObj
    }

    private fun saveCurrentPosition() {
        val p = player ?: return
        val key = resumeKey ?: return
        val pos = p.currentPosition
        val dur = p.duration
        if (initialResumeMs != null && pos < 60_000L) {
            return
        }
        val prefs = getSharedPreferences(ResumeStore.PREFS_NAME, Context.MODE_PRIVATE)
        ResumeStore.savePosition(prefs, key, pos, dur)
    }

    private fun broadcastPlayerState() {
        val p = player ?: return
        val pos = if (p.currentPosition > 0) p.currentPosition else 0L
        val dur = if (p.duration > 0) p.duration else 0L
        val playing = p.isPlaying
        val (tracks, sel) = getTracksJson()
        val resumeToSend: Long? = if (initialResumeMs != null && ResumeStore.shouldResume(initialResumeMs!!, dur)) {
            initialResumeMs
        } else {
            null
        }
        if (resumeToSend != null && !resumeOfferLogged) {
            resumeOfferLogged = true
            logP("PLAYER resume_offer ms=$resumeToSend")
        }
        Hub.sendText(
            buildPlayer(
                on = true,
                playing = playing,
                pos = pos,
                dur = dur,
                title = videoTitle,
                tracks = tracks,
                sel = sel,
                resume = resumeToSend
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val url = intent.getStringExtra(EXTRA_URL) ?: run {
            finish()
            return
        }
        val kind = intent.getStringExtra(EXTRA_KIND) ?: com.phantom.web.kindOf(url)
        val referer = intent.getStringExtra(EXTRA_REFERER)
        val origin = intent.getStringExtra(EXTRA_ORIGIN)
        val subsJsonStr = intent.getStringExtra(EXTRA_SUBS)
        val userAgent = intent.getStringExtra(EXTRA_USER_AGENT)
        val cookie = intent.getStringExtra(EXTRA_COOKIE)
        videoTitle = intent.getStringExtra(EXTRA_TITLE)

        mediaUrl = url
        pageUrl = intent.getStringExtra(EXTRA_PAGE_URL) ?: referer ?: ""
        val k = ResumeStore.computeKey(pageUrl!!, mediaUrl!!)
        resumeKey = k
        val prefs = getSharedPreferences(ResumeStore.PREFS_NAME, Context.MODE_PRIVATE)
        val saved = ResumeStore.getPosition(prefs, k)
        initialResumeMs = if (saved > 60_000L) saved else null

        logP("NATIVE_PLAYER url=$url kind=$kind referer=$referer origin=$origin cookie=${!cookie.isNullOrEmpty()} resumeKey=$k savedResume=$saved")

        playerView = PlayerView(this).apply {
            useController = true
            // The focused PlayerView otherwise gets the system focus highlight, drawn as a
            // tinted band in the letterbox area below the video.
            defaultFocusHighlightEnabled = false
            setBackgroundColor(android.graphics.Color.BLACK)
            setShutterBackgroundColor(android.graphics.Color.BLACK)
        }
        // Our own subtitle layer, so cues can sit in the letterbox bar below the picture
        // instead of over it; PlayerView's built-in one is hidden.
        playerView?.subtitleView?.visibility = android.view.View.GONE
        cueView = SubtitleView(this)
        val root = android.widget.FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(playerView)
            addView(cueView, android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT))
        }
        setContentView(root)
        playerView?.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_content_frame)
            ?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeCues() }

        val headers = mutableMapOf<String, String>()
        // Repeat the page's own request: same headers as the browser sent for this URL.
        intent.getStringExtra(EXTRA_HEADERS)?.let { raw ->
            try {
                val obj = JSONObject(raw)
                for (key in obj.keys()) {
                    if (key.lowercase() !in SKIP_HEADERS && !key.equals("User-Agent", true)) headers[key] = obj.getString(key)
                }
            } catch (_: Exception) {}
        }
        logP("NATIVE_PLAYER page_headers=${headers.keys}")
        val httpDataSourceFactory = DefaultHttpDataSource.Factory().apply {
            if (!userAgent.isNullOrEmpty()) {
                setUserAgent(userAgent)
            }
            if (!referer.isNullOrEmpty() && headers.keys.none { it.equals("Referer", true) }) {
                headers["Referer"] = referer
            }
            // "null" is the opaque origin of sandboxed frames; it is not a usable Origin header.
            if (!origin.isNullOrEmpty() && origin != "null" && headers.keys.none { it.equals("Origin", true) }) {
                headers["Origin"] = origin
            }
            if (!cookie.isNullOrEmpty()) {
                headers["Cookie"] = cookie
            }
            if (headers.isNotEmpty()) {
                setDefaultRequestProperties(headers)
            }
        }

        val subConfigsWithReferer = mutableListOf<Triple<MediaItem.SubtitleConfiguration, String?, String>>()
        if (!subsJsonStr.isNullOrEmpty()) {
            try {
                val array = JSONArray(subsJsonStr)
                // Default subtitle: Turkish if present, else the first one with unknown language
                // (film sites here are Turkish), else none.
                val langs = (0 until array.length()).map { array.getJSONObject(it).optString("lang", "?").lowercase() }
                val defaultIndex = langs.indexOfFirst { it == "tr" || it == "tur" }
                    .takeIf { it >= 0 }
                    ?: langs.indexOfFirst { it == "?" || it.isEmpty() || it == "und" }
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val subUrl = obj.getString("url")
                    val subLang = obj.optString("lang", "?")
                    val subReferer = if (obj.has("referer") && !obj.isNull("referer")) obj.optString("referer").ifEmpty { null } else null
                    val mimeType = when {
                        subUrl.contains(".srt") -> MimeTypes.APPLICATION_SUBRIP
                        subUrl.contains(".ass") -> MimeTypes.TEXT_SSA
                        else -> MimeTypes.TEXT_VTT
                    }
                    val label = when (subLang.lowercase()) {
                        "tr", "tur" -> getString(com.phantom.R.string.lang_turkish)
                        "en", "eng" -> getString(com.phantom.R.string.lang_english)
                        // Sites often give no language: "Subtitle 1", "Subtitle 2" instead of "?".
                        "", "?", "und", "bilinmeyen" -> getString(com.phantom.R.string.sub_numbered, i + 1)
                        else -> subLang.uppercase()
                    }
                    val config = MediaItem.SubtitleConfiguration.Builder(Uri.parse(subUrl))
                        .setId("sub_$i")
                        .setLabel(label)
                        .setMimeType(mimeType)
                        .setLanguage(subLang)
                        .setSelectionFlags(if (i == defaultIndex) C.SELECTION_FLAG_DEFAULT else 0)
                        .build()
                    subConfigsWithReferer.add(Triple(config, subReferer, subLang))
                }
                logP("NATIVE_PLAYER configured ${subConfigsWithReferer.size} subtitles langs=$langs default=$defaultIndex")
            } catch (e: Exception) {
                Log.w("PHANTOM", "Failed to parse EXTRA_SUBS: ${e.message}")
            }
        }

        val uri = Uri.parse(url)
        val mediaItemBuilder = MediaItem.Builder().setUri(uri)
        if (kind?.lowercase() == "hls") {
            mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
        } else if (kind?.lowercase() == "dash") {
            mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
        }
        if (subConfigsWithReferer.isNotEmpty()) {
            mediaItemBuilder.setSubtitleConfigurations(subConfigsWithReferer.map { it.first })
        }
        val mediaItem = mediaItemBuilder.build()

        // Subtitle hosts check the Referer of the page that fetched the .vtt (e.g. the embed
        // iframe), which differs from the media Referer, so override it per subtitle URI.
        val subRefererByUri = subConfigsWithReferer
            .mapNotNull { (cfg, ref, _) -> ref?.let { cfg.uri to it } }
            .toMap()
        val dataSourceFactory = ResolvingDataSource.Factory(httpDataSourceFactory) { spec ->
            val ref = subRefererByUri[spec.uri]
            if (ref != null) spec.withAdditionalHeaders(mapOf("Referer" to ref)) else spec
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    logP("PLAYER state=$playbackState")
                    if (playbackState == Player.STATE_READY) {
                        logP("PLAYER ready")
                    } else if (playbackState == Player.STATE_ENDED) {
                        initialResumeMs = null
                        val key = resumeKey
                        if (key != null) {
                            val prefs = getSharedPreferences(ResumeStore.PREFS_NAME, Context.MODE_PRIVATE)
                            ResumeStore.clearPosition(prefs, key)
                        }
                    }
                    broadcastPlayerState()
                }

                override fun onTracksChanged(tracks: Tracks) {
                    for (group in tracks.groups) {
                        if (group.type == C.TRACK_TYPE_TEXT && group.isSelected) {
                            for (i in 0 until group.length) {
                                if (group.isTrackSelected(i)) {
                                    val f = group.getTrackFormat(i)
                                    val id = f.id ?: "sub_$i"
                                    if (id != lastLoggedTextTrack) {
                                        lastLoggedTextTrack = id
                                        logP("TRACK select type=text id=$id lang=${f.language}")
                                    }
                                }
                            }
                        }
                    }
                    broadcastPlayerState()
                }

                override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                    // Drop the stream's own line/position so every cue goes to the bottom area.
                    // Seek-preview tracks (WebVTT whose cues are "thumbs.jpg#xywh=...") are not
                    // subtitles: their cues are never shown.
                    cueView?.setCues(cueGroup.cues.filterNot { QualityLabel.isThumbnailCue(it.text) }.map { c ->
                        if (c.bitmap != null) c else c.buildUpon()
                            .setLine(androidx.media3.common.text.Cue.DIMEN_UNSET, androidx.media3.common.text.Cue.TYPE_UNSET)
                            .setLineAnchor(androidx.media3.common.text.Cue.TYPE_UNSET)
                            .setPosition(androidx.media3.common.text.Cue.DIMEN_UNSET)
                            .setPositionAnchor(androidx.media3.common.text.Cue.TYPE_UNSET)
                            .build()
                    })
                    if (!loggedFirstCue && cueGroup.cues.isNotEmpty()) {
                        loggedFirstCue = true
                        Log.i("PHANTOM", "SUB_CUE ${cueGroup.cues.first().text?.toString()?.take(60)}")
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (!isPlaying) {
                        saveCurrentPosition()
                    }
                    broadcastPlayerState()
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int
                ) {
                    broadcastPlayerState()
                }

                override fun onPlayerError(error: PlaybackException) {
                    val causeStr = error.cause?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "no-cause"
                    logPErr("PLAYER_ERR ${error.errorCodeName}: $causeStr", error)
                    if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
                        error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED) {
                        // What did the server send instead of a playlist? Logged for "Report a problem".
                        val probeHeaders = headers.toMap()
                        Thread {
                            logP(ManifestProbe.describe(url, userAgent, probeHeaders))
                        }.start()
                    }
                    // A manually chosen quality the TV cannot decode (e.g. AV1) must not end the
                    // film: drop the override, go back to automatic quality and continue.
                    val p = player
                    val decoderError = error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                        error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED
                    if (p != null && decoderError && !recoveredFromDecoderError) {
                        recoveredFromDecoderError = true
                        val pos = p.currentPosition
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                            .clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
                        p.prepare()
                        p.seekTo(pos)
                        p.play()
                        logP("PLAYER recover auto_video pos=$pos")
                        return
                    }
                    Hub.sendText(buildErr("player", "${error.errorCodeName}: $causeStr"))
                    finish()
                }
            })

            val defaultAudioLang = Locale.getDefault().language
            val hasDefaultSub = subConfigsWithReferer.any { (it.first.selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0 }
            val trackParamsBuilder = trackSelectionParameters.buildUpon()
                .setPreferredAudioLanguage(defaultAudioLang)

            if (hasDefaultSub) {
                trackParamsBuilder
                    .setPreferredTextLanguage("tr")
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            } else {
                trackParamsBuilder
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            }
            trackSelectionParameters = trackParamsBuilder.build()

            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }
        player = exo
        playerView?.player = exo

        Hub.activePlayerListener = this
        Hub.sendText(buildFullscreen(true))
        broadcastPlayerState()
        mainHandler.postDelayed(stateTicker, 1000L)
        mainHandler.postDelayed(saveTicker, 10_000L)
    }

    override fun onButton(down: Boolean, btn: Int) {
        if (down && btn == 0) {
            runOnUiThread {
                val p = player ?: return@runOnUiThread
                if (p.isPlaying) {
                    p.pause()
                    logP("PLAYER pause")
                } else {
                    p.play()
                    logP("PLAYER play")
                }
                broadcastPlayerState()
            }
        }
    }

    override fun onScroll(sx: Short, sy: Short) {
        if (sx != 0.toShort()) {
            runOnUiThread {
                val p = player ?: return@runOnUiThread
                initialResumeMs = null
                val delta = if (sx > 0) 10_000L else -10_000L
                val maxDuration = if (p.duration > 0) p.duration else Long.MAX_VALUE
                val target = (p.currentPosition + delta).coerceIn(0L, maxDuration)
                p.seekTo(target)
                logP("PLAYER seek $target")
                broadcastPlayerState()
            }
        }
    }

    override fun onPlayerCmd(a: String, ms: Long?, type: String?, id: String?) {
        runOnUiThread {
            val p = player ?: return@runOnUiThread
            when (a.lowercase()) {
                "play" -> {
                    p.play()
                    logP("PLAYER play")
                    broadcastPlayerState()
                }
                "pause" -> {
                    p.pause()
                    logP("PLAYER pause")
                    broadcastPlayerState()
                }
                "seek" -> {
                    initialResumeMs = null
                    if (ms != null) {
                        val maxDur = if (p.duration > 0) p.duration else Long.MAX_VALUE
                        val target = ms.coerceIn(0L, maxDur)
                        p.seekTo(target)
                        logP("PLAYER seek $target")
                        broadcastPlayerState()
                    }
                }
                "track" -> {
                    val trackType = when (type?.lowercase()) {
                        "audio" -> C.TRACK_TYPE_AUDIO
                        "text" -> C.TRACK_TYPE_TEXT
                        "video" -> C.TRACK_TYPE_VIDEO
                        else -> null
                    }
                    if (trackType != null) {
                        if (id == "off") {
                            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(trackType, true)
                                .build()
                            logP("TRACK disabled $type")
                        } else if (id == "auto") {
                            val builder = p.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(trackType, false)
                            var targetGroup: Tracks.Group? = null
                            var targetIndex = -1
                            var targetId = ""
                            for (group in p.currentTracks.groups) {
                                if (group.type == trackType) {
                                    for (i in 0 until group.length) {
                                        val f = group.getTrackFormat(i)
                                        val lang = f.language?.lowercase()
                                        if (lang == "tr" || lang == "tur") {
                                            targetGroup = group
                                            targetIndex = i
                                            targetId = f.id ?: "${group.type}_$i"
                                            break
                                        }
                                        if (targetGroup == null) {
                                            targetGroup = group
                                            targetIndex = i
                                            targetId = f.id ?: "${group.type}_$i"
                                        }
                                    }
                                    if (targetIndex >= 0 && targetGroup?.getTrackFormat(targetIndex)?.language?.lowercase()?.let { it == "tr" || it == "tur" } == true) {
                                        break
                                    }
                                }
                            }
                            if (targetGroup != null && targetIndex >= 0) {
                                builder.setOverrideForType(TrackSelectionOverride(targetGroup.mediaTrackGroup, targetIndex))
                                p.trackSelectionParameters = builder.build()
                                if (type == "text") lastLoggedTextTrack = targetId
                                logP("TRACK select type=$type id=$targetId")
                            }
                        } else if (!id.isNullOrEmpty()) {
                            val builder = p.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(trackType, false)
                            var matched = false
                            for (group in p.currentTracks.groups) {
                                if (group.type == trackType) {
                                    for (i in 0 until group.length) {
                                        val f = group.getTrackFormat(i)
                                        val formatId = f.id ?: "${group.type}_$i"
                                        if (formatId == id) {
                                            builder.setOverrideForType(
                                                TrackSelectionOverride(group.mediaTrackGroup, i)
                                            )
                                            matched = true
                                            break
                                        }
                                    }
                                }
                            }
                            if (matched) {
                                p.trackSelectionParameters = builder.build()
                                if (type == "text") lastLoggedTextTrack = id
                                logP("TRACK select type=$type id=$id")
                            }
                        }
                        broadcastPlayerState()
                    }
                }
                "close" -> {
                    finish()
                }
            }
        }
    }

    override fun onClosePlayer() {
        runOnUiThread {
            finish()
        }
    }

    /**
     * When the video leaves a black bar under the picture tall enough for two lines (wide films),
     * put the subtitles in that bar; otherwise keep the usual position over the picture.
     * A third line, if any, grows upward onto the picture rather than being cut off.
     */
    private fun placeCues() {
        val view = cueView ?: return
        val frame = playerView?.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_content_frame) ?: return
        val h = view.height
        if (h <= 0) return
        val bar = h - frame.bottom
        if (bar >= h * BAR_MIN_FRACTION) {
            view.setFractionalTextSize(BAR_TEXT_FRACTION)
            view.setBottomPaddingFraction(bar * 0.12f / h)
        } else {
            view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION)
            view.setBottomPaddingFraction(SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION)
        }
    }

    /** Remote-only settings: Subtitles / Audio / Quality, each a list of the player's tracks. */
    private fun showSettingsDialog() {
        val entries = listOf(
            C.TRACK_TYPE_TEXT to getString(com.phantom.R.string.sub_title),
            C.TRACK_TYPE_AUDIO to getString(com.phantom.R.string.audio_title),
            C.TRACK_TYPE_VIDEO to getString(com.phantom.R.string.quality_title),
        )
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(com.phantom.R.string.settings_title))
            .setItems(entries.map { it.second }.toTypedArray()) { _, which ->
                showTrackDialog(entries[which].first, entries[which].second)
            }
            .setNegativeButton(getString(com.phantom.R.string.btn_close), null)
            .show()
    }

    private fun showTrackDialog(trackType: Int, title: String) {
        val p = player ?: return
        data class Item(val group: Tracks.Group, val index: Int, val label: String, val selected: Boolean)
        val items = mutableListOf<Item>()
        var n = 0
        for (group in p.currentTracks.groups) {
            if (group.type != trackType) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val f = group.getTrackFormat(i)
                if (trackType == C.TRACK_TYPE_VIDEO && !QualityLabel.isSelectableVideo(f)) continue
                val label = when (trackType) {
                    C.TRACK_TYPE_TEXT -> SubtitleHelper.formatSubtitleLabel(f.language, f.label, n, false, this)
                    C.TRACK_TYPE_VIDEO -> QualityLabel.of(f.width, f.height) ?: "${n + 1}"
                    else -> f.label ?: f.language?.uppercase() ?: getString(com.phantom.R.string.audio_title) + " ${n + 1}"
                }
                items.add(Item(group, i, label, group.isTrackSelected(i)))
                n++
            }
        }
        if (trackType == C.TRACK_TYPE_VIDEO) {
            // Same resolution in several codecs/bitrates: keep the highest bitrate per label.
            val best = items.groupBy { it.label }.values.map { same -> same.maxBy { it.group.getTrackFormat(it.index).bitrate } }
            items.clear()
            items.addAll(best.sortedByDescending { it.group.getTrackFormat(it.index).width })
        }

        // First row: "off" for subtitles, "auto" for quality; audio has no extra row.
        val extra = when (trackType) {
            C.TRACK_TYPE_TEXT -> getString(com.phantom.R.string.sub_off)
            C.TRACK_TYPE_VIDEO -> getString(com.phantom.R.string.quality_auto)
            else -> null
        }
        val labels = (listOfNotNull(extra) + items.map { if (it.selected) "✓ ${it.label}" else it.label }).toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels) { _, which ->
                val pl = player ?: return@setItems
                val b = pl.trackSelectionParameters.buildUpon()
                if (extra != null && which == 0) {
                    if (trackType == C.TRACK_TYPE_TEXT) b.setTrackTypeDisabled(trackType, true)
                    else b.clearOverridesOfType(trackType)
                    logP("TRACK ${if (trackType == C.TRACK_TYPE_TEXT) "disabled text" else "auto video"}")
                } else {
                    val it = items[which - (if (extra != null) 1 else 0)]
                    b.setTrackTypeDisabled(trackType, false)
                        .setOverrideForType(TrackSelectionOverride(it.group.mediaTrackGroup, it.index))
                    val type = when (trackType) { C.TRACK_TYPE_TEXT -> "text"; C.TRACK_TYPE_AUDIO -> "audio"; else -> "video" }
                    if (trackType == C.TRACK_TYPE_TEXT) lastLoggedTextTrack = it.group.getTrackFormat(it.index).id
                    logP("TRACK select type=$type id=${it.group.getTrackFormat(it.index).id} label=${it.label}")
                }
                pl.trackSelectionParameters = b.build()
            }
            .setNegativeButton(getString(com.phantom.R.string.btn_close), null)
            .show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Keys we handle on ACTION_DOWN must not reach PlayerView on ACTION_UP either:
        // its focused controller button would "click" again and undo the action.
        if (event.action != KeyEvent.ACTION_DOWN && event.keyCode in HANDLED_KEYS) return true
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    val p = player ?: return true
                    if (p.isPlaying) {
                        p.pause()
                        logP("PLAYER pause")
                    } else {
                        p.play()
                        logP("PLAYER play")
                    }
                    playerView?.showController()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_MEDIA_REWIND,
                KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD -> {
                    val p = player ?: return true
                    val target = maxOf(0L, p.currentPosition - 10_000L)
                    p.seekTo(target)
                    logP("PLAYER seek $target")
                    playerView?.showController()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                KeyEvent.KEYCODE_MEDIA_STEP_FORWARD -> {
                    val p = player ?: return true
                    val dur = if (p.duration > 0) p.duration else Long.MAX_VALUE
                    val target = minOf(dur, p.currentPosition + 10_000L)
                    p.seekTo(target)
                    logP("PLAYER seek $target")
                    playerView?.showController()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_CAPTIONS,
                KeyEvent.KEYCODE_PROG_RED,
                KeyEvent.KEYCODE_INFO -> {
                    showSettingsDialog()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    finish()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onPause() {
        super.onPause()
        saveCurrentPosition()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(stateTicker)
        mainHandler.removeCallbacks(saveTicker)
        saveCurrentPosition()
        Hub.activePlayerListener = null
        playerView?.player = null
        player?.release()
        player = null
        Hub.sendText(buildFullscreen(false))
        Hub.sendText(buildPlayer(on = false))
    }
}
