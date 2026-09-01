package com.rimboard.keyboard.engine

import com.rimboard.keyboard.model.KeyProximity
import com.rimboard.keyboard.model.StripLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.random.Random

/**
 * The bold chip is a promise about what the space bar will do, and one word in
 * seven was rewritten without it.
 *
 * The rule is written down twice in this project and both times as a
 * one-directional thing: `mayCorrect`'s doc says "a word this refuses to commit
 * must never be shown as the one that will be", and the commit site in the
 * service says "the bold is a promise about what the separator is going to do".
 * The other direction has no sentence anywhere, and is the dangerous one — a
 * word committed with **nothing** on the strip to warn that it would be.
 *
 * `suggestionsFor` computed its bold index behind `split == null`:
 *
 *     // A run-together typing suppresses autocorrect entirely.
 *     if (allowAutocorrect && split == null && correction != null)
 *
 * and [SuggestionEngine.correctionFor], which is what the separator actually
 * asks, has no such clause and never had one. So a split suppressed the *bold*
 * and not the *commit*. Typing "helko" showed
 *
 *     [hello]  "helko"  [helo]
 *
 * with nothing in bold, and the space bar committed "hello". The split it was
 * suppressed for — "hel ko" — is not on the strip either, because the split is
 * added only when a slot is spare.
 *
 * ## Measured, one neighbour slip per word, over the twenty-two fixtures
 *
 *     14,754 commits: 2,084 of them unmarked (14.1%), 0 marked as anything else
 *
 * Not one language was clean: en 162 of 599, id 213 of 738, ro 140 of 624,
 * tr 141 of 990, ru 62 of 921. All zero now.
 *
 * ## Why the clause was protecting nothing
 *
 *  - **The words it names do not need it.** "alot", "infact", "thankyou",
 *    "aswell" and "eachother" every one returns null from [correctionFor]
 *    already, because [Dictionary.autoCommitConfident] refuses them. That is
 *    what stopped the silent deletion the comment describes; this clause only
 *    hid the evidence when something else got through.
 *  - **Where both fire, the correction is the word that was meant.** 2,054
 *    right against 107 wrong across the fixtures, and **zero** cases of a
 *    correctly typed word being rewritten, which is the harm it names.
 *  - **Over the attested run-togethers**, the ones the splitter fires on in the
 *    shipped lists, almost none autocorrect at all — and the handful that do
 *    are accent restorations that are simply right: 5 of 910 in German
 *    (`naher` -> `näher`, `manner` -> `männer`), 5 of 94 in Turkish, 1 of 626
 *    in Spanish. Those were being committed with the bold hidden.
 *
 * Nothing about what is committed changes. The index is read only by the strip;
 * every figure in [StripAccuracyTest], [AutocorrectAccuracyTest],
 * [BilingualTest], [OutOfVocabularyTest], [SplitOnStripTest], [SplitFloorTest],
 * [SplitShapeTest] and [SuggestionEngineTest] is identical to the character.
 */
class StripPromiseTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("promise", "").let { it.delete(); it.mkdirs(); it }
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

    private fun surveyLanguages(): List<String> =
        File(assets(), "dictionaries").list().orEmpty()
            .map { it.removeSuffix(".txt") }
            .filter { File(fixtures(), "prose_$it.txt").isFile }
            .sorted()

    /**
     * Both directions of the promise, over every fixture: what the separator
     * would commit is on the strip, and it is the chip in bold.
     */
    @Test
    fun `what the separator commits is what the strip marks`() {
        val casualties = StringBuilder()
        val report = StringBuilder()
        var commits = 0
        var unmarked = 0
        var mismarked = 0
        for (lang in surveyLanguages()) {
            val locale = Locale.forLanguageTag(lang)
            val e = engine(lang)
            e.dictionary(lang, locale)
            e.predictions("", "x", lang, locale, 1)
            val prox = KeyProximity.forLang(lang)
            // Seeded, so the same slips happen on every machine and every run.
            val rnd = Random(seed = 424242)
            var n = 0
            for (line in File(fixtures(), "prose_$lang.txt").readLines().filter { it.isNotBlank() }) {
                for (w in wordsOf(line, locale)) {
                    // As typed and with one neighbour slip: a disagreement needs
                    // a word the dictionary does not know to arise at all.
                    val variants = ArrayList<String>(2)
                    variants.add(w)
                    val i = rnd.nextInt(w.length)
                    prox.neighbours(w[i]).firstOrNull()?.let {
                        variants.add(w.substring(0, i) + it + w.substring(i + 1))
                    }
                    for (typed in variants) {
                        val commit = e.correctionFor(typed, lang, locale) ?: continue
                        n++
                        commits++
                        val r = e.suggestionsFor(
                            typed, lang, locale, allowAutocorrect = true, personalized = false
                        )
                        val a = StripLayout.arrange(
                            r.items, r.autocorrectIndex,
                            e.acceptedWord(r.items.first(), lang, locale)
                        ) { "“$it”" }
                        val marked = a.words.getOrNull(a.highlight)
                        if (marked == null) {
                            unmarked++
                            if (casualties.length < 900) {
                                casualties.append(
                                    "\n  $lang: \"$typed\" commits \"$commit\" with nothing " +
                                        "marked; the strip reads ${a.words}"
                                )
                            }
                        } else if (!marked.equals(commit, ignoreCase = true)) {
                            mismarked++
                            if (casualties.length < 900) {
                                casualties.append(
                                    "\n  $lang: \"$typed\" commits \"$commit\" but marks \"$marked\""
                                )
                            }
                        }
                    }
                }
            }
            report.append("$lang: $n commits\n")
        }
        println(report)
        println("$commits commits, $unmarked unmarked, $mismarked marked as something else")
        assertTrue("the fixtures produced almost nothing: $commits", commits >= 5000)
        assertEquals(
            "the space bar would rewrite a word with nothing on the strip saying " +
                "so, or with the wrong chip in bold:$casualties",
            0, unmarked + mismarked
        )
    }

    /** The word it was found on. */
    @Test
    fun `a correction behind a junk split is still shown in bold`() {
        val e = engine("en")
        val en = Locale.ENGLISH
        val bad = StringBuilder()
        for (typed in listOf("helko", "deeam", "wanr", "robota")) {
            val commit = e.correctionFor(typed, "en", en)
            val r = e.suggestionsFor(typed, "en", en, allowAutocorrect = true, personalized = false)
            val marked = r.items.getOrNull(r.autocorrectIndex)
            if (commit != null && !commit.equals(marked, ignoreCase = true)) {
                bad.append("\n  \"$typed\" commits \"$commit\", marks \"$marked\" (split ")
                bad.append("${e.splitFor(typed, "en", en)})")
            }
        }
        assertEquals(
            "a correction is hidden behind a split that is not itself on the " +
                "strip:$bad",
            "", bad.toString()
        )
    }

    /**
     * The control, and the case the removed clause was written for.
     *
     * A run-together must still not be silently rewritten — and it is not, but
     * by [Dictionary.autoCommitConfident] rather than by the clause. Nothing is
     * committed, so there is nothing to mark, which is the honest strip.
     */
    @Test
    fun `a run-together is still never committed as a correction`() {
        val e = engine("en")
        val en = Locale.ENGLISH
        for (w in listOf("alot", "infact", "thankyou", "aswell", "eachother")) {
            assertNull(
                "\"$w\" was silently rewritten on the separator; the split is the " +
                    "answer here and it needs a tap",
                e.correctionFor(w, "en", en)
            )
            val r = e.suggestionsFor(w, "en", en, allowAutocorrect = true, personalized = false)
            assertEquals(
                "nothing may be bold for \"$w\", because nothing will be committed",
                -1, r.autocorrectIndex
            )
        }
    }

    /** And the split is still offered for them, which is what makes that fair. */
    @Test
    fun `the split is still on the strip`() {
        val e = engine("en")
        val en = Locale.ENGLISH
        val missing = listOf("alot" to "a lot", "infact" to "in fact", "thankyou" to "thank you")
            .filterNot { (typed, split) ->
                e.suggestionsFor(typed, "en", en, allowAutocorrect = true, personalized = false)
                    .items.any { it.equals(split, ignoreCase = true) }
            }
        assertEquals(
            "a run-together is neither corrected nor offered its split, which " +
                "leaves the user nothing.",
            emptyList<Pair<String, String>>(), missing
        )
    }
}
