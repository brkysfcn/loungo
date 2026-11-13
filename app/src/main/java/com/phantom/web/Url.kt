package com.phantom.web

import java.net.URLEncoder

private val HOST_PORT_REGEX = Regex("""^[\w.-]+:\d+(/.*)?$""")
private val BLOCKED_SCHEMES = listOf("javascript:", "file:", "intent:", "content:", "data:")
private val GENERIC_SCHEME_REGEX = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*://""")

fun normalizeUrl(input: String, debug: Boolean): String? {
    val s = input.trim()
    if (s.isEmpty()) return null

    // 2. http:, https: ve about:blank olduğu gibi kalır
    if (s.equals("about:blank", ignoreCase = true)) {
        return "about:blank"
    }
    if (s.startsWith("http:", ignoreCase = true) ||
        s.startsWith("https:", ignoreCase = true)
    ) {
        return s
    }

    // 3. chrome: yalnızca debug açıkken geçer. Diğer şemalar (javascript:, file:, intent:, content:, data:) -> null
    if (s.startsWith("chrome:", ignoreCase = true)) {
        return if (debug) s else null
    }
    for (blocked in BLOCKED_SCHEMES) {
        if (s.startsWith(blocked, ignoreCase = true)) {
            return null
        }
    }
    if (GENERIC_SCHEME_REGEX.containsMatchIn(s)) {
        return null
    }

    // 4. ^[\w.-]+:\d+(/.*)?$ biçimi -> başına http:// eklenir
    if (HOST_PORT_REGEX.matches(s)) {
        return "http://$s"
    }

    // 5. Boşluk içeriyorsa ya da hiç nokta yoksa -> https://www.google.com/search?q= + urlencoded
    if (s.any { it.isWhitespace() } || !s.contains('.')) {
        val encoded = URLEncoder.encode(s, "UTF-8")
        return "https://www.google.com/search?q=$encoded"
    }

    // 6. Hiçbiri değilse başına https:// eklenir
    return "https://$s"
}
