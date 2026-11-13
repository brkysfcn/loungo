package com.phantom.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationFilterTest {

    @Test
    fun testExtractBaseDomain() {
        assertEquals("moviesite.nl", NavigationFilter.extractBaseDomain("https://www.moviesite.nl/film/test"))
        assertEquals("moviesite.nl", NavigationFilter.extractBaseDomain("sub.moviesite.nl:8080"))
        assertEquals("streamprovider.to", NavigationFilter.extractBaseDomain("https://embed.streamprovider.to/video/embed/123"))
        assertEquals("example.com.tr", NavigationFilter.extractBaseDomain("https://haber.example.com.tr/gundem"))
        assertEquals("google.co.uk", NavigationFilter.extractBaseDomain("https://www.google.co.uk/search?q=test"))
        assertEquals("localhost", NavigationFilter.extractBaseDomain("http://localhost:8765/"))
        assertEquals("192.168.1.150", NavigationFilter.extractBaseDomain("http://192.168.1.150:8765/"))
    }

    @Test
    fun testCrossSiteRedirectWithoutGestureBlocked() {
        val current = "https://www.moviesite.nl/film/action-movie/"
        val adRedirect = "https://onclickprediction.com/click.php?id=999"

        val blocked = NavigationFilter.shouldBlockCrossSiteRedirect(
            targetUrl = adRedirect,
            currentUrl = current,
            hasGesture = false
        )
        assertTrue("Cross-site redirect without gesture must be blocked", blocked)
    }

    @Test
    fun testSameSiteRedirectWithoutGestureAllowed() {
        val current = "https://www.moviesite.nl/login"
        val sameSiteTarget = "https://www.moviesite.nl/account/dashboard"
        val subDomainTarget = "https://auth.moviesite.nl/callback"

        assertFalse(
            "Same-domain redirect without gesture must be allowed",
            NavigationFilter.shouldBlockCrossSiteRedirect(sameSiteTarget, current, hasGesture = false)
        )
        assertFalse(
            "Subdomain redirect without gesture must be allowed",
            NavigationFilter.shouldBlockCrossSiteRedirect(subDomainTarget, current, hasGesture = false)
        )
    }

    @Test
    fun testCrossSiteWithGestureAllowed() {
        val current = "https://www.google.com/search?q=news"
        val externalSite = "https://www.bbc.com/news"

        assertFalse(
            "Cross-site navigation with user gesture must be allowed",
            NavigationFilter.shouldBlockCrossSiteRedirect(externalSite, current, hasGesture = true)
        )
    }

    @Test
    fun testInitialNavigationAllowed() {
        val target = "https://www.moviesite.nl/"

        assertFalse(
            "Initial navigation from empty URL must be allowed",
            NavigationFilter.shouldBlockCrossSiteRedirect(target, "", hasGesture = false)
        )
        assertFalse(
            "Initial navigation from about:blank must be allowed",
            NavigationFilter.shouldBlockCrossSiteRedirect(target, "about:blank", hasGesture = false)
        )
        assertFalse(
            "Initial navigation from about:start must be allowed",
            NavigationFilter.shouldBlockCrossSiteRedirect(target, "about:start", hasGesture = false)
        )
    }
}
