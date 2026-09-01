package com.rimboard.keyboard.engine

import com.rimboard.keyboard.model.Diacritics
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
 * An empty strip has two causes, and the code knew one of them.
 *
 * `byPrefixFuzzy` is the recovery for a prefix that has a typo in it: an exact
 * search returns nothing for "hellp", so without it the strip stays blank for
 * the rest of a long word. It is gated on the completion list being nearly
 * empty, and [SuggestionEngine.FUZZY_TRIGGER]'s own comment says why — "the
 * strip is nearly empty, **which is the symptom of a typo already in the
 * prefix**".
 *
 * It is a symptom of two things. The other is that the word is finished. A
 * complete word with no continuations leaves the completion list exactly as
 * empty as a mistyped prefix does, so the repair meant for typos fired hardest
 * on words that were already right, and answered with a different word:
 *
 *     siblings -> sibling, siblington      sunday  -> sundae
 *     classes  -> classed, classe          bloody  -> blood
 *     einem    -> eine, einen              gördüm  -> gördün
 *     będę     -> będzie, będziesz         mogę    -> mogą, mogła
 *
 * ## Measured over the prose fixtures, on the words the engine itself accepts
 *
 *     en   137 of 677   20.2%      de   110 of 663   16.6%
 *     tr   162 of 873   18.6%      pl   358 of 744   48.1%
 *
 * **Nearly half of every correctly typed Polish word**, and the gradient is
 * the point: the more a language inflects, the more near-misses each of its
 * words has, so the noise lands hardest exactly where the strip's two spare
 * chips are worth most. Every one of those came from this path and **none was
 * ever committed on the space bar** — the cost is the chips, not the text.
 *
 * The fix asks [SuggestionEngine.acceptedWord], which is the one definition of
 * a word this engine has; its own doc promises the underline, the space bar and
 * the strip cannot disagree about what one is, and this is the strip.
 *
 * ## The price, measured
 *
 * A typo can land on another real word, and then this declines to look. In the
 * typo arm of [StripAccuracyTest], which slips a key in every word of the
 * corpus:
 *
 *     en typo   saved 41.51% -> 41.45%   never offered 4.46% -> 4.56%
 *     tr typo   saved 37.08% -> 37.06%   never offered 4.13% -> 4.25%
 *
 * Six hundredths of a point. Every other figure in that file is unchanged to
 * two decimals — en blind 34.28, en context 43.80, tr blind 29.23, tr context
 * 39.82, all twenty-two single-language arms, every held-out figure and every
 * autocorrect figure. [BilingualTest] moves the other way: what enabling
 * English costs Turkish falls from 1.4 points to 1.3.
 *
 * It also costs nothing that the path was doing for ordinary typing, and the
 * reason is structural rather than lucky. A word with completions never
 * reaches this branch — the trigger is never met — so "cat" typed on the way
 * to "cart" is untouched. The only words that reach it are the ones with
 * nothing after them.
 *
 * ## The one thing that is genuinely given up
 *
 * A misspelling the corpus itself recorded is in the list, so the engine calls
 * it a word and now declines to look. "diffrent" sits in the English list at
 * 26 against "different" at 174,657, and typing it no longer offers the right
 * spelling from this path.
 *
 * That was worth counting before accepting, and counting it settles the
 * question the other way. Of the entries that reach this branch at all,
 * the ones the fuzzy search would answer with a word a hundred times commoner:
 *
 *     en  30,615 of 286,413  10.7%      de  7,437 of 196,312  3.8%
 *     pl   4,591 of 196,440   2.3%      tr  4,226 of 196,739  2.2%
 *
 * and they are not misspellings. English: `rights` -> right, `maker` -> make,
 * `courses` -> course, `believer` -> believe, `lists` -> listen. German:
 * `isst` -> ist, `halben` -> haben, `bittet` -> bitte. Polish: `siłę` -> się,
 * `winę` -> więc. Turkish: `içip` -> için, `nehre` -> nerede. Every one an
 * ordinary word of the language being offered a commoner unrelated one.
 *
 * Which is the trap [Dictionary.TRANSPOSE_SUGGEST_RATIO] already wrote down
 * from the other direction: "in the list and much rarer than a near neighbour"
 * is mostly ordinary vocabulary. "diffrent" is a member of that class and not
 * a description of it, and the words the corpus misspelled have their own
 * route to the right spelling in [CorpusTypoTest].
 *
 * ## What is allowed to be here, and why the residue is not zero
 *
 * Two other chips argue with a correctly spelled word on purpose, and this
 * does not speak about either: the split ("behave" -> "be have", "showroom" ->
 * "show room", Ukrainian "нечто" -> "не что") and the accented form ("rada" ->
 * "ráda", "byt" -> "byť"). Both are separate features with their own
 * arguments and their own tests. Twenty-seven cases across the twenty-two
 * fixtures are one of those two; everything else is what this file is about.
 */
class FinishedWordStripTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("finished", "").let { it.delete(); it.mkdirs(); it }
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
        e: SuggestionEngine, r: SuggestionsResult, lang: String, locale: Locale
    ): List<String> {
        val v = r.items.firstOrNull() ?: return emptyList()
        return StripLayout.arrange(r.items, r.autocorrectIndex, e.acceptedWord(v, lang, locale)) {
            "“$it”"
        }.words
    }

    /**
     * The three shapes a chip beside a correctly typed word may take.
     *
     * A continuation of it, the same word written as two, or the same letters
     * with their accents on. Anything else is a different word.
     */
    private fun defensible(word: String, chip: String): Boolean =
        chip.startsWith(word) ||
            chip.contains(' ') ||
            Diacritics.fold(chip) == Diacritics.fold(word)

    private fun surveyLanguages(): List<String> =
        File(assets(), "dictionaries").list().orEmpty()
            .map { it.removeSuffix(".txt") }
            .filter { File(fixtures(), "prose_$it.txt").isFile }
            .sorted()

    @Test
    fun `a word the engine accepts is not offered a different word`() {
        val report = StringBuilder()
        val casualties = StringBuilder()
        var accepted = 0
        var bad = 0
        for (lang in surveyLanguages()) {
            val locale = Locale.forLanguageTag(lang)
            val e = engine(lang)
            e.dictionary(lang, locale)
            e.predictions("", "x", lang, locale, 1)
            var n = 0
            var hits = 0
            val seen = HashSet<String>()
            for (line in File(fixtures(), "prose_$lang.txt").readLines().filter { it.isNotBlank() }) {
                for (w in wordsOf(line, locale)) {
                    // Each spelling once: this is about the vocabulary, not
                    // about how often the fixture happens to repeat a word.
                    if (!seen.add(w)) continue
                    if (!e.acceptedWord(w, lang, locale)) continue
                    n++
                    val r = e.suggestionsFor(
                        w, lang, locale, allowAutocorrect = true, personalized = false
                    )
                    for (chip in strip(e, r, lang, locale).drop(1)) {
                        val c = chip.trim('“', '”').lowercase(locale)
                        if (defensible(w, c)) continue
                        hits++
                        if (casualties.length < 1200) casualties.append("\n  $lang: \"$w\" -> \"$c\"")
                    }
                }
            }
            accepted += n
            bad += hits
            report.append("$lang: $n accepted spellings, $hits offered a different word\n")
        }
        println(report)
        assertTrue("the fixtures produced almost nothing: $accepted", accepted >= 10_000)
        assertEquals(
            "a word this engine calls correctly spelled was offered a chip that " +
                "is a different word -- not a continuation of it, not a split of " +
                "it, and not its accented spelling:$casualties",
            0, bad
        )
    }

    /**
     * And the recovery this gates still happens where its premise holds.
     *
     * The control, and the reason the guard is on the word rather than on the
     * path: a prefix with a typo in it is not a word, so it still reaches
     * [Dictionary.byPrefixFuzzy] and still comes back with the answer.
     */
    @Test
    fun `a mistyped prefix is still rescued`() {
        val e = engine("en")
        val en = Locale.ENGLISH
        // Each of these must be a non-word, or the control would be asserting
        // that the guard does not work. "diffrent" was here and is not one:
        // the corpus recorded it at 26, so the engine calls it a word and this
        // path is closed to it on purpose. See the class comment.
        for (w in listOf("impirtant", "helllo", "sudddenl", "yeserda", "diffirent")) {
            assertTrue(
                "\"$w\" is in the word list, so it is not a mistyped prefix and " +
                    "cannot be a control for one",
                !e.acceptedWord(w, "en", en)
            )
        }
        val missing = listOf(
            "impirtant" to "important", "helllo" to "hello", "sudddenl" to "suddenly",
            "yeserda" to "yesterday", "diffirent" to "different"
        ).filterNot { (typed, wanted) ->
            e.suggestionsFor(typed, "en", en, allowAutocorrect = true, personalized = false)
                .items.any { it.lowercase(en) == wanted }
        }
        assertEquals(
            "the strip stopped recovering a prefix with a typo in it, which is " +
                "what the fuzzy path is for.",
            emptyList<Pair<String, String>>(), missing
        )
        // And the guard is not simply never firing: a word reaching the branch
        // is what the other test is about, and here is the same word as a
        // prefix and as itself.
        val prox = KeyProximity.forLang("en")
        val d = e.dictionary("en", en)
        assertTrue(
            "byPrefixFuzzy itself must be unchanged; only who is allowed to ask it",
            d.byPrefixFuzzy("siblingz", prox, 6).any { it.first.startsWith("sibling") }
        )
    }
}
