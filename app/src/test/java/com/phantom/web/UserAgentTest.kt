package com.phantom.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserAgentTest {

    @Test
    fun testRealTvUserAgentTransformation() {
        val tvUa = "Mozilla/5.0 (Linux; U; Android 12; en-us; TPM191E Build/STT2.230526.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/156.0.8078.47 Mobile Safari/537.36"
        val desktopUa = toDesktopUserAgent(tvUa)

        assertFalse("Should not contain Android", desktopUa.contains("Android"))
        assertFalse("Should not contain Mobile", desktopUa.contains("Mobile"))
        assertFalse("Should not contain wv", desktopUa.contains("; wv"))
        assertTrue("Should contain X11; Linux x86_64", desktopUa.contains("(X11; Linux x86_64)"))
        assertTrue("Should preserve Chrome version", desktopUa.contains("Chrome/156.0.8078.47"))
        assertEquals(
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/156.0.8078.47 Safari/537.36",
            desktopUa
        )
    }

    @Test
    fun testStandardAndroidTvUserAgentTransformation() {
        val standardTvUa = "Mozilla/5.0 (Linux; Android 12; TPM191E) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.47 Mobile Safari/537.36"
        val desktopUa = toDesktopUserAgent(standardTvUa)

        assertFalse(desktopUa.contains("Android"))
        assertFalse(desktopUa.contains("Mobile"))
        assertTrue(desktopUa.contains("(X11; Linux x86_64)"))
        assertTrue(desktopUa.contains("Chrome/156.0.8078.47"))
        assertEquals(
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.47 Safari/537.36",
            desktopUa
        )
    }
}
