package com.phantom.web

import java.net.URI
import java.util.Locale

object NavigationFilter {

    private val TWO_PART_TLDS = setOf(
        "com.tr", "org.tr", "net.tr", "gov.tr", "edu.tr",
        "co.uk", "org.uk", "me.uk", "ltd.uk", "plc.uk",
        "com.au", "net.au", "org.au",
        "co.nz", "net.nz", "org.nz",
        "co.jp", "ne.jp", "or.jp",
        "com.br", "net.br", "org.br"
    )

    /**
     * Extracts the registrable base domain (eTLD+1) from a URL or host string.
     * Returns empty string if parsing fails or input is empty.
     */
    fun extractBaseDomain(urlOrHost: String): String {
        val trimmed = urlOrHost.trim()
        if (trimmed.isEmpty()) return ""

        val rawHost = try {
            val uri = if (trimmed.contains("://")) URI(trimmed) else URI("http://$trimmed")
            uri.host ?: ""
        } catch (_: Exception) {
            val withoutScheme = if (trimmed.contains("://")) trimmed.substringAfter("://") else trimmed
            withoutScheme.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
        }

        val host = rawHost.lowercase(Locale.ROOT).trimEnd('.')
        if (host.isEmpty()) return ""

        // Return immediately if it's an IP address or localhost
        if (host == "localhost" || host.all { it.isDigit() || it == '.' || it == ':' }) {
            return host
        }

        val parts = host.split('.')
        if (parts.size <= 2) return host

        val lastTwo = "${parts[parts.size - 2]}.${parts.last()}"
        return if (TWO_PART_TLDS.contains(lastTwo) && parts.size >= 3) {
            parts.takeLast(3).joinToString(".")
        } else {
            parts.takeLast(2).joinToString(".")
        }
    }

    /**
     * Checks whether a top-level main-frame redirect should be blocked.
     * Pure decision function:
     * - Returns true (BLOCK) if there is NO user gesture and target base domain != current base domain.
     * - Returns false (ALLOW) if there is user gesture, or if domains match, or if initial/internal page.
     */
    fun shouldBlockCrossSiteRedirect(targetUrl: String, currentUrl: String, hasGesture: Boolean): Boolean {
        // User gesture explicitly initiated the action (click, tap) -> ALLOW
        if (hasGesture) return false

        val trimmedCurrent = currentUrl.trim()
        // If current page is empty or internal/blank (initial navigation), ALLOW
        if (trimmedCurrent.isEmpty() ||
            trimmedCurrent.startsWith("about:", ignoreCase = true) ||
            trimmedCurrent.startsWith("chrome:", ignoreCase = true) ||
            trimmedCurrent.startsWith("file:", ignoreCase = true)
        ) {
            return false
        }

        val currentBase = extractBaseDomain(trimmedCurrent)
        val targetBase = extractBaseDomain(targetUrl)

        // If either base domain could not be resolved, do not block
        if (currentBase.isEmpty() || targetBase.isEmpty()) {
            return false
        }

        // Block if cross-site (different base domains)
        return !currentBase.equals(targetBase, ignoreCase = true)
    }
}
