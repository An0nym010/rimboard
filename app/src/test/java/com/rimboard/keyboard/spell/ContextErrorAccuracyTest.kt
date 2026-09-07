package com.rimboard.keyboard.spell

import com.rimboard.keyboard.engine.SuggestionEngine
import com.rimboard.keyboard.engine.UserData
import com.rimboard.keyboard.model.ContextError
import com.rimboard.keyboard.model.KeyProximity
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * What [ContextError] catches, and what it underlines by mistake.
 *
 * The second column is the one that decides whether this feature may exist. It
 * suspects words that are **spelled correctly**, so every false positive is a
 * red line under text the user got right — and unlike a missed error, the user
 * cannot help noticing it. A rule that caught every real-word error and marked
 * one word in twenty would be strictly worse than no rule at all.
 *
 * ## The corpus
 *
 * Real sentences from the `openvocab` fixtures, walked word by word. Each word
 * is asked about twice:
 *
 *  - **as written** — the sentence the way a person wrote it. Anything flagged
 *    here is a false positive, full stop.
 *  - **with a real-word error injected** — the word replaced by a *different
 *    dictionary word* one edit away, which is exactly what a real-word typo is.
 *    Flagging that is a catch; naming the original is a catch that helped.
 *
 * The injected error is drawn from `correctionsScored`, so it is a word the key
 * geometry says a thumb could plausibly have produced, not an arbitrary
 * dictionary neighbour.
 *
 * ## The sweep, and the two rules it killed
 *
 * Measured 2026-09-07, ~3,400 English and ~2,500 Turkish word positions:
 *
 *     variant                    en false pos   en caught    tr false pos  tr caught
 *     right context only            5.06%          29%          2.64%         29%
 *     dictionary walk, top-12       0.09%          10%          0.12%          9%
 *     walk-free, confident bar      0.00%           1%          0.04%          4%
 *     walk-free, one edit (ships)   0.12%          11%          0.04%          9%
 *
 * **Right context alone is confetti**: one correctly-typed English word in
 * twenty underlined. It catches the most, and that is exactly why a catch rate
 * on its own is not a result.
 *
 * **The dictionary walk is the obvious implementation and it is the wrong one.**
 * It is no more accurate than the shipped rule and it pays `correctionsScored`
 * — a scan of every word within the edit budget — for every *correctly-spelled*
 * word, on a binder thread, on every keystroke. The shipped rule inverts the
 * loop and touches no dictionary at all.
 *
 * **The confident bar throws away nine tenths of the feature.**
 * `Dictionary.autoCommitConfident` is tuned to guard a silent replacement; used
 * as a closeness test here it admits almost nothing, for a gain of 0.12
 * percentage points of precision. See [ContextError] on why one edit is the
 * right question for this rule and not for that one.
 */
class ContextErrorAccuracyTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-ctxerr", "").let { it.delete(); it.mkdirs(); it }
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

    private fun engineOver(lang: String): SuggestionEngine {
        val files = HashMap<String, String>()
        for (kind in listOf("dictionaries", "predictions", "suffixes", "prefixes")) {
            File(assets(), "$kind/$lang.txt").takeIf { it.isFile }?.let {
                files["$kind/$lang.txt"] = it.readText()
            }
        }
        val e = SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
        val loc = Locale.forLanguageTag(lang)
        e.dictionary(lang, loc)
        // Without this the model is never parsed, every context rule answers
        // "no opinion", and the whole file reports a feature that never fires
        // as a feature with no false positives.
        e.predictions("", "the", lang, loc, 1, personalized = false, mayLoad = true)
        assertTrue("the $lang prediction model did not load", e.predictionsReady(lang))
        return e
    }

    private fun sentences(lang: String, want: Int): List<List<String>> {
        val f = File(fixtures(), "openvocab/loose_$lang.txt").takeIf { it.isFile }
            ?: File(fixtures(), "prose_$lang.txt")
        val loc = Locale.forLanguageTag(lang)
        return f.readLines()
            .map { line ->
                line.split(Regex("[^\\p{L}']+")).filter { it.length >= 2 }
                    .map { it.lowercase(loc) }
            }
            .filter { it.size >= 4 }
            .take(want)
    }

    private enum class Variant { SHIPPED, RIGHT_ONLY, STRICT_CLOSE, WALK }

    private data class Score(
        val flagged: Int, val clean: Int, val caught: Int, val injected: Int, val right: Int
    ) {
        val falsePct get() = flagged * 100.0 / maxOf(1, clean)
        val caughtPct get() = caught * 100.0 / maxOf(1, injected)
        val rightPct get() = right * 100.0 / maxOf(1, injected)
    }

    /**
     * One variant's answer for one word. [Variant.SHIPPED] calls the production
     * rule itself rather than a copy of it, so this file cannot drift into
     * measuring a feature the app does not have.
     */
    private fun ask(
        v: Variant,
        e: SuggestionEngine,
        lang: String,
        loc: Locale,
        prev2: String,
        prev: String,
        w: String,
        next: String
    ): String? {
        val continues = { a: String, b: String -> e.continues(a, b, lang, loc, false) }
        val left = {
            e.predictions(prev2, prev, lang, loc, ContextError.DEPTH, false, mayLoad = false)
                .map { it.lowercase(loc) }
        }
        return when (v) {
            Variant.SHIPPED -> ContextError.suggest(w, next, left, continues)
            Variant.RIGHT_ONLY -> {
                // No left-hand requirement at all, so the candidates cannot
                // come from the context and the dictionary has to supply them.
                // That is what makes this variant simultaneously the loosest
                // and the most expensive of the four.
                if (next.isEmpty() || continues(w, next)) null
                else {
                    val dict = e.cachedDictionary(lang) ?: return null
                    dict.correctionsScored(w, KeyProximity.forLang(lang), 6)
                        .map { it.first }
                        .firstOrNull { it != w && continues(it, next) }
                }
            }
            Variant.STRICT_CLOSE -> {
                if (next.isEmpty() || prev.isEmpty() || continues(w, next)) null
                else {
                    val dict = e.cachedDictionary(lang) ?: return null
                    val prox = KeyProximity.forLang(lang)
                    val l = left()
                    if (l.isEmpty() || l.contains(w)) null
                    else l.firstOrNull {
                        it != w && dict.autoCommitConfident(w, it, prox) && continues(it, next)
                    }
                }
            }
            Variant.WALK -> {
                if (next.isEmpty() || prev.isEmpty() || continues(w, next)) null
                else {
                    val dict = e.cachedDictionary(lang) ?: return null
                    val l = left()
                    if (l.isEmpty() || l.contains(w)) null
                    else dict.correctionsScored(w, KeyProximity.forLang(lang), 6)
                        .map { it.first }
                        .firstOrNull { it != w && l.contains(it) && continues(it, next) }
                }
            }
        }
    }

    private fun measure(lang: String, v: Variant): Score {
        val loc = Locale.forLanguageTag(lang)
        val e = engineOver(lang)
        val dict = e.cachedDictionary(lang)!!
        val prox = KeyProximity.forLang(lang)
        var flagged = 0
        var clean = 0
        var caught = 0
        var injected = 0
        var right = 0
        for (s in sentences(lang, 600)) {
            for (i in 1 until s.size - 1) {
                val w = s[i]
                if (!e.acceptedWord(w, lang, loc)) continue
                val prev2 = if (i >= 2) s[i - 2] else ""
                clean++
                if (ask(v, e, lang, loc, prev2, s[i - 1], w, s[i + 1]) != null) flagged++
                val wrong = dict.correctionsScored(w, prox, 4).map { it.first }
                    .firstOrNull { it != w && e.acceptedWord(it, lang, loc) } ?: continue
                injected++
                val got = ask(v, e, lang, loc, prev2, s[i - 1], wrong, s[i + 1]) ?: continue
                caught++
                if (got == w) right++
            }
        }
        return Score(flagged, clean, caught, injected, right)
    }

    @Test
    fun `it underlines almost no correctly typed word, and catches real-word errors`() {
        val report = StringBuilder()
        for (lang in listOf("en", "tr")) {
            val s = measure(lang, Variant.SHIPPED)
            report.append(
                "%s: %d/%d correct words underlined (%.2f%%), %d/%d injected errors caught (%.0f%%), named right %d (%.0f%%)\n"
                    .format(
                        lang, s.flagged, s.clean, s.falsePct, s.caught, s.injected,
                        s.caughtPct, s.right, s.rightPct
                    )
            )
            assertTrue("$lang: nothing measured\n$report", s.clean >= 1_000)
            // The number this feature lives or dies by. Measured en 0.12%,
            // tr 0.04%; the ceiling has real headroom because the fixtures and
            // the n-grams both get regenerated, and none because it may be
            // raised to let a change through. A change that moves this is a
            // change that underlines more correct writing.
            assertTrue(
                "$lang underlined %.2f%% of correctly-typed words, over the %.2f%% ceiling\n%s"
                    .format(s.falsePct, MAX_FALSE_PCT, report),
                s.falsePct <= MAX_FALSE_PCT
            )
            assertTrue(
                "$lang caught only %.0f%% of injected real-word errors\n%s"
                    .format(s.caughtPct, report),
                s.caughtPct >= MIN_CAUGHT_PCT
            )
            // When it does fire it has to be worth acting on. Measured 93% of
            // catches name the original in English and 99% in Turkish.
            assertTrue(
                "$lang named the right word in only %d of %d catches\n%s"
                    .format(s.right, s.caught, report),
                s.right * 100 >= s.caught * 85
            )
        }
        println(report)
    }

    @Test
    fun `the alternatives it was chosen over are still worse`() {
        val report = StringBuilder()
        for (lang in listOf("en", "tr")) {
            for (v in Variant.values()) {
                val s = measure(lang, v)
                report.append(
                    "%s %-13s false pos %.2f%%  caught %.0f%%\n"
                        .format(lang, v, s.falsePct, s.caughtPct)
                )
            }
        }
        println(report)

        val shipped = measure("en", Variant.SHIPPED)
        val rightOnly = measure("en", Variant.RIGHT_ONLY)
        val strict = measure("en", Variant.STRICT_CLOSE)
        // Asserted as orderings rather than as figures, so regenerating the
        // dictionaries or the n-grams does not turn this into a chore. Each one
        // is a claim the shipped design rests on.
        assertTrue(
            "dropping the left-hand context no longer costs precision, so the " +
                "reason this rule asks both neighbours has gone\n$report",
            rightOnly.falsePct > shipped.falsePct * 4
        )
        assertTrue(
            "the confident bar no longer costs recall, so the reason this rule " +
                "uses a plain one-edit test has gone\n$report",
            strict.caughtPct < shipped.caughtPct / 2
        )
    }

    private companion object {
        /** Ceiling on underlining correct text, in percent. Measured 0.12 / 0.04. */
        const val MAX_FALSE_PCT = 0.5

        /** Floor on real-word errors caught. Measured 11% / 9%. */
        const val MIN_CAUGHT_PCT = 6.0
    }
}
