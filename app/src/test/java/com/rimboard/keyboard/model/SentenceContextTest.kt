package com.rimboard.keyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Reading the next-word context back from the text before the cursor.
 *
 * The version this replaces decided whether the cursor sat at a sentence start
 * and then took the preceding word by scanning straight across the boundary it
 * had just found. Two correct lines, four apart, contradicting each other — and
 * nothing in the suite touched either of them.
 */
class SentenceContextTest {

    private val en = Locale.ENGLISH

    @Test
    fun `after a full stop there is no preceding word`() {
        // The bug, exactly. This used to answer "hello", so the openers that
        // the separator had just put on the strip were immediately replaced by
        // continuations of "hello" — visible as a flicker — and the next word
        // committed was learned as following it, across the full stop.
        val c = SentenceContext.from("Hello. ", en)
        assertTrue(c.atSentenceStart)
        assertEquals("", c.prevWord)
        assertEquals("", c.prevWord2)
    }

    @Test
    fun `mid sentence the previous two words are the context`() {
        val c = SentenceContext.from("I would like to", en)
        assertFalse(c.atSentenceStart)
        assertEquals("to", c.prevWord)
        assertEquals("like", c.prevWord2)
    }

    @Test
    fun `the second word back does not reach into the previous sentence`() {
        // One word into a new sentence: the trigram context must not pair the
        // new word with the last word of the sentence before it.
        val c = SentenceContext.from("Hello there. How ", en)
        assertFalse(c.atSentenceStart)
        assertEquals("how", c.prevWord)
        assertEquals("", c.prevWord2)
    }

    @Test
    fun `every sentence ender counts, including a newline`() {
        for (end in listOf(".", "!", "?", "\n")) {
            val c = SentenceContext.from("Done$end", en)
            assertTrue("'$end' did not end the sentence", c.atSentenceStart)
            assertEquals("", c.prevWord)
        }
    }

    @Test
    fun `trailing spaces do not hide the sentence end but a newline is one`() {
        assertTrue(SentenceContext.from("Done.   ", en).atSentenceStart)
        assertTrue(SentenceContext.from("Done.\t", en).atSentenceStart)
        // Trimming everything would have eaten the newline being tested for.
        assertTrue(SentenceContext.from("Done\n", en).atSentenceStart)
    }

    @Test
    fun `an empty field is a sentence start with no context`() {
        val c = SentenceContext.from("", en)
        assertTrue(c.atSentenceStart)
        assertEquals("", c.prevWord)
        assertEquals("", c.prevWord2)
    }

    @Test
    fun `a comma ends a word but not a sentence`() {
        val c = SentenceContext.from("Well, ", en)
        assertFalse(c.atSentenceStart)
        assertEquals("well", c.prevWord)
    }

    @Test
    fun `words are folded in the given language`() {
        // Turkish dotless I: folding with the wrong locale gives a key the
        // typing path can never produce, so the prediction never matches.
        val tr = Locale("tr", "TR")
        assertEquals("ışık", SentenceContext.from("IŞIK", tr).prevWord)
        assertEquals("işık", SentenceContext.from("İŞIK", tr).prevWord)
    }

    @Test
    fun `an apostrophe stays inside the word`() {
        assertEquals("don't", SentenceContext.from("I don't", en).prevWord)
    }
}
