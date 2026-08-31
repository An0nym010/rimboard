package com.rimboard.keyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ElongationTest {

    @Test
    fun `a trebled letter is a run and a doubled one is not`() {
        assertTrue(Elongation.hasRun("hellooo"))
        assertTrue(Elongation.hasRun("aaa"))
        // Doubled letters are ordinary spelling and must never be touched.
        assertFalse(Elongation.hasRun("hello"))
        assertFalse(Elongation.hasRun("bookkeeper"))
        assertFalse(Elongation.hasRun("spell"))
        assertFalse(Elongation.hasRun(""))
        assertFalse(Elongation.hasRun("a"))
    }

    @Test
    fun `both one and two letter collapses are offered`() {
        // Either can be the real spelling — "hellooo" wants one o, "coool"
        // wants two — and only the dictionary can say which.
        assertEquals(listOf("hello", "helloo"), Elongation.collapsed("hellooo"))
        assertEquals(listOf("col", "cool"), Elongation.collapsed("coool"))
    }

    @Test
    fun `a word with no run collapses to nothing`() {
        // Nothing to propose, so the caller does not ask the dictionary at all.
        assertEquals(emptyList<String>(), Elongation.collapsed("hello"))
        assertEquals(emptyList<String>(), Elongation.collapsed("bookkeeper"))
    }

    @Test
    fun `only the long run is collapsed`() {
        // "oo" and "kk" are ordinary spelling and "sss" is not, in one word.
        // The doubles have to survive or the proposal is a different word.
        assertEquals(listOf("bookkeeps", "bookkeepss"), Elongation.collapsed("bookkeepsss"))
    }

    @Test
    fun `a run at the start is handled`() {
        assertEquals(listOf("ah", "aah"), Elongation.collapsed("aaah"))
    }

    /**
     * A word that is one letter over and over has nothing underneath it.
     *
     * This used to answer "aaa" with "a" and "aa", and the caller took the
     * commoner of the two and committed it. On the device that turned "www."
     * into "W." -- see `ElongationEngineTest`. Neither answer is wrong as a
     * string operation, which is why it was written this way; both are wrong
     * as a claim about what somebody meant.
     */
    @Test
    fun `the whole word being one letter is not an elongation`() {
        assertEquals(emptyList<String>(), Elongation.collapsed("aaa"))
        assertEquals(emptyList<String>(), Elongation.collapsed("www"))
        assertEquals(emptyList<String>(), Elongation.collapsed("ooooo"))
        // Two distinct letters is enough to have something underneath.
        assertEquals(listOf("br", "brr"), Elongation.collapsed("brrr"))
    }

    @Test
    fun `an identical collapse is not offered as an alternative`() {
        // Collapsing must produce something *different*, or the caller would
        // ask the dictionary whether the word is a shortened form of itself.
        assertTrue(Elongation.collapsed("aaa").none { it == "aaa" })
    }
}
