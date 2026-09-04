package com.rimboard.keyboard.engine

import com.rimboard.keyboard.model.Diacritics
import com.rimboard.keyboard.model.StripLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * A guard that says "valid" and asks "contains".
 *
 * `correctionCandidates` carries a line whose comment has always read *never
 * "correct" a word that is valid in the user's other enabled language*, and the
 * line under it asked `dictionary(altLang, altLocale).contains(typed)`.
 *
 * Being in a word list is strictly narrower than being a word, and this engine
 * knows that better than most — it has five separate ways a word can be real
 * without being an entry, and a whole file for each. A German compound, a
 * Turkish stem carrying suffixes, an English contraction, a Ukrainian word
 * spelled with an apostrophe: all valid, none contained.
 *
 * ## Where it shows, and why it is the contractions every time
 *
 * Hardest through the cross-language fallback, where the roles swap and the
 * second language is handed a word the first has already accepted. The shipped
 * lists come from a tokeniser that split at the apostrophe, so **no list on
 * earth holds a contraction** and `contains` was guaranteed to say no about
 * every one of them. Measured over the prose fixtures, on the distinct
 * spellings the engine itself accepts:
 *
 *     fr + en   17 of 702    l'impossible -> impossible   j'avais -> jamais
 *                            qu'elle -> quelle   l'un -> lun   d'avis -> david
 *     en + de   14 of 677    doesn't -> doesn   don't -> donut   he's -> hess
 *                            i'm -> im   you're -> your   it's -> its
 *                            i'll -> ill   we'll -> well   man's -> mans
 *     en + tr   10 of 677    the same words, minus the ones Turkish cannot
 *                            spell -- and this is the install on the phone
 *     tr + en    1 of 873    abd'de -> abide
 *     de+en, pl+en, es+en, ru+en, en+ru: 0
 *
 * Every casualty in every pair is a word written with an apostrophe. The pairs
 * that score zero are the ones whose second language cannot spell the first at
 * all — Russian has no candidates for Latin letters, so it never gets to be
 * wrong. All of them are zero now.
 *
 * ## What it costs
 *
 * Nothing measurable, and one figure improves. Every arm of
 * [AutocorrectAccuracyTest], [StripAccuracyTest], [ForeignAccentTest],
 * [LanguageBoostAccuracyTest] and [AltLanguageOffensiveTest] is identical
 * before and after. [BilingualTest] moves: Turkish with English enabled saves
 * 30.6% -> 30.7%, so what the second language costs the first falls from 1.3
 * points to 1.2.
 *
 * That it costs nothing is not luck either. The guard sits after the two that
 * ask the *primary* language whether the word is fine, so it is only ever
 * reached for something that language could not vouch for — and then it asks
 * the same question of the other one, which is what the sentence above always
 * said it did.
 *
 * It is a heavier question, though: a morphology peel and a compound split
 * where `contains` was a binary search. Measured over 2,234 bilingual
 * keystrokes, **0.117 ms to 0.119 ms** on the mean. The arm below exists
 * because [StripLatencyTest] measures one language and so exercises none of
 * this.
 *
 * Found on the phone: English primary with Turkish second is what this install
 * has, and "i'm" showed a chip reading *im* that no test here could reproduce
 * until the probe loaded the second dictionary too.
 */
class AltLanguageWordTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("altword", "").let { it.delete(); it.mkdirs(); it }
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
            for (p in listOf("dictionaries/$l.txt", "predictions/$l.txt")) {
                if (File(assets(), p).isFile) files[p] = File(assets(), p).readText()
            }
        }
        return SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
    }

    private fun loc(l: String) = Locale.forLanguageTag(l)

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

    /** The same three shapes [FinishedWordStripTest] allows, for the same reason. */
    private fun defensible(word: String, chip: String): Boolean =
        chip.startsWith(word) ||
            chip.contains(' ') ||
            Diacritics.fold(chip) == Diacritics.fold(word)

    private val pairs = listOf(
        "en" to "tr", "tr" to "en", "de" to "en", "en" to "de",
        "fr" to "en", "pl" to "en", "es" to "en", "ru" to "en", "en" to "ru"
    )

    @Test
    fun `a second language does not correct a word the first one vouches for`() {
        val casualties = StringBuilder()
        val report = StringBuilder()
        var total = 0
        for ((a, b) in pairs) {
            val la = loc(a)
            val lb = loc(b)
            val e = engine(a, b)
            e.dictionary(a, la)
            e.dictionary(b, lb)
            var n = 0
            var hits = 0
            val seen = HashSet<String>()
            for (line in File(fixtures(), "prose_$a.txt").readLines().filter { it.isNotBlank() }) {
                for (w in wordsOf(line, la)) {
                    if (!seen.add(w)) continue
                    if (!e.acceptedWord(w, a, la)) continue
                    n++
                    val r = e.suggestionsFor(
                        w, a, la, allowAutocorrect = true, personalized = false,
                        altLang = b, altLocale = lb
                    )
                    for (chip in strip(e, r, a, la).drop(1)) {
                        val c = chip.trim('“', '”').lowercase(la)
                        if (defensible(w, c)) continue
                        hits++
                        if (casualties.length < 1000) {
                            casualties.append("\n  $a with $b enabled: \"$w\" -> \"$c\"")
                        }
                    }
                }
            }
            total += n
            report.append("$a+$b: $n accepted spellings, $hits offered a different word\n")
        }
        println(report)
        assertTrue("the fixtures produced almost nothing: $total", total >= 5000)
        assertEquals(
            "the other language was asked to repair a word this one calls " +
                "correctly spelled, and answered:$casualties",
            "", casualties.toString()
        )
    }

    /**
     * And the fallback it gates still does its job.
     *
     * The whole point of asking the second language is a word the first cannot
     * explain — typing English on a Turkish keyboard. That case is not a word
     * in Turkish by any of the five definitions, so it still reaches the other
     * language and still comes back with the answer.
     */
    @Test
    fun `the other language still corrects what this one cannot explain`() {
        val e = engine("tr", "en")
        val tr = loc("tr")
        val en = Locale.ENGLISH
        val missing = listOf("helko", "wprld", "peopel", "thibk").filterNot { typed ->
            assertTrue(
                "\"$typed\" must not be a Turkish word, or this proves nothing",
                !e.acceptedWord(typed, "tr", tr)
            )
            e.suggestionsFor(
                typed, "tr", tr, allowAutocorrect = true, personalized = false,
                altLang = "en", altLocale = en
            ).items.drop(1).isNotEmpty()
        }
        assertEquals(
            "the cross-language fallback stopped offering anything, which is " +
                "what it exists for.",
            emptyList<String>(), missing
        )
    }

    /** Nor does it stop the primary language correcting an ordinary typo. */
    @Test
    fun `an ordinary typo is still corrected with two languages enabled`() {
        val e = engine("en", "tr")
        val en = Locale.ENGLISH
        val missing = listOf(
            "helko" to "hello", "wprld" to "world", "teh" to "the", "thibk" to "think"
        ).filterNot { (typed, wanted) ->
            e.suggestionsFor(
                typed, "en", en, allowAutocorrect = true, personalized = false,
                altLang = "tr", altLocale = loc("tr")
            ).items.any { it.lowercase(en) == wanted }
        }
        assertEquals("a plain typo stopped being corrected.", emptyList<Pair<String, String>>(), missing)
    }

    /**
     * The guard is heavier than the one it replaces and runs per keystroke.
     *
     * [StripLatencyTest] measures one language, so nothing there exercises this
     * at all — it only runs when a second is enabled. Asking [acceptedWord] of
     * the other language costs a morphology peel and a compound split where
     * `contains` cost a binary search, so the number is worth having rather
     * than assuming.
     *
     * The bar is loose for the same reason that file's is: a test machine under
     * load is not a phone, and this is a tripwire on the order of magnitude.
     */
    @Test
    fun `two languages stay inside the keystroke budget`() {
        val e = engine("tr", "en")
        val tr = loc("tr")
        val en = Locale.ENGLISH
        e.dictionary("tr", tr)
        e.dictionary("en", en)
        val words = File(fixtures(), "prose_tr.txt").readLines()
            .filter { it.isNotBlank() }
            .flatMap { wordsOf(it, tr) }
            .take(400)
        // Warm, then measure: the first touch of a language loads it.
        for (w in words.take(20)) {
            e.suggestionsFor(w, "tr", tr, allowAutocorrect = true, personalized = false,
                altLang = "en", altLocale = en)
        }
        var worst = 0.0
        var totalMs = 0.0
        var n = 0
        for (w in words) {
            for (k in 1..w.length) {
                val t0 = System.nanoTime()
                e.suggestionsFor(
                    w.substring(0, k), "tr", tr, allowAutocorrect = true,
                    personalized = false, altLang = "en", altLocale = en
                )
                val ms = (System.nanoTime() - t0) / 1_000_000.0
                totalMs += ms
                n++
                if (ms > worst) worst = ms
            }
        }
        println("tr+en: %d keystrokes, mean %.3f ms, worst %.2f ms".format(n, totalMs / n, worst))
        assertTrue("nothing was measured", n > 1000)
        assertTrue(
            "a bilingual keystroke took %.2f ms on average, which would be felt".format(totalMs / n),
            totalMs / n < 2.0
        )
    }
    /**
     * The harness guard, and why it exists.
     *
     * Every test in this file builds its engine over *both* languages, which
     * is what makes the assertions mean anything. An engine built over one
     * answers about the other with an empty dictionary — so the correction
     * refusal, the offensive fallback and [SuggestionEngine.acceptedWord]'s
     * alt branch all find nothing, do nothing, and pass while measuring the
     * opposite of what they claim.
     *
     * That is not hypothetical. On 2026-09-05 a probe built over French alone
     * reported that enabling English as the second language stopped none of
     * the 79 words in 200 that French autocorrect overwrites; with both
     * loaded it stops all 79. The engine was answering about a language whose
     * word list it had never seen.
     *
     * [SuggestionEngine.forTesting] now throws instead of answering, and this
     * is the test that it does. The app itself keeps the old behaviour on
     * purpose — see the note there — because an unavailable language must not
     * take the keyboard down with it.
     */
    @Test
    fun `an engine built over one language refuses to answer about another`() {
        val e = engine("en")
        var threw = false
        try {
            e.acceptedWord("kitap", "tr", loc("tr"))
        } catch (expected: AssertionError) {
            threw = true
        }
        assertTrue(
            "the test engine answered about Turkish without a Turkish word " +
                "list, which is how a bilingual assertion passes while " +
                "measuring nothing",
            threw
        )
        // ...and the language it *was* built over still works, so the guard is
        // not simply breaking every engine.
        assertTrue("English stopped answering", e.acceptedWord("keyboard", "en", loc("en")))
    }

}
