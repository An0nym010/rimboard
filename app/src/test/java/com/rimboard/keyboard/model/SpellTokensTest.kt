package com.rimboard.keyboard.model

import org.junit.Assert.assertEquals
import org.junit.Test

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
