package com.rimboard.keyboard.engine

import com.rimboard.keyboard.model.KeyProximity
import com.rimboard.keyboard.model.PostCorrection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.random.Random

/**
 * What post-correction wins, and what it costs.
 *
 * [AutocorrectAccuracyTest] measures the ranking; this measures a *decision*,
 * and the two columns below are the only honest way to report one. Repairs
 * alone would make any loosening look like an improvement — the way to rescue
 * every typo is to rewrite everything — so every number here is printed beside
 * the correctly-typed words the same setting destroys.
 *
 * ## The population, which is the whole design
 *
 * Post-correction is only ever asked about a word autocorrect **declined**.
 * The rescue arm therefore throws away every typo `correctionFor` already
 * fixes and measures what is left, which is the population the feature exists
 * for and a much harder one than the corpus in [AutocorrectAccuracyTest]: by
 * construction, every word in it sits far enough from its repair that the
 * distance bar refused it.
 *
 * ## The destruction arm cannot be generated damage
 *
 * A rescue can be: damage a word and see if it comes back. The opposite risk
 * cannot, because the words at risk are the ones nobody damaged — correctly
 * typed words the shipped list does not hold. That population is
 * [OutOfVocabularyTest]'s, built the same way, and the arm's own doc explains
 * how a realistic follower is supplied for a word too rare to have one.
 *
 * ## What the sweep found, which is why this file exists
 *
 * [PostCorrection.SLACK] is the one constant and it is swept here rather than
 * by hand-patching it, so the conclusion can be re-derived rather than
 * believed. Measured 2026-09-07 over 428 English and 346 Turkish typos that
 * autocorrect declined:
 *
 *     en  slack 1.0   rescued 345 (81%)   mis-repaired  17 (4%)
 *         slack 1.3   rescued 347 (81%)   mis-repaired  75 (18%)
 *         slack 1.6   rescued 315 (74%)   mis-repaired 107 (25%)
 *         slack 2.0   rescued 270 (63%)   mis-repaired 152 (36%)
 *         slack 3.0   rescued 268 (63%)   mis-repaired 155 (36%)
 *     tr  slack 1.0   rescued 276 (80%)   mis-repaired   6 (2%)
 *         slack 1.3   rescued 300 (87%)   mis-repaired  40 (12%)
 *         slack 1.6   rescued 283 (82%)   mis-repaired  58 (17%)
 *         slack 2.0   rescued 268 (77%)   mis-repaired  73 (21%)
 *
 * **Loosening the bar loses repairs.** That reads as an instrument fault until
 * you notice the winner is the *first* candidate the follower endorses, in the
 * engine's own order: a near-but-wrong candidate above the right one is refused
 * at 1.0 and admitted at 1.6, and once admitted it wins. The bar filters the
 * race, not just the answer. So the shipped value is 1.0 and the feature turns
 * out to apply nothing the space bar's bar would have refused — see
 * [PostCorrection], whose design premise this measurement overturned.
 *
 * The Turkish 1.3 row is the one place the two columns disagree (+24 repairs
 * for +34 mis-repairs). An earlier draft of this file ran a corpus a tenth of
 * this size and read that row as "+14 repairs at zero cost", which was noise;
 * it is recorded here because a 26-word corpus produced a confident wrong
 * answer and the only thing that caught it was making the corpus bigger.
 */
class PostCorrectionAccuracyTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-postcorrect", "").let { it.delete(); it.mkdirs(); it }
        userData = UserData.inDir(dir)
    }

    @After
    fun tearDown() {
        userData.shutdown()
        dir.deleteRecursively()
    }

    private fun assets(): File =
        listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }

    /**
     * An engine over the shipped assets for one language, warmed.
     *
     * **Both warmings are load-bearing.** `postCorrectionFor` may not load
     * anything — it runs on the typing path — so it reads the dictionary
     * through `cachedDictionary` and the n-grams through `predictionsReady`,
     * and both answer "nothing here" rather than parsing. A test that skipped
     * this would measure an engine that declines every case for want of data
     * and would report it as a feature that never fires. That is the blind
     * instrument [AutocorrectAccuracyTest] documents having been caught by, in
     * the one form it can still take.
     */
    private fun engineOver(lang: String, dictText: String? = null): SuggestionEngine {
        val files = HashMap<String, String>()
        for (kind in listOf("dictionaries", "predictions", "suffixes", "prefixes")) {
            File(assets(), "$kind/$lang.txt").takeIf { it.isFile }?.let {
                files["$kind/$lang.txt"] = it.readText()
            }
        }
        dictText?.let { files["dictionaries/$lang.txt"] = it }
        val e = SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
        val locale = Locale.forLanguageTag(lang)
        e.dictionary(lang, locale)
        e.predictions("", "the", lang, locale, 1, personalized = false, mayLoad = true)
        assertTrue(
            "the prediction model for $lang did not load, so every context " +
                "rule below is inert and this test would measure nothing",
            e.predictionsReady(lang)
        )
        return e
    }

    /**
     * The pairs the shipped n-grams actually hold, as (word, follower).
     *
     * Taken from the asset rather than invented, because the rescue arm's whole
     * premise is that the follower is evidence *this engine can read*. A pair
     * the model does not hold would measure the model's coverage instead, which
     * is [StripAccuracyTest]'s question.
     */
    private fun pairs(lang: String, want: Int): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        File(assets(), "predictions/$lang.txt").forEachLine { line ->
            if (out.size >= want) return@forEachLine
            val tab = line.indexOf('\t')
            if (tab <= 0) return@forEachLine
            val head = line.substring(0, tab)
            // Long enough that a repair is not a coin toss, which is the same
            // bound `AutocorrectAccuracyTest.sample` draws for the same reason.
            if (head.length !in 5..9 || !head.all { it.isLetter() }) return@forEachLine
            val follower = line.substring(tab + 1).split(' ').firstOrNull().orEmpty()
            if (follower.isEmpty() || !follower.all { it.isLetter() }) return@forEachLine
            out.add(head to follower)
        }
        return out
    }

    private enum class Slip { NEIGHBOUR, DOUBLED, DROPPED, SWAPPED }

    /** One word, damaged one way, or null when it cannot take that damage. */
    private fun damage(word: String, slip: Slip, prox: KeyProximity, rnd: Random): String? {
        if (word.length < 4) return null
        val i = 1 + rnd.nextInt(word.length - 2)
        return when (slip) {
            Slip.NEIGHBOUR -> prox.neighbours(word[i]).firstOrNull()
                ?.let { word.substring(0, i) + it + word.substring(i + 1) }
            Slip.DOUBLED -> word.substring(0, i) + word[i] + word.substring(i)
            Slip.DROPPED -> word.substring(0, i) + word.substring(i + 1)
            Slip.SWAPPED -> word.substring(0, i) + word[i + 1] + word[i] +
                word.substring(i + 2)
        }?.takeIf { it != word }
    }

    /**
     * The decision, reconstructed so the slack can be varied.
     *
     * `SuggestionEngine.postCorrectionFor` hard-codes the shipped constant,
     * which is right for the keyboard and useless for a sweep. The
     * reconstruction is pinned to the real thing by
     * `the sweep reconstructs what the engine actually does`, so the two cannot
     * drift into measuring different features.
     */
    private fun decide(
        e: SuggestionEngine,
        lang: String,
        locale: Locale,
        typed: String,
        follower: String,
        slack: Double
    ): String? {
        val cands = e.correctionCandidates(typed, lang, locale, limit = 4, personalized = false)
        if (cands.isEmpty()) return null
        val dict = e.cachedDictionary(lang) ?: return null
        val prox = KeyProximity.forLang(lang)
        val lower = typed.lowercase(locale)
        return PostCorrection.replacementFor(
            typed = typed,
            committed = typed,
            separator = " ",
            follower = follower,
            followerCorrected = false,
            candidates = cands,
            continues = { e.continues(it, follower, lang, locale, personalized = false) },
            confident = {
                dict.autoCommitConfident(lower, it.lowercase(locale), prox, false, slack)
            }
        )
    }

    private data class Rescue(val asked: Int, val right: Int, val wrong: Int)

    /**
     * Over the typos autocorrect declined, how many the follower rescues and
     * how many it turns into some third word.
     */
    private fun rescues(lang: String, slack: Double, want: Int = 2_000): Rescue {
        val e = engineOver(lang)
        val locale = Locale.forLanguageTag(lang)
        val prox = KeyProximity.forLang(lang)
        var asked = 0
        var right = 0
        var wrong = 0
        for ((slipIndex, slip) in Slip.values().withIndex()) {
            // Seeded per kind, so the corpus is identical on every run and
            // every machine, and the sweep below compares like with like.
            val rnd = Random(seed = 20260907 + slipIndex)
            for ((word, follower) in pairs(lang, want)) {
                val typo = damage(word, slip, prox, rnd) ?: continue
                // Damage that lands on another real word is not a typo this
                // engine should be scored on, and post-correction cannot see
                // it either -- a real word has no candidates.
                if (e.acceptedWord(typo, lang, locale)) continue
                // The population, and the line that makes this a measurement
                // of post-correction rather than of autocorrect: anything the
                // space bar already repairs is somebody else's win.
                if (e.correctionFor(typo, lang, locale, personalized = false) != null) continue
                asked++
                when (decide(e, lang, locale, typo, follower, slack)) {
                    null -> {}
                    word -> right++
                    else -> wrong++
                }
            }
        }
        return Rescue(asked, right, wrong)
    }

    private data class Damage(val asked: Int, val destroyed: Int, val examples: List<String>)

    /**
     * Over correctly-typed words the dictionary does not hold, how often
     * post-correction rewrites one.
     *
     * The population is [OutOfVocabularyTest]'s and is built the same way: cut
     * the dictionary at [KEEP], give the engine only the head of it, and offer
     * it the words that fell off the end. Those are ordinary vocabulary the
     * keyboard has simply never seen, which is the shape of word a user reaches
     * past the end of any shipped list — and, unlike generated damage, a
     * population where every rewrite is a loss.
     *
     * **The follower is drawn rather than found, and that is the honest
     * construction here.** A word below rank 60,000 has no n-gram of its own,
     * so there is no real follower to look up — but the trigger does not want
     * one. Post-correction fires when a *candidate* pairs with the follower,
     * and the candidates are common words, so what decides the rate is how
     * often an ordinary following word happens to bigram with a near-neighbour
     * of the word typed. Each held-out word is therefore tried against
     * [FOLLOWERS_PER_WORD] real followers taken from the shipped model, which
     * is the distribution a user's next word is actually drawn from.
     *
     * Using the prose fixtures instead was tried first and measures nothing:
     * every word in them is inside the top 60,000, so the truncated engine
     * accepts all of them and the arm reports zero cases examined.
     */
    private fun destruction(lang: String, slack: Double = PostCorrection.SLACK): Damage? {
        val all = File(assets(), "dictionaries/$lang.txt")
            .readLines().filter { it.isNotBlank() }
        if (all.size < KEEP + 5_000) return null
        val heldOut = all.drop(KEEP)
            .mapNotNull { it.split(' ').firstOrNull() }
            .filter { w -> w.length in 5..12 && w.all { it.isLetter() } }
        if (heldOut.size < 500) return null
        val step = maxOf(1, heldOut.size / WORDS)
        val words = heldOut.filterIndexed { i, _ -> i % step == 0 }.take(WORDS)
        val followers = pairs(lang, 400).map { it.second }.distinct()
        if (followers.size < FOLLOWERS_PER_WORD) return null
        val e = engineOver(lang, all.take(KEEP).joinToString("\n"))
        val locale = Locale.forLanguageTag(lang)
        var asked = 0
        var destroyed = 0
        val examples = ArrayList<String>()
        for ((i, w) in words.withIndex()) {
            for (k in 0 until FOLLOWERS_PER_WORD) {
                // Deterministic rather than random, and spread so that a word
                // and its neighbour in the list do not share a follower.
                val follower = followers[(i * FOLLOWERS_PER_WORD + k) % followers.size]
                if (follower == w) continue
                asked++
                val fix = decide(e, lang, locale, w, follower, slack)
                if (fix != null && !fix.equals(w, ignoreCase = true)) {
                    destroyed++
                    if (examples.size < 4) examples.add("$w $follower -> $fix")
                }
            }
        }
        return Damage(asked, destroyed, examples)
    }

    @Test
    fun `the sweep reconstructs what the engine actually does`() {
        // The sweep below reimplements `postCorrectionFor` so the slack can be
        // varied, and a reimplementation that has drifted measures a feature
        // the keyboard does not have. Nothing here asserts the answers are
        // *right* -- the arms below do that -- only that the two paths agree.
        val lang = "en"
        val locale = Locale.ENGLISH
        val e = engineOver(lang)
        val prox = KeyProximity.forLang(lang)
        val rnd = Random(seed = 20260907)
        var compared = 0
        for ((word, follower) in pairs(lang, 200)) {
            val typo = damage(word, Slip.NEIGHBOUR, prox, rnd) ?: continue
            if (e.acceptedWord(typo, lang, locale)) continue
            compared++
            assertEquals(
                "the engine and the sweep disagree about \"$typo $follower\"",
                e.postCorrectionFor(
                    typed = typo, committed = typo, separator = " ",
                    follower = follower, followerCorrected = false,
                    lang = lang, locale = locale, personalized = false
                ),
                decide(e, lang, locale, typo, follower, PostCorrection.SLACK)
            )
        }
        assertTrue("nothing was compared, so this guard is decoration", compared >= 50)
    }

    @Test
    fun `the slack is swept, not chosen`() {
        val report = StringBuilder()
        for (lang in listOf("en", "tr")) {
            for (slack in SWEEP) {
                val r = rescues(lang, slack)
                report.append(
                    "%s slack %.1f: rescued %d/%d (%.0f%%), mis-repaired %d (%.0f%%)\n".format(
                        lang, slack, r.right, r.asked, pct(r.right, r.asked),
                        r.wrong, pct(r.wrong, r.asked)
                    )
                )
            }
        }
        println(report)

        // The claim the shipped constant rests on: widening it past the value
        // in `PostCorrection` buys less than it costs. Asserted as a *shape*
        // rather than as figures, because figures move with the dictionary and
        // the n-grams and this should not have to be rewritten every time they
        // are regenerated.
        val at = rescues("en", PostCorrection.SLACK)
        val wide = rescues("en", PostCorrection.SLACK * 2)
        assertTrue(
            "the widened bar mis-repaired no more than the shipped one " +
                "(${wide.wrong} vs ${at.wrong}), so the constant is not doing " +
                "anything and either the sweep or the bound is wrong",
            wide.wrong > at.wrong
        )
    }

    @Test
    fun `it rescues typos the space bar gave up on`() {
        val lines = StringBuilder()
        var anyRescued = false
        for (lang in listOf("en", "tr")) {
            val r = rescues(lang, PostCorrection.SLACK)
            lines.append(
                "%s: %d typos autocorrect declined, %d rescued (%.0f%%), %d mis-repaired\n"
                    .format(lang, r.asked, r.right, pct(r.right, r.asked), r.wrong)
            )
            if (r.right > 0) anyRescued = true
            // A feature that repairs fewer words than it breaks is a net loss
            // however good the repairs are. This is the floor that matters and
            // it is the one to hold when the constant is next touched.
            assertTrue(
                "$lang: post-correction mis-repaired ${r.wrong} words while " +
                    "rescuing ${r.right}\n$lines",
                r.right > r.wrong
            )
        }
        println(lines)
        assertTrue("post-correction rescued nothing at all\n$lines", anyRescued)
    }

    @Test
    fun `it leaves correctly typed words it has never seen alone`() {
        val lines = StringBuilder()
        for (lang in listOf("en", "tr")) {
            val d = destruction(lang) ?: continue
            lines.append(
                "%s: %d correct out-of-vocabulary words, %d rewritten (%.1f%%) %s\n"
                    .format(lang, d.asked, d.destroyed, pct(d.destroyed, d.asked), d.examples)
            )
            assertTrue("$lang: nothing measured, so this arm is decoration", d.asked >= 500)
            // The number to defend. Autocorrect's own destruction on this
            // population is measured in whole percent by OutOfVocabularyTest;
            // post-correction is a strictly narrower gate on a strictly
            // smaller population and has no business approaching it.
            assertTrue(
                "$lang: post-correction rewrote ${d.destroyed} of ${d.asked} " +
                    "correctly-typed words\n$lines",
                pct(d.destroyed, d.asked) <= MAX_DESTRUCTION_PCT
            )
        }
        println(lines)
        assertTrue("neither language could be measured", lines.isNotEmpty())
    }

    private fun pct(n: Int, of: Int): Double = if (of == 0) 0.0 else n * 100.0 / of

    private companion object {
        /**
         * Where the dictionary is cut for the destruction arm. The same value
         * [OutOfVocabularyTest] uses, so the two numbers are comparable.
         */
        const val KEEP = 60_000

        /** How many held-out words the destruction arm draws. */
        const val WORDS = 500

        /** How many real followers each of them is tried against. */
        const val FOLLOWERS_PER_WORD = 6

        /**
         * Ceiling on rewriting correct text, in percent.
         *
         * Measured 2026-09-07: **en 0.6%, tr 0.1%** of 3,000 correctly-typed
         * out-of-vocabulary words, and two of the four English examples the arm
         * prints ("spanlsh as -> spanish") are repairs of genuinely misspelled
         * corpus entries counted against us, so the true figure is lower still.
         * `OutOfVocabularyTest` measures autocorrect's own destruction on this
         * same population in whole percent; post-correction is a narrower gate
         * and has no business approaching it.
         *
         * A tripwire with headroom, not a target. Set from what was measured,
         * and never to be raised to make a change pass — a change that moves
         * this is a change that rewrites more correct text.
         */
        const val MAX_DESTRUCTION_PCT = 1.5

        val SWEEP = listOf(1.0, 1.3, 1.6, 2.0, 3.0)
    }
}
