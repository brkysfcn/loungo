package com.phantom.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleHelperTest {

    @Test
    fun testNoLanguageReturnsNumberedLabel() {
        assertEquals("Subtitle 1", SubtitleHelper.formatSubtitleLabel(null, null, 0, false))
        assertEquals("Subtitle 2", SubtitleHelper.formatSubtitleLabel("", null, 1, false))
        assertEquals("Subtitle 3 [Active]", SubtitleHelper.formatSubtitleLabel("und", null, 2, true))
        assertEquals("Subtitle 1", SubtitleHelper.formatSubtitleLabel("?", null, 0, false))
        assertEquals("Subtitle 1", SubtitleHelper.formatSubtitleLabel("bilinmeyen", null, 0, false))
    }

    @Test
    fun testReplacesBilinmeyenLabelWithNumberedLabel() {
        assertEquals("Subtitle 1", SubtitleHelper.formatSubtitleLabel("bilinmeyen", "BILINMEYEN (1/8219)", 0, false))
        assertEquals("Subtitle 2 [Active]", SubtitleHelper.formatSubtitleLabel(null, "BILINMEYEN (2/8220)", 1, true))
    }

    @Test
    fun testCustomValidLabelPreserved() {
        assertEquals("Director's Commentary", SubtitleHelper.formatSubtitleLabel("en", "Director's Commentary", 0, false))
        assertEquals("Director's Commentary [Active]", SubtitleHelper.formatSubtitleLabel("en", "Director's Commentary", 0, true))
    }
}
