package com.phantom.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlTest {

    @Test
    fun rule1_emptyOrWhitespaceReturnsNull() {
        assertNull(normalizeUrl("", false))
        assertNull(normalizeUrl("   ", false))
        assertNull(normalizeUrl("\t\n", true))
    }

    @Test
    fun rule2_httpHttpsAboutBlankPreserved() {
        assertEquals("http://example.com", normalizeUrl("http://example.com", false))
        assertEquals("https://example.com/path?arg=1", normalizeUrl("https://example.com/path?arg=1", false))
        assertEquals("about:blank", normalizeUrl("about:blank", false))
        assertEquals("about:blank", normalizeUrl("ABOUT:BLANK", false))
    }

    @Test
    fun rule3_chromeOnlyAllowedInDebug_otherSchemesNull() {
        assertEquals("chrome://crash", normalizeUrl("chrome://crash", true))
        assertNull(normalizeUrl("chrome://crash", false))
        assertNull(normalizeUrl("javascript:alert(1)", true))
        assertNull(normalizeUrl("file:///sdcard/test.txt", true))
        assertNull(normalizeUrl("intent:#Intent;...", true))
        assertNull(normalizeUrl("content://media/external/...", true))
        assertNull(normalizeUrl("data:text/html,test", true))
        assertNull(normalizeUrl("ftp://files.example.com", true))
    }

    @Test
    fun rule4_hostPortPrependHttp() {
        assertEquals("http://192.168.1.5:8080", normalizeUrl("192.168.1.5:8080", false))
        assertEquals("http://localhost:3000", normalizeUrl("localhost:3000", false))
        assertEquals("http://localhost:3000/app/index.html", normalizeUrl("localhost:3000/app/index.html", false))
    }

    @Test
    fun rule5_searchWhenSpaceOrNoDot() {
        assertEquals("https://www.google.com/search?q=t%C3%BCrk%C3%A7e+arama", normalizeUrl("türkçe arama", false))
        assertEquals("https://www.google.com/search?q=searchterm", normalizeUrl("searchterm", false))
        assertEquals("https://www.google.com/search?q=multiple+words+query", normalizeUrl("multiple words query", false))
    }

    @Test
    fun rule6_prependHttps() {
        assertEquals("https://example.com", normalizeUrl(" example.com ", false))
        assertEquals("https://a.b/c?d=1", normalizeUrl("a.b/c?d=1", false))
        assertEquals("https://en.wikipedia.org/wiki/Kotlin", normalizeUrl("en.wikipedia.org/wiki/Kotlin", false))
    }
}
