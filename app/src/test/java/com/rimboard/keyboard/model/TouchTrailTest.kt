package com.rimboard.keyboard.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The trail of where each letter was tapped.
 *
 * Almost all of this is about the one invariant that makes the feature safe to
 * ship: a trail that is not exactly as long as the word it describes is refused
 * rather than used. Wrong touch data is worse than none — it would argue
 * confidently for the wrong word — and there are a dozen paths in the service
 * that reset the composing buffer, so "somebody will remember to clear it" is
 * not a design.
 */
class TouchTrailTest {

    @Test
    fun `offsets come back in typing order`() {
        val t = TouchTrail()
        t.add(0.1f, -0.2f)
        t.add(0.3f, 0.4f)
        assertArrayEquals(
            floatArrayOf(0.1f, -0.2f, 0.3f, 0.4f), t.offsetsFor(2), 1e-6f
        )
    }

    @Test
    fun `a trail shorter or longer than the word is refused`() {
        val t = TouchTrail()
        t.add(0f, 0f)
        t.add(0f, 0f)
        assertNull("two taps cannot describe three letters", t.offsetsFor(3))
        assertNull("nor one", t.offsetsFor(1))
        assertEquals("two positions is four floats", 4, t.offsetsFor(2)?.size)
    }

    @Test
    fun `an empty word is refused rather than answered with an empty array`() {
        assertNull(TouchTrail().offsetsFor(0))
    }

    @Test
    fun `backspace drops the last tap so the trail keeps pace`() {
        val t = TouchTrail()
        t.add(0.1f, 0.1f)
        t.add(0.2f, 0.2f)
        t.removeLast()
        assertNull("the two-letter reading is gone", t.offsetsFor(2))
        assertArrayEquals(floatArrayOf(0.1f, 0.1f), t.offsetsFor(1), 1e-6f)
    }

    @Test
    fun `backspacing an empty trail is not an error`() {
        // The buffer and the trail are reset by different code paths, so the
        // trail can legitimately be asked to drop something it does not have.
        val t = TouchTrail()
        t.removeLast()
        assertEquals(0, t.size)
    }

    @Test
    fun `clearing leaves nothing to be matched against`() {
        val t = TouchTrail()
        t.add(0.1f, 0.1f)
        t.clear()
        assertNull(t.offsetsFor(1))
    }

    @Test
    fun `a character that arrived without a tap still takes a place`() {
        // A popup pick or a glide letter has no touch to report, but it is a
        // letter in the word — so it holds its position with NaN rather than
        // being skipped, which would shift every later measurement one left.
        val t = TouchTrail()
        t.add(0.1f, 0.1f)
        t.add(Float.NaN, Float.NaN)
        t.add(0.3f, 0.3f)
        val out = t.offsetsFor(3)!!
        assertEquals(0.1f, out[0], 1e-6f)
        assertEquals(true, out[2].isNaN())
        assertEquals(0.3f, out[4], 1e-6f)
    }

    @Test
    fun `a runaway word stops being measured without going out of step`() {
        val t = TouchTrail(cap = 4)
        repeat(10) { t.add(0.5f, 0.5f) }
        assertEquals("still one entry per letter", 10, t.size)
        val out = t.offsetsFor(10)!!
        assertEquals(0.5f, out[0], 1e-6f)
        assertEquals("past the cap it stops measuring", true, out[18].isNaN())
    }
}
