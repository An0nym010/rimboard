package com.rimboard.keyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How far the language is allowed to move a finger.
 *
 * Adaptive touch targeting runs before everything else in this engine, so a
 * fault here is not a bad suggestion, it is a letter the user did not type.
 * The whole design rests on one balance — the language settles a tap near a
 * boundary and cannot touch a tap that was clear — and until this file that
 * balance was four constants nothing had ever run.
 *
 * The interesting test is the last one. It does not assert the balance, it
 * *measures* it: how far off a key's centre a tap has to be before the strongest
 * possible language prior can take it away. A number like that is checkable
 * against a thumb; an assertion that "context breaks ties" is not.
 */
class TapArbiterTest {

    // One key, 100 wide and 120 tall, with its left-top at the origin.
    private val kx = 0f
    private val ky = 0f
    private val kw = 100f
    private val kh = 120f

    private fun contends(x: Float, y: Float) = TapArbiter.contends(x, y, kx, ky, kw, kh)
    private fun logP(x: Float, y: Float) = TapArbiter.spatialLogP(x, y, kx, ky, kw, kh)

    @Test
    fun `a key competes a little past its own edge and not far`() {
        assertTrue("the centre of the key does not contend", contends(50f, 60f))
        // 18% of 100 sideways, 15% of 120 vertically.
        assertTrue(contends(-17f, 60f))
        assertFalse("a touch two keys away contends", contends(-19f, 60f))
        assertTrue(contends(50f, -17f))
        assertFalse(contends(50f, -19f))
    }

    @Test
    fun `the spatial score is highest dead centre and falls away`() {
        val centre = logP(50f, 60f)
        assertEquals(0.0, centre, 1e-9)
        assertTrue(logP(70f, 60f) < centre)
        assertTrue(logP(90f, 60f) < logP(70f, 60f))
        // Symmetric: missing left and right by the same amount costs the same.
        assertEquals(logP(30f, 60f), logP(70f, 60f), 1e-9)
    }

    @Test
    fun `pick takes the best of the two together`() {
        // Spatially level, so the language decides.
        assertEquals(1, TapArbiter.pick(doubleArrayOf(0.0, 0.0), doubleArrayOf(-6.0, 0.0)))
        // Language level, so the geometry decides.
        assertEquals(0, TapArbiter.pick(doubleArrayOf(0.0, -1.0), doubleArrayOf(0.0, 0.0)))
    }

    @Test
    fun `pick refuses a malformed question rather than guessing`() {
        assertEquals(-1, TapArbiter.pick(DoubleArray(0), DoubleArray(0)))
        assertEquals(-1, TapArbiter.pick(doubleArrayOf(0.0), doubleArrayOf(0.0, 0.0)))
    }

    /**
     * The pipeline as `KeyboardView` runs it: only the keys that contend are
     * scored, and only those are offered to [TapArbiter.pick].
     *
     * Writing the test without this step is what turned up the finding below —
     * scoring two keys that the geometry would never have put in the same
     * question, and getting an answer that cannot happen.
     */
    private fun arbitrate(x: Float, keys: List<Float>, language: DoubleArray): Int {
        val live = keys.indices.filter { contendsAt(x, keys[it]) }
        if (live.size < 2) return live.firstOrNull() ?: -1
        val spatial = DoubleArray(live.size) {
            TapArbiter.spatialLogP(x, 60f, keys[live[it]], ky, kw, kh)
        }
        val lang = DoubleArray(live.size) { language[live[it]] }
        val picked = TapArbiter.pick(spatial, lang)
        return if (picked < 0) -1 else live[picked]
    }

    private fun contendsAt(x: Float, keyLeft: Float) =
        TapArbiter.contends(x, 60f, keyLeft, ky, kw, kh)

    @Test
    fun `the language cannot reach a tap the geometry never offers it`() {
        // Two keys side by side, the finger exactly on the first, and the
        // second given the strongest prior the character model can express.
        //
        // **The scoring alone would move it.** Dead centre scores -3.3 once the
        // prior is weighed in, against the neighbour's -3.125, so a version of
        // this that scored both keys would take the tap away from under the
        // finger. What stops it is that the neighbour is not a candidate at
        // all: [TapArbiter.contends] widens a key by 18% of its width, so the
        // two only ever compete for the same touch within 0.32 key widths of
        // the boundary.
        //
        // That coupling is the safety property of this whole mechanism, and it
        // is not obvious from either half on its own -- which is exactly why it
        // is written down as a test. Widening EXPAND_X is not a hit-test tweak;
        // it is a change to how far the language may move a finger.
        assertEquals(
            "a tap in the middle of a key was moved to its neighbour",
            0, arbitrate(50f, listOf(0f, 100f), doubleArrayOf(-6.0, 0.0))
        )
    }

    /**
     * The design, as a distance.
     *
     * Walks a tap from the centre of one key toward its neighbour and reports
     * where the strongest possible language prior takes over. Printed as well
     * as asserted, because the useful form is "about a third of a key", which
     * somebody can hold a thumb against -- and because if a constant moves
     * later, the number in the log says how far, not merely that it did.
     */
    @Test
    fun `how far off centre the language can take a tap`() {
        var flipped = -1f
        var x = 50f
        while (x <= 150f) {
            if (arbitrate(x, listOf(0f, 100f), doubleArrayOf(-6.0, 0.0)) == 1) {
                flipped = x
                break
            }
            x += 0.5f
        }
        assertTrue("the language never took over at all", flipped > 0f)
        val fraction = (flipped - 50f) / kw
        println(
            "the strongest language prior takes over %.2f key widths off centre"
                .format(fraction)
        )
        assertTrue(
            "the language can move a tap that was not close to a boundary " +
                "($fraction key widths off centre)",
            fraction >= 0.25f
        )
        assertTrue(
            "the language cannot settle even a boundary tap ($fraction)",
            fraction <= 0.50f
        )
    }

    @Test
    fun `an unambiguous tap is decided without asking the language at all`() {
        // Everywhere outside the contested band there is one candidate, so the
        // prior is not consulted and cannot matter. Checked across the whole
        // key rather than at a point, because "it is only asked near an edge"
        // is the claim, and a claim about a range wants a range.
        var x = 0f
        while (x < 82f) {
            assertEquals(
                "the language was consulted at x=$x, which is not a boundary",
                0, arbitrate(x, listOf(0f, 100f), doubleArrayOf(-6.0, 0.0))
            )
            x += 1f
        }
    }
}
