package com.rimboard.keyboard.engine

import com.rimboard.keyboard.model.Contractions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * The completion table was the restoration table, and inherited its exclusions.
 *
 * [Contractions] keeps two maps, and what decides which one a contraction goes
 * in — or whether it goes in either — is the risk of putting an apostrophe into
 * a word somebody typed *without* one. The class comment names the third class
 * that creates: "its", "were" and "well" appear in neither list, because their
 * bare form is not only a real word but an extremely common and usually-correct
 * one, and a keyboard that turned "its" into "it's" would be wrong far more
 * often than right.
 *
 * That is the right call about restoration and it says nothing about
 * completion. `completionsFor` reads the same maps backwards, and so inherited
 * an exclusion whose entire reason has already been settled by the time it is
 * asked — **somebody who has typed `it'` cannot have meant the word "its"**.
 * The apostrophe *is* the disambiguation.
 *
 * So four of the commonest contractions in English were typed out letter by
 * letter while `don't` and `you're` were offered at the mark:
 *
 *     it's   4 of 4        we're  5 of 5
 *     let's  5 of 5        we'll  5 of 5
 *
 * ## The rule that admits a word, which is a test and not a taste
 *
 * The same sentence the class comment already contains: **the bare form is an
 * ordinary English word**, which is exactly why it is in neither map. Measured
 * on the shipped list, that draws the line by itself and leaves nothing to
 * anybody's judgement:
 *
 *     its 180,594   were 1,315,964   well 2,159,909      in
 *     lets 14,876   id 16,835        shed 10,791         in
 *     ------------------------------------------------------
 *     hed 129       itd 13                               out
 *
 * `he'd` and `it'd` are missing from the maps for a different reason — nobody
 * added them, and their bare forms are not words at all — so they are not this
 * list's business and the test below refuses them.
 *
 * ## The order they came in was the order they were listed in
 *
 * Every candidate at a given prefix shares one stem, and each was scored by
 * that stem's frequency — so they all scored the same and the ranking fell to
 * whichever map they happened to sit in. Typing `i'` offered I'm, I've, I'll in
 * that order, though the corpus holds `'ll` at 2,913,428 against `'ve` at
 * 1,991,871.
 *
 * The lists were split at the apostrophe when they were built, so **the ending
 * of every contraction is an ordinary entry with an ordinary count**. That is
 * the only thing they know about which contraction is likelier than which, and
 * it was going unasked.
 *
 * ## Measured, over the thirty commonest English contractions
 *
 *                            letters saved   weighted by ending
 *     before                   26 of 151          16.7%
 *     with the six entries      30                19.5%
 *     and the ending weight     31                20.1%
 *
 * The weighted column is the honest one: an unweighted sum treats `we'd` as it
 * treats `it's`, and the corpus says one is thirteen times the other.
 *
 * On real prose, [StripAccuracyTest]'s English context arm goes **43.9% to
 * 44.0%** saved and 83% to 84% by the third letter. Every figure in
 * [AutocorrectAccuracyTest], [BilingualTest] and [OutOfVocabularyTest] is
 * unchanged.
 */
class ContractionInventoryTest {

    private lateinit var dir: File
    private lateinit var userData: UserData
    private val en = Locale.ENGLISH

    @Before
    fun setUp() {
        dir = File.createTempFile("contrinv", "").let { it.delete(); it.mkdirs(); it }
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
        val files = listOf("dictionaries/$lang.txt", "predictions/$lang.txt")
            .filter { File(assets(), it).isFile }
            .associateWith { File(assets(), it).readText() }
        return SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
    }

    @Test
    fun `a contraction whose bare form is a word is still completed`() {
        val e = engine("en")
        val missing = listOf(
            "it'" to "it's", "let'" to "let's", "we'" to "we're", "we'" to "we'll"
        ).filterNot { (typed, wanted) ->
            e.suggestionsFor(typed, "en", en, allowAutocorrect = true, personalized = false)
                .items.any { it.equals(wanted, ignoreCase = true) }
        }
        assertEquals(
            "a contraction left out of both maps because restoring it would be " +
                "unsafe cannot be completed either, though the apostrophe has " +
                "already settled what it is.",
            emptyList<Pair<String, String>>(), missing
        )
    }

    /**
     * The rule, held against the list itself.
     *
     * Every entry must be here for the stated reason and no other: its bare
     * form is an ordinary English word, which is what keeps it out of [auto]
     * and [suggest]. An entry that fails this belongs in one of those instead,
     * and the argument for it is a different one.
     */
    @Test
    fun `every completion-only entry is here for the stated reason`() {
        val d = engine("en").dictionary("en", en)
        val wrong = StringBuilder()
        for (canonical in Contractions.completeOnlyForms("en")) {
            val bare = canonical.filterNot { it == '\'' }.lowercase(en)
            val f = d.frequency(bare)
            if (f < 10_000) {
                wrong.append("\n  \"$canonical\": its bare form \"$bare\" was counted $f " +
                    "times, so it is not an ordinary word and this is not why it is here")
            }
        }
        assertEquals(
            "the completion-only list admits a word the stated rule does not:$wrong",
            "", wrong.toString()
        )
        // And the two the rule keeps out are genuinely out.
        assertTrue("\"hed\" is not an English word", d.frequency("hed") < 1000)
        assertTrue("\"itd\" is not an English word", d.frequency("itd") < 1000)
        for (c in Contractions.completeOnlyForms("en")) {
            assertTrue("\"$c\" must not also be in the restoring maps", c !in
                (Contractions.allCanonical("en") - Contractions.completeOnlyForms("en")))
        }
    }

    /**
     * And the restoration direction is exactly where it was.
     *
     * This is the whole safety property: adding these to *completion* must not
     * put an apostrophe into "its" or "were" — which is the thing the class
     * comment refused, and refused correctly.
     */
    @Test
    fun `the bare forms are still left alone`() {
        val e = engine("en")
        val bad = StringBuilder()
        for (bare in listOf("its", "were", "well", "lets", "id", "shed")) {
            val r = e.suggestionsFor(bare, "en", en, allowAutocorrect = true, personalized = false)
            assertEquals("slot 0 must be what was typed", bare, r.items.first())
            assertNull(
                "\"$bare\" was committed as something else on the separator",
                e.correctionFor(bare, "en", en)
            )
            for (chip in r.items.drop(1)) {
                if (chip.contains('\'')) bad.append("\n  $bare -> $chip")
            }
        }
        assertEquals(
            "typing the bare form offered an apostrophe form; these words are in " +
                "the completion table only, and restoring them was refused on " +
                "purpose:$bad",
            "", bad.toString()
        )
    }

    /**
     * The order is the corpus's, not the table's.
     *
     * Both halves matter: that the commoner ending leads, and that the reason
     * is a count rather than a position in a source file.
     */
    @Test
    fun `contractions are ranked by how common their ending is`() {
        val e = engine("en")
        val d = e.dictionary("en", en)
        assertTrue(
            "the English list no longer holds the split endings, so there is " +
                "nothing to rank by",
            d.frequency("'ll") > d.frequency("'ve") && d.frequency("'ve") > 0
        )
        val out = e.suggestionsFor("i'", "en", en, allowAutocorrect = true, personalized = false)
            .items.drop(1)
        val ll = out.indexOf("I'll")
        val ve = out.indexOf("I've")
        assertTrue("\"I'll\" was not offered at all: $out", ll >= 0)
        assertTrue(
            "\"I've\" is ranked above \"I'll\" though the corpus counted `'ve` " +
                "${d.frequency("'ve")} times against `'ll` ${d.frequency("'ll")}: $out",
            ve < 0 || ll < ve
        )
    }

    /** A language whose corpus never split that way is untouched. */
    @Test
    fun `a language with no split endings is unaffected`() {
        val e = engine("tr")
        val d = e.dictionary("tr", Locale.forLanguageTag("tr"))
        assertEquals(
            "Turkish has no apostrophe entries, so the denominator must fall " +
                "back rather than divide by zero",
            1, d.commonestEnding()
        )
    }
}
