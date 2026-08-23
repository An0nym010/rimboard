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
 * Then the word list stopped being an array of `String` objects and became one
 * concatenated `CharArray` -- done for memory, and worth another third here for
 * free. A scan of a hundred thousand candidates walks one contiguous array
 * instead of chasing a pointer per word, and compares in place.
 *
 *     worst p99 across all languages   1.33 ms -> 0.80-0.96 ms
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

    /**
     * How long after opening the keyboard the suggestions arrive.
     *
     * [SuggestionEngine.warm] loads the dictionary and the prediction model off
     * the UI thread, so this never blocks a keystroke — what it delays is the
     * strip having anything to say. Until then the keyboard types fine and
     * suggests nothing, which is a worse first impression than a slow one.
     *
     * `warm` logs its own duration on a device and nothing had ever measured
     * it, which is a fair description of most timing in this app before today.
     * It is also the check on the store rewrite: the words moved from separate
     * `String` objects into one concatenated array, and a load that copies two
     * and a half million characters could easily have cost more than it saved.
     * It did not — the same change deleted a three-hundred-thousand-entry
     * `HashSet` build from the same constructor.
     *
     * ## Where the time goes
     *
     * Timed by phase, once, and worth not re-deriving: **sorting dominates**
     * (19–78 ms), parsing is second (21–52 ms), and everything after them —
     * building the store, the character-transition model, the length buckets
     * and the diacritic index — is the rest. German and Turkish sort slowest
     * because their words are longest and share the deepest prefixes, so a
     * string comparison in the sort runs further before it decides.
     *
     * The one thing found and fixed here was in the phase after: `foldDiacritics`
     * ran a Unicode normalisation and built two objects for every word in the
     * language, including the ones with nothing to fold. An ASCII fast path took
     * the worst language from 361 ms to 196.
     *
     *     de 337 -> 159 ms    cs 250 -> 119    tr 150 -> 177    en 117 -> 134
     *
     * The sort is the obvious remaining target and is deliberately left alone.
     * It could be skipped entirely by shipping the word lists already in
     * alphabetical order, but the extended dictionaries are published on a
     * branch with their SHA-256 compiled into the APK, so re-ordering the
     * format means re-publishing and re-pinning all thirteen of them. That is a
     * lot of moving parts for a background load that happens once per process.
     */
    @Test
    fun `how long until the strip has anything to say`() {
        val lines = StringBuilder()
        var worst = 0.0
        for (lang in languages()) {
            val text = File(assets(), "dictionaries/$lang.txt").readText()
            val model = File(assets(), "predictions/$lang.txt").readText()
            // One discarded build so the measurement is not the JIT's first
            // sight of the parser.
            Dictionary(text.byteInputStream(), null, Locale.forLanguageTag(lang))

            val t0 = System.nanoTime()
            val d = Dictionary(text.byteInputStream(), null, Locale.forLanguageTag(lang))
            val dictMs = (System.nanoTime() - t0) / 1e6
            val t1 = System.nanoTime()
            val engine = realEngine(lang)
            // The synchronous door to the prediction model; the keyboard's
            // own path loads it on the warm thread for the same reason.
            engine.predictions("", "i", lang, Locale.forLanguageTag(lang), 1)
            val modelMs = (System.nanoTime() - t1) / 1e6

            val total = dictMs + modelMs
            if (total > worst) worst = total
            lines.append(
                "%-3s %,8d words   dictionary %6.0f ms   predictions %5.0f ms   total %6.0f ms\n"
                    .format(lang, d.size, dictMs, modelMs, total)
            )
            if (model.isEmpty()) throw IllegalStateException("no model for $lang")
        }
        println(lines)
        println("worst language: %.0f ms".format(worst))

        assertTrue(
            "cold start has regressed past the ceiling.\n$lines",
            worst <= COLD_START_CEILING_MS
        )
    }

    private companion object {
        const val SENTENCES = 40

        /**
         * Above the slowest language measured, with room for a busy machine.
         *
         * This is a background load, so it buys nothing to be fast and costs a
         * great deal to be slow: it is the window in which the keyboard types
         * fine and suggests nothing. Turkish is the slowest at 196 ms, and load
         * times move much more run to run than keystroke times do -- the same
         * language has measured 117 and 134 ms in consecutive runs -- so this
         * sits well clear rather than close.
         */
        const val COLD_START_CEILING_MS = 600.0

        /**
         * Above the worst language measured, with room for a noisy machine.
         *
         * Measured at 0.80, 0.88 and 0.96 ms across three runs of all
         * twenty-two languages, so the run-to-run spread is a fifth of a
         * millisecond and the ceiling sits well clear of it. It was 6.03
         * before the correction scan was fixed. Two is low enough to catch a
         * slide back toward that and high enough not to fail on a busy build
         * machine.
         */
        const val P99_CEILING_MS = 2.0
    }
}
