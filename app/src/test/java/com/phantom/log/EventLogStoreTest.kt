package com.phantom.log

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EventLogStoreTest {
    private lateinit var tempDir: File

    @Before
    fun setup() {
        tempDir = Files.createTempDirectory("phantom_log_test").toFile()
        EventLogStore.init(tempDir)
        EventLogStore.clear()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testFiltersDisallowedAndAllowsRequired() {
        assertTrue(EventLogStore.shouldLog("PHANTOM", "PAGE_FINISHED ms=123"))
        assertTrue(EventLogStore.shouldLog("PHANTOM", "MEDIA url=https://example.com/a.m3u8"))
        assertTrue(EventLogStore.shouldLog("PHANTOM", "PLAYER state=3"))
        assertTrue(EventLogStore.shouldLog("PHANTOM", "TRACK select type=text id=1"))
        assertTrue(EventLogStore.shouldLog("PHANTOM", "NAV_BLOCKED https://spam.com"))
        assertTrue(EventLogStore.shouldLog("PHANTOM", "JS_ERR syntax error"))
        assertTrue(EventLogStore.shouldLog("PHANTOM", "RENDER_GONE crashed=true"))
        assertTrue(EventLogStore.shouldLog("CRASH", "Uncaught exception"))
        assertTrue(EventLogStore.shouldLog("APP_START", "Phantom started"))

        // Heavy request logs must NOT be recorded
        assertFalse(EventLogStore.shouldLog("PHANTOM", "REQ https://example.com/asset.js"))
        assertFalse(EventLogStore.shouldLog("PHANTOM", "REQ_BLOCK https://ad.com"))
        assertFalse(EventLogStore.shouldLog("PHANTOM", "TOUCH x=10 y=20"))
    }

    @Test
    fun testWritesAndReadsLogs() {
        EventLogStore.log("PHANTOM", "MEDIA url=https://stream.test/master.m3u8")
        EventLogStore.log("PHANTOM", "REQ https://skip.me")
        EventLogStore.log("PHANTOM", "PLAYER state=3")

        val lines = EventLogStore.readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("MEDIA url="))
        assertTrue(lines[1].contains("PLAYER state=3"))
    }

    @Test
    fun testPersistenceAcrossInit() {
        EventLogStore.log("PHANTOM", "PAGE_FINISHED ms=456")

        // Re-initialize pointing to same dir
        EventLogStore.init(tempDir)
        val lines = EventLogStore.readLines()
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("PAGE_FINISHED ms=456"))
    }

    @Test
    fun testCircularTrimming() {
        // Write enough data to exceed 512 KB
        val largeBlock = "MEDIA " + "x".repeat(1024)
        for (i in 0..600) {
            EventLogStore.log("PHANTOM", "$largeBlock index=$i")
        }

        val file = File(tempDir, EventLogStore.LOG_FILE_NAME)
        assertTrue("File should not exceed MAX_SIZE", file.length() <= EventLogStore.MAX_SIZE + 2048)
        val lines = EventLogStore.readLines()
        assertTrue("Should have retained recent lines", lines.isNotEmpty())
        assertTrue("Last line should have high index", lines.last().contains("index=600"))
    }
}
