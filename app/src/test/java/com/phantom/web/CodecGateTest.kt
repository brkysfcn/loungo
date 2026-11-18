package com.phantom.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecGateTest {
    @Test fun blocksOnlyFamiliesWithoutHardware() {
        val hw = setOf("video/x-vnd.on2.vp9", "video/hevc")
        assertEquals(listOf("av01"), CodecGate.blockedPrefixes { it in hw })
    }

    @Test fun nothingBlockedWhenAllHardware() {
        assertTrue(CodecGate.blockedPrefixes { true }.isEmpty())
        assertNull(CodecGate.script(emptyList()))
    }

    @Test fun scriptListsPrefixes() {
        val s = CodecGate.script(listOf("av01", "vp09"))
        assertNotNull(s)
        assertTrue(s!!.contains("['av01','vp09']"))
    }
}
