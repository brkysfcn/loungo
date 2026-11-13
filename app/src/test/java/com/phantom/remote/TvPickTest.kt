package com.phantom.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class TvPickTest {
    private val a = FoundTv("loungo-aaaa", "Salon", "192.168.1.10", 8765)
    private val b = FoundTv("loungo-bbbb", "Oda", "192.168.1.11", 8765)

    @Test fun nothingFoundWaits() =
        assertEquals(TvPick.Decision.Wait, TvPick.decide(emptyList(), null, 0, 5000))

    @Test fun lastTvConnectsImmediately() =
        assertEquals(TvPick.Decision.Connect(b), TvPick.decide(listOf(a, b), "loungo-bbbb", 0, 10))

    @Test fun loneTvAfterSettle() {
        assertEquals(TvPick.Decision.Wait, TvPick.decide(listOf(a), null, 0, 500))
        assertEquals(TvPick.Decision.Connect(a), TvPick.decide(listOf(a), null, 0, 800))
    }

    @Test fun severalTvsChoose() =
        assertEquals(TvPick.Decision.Choose(listOf(a, b)), TvPick.decide(listOf(a, b), "loungo-zzzz", 0, 900))
}
