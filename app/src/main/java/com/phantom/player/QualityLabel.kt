package com.phantom.player

import android.media.MediaCodecList
import androidx.media3.common.C
import androidx.media3.common.Format

object QualityLabel {
    /**
     * Films are often letterboxed (1920x800 is Full HD, not "800p"), so the class is taken
     * from the width, falling back to the height for tall/portrait streams.
     */
    fun of(width: Int, height: Int): String? {
        if (width <= 0 && height <= 0) return null
        val byWidth = when {
            width >= 3800 -> 2160
            width >= 2500 -> 1440
            width >= 1900 -> 1080
            width >= 1250 -> 720
            width >= 840 -> 480
            width > 0 -> 360
            else -> 0
        }
        return "${if (byWidth > 0) byWidth else height}p"
    }

    /**
     * A variant the user may pick: not an HLS I-frame (trick-play) track, which only holds key
     * frames and looks frozen while audio goes on, and decodable in hardware on this TV.
     */
    fun isSelectableVideo(f: Format): Boolean =
        (f.roleFlags and C.ROLE_FLAG_TRICK_PLAY) == 0 && hasHardwareDecoder(f.sampleMimeType)

    private val hwCache = mutableMapOf<String, Boolean>()

    /**
     * Only offer codecs with a hardware decoder. A software AV1 decoder (libgav1) was tried
     * (T64): 1080p used ~3 of 4 cores on a 32-bit ARM TV and stuttered, so it was removed.
     */
    fun hasHardwareDecoder(mime: String?): Boolean {
        if (mime == null) return true
        return hwCache.getOrPut(mime) {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                !info.isEncoder && info.isHardwareAccelerated &&
                    info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
        }
    }

    private val THUMBNAIL_CUE = Regex("""^\S+\.(jpe?g|png|webp)(\?\S*)?(#xywh=\d+,\d+,\d+,\d+)?$""", RegexOption.IGNORE_CASE)

    /** A cue from a seek-preview (thumbnail sprite) track rather than a subtitle line. */
    fun isThumbnailCue(text: CharSequence?): Boolean = text != null && THUMBNAIL_CUE.matches(text.trim())
}
