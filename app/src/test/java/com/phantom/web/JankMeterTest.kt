package com.phantom.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JankMeterTest {
    @Test
    fun smoothFramesAreNotJanky() {
        val m = JankMeter()
        var janky = false
        for (i in 0 until 120) janky = m.record(16.0, i * 16L)
        assertFalse(janky)
    }

    @Test
    fun mostlySlowFramesAreJanky() {
        val m = JankMeter()
        var janky = false
        for (i in 0 until 40) janky = m.record(if (i % 4 == 0) 16.0 else 60.0, i * 60L)
        assertTrue(janky)
    }

    @Test
    fun fewFramesAreNotEnoughToJudge() {
        val m = JankMeter()
        var janky = false
        for (i in 0 until 10) janky = m.record(100.0, i * 100L)
        assertFalse(janky)
    }

    @Test
    fun oldFramesLeaveTheWindow() {
        val m = JankMeter()
        for (i in 0 until 30) m.record(80.0, i * 50L)
        var janky = true
        for (i in 0 until 200) janky = m.record(16.0, 10_000L + i * 16L)
        assertFalse(janky)
    }
}
