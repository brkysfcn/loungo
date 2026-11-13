package com.phantom.web

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class AdBlockerTest {

    @Test
    fun testFnv1a64TestVectorsMatchingNode() {
        // Test vectors produced by tools/adblock/build.mjs
        assertEquals(-2554661609801288243L, AdBlocker.fnv1a64("doubleclick.net"))
        assertEquals(3967668997516456809L, AdBlocker.fnv1a64("ads.example"))
        assertEquals(-4288261434334511262L, AdBlocker.fnv1a64("googlesyndication.com"))
    }

    @Test
    fun testAdBlockerMatchingAndSubdomains() {
        val sampleDomains = listOf(
            "ads.example",
            "doubleclick.net",
            "googlesyndication.com"
        )
        val hashes = sampleDomains.map { AdBlocker.fnv1a64(it) }.sorted().toLongArray()
        val blocker = AdBlocker(hashes)

        // Exact matches
        assertTrue(blocker.isBlocked("ads.example"))
        assertTrue(blocker.isBlocked("doubleclick.net"))
        assertTrue(blocker.isBlocked("googlesyndication.com"))

        // Subdomain matches
        assertTrue(blocker.isBlocked("sub.ads.example"))
        assertTrue(blocker.isBlocked("a.b.c.doubleclick.net"))

        // Case insensitivity
        assertTrue(blocker.isBlocked("ADS.EXAMPLE"))
        assertTrue(blocker.isBlocked("Sub.DoubleClick.Net"))

        // Non-matches
        assertFalse(blocker.isBlocked("example.com"))
        assertFalse(blocker.isBlocked("notads.example.com"))
        assertFalse(blocker.isBlocked("google.com"))
        assertFalse(blocker.isBlocked("com")) // TLD not blocked
        assertFalse(blocker.isBlocked(""))
    }

    @Test
    fun testParseBinary() {
        val stream = javaClass.classLoader?.getResourceAsStream("adblock.bin")
            ?: java.io.FileInputStream("src/main/assets/adblock.bin")
        val bytes = stream.use { it.readBytes() }
        val hashes = AdBlocker.parseBinary(bytes)

        assertTrue("Hashes count should be > 50000", hashes.size > 50000)

        // Verify sorted order for binary search
        for (i in 0 until hashes.size - 1) {
            assertTrue("Hashes should be sorted in ascending order", hashes[i] <= hashes[i + 1])
        }

        val blocker = AdBlocker(hashes)
        assertTrue(blocker.isBlocked("doubleclick.net"))
        assertTrue(blocker.isBlocked("ads.example"))
        assertTrue(blocker.isBlocked("a.b.googlesyndication.com"))
        assertFalse(blocker.isBlocked("wikipedia.org"))
    }

    @Test
    fun testParseHostsAndWriteBinary() {
        val sampleHosts = """
            # This is a comment
            127.0.0.1 localhost
            127.0.0.1 localhost.localdomain
            0.0.0.0 0.0.0.0
            ::1 ip6-localhost
            
            # Valid ad hosts
            0.0.0.0 ads.example
            0.0.0.0 doubleclick.net # inline comment
            127.0.0.1 googlesyndication.com
            0.0.0.0 ADS.EXAMPLE
        """.trimIndent()

        val hashes = AdBlocker.parseHosts(sampleHosts.reader().buffered())
        assertEquals(3, hashes.size)

        for (i in 0 until hashes.size - 1) {
            assertTrue("Hashes should be sorted", hashes[i] <= hashes[i + 1])
        }

        val baos = java.io.ByteArrayOutputStream()
        AdBlocker.writeBinary(hashes, baos)
        val readBack = AdBlocker.parseBinary(baos.toByteArray())
        assertArrayEquals(hashes, readBack)

        val blocker = AdBlocker(readBack)
        assertTrue(blocker.isBlocked("ads.example"))
        assertTrue(blocker.isBlocked("sub.doubleclick.net"))
        assertFalse(blocker.isBlocked("localhost"))
        assertFalse(blocker.isBlocked("google.com"))
    }

    @Test
    fun testLoadExtraFromStream() {
        val blocker = AdBlocker()
        assertFalse(blocker.isBlocked("cloudflareinsights.com"))
        assertFalse(blocker.isBlocked("onclickprediction.com"))

        val extraContent = """
            # Extra list
            cloudflareinsights.com
            0.0.0.0 onclickprediction.com
            127.0.0.1 adsterra.com
        """.trimIndent()

        blocker.loadExtraFromStream(ByteArrayInputStream(extraContent.toByteArray(Charsets.UTF_8)))

        assertTrue(blocker.isBlocked("cloudflareinsights.com"))
        assertTrue(blocker.isBlocked("static.cloudflareinsights.com"))
        assertTrue(blocker.isBlocked("onclickprediction.com"))
        assertTrue(blocker.isBlocked("sub.adsterra.com"))
        assertFalse(blocker.isBlocked("example.com"))
    }
}
