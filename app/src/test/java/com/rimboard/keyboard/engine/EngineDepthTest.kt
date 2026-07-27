package com.rimboard.keyboard.engine

import com.rimboard.keyboard.model.KeyProximity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * The three things the engine could not do before: complete a prefix that
 * already has a typo in it, notice a missing space, and suggest anything at all
 * for an inflected word an agglutinative corpus never listed.
 *
 * Each was a hole rather than a weakness — not "ranks badly", but "returns
 * nothing" — which is why they were worth fixing ahead of any tuning.
 */
class EngineDepthTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    private fun engine(assets: Map<String, String>): SuggestionEngine =
        SuggestionEngine.forTesting(userData) { path -> assets[path]?.byteInputStream() }

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-depth", "").let { it.delete(); it.mkdirs(); it }
        userData = UserData.inDir(dir)
    }

    @After
    fun tearDown() {
        userData.shutdown()
        dir.deleteRecursively()
    }

    private val en = Locale.ENGLISH
    private val tr = Locale.forLanguageTag("tr")

    private fun dict(text: String, locale: Locale = Locale.ENGLISH) =
        Dictionary(text.byteInputStream(), null, locale)

    // ---- fuzzy prefix completion ----

    @Test
    fun `a prefix with an adjacent-key slip still completes`() {
        // "helko" — k is next to l on qwerty. Exact prefix search returns
        // nothing and the strip goes blank for the rest of the word.
        val d = dict("hello 9000\nhelp 5000\nworld 4000")
        assertTrue(d.byPrefix("helk", 5).isEmpty())
        val fuzzy = d.byPrefixFuzzy("helk", KeyProximity.forLang("en"), 5)
        assertTrue("expected hello in $fuzzy", fuzzy.any { it.first == "hello" })
    }

    @Test
    fun `a transposed prefix completes`() {
        val d = dict("their 9000\nthere 8000")
        val fuzzy = d.byPrefixFuzzy("thier", KeyProximity.forLang("en"), 5)
        assertTrue("expected their in $fuzzy", fuzzy.any { it.first == "their" })
    }

    @Test
    fun `a doubled letter in the prefix completes`() {
        val d = dict("hello 9000")
        val fuzzy = d.byPrefixFuzzy("hhel", KeyProximity.forLang("en"), 5)
        assertTrue("expected hello in $fuzzy", fuzzy.any { it.first == "hello" })
    }

    @Test
    fun `fuzzy results never include what the exact prefix already found`() {
        // Otherwise the same word arrives twice, once at a third of its score,
        // and the merge in the engine could pick the wrong one.
        val d = dict("hello 9000\nhelp 5000")
        val fuzzy = d.byPrefixFuzzy("hel", KeyProximity.forLang("en"), 10)
        assertTrue(fuzzy.none { it.first.startsWith("hel") })
    }

    @Test
    fun `fuzzy completion is scored below an exact match of the same word`() {
        val d = dict("hello 9000")
        val exact = d.byPrefix("hell", 5).first { it.first == "hello" }.second
        val fuzzy = d.byPrefixFuzzy("helk", KeyProximity.forLang("en"), 5)
            .first { it.first == "hello" }.second
        assertTrue("fuzzy $fuzzy should rank under exact $exact", fuzzy < exact)
    }

    @Test
    fun `a prefix too short to mean anything is not fuzzy-matched`() {
        val d = dict("hello 9000\nworld 8000")
        assertTrue(d.byPrefixFuzzy("he", KeyProximity.forLang("en"), 5).isEmpty())
    }

    @Test
    fun `the engine falls back to fuzzy completion when exact finds nothing`() {
        val e = engine(mapOf("dictionaries/en.txt" to "hello 9000\nhelp 5000"))
        val out = e.suggestionsFor(
            "helk", "en", en, allowAutocorrect = false, personalized = false
        ).items
        assertEquals("helk", out.first())   // slot 0 stays verbatim
        assertTrue("expected a suggestion for helk, got $out", out.size > 1)
    }

    // ---- run-together words ----

    @Test
    fun `a missing space is found`() {
        val d = dict("a 90000\nlot 8000\nalpha 3000")
        assertEquals("a" to "lot", d.splitInto("alot"))
    }

    @Test
    fun `a real word is never split`() {
        // "another" contains "an" and "other" and must be left entirely alone.
        val d = dict("another 9000\nan 8000\nother 7000")
        assertNull(d.splitInto("another"))
    }

    @Test
    fun `a run-together form that the corpus itself records is still split`() {
        // The case that made the first version of this useless: a web corpus
        // records "alot" alongside "a lot", so refusing to split anything the
        // dictionary contains refuses exactly the words this is for. Real
        // frequencies from the shipped English list.
        val d = dict("a 14484562\nlot 411660\nalot 831")
        assertEquals("a" to "lot", d.splitInto("alot"))
    }

    @Test
    fun `a real compound is protected by how common it is`() {
        // Also real frequencies. "cannot" is 37x rarer than "can"; "alot" is
        // 495x rarer than "lot". That gap is the whole rule, and it is a ratio
        // so it survives a corpus of a different size.
        val d = dict("cannot 103913\ncan 3826118\nnot 4262273")
        assertNull(d.splitInto("cannot"))

        val e = dict("awhile 5199\na 14484562\nwhile 255970")
        assertNull(e.splitInto("awhile"))

        val f = dict("alright 116629\nal 26447\nright 2576821")
        assertNull(f.splitInto("alright"))
    }

    @Test
    fun `a split into a rare word is refused`() {
        // Without a frequency floor, almost any long word finds some obscure
        // two-letter entry to break on.
        val d = dict("thank 9000\nyo 3\nu 2")
        assertNull(d.splitInto("thankyou"))
    }

    @Test
    fun `the split with the two commonest halves wins`() {
        val d = dict("in 90000\nfact 20000\ninf 800\nact 15000")
        assertEquals("in" to "fact", d.splitInto("infact"))
    }

    @Test
    fun `a split is offered on the strip but never auto-committed`() {
        // The property that matters: it appears as something to tap, and the
        // autocorrect index never points at it, because inserting a word
        // boundary is a bigger intervention than fixing a spelling.
        val e = engine(mapOf("dictionaries/en.txt" to "a 90000\nlot 8000"))
        val r = e.suggestionsFor(
            "alot", "en", en, allowAutocorrect = true, personalized = false
        )
        assertTrue("expected 'a lot' in ${r.items}", "a lot" in r.items)
        assertFalse(
            "a split must never be the autocorrect target",
            r.autocorrectIndex >= 0 && r.items[r.autocorrectIndex].contains(' ')
        )
    }

    @Test
    fun `blocking a split actually stops it being offered`() {
        // Long-pressing a chip blocks whatever was displayed. For a split that
        // is the pair, so checking only the two halves left the chip coming
        // straight back and the control doing nothing visible.
        val e = engine(mapOf("dictionaries/en.txt" to "a 90000\nlot 8000"))
        assertEquals("a lot", e.splitFor("alot", "en", en))
        userData.blockWord("a lot")
        assertNull(e.splitFor("alot", "en", en))
    }

    @Test
    fun `blocking one half does not need the whole pair to be blocked`() {
        val e = engine(mapOf("dictionaries/en.txt" to "a 90000\nlot 8000"))
        userData.blockWord("lot")
        assertNull(e.splitFor("alot", "en", en))
    }

    // ---- generated Turkish forms, end to end ----

    @Test
    fun `an inflected word absent from the corpus still completes`() {
        // The dictionary holds only the stem. Before this, typing "kitapl"
        // produced nothing at all.
        val e = engine(mapOf("dictionaries/tr.txt" to "kitap 9000"))
        val out = e.suggestionsFor(
            "kitapl", "tr", tr, allowAutocorrect = false, personalized = false
        ).items
        assertTrue("expected kitaplar in $out", "kitaplar" in out)
    }

    @Test
    fun `bare keys get accents back on a generated form`() {
        // "kitaplarimizdan" cannot be looked up in any word list; the accented
        // form has to be built from the stem.
        val e = engine(mapOf("dictionaries/tr.txt" to "kitap 9000"))
        val corr = e.correctionCandidates("kitaplarimizdan", "tr", tr, limit = 1)
        assertEquals(listOf("kitaplarımızdan"), corr)
    }

    @Test
    fun `a correctly accented inflection is left alone`() {
        // The other half of the same rule: having generated it, the engine must
        // not then treat the real spelling as something to fix.
        val e = engine(mapOf("dictionaries/tr.txt" to "kitap 9000"))
        assertTrue(e.acceptedWord("kitaplarımızdan", "tr", tr))
        assertTrue(e.correctionCandidates("kitaplarımızdan", "tr", tr, limit = 1).isEmpty())
    }

    @Test
    fun `generation does not fire for a language it does not model`() {
        // The generator is Turkish-only; English must be untouched by it.
        val e = engine(mapOf("dictionaries/en.txt" to "book 9000"))
        val out = e.suggestionsFor(
            "bookl", "en", en, allowAutocorrect = false, personalized = false
        ).items
        assertTrue(out.none { it.startsWith("bookl") && it.length > 5 })
    }
}
