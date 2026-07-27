package com.rimboard.keyboard.model

import com.rimboard.keyboard.engine.Dictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turkish inflection, generated rather than looked up.
 *
 * Every expected form here is an ordinary word any Turkish speaker would
 * produce without thinking, and none of them can be assumed present in a
 * frequency list — which is the whole reason the generator exists. If vowel
 * harmony is wrong the output is not merely unhelpful, it is visibly not
 * Turkish, so these are worth pinning individually.
 */
class TurkishMorphTest {

    private fun known(vararg words: String): (String) -> Boolean {
        val set = words.toSet()
        return { w -> w in set }
    }

    // ---- vowel harmony ----

    @Test
    fun `plural follows the last vowel of the stem`() {
        assertEquals("kitaplar", TurkishMorph.apply("kitap", "lAr"))  // back
        assertEquals("evler", TurkishMorph.apply("ev", "lAr"))        // front
        assertEquals("gözler", TurkishMorph.apply("göz", "lAr"))      // front rounded
        assertEquals("okullar", TurkishMorph.apply("okul", "lAr"))    // back rounded
    }

    @Test
    fun `four-way harmony picks among all four high vowels`() {
        assertEquals("evim", TurkishMorph.apply("ev", "Im"))     // front unrounded
        assertEquals("kızım", TurkishMorph.apply("kız", "Im"))   // back unrounded
        assertEquals("gözüm", TurkishMorph.apply("göz", "Im"))   // front rounded
        assertEquals("okulum", TurkishMorph.apply("okul", "Im")) // back rounded
    }

    @Test
    fun `the case suffix hardens after a voiceless consonant`() {
        assertEquals("kitapta", TurkishMorph.apply("kitap", "DA"))
        assertEquals("evde", TurkishMorph.apply("ev", "DA"))
        assertEquals("kitaptan", TurkishMorph.apply("kitap", "DAn"))
        assertEquals("evden", TurkishMorph.apply("ev", "DAn"))
    }

    @Test
    fun `the buffer consonant appears only after a vowel`() {
        assertEquals("arabaya", TurkishMorph.apply("araba", "YA"))
        assertEquals("eve", TurkishMorph.apply("ev", "YA"))
    }

    @Test
    fun `harmony inside a suffix follows the suffix, not the stem`() {
        // The textbook stack. Each vowel harmonises with the one before it, so
        // resolving all of them against the stem's final vowel gets this wrong
        // as soon as a suffix contains a vowel of its own.
        assertEquals("kitaplarımızdan", TurkishMorph.apply("kitap", "lArImIzDAn"))
        assertEquals("evlerimizden", TurkishMorph.apply("ev", "lArImIzDAn"))
        assertEquals("gözlerinizde", TurkishMorph.apply("göz", "lArInIzDA"))
    }

    @Test
    fun `a stem with no vowel produces nothing rather than a guess`() {
        assertTrue(TurkishMorph.inflections("crm").isEmpty())
    }

    // ---- consonant softening ----

    @Test
    fun `a polysyllabic stem softens before a vowel but not before a consonant`() {
        val forms = TurkishMorph.inflections("kitap")
        assertTrue("expected kitabı in $forms", "kitabı" in forms)
        assertTrue("expected kitaplar in $forms", "kitaplar" in forms)
        // The softened stem must not leak into consonant-initial suffixes.
        assertTrue("kitablar must not be generated", "kitablar" !in forms)
    }

    @Test
    fun `a monosyllabic stem does not soften`() {
        assertNull(TurkishMorph.softened("top"))
        assertNull(TurkishMorph.softened("at"))
        assertEquals("kitab", TurkishMorph.softened("kitap"))
        assertEquals("ağac", TurkishMorph.softened("ağaç"))
    }

    @Test
    fun `k softens to g after n, l or r and to soft g otherwise`() {
        assertEquals("reng", TurkishMorph.softened("renk"))
        assertEquals("bardağ", TurkishMorph.softened("bardak"))
    }

    // ---- completion ----

    @Test
    fun `a partial inflection completes from a known stem`() {
        // "kitaplar" is absent from the word list; only "kitap" is there.
        val out = TurkishMorph.completionsFor("kitapl", 5, known("kitap"))
        assertTrue("expected kitaplar in $out", "kitaplar" in out)
    }

    @Test
    fun `completion finds the longest stem, not the shortest`() {
        // Both "ki" and "kitap" are words; the one being typed is "kitap".
        val out = TurkishMorph.completionsFor("kitapla", 5, known("ki", "kitap"))
        assertTrue("expected kitaplar in $out", "kitaplar" in out)
        assertTrue(out.all { it.startsWith("kitapla") })
    }

    @Test
    fun `completion finds a stem hiding behind a softened consonant`() {
        // The text holds "kitab"; the dictionary holds "kitap".
        val out = TurkishMorph.completionsFor("kitabı", 5, known("kitap"))
        assertTrue("expected a longer form in $out", out.isNotEmpty())
        assertTrue(out.all { it.startsWith("kitabı") })
    }

    @Test
    fun `completions always continue what was typed`() {
        // The safety property the whole feature rests on: this proposes words,
        // it never replaces the one in front of the user.
        val out = TurkishMorph.completionsFor("evler", 8, known("ev"))
        assertTrue(out.isNotEmpty())
        assertTrue(out.all { it.startsWith("evler") && it.length > "evler".length })
    }

    @Test
    fun `an unknown stem yields no completions`() {
        assertTrue(TurkishMorph.completionsFor("qwertyl", 5, known("kitap")).isEmpty())
    }

    // ---- accent restoration on inflected words ----

    @Test
    fun `bare keys restore accents on a word no dictionary contains`() {
        // "kitaplarımızdan" is a perfectly ordinary word and is in no corpus.
        // Typed on bare keys it can only be reached by generating it.
        val out = TurkishMorph.accentedInflection(
            "kitaplarimizdan",
            fold = { Dictionary.foldDiacritics(it) },
            accentedStem = { bare -> if (bare == "kitap") "kitap" else null }
        )
        assertEquals("kitaplarımızdan", out)
    }

    @Test
    fun `the stem itself may also need its accents back`() {
        // "kağıtlarımız" from "kagitlarimiz": both the stem and the suffixes
        // are missing accents.
        val out = TurkishMorph.accentedInflection(
            "kagitlarimiz",
            fold = { Dictionary.foldDiacritics(it) },
            accentedStem = { bare -> if (bare == "kagit") "kağıt" else null }
        )
        assertEquals("kağıtlarımız", out)
    }

    @Test
    fun `a bare word that spells nothing real returns null`() {
        val out = TurkishMorph.accentedInflection(
            "qwertylarimiz",
            fold = { Dictionary.foldDiacritics(it) },
            accentedStem = { null }
        )
        assertNull(out)
    }
}
