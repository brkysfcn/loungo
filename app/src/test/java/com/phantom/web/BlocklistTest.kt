package com.phantom.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class BlocklistTest {

    private val blocklist: Blocklist = AdBlocker(
        listOf("ads.example", "doubleclick.net", "googlesyndication.com")
            .map { AdBlocker.fnv1a64(it) }
            .sorted()
            .toLongArray()
    )

    @Test
    fun testExactMatch() {
        assertTrue(blocklist.isBlocked("ads.example"))
        assertTrue(blocklist.isBlocked("doubleclick.net"))
        assertFalse(blocklist.isBlocked("example.com"))
    }

    @Test
    fun testSubdomains() {
        assertTrue(blocklist.isBlocked("a.ads.example"))
        assertTrue(blocklist.isBlocked("x.y.z.doubleclick.net"))
        assertFalse(blocklist.isBlocked("myads.example"))
        assertFalse(blocklist.isBlocked("fake-doubleclick.net"))
    }

    @Test
    fun testCaseInsensitive() {
        assertTrue(blocklist.isBlocked("ADS.EXAMPLE"))
        assertTrue(blocklist.isBlocked("Sub.DoubleClick.Net"))
        assertTrue(blocklist.isBlocked("GoOgLeSyNdIcAtIoN.CoM"))
    }

    @Test
    fun testEmptyOrInvalid() {
        assertFalse(blocklist.isBlocked(""))
        assertFalse(blocklist.isBlocked("   "))
    }
}
