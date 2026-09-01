package com.rimboard.keyboard.engine

import com.rimboard.keyboard.model.KeyProximity
import com.rimboard.keyboard.model.StripLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Typing "i'm" filled the strip with *important* and *imagine*.
 *
 * The fuzzy prefix search exists for a prefix that has a typo in it: an exact
 * search returns nothing for "hellp", so the strip goes blank for the rest of a
 * long word at exactly the moment it is most wanted. It recovers by asking the
 * same question of the near-misses a thumb actually makes — a neighbouring key,
 * two letters swapped, a letter doubled, a letter dropped.
 *
 * The dropped-letter variant deleted **any** character, and the one character
 * in a composing word that is not a letter is the apostrophe. So it treated a
 * correctly typed apostrophe as a slip and answered about a different word:
 *
 *     i'm    ->  i'm, important, imagine
 *     i've   ->  i've, ives, iverson
 *     he's   ->  he's, hesitate, hesitation
 *     that's ->  that's, thatshe, thatss
 *     you're ->  you're, youreyes, yourelf
 *     don't  ->  don't, donte, dontcha
 *
 * Two things make this fire on **every** apostrophe word rather than
 * occasionally. The fuzzy path runs only when the exact path found nothing —
 * and the lists come from a tokeniser that split at the apostrophe, so no entry
 * contains one and an exact prefix search through one is guaranteed to be
 * silent. And the apostrophe is not on the letter layer at all: reaching it
 * takes a long-press on the full stop or a switch to the symbol layer, so
 * deleting it models no slip that a finger can make.
 *
 * ## Measured, over the prose fixtures
 *
 * One number per language hid it, the way it hid the doubled-letter class in
 * [GlideAccuracyTest]: 2.2% of English words and 5.8% of French ones carry an
 * apostrophe, and they behave nothing like the rest.
 *
 *     apostrophe words          the rest
 *     en   12.1% saved          44.3%      mean letters 4.44 vs 1.84
 *     fr   33.7%                44.9%                   3.82 vs 1.93
 *     it   29.8%                43.2%                   5.65 vs 2.14
 *     tr    0.0%                38.9%                   8.92 vs 3.02
 *
 * And of the chips offered once an apostrophe was on screen, the share that
 * could not continue what was there but did continue it with the mark rubbed
 * out — which is the survey below, run against the code as it stood:
 *
 *     en  68 of 83      tr  14 of 23      uk  10 of 14
 *     fr   1 of 896     it   0 of 245     cs   0 of 14
 *
 * **Four English chips in five.** Ukrainian is the one where it is not even
 * arguable: there the apostrophe is a letter — `комп'ютер` — and deleting it
 * spells a different word by the rules of the writing system, which is what
 * [InnerApostrophe] exists to say. French escapes because its elided articles
 * `l'` and `qu'` are entries in their own right, so the exact path answers and
 * the fuzzy one never runs.
 *
 * ## What this does not fix, and what is left
 *
 * It removes a wrong answer; it does not supply a right one, and the savings
 * figures above are unchanged by it. Turkish still types `iskenderiye'ye` in
 * full, fourteen letters, and English still has no route to "i'm" or "man's".
 * That is a missing completion path rather than a wrong one and it is a
 * separate piece of work.
 *
 * The residue is the cases where the prefix *ends* at the apostrophe —
 * `let'`, `ken'`, `dell'` — and the chip comes from the correction path rather
 * than the completion one. A word broken off at an apostrophe is genuinely
 * unfinished, and offering the nearest whole word for it is a different act
 * with a different argument, so this test does not speak about it.
 *
 * ## One thing the phone says that this harness does not
 *
 * Verified on a Redmi Note 8: "you're" offered *youreyes* and *yourelf* before
 * and offers neither after, which is what this is about. But it also offers
 * *your*, and "i'm" offers *im* — both of which are in `corrections()` for
 * those words and neither of which the engine returns here, because
 * [SuggestionEngine.correctionCandidates] returns nothing for a word
 * `wellFormedWord` accepts and it accepts every one of these through
 * [Elision]. So the correction path reaches the strip on the device in a case
 * this harness says it cannot, and that is its own question rather than this
 * one; it is not affected by the change here either way.
 */
class ApostropheStripTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("apostrophe", "").let { it.delete(); it.mkdirs(); it }
        userData = UserData.inDir(dir)
    }

    @After
    fun tearDown() {
        userData.shutdown()
        dir.deleteRecursively()
    }

    private fun assets(): File =
        listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }

    private fun fixtures(): File =
        listOf(File("src/test/fixtures"), File("app/src/test/fixtures")).first { it.isDirectory }

    private fun engine(lang: String): SuggestionEngine {
        val files = listOf("dictionaries/$lang.txt", "predictions/$lang.txt")
            .filter { File(assets(), it).isFile }
            .associateWith { File(assets(), it).readText() }
        return SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
    }

    private fun wordsOf(s: String, locale: Locale): List<String> =
        s.split(Regex("""[^\p{L}']+"""))
            .map { it.trim('\'') }
            .filter { it.isNotEmpty() && it.all { c -> c.isLetter() || c == '\'' } }
            .map { it.lowercase(locale) }

    private fun strip(
        e: SuggestionEngine, res: SuggestionsResult, lang: String, locale: Locale
    ): List<String> {
        val v = res.items.firstOrNull() ?: return emptyList()
        return StripLayout.arrange(res.items, res.autocorrectIndex, e.acceptedWord(v, lang, locale)) {
            "“$it”"
        }.words
    }

    /** The four the bug was found on, spelled out so a regression names itself. */
    @Test
    fun `a correctly typed contraction is not completed as though the apostrophe were a slip`() {
        val e = engine("en")
        val en = Locale.ENGLISH
        val bad = StringBuilder()
        for (w in listOf("i'm", "i've", "i'd", "it's", "don't", "you're", "man's", "let's")) {
            val out = e.suggestionsFor(w, "en", en, allowAutocorrect = true, personalized = false)
                .items.map { it.lowercase(en) }
            val blind = w.filter { it != '\'' }
            for (c in out.drop(1)) {
                if (!c.startsWith(w) && c.startsWith(blind)) bad.append("\n  $w -> $c")
            }
        }
        assertEquals(
            "the strip offered a completion of the word with the apostrophe " +
                "rubbed out, which is a different word:$bad",
            "", bad.toString()
        )
    }

    /**
     * The whole of every prose fixture, so this is not eight words somebody
     * noticed. Prefixes with at least one character past the apostrophe: see
     * the class comment for why a prefix that stops at one is excluded.
     */
    @Test
    fun `no chip in any language completes the apostrophe away`() {
        val report = StringBuilder()
        val casualties = StringBuilder()
        var total = 0
        for (lang in File(assets(), "dictionaries").list().orEmpty()
            .map { it.removeSuffix(".txt") }
            .filter { File(fixtures(), "prose_$it.txt").isFile }
            .sorted()
        ) {
            val locale = Locale.forLanguageTag(lang)
            val e = engine(lang)
            e.dictionary(lang, locale)
            e.predictions("", "x", lang, locale, 1)
            var slots = 0
            var blindChips = 0
            var apoWords = 0
            for (line in File(fixtures(), "prose_$lang.txt").readLines().filter { it.isNotBlank() }) {
                val ws = wordsOf(line, locale)
                for ((i, w) in ws.withIndex()) {
                    if (w.none { it == '\'' }) continue
                    apoWords++
                    val prev = if (i >= 1) ws[i - 1] else ""
                    val prev2 = if (i >= 2) ws[i - 2] else ""
                    for (k in 1..w.length) {
                        val pfx = w.substring(0, k)
                        val bare = pfx.filter { it != '\'' }
                        // Nothing elided yet, or the prefix stops at the mark.
                        if (bare == pfx || pfx.last() == '\'') continue
                        val res = e.suggestionsFor(
                            pfx, lang, locale, allowAutocorrect = true, personalized = false,
                            prevWord2 = prev2, prevWord = prev
                        )
                        for (c in strip(e, res, lang, locale).drop(1)) {
                            slots++
                            val cw = c.trim('“', '”').lowercase(locale)
                            if (!cw.startsWith(pfx) && cw.startsWith(bare)) {
                                blindChips++
                                casualties.append("\n  $lang: typed \"$pfx\" for \"$w\", offered \"$cw\"")
                            }
                        }
                    }
                }
            }
            if (apoWords > 0) {
                total += apoWords
                report.append(
                    "$lang: $apoWords words with an apostrophe, $slots chips, " +
                        "$blindChips of them blind to it\n"
                )
            }
        }
        println(report)
        assertTrue("no fixture has an apostrophe in it any more: $total", total >= 150)
        assertEquals(
            "a chip completed a prefix the user did not type -- the one with the " +
                "apostrophe removed:$casualties",
            "", casualties.toString()
        )
    }

    /**
     * And the recovery it exists for still happens.
     *
     * The guard is narrow on purpose: only the dropped-*character* edit, and
     * only when the character is not a letter. A doubled letter and a
     * neighbouring key are what the path was built for and they are untouched,
     * so this is the control that says the fix removed a wrong answer rather
     * than the feature.
     */
    @Test
    fun `a mistyped prefix is still recovered`() {
        val d = engine("en").dictionary("en", Locale.ENGLISH)
        val prox = KeyProximity.forLang("en")
        val missing = listOf(
            // A letter typed twice.
            // Inside the last [Dictionary.FUZZY_EDIT_WINDOW] characters, which
            // is where this path has always looked and is not what changed.
            "helllo" to "hello", "importannt" to "important", "sudddenl" to "suddenly",
            // A neighbouring key.
            "helli" to "hello", "importsn" to "important"
        ).filterNot { (typed, wanted) ->
            d.byPrefixFuzzy(typed, prox, 6).any { it.first == wanted }
        }
        assertEquals(
            "the fuzzy prefix search stopped recovering the typos it exists for.",
            emptyList<Pair<String, String>>(), missing
        )
    }

    /** The mechanism itself, under the strip. */
    @Test
    fun `the fuzzy search does not delete an apostrophe`() {
        val d = engine("en").dictionary("en", Locale.ENGLISH)
        val prox = KeyProximity.forLang("en")
        val bad = StringBuilder()
        for (typed in listOf("i'm", "i've", "you're", "don't", "it's", "man's")) {
            val blind = typed.filter { it != '\'' }
            for ((w, _) in d.byPrefixFuzzy(typed, prox, 6)) {
                if (w.startsWith(blind)) bad.append("\n  $typed -> $w")
            }
        }
        assertEquals(
            "byPrefixFuzzy still reaches words by deleting the apostrophe:$bad",
            "", bad.toString()
        )
    }
}
