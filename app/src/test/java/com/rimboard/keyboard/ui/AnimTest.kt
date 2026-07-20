package com.rimboard.keyboard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the motion curves. The endpoints are the part that matters: a curve
 * that does not reach exactly 1 leaves a key preview permanently a fraction
 * too small, or a released key holding a sliver of its pressed tint — the kind
 * of artefact that is easy to introduce while tuning and hard to spot by eye.
 */
class AnimTest {

    @Test
    fun `progress runs from zero to one over the duration`() {
        assertEquals(0f, Anim.progress(1000L, 1000L, 100f), 1e-6f)
        assertEquals(0.5f, Anim.progress(1050L, 1000L, 100f), 1e-6f)
        assertEquals(1f, Anim.progress(1100L, 1000L, 100f), 1e-6f)
    }

    @Test
    fun `progress clamps instead of running past the endpoints`() {
        // Long past the end, and a clock that appears to go backwards.
        assertEquals(1f, Anim.progress(9000L, 1000L, 100f), 1e-6f)
        assertEquals(0f, Anim.progress(900L, 1000L, 100f), 1e-6f)
    }

    @Test
    fun `eased curves hit both endpoints exactly`() {
        assertEquals(0f, Anim.easeOut(0f), 1e-6f)
        assertEquals(1f, Anim.easeOut(1f), 1e-6f)
        assertEquals(0f, Anim.easeIn(0f), 1e-6f)
        assertEquals(1f, Anim.easeIn(1f), 1e-6f)
        assertEquals(0f, Anim.overshoot(0f), 1e-6f)
        assertEquals(1f, Anim.overshoot(1f), 1e-6f)
    }

    @Test
    fun `easeOut decelerates and easeIn accelerates`() {
        // Past the halfway mark early, then slowing.
        assertTrue(Anim.easeOut(0.5f) > 0.5f)
        assertTrue(Anim.easeIn(0.5f) < 0.5f)
    }

    @Test
    fun `eased curves never reverse`() {
        var prevOut = -1f
        var prevIn = -1f
        var t = 0f
        while (t <= 1f) {
            val o = Anim.easeOut(t)
            val i = Anim.easeIn(t)
            assertTrue("easeOut went backwards at $t", o >= prevOut)
            assertTrue("easeIn went backwards at $t", i >= prevIn)
            assertTrue("easeOut out of range at $t: $o", o in 0f..1f)
            assertTrue("easeIn out of range at $t: $i", i in 0f..1f)
            prevOut = o
            prevIn = i
            t += 0.01f
        }
    }

    @Test
    fun `overshoot actually overshoots before settling`() {
        // The bounce is the whole point: it must exceed 1 somewhere.
        var peak = 0f
        var t = 0f
        while (t <= 1f) {
            if (Anim.overshoot(t) > peak) peak = Anim.overshoot(t)
            t += 0.01f
        }
        assertTrue("never overshot, peak was $peak", peak > 1f)
        // But not so far that the popup visibly jumps its own size.
        assertTrue("overshot too far, peak was $peak", peak < 1.25f)
    }

    @Test
    fun `a zero duration scale finishes everything on the first frame`() {
        // What the accessibility "remove animations" setting asks for. Reporting
        // 1 immediately also stops the redraw loops the animations drive.
        Anim.durationScale = 0f
        try {
            assertEquals(1f, Anim.progress(1000L, 1000L, 100f), 1e-6f)
            assertEquals(1f, Anim.progress(1000L, 1000L, 9999f), 1e-6f)
        } finally {
            Anim.durationScale = 1f
        }
    }

    @Test
    fun `duration scale stretches the timeline`() {
        Anim.durationScale = 2f
        try {
            assertEquals(0.5f, Anim.progress(1100L, 1000L, 100f), 1e-6f)
            assertEquals(1f, Anim.progress(1200L, 1000L, 100f), 1e-6f)
        } finally {
            Anim.durationScale = 1f
        }
    }

    @Test
    fun `zero tension degrades to no bounce`() {
        var t = 0f
        while (t <= 1f) {
            assertTrue(Anim.overshoot(t, 0f) <= 1f + 1e-6f)
            t += 0.01f
        }
    }
}
