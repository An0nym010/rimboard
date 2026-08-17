package com.rimboard.keyboard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The particle grid, adapted from Justin Windle's "30,000 Particles" (MIT, see
 * NOTICE). The adaptations are the parts worth testing: the original steps once
 * per frame with no time term, allocates a fresh image buffer per frame, and
 * animates forever. None of the three survives contact with a keyboard.
 */
class ParticleGridTest {

    private fun grid(w: Float = 360f, h: Float = 200f) =
        ParticleGrid(spacing = 14f, margin = 6f).also { it.resize(w, h) }

    @Test
    fun `the grid fills the view and its point array matches its count`() {
        val g = grid()
        assertTrue("no particles", g.size() > 0)
        // drawPoints reads pairs; a mismatch here is an out-of-bounds draw.
        assertEquals(g.size() * 2, g.points().size)
    }

    @Test
    fun `particles return home after a push`() {
        // The spring is the whole effect. Without it the grid scatters once and
        // stays scattered, which looks like the keyboard breaking rather than
        // like an animation.
        val g = grid()
        val home = g.points().copyOf()
        g.touch(180f, 100f)
        repeat(20) { g.step(0.016f) }
        val displaced = g.points().copyOf()
        var moved = 0
        for (i in home.indices) if (Math.abs(home[i] - displaced[i]) > 1f) moved++
        assertTrue("the push moved nothing", moved > 0)
        repeat(600) { g.step(0.016f) }
        for (i in home.indices) {
            assertTrue(
                "particle $i never came home: ${home[i]} vs ${g.points()[i]}",
                Math.abs(home[i] - g.points()[i]) < 0.5f
            )
        }
    }

    @Test
    fun `the same elapsed time gives the same result at any frame rate`() {
        // The original's constants are per frame, so on a 120Hz phone it runs
        // at double speed. Half-length steps twice over must land where
        // full-length steps land once.
        //
        // Not bit-identical, and the threshold says so: the accumulator and the
        // push decay are floats, so two different sequences of additions round
        // differently and can land a substep apart at the boundary. Measured at
        // 1.2px here against 8.8px when the constants were merely scaled by the
        // frame time, so this separates the two designs rather than pinning an
        // exact number.
        val slow = grid()
        val fast = grid()
        slow.touch(180f, 100f)
        fast.touch(180f, 100f)
        repeat(30) { slow.step(1f / 60f) }
        repeat(60) { fast.step(1f / 120f) }
        var worst = 0f
        for (i in slow.points().indices) {
            worst = maxOf(worst, Math.abs(slow.points()[i] - fast.points()[i]))
        }
        assertTrue("60Hz and 120Hz diverged by $worst px", worst < 2f)
    }

    @Test
    fun `a long pause does not fling the grid apart`() {
        // Frames stop while the keyboard is hidden and resume with a huge
        // delta. Unclamped, one step would apply a second of spring at once.
        val g = grid()
        val home = g.points().copyOf()
        g.touch(180f, 100f)
        g.step(45f)
        for (i in home.indices) {
            assertTrue(
                "particle $i left the view: ${g.points()[i]}",
                g.points()[i] > -400f && g.points()[i] < 800f
            )
        }
    }

    @Test
    fun `an untouched grid is at rest and asks for no frames`() {
        // This is what keeps an idle keyboard off the CPU: no push, no motion,
        // no redraw.
        val g = grid()
        assertTrue("a still grid claimed to be animating", !g.settling())
        repeat(10) { g.step(0.016f) }
        assertTrue(!g.settling())
    }

    @Test
    fun `a pushed grid keeps animating until it has actually settled`() {
        val g = grid()
        g.touch(180f, 100f)
        assertTrue(g.settling())
        // Long enough to come to rest; if this never went quiet the keyboard
        // would draw forever after a single keypress.
        var frames = 0
        while (g.settling() && frames < 2000) {
            g.step(0.016f)
            frames++
        }
        assertTrue("never settled after $frames frames", frames < 2000)
    }

    @Test
    fun `resizing to the same bounds keeps the grid as it is`() {
        val g = grid()
        g.touch(180f, 100f)
        repeat(5) { g.step(0.016f) }
        val before = g.points().copyOf()
        g.resize(360f, 200f)
        assertTrue(before.contentEquals(g.points()))
    }

    @Test
    fun `a zero size leaves the grid alone and stepping it is safe`() {
        val g = ParticleGrid(spacing = 14f, margin = 6f)
        g.resize(0f, 0f)
        assertEquals(0, g.size())
        g.touch(1f, 1f)
        g.step(0.016f)
        assertTrue(!g.settling())
    }
}
