package com.rimboard.keyboard.engine

import com.rimboard.keyboard.model.KeyProximity
import com.rimboard.keyboard.spell.SpellJudge
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.random.Random

/**
 * How often the autocorrect is right, as a number.
 *
 * Everything else in this suite asks whether a rule behaves; this asks whether
 * the ranking is any good, which is a different question and the one that has
 * been answered by intuition until now. Three times in one week a change to
 * this ranking was reasoned about and reasoned about wrongly, and the fault a
 * user actually reported {EM} "naberr" correcting to "haber" {EM} had been
 * sitting in shipped code with a full suite passing over it.
 *
 * The corpus is generated rather than collected: the top words of the real
 * shipped dictionary, damaged in the four ways a thumb damages a word, with the
 * real key geometry deciding which slip is plausible. That is not a substitute
 * for what people actually type, and it is not pretending to be. What it is, is
 * repeatable, honest about its own construction, and sensitive to exactly the
 * kind of regression that has been getting through.
 *
 * The per-kind breakdown is the useful part. A single accuracy number says the
 * autocorrect is good or bad; the breakdown says *which* slip it handles badly,
 * which is what a fix needs to know.
 */
class AutocorrectAccuracyTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-accuracy", "").let { it.delete(); it.mkdirs(); it }
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
     * The engine, backed by the dictionary and the n-grams this app ships.
     *
     * The prediction asset was not served here until the context arms below
     * were written, and an engine that cannot open it does not fail: it ranks
     * without context and looks exactly like an engine whose context did
     * nothing. That is the blind instrument this file has already been caught
     * by once, so [measureContext] asserts the model actually loaded.
     */
    private fun realEngine(lang: String): SuggestionEngine {
        val files = listOf("dictionaries/$lang.txt", "predictions/$lang.txt")
            .associateWith { File(assets(), it).readText() }
        return SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
    }

    /**
     * Words worth testing on: common enough that somebody types them, long
     * enough that a correction is not a coin toss. The very top of the list is
     * skipped because two- and three-letter function words are genuinely
     * ambiguous under one edit and would measure nothing but that.
     */
    private fun sample(lang: String, count: Int): List<String> =
        File(assets(), "dictionaries/$lang.txt").useLines { lines ->
            lines.drop(80)
                .mapNotNull { it.split(' ').firstOrNull() }
                .filter { it.length in 5..9 && it.all { c -> c.isLetter() } }
                .take(count)
                .toList()
        }

    private enum class Slip { NEIGHBOUR, DOUBLED, DROPPED, SWAPPED, FIRST }

    /** One word, damaged one way, or null when this word cannot take that damage. */
    private fun damage(word: String, slip: Slip, prox: KeyProximity, rnd: Random): String? {
        val i = 1 + rnd.nextInt(word.length - 2)   // never the first or last letter
        return when (slip) {
            // The typo this engine is built around: a thumb landing next door.
            Slip.NEIGHBOUR -> prox.neighbours(word[i]).firstOrNull()
                ?.let { word.substring(0, i) + it + word.substring(i + 1) }
            // The one that produced the reported bug.
            Slip.DOUBLED -> word.substring(0, i) + word[i] + word.substring(i)
            Slip.DROPPED -> word.substring(0, i) + word.substring(i + 1)
            Slip.SWAPPED -> word.substring(0, i) + word[i + 1] + word[i] +
                word.substring(i + 2)
            // The first key is aimed at from rest and is the letter read back
            // first, so getting it wrong is both rarer and more jarring to
            // have rewritten. FIRST_LETTER_PENALTY exists for that, and until
            // this slip existed nothing here ever damaged the first letter,
            // so the penalty could not be measured at all — the corpus and
            // the constant were talking past each other. This is also the
            // shape of the fault a user reported: "naberr" for "naber", where
            // a commoner word one letter further away won on the first letter.
            Slip.FIRST -> prox.neighbours(word[0]).firstOrNull()
                ?.let { it + word.substring(1) }
        }?.takeIf { it != word }
    }

    /**
     * Accuracy over all the damage, and over the damage that was contested.
     *
     * The second number is the one worth watching. A typo with a single
     * plausible repair is answered correctly by any ranking at all, so a corpus
     * made of those measures whether the candidate *generator* works and says
     * nothing about the ranking sitting on top of it.
     *
     * That is not a guess about the instrument, it is how the blind spot was
     * found: sweeping the weight that balances key geometry against word
     * frequency across 2.5 to 5.0 moved every overall figure by exactly
     * nothing. A benchmark that cannot tell 2.5 from 5.0 is not measuring what
     * they control.
     *
     * Contested means the engine offered more than one repair — the case
     * the reported "naberr" fault lived in, and the only case a ranking exists
     * for.
     */
    private data class Score(val overall: Double, val contested: Double, val contestedN: Int)

    private fun measure(lang: String, locale: Locale, words: List<String>): Map<Slip, Score> {
        val engine = realEngine(lang)
        val prox = KeyProximity.forLang(lang)
        val out = LinkedHashMap<Slip, Score>()
        for (slip in Slip.values()) {
            // Seeded per kind, so the corpus is the same on every run and on
            // every machine. A benchmark that moves on its own measures noise.
            val rnd = Random(seed = 20260819 + slip.ordinal)
            var asked = 0
            var right = 0
            var contested = 0
            var contestedRight = 0
            for (w in words) {
                val typo = damage(w, slip, prox, rnd) ?: continue
                // Skip damage that lands on another real word: "hat" from
                // "hate" is not a typo the engine should be scored on.
                if (engine.acceptedWord(typo, lang, locale)) continue
                asked++
                val offered = engine.correctionCandidates(typo, lang, locale, limit = 3)
                val hit = offered.firstOrNull() == w
                if (hit) right++
                if (offered.size > 1) {
                    contested++
                    if (hit) contestedRight++
                }
            }
            out[slip] = Score(
                overall = if (asked == 0) 0.0 else right.toDouble() / asked,
                contested = if (contested == 0) 0.0 else contestedRight.toDouble() / contested,
                contestedN = contested
            )
        }
        return out
    }

    private fun report(lang: String, scores: Map<Slip, Score>): String =
        scores.entries.joinToString(", ") { (k, v) ->
            "$k ${"%.0f".format(v.overall * 100)}%/${"%.0f".format(v.contested * 100)}%"
        }.let { "$lang (all/contested): $it" }

    @Test
    fun `the autocorrect is right often enough, and says where it is not`() {
        val results = listOf(
            "en" to Locale.ENGLISH,
            "tr" to Locale.forLanguageTag("tr")
        ).map { (lang, locale) -> lang to measure(lang, locale, sample(lang, 70)) }

        val lines = results.joinToString("\n") { (lang, s) -> report(lang, s) }

        println(lines)

        // A floor, not a target, and set from what the engine measures
        // rather than from a wish. Measured the day the contested figures
        // were added, as all/contested:
        //
        //   en: neighbour 100/100, doubled 100/100, dropped 96/96,
        //       swapped 100/100, first 93/93
        //   tr: neighbour  96/96,  doubled  96/96,  dropped 88/86,
        //       swapped 100/100, first 95/95   (dropped contested was 86)
        //
        // The two columns turned out to sit almost on top of each other, which
        // says something worth keeping: nearly every generated typo is
        // contested, so the engine is being asked to choose, not merely to
        // find. That in turn means the flat result from sweeping the
        // geometry-against-frequency weight across 2.5 to 5.0 was not the
        // corpus being too easy. Within one edit the right answer is almost
        // always the cheapest answer as well, so scaling every cost by the
        // same factor reorders nothing. The weight matters in a narrow band --
        // where a commoner word is also a worse fit, which is exactly the
        // reported "naberr" case -- and is not worth agonising over outside it.
        //
        // The floor is on the contested figures, since those are the ones a
        // ranking change can move. Lowering it to make a change pass is the
        // one use this must never be put to.
        val worst = results.flatMap { r -> r.second.values.map { it.contested } }.min()
        assertTrue(
            "contested autocorrect accuracy has fallen below the floor.\n" + lines,
            worst >= 0.78
        )
        // Guards the guard twice over: a corpus that generated nothing would
        // score a perfect zero-of-zero, and a contested set that never fills
        // would make the floor above meaningless.
        assertTrue("the corpus generated nothing:\n" + lines,
            results.all { r -> r.second.values.all { it.overall > 0.0 } })
        assertTrue("nothing was contested, so nothing measured the ranking:\n" + lines,
            results.all { r -> r.second.values.all { it.contestedN >= 5 } })
    }

    // ---- Does the context data actually earn its megabyte? -----------------

    /**
     * The same question asked of the n-grams, which the test above cannot see.
     *
     * [measure] passes no `contextRank`. It therefore scores the channel model
     * alone and would print identical figures if every prediction row in the
     * app were deleted. That was a fair scope while there were 1,746
     * hand-written rows and no claim resting on them. It stopped being fair at
     * 89,442 rows and 0.9 MB of APK, whose entire justification is that they
     * make corrections better — a thing nothing here had ever checked.
     *
     * Three arms over one corpus, every word measured by all three:
     *
     *  - **blind** — no context, which is the whole of what [measure] scores.
     *  - **informed** — the rank map of the word that really does precede
     *    the target, from the same call [SpellJudge] makes and to its
     *    [SpellJudge.CONTEXT_DEPTH], with one preceding word rather than two.
     *  - **runner-up** — a context naming the candidate that came second, at
     *    rank 0, which is the strongest possible pull away from the answer.
     *
     * The informed arm is an upper bound and says so. Its pairs are read out
     * of the shipped model, so the right answer is in the rank map by
     * construction; it measures what context is worth when context is present
     * and correct, not what it is worth across prose in general. Read it as a
     * ceiling on the lift, never as the lift.
     *
     * The runner-up arm is the one that can fail, and the reason the other
     * two are here to compare it against. The rule this ranking is built on is
     * that evidence breaks ties and never overrules the channel model, so a
     * context pulling the other way should move a near-tie and nothing more.
     * The bonus is `CONTEXT_CORRECTION_WEIGHT / (rank + 1)`, so rank 0 is the
     * whole 2.0 of it, against a spatial term of 3.5 per key: an extra edit
     * must stay out of reach, while a frequency gap of about e^2 is meant to
     * be inside it.
     *
     * It replaced an earlier arm that used an unrelated real predecessor, and
     * the replacement is the point. That arm scored a perfect zero regressions
     * over 404 cases, which reads as a strong result and is nearly no evidence
     * at all: a rank map for some other word rarely happens to name a
     * candidate for *this* typo, so the bonus was mostly being applied to
     * nothing. An arm that cannot exert the pressure it is testing for will
     * pass whatever the constant is set to.
     */
    private data class ContextScore(
        val asked: Int, val blind: Int, val informed: Int, val runnerUp: Int,
        val rescued: Int, val broken: Int,
        val pulledOff: Int, val pulledOn: Int
    ) {
        private fun rate(n: Int) = if (asked == 0) 0.0 else n.toDouble() / asked
        val blindRate get() = rate(blind)
        val informedRate get() = rate(informed)
        val runnerUpRate get() = rate(runnerUp)
    }

    /**
     * Real (preceding word, following word) pairs out of the shipped n-grams.
     *
     * One pair per target word. A common word follows dozens of different
     * predecessors, and taking a pair from every row would weight the score by
     * how predictable a word is rather than by how well it gets corrected.
     */
    private fun contextPairs(lang: String, locale: Locale, count: Int): List<Pair<String, String>> =
        File(assets(), "predictions/$lang.txt").useLines { lines ->
            lines.mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) null
                else line.substring(0, tab) to line.substring(tab + 1).trim()
            }
                // The sentence-opener row is keyed U+0001, which is a real
                // context but not a preceding *word*, and a pair here is a
                // word and the word after it.
                .filter { (prev, _) -> prev.all { it.isLetter() } }
                .flatMap { (prev, nexts) ->
                    nexts.split(' ').asSequence()
                        .filter { w -> w.length in 5..9 && w.all { c -> c.isLetter() } }
                        .map { prev to it.lowercase(locale) }
                }
                .distinctBy { it.second }
                .take(count)
                .toList()
        }

    /**
     * Two slips rather than five, and more words instead.
     *
     * The context question is not slip-specific — a rank map re-orders
     * whatever candidates the channel produced, however the word was damaged
     * — so five kinds of damage to sixty words and two kinds to a hundred and
     * fifty cost the same three scans apiece while the second samples two and
     * a half times as much vocabulary. Vocabulary is the axis that matters
     * here: whether context helps depends on which words are near each other
     * in the dictionary, not on which finger slipped.
     *
     * These two because they leave the most room. Neighbour is the typo the
     * whole engine is built around, and dropped is the slip with the lowest
     * accuracy in [measure] (en 96, tr 88), so it is where a second signal has
     * something left to fix.
     */
    private val contextSlips = listOf(Slip.NEIGHBOUR, Slip.DROPPED)

    private fun measureContext(lang: String, locale: Locale, words: Int): ContextScore {
        val engine = realEngine(lang)
        val prox = KeyProximity.forLang(lang)

        // Loaded once, here, deliberately. Production never loads the model
        // from the judge — that would be an asset parse on a binder thread —
        // so every call below uses the production shape, mayLoad = false, and
        // reads what this line put in place. Asserting readiness guards the
        // failure this whole test is most vulnerable to: an engine with no
        // model answers every arm from an empty map, all three agree exactly,
        // and the result reads as a clean pass.
        engine.predictions("", "", lang, locale, 1, mayLoad = true)
        assertTrue(
            "no prediction model loaded for $lang, so all three arms are one arm",
            engine.predictionsReady(lang)
        )

        val pairs = contextPairs(lang, locale, words * 4)
            .filter { engine.acceptedWord(it.second, lang, locale) }
            .take(words)

        val rankCache = HashMap<String, Map<String, Int>>()
        fun ranks(prev: String): Map<String, Int> = rankCache.getOrPut(prev) {
            engine.predictions("", prev, lang, locale, SpellJudge.CONTEXT_DEPTH, mayLoad = false)
                .withIndex().associate { (i, w) -> w.lowercase(locale) to i }
        }

        fun offered(typo: String, ctx: Map<String, Int>) =
            engine.correctionCandidates(typo, lang, locale, limit = 3, contextRank = ctx)

        var asked = 0
        var blind = 0
        var informed = 0
        var runnerUp = 0
        var rescued = 0
        var broken = 0
        var pulledOff = 0
        var pulledOn = 0
        for (slip in contextSlips) {
            val rnd = Random(seed = 20260820 + slip.ordinal)
            for ((prev, target) in pairs) {
                val typo = damage(target, slip, prox, rnd) ?: continue
                if (engine.acceptedWord(typo, lang, locale)) continue
                asked++

                val pool = offered(typo, emptyMap())
                val b = pool.firstOrNull() == target
                val h = offered(typo, ranks(prev)).firstOrNull() == target
                // Nothing came second, so there is nothing to be pulled
                // toward and this word cannot say anything about the bonus.
                // Counted as unmoved rather than dropped, because dropping it
                // would score this arm over a different set of words than the
                // other two and the whole value here is the comparison.
                val r = pool.getOrNull(1)
                    ?.let { offered(typo, mapOf(it to 0)).firstOrNull() == target }
                    ?: b

                if (b) blind++
                if (h) informed++
                if (r) runnerUp++
                // Direction, not just movement. Three percentages a point
                // apart cannot be read at this sample size — "context moved
                // four answers" is only worth knowing alongside which way they
                // moved, and a rate that holds still can be two rescues
                // cancelling two regressions.
                if (!b && h) rescued++
                if (b && !h) broken++
                if (b && !r) pulledOff++
                if (!b && r) pulledOn++
            }
        }
        return ContextScore(
            asked, blind, informed, runnerUp, rescued, broken, pulledOff, pulledOn
        )
    }

    private fun contextReport(lang: String, s: ContextScore): String =
        ("%s context (n=%d): blind %.0f%%, informed %.0f%%, runner-up %.0f%%\n" +
            "    true context: rescued %d, broke %d" +
            "  |  runner-up context: pulled %d off the answer, %d onto it").format(
            lang, s.asked,
            s.blindRate * 100, s.informedRate * 100, s.runnerUpRate * 100,
            s.rescued, s.broken, s.pulledOff, s.pulledOn
        )

    @Test
    fun `the n-grams help when they are right and cost little when they are wrong`() {
        val results = listOf(
            "en" to Locale.ENGLISH,
            "tr" to Locale.forLanguageTag("tr")
        ).map { (lang, locale) -> lang to measureContext(lang, locale, 150) }

        val lines = results.joinToString("\n") { (lang, s) -> contextReport(lang, s) }
        println(lines)

        // Measured 2026-08-20, the day the arms were added, at the shipped
        // CONTEXT_CORRECTION_WEIGHT of 2.0:
        //
        //   en (n=218): blind 94%, informed 95%, runner-up 83%
        //               true context rescued 1, broke 0; runner-up pulled 33 off, 7 on
        //   tr (n=186): blind 85%, informed 89%, runner-up 79%
        //               true context rescued 7, broke 1; runner-up pulled 30 off, 18 on
        //
        // The honest summary of that, which is smaller than the size of the
        // data might suggest: **the n-grams measurably help Turkish and barely
        // touch English.** English is already right 94% of the time without
        // them, and the words an English function word predicts are common
        // ones the frequency term ranks first anyway, so context arrives with
        // nothing to add. Turkish starts nine points lower and gains four.
        // Fifty-one times the data did not buy fifty-one times anything; it
        // bought one language a few points. That is still worth its 0.9 MB,
        // and it is not what a reader would assume from the row count.
        //
        // Sweeping the weight, which this arm made possible for the first
        // time (en/tr, rescued-broke, then how much a runner-up context pulls
        // off the answer):
        //
        //   1.0   +0 / +5    12% / 12%    context does nothing at all in en
        //   2.0   +1 / +6    15% / 16%    shipped
        //   3.0   +3 / +5    24% / 27%    tr *net worse*, damage half again
        //   6.0   +6 / +9    65% / 56%    context has overruled the geometry
        //
        // Held at 2.0. Below it the signal is inert in English; above it
        // robustness falls away much faster than accuracy climbs, and 3.0
        // already makes Turkish net worse while costing 60% more damage.
        // The informed column is an upper bound, so tuning to maximise it is
        // exactly the overfitting this file warns about elsewhere: real prose
        // supplies a wrong or absent context far more often than a corpus
        // built out of the model's own rows ever will. 6.0 wins that column
        // outright and would be a plainly bad keyboard.
        val total = results.map { it.second }
        val rescued = total.sumOf { it.rescued }
        val broken = total.sumOf { it.broken }

        // Summed across languages on purpose. English contributes a single
        // rescue, so a per-language form of this assertion would be one word's
        // behaviour away from failing for no reason worth investigating.
        assertTrue(
            "the n-grams stopped paying for themselves: $rescued rescued, " +
                "$broken broken.\n" + lines,
            rescued > broken && rescued >= 4
        )

        results.forEach { (lang, s) ->
            // The arm has to be able to exert the pressure it tests for. An
            // earlier version of it could not, scored a perfect zero, and
            // would have passed at any weight whatsoever.
            assertTrue(
                "a rank-0 context moved almost nothing in $lang, so the " +
                    "ceiling below is vacuous.\n" + lines,
                s.pulledOff >= 8
            )
            // The ceiling, and the real guard here. Context is allowed to
            // settle a near-tie and is not allowed to overrule the channel
            // model, so there has to be a limit on how much of the answer a
            // deliberately wrong context can take away. 25% passes the shipped
            // 2.0 (15/16%) with room and trips before the constant reaches a
            // value where geometry stops deciding.
            assertTrue(
                "a wrong context now overturns ${s.pulledOff} of ${s.asked} " +
                    "answers in $lang, which is not tie-breaking any more.\n" + lines,
                s.pulledOff <= s.asked / 4
            )
        }
    }
}
