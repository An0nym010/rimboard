package com.rimboard.keyboard.model

import com.rimboard.keyboard.model.PointerGesture.Release
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a finger on a key turns into.
 *
 * The interesting tests here are the two about how the rules *combine*. Each
 * rule on its own is a comparison anybody can read; what nobody can read off
 * the source is that a glide-capable key never cancels, and that the two
 * distance rules are measured from different origins and so cannot be reasoned
 * about as a pair.
 */
class PointerGestureTest {

    // A key 100 wide and 120 tall at the origin, and the view's real slops.
    private val kx = 0f
    private val ky = 0f
    private val kw = 100f
    private val kh = 120f
    private val slopX = 12f
    private val slopY = 18f
    private val glideThreshold = 14f

    private fun slidesOff(x: Float, y: Float) =
        PointerGesture.slidesOff(x, y, kx, ky, kw, kh, slopX, slopY)

    private fun armsGlide(x: Float, y: Float, downX: Float = 50f, downY: Float = 60f) =
        PointerGesture.armsGlide(x, y, downX, downY, glideThreshold)

    // ---- the release priority ----------------------------------------------

    @Test
    fun `a release is decided in one order, and this is it`() {
        assertEquals(Release.TAP, PointerGesture.releaseAction(false, false, false, false, false))
        assertEquals(Release.GLIDE, PointerGesture.releaseAction(false, true, false, false, false))
        assertEquals(Release.POPUP, PointerGesture.releaseAction(false, false, true, false, false))
        assertEquals(Release.CURSOR, PointerGesture.releaseAction(false, false, false, true, false))
        assertEquals(
            Release.ALREADY_DONE,
            PointerGesture.releaseAction(false, false, false, false, true)
        )
    }

    @Test
    fun `cancelled beats everything, and a tap is the last resort`() {
        // The priority is the whole content of that `when`, and a pointer
        // really can be several of these at once. If cancelled ever stopped
        // winning, a touch the user dragged away to abandon would still type.
        assertEquals(
            Release.NOTHING,
            PointerGesture.releaseAction(
                cancelled = true, glide = true, popupOpen = true,
                cursorMode = true, handledOnDown = true
            )
        )
        // And a glide outranks a popup, which outranks a cursor drag.
        assertEquals(
            Release.GLIDE,
            PointerGesture.releaseAction(false, glide = true, popupOpen = true,
                cursorMode = true, handledOnDown = true)
        )
        assertEquals(
            Release.POPUP,
            PointerGesture.releaseAction(false, false, popupOpen = true,
                cursorMode = true, handledOnDown = true)
        )
    }

    @Test
    fun `only a tap types a letter`() {
        // Every other outcome is handled elsewhere or deliberately does
        // nothing, so this is the one branch that can put a character in the
        // field. Stated as a test because it is the safety property: any new
        // case added above TAP silently takes keystrokes away from it.
        val typing = Release.values().filter {
            PointerGesture.releaseAction(
                cancelled = it == Release.NOTHING,
                glide = it == Release.GLIDE,
                popupOpen = it == Release.POPUP,
                cursorMode = it == Release.CURSOR,
                handledOnDown = it == Release.ALREADY_DONE
            ) == Release.TAP
        }
        assertEquals(listOf(Release.TAP), typing)
    }

    // ---- the two distance rules --------------------------------------------

    @Test
    fun `a glide arms on travel from the finger, not from the key`() {
        assertFalse("a still finger armed a glide", armsGlide(50f, 60f))
        assertTrue(armsGlide(65f, 60f))
        assertTrue(armsGlide(50f, 75f))
        assertFalse(armsGlide(63f, 73f))
        // Pressing at the key's edge and rolling inward arms one, because the
        // measure is travel and not position.
        assertTrue(armsGlide(20f, 60f, downX = 5f, downY = 60f))
    }

    @Test
    fun `a press is abandoned by distance from the key, not by travel`() {
        assertFalse("a finger inside the key was abandoned", slidesOff(50f, 60f))
        assertFalse("the slop was not honoured", slidesOff(-11f, 60f))
        assertTrue(slidesOff(-13f, 60f))
        assertFalse(slidesOff(50f, -17f))
        assertTrue(slidesOff(50f, -19f))
    }

    @Test
    fun `the two rules are not two halves of one rule`() {
        // They read as a pair and are measured from different origins, so a
        // press can satisfy both at once. Pressing the left edge of the key and
        // sliding left has travelled far enough to arm a glide *and* far enough
        // off the key to be abandoned. Which one happens is not decided here --
        // it is decided by the caller asking only one of them, and the next
        // test is that rule.
        val x = -13f
        assertTrue(armsGlide(x, 60f, downX = 5f, downY = 60f))
        assertTrue(slidesOff(x, 60f))
    }

    /**
     * The composition `KeyboardView` performs, which is where the real rule is.
     *
     * A key that can glide is never asked whether the finger slid off it. That
     * is what lets a swipe cross the whole keyboard without the press being
     * abandoned three keys in — and it is invisible from either rule alone,
     * because both of them say "yes" for the same touch.
     */
    private fun outcome(x: Float, y: Float, glideCapable: Boolean): Release {
        if (glideCapable && armsGlide(x, y)) {
            return PointerGesture.releaseAction(false, glide = true, false, false, false)
        }
        val cancelled = !glideCapable && slidesOff(x, y)
        return PointerGesture.releaseAction(cancelled, false, false, false, false)
    }

    @Test
    fun `a key that can glide is never abandoned for sliding away`() {
        // Far off the key in both directions -- three keys away, which is an
        // ordinary swipe.
        assertEquals(Release.GLIDE, outcome(400f, 60f, glideCapable = true))
        // The same touch on a key that cannot glide is abandoned instead.
        assertEquals(Release.NOTHING, outcome(400f, 60f, glideCapable = false))
    }

    @Test
    fun `a still finger on either kind of key is an ordinary tap`() {
        assertEquals(Release.TAP, outcome(50f, 60f, glideCapable = true))
        assertEquals(Release.TAP, outcome(50f, 60f, glideCapable = false))
    }
}
