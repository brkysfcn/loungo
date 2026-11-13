package com.phantom.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairRequestsTest {

    private fun created(r: PairRequests.Start) = (r as PairRequests.Start.Created).req

    @Test
    fun approveFlow() {
        val p = PairRequests()
        val req = created(p.request("10.0.0.5", "Pixel 7", now = 0))
        assertEquals("Pixel 7", req.name)
        assertEquals(PairRequests.Status.Waiting, p.status(req.id, "10.0.0.5", now = 1000))
        assertTrue(p.answer(req.id, true, now = 2000))
        assertEquals(PairRequests.Status.Approved, p.status(req.id, "10.0.0.5", now = 3000))
        // Answer is read once.
        assertEquals(PairRequests.Status.Unknown, p.status(req.id, "10.0.0.5", now = 3500))
    }

    @Test
    fun secondIpIsBusySameIpGetsExisting() {
        val p = PairRequests()
        val req = created(p.request("10.0.0.5", "A", now = 0))
        assertEquals(PairRequests.Start.Busy, p.request("10.0.0.6", "B", now = 10))
        val again = p.request("10.0.0.5", "A", now = 20)
        assertTrue(again is PairRequests.Start.Existing && again.req.id == req.id)
    }

    @Test
    fun denyAndTimeout() {
        val p = PairRequests(timeoutMs = 30_000)
        val a = created(p.request("1.1.1.1", "A", now = 0))
        assertTrue(p.answer(a.id, false, now = 100))
        assertEquals(PairRequests.Status.Denied, p.status(a.id, "1.1.1.1", now = 200))

        val b = created(p.request("1.1.1.1", "B", now = 1000))
        assertEquals(PairRequests.Status.Denied, p.status(b.id, "1.1.1.1", now = 31_000))
        assertFalse(p.answer(b.id, true, now = 31_001))
        // Slot is free again after the timeout.
        assertTrue(p.request("2.2.2.2", "C", now = 31_002) is PairRequests.Start.Created)
    }

    @Test
    fun idIsBoundToIp() {
        val p = PairRequests()
        val req = created(p.request("1.1.1.1", "A", now = 0))
        assertEquals(PairRequests.Status.Unknown, p.status(req.id, "9.9.9.9", now = 10))
        p.answer(req.id, true, now = 20)
        assertEquals(PairRequests.Status.Unknown, p.status(req.id, "9.9.9.9", now = 30))
        assertEquals(PairRequests.Status.Approved, p.status(req.id, "1.1.1.1", now = 40))
    }

    @Test
    fun nameIsCleaned() {
        assertEquals("Android", PairRequests.cleanName(null))
        assertEquals("Android", PairRequests.cleanName("  \n "))
        assertEquals("abc", PairRequests.cleanName("a\u0000b\nc"))
        assertEquals(40, PairRequests.cleanName("x".repeat(100)).length)
    }
}
