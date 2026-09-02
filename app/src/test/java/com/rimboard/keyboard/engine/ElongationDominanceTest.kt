package com.rimboard.keyboard.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * A trebled letter is only a held-down key if the corpus is emphatic about it.
 *
 * [SuggestionEngine.elongationBase] asks whether collapsing a run of three
 * gives a word the dictionary knows and ranks higher — and until now "higher"
 * meant *by one occurrence*. Every sibling rule in this engine that overrules
 * what somebody typed asks for dominance instead: the bare-key ratio is 50,
 * the transposition ratio is 1,000, the split ratio is 150. And the note
 * beside this one already described the right band and never asked for it:
 * "hello outnumbers hellooo thousands to one, which is what an elongation
 * looks like from the corpus's side."
 *
 * ## What a majority of one was doing
 *
 * Two spellings of comparable frequency that differ by a repeated letter are
 * usually two words, and the shipped lists have plenty:
 *
 *     ro  copiii  59,389 : copii 111,475   1.9x   "the children"
 *     es  xviii      580 : xvi       615   1.1x   Roman numeral
 *     en  viii       585 : vi      2,114   3.6x   Roman numeral
 *
 * Each was underlined as a misspelling **and** rewritten on the space bar.
 * `copiii` is the one that matters: 187 occurrences per million of Romanian
 * text — one word in 5,340 — silently committed as the same noun stripped of
 * its definite article.
 *
 * ## The gap is enormous, so the constant is not delicate
 *
 *     hellooo 4,408x   helloooo 6,759x   hellooooo 27,036x   coool 7,270x
 *     copiii      1.9x  xviii        1.1x  viii          3.6x
 *
 * Swept at 1, 2, 5, 10, 20 and 50: destruction is identical at every value
 * (de 21.0%, en 20.2%, es 27.3%) and no other arm moved. See
 * [SuggestionEngine.ELONGATION_DOMINANCE] for what 20 would additionally buy
 * and cost, and for the one casualty no ratio can reach.
 */
class ElongationDominanceTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("elongdom", "").let { it.delete(); it.mkdirs(); it }
        userData = UserData.inDir(dir)
    }

    @After
    fun tearDown() {
        userData.shutdown()
        dir.deleteRecursively()
    }

    private fun assets(): File =
        listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }

    private fun engine(lang: String): SuggestionEngine {
        val files = HashMap<String, String>()
        for (kind in listOf("dictionaries", "predictions", "suffixes", "prefixes")) {
            File(assets(), "$kind/$lang.txt").takeIf { it.isFile }?.let {
                files["$kind/$lang.txt"] = it.readText()
            }
        }
        return SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
    }

    /**
     * The word this was found on, and the worst of them by a long way.
     *
     * Romanian marks a plural definite by adding -i to a plural already
     * ending in -ii, so the language's ordinary vocabulary contains trebled
     * letters that are not emphasis. "copiii" is the commonest of them in the
     * shipped list.
     */
    @Test
    fun `a Romanian plural definite is a word, not a held-down key`() {
        val e = engine("ro")
        val ro = Locale.forLanguageTag("ro")
        val bad = StringBuilder()
        for (w in listOf("copiii")) {
            if (!e.acceptedWord(w, "ro", ro)) bad.append("\n  \"$w\" is underlined")
            val commit = e.correctionFor(w, "ro", ro)
            if (commit != null) bad.append("\n  \"$w\" + space commits \"$commit\"")
        }
        assertEquals(
            "the definite plural is being treated as its own indefinite with a " +
                "letter held down:$bad",
            "", bad.toString()
        )
    }

    /**
     * Roman numerals, in every language whose corpus holds them.
     *
     * VIII is not VI with the i held down. The rule fired because "vi" and
     * "xi" happen to be commoner strings, by margins between 1.1 and 3.6.
     */
    @Test
    fun `a Roman numeral is not a letter held down`() {
        val bad = StringBuilder()
        for ((lang, words) in listOf(
            "en" to listOf("viii", "xiii"),
            "es" to listOf("xviii", "xiii"),
            "pl" to listOf("viii", "xiii"),
            "hu" to listOf("viii", "xiii"),
            "fr" to listOf("viii"),
            "cs" to listOf("viii", "xiii")
        )) {
            val e = engine(lang)
            val loc = Locale.forLanguageTag(lang)
            for (w in words) {
                val commit = e.correctionFor(w, lang, loc)
                if (commit != null) bad.append("\n  $lang \"$w\" + space commits \"$commit\"")
            }
        }
        assertEquals("a Roman numeral was rewritten as a shorter one:$bad", "", bad.toString())
    }

    /**
     * The control, and the case the whole feature exists for.
     *
     * "hello" outnumbers "hellooo" four thousand to one, so nothing about a
     * dominance bar of ten reaches it — which is the point of choosing a value
     * two orders of magnitude below the thing being protected.
     */
    @Test
    fun `a held-down key is still collapsed`() {
        val e = engine("en")
        val en = Locale.ENGLISH
        val missed = StringBuilder()
        for ((typed, want) in listOf(
            "hellooo" to "hello", "helloooo" to "hello", "hellooooo" to "hello",
            "coool" to "cool", "shhh" to "shh"
        )) {
            val got = e.correctionFor(typed, "en", en)
            if (got != want) missed.append("\n  \"$typed\" commits \"${got ?: "-"}\", wanted \"$want\"")
        }
        assertEquals("an elongation stopped being corrected:$missed", "", missed.toString())
    }

    /**
     * And the other control, from the change that put the frequency
     * comparison here in the first place: German's 1996 reform *created*
     * trebled letters, and those are still kept.
     */
    @Test
    fun `the German reform spellings are still words`() {
        val e = engine("de")
        val de = Locale.GERMAN
        val bad = listOf("helllichten", "volllaufen", "brennnesseln").filterNot {
            e.acceptedWord(it, "de", de) && e.correctionFor(it, "de", de) == null
        }
        assertEquals(
            "a post-reform German spelling is being corrected to the pre-reform one.",
            emptyList<String>(), bad
        )
    }

    /**
     * The constant is a dominance bar and has to stay well clear of both
     * populations, so a future edit cannot quietly move it into the band where
     * the two spellings are different words.
     */
    @Test
    fun `the bar sits between the two populations`() {
        assertTrue(
            "the dominance bar has fallen into the band where a trebled word is " +
                "usually a different word (measured up to 3.6x)",
            SuggestionEngine.ELONGATION_DOMINANCE >= 5
        )
        assertTrue(
            "the dominance bar has risen far enough to stop collapsing genuine " +
                "elongations (measured from 4,408x)",
            SuggestionEngine.ELONGATION_DOMINANCE <= 100
        )
    }

    /** And the base is still chosen from the two collapses, not invented. */
    @Test
    fun `a trebled word with no word underneath it is left alone`() {
        val e = engine("en")
        val en = Locale.ENGLISH
        assertNull(
            "\"www\" is not \"w\" held down; see Elongation.collapsed",
            e.correctionFor("www", "en", en)
        )
    }
}
