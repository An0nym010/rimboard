package com.rimboard.keyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The state machine on its own: what each kind of evidence does.
 *
 * The behaviour against real prose — which is where the constants come from and
 * what they cost — is measured in
 * `com.rimboard.keyboard.engine.LanguageBoostAccuracyTest`. This file is the
 * part that can be stated exactly.
 */
class LanguageBoostTest {

    private fun boost() = LanguageBoost()

    /**
     * Drive [b] to the boosted state the way evidence would.
     *
     * Written against [LanguageBoost.ALT_RUN] rather than assuming one word, so
     * that everything below tests the machine's behaviour and only
     * `one unambiguous word is enough` pins the constant itself.
     */
    private fun engage(b: LanguageBoost) {
        repeat(LanguageBoost.ALT_RUN) { b.note(inPrimary = false, inAlt = true) }
    }

    @Test
    fun `it starts on the language the layout is drawn in`() {
        assertFalse(boost().boosted)
    }

    @Test
    fun `one unambiguous word of the second language is enough`() {
        val b = boost()
        assertEquals(LanguageBoost.ALT_RUN, 1)
        assertTrue("a word only the second language knows must flip it",
            b.note(inPrimary = false, inAlt = true))
        assertTrue(b.boosted)
    }

    @Test
    fun `a word both dictionaries know counts for the primary`() {
        // The common case rather than the odd one: these corpora overlap
        // heavily, which is exactly why it takes several of them to mean
        // anything.
        val b = boost()
        engage(b)
        assertTrue(b.boosted)
        repeat(LanguageBoost.PRIM_RUN - 1) { b.note(inPrimary = true, inAlt = true) }
        assertTrue("must not give the slot back before the run is complete", b.boosted)
        b.note(inPrimary = true, inAlt = true)
        assertFalse(b.boosted)
    }

    @Test
    fun `a word neither dictionary knows says nothing`() {
        // A name, a typo, a word no list has. A passage full of them must not
        // drift in either direction.
        val b = boost()
        engage(b)
        assertTrue(b.boosted)
        repeat(20) { b.note(inPrimary = false, inAlt = false) }
        assertTrue("unknown words must not drag the slot back", b.boosted)

        val c = boost()
        repeat(20) { c.note(inPrimary = false, inAlt = false) }
        assertFalse("nor push it forward", c.boosted)
    }

    @Test
    fun `a run of the primary interrupted by the second language starts over`() {
        val b = boost()
        engage(b)
        repeat(LanguageBoost.PRIM_RUN - 1) { b.note(inPrimary = true, inAlt = false) }
        // One word of the second language resets the run to give the slot back.
        b.note(inPrimary = false, inAlt = true)
        repeat(LanguageBoost.PRIM_RUN - 1) { b.note(inPrimary = true, inAlt = false) }
        assertTrue("the run must be consecutive", b.boosted)
    }

    @Test
    fun `reset returns it to the layout's language`() {
        val b = boost()
        engage(b)
        assertTrue(b.boosted)
        b.reset()
        assertFalse(b.boosted)
    }

    @Test
    fun `note reports whether anything moved`() {
        // The caller uses this to avoid recomputing when nothing changed, so a
        // false positive would cost work on every keystroke.
        val b = boost()
        engage(b)
        assertTrue(b.boosted)
        assertFalse("already boosted, nothing moved",
            b.note(inPrimary = false, inAlt = true))
        assertFalse("part-way through the run, nothing moved yet",
            b.note(inPrimary = true, inAlt = false))
    }

    /**
     * The strong signal must not need more repetitions than the weak one.
     *
     * This is the fault the constants had: three unambiguous words demanded
     * against two ambiguous ones. Stated as a property rather than as the two
     * numbers, so tuning stays free but inverting the reasoning does not.
     */
    @Test
    fun `unambiguous evidence is not held to a higher bar than ambiguous`() {
        assertTrue(
            "ALT_RUN (${LanguageBoost.ALT_RUN}) counts words only the second " +
                "language knows, which is unambiguous; PRIM_RUN " +
                "(${LanguageBoost.PRIM_RUN}) counts words the primary knows, " +
                "most of which are in both lists. The first must not be the " +
                "one asked for more repetitions.",
            LanguageBoost.ALT_RUN <= LanguageBoost.PRIM_RUN
        )
    }
}
