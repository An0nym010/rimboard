package com.rimboard.keyboard.engine

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import com.rimboard.keyboard.model.StripLayout
import java.util.Locale

/**
 * How much typing the suggestion strip actually saves, over real prose.
 *
 * The strip runs on every keystroke and is the whole of why a keyboard feels
 * fast, and until this file nothing had put a number on it.
 * [AutocorrectAccuracyTest] scores the *repair* of a word already typed wrong
 * and [GlideAccuracyTest] scores a swipe; neither asks the question a user
 * actually experiences, which is how many letters they had to type before the
 * word they wanted was on offer.
 *
 * ## The measure
 *
 * Keystroke savings, the standard way input methods are compared. Typing a
 * word costs its letters plus a space. Accepting it from the strip after `k`
 * letters costs `k` taps plus one on the suggestion, which carries the space
 * with it. The saving is what that avoids, over the whole corpus.
 *
 * It is an *upper bound on a real session* and should be read as one: it
 * assumes the user glances at the strip after every letter and takes the word
 * the moment it appears. Nobody types that way. What it is good for is
 * comparison — between two versions of this engine, and against the published
 * figures of other keyboards, which are measured the same idealised way.
 *
 * ## What this corpus can and cannot say
 *
 * The sentences are real Tatoeba prose, lifted into `src/test/fixtures` by
 * `tools/build_prose_fixture.py` so the figure is reproducible off this
 * machine. Two arms, because the two halves of the strip have very different
 * standing to be measured here:
 *
 *  - **blind** — no preceding word, so the strip is pure prefix completion out
 *    of the bundled dictionary. Those dictionaries are built from OPUS
 *    OpenSubtitles, a different corpus entirely, so this arm is an honest
 *    out-of-domain measurement and the one to trust.
 *  - **context** — the real configuration, with the preceding words passed.
 *    The bundled n-grams are *counted from Tatoeba*, so this arm scores the
 *    model partly on its own training data and is a ceiling rather than a
 *    figure. It is here for the gap between the two, which is the only part
 *    that means anything: it bounds what context can be worth.
 */
class StripAccuracyTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-strip", "").let { it.delete(); it.mkdirs(); it }
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

    /** The words of a sentence, in order, as the keyboard would compose them. */
    private fun wordsOf(sentence: String, locale: Locale): List<String> =
        sentence.split(Regex("[^\\p{L}']+"))
            .map { it.trim('\'') }
            .filter { it.isNotEmpty() && it.all { c -> c.isLetter() || c == '\'' } }
            .map { it.lowercase(locale) }

    private fun sentences(lang: String, count: Int): List<String> =
        File(fixtures(), "prose_$lang.txt").readLines().filter { it.isNotBlank() }.take(count)

    /** How many slots the suggestion strip has. */
    private val SLOTS = 3

    private data class Savings(
        val keystrokes: Int,
        val baseline: Int,
        val words: Int,
        /** Words offered before a single letter was typed. */
        val predicted: Int,
        /** Words on offer by the third letter. */
        val byThree: Int,
        /** Words never offered at all. */
        val never: Int,
        val lettersTyped: Int
    ) {
        val ksr get() = 1.0 - keystrokes.toDouble() / baseline
        fun line(label: String) =
            "%-18s saved %4.1f%%   predicted %3.0f%%   by 3 letters %3.0f%%   " .format(
                label, ksr * 100, predicted * 100.0 / words, byThree * 100.0 / words
            ) + "never %3.0f%%   mean letters %.2f".format(
                never * 100.0 / words, lettersTyped.toDouble() / words
            )
    }

    /**
     * Types [sentences] one letter at a time and records when the strip caught up.
     *
     * [withContext] is the whole difference between the two arms: when false the
     * engine is told nothing about what came before, which is what makes the
     * completion path measurable against a corpus it did not come from.
     */
    private fun measure(
        lang: String, locale: Locale, sentences: List<String>, withContext: Boolean
    ): Savings {
        val engine = realEngine(lang)
        var keystrokes = 0
        var baseline = 0
        var words = 0
        var predicted = 0
        var byThree = 0
        var never = 0
        var lettersTyped = 0

        for (sentence in sentences) {
            val ws = wordsOf(sentence, locale)
            for ((i, w) in ws.withIndex()) {
                val prev = if (withContext && i >= 1) ws[i - 1] else ""
                val prev2 = if (withContext && i >= 2) ws[i - 2] else ""
                words++
                baseline += w.length + 1

                var taken = -1
                // k = 0 is the strip before a letter is typed, which is the
                // prediction path rather than the completion one -- a different
                // call, and the reason a word can be free.
                if (prev.isNotEmpty() || (withContext && i == 0)) {
                    val preds = engine.predictions(
                        prev2, prev, lang, locale, SLOTS, personalized = false
                    )
                    if (preds.any { it.equals(w, ignoreCase = true) }) taken = 0
                }
                if (taken < 0) {
                    for (k in 1..w.length) {
                        val res = engine.suggestionsFor(
                            w.substring(0, k), lang, locale,
                            allowAutocorrect = true, personalized = false,
                            prevWord2 = prev2, prevWord = prev
                        )
                        if (strip(engine, res, lang, locale)
                                .any { it.equals(w, ignoreCase = true) }
                        ) {
                            taken = k
                            break
                        }
                    }
                }
                if (taken < 0) {
                    // Typed out in full, plus the space.
                    keystrokes += w.length + 1
                    lettersTyped += w.length
                    never++
                } else {
                    // The letters, then one tap that brings the space with it.
                    keystrokes += taken + 1
                    lettersTyped += taken
                    if (taken == 0) predicted++
                    if (taken <= 3) byThree++
                }
            }
        }
        return Savings(keystrokes, baseline, words, predicted, byThree, never, lettersTyped)
    }

    /**
     * The three chips the user actually sees, which is not what the engine
     * returns.
     *
     * [StripLayout] moves a typed prefix the dictionary does not know into the
     * middle slot in quotes, so that a word being typed keeps a place on the
     * strip. It is the right behaviour and it costs a slot: mid-word, the strip
     * carries **two** completions and not three. Reading `items.take(3)` here
     * instead would have credited the decoder with a chip nobody can tap, which
     * is the same measurement fault the glide benchmark was caught by.
     */
    private fun strip(
        engine: SuggestionEngine, res: SuggestionsResult, lang: String, locale: Locale
    ): List<String> {
        val verbatim = res.items.firstOrNull() ?: return emptyList()
        val known = engine.acceptedWord(verbatim, lang, locale)
        return StripLayout.arrange(res.items, res.autocorrectIndex, known) { "\u201C$it\u201D" }
            .words
    }

    @Test
    fun `the strip saves this much typing, over prose it has never seen`() {
        val lines = StringBuilder()
        val blind = LinkedHashMap<String, Savings>()
        for ((lang, locale) in listOf("en" to Locale.ENGLISH, "tr" to Locale.forLanguageTag("tr"))) {
            val corpus = sentences(lang, 120)
            val b = measure(lang, locale, corpus, withContext = false)
            val c = measure(lang, locale, corpus, withContext = true)
            blind[lang] = b
            lines.append(b.line("$lang blind")).append('\n')
            lines.append(c.line("$lang context")).append('\n')
        }
        println(lines)

        assertTrue("the corpus generated nothing:\n$lines",
            blind.values.all { it.words >= 400 })
        // A floor on the arm that is honestly measured. The context arm is a
        // ceiling and must never be the thing asserted on -- it would let a
        // regression in the completion path hide behind the n-grams having
        // seen the sentence before.
        assertTrue(
            "keystroke savings have fallen below the floor.\n$lines",
            blind.values.all { it.ksr >= KSR_FLOOR }
        )
    }

    private companion object {
        /**
         * Under the worst honestly-measured arm, with room for corpus noise.
         *
         * Measured 2026-08-23, keystrokes saved over 120 sentences of real
         * prose:
         *
         *     arm            at first measurement   now
         *     en blind               34.2%            34.6%
         *     en context             43.1%            43.5%
         *     tr blind               25.9%            28.5%
         *     tr context             32.3%            34.7%
         *
         * Two changes account for the difference: one slot reserved for
         * finishing the word, and generated Turkish inflections anchored below
         * the attested completions instead of above all but one of them.
         *
         * Only the blind arms are asserted on. The context arm is scored partly
         * on the register its own n-grams were counted from, so letting it hold
         * the floor would let a regression in the completion path hide behind
         * the model having seen prose like this before.
         */
        const val KSR_FLOOR = 0.25
    }
}
