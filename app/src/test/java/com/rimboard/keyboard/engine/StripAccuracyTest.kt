package com.rimboard.keyboard.engine

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import com.rimboard.keyboard.model.KeyProximity
import com.rimboard.keyboard.model.StripLayout
import java.util.Locale
import kotlin.random.Random

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
 *
 * ## Measured and rejected
 *
 * Four ideas for closing the gap at the bottom of that table, none of which
 * survived contact with it. Recorded because each is the obvious next thought
 * and none is cheap to re-derive.
 *
 *  - **Fetch more prefix matches per keystroke.** Swept `COMPLETION_FETCH`
 *    12/24/40/64: Turkish +0.1 points, English none. Candidates below twelve
 *    are rarer than the ones above and cannot outrank them, so the bottleneck
 *    was never generation.
 *  - **Deepen the shipped n-grams.** `CONTEXT_COMPLETION_DEPTH` is 12 while
 *    `build_ngrams.PER_CONTEXT` is 6, which looks like the engine asking for
 *    twice the material the asset holds. It is not: the trigram row is merged
 *    in front of the bigram row, and the map really does reach twelve entries
 *    on 47% of English contexts. The target is found at rank 6 or deeper on
 *    10% of English hits and 1% of Czech ones, so the deep ranks carry almost
 *    nothing. A rebuild at PER_CONTEXT=12 would have cost about a megabyte of
 *    APK for that.
 *  - **Prefer the best-scoring continuation over the first in display order.**
 *    0.1 points worse on Turkish, identical on English. See the note in
 *    `SuggestionEngine`.
 *  - **Reward longer completions**, on the theory that a keystroke-savings
 *    metric should prefer the candidate that saves more when it is right.
 *    Swept a length bonus at 0.05/0.10/0.20/0.35: the median moved +0.5 points
 *    while English and Turkish both *lost*. Frequency already encodes most of
 *    what length would say, and the two effects -- a long word is likelier to
 *    need the strip, and likelier to be wrong -- very nearly cancel.
 *
 * What is left is not a constant. Every language at the bottom needs the
 * grammatical form, not the word, and a counted lookup table does not know
 * agreement. That is the shape of the remaining gap, and it is worth being
 * honest that tuning will not close it.
 *
 * ## And two more, from the other end
 *
 * The typo arm leaves about 5% of mistyped words unrecoverable, and reading
 * *which* ones settles what can be done about it. Two answers, neither of them
 * a bug:
 *
 *  - **Most of them are slips onto another real word.** "if" typed as "of",
 *    "good" as "food", "on" as "in", "be" as "he". The strip shows completions
 *    of what was actually typed, because what was actually typed is a word, and
 *    a keyboard that offered "if" every time somebody wrote "of" would be wrong
 *    far more often than right. They are also overwhelmingly one- to
 *    three-letter function words, where there was almost nothing to save. This
 *    is the irreducible floor of typo correction without deeper context, not a
 *    fault to fix.
 *  - **The rest are long words with more than one slip in them**, where no
 *    single-edit path reaches the target at all.
 *
 * Both of the obvious repairs were measured and neither moved a figure:
 * widening [Dictionary.FUZZY_EDIT_WINDOW] from four to six, eight and twelve
 * changed nothing whatever, and letting a fuzzy prefix substitute the *first*
 * letter — which it is structurally forbidden to do, independently of that
 * window — changed one Turkish figure by 0.01 letters. The reason is that
 * `correctionCandidates` already reaches a first-letter slip, with a penalty;
 * the fuzzy prefix path was never what covered that case, so opening it up buys
 * a second route to answers already being found.
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

    /**
     * The same engine, with a prediction model supplied rather than shipped.
     *
     * For `heldOutSavings`, which needs a model built from a corpus that
     * excludes the sentences it is about to be scored on.
     */
    private fun engineWith(lang: String, predictions: String): SuggestionEngine {
        val files = mapOf(
            "dictionaries/$lang.txt" to File(assets(), "dictionaries/$lang.txt").readText(),
            "predictions/$lang.txt" to predictions
        )
        return SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
    }

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

    /** Every language with both a shipped dictionary and a prose fixture. */
    private fun surveyLanguages(): List<String> =
        File(assets(), "dictionaries").list().orEmpty()
            .map { it.removeSuffix(".txt") }
            .filter { File(fixtures(), "prose_$it.txt").isFile }
            .sorted()

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
        lang: String,
        locale: Locale,
        sentences: List<String>,
        withContext: Boolean,
        typos: Boolean = false,
        predictions: String? = null,
        personalized: Boolean = false,
        engineOverride: SuggestionEngine? = null
    ): Savings {
        val engine = engineOverride
            ?: if (predictions == null) realEngine(lang) else engineWith(lang, predictions)
        // Resident before the first keystroke, and not for speed.
        //
        // The keystroke path asks for the prediction model with mayLoad =
        // false, because on a phone it runs on the UI thread: a model that is
        // not in memory yet is reported absent and fetched on a warm thread for
        // next time. A harness that builds an engine and immediately starts
        // typing is therefore measuring the warm-up as well as the engine, and
        // how much of the corpus goes past before the model lands depends on
        // how busy the machine is -- which is not a property of the keyboard.
        //
        // It showed up as a test that passed alone and failed in the suite:
        // BilingualTest's cross-language savings read 34.1% run by itself and
        // 32.7% with 880 other tests competing for the cores. The seeded RNG
        // below was already there to keep the typos identical on every machine;
        // this is the same intent applied to the other source of drift.
        engine.dictionary(lang, locale)
        engine.predictions("", "x", lang, locale, 1)
        val prox = KeyProximity.forLang(lang)
        // Seeded, so the same slips happen on every machine and every run.
        val rnd = Random(seed = 20260823)
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
                        prev2, prev, lang, locale, SLOTS, personalized = personalized
                    )
                    if (preds.any { it.equals(w, ignoreCase = true) }) taken = 0
                }
                // What the finger actually put in the buffer, which is the
                // word only when it did not slip.
                val typed = if (!typos) w else buildString {
                    for (ch in w) {
                        val slip = rnd.nextDouble() < TYPO_RATE
                        val nb = if (slip) prox.neighbours(ch).firstOrNull() else null
                        append(nb ?: ch)
                    }
                }
                if (taken < 0) {
                    for (k in 1..typed.length) {
                        val res = engine.suggestionsFor(
                            typed.substring(0, k), lang, locale,
                            allowAutocorrect = true, personalized = personalized,
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

    /**
     * The same measure, but the finger misses sometimes.
     *
     * Every arm above types perfectly, which nobody does. That makes them a
     * measurement of *completion* and blind to the half of the strip that
     * exists for mistakes: mid-word, the two free chips are shared between
     * continuations of what was typed and repairs of it, and until this arm
     * there was no way to price a change that moved that boundary.
     *
     * The finger slips to an adjacent key with probability [TYPO_RATE], using
     * the same key geometry [AutocorrectAccuracyTest] damages words with, and
     * **the slip is not noticed**: the wrong letter stays in the buffer and
     * every later keystroke is typed on top of it. That is what makes this
     * hard and what makes it realistic — by the time the strip could help, the
     * prefix has been wrong for several letters.
     *
     * `never` is the number to watch rather than the savings. It is how often a
     * mistyped word could not be recovered from the strip at all, which is the
     * user ending up with the wrong word in their message.
     */
    @Test
    fun `what the strip is worth when the finger misses`() {
        val lines = StringBuilder()
        val scores = LinkedHashMap<String, Savings>()
        for ((lang, locale) in listOf("en" to Locale.ENGLISH, "tr" to Locale.forLanguageTag("tr"))) {
            val s = measure(lang, locale, sentences(lang, 120), withContext = true, typos = true)
            scores[lang] = s
            lines.append(s.line("$lang typo")).append('\n')
        }
        println(lines)
        assertTrue("the corpus generated nothing:\n$lines",
            scores.values.all { it.words >= 400 })
        assertTrue(
            "mistyped words are being lost more often than the floor allows.\n$lines",
            scores.values.all { it.never <= it.words * TYPO_LOST_CEILING }
        )
    }

    /**
     * The same measure across every language that ships, blind.
     *
     * Two languages is not a survey, and which two were chosen was an accident
     * of what the other benchmarks already used. English and Turkish turn out
     * to sit at opposite ends of this, which is exactly the shape of sample
     * that makes an average look like a fact.
     *
     * Blind only: the context arm needs the n-grams, which were counted from
     * this same corpus, and running twenty-two contaminated numbers would say
     * less than two honest ones. This is the completion path, out of domain,
     * everywhere it ships.
     *
     * Measured 2026-08-23, keystrokes saved, worst first:
     *
     *     cs 26.3   sk 28.9   uk 29.2   hu 29.3   tr 29.4   ru 30.0
     *     pl 30.0   es 31.4   fi 31.7   ro 32.1   hr 32.2   fr 32.2
     *     no 32.7   de 32.9   it 32.9   da 33.8   pt 33.9   en 34.3
     *     sv 36.3   el 37.1   nl 38.1   id 40.4
     *
     * The order is not noise, it is morphology. Every language in the bottom
     * third inflects heavily -- Czech, Slovak, Ukrainian, Hungarian, Turkish,
     * Russian, Polish -- and every language in the top third does not.
     * Indonesian, which barely inflects at all, is fourteen points clear of
     * Czech. **It is not a coverage problem:** `never` is 0% for every language
     * at the bottom, so the word is always in the dictionary. It is prefix
     * ambiguity. "kter-" in Czech continues a dozen ways that differ only in
     * the ending, and mid-word the strip has two slots to spend on it, so the
     * completion path is close to the best it can do without knowing which
     * grammatical form is wanted -- which is exactly what context knows, and
     * why Turkish gains nine points from it against English's nine and a half
     * from a much better starting point.
     */
    @Test
    fun `keystrokes saved in every language that ships`() {
        val rows = ArrayList<Pair<String, Savings>>()
        for (lang in surveyLanguages()) {
            val locale = Locale.forLanguageTag(lang)
            val corpus = sentences(lang, SURVEY_SENTENCES)
            if (corpus.size < 25) continue
            rows.add(lang to measure(lang, locale, corpus, withContext = false))
        }
        val report = rows.sortedBy { it.second.ksr }
            .joinToString("\n") { (lang, s) -> s.line(lang) }
        println(report)
        println(
            "median %.1f%%   worst %s   best %s".format(
                rows.map { it.second.ksr }.sorted()[rows.size / 2] * 100,
                rows.minByOrNull { it.second.ksr }?.first,
                rows.maxByOrNull { it.second.ksr }?.first
            )
        )

        assertTrue("the survey covered almost nothing:\n$report", rows.size >= 18)
        assertTrue(
            "a language has fallen below the floor.\n$report",
            rows.all { it.second.ksr >= SURVEY_FLOOR }
        )
    }

    /**
     * Context savings for languages the shipped fixtures cannot measure.
     *
     * The survey above runs blind everywhere, and the reason is contamination:
     * for every language but English and Turkish the prose fixture and the
     * n-grams come from the same Tatoeba corpus, so scoring the model on those
     * sentences would be scoring it on what it counted. That is a mirror, not a
     * measurement, and twenty-two of those would say less than the two honest
     * ones.
     *
     * A split fixes it. `tools/eval_ngrams.py --fixtures` builds a model from
     * nine tenths of a corpus and writes the tenth it never saw beside it, so
     * a keystroke figure taken here means for Croatian what the shipped one
     * means for English. Two models per language, at MIN_PAIR 3 and 2, because
     * the question this was added to answer is whether the coverage that
     * constant bought translates into keystrokes -- 1.45 MB was spent partly on
     * languages whose end-to-end benefit had only ever been inferred.
     *
     * Measured 2026-08-28, four corpora of differing size, 140 held-out
     * sentences each, keystrokes saved with context:
     *
     * ```
     *      MIN_PAIR 3   MIN_PAIR 2   delta
     * cs      28.3%        29.1%     +0.8
     * da      40.9%        41.4%     +0.5
     * hr      28.4%        29.3%     +0.9
     * sk      29.7%        30.0%     +0.3
     * ```
     *
     * Every one improves, and the smallest corpus improves most, which is what
     * the coverage numbers implied but could not show: a denser model helps the
     * languages that had the least. It is 0.6 points on average, not the 34%
     * English gets -- worth the 1.45 MB, and worth knowing it is that size and
     * not larger before someone spends the next megabyte on the same argument.
     *
     * Printed rather than asserted. These are small corpora and 140 sentences;
     * the number is evidence for a decision, not a threshold to defend.
     */
    /**
     * The learned store must not cost keystrokes.
     *
     * Every other arm in this file runs `personalized = false`, so the thing
     * that makes a keyboard *yours* — the words and word pairs it has watched
     * you type — has never been measured end to end. This arm gives the engine
     * a history and asks what it is worth.
     *
     * The history is real text fed exactly as the service feeds it on a
     * committed word: [UserData.learnWord] and [UserData.recordNgram] for every
     * word of it. The model is the held-out one and the text scored is the half
     * neither the model nor the history has seen, so nothing here is asked
     * about sentences it has already counted. Both split directions are run and
     * pooled, because with seventy sentences a side a single word is worth two
     * tenths of a point.
     *
     * **What it is worth: about +0.22 points, and that is a floor.** It grows
     * with the history — +0.167 after roughly 390 words, +0.221 after about
     * 1,500 — and both split directions agree on the sign.
     *
     * ## Why the obvious follow-up is a trap
     *
     * [SuggestionEngine.STATIC_WEIGHT] decides how loudly the shipped model
     * speaks against a count of how often you have typed something. Swept here
     * it improves monotonically and never turns over:
     *
     *     STATIC_WEIGHT    1.0     1.5     3.0     5.0     8.0    20.0   100
     *     personal gain  -0.177  +0.061  +0.221  +0.350  +0.436  +0.490  +0.44
     *
     * Read literally that says "raise it, and keep raising it" — trust the
     * shipped model, almost never the user. **The reason it says that is that
     * this corpus cannot represent a person.** The history is drawn from the
     * same distribution as the model's training data, so it holds no idiom the
     * model lacks; it is a small noisy sample of what the model already knows
     * well. Under those conditions preferring the model is simply correct, and
     * the sweep dutifully says so. A real user's value is exactly the
     * difference between their writing and the corpus average, and there is
     * none of that difference in this data.
     *
     * So the sweep is recorded and not acted on, which is the same discipline
     * `GlideAccuracyTest` learned the hard way: a measurement that covers one
     * population must not set a constant that governs another.
     *
     * What is safe to take from it is this assertion. The harmful regime is
     * real and reachable — at 1.0 the store costs 0.18 points — so a change
     * that lets learned junk outrank the model would show up here. The `off`
     * column reads 39.716% at every weight, which is the control: with
     * personalisation off the model's rank ordering is scale-invariant, so this
     * constant touches nothing but the personalised path.
     */
    @Test
    fun `the learned store does not cost keystrokes`() {
        val dir = File(fixtures(), "heldout")
        val langs = dir.list().orEmpty()
            .filter { it.startsWith("prose_") && it.endsWith(".txt") }
            .map { it.removePrefix("prose_").removeSuffix(".txt") }.sorted()
        val out = StringBuilder()
        var onSum = 0.0
        var offSum = 0.0
        var halves = 0
        for (lang in langs) {
            val loc = Locale.forLanguageTag(lang)
            val all = File(dir, "prose_$lang.txt").readLines().filter { it.isNotBlank() }
            val half = all.size / 2
            // A bigger history: the main prose fixture as well. It is the
            // corpus the *shipped* model was built from, but the model used
            // here is the held-out one, and the test half is text neither has
            // seen -- so this is a user with more of their own writing behind
            // them, not a leak.
            val extra = File(fixtures(), "prose_$lang.txt").let {
                if (it.isFile) it.readLines().filter { l -> l.isNotBlank() } else emptyList()
            }
            for (reversed in listOf(false, true)) {
            val history = extra + (if (reversed) all.drop(half) else all.take(half))
            val test = if (reversed) all.take(half) else all.drop(half)
            val store = File.createTempFile("hist_" + lang, "").let { it.delete(); it.mkdirs(); it }
            val ud = UserData.inDir(store)
            // Fed exactly as the service feeds it on a committed word.
            var words = 0
            for (sentence in history) {
                val ws = wordsOf(sentence, loc)
                for ((i, w) in ws.withIndex()) {
                    val lw = w.lowercase(loc)
                    if (lw.length >= 2) ud.learnWord(lw)
                    ud.recordNgram(
                        if (i >= 2) ws[i - 2].lowercase(loc) else "",
                        if (i >= 1) ws[i - 1].lowercase(loc) else "",
                        lw
                    )
                    words++
                }
            }
            val files = mapOf(
                "dictionaries/$lang.txt" to File(assets(), "dictionaries/$lang.txt").readText(),
                "predictions/$lang.txt" to File(dir, "pred2_$lang.txt").readText()
            )
            val engine = SuggestionEngine.forTesting(ud) { p -> files[p]?.byteInputStream() }
            val on = measure(lang, loc, test, withContext = true, personalized = true,
                engineOverride = engine)
            val off = measure(lang, loc, test, withContext = true, personalized = false,
                engineOverride = engine)
            out.append("%-3s %-5s history %d words: personal %.2f%%  off %.2f%%  (%+.2f)%n"
                .format(lang, if (reversed) "back" else "fwd", words,
                        on.ksr * 100, off.ksr * 100, (on.ksr - off.ksr) * 100))
            onSum += on.ksr * 100
            offSum += off.ksr * 100
            halves++
            ud.shutdown()
            store.deleteRecursively()
            }
        }
        val on = onSum / halves
        val off = offSum / halves
        out.append("pooled over %d halves: personal %.3f%%  off %.3f%%  (%+.3f)%n"
            .format(halves, on, off, on - off))
        println(out)
        assertTrue(
            "the learned store is costing keystrokes rather than saving them:" +
                System.lineSeparator() + out,
            on >= off
        )
    }


    @Test
    fun `held-out context savings, for languages the fixtures cannot`() {
        val dir = File(fixtures(), "heldout")
        if (!dir.isDirectory) return
        val langs = dir.list().orEmpty()
            .filter { it.startsWith("prose_") && it.endsWith(".txt") }
            .map { it.removePrefix("prose_").removeSuffix(".txt") }
            .sorted()
        val out = StringBuilder()
        for (lang in langs) {
            val locale = Locale.forLanguageTag(lang)
            val corpus = File(dir, "prose_$lang.txt").readLines().filter { it.isNotBlank() }
            // Three models per language now, because the constant they sweep
            // is per-language and these four all sit on the low side of it.
            // Coverage is an input to keystrokes saved and not a synonym for
            // it, which is the whole reason this arm exists, so a change that
            // only ever quoted coverage would be quoting the wrong number.
            val scores = listOf(3, 2, 1).map { mp ->
                mp to measure(
                    lang, locale, corpus, withContext = true,
                    predictions = File(dir, "pred${mp}_$lang.txt").readText()
                )
            }
            out.append("    %-3s held-out context:".format(lang))
            for ((mp, sc) in scores) {
                out.append(
                    "  MIN_PAIR %d saved %.1f%% (predicted %.0f%%)"
                        .format(mp, sc.ksr * 100, sc.predicted * 100.0 / sc.words)
                )
            }
            out.append("%n".format())
        }
        println(out)
        assertTrue("no held-out fixtures found", langs.isNotEmpty())
    }

    private companion object {
        /**
         * Under the worst honestly-measured arm, with room for corpus noise.
         *
         * Measured 2026-08-23, keystrokes saved over 120 sentences of real
         * prose:
         *
         *     en blind 33.7%    en context 43.1%
         *     tr blind 29.2%    tr context 38.1%
         *
         * Re-measured 2026-08-28, after MIN_PAIR fell to 2 and the context
         * weight to 1.25:
         *
         *     en blind 34.4%    en context 43.9%
         *     tr blind 29.2%    tr context 39.9%
         *
         * The blind arms are untouched by that change, as they must be -- they
         * never consult the prediction model -- and they are the only arms
         * asserted on. Turkish gained 1.8 points and English 0.1, which is the
         * held-out coverage measurement showing up end to end: English was
         * already saturated at three occurrences and had 0.4 points of coverage
         * to gain, Turkish had 3.0.
         *
         * Two engine changes moved these: one slot reserved for finishing the
         * word, and generated Turkish inflections anchored below the attested
         * completions instead of above all but one of them. Between the two the
         * *corpus* also changed -- the fixture builder went from rejecting any
         * sentence with an interior capital (which throws away nearly all of
         * German) to the outlier detector build_ngrams.py already used -- so
         * the figures before that change are not comparable to these and are
         * not reproduced here. The engine improvements they measured are
         * separately pinned by the tests in SuggestionEngineTest.
         *
         * Only the blind arms are asserted on. The context arm is scored partly
         * on the register its own n-grams were counted from, so letting it hold
         * the floor would let a regression in the completion path hide behind
         * the model having seen prose like this before.
         *
         * ## Re-measured 2026-09-04, and the floor tightened to match
         *
         *     en blind 40.3%    en context 48.8%
         *     tr blind 36.9%    tr context 46.2%
         *
         * Six points above what is recorded above, from changes since. The
         * floor was still 0.25 -- **twelve points under the arm it guards** --
         * and a ratchet that far below the measurement is not a ratchet: a
         * change costing ten points of keystroke savings, which would be an
         * enormous regression, passed it without a word.
         *
         * These corpora are seeded and shipped in the repository, so there is
         * no run-to-run noise to leave room for; what room remains is for the
         * JDK the suite happens to run on (CI is 17 and this machine is 21,
         * whose CLDR tables differ) and for the assets being rebuilt. Five
         * points is generous for both. Anything larger is a number nobody has
         * to think about, and this file's whole argument is that the numbers
         * are worth thinking about.
         */
        const val KSR_FLOOR = 0.32

        /**
         * Sentences per language in the survey.
         *
         * Fewer than the two-language arm uses, because twenty-two dictionaries
         * have to be parsed and walked. It is enough to separate the languages
         * from each other, which is all the survey is for; the arm above is
         * where a number is read closely.
         */
        const val SURVEY_SENTENCES = 60

        /**
         * How often the finger lands on the neighbouring key.
         *
         * Per *letter*, so a six-letter word is mistyped about a quarter of the
         * time at 5% — which is roughly what unhurried thumb typing looks like
         * and is deliberately not a worst case.
         */
        const val TYPO_RATE = 0.05

        /**
         * Share of mistyped words that may go unrecovered.
         *
         * Measured 2026-09-04: **4%** on both typo arms. The ceiling was 0.30,
         * which is twenty-six points of slack and could only ever have fired
         * on a catastrophe. Three times the measured value is still generous
         * for a figure this small.
         */
        const val TYPO_LOST_CEILING = 0.12

        /**
         * Under the worst language measured, with room for corpus noise.
         *
         * Czech at 26.3% is the floor-setter, not Turkish. That is worth
         * saying because Turkish was assumed to be the worst case for two
         * sessions on the strength of it being the one non-English language
         * anything was measured on.
         *
         * Re-measured 2026-09-04: Czech is still the floor-setter and now
         * reads **34.7%**, with the survey running cs 34.7 to id 47.4. The
         * floor stayed at 0.22, nearly thirteen points under it. Tightened for
         * the reason given on [KSR_FLOOR].
         */
        const val SURVEY_FLOOR = 0.30
    }
}
