package com.rimboard.keyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Words written with an apostrophe in them.
 *
 * The rule is data-driven and names no language, so these fixtures are the two
 * shapes rather than the two languages: the apostrophe stays on the right in
 * English and on the left in French and Italian, and the dictionary holds
 * whichever half owns it.
 */
class ElisionTest {

    /** English: the suffix is the entry. */
    private val english = mapOf(
        "don" to 4158644, "'t" to 9628970, "you" to 28787591, "'re" to 4059719,
        "we" to 6755687, "'ll" to 2913428, "it" to 9000000, "'s" to 14291013
    )

    /** French and Italian: the elided article is the entry. */
    private val romance = mapOf(
        "l'" to 3675406, "homme" to 90000, "qu'" to 2520219, "il" to 900000,
        "dell'" to 238490, "amore" to 40000, "c'" to 4184576, "è" to 700000
    )

    private fun split(word: String, dict: Map<String, Int>) =
        Elision.splitOf(word, 500) { dict[it] ?: 0 }

    @Test
    fun `English keeps the apostrophe on the suffix`() {
        assertEquals("don" to "'t", split("don't", english))
        assertEquals("you" to "'re", split("you're", english))
        assertEquals("we" to "'ll", split("we'll", english))
        assertEquals("it" to "'s", split("it's", english))
    }

    @Test
    fun `French and Italian keep it on the article`() {
        assertEquals("l'" to "homme", split("l'homme", romance))
        assertEquals("qu'" to "il", split("qu'il", romance))
        assertEquals("dell'" to "amore", split("dell'amore", romance))
        assertEquals("c'" to "è", split("c'è", romance))
    }

    @Test
    fun `a curly apostrophe is the same word`() {
        // Autocorrect and several keyboards produce U+2019, and a word is not
        // a different word for having been typed on a different keyboard.
        //
        // Asked of the *straight* map, which is the whole point. The previous
        // version handed the frequency function a "’t" entry of its own
        // invention and asserted it came back, so it passed while the feature
        // did not: no shipped list holds a single U+2019, and the real lookup
        // was for a key that cannot exist. A fixture that invents the data
        // under test proves the test rather than the code — see
        // `ElisionRealListsTest` for the same case put to the lists that ship.
        assertEquals("don" to "'t", split("don’t", english))
        assertEquals("l'" to "homme", split("l’homme", romance))
    }

    @Test
    fun `both halves have to be known`() {
        assertNull(split("asdf'qwer", english))
        assertNull("an unknown left half was accepted", split("xyzzy't", english))
        assertNull("an unknown right half was accepted", split("don'xyzzy", english))
    }

    @Test
    fun `a rare half does not count`() {
        // One half is often a single letter and a corpus has a stray entry for
        // nearly every letter, so the floor is doing more work here than it
        // does for a compound.
        val thin = mapOf("don" to 4158644, "'t" to 12)
        assertNull(Elision.splitOf("don't", 500) { thin[it] ?: 0 })
    }

    @Test
    fun `an apostrophe at either end is not an elision`() {
        assertNull(split("'tis", english))
        assertNull(split("dons'", english))
        assertNull(split("'", english))
    }

    @Test
    fun `a word with no apostrophe is not one`() {
        assertNull(split("dont", english))
        assertNull(split("", english))
    }

    @Test
    fun `a language whose list holds no apostrophes matches nothing`() {
        // German and Turkish ship without a single apostrophe entry, so this
        // rule is inert for them without anybody maintaining a list of which
        // languages it applies to. That is the reason it names none.
        val german = mapOf("kann" to 900000, "nicht" to 900000)
        assertNull(split("kann'nicht", german))
    }
}
