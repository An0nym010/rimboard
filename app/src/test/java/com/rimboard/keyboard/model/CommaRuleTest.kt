package com.rimboard.keyboard.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules [CommaRule] refuses on.
 *
 * Almost every test here is a refusal, which is the same ratio
 * `PostCorrectionTest` has and for the same reason: this writes a character
 * into text the user has already read past, so the interesting question is
 * never "does it fire" but "does it decline".
 *
 * The one refusal that is not here is the biggest one. Whether the field still
 * reads the way the commit left it is the caller's check -- it needs an
 * `InputConnection` and this object needs none -- and it is what stops a comma
 * landing after a full stop, after a comma already there, or across a newline.
 * `CommaWiringTest` holds that one.
 */
class CommaRuleTest {

    /** Every argument at its firing value, so a test can move one thing. */
    private fun decide(
        word: String = "dass",
        share: Int? = 980,
        sentenceInitial: Boolean = false,
        previousWord: String = "sagte"
    ) = CommaRule.applies(word, share, sentenceInitial, previousWord)

    @Test
    fun `a word that nearly always follows a comma gets one`() {
        assertTrue(decide())
    }

    @Test
    fun `a word with no entry is left alone`() {
        // The answer for almost every word in the seven languages that have a
        // list, and for every word in the fifteen that do not.
        assertFalse(decide(share = null))
    }

    @Test
    fun `a word under the bar is left alone`() {
        assertFalse(decide(share = CommaRule.MIN_SHARE - 1))
        assertTrue(decide(share = CommaRule.MIN_SHARE))
    }

    @Test
    fun `the first word of a sentence never gets one`() {
        // A sentence cannot open with a comma before its first word -- and the
        // shares were counted over mid-sentence occurrences only, so firing
        // here would be acting on a number measured over a population that
        // excludes this case. That is this project's most repeated mistake.
        assertFalse(decide(sentenceInitial = true))
    }

    @Test
    fun `there has to be something for the comma to come after`() {
        assertFalse(decide(previousWord = ""))
    }

    @Test
    fun `only a word gets one`() {
        assertFalse(decide(word = ""))
        assertFalse(decide(word = "12"))
        assertFalse(decide(word = "a1"))
        assertFalse(decide(word = "-"))
    }

    @Test
    fun `an apostrophe is part of a word`() {
        // The lists are counted with the same word rule the rest of the
        // keyboard uses, and in the languages that ship one an apostrophe is
        // rare but not impossible.
        assertTrue(decide(word = "l'on"))
    }

    @Test
    fun `the bar is where the sweep put it`() {
        // Restated here so a change to the constant fails something that names
        // the reason. 900 is the lowest value at which every shipping language
        // reaches 95% precision on held-out text; 800 leaves Russian at 85.7%
        // and German at 91.9%, and 950 costs five to ten points of recall
        // everywhere for one or two of precision.
        org.junit.Assert.assertEquals(900, CommaRule.MIN_SHARE)
    }
}
