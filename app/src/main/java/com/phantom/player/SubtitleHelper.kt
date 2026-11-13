package com.phantom.player

import android.content.Context
import java.util.Locale

object SubtitleHelper {
    /**
     * Formats subtitle track labels for UI display using localized resources.
     */
    fun formatSubtitleLabel(
        lang: String?,
        label: String?,
        index: Int,
        isSelected: Boolean,
        context: Context? = null
    ): String {
        val cleanLang = lang?.trim()?.lowercase()
        val displayLang = if (!cleanLang.isNullOrEmpty() && cleanLang != "und" && cleanLang != "?" && cleanLang != "bilinmeyen") {
            if (context != null) {
                when (cleanLang) {
                    "tr", "tur" -> context.getString(com.phantom.R.string.lang_turkish)
                    "en", "eng" -> context.getString(com.phantom.R.string.lang_english)
                    "de", "deu", "ger" -> context.getString(com.phantom.R.string.lang_german)
                    "fr", "fra", "fre" -> context.getString(com.phantom.R.string.lang_french)
                    "es", "spa" -> context.getString(com.phantom.R.string.lang_spanish)
                    "it", "ita" -> context.getString(com.phantom.R.string.lang_italian)
                    "ru", "rus" -> context.getString(com.phantom.R.string.lang_russian)
                    else -> {
                        val loc = Locale.forLanguageTag(cleanLang)
                        val name = loc.getDisplayName(Locale.getDefault())
                        if (name.isNotEmpty() && !name.equals(cleanLang, ignoreCase = true)) {
                            name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                        } else {
                            cleanLang.uppercase()
                        }
                    }
                }
            } else {
                val loc = Locale.forLanguageTag(cleanLang)
                val name = loc.getDisplayName(Locale.getDefault())
                if (name.isNotEmpty() && !name.equals(cleanLang, ignoreCase = true)) {
                    name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                } else {
                    cleanLang.uppercase()
                }
            }
        } else {
            null
        }

        val numberedFallback = if (context != null) {
            context.getString(com.phantom.R.string.sub_numbered, index + 1)
        } else {
            "Subtitle ${index + 1}"
        }

        val activeTag = if (context != null) {
            " " + context.getString(com.phantom.R.string.sub_active)
        } else {
            " [Active]"
        }

        val base = label?.takeIf { it.isNotBlank() && it != "?" && !it.contains("BILINMEYEN", ignoreCase = true) }
            ?: displayLang
            ?: numberedFallback

        return if (isSelected) "$base$activeTag" else base
    }
}
