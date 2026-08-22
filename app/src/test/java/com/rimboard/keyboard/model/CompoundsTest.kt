package com.rimboard.keyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Two words written closed.
 *
 * The rule itself, with a dictionary supplied as a lambda — the same shape
 * [Morphology] uses, and for the same reason: what can be wrong here is the
 * decision, not the lookup.
 */
class CompoundsTest {

    /** A small German dictionary: word to how often the corpus saw it. */
    private val de = mapOf(
        "banane" to 900, "bananen" to 700, "kuchen" to 4000,
        "arbeit" to 9000, "platz" to 5000, "land" to 8000, "tiere" to 2000,
        "haus" to 7000, "schuh" to 1200, "hau" to 600, "auf" to 90000,
        "gabe" to 800, "rand" to 900,
        // in the corpus but barely, which is what the frequency bar is for
        "zell" to 12, "krone" to 40
    )

    private fun split(word: String, lang: String = "de") =
        Compounds.splitOf(lang, word, 500) { de[it] ?: 0 }

    @Test
    fun `a compound of two known words is one word`() {
        assertEquals("bananen" to "kuchen", split("bananenkuchen"))
        assertEquals("land" to "tiere", split("landtiere"))
    }

    @Test
    fun `the linking s belongs to neither half`() {
        // Arbeit + s + platz. Tried only after the plain split, so "hausschuh"
        // is "haus" + "schuh" and never "hau" + "s" + "schuh" — both readings
        // are available in this dictionary on purpose.
        assertEquals("arbeit" to "platz", split("arbeitsplatz"))
        assertEquals("haus" to "schuh", split("hausschuh"))
    }

    @Test
    fun `a half the dictionary barely knows is not a word`() {
        // "nervenzelle" would split if "zell" and "krone" counted. They are in
        // the corpus at a dozen sightings, which is what a compound built out
        // of noise looks like.
        assertNull(split("randzell"))
        assertNull(split("aufkrone"))
    }

    @Test
    fun `a half too short to be a word does not count`() {
        // "auf" is one of the commonest words in German and exactly the reason
        // for a length floor: at three characters every misspelling starting
        // with a preposition becomes a compound.
        assertNull(split("aufgabe"))
        assertEquals(4, Compounds.MIN_PART)
    }

    @Test
    fun `nothing is a compound in a language that writes them open`() {
        // The same test in English would accept "alot", where the right answer
        // is to offer the split instead. This is why the rule is scoped.
        for (lang in listOf("en", "tr", "fr", "nl", "sv")) {
            assertNull(lang, split("bananenkuchen", lang))
        }
        assertNotNull(split("bananenkuchen", "de"))
    }

    @Test
    fun `a word too short to be two words is never one`() {
        assertNull(split("haus"))
        assertNull(split("hausx"))
    }
}
