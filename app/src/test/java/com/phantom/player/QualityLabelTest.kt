package com.phantom.player

import org.junit.Assert.assertEquals
import org.junit.Test

class QualityLabelTest {
    @Test
    fun letterboxedFullHdIs1080p() {
        assertEquals("1080p", QualityLabel.of(1920, 800))
        assertEquals("1080p", QualityLabel.of(1920, 1080))
    }

    @Test
    fun commonSizes() {
        assertEquals("720p", QualityLabel.of(1280, 534))
        assertEquals("480p", QualityLabel.of(854, 356))
        assertEquals("2160p", QualityLabel.of(3840, 1600))
    }

    @Test
    fun thumbnailCuesAreRecognised() {
        org.junit.Assert.assertTrue(QualityLabel.isThumbnailCue("thumbnails.jpg#xywh=600,0,150,84"))
        org.junit.Assert.assertTrue(QualityLabel.isThumbnailCue("https://cdn.x/sprite-3.webp?v=2#xywh=0,0,160,90"))
        org.junit.Assert.assertTrue(QualityLabel.isThumbnailCue("thumb_0001.png"))
        org.junit.Assert.assertFalse(QualityLabel.isThumbnailCue("Taşlaşmış yüzler izliyorlar karanlıkta."))
        org.junit.Assert.assertFalse(QualityLabel.isThumbnailCue("See photo.jpg on the wall"))
        org.junit.Assert.assertFalse(QualityLabel.isThumbnailCue(null))
    }
}
