package com.phantom.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterTest {

    @Test
    fun testBlocksAfter10Failures() {
        val limiter = RateLimiter(maxAttempts = 10, windowMs = 60_000L, banDurationMs = 60_000L)
        val ip = "192.168.1.100"
        var time = 1_000_000L

        // 1 to 10 failures: not blocked
        for (i in 1..10) {
            val banned = limiter.recordFailure(ip, time)
            assertFalse("Attempt $i should not ban", banned)
            assertFalse("Attempt $i should not be blocked", limiter.isBlocked(ip, time))
            time += 1000L
        }

        // 11th failure: banned for 60 seconds
        val banned11 = limiter.recordFailure(ip, time)
        assertTrue("11th attempt should ban", banned11)
        assertTrue("Should be blocked", limiter.isBlocked(ip, time))

        // 30 seconds into ban: still blocked
        assertTrue("Still blocked at +30s", limiter.isBlocked(ip, time + 30_000L))

        // Exactly at +60s ban expiration: no longer blocked
        assertFalse("Unblocked after +60s", limiter.isBlocked(ip, time + 60_001L))
    }

    @Test
    fun testWindowExpiresOldAttempts() {
        val limiter = RateLimiter(maxAttempts = 10, windowMs = 60_000L, banDurationMs = 60_000L)
        val ip = "192.168.1.101"
        var time = 1_000_000L

        // 8 failures early on
        for (i in 1..8) {
            limiter.recordFailure(ip, time)
            time += 1000L
        }

        // Fast forward 70 seconds: old attempts expired
        time += 70_000L
        for (i in 1..8) {
            limiter.recordFailure(ip, time)
            time += 1000L
        }

        // Total 16 attempts, but only 8 in current 60s window: not blocked
        assertFalse("Should not be blocked because old attempts expired", limiter.isBlocked(ip, time))
    }

    @Test
    fun testIndependentIPs() {
        val limiter = RateLimiter(maxAttempts = 10, windowMs = 60_000L, banDurationMs = 60_000L)
        val ipA = "192.168.1.50"
        val ipB = "192.168.1.51"
        val time = 1_000_000L

        for (i in 1..11) {
            limiter.recordFailure(ipA, time + i)
        }
        assertTrue(limiter.isBlocked(ipA, time + 20))
        assertFalse("IP B should not be affected by IP A", limiter.isBlocked(ipB, time + 20))
    }
}
