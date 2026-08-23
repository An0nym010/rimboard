package com.rimboard.keyboard.engine

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * What a keystroke costs, on the thread that draws the keyboard.
 *
 * Everything the strip does happens between the finger going down and the next
 * frame. [StripAccuracyTest] asks whether the answer is any good; this asks
 * whether it arrives in time, which is a different question and the one nobody
 * had asked. The only work budget anywhere in this engine is
 * [com.rimboard.keyboard.spell.SpellJudge.CORRECTION_BUDGET], and that governs
 * the system spell checker on a binder thread — the keyboard's own per-keystroke
 * path has never had one, or a number.
 *
 * ## Reading these figures
 *
 * A desktop JVM is not a phone. Treat the absolute numbers as an order of
 * magnitude and a regression tripwire, and the *comparisons* — between
 * languages, between the mean and the tail — as the real content. A mid-range
 * phone is commonly reckoned five to ten times slower than a warm desktop JVM
 * on this kind of work, so the frame budget worth thinking about here is a
 * small fraction of 16 ms rather than 16 ms itself.
 *
 * The tail matters more than the mean. A keyboard whose average keystroke is
 * fast and whose worst is fifty times that does not feel fast; it feels like it
 * catches. That is why p99 is what the assertion is on and the mean is almost
 * an aside.
 *
 * **The `worst` column is noise and is printed anyway.** Its top entries are
 * routinely two-letter prefixes that cannot possibly be the most expensive
 * thing measured -- a garbage collection landed on them. It is kept because a
 * *sustained* change in it would mean something, and read with the knowledge
 * that any single value in it is a pause and not work.
 *
 * ## What this found
 *
 * On the first run, the median keystroke cost 0.02 ms and p99 cost 6 ms -- a
 * tail three hundred times the middle, concentrated at prefixes of six letters
 * and up, which is where [Dictionary.maxEditDistance] widens the correction
 * budget from one edit to two. Timing the pieces put 82% of a keystroke in
 * [SuggestionEngine.correctionCandidates].
 *
 * Two things were wrong with it, both fixed without changing a single
 * suggestion: the distance function allocated three arrays per candidate word
 * across a scan of a hundred thousand of them, and it computed every cell of
 * the matrix when only a band of five per row can matter. A letter-set bound
 * in front of the scan then removed most of the candidates before any matrix
 * was built at all.
 *
 *     worst p99 across all languages   6.03 ms -> 1.33 ms
 *     Ukrainian p99                    6.03 ms -> 0.73 ms
 *     Turkish p99                      5.98 ms -> 1.04 ms
 *
 * Every figure in [AutocorrectAccuracyTest] and [StripAccuracyTest] is
 * unchanged to the digit, which is the claim that matters: this is the same
 * keyboard, three to four times faster on the keystrokes that were slow.
 *
 * ## What is timed
 *
 * The three engine calls the service makes for every letter typed, in the order
 * it makes them: [SuggestionEngine.suggestionsFor], then
 * [SuggestionEngine.acceptedWord] for the verbatim chip, then
 * [SuggestionEngine.emojiFor]. Everything else on that path is view work or
 * needs an Android Context and cannot run here.
 */
class StripLatencyTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-latency", "").let { it.delete(); it.mkdirs(); it }
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

    private fun realEngine(lang: String): SuggestionEngine {
        val files = listOf("dictionaries/$lang.txt", "predictions/$lang.txt")
            .associateWith { File(assets(), it).readText() }
        return SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
    }

    private fun wordsOf(s: String, locale: Locale): List<String> =
        s.split(Regex("[^\\p{L}']+")).map { it.trim('\'') }
            .filter { it.isNotEmpty() && it.all { c -> c.isLetter() || c == '\'' } }
            .map { it.lowercase(locale) }

    private fun languages(): List<String> =
        File(assets(), "dictionaries").list().orEmpty()
            .map { it.removeSuffix(".txt") }
            .filter { File(fixtures(), "prose_$it.txt").isFile }
            .sorted()

    /**
     * Every prefix of every word of [sentences] — the exact sequence of
     * composing buffers the engine sees as the sentence is typed out.
     */
    private fun keystrokes(lang: String, locale: Locale, count: Int): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (s in File(fixtures(), "prose_$lang.txt").readLines()
            .filter { it.isNotBlank() }.take(count)) {
            val ws = wordsOf(s, locale)
            for ((i, w) in ws.withIndex()) {
                val prev = if (i >= 1) ws[i - 1] else ""
                for (k in 1..w.length) out.add(w.substring(0, k) to prev)
            }
        }
        return out
    }

    private data class Timing(val lang: String, val us: LongArray, val worstAt: String) {
        val mean get() = us.average() / 1000.0
        fun pct(p: Double): Double {
            val sorted = us.sorted()
            return sorted[((sorted.size - 1) * p).toInt()] / 1000.0
        }
        fun line() = "%-4s n=%5d  mean %5.2f ms   p50 %5.2f   p99 %6.2f   worst %7.2f  (%s)"
            .format(lang, us.size, mean, pct(0.50), pct(0.99), us.max() / 1000.0, worstAt)
    }

    private fun time(lang: String, sentences: Int): Timing {
        val locale = Locale.forLanguageTag(lang)
        val engine = realEngine(lang)
        val strokes = keystrokes(lang, locale, sentences)

        // Warm the JIT and force the dictionary and prediction model to load,
        // so the first keystroke does not carry a one-off asset parse that no
        // real keystroke carries either — the service warms both off-thread.
        for ((w, prev) in strokes.take(400)) {
            engine.suggestionsFor(w, lang, locale, true, false, prevWord = prev)
        }

        val us = LongArray(strokes.size)
        var worst = 0L
        var worstAt = ""
        for ((i, s) in strokes.withIndex()) {
            val (w, prev) = s
            val t0 = System.nanoTime()
            val res = engine.suggestionsFor(
                w, lang, locale, allowAutocorrect = true, personalized = true,
                prevWord = prev
            )
            engine.acceptedWord(res.items.firstOrNull() ?: w, lang, locale)
            if (w.length >= 2) engine.emojiFor(w, lang)
            val dt = (System.nanoTime() - t0) / 1000
            us[i] = dt
            if (dt > worst) { worst = dt; worstAt = w }
        }
        return Timing(lang, us, worstAt)
    }

    @Test
    fun `a keystroke costs this much, in every language that ships`() {
        val rows = languages().map { time(it, SENTENCES) }
        val report = rows.sortedByDescending { it.pct(0.99) }
            .joinToString("\n") { it.line() }
        println(report)
        val worstP99 = rows.maxOf { it.pct(0.99) }
        val worstEver = rows.maxOf { it.us.max() / 1000.0 }
        println("worst p99 %.2f ms, worst single keystroke %.2f ms".format(worstP99, worstEver))

        assertTrue("the corpus generated nothing:\n$report", rows.all { it.us.size > 500 })
        // A tripwire, not a target, and set on the tail because the tail is what
        // a user feels. Raising it to make a change pass is the one use it must
        // never be put to; a change that needs it raised has made typing
        // stutter on the slowest phone this ships to.
        assertTrue(
            "per-keystroke latency has regressed.\n$report",
            worstP99 <= P99_CEILING_MS
        )
    }

    private companion object {
        const val SENTENCES = 40

        /**
         * Above the worst language measured, with room for a noisy machine.
         *
         * Measured at 1.33 ms across twenty-two languages, and 1.81 on a
         * second run, so the run-to-run spread is real and the ceiling sits
         * clear of it. It was 6.03 before the correction scan was fixed; three
         * is low enough to catch a slide back toward that and high enough not
         * to fail on a busy build machine.
         */
        const val P99_CEILING_MS = 3.0
    }
}
