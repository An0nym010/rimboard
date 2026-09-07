package com.rimboard.keyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules [ContextError] refuses on, and the one-edit test underneath them.
 *
 * `ContextErrorAccuracyTest` prices the rule against real prose; this pins the
 * shape, because the accuracy figures cannot tell a rule that declines for the
 * right reason from one that declines by accident.
 */
class ContextErrorTest {

    /** "from" is what the context wants; "form" is what was written. */
    private fun decide(
        word: String = "form",
        next: String = "the",
        predictions: List<String> = listOf("from", "in", "at"),
        pairs: Set<Pair<String, String>> = setOf("from" to "the")
    ): String? = ContextError.suggest(
        word = word,
        next = next,
        predictions = { predictions },
        continues = { a, b -> (a to b) in pairs }
    )

    @Test
    fun `both neighbours agreeing on a different real word is the whole trigger`() {
        assertEquals("from", decide())
    }

    @Test
    fun `a word the following word supports is left alone`() {
        // "form the" is itself an attested pair, so the sentence is happy.
        assertNull(decide(pairs = setOf("from" to "the", "form" to "the")))
    }

    @Test
    fun `a word the preceding context predicts is left alone`() {
        // The strongest guard in the rule: if the left context expected this
        // word, the fact that something else also fits is not evidence.
        assertNull(decide(predictions = listOf("form", "from", "in")))
    }

    @Test
    fun `a candidate the following word does not support is not offered`() {
        assertNull(decide(pairs = emptySet()))
    }

    @Test
    fun `a candidate too far from what was written is not offered`() {
        // "in" is predicted and fits the follower, and is nothing like "form".
        assertNull(decide(predictions = listOf("in"), pairs = setOf("in" to "the")))
    }

    @Test
    fun `no context means no opinion`() {
        assertNull(decide(next = ""))
        assertNull(decide(predictions = emptyList()))
        assertNull(decide(word = ""))
    }

    @Test
    fun `the predictions are asked for only once the cheap refusals have passed`() {
        // The map lookup is free and the prediction list is not, so a word the
        // follower already supports must never build one. This runs per word
        // per keystroke on a binder thread.
        var built = 0
        ContextError.suggest(
            word = "form",
            next = "the",
            predictions = { built++; listOf("from") },
            continues = { _, _ -> true }
        )
        assertEquals(0, built)
    }

    @Test
    fun `one edit covers the four ways a keystroke goes wrong`() {
        assertTrue("substitution", ContextError.withinOneEdit("form", "fort"))
        assertTrue("transposition", ContextError.withinOneEdit("form", "from"))
        assertTrue("deletion", ContextError.withinOneEdit("form", "for"))
        assertTrue("insertion", ContextError.withinOneEdit("for", "form"))
    }

    @Test
    fun `two edits is not one`() {
        assertFalse(ContextError.withinOneEdit("form", "fart"))
        assertFalse(ContextError.withinOneEdit("form", "storm"))
        assertFalse(ContextError.withinOneEdit("abc", "xyz"))
    }

    @Test
    fun `a word is not a correction of itself`() {
        // Returning true here would let the loop offer the word it was asked
        // about, which reads as the spell checker underlining a word and then
        // suggesting it back.
        assertFalse(ContextError.withinOneEdit("form", "form"))
        assertFalse(ContextError.withinOneEdit("", ""))
    }

    @Test
    fun `the length gap alone can settle it`() {
        assertFalse(ContextError.withinOneEdit("a", "abc"))
        assertFalse(ContextError.withinOneEdit("abcd", "a"))
        assertFalse(ContextError.withinOneEdit("form", ""))
    }

    @Test
    fun `a transposition at either end is still one edit`() {
        assertTrue(ContextError.withinOneEdit("teh", "the"))
        assertTrue(ContextError.withinOneEdit("thier", "their"))
    }
}
