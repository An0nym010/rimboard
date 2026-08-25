package com.rimboard.keyboard.engine

import com.rimboard.keyboard.model.StripLayout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Typing in two languages without switching between them.
 *
 * The whole of this path was unmeasured: **no test anywhere passed `altLang`**,
 * though every bilingual user goes through it on every keystroke, and the code
 * behind it is substantial — a second dictionary merged into the candidate
 * list, a cross-language correction fallback, per-language casing.
 *
 * ## What it is worth, and what it cost
 *
 * The feature earns its place. Keystrokes saved over real prose:
 *
 *                        typing en    typing tr
 *     en only              37.7          1.4
 *     en + tr              36.2         27.7
 *     tr only               8.7         31.2
 *     tr + en              32.8         30.1
 *
 * But it used to be paid for by the primary language, unevenly: adding English
 * cost Turkish **3.2 points of its own typing** while adding Turkish cost
 * English 0.4. That asymmetry had nothing to do with the languages.
 *
 * ## The cause: two lists, two scales
 *
 * A raw count is meaningless across corpora. English was counted from 728
 * million tokens and Turkish from 215 million, so the same word at the same
 * rank carries a five-times larger number in the English list — at rank 100,
 * 1,166,914 against 222,447. The blend discounted the second language by 0.85
 * and merged the raw numbers, so "slightly below" was in fact comfortably
 * above, precisely among the common words completions are drawn from.
 *
 * Dividing by [Dictionary.tokenTotal] first is what makes the discount mean
 * what it says. The same trap `CORRECTION_TARGET_CAP` documents, in a different
 * place and found the same way — by asking what a number means rather than what
 * it is.
 */
class BilingualTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-bi", "").let { it.delete(); it.mkdirs(); it }
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

    private fun engine(vararg langs: String): SuggestionEngine {
        val files = HashMap<String, String>()
        for (l in langs) {
            files["dictionaries/$l.txt"] = File(assets(), "dictionaries/$l.txt").readText()
            files["predictions/$l.txt"] = File(assets(), "predictions/$l.txt").readText()
        }
        return SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
    }

    private fun sentences(lang: String, locale: Locale, n: Int): List<List<String>> =
        File(fixtures(), "prose_$lang.txt").readLines().filter { it.isNotBlank() }.take(n)
            .map { line ->
                val sb = StringBuilder()
                val out = ArrayList<String>()
                for (c in line) {
                    if (c.isLetter() || c == '\'' || c == '’') sb.append(c)
                    else { if (sb.isNotEmpty()) out.add(sb.toString()); sb.setLength(0) }
                }
                if (sb.isNotEmpty()) out.add(sb.toString())
                out.map { it.trim('\'').lowercase(locale) }.filter { it.length > 1 }
            }

    /**
     * The three chips actually shown; see StripAccuracyTest for why not items.
     *
     * [alt] is passed to [SuggestionEngine.acceptedWord] because the keyboard
     * passes it (`arrangeUnknownWord` in the service). Leaving it out here
     * quoted every word of the *other* language as unrecognised, so it never
     * matched the target and the harness reported words as "never offered"
     * that the strip was showing all along -- 7.8% of Turkish, all of it the
     * instrument.
     */
    private fun strip(
        e: SuggestionEngine, res: SuggestionsResult, lang: String, locale: Locale,
        alt: String?, altLocale: Locale?
    ): List<String> {
        val verbatim = res.items.firstOrNull() ?: return emptyList()
        val known = e.acceptedWord(verbatim, lang, locale, alt, altLocale)
        return StripLayout.arrange(res.items, res.autocorrectIndex, known) { "“$it”" }.words
    }

    /** Keystrokes saved typing [corpus] with [lang] primary and [alt] second. */
    private fun saved(lang: String, alt: String?, corpus: String): Double {
        val locale = Locale.forLanguageTag(lang)
        val altLocale = alt?.let { Locale.forLanguageTag(it) }
        val e = if (alt == null) engine(lang, corpus) else engine(lang, alt, corpus)
        var keystrokes = 0
        var baseline = 0
        for (sentence in sentences(corpus, Locale.forLanguageTag(corpus), 60)) {
            for ((i, w) in sentence.withIndex()) {
                baseline += w.length + 1
                val prev = if (i >= 1) sentence[i - 1] else ""
                val prev2 = if (i >= 2) sentence[i - 2] else ""
                var taken = -1
                for (k in 1..w.length) {
                    val res = e.suggestionsFor(
                        w.substring(0, k), lang, locale,
                        allowAutocorrect = true, personalized = false,
                        altLang = alt, altLocale = altLocale,
                        prevWord2 = prev2, prevWord = prev
                    )
                    if (strip(e, res, lang, locale, alt, altLocale)
                            .any { it.equals(w, ignoreCase = true) }) {
                        taken = k; break
                    }
                }
                keystrokes += if (taken < 0) w.length + 1 else taken + 1
            }
        }
        return (1.0 - keystrokes.toDouble() / baseline) * 100
    }

    @Test
    fun `the two shipped dictionaries really are on different scales`() {
        // The fact the blend has to divide out, asserted so that a rebuild of
        // the assets which happened to equalise them would not quietly make
        // the normalisation untested rather than unnecessary.
        val en = engine("en").dictionary("en", Locale.ENGLISH)
        val tr = engine("tr").dictionary("tr", Locale.forLanguageTag("tr"))
        assertTrue("both dictionaries must report a corpus size",
            en.tokenTotal > 0 && tr.tokenTotal > 0)
        val ratio = en.tokenTotal.toDouble() / tr.tokenTotal
        assertTrue(
            "English and Turkish were built from similar amounts of text " +
                "(ratio $ratio), so nothing here is measuring what it claims",
            ratio > 2.0
        )
    }

    @Test
    fun `a second language costs the first very little`() {
        // The regression this exists for. Turkish used to lose 3.2 points of
        // its own typing for having English switched on.
        val alone = saved("tr", null, "tr")
        val withEnglish = saved("tr", "en", "tr")
        val cost = alone - withEnglish
        println("tr alone %.1f%%, tr+en %.1f%%, cost %.1f points".format(alone, withEnglish, cost))
        assertTrue(
            "enabling English cost Turkish %.1f points of its own typing (%.1f -> %.1f); "
                .format(cost, alone, withEnglish) +
                "it was 3.2 before the corpus scales were divided out",
            cost < 2.0
        )
    }

    @Test
    fun `the second language still does its job`() {
        // The other half of the trade, so the test above cannot be satisfied
        // by simply ignoring the second language.
        val without = saved("tr", null, "en")
        val with = saved("tr", "en", "en")
        println("typing en on tr: alone %.1f%%, with en enabled %.1f%%".format(without, with))
        assertTrue(
            "a second language must transform typing it (%.1f -> %.1f)".format(without, with),
            with > without + 15.0
        )
    }

    @Test
    fun `the other language gets context ranking too`() {
        // Context is worth six to nine points of keystroke savings, and
        // somebody typing their second language was getting none of it: the
        // word before is in that language, the primary's n-grams have never
        // seen it, so the rank map came back empty and every completion fell
        // back to raw frequency.
        //
        // Two things had to change together, which is why loading the second
        // model alone moved nothing: the map has to be built from both
        // languages, *and* the second language's candidates have to be
        // multiplied by it. Every other candidate source already was.
        val e = engine("tr", "en")
        val tr = Locale.forLanguageTag("tr")
        val en = Locale.ENGLISH
        // mayLoad is false on the keystroke path, so the models must be resident.
        e.dictionary("tr", tr); e.predictions("", "x", "tr", tr, 1)
        e.dictionary("en", en); e.predictions("", "x", "en", en, 1)

        val saved = saved("tr", "en", "en")
        println("typing English with Turkish primary: %.1f%% saved".format(saved))
        assertTrue(
            "typing the other language saved only %.1f%%; it was 32.8 before the ".format(saved) +
                "second language's n-grams were consulted and 35.1 after",
            saved > 34.0
        )
    }

    @Test
    fun `word-building rules follow the language, not the slot`() {
        // Turkish stacks suffixes whether it is the first language or the
        // second. "kitaplarımızda" is in no word list — it is a stem with four
        // endings on it — and the morphology rule is what makes it a word.
        // That rule was only ever asked about the *primary* language, so the
        // same Turkish typed by the same user was a word with Turkish selected
        // and a misspelling with English selected.
        val tr = Locale.forLanguageTag("tr")
        val en = Locale.ENGLISH
        val built = "kitaplarımızda"

        val trFirst = engine("tr")
        assertTrue(
            "the fixture must not be in the list verbatim, or this proves nothing",
            !trFirst.dictionary("tr", tr).contains(built)
        )
        assertTrue(
            "$built is a word when Turkish is the primary language",
            trFirst.acceptedWord(built, "tr", tr)
        )

        val enFirst = engine("en", "tr")
        assertTrue(
            "$built must be a word when Turkish is the *second* language too",
            enFirst.acceptedWord(built, "en", en, "tr", tr)
        )
        assertTrue(
            "and not merely because English accepts everything",
            !enFirst.acceptedWord(built, "en", en)
        )
    }

    @Test
    fun `the other language's words are accepted rather than underlined`() {
        // The fixture is derived rather than guessed, because guessing it got
        // this wrong: "because", "tomorrow" and "different" are all in the
        // *Turkish* list at 284, 187 and 64. Subtitle corpora are full of the
        // other language, so a hand-picked English word is quite likely to be
        // a Turkish entry too, and the negative half of this test would have
        // been asserting something false about three words in four.
        val e = engine("tr", "en")
        val tr = Locale.forLanguageTag("tr")
        val en = Locale.ENGLISH
        val onlyEnglish = File(assets(), "dictionaries/en.txt").useLines { lines ->
            lines.drop(200)
                .mapNotNull { it.split(' ').firstOrNull() }
                .filter { it.length in 5..10 && it.all { c -> c.isLetter() } }
                .filterNot { e.acceptedWord(it, "tr", tr) }
                .take(40)
                .toList()
        }
        assertTrue(
            "no English word was found that Turkish alone rejects, so this " +
                "measures nothing",
            onlyEnglish.size >= 20
        )
        val rescued = onlyEnglish.count { e.acceptedWord(it, "tr", tr, "en", en) }
        println("English words Turkish alone rejects: ${onlyEnglish.size}, " +
            "accepted once English is on: $rescued")
        assertTrue(
            "enabling English rescued only $rescued of ${onlyEnglish.size} " +
                "English words from being underlined: " + onlyEnglish.take(8),
            rescued == onlyEnglish.size
        )
    }
}
