package com.rimboard.keyboard.model

import org.junit.Assert.assertEquals
import org.junit.Test

private const val NEWLINE = "\n"

/**
 * The offsets are the half that cannot be eyeballed: a wrong one puts the red
 * underline under the wrong word, which looks like the checker misjudging a
 * word it never looked at.
 */
class SpellTokensTest {

    private fun words(s: String) = SpellTokens.of(s).map { it.text }
    private fun spans(s: String) = SpellTokens.of(s).map { it.start to it.length }

    @Test
    fun `a plain sentence splits on spaces`() {
        assertEquals(listOf("the", "stroe", "was", "shut"), words("the stroe was shut"))
        assertEquals(listOf(0 to 3, 4 to 5, 10 to 3, 14 to 4), spans("the stroe was shut"))
    }

    @Test
    fun `punctuation is a boundary and is not part of the word`() {
        assertEquals(listOf("Hello", "there"), words("Hello, there!"))
        assertEquals(listOf(0 to 5, 7 to 5), spans("Hello, there!"))
    }

    @Test
    fun `an apostrophe inside a word is part of it`() {
        assertEquals(listOf("don't", "isn\u2019t"), words("don't isn\u2019t"))
    }

    @Test
    fun `quotes around a word are not`() {
        // Trimmed from both ends, and the offset moves with the trim -- an
        // underline that starts on the quote is the visible symptom.
        assertEquals(listOf("quoted"), words("'quoted'"))
        assertEquals(listOf(1 to 6), spans("'quoted'"))
    }

    @Test
    fun `a token of nothing but apostrophes is not a word`() {
        assertEquals(emptyList<String>(), words("'' ''' -- ..."))
    }

    @Test
    fun `repeated words keep their own offsets`() {
        // The reason offsets are carried rather than looked up: indexOf would
        // report the first "the" for both.
        assertEquals(listOf(0 to 3, 4 to 3), spans("the the"))
    }

    @Test
    fun `digits break a word the way punctuation does`() {
        assertEquals(listOf("abc", "def"), words("abc123def"))
    }

    @Test
    fun `empty and blank text yield nothing`() {
        assertEquals(emptyList<String>(), words(""))
        assertEquals(emptyList<String>(), words("   \n\t "))
    }

    private fun opens(s: String) = SpellTokens.of(s).map { it.text to it.startsSentence }

    @Test
    fun `only the first word opens the sentence`() {
        assertEquals(
            listOf("the" to true, "stroe" to false, "was" to false),
            opens("the stroe was")
        )
    }

    @Test
    fun `a full stop opens the next one`() {
        // Both halves of the bug this fixes are here. "Helo" must be judged as
        // a sentence opener, or its capital reads as a name and the typo goes
        // unflagged; and "left" must not be offered as context for it, which
        // is ranking across a full stop.
        assertEquals(
            listOf("He" to true, "left" to false, "Helo" to true, "there" to false),
            opens("He left. Helo there")
        )
    }

    @Test
    fun `question and exclamation marks and newlines do too`() {
        assertEquals(listOf("a" to true, "b" to true), opens("a? b"))
        assertEquals(listOf("a" to true, "b" to true), opens("a! b"))
        assertEquals(listOf("a" to true, "b" to true), opens("a" + NEWLINE + "b"))
        assertEquals(listOf("a" to true, "b" to false), opens("a, b"))
    }

    @Test
    fun `the enders are the ones SentenceContext already defined`() {
        // Not a second copy of ".!?" -- the keyboard side owns that list and
        // this reads it, because the project has shipped a stale duplicate of
        // a list before.
        for (c in SentenceContext.ENDERS) {
            assertEquals(
                "'$c' should open the next sentence",
                listOf("a" to true, "b" to true),
                opens("a" + c + "b")
            )
        }
    }

    @Test
    fun `the follower is the next word in the same sentence`() {
        val t = SpellTokens.of("the stroe was shut")
        assertEquals("stroe", SpellTokens.followerOf(t, 0))
        assertEquals("was", SpellTokens.followerOf(t, 1))
        assertEquals("", SpellTokens.followerOf(t, 3))
    }

    @Test
    fun `a word across a full stop is not a follower`() {
        // The mirror of the context reset. "left" must not be handed "Store"
        // as evidence about it, for the same reason "Store" is not ranked
        // against "left".
        val t = SpellTokens.of("He left. Store was shut")
        assertEquals("", SpellTokens.followerOf(t, 1))
        assertEquals("was", SpellTokens.followerOf(t, 2))
    }

    @Test
    fun `asking past either end is empty rather than a crash`() {
        val t = SpellTokens.of("one two")
        assertEquals("", SpellTokens.followerOf(t, 1))
        assertEquals("", SpellTokens.followerOf(t, 99))
        assertEquals("", SpellTokens.followerOf(emptyList(), 0))
    }

    @Test
    fun `every token can be cut back out of the text it came from`() {
        // The invariant the framework relies on: offset and length must
        // address exactly the word that was judged.
        val text = "Don't, the stroe' was 'shut -- 42 times"
        for (t in SpellTokens.of(text)) {
            assertEquals(t.text, text.substring(t.start, t.start + t.length))
        }
    }
}
