package com.rimboard.keyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        // One chip, not one chip and two blanks. Padding is the view's job
        // now that the strip is five wide -- see StripLayout's closing note.
        assertEquals(listOf("“mndsnfms”"), out.words)
        assertEquals(-1, out.highlight)
        assertEquals("mndsnfms", out.quotedWord)
    }

    @Test
    fun `empty candidate slots do not count as suggestions`() {
        // A blank handed in is not something to arrange the strip around — it
        // would put the word at the front again with an empty chip beside it.
        val out = StripLayout.arrange(
            listOf("mndsnfms", "", ""), autocorrectIndex = -1, known = false, quote = q
        )
        assertEquals(listOf("“mndsnfms”"), out.words)
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

    /**
     * Five chips, and the quoted word still sits second.
     *
     * The strip was three wide and the arrangement was written as a literal
     * triple. Widening it is only safe if the *rule* survives -- the best
     * suggestion leads, the word the user actually typed is beside it, and
     * everything else follows in rank order.
     */
    @Test
    fun `an unknown word keeps its place as the strip widens`() {
        val out = StripLayout.arrange(
            listOf("helko", "hello", "helo", "help", "held", "hell"),
            autocorrectIndex = 1, known = false, quote = q
        )
        assertEquals(
            listOf("hello", "“helko”", "helo", "help", "held"), out.words
        )
        // Re-found by value, as ever: "hello" moved from index 1 to index 0.
        assertEquals(0, out.highlight)
        assertEquals("helko", out.quotedWord)
    }

    /**
     * Width is shared by what each chip has to hold.
     *
     * Equal shares ellipsised "Bananenkuchen" into "Banane…uchen" while
     * "Kinde" beside it sat two-thirds empty — and equal shares are what would
     * make five chips unreadable rather than merely narrow.
     */
    @Test
    fun `a chip gets width in proportion to its word`() {
        val w = StripLayout.weights(listOf("a", "hello", "bananenkuchen", ""))
        // The floor: a one-letter chip is still a target you can hit.
        assertEquals(StripLayout.MIN_WEIGHT, w[0], 0f)
        assertEquals(5f, w[1], 0f)
        // The cap: one long word may not starve the rest.
        assertEquals(StripLayout.MAX_WEIGHT, w[2], 0f)
        // An empty slot is not shown, so it takes nothing.
        assertEquals(0f, w[3], 0f)
    }

    @Test
    fun `the floor is below the cap and both are usable`() {
        assertTrue(StripLayout.MIN_WEIGHT < StripLayout.MAX_WEIGHT)
        assertTrue("five chips at the floor must still be tappable",
            StripLayout.SLOTS * StripLayout.MIN_WEIGHT > 0f)
    }
}
