package com.rimboard.keyboard.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Turkish suffix-stripping validity guard.
 *
 * The claim under test is narrow and safe: a word that peels down to a known
 * root through recognised suffixes is accepted as real, so the engine will not
 * try to correct it. Genuine misspellings, which do not peel to a root, are
 * left for the ordinary correction path.
 */
class MorphologyTest {

    /** A handful of roots stands in for the dictionary. */
    private val roots = setOf("ev", "kitap", "gel", "araba", "göz", "çocuk", "su", "yap")
    private val known: (String) -> Boolean = { it in roots }

    private fun accepts(word: String) = Morphology.stemIsKnown("tr", word, known)

    @Test
    fun `a bare root is trivially known`() {
        assertTrue(accepts("ev"))
        assertTrue(accepts("kitap"))
    }

    @Test
    fun `single-suffix inflections of a known root are accepted`() {
        assertTrue("evde (in the house)", accepts("evde"))
        assertTrue("evden (from the house)", accepts("evden"))
        assertTrue("evler (houses)", accepts("evler"))
        assertTrue("kitabı is a mutation, but kitaplar is not", accepts("kitaplar"))
        assertTrue("arabayla (by car)", accepts("arabayla"))
    }

    @Test
    fun `deep suffix stacks are accepted the way the dictionary never could`() {
        // The word from the bug report: "from our books". Absent from any
        // surface-form list, valid Turkish, peels kitap+lar+ımız+dan.
        assertTrue("kitaplarımızdan", accepts("kitaplarımızdan"))
        assertTrue("evlerimizden (from our houses)", accepts("evlerimizden"))
        assertTrue("gözlerimiz (our eyes)", accepts("gözlerimiz"))
    }

    @Test
    fun `verb inflections peel to a known verb root`() {
        assertTrue("geliyor (is coming)", accepts("geliyor"))
        assertTrue("geldi (came)", accepts("geldi"))
        assertTrue("yapabilir would need -abil-; yaptı does not", accepts("yaptı"))
    }

    @Test
    fun `a genuine misspelling does not peel to a root`() {
        // "evxz" is not ev + any suffix; "kitpap" is a transposition typo whose
        // stem is not a word. Both must stay correctable.
        assertFalse(accepts("evxz"))
        assertFalse(accepts("kitpap"))
        assertFalse(accepts("qwertz"))
    }

    @Test
    fun `a suffix with no known root behind it is not a free pass`() {
        // "masada" ends in a valid-looking suffix chain, but "mas"/"masa" is
        // not in this dictionary, so it is not accepted here — the guard needs
        // a real stem, not just a plausible tail.
        assertFalse(accepts("xyzler"))
        assertFalse(accepts("blarımız"))
    }

    @Test
    fun `non-agglutinative languages are never touched by this`() {
        // English "walked" must not be waved through as some stem+suffix; the
        // guard is Turkish-only and returns false for everything else.
        assertFalse(Morphology.stemIsKnown("en", "walked") { it == "walk" })
        assertFalse(Morphology.isAgglutinative("en"))
        assertTrue(Morphology.isAgglutinative("tr"))
    }
}
