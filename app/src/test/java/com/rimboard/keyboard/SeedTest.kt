package com.rimboard.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The GIF search seed, and specifically the gap between what is searched for
 * and what gets deleted.
 *
 * Picking a GIF deletes the words that seeded the search, because they were the
 * query rather than part of the message. The query is normalised for searching
 * and the field is not, so using the query's length to delete was wrong by
 * exactly the whitespace that normalising removed — and wrong quietly, leaving
 * a couple of stray letters in front of the GIF.
 */
class SeedTest {

    @Test
    fun `query is normalised but the length spans the raw text`() {
        val seed = seedFromTextBeforeCursor("hey  there  cat")!!
        assertEquals("hey there cat", seed.query)
        // 15, not the query's 13 — the two collapsed double spaces.
        assertEquals(15, seed.rawLength)
    }

    @Test
    fun `a trailing space is part of what gets deleted`() {
        // Otherwise the space survives and the GIF lands after a stray gap.
        val seed = seedFromTextBeforeCursor("cat ")!!
        assertEquals("cat", seed.query)
        assertEquals(4, seed.rawLength)
    }

    @Test
    fun `takes only the last few words and leaves the rest alone`() {
        val seed = seedFromTextBeforeCursor("I really want a funny cat")!!
        assertEquals("a funny cat", seed.query)
        assertEquals(11, seed.rawLength)
        // The earlier words are the user's actual message and must survive.
        assertEquals("I really want ", "I really want a funny cat".dropLast(seed.rawLength))
    }

    @Test
    fun `deleting rawLength leaves none of the query behind`() {
        // The property that matters: after the keyboard deletes rawLength
        // characters, nothing the user searched for is still sitting in the
        // field in front of the GIF. This is what the old length-of-query
        // arithmetic got wrong, and only on irregular whitespace.
        for (text in listOf(
            "cat", "cat ", "hey  there  cat", "a b c d e", "one\ttwo  three", "  padded cat "
        )) {
            val seed = seedFromTextBeforeCursor(text)!!
            // The characters about to be deleted must be exactly the ones the
            // query was derived from — no more, no fewer. Stated this way it
            // fails on the old arithmetic: for "hey  there  cat" a length of 13
            // deletes "y  there  cat", which normalises to "y there cat".
            val deleted = text.takeLast(seed.rawLength)
            assertEquals(
                "deleted span does not match the query for \"$text\"",
                seed.query,
                deleted.trim().replace(Regex("\\s+"), " ")
            )
            // And nothing of the query may be left in front of the GIF.
            val remaining = text.dropLast(seed.rawLength)
            assertFalse(
                "query tail survived deletion from \"$text\" (left: \"$remaining\")",
                remaining.isNotEmpty() && !remaining.last().isWhitespace()
            )
        }
    }

    @Test
    fun `nothing typed yields no seed`() {
        assertNull(seedFromTextBeforeCursor(null))
        assertNull(seedFromTextBeforeCursor(""))
        assertNull(seedFromTextBeforeCursor("   "))
    }

    @Test
    fun `a single word is taken whole`() {
        val seed = seedFromTextBeforeCursor("cat")!!
        assertEquals("cat", seed.query)
        assertEquals(3, seed.rawLength)
    }
}
