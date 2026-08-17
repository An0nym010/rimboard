package com.rimboard.keyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StripLayoutTest {

    private val q: (String) -> String = { "“$it”" }

    @Test
    fun `a known word is left exactly as the engine ranked it`() {
        val items = listOf("hello", "hello there", "hellos")
        val out = StripLayout.arrange(items, autocorrectIndex = 0, known = true, quote = q)
        assertEquals(items, out.words)
        assertEquals(0, out.highlight)
        // Nothing is quoted, so nothing needs unwrapping when it is picked.
        assertNull(out.quotedWord)
    }

    @Test
    fun `an unknown word moves to the middle in quotes and keeps both suggestions`() {
        val out = StripLayout.arrange(
            listOf("hellooo", "hello", "hellos"), autocorrectIndex = -1, known = false, quote = q
        )
        assertEquals(listOf("hello", "“hellooo”", "hellos"), out.words)
        // The raw word travels separately, so picking the chip commits
        // `hellooo` and not a pair of quotation marks around it.
        assertEquals("hellooo", out.quotedWord)
    }

    @Test
    fun `a word with nothing to suggest is alone on the strip`() {
        val out = StripLayout.arrange(
            listOf("mndsnfms"), autocorrectIndex = -1, known = false, quote = q
        )
        assertEquals(listOf("“mndsnfms”", "", ""), out.words)
        assertEquals(-1, out.highlight)
        assertEquals("mndsnfms", out.quotedWord)
    }

    @Test
    fun `empty candidate slots do not count as suggestions`() {
        // The engine pads to three, and a padded blank is not something to
        // arrange the strip around — it would put the word at the front again
        // with an empty chip beside it.
        val out = StripLayout.arrange(
            listOf("mndsnfms", "", ""), autocorrectIndex = -1, known = false, quote = q
        )
        assertEquals(listOf("“mndsnfms”", "", ""), out.words)
    }

    @Test
    fun `the autocorrect highlight follows the word it belongs to`() {
        // This is the one that bites silently: the highlight is what the space
        // bar commits, so an index left pointing at the old position would
        // commit whatever moved into it — here, the user's own word instead of
        // the correction, or worse the other way about.
        val out = StripLayout.arrange(
            listOf("teh", "the", "ten"), autocorrectIndex = 1, known = false, quote = q
        )
        assertEquals(listOf("the", "“teh”", "ten"), out.words)
        assertEquals("the", out.words[out.highlight])
    }

    @Test
    fun `no autocorrect target stays no target`() {
        val out = StripLayout.arrange(
            listOf("hellooo", "hello"), autocorrectIndex = -1, known = false, quote = q
        )
        assertEquals(-1, out.highlight)
    }

    @Test
    fun `an empty result is passed through untouched`() {
        val out = StripLayout.arrange(emptyList(), autocorrectIndex = -1, known = false, quote = q)
        assertEquals(emptyList<String>(), out.words)
        assertNull(out.quotedWord)
    }
}
