package com.rimboard.keyboard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sampling rule for GIF thumbnails. Its job is to bound memory without
 * making the grid look worse, and both halves of that are easy to get wrong in
 * a way nothing reports: too timid and the keyboard is still holding megabytes
 * it cannot show, too aggressive and every thumbnail is upscaled and soft.
 */
class ThumbsTest {

    // A two-column grid on a 1080p phone at 3x: tiles about 540 x 276 px.
    private val reqW = 540
    private val reqH = 276

    @Test
    fun `an image already at tile size is not sampled`() {
        assertEquals(1, Thumbs.sampleSizeFor(540, 276, reqW, reqH))
        assertEquals(1, Thumbs.sampleSizeFor(600, 300, reqW, reqH))
    }

    @Test
    fun `an image smaller than the tile is never sampled`() {
        // Sampling here would decode below what the tile draws and the result
        // would be upscaled — paying in quality for memory already saved.
        assertEquals(1, Thumbs.sampleSizeFor(120, 90, reqW, reqH))
        assertEquals(1, Thumbs.sampleSizeFor(1, 1, reqW, reqH))
    }

    @Test
    fun `a large image is halved until one more halving would go under`() {
        // 1920x1080: halving twice gives 480x270, which is below the tile, so
        // the answer is one halving — 960x540, still covering it.
        assertEquals(2, Thumbs.sampleSizeFor(1920, 1080, reqW, reqH))
        // 4320x2208 is exactly 8x the tile and may be halved three times.
        assertEquals(8, Thumbs.sampleSizeFor(4320, 2208, reqW, reqH))
    }

    @Test
    fun `the result never decodes below the tile in either dimension`() {
        // The property that matters, over shapes a provider might really send:
        // whatever comes back, both dimensions must still cover the tile — as
        // long as the source did to begin with.
        for (w in listOf(560, 640, 800, 1024, 1280, 1920, 2560, 3840)) {
            for (h in listOf(280, 360, 480, 540, 720, 1080, 1440, 2160)) {
                val s = Thumbs.sampleSizeFor(w, h, reqW, reqH)
                assertTrue("sample $s for ${w}x$h is not a power of two", s and (s - 1) == 0)
                assertTrue(
                    "${w}x$h sampled by $s gives ${w / s}x${h / s}, under the ${reqW}x$reqH tile",
                    w / s >= reqW && h / s >= reqH
                )
            }
        }
    }

    @Test
    fun `a very wide image is limited by its short edge`() {
        // A banner-shaped preview: plenty of width to give up, almost no
        // height. Sampling on the long edge alone would leave the short one
        // far below the tile, which is the mistake this shape exposes.
        val s = Thumbs.sampleSizeFor(4000, 300, reqW, reqH)
        assertEquals(1, s)
    }

    @Test
    fun `an undecodable header does not produce a nonsense sample size`() {
        // BitmapFactory reports 0 or -1 for a header it could not read, and
        // those must not be arithmetic input.
        assertEquals(1, Thumbs.sampleSizeFor(0, 0, reqW, reqH))
        assertEquals(1, Thumbs.sampleSizeFor(-1, -1, reqW, reqH))
        assertEquals(1, Thumbs.sampleSizeFor(1920, 0, reqW, reqH))
        // And a tile size of zero, which is what an unlaid-out view would give.
        assertEquals(1, Thumbs.sampleSizeFor(1920, 1080, 0, 0))
    }
}
