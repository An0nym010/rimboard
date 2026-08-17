package com.rimboard.keyboard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The starfield runs on every animation frame behind a keyboard, so anything
 * wrong here is paid sixty times a second while someone is typing. A
 * simulation is also the kind of code that looks right and then drifts,
 * accumulates or quietly stops, none of which is visible from a screenshot.
 */
class StarfieldTest {

    private fun field(w: Float = 400f, h: Float = 200f): Starfield =
        Starfield(count = 60, seed = 7).also { it.resize(w, h) }

    @Test
    fun `stars stay inside the view however long it runs`() {
        // Wrapping is what keeps the count constant and the sky populated. If
        // it were wrong the field would slowly empty from one edge, which
        // takes minutes to notice by eye and no time at all here.
        val f = field()
        repeat(4000) { f.step(0.016f) }
        for (s in f.stars()) {
            assertTrue("x escaped: ${s.x}", s.x in -8f..408f)
            assertTrue("y escaped: ${s.y}", s.y in -8f..208f)
        }
        assertEquals(60, f.stars().size)
    }

    @Test
    fun `a huge frame gap does not teleport the sky`() {
        // The frame callback stops while the keyboard is hidden and resumes
        // with an enormous delta. Unclamped, the first frame back would move
        // every star across the screen at once — the animation would visibly
        // jump every time the keyboard opened.
        val f = field()
        val before = f.stars().map { it.x to it.y }
        f.step(30f)
        val after = f.stars().map { it.x to it.y }
        for (i in before.indices) {
            val dx = Math.abs(before[i].first - after[i].first)
            val dy = Math.abs(before[i].second - after[i].second)
            assertTrue("moved $dx horizontally in one frame", dx < 20f)
            assertTrue("moved $dy vertically in one frame", dy < 20f)
        }
    }

    @Test
    fun `a touch pushes nearby stars away from it and distant ones not at all`() {
        val f = field()
        val near = f.stars().minByOrNull { Math.hypot((it.x - 200.0), (it.y - 100.0)) }!!
        val far = f.stars().maxByOrNull { Math.hypot((it.x - 200.0), (it.y - 100.0)) }!!
        val nearBefore = Math.hypot((near.x - 200.0), (near.y - 100.0))
        val farBefore = Math.hypot((far.x - 200.0), (far.y - 100.0))
        f.touch(200f, 100f)
        repeat(10) { f.step(0.016f) }
        val nearAfter = Math.hypot((near.x - 200.0), (near.y - 100.0))
        val farAfter = Math.hypot((far.x - 200.0), (far.y - 100.0))
        assertTrue("the near star did not move away", nearAfter > nearBefore)
        // Drift still moves it a little; what matters is that the push did not.
        assertTrue("a distant star was pushed", Math.abs(farAfter - farBefore) < 3.0)
    }

    @Test
    fun `the push fades so the field goes quiet again`() {
        // The view stops asking for frames once this returns false. If it
        // never settled the keyboard would animate forever after one keypress.
        val f = field()
        f.touch(200f, 100f)
        assertTrue(f.settling())
        repeat(120) { f.step(0.016f) }
        assertTrue("the push never ended", !f.settling())
    }

    @Test
    fun `brightness stays within range even at the centre of a push`() {
        val f = field()
        f.touch(200f, 100f)
        for (s in f.stars()) {
            val b = f.brightness(s)
            assertTrue("brightness out of range: $b", b in 0f..1f)
        }
    }

    @Test
    fun `resizing to the same bounds does not rebuild the sky`() {
        // Layout reports the same size repeatedly; restarting there would
        // scatter the stars on every pass and look like flicker.
        val f = field()
        repeat(50) { f.step(0.016f) }
        val before = f.stars().map { it.x }
        f.resize(400f, 200f)
        assertEquals(before, f.stars().map { it.x })
    }

    @Test
    fun `a zero size is ignored rather than emptying the field`() {
        // onSizeChanged can report 0 before the first real layout.
        val f = field()
        f.resize(0f, 0f)
        assertEquals(60, f.stars().size)
    }

    @Test
    fun `stepping before any size is set does nothing and does not throw`() {
        val f = Starfield(count = 20, seed = 3)
        f.step(0.016f)
        f.touch(10f, 10f)
        f.step(0.016f)
        assertTrue(f.stars().isEmpty())
    }
}
