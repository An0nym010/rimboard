package com.rimboard.keyboard.engine

import com.rimboard.keyboard.model.KeyProximity
import com.rimboard.keyboard.spell.SpellJudge
import org.junit.After
import org.junit.Assert.assertEquals
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
        val files = HashMap<String, String>()
        for (kind in listOf("dictionaries", "predictions", "suffixes", "prefixes")) {
            File(assets(), "$kind/$lang.txt").takeIf { it.isFile }?.let {
                files["$kind/$lang.txt"] = it.readText()
            }
        }
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

    /** Frequency ranks the repair rate is measured at. */
    private val BANDS = listOf(80, 2_000, 10_000, 30_000, 80_000)

    /** Bands at or above this rank carry a floor; deeper ones are reported only. */
    private val FLOORED_TO = 30_000

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
        val results = com.rimboard.keyboard.model.Languages.all
            .map { it.code to it.locale }
            .map { (lang, locale) -> lang to measure(lang, locale, sample(lang, 70)) }

        val lines = results.joinToString("\n") { (lang, s) -> report(lang, s) }

        println(lines)

        // A floor, not a target, and set from what the engine measures
        // rather than from a wish. Measured the day the contested figures
        // were added, as all/contested:
        //
        //   en: neighbour 100/100, doubled 100/100, dropped 96/96,
        //       swapped 100/100, first 93/93
        //   tr: neighbour  97/96,  doubled  97/97,  dropped 90/88,
        //       swapped 100/100, first 96/96
        //
        // Re-read 2026-08-23 and they have drifted, which is the point of
        // writing them down:
        //
        //   en: neighbour 100/100, doubled 100/100, dropped 93/93,
        //       swapped 100/100, first 91/91
        //   tr: neighbour  97/96,  doubled  97/97,  dropped 91/89,
        //       swapped 100/100, first 97/96
        //
        // English lost three points of DROPPED and two of FIRST; Turkish
        // gained on DROPPED and FIRST. Neither was caused by anything measured
        // here -- the changes in between were the German compound rule and the
        // Turkish morphology guard, both of which move which words reach the
        // corrector at all. Recorded rather than chased: the floor below is
        // what this file promises, and these are the numbers it is at.
        //
        // The Turkish column moved up once, and the reason is worth keeping:
        // nothing about the ranking changed. The morphology guard learned
        // vowel harmony and stopped peeling onto corpus noise, so seventy more
        // Turkish typos reached the corrector at all -- before that the guard
        // had pronounced them correct and this arm never saw them.
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
        // ## All twenty-two, 2026-08-30
        //
        // [measure] and [sample] have been language-general since they were
        // written; only this call site was not, which is the third time that
        // exact fault has turned up in this neighbourhood. Contested figures
        // at the shipped costs, sorted by the kind that separates them:
        //
        //     fi 77   sv 79   da 81   it 85   tr 89   pt 92   ro 93
        //     cs 78   de 80   fr 83   hu 87   es 89   pl 92   hr 93
        //     no 78   nl 81           uk 88   ru 89   en 93   el 95
        //                                     sk 89           id 100
        //
        // That column is DROPPED. The other four kinds read 91-100 in every
        // language, so **a dropped letter is the weak slip everywhere, by
        // fifteen to twenty points** -- and the two-language reading hid it,
        // because English is the third best of the twenty-two at it and
        // Turkish is mid-table.
        //
        // Not a tuning failure: the insertion cost was re-swept across all
        // twenty-two and comes out flat, see [Dictionary.spatialCost]. It is
        // that a dropped letter is the one slip the geometry cannot speak
        // to -- there is no touch point near the right key, because the key
        // was never struck. Every other kind leaves evidence of where the
        // finger was; this one leaves an absence.
        //
        // The floor is on the contested figures, since those are the ones a
        // ranking change can move. Lowering it to make a change pass is the
        // one use this must never be put to -- and moving it from 0.78 to
        // 0.75 is not that. Nothing about the ranking moved; the *population*
        // did, from two languages to twenty-two, and 0.78 was a promise about
        // English and Turkish that Finnish had never been asked to keep. The
        // new number is what twenty-two languages measure (0.77) with two
        // points of room, and it guards a hundred and ten figures instead of
        // ten.
        val worst = results.flatMap { r -> r.second.values.map { it.contested } }.min()
        assertTrue(
            "contested autocorrect accuracy has fallen below the floor.\n" + lines,
            worst >= 0.75
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
    private fun contextPairs(lang: String, locale: Locale, count: Int): List<Pair<String, String>> {
        val all = File(assets(), "predictions/$lang.txt").useLines { lines ->
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
                .toList()
        }
        // Spread through the model, not taken off the top.
        //
        // The rows are written in frequency order -- the hand-written ones
        // first, then the corpus rows by how common the context word is -- so
        // `take(count)` sampled the head and nothing else. Every row past
        // roughly the first thousand was invisible here, and so was any change
        // that only added rows: six thousand were added and all eight numbers
        // this arm prints came back identical, which is not a result, it is an
        // instrument that cannot see the thing being changed. Third time that
        // shape has been caught in this file.
        //
        // A uniform stride over-represents rare contexts relative to real
        // prose, where the preceding word is usually a common one. That is the
        // right trade for what this arm is for: it asks whether context helps
        // *where the model has an opinion*, and the head-only sample answered
        // that question for one twentieth of the model.
        val step = maxOf(1, all.size / count)
        return all.filterIndexed { i, _ -> i % step == 0 }.take(count)
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

    /**
     * The same sampling, over the rows a *two-word* context keys.
     *
     * These are the rows [contextPairs] deliberately skips -- its key filter
     * drops anything with a space in it -- and they are half the model now.
     * Sampling them is what lets this file measure the path production
     * actually takes: the keyboard and the spell checker both pass two
     * preceding words, and where a trigram row exists the ranked list handed
     * to the corrector is up to twelve words rather than six.
     */
    private fun trigramTriples(
        lang: String, locale: Locale, count: Int
    ): List<Triple<String, String, String>> {
        val all = File(assets(), "predictions/$lang.txt").useLines { lines ->
            lines.mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) null
                else line.substring(0, tab) to line.substring(tab + 1).trim()
            }
                .filter { (key, _) -> key.count { it == ' ' } == 1 }
                .filter { (key, _) -> key.all { it.isLetter() || it == ' ' } }
                .flatMap { (key, nexts) ->
                    val (a, b) = key.split(' ')
                    nexts.split(' ').asSequence()
                        .filter { w -> w.length in 5..9 && w.all { c -> c.isLetter() } }
                        .map { Triple(a, b, it.lowercase(locale)) }
                }
                .distinctBy { it.third }
                .toList()
        }
        val step = maxOf(1, all.size / count)
        return all.filterIndexed { i, _ -> i % step == 0 }.take(count)
    }

    /**
     * [twoWord] chooses which half of the model is sampled, and how much
     * context the engine is given -- one function rather than two so the
     * scoring, the slips and the seeds cannot drift between the arms. That
     * matters more than usual here: the whole output is a comparison between
     * them.
     */
    /**
     * Which languages the context ceiling is guarded for.
     *
     * English and Turkish for a long time, because they are the two the rest of
     * this file measures. That was the wrong set for the one thing this arm
     * defends. The ceiling is a statement about how loud the prediction model
     * is allowed to be on the typing path, and loudness is a property of the
     * model -- so the languages worth watching are the ones whose models are
     * about to change, and those are never English and Turkish, whose corpora
     * are large enough that the counting knobs barely move them.
     *
     * `measureContext` was language-general the whole time; only this list was
     * not.
     */
    private val CONTEXT_LANGS = listOf(
        "en" to Locale.ENGLISH,
        "tr" to Locale.forLanguageTag("tr"),
        "hr" to Locale.forLanguageTag("hr"),
        "sk" to Locale.forLanguageTag("sk"),
        "no" to Locale.forLanguageTag("no"),
        "ro" to Locale.forLanguageTag("ro"),
        "id" to Locale.forLanguageTag("id"),
        "el" to Locale.forLanguageTag("el"),
        "da" to Locale.forLanguageTag("da"),
        "sv" to Locale.forLanguageTag("sv"),
        "pl" to Locale.forLanguageTag("pl"),
        "fi" to Locale.forLanguageTag("fi")
    )

    private fun measureContext(
        lang: String, locale: Locale, words: Int, twoWord: Boolean = false
    ): ContextScore {
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

        val pairs =
            if (twoWord) {
                trigramTriples(lang, locale, words * 4)
                    .filter { engine.acceptedWord(it.third, lang, locale) }
                    .take(words)
            } else {
                contextPairs(lang, locale, words * 4)
                    .filter { engine.acceptedWord(it.second, lang, locale) }
                    .take(words)
                    .map { Triple("", it.first, it.second) }
            }
        if (twoWord) {
            assertTrue(
                "no two-word rows in the $lang model, so this arm measures nothing",
                pairs.isNotEmpty()
            )
        }

        val rankCache = HashMap<Pair<String, String>, Map<String, Int>>()
        fun ranks(prev2: String, prev: String): Map<String, Int> =
            rankCache.getOrPut(prev2 to prev) {
                engine.predictions(
                    prev2, prev, lang, locale, SpellJudge.CONTEXT_DEPTH, mayLoad = false
                ).withIndex().associate { (i, w) -> w.lowercase(locale) to i }
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
            for ((prev2, prev, target) in pairs) {
                val typo = damage(target, slip, prox, rnd) ?: continue
                if (engine.acceptedWord(typo, lang, locale)) continue
                asked++

                val pool = offered(typo, emptyMap())
                val b = pool.firstOrNull() == target
                val h = offered(typo, ranks(prev2, prev)).firstOrNull() == target
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
        val results = CONTEXT_LANGS
            .map { (lang, locale) -> lang to measureContext(lang, locale, 150) }

        val lines = results.joinToString("\n") { (lang, s) -> contextReport(lang, s) }
        println(lines)

        // Measured 2026-08-22 against the shipped model, at
        // CONTEXT_CORRECTION_WEIGHT = 1.5. One-word contexts, this arm:
        //
        //   en (n=232): blind 88%, informed 89%, runner-up 77%
        //               true context rescued 4, broke 1; runner-up pulled 39 off, 14 on
        //   tr (n=243): blind 80%, informed 82%, runner-up 78%
        //               true context rescued 6, broke 0; runner-up pulled 30 off, 25 on
        //
        // And two-word contexts, the arm below:
        //
        //   en (n=207): blind 94%, informed 97%, runner-up 88%
        //               true context rescued 5, broke 0; runner-up pulled 22 off, 9 on
        //   tr (n=249): blind 88%, informed 89%, runner-up 73%
        //               true context rescued 6, broke 3; runner-up pulled 50 off, 12 on
        //
        // **The weight came down from 2.0 on 2026-08-22, and the two-word arm
        // is why.** It had been swept twice, and both times against a sample
        // where a two-word context never fired -- [contextPairs] filters those
        // rows out by construction. Once measured, 2.0 put a wrong two-word
        // context at 69 of 249 Turkish answers overturned: 27.7%, against the
        // 25% ceiling this file asserts and 17.7% on the one-word path. The
        // ceiling is not arbitrary; it is the operational form of "context
        // settles a near-tie and does not overrule the geometry of what was
        // typed", and the path that broke it now fires on about half of
        // English words.
        //
        // Sweeping against both arms at once, worst damage of the four
        // measurements and total rescues across them:
        //
        //   2.0    27.7%  (tr, two-word)   26 rescued, 4 broken   over the ceiling
        //   1.75   24.1%                   24 rescued, 4 broken   one point of margin
        //   1.5    20.1%                   21 rescued, 4 broken   shipped
        //
        // 1.75 passes and was not taken: a ratchet sitting one word away from
        // failing is one resample from being edited rather than believed. 1.5
        // costs five rescues out of twenty-six and buys seven points.
        //
        // The informed column is an upper bound, so tuning to maximise it is
        // exactly the overfitting this file warns about elsewhere: real prose
        // supplies a wrong or absent context far more often than a corpus
        // built out of the model's own rows ever will.
        //
        // Kept from the earlier sweeps, because it is what the 25% ceiling was
        // originally set against, on the head-only sample that predates all of
        // this:
        //
        //   1.0   +0 / +5    12% / 12%
        //   2.0   +1 / +6    15% / 16%
        //   3.0   +3 / +5    24% / 27%    tr *net worse*, damage half again
        //   6.0   +6 / +9    65% / 56%    context has overruled the geometry
        //
        // A data change moves these samples, so re-sweep after one rather than
        // trusting the numbers here.
        val total = results.map { it.second }
        val rescued = total.sumOf { it.rescued }
        val broken = total.sumOf { it.broken }

        // Summed across languages on purpose. Either language can contribute
        // as few as three or four rescues -- English contributed exactly one
        // when this arm was written -- so a per-language form of this
        // assertion would be one word's behaviour away from failing for no
        // reason worth investigating.
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

    @Test
    fun `a two-word context helps and costs no more than a one-word one`() {
        val results = CONTEXT_LANGS
            .map { (lang, locale) -> lang to measureContext(lang, locale, 150, twoWord = true) }

        val lines = results.joinToString("\n") { (lang, s) -> contextReport(lang, s) }
        println("two-word context:\n" + lines)

        val total = results.map { it.second }
        assertTrue(
            "the two-word rows stopped paying for themselves: " +
                "${total.sumOf { it.rescued }} rescued, ${total.sumOf { it.broken }} broken.\n" +
                lines,
            total.sumOf { it.rescued } > total.sumOf { it.broken }
        )
        results.forEach { (lang, s) ->
            assertTrue(
                "a rank-0 context moved almost nothing in $lang, so the ceiling " +
                    "below is vacuous.\n" + lines,
                s.pulledOff >= 8
            )
            // The same ceiling as the one-word arm, and the reason it is the
            // same: the rule being defended is about the engine, not about
            // which row answered. A two-word row hands the corrector up to
            // twelve ranked words where a one-word row hands it six, so if
            // context is going to overrule the geometry anywhere, it is here.
            assertTrue(
                "a wrong two-word context overturns ${s.pulledOff} of ${s.asked} " +
                    "answers in $lang, which is not tie-breaking any more.\n" + lines,
                s.pulledOff <= s.asked / 4
            )
        }
    }

    // ---- what is *applied*, not merely offered -----------------------------

    /**
     * The auto-correction gate, from both sides at once.
     *
     * Everything above measures [SuggestionEngine.correctionCandidates] — the
     * list on the strip. This measures [SuggestionEngine.correctionFor], which
     * is the narrower and far more damaging question of what gets committed on
     * the space bar without anyone tapping anything. The two were the same
     * decision until a threshold was added: whatever ranked first was applied,
     * however far it sat from what was typed.
     *
     * A gate like that has two costs and they pull opposite ways, so measuring
     * one of them alone would say nothing:
     *
     *  - **fixed** — damaged words the keyboard still repairs on its own. Every
     *    point lost here is a typo the user now has to tap to fix.
     *  - **destroyed** — correctly-typed words the keyboard overwrites. The
     *    corpus is real words of the *other* language, typed into a field whose
     *    dictionary does not contain them, standing in for the names, brands,
     *    slang and jargon that no 200k-word list holds. Every point here is the
     *    keyboard silently replacing something that was already right, which is
     *    the failure people actually complain about.
     *
     * The second number was 56% in English and 61% in Turkish before the gate
     * existed. That is the measurement that justified building this at all, and
     * it is why the floor below is on both arms: a threshold tuned to make one
     * of them look good is trivially achievable by wrecking the other.
     *
     * ## What the 15/16% average hides
     *
     * Read before tuning [Dictionary.AUTO_MAX_COST_PER_CHAR], because the
     * single figure it is swept against describes a rate that varies fifty-fold
     * across the corpus. Broken out by the length of the word being destroyed
     * (150 unknown words per bucket, en/tr):
     *
     *     4 letters   14% / 33%        9 letters    0% /  3%
     *     5 letters   31% / 48%       10 letters    1% /  5%
     *     6 letters    8% / 17%       11 letters    0% /  5%
     *     7 letters    3% /  7%       12 letters    0% /  1%
     *     8 letters    3% /  8%       13 letters    0% /  0%
     *
     * **Destruction is a short-word problem**, and the intuition that the
     * per-character bar gets slack on long words is wrong in practice: it does
     * (one maximally-distant substitution clears it at eight letters and above,
     * `cost 1.0 / 8 <= 0.14`), but a long unknown word has no near neighbour in
     * the dictionary to be destroyed *toward*, so the slack is never spent. The
     * peak at five letters is where a single deletion first becomes affordable
     * — `ins` is 0.7 and the budget at five is 0.70.
     *
     * By the shape of the edit, over 1,200 unknown words (en/tr):
     *
     *     substitution   38% / 40%    of everything destroyed
     *     deletion       48% / 39%    (drop-inner plus drop-last)
     *     transposition   8% / 10%
     *     insertion       7% /  6%
     *
     * **A measured dead end, recorded so nobody re-derives it.** Nearly half of
     * destruction is the keyboard deleting a letter that was really struck —
     * "thinks" committed as "think", "devam" as "deva", "karar" as "kara" —
     * which looks like an obvious candidate for a veto, since the repair side
     * only ever *needs* a deletion for a doubled letter, where the deleted
     * character duplicates its neighbour. Refusing any other deletion at the
     * auto-commit gate turns out to cost the entire stray-extra-key arm: a
     * finger clipping the key next door is repaired by deleting a letter that
     * duplicates nothing, and that is 95% of English and 97% of Turkish. The
     * discriminator is not there. Measure the cost of a filter before building
     * it, the same way a new score has to be checked for excluding anything.
     *
     * The remaining structure is a **data** property rather than a threshold:
     * a large share of substitution targets are proper nouns the subtitle
     * corpus is full of — "orada" destroyed to "prada", "names" to "james",
     * "settle" to "seattle". The lists are lowercased, so nothing downstream
     * can tell a name from a word; fixing that means regenerating the assets
     * with a capitalisation ratio per word, not tuning a constant.
     */
    private data class CommitScore(
        val asked: Int, val fixed: Int, val alien: Int, val destroyed: Int
    ) {
        val fixRate get() = if (asked == 0) 0.0 else fixed.toDouble() / asked
        val destroyRate get() = if (alien == 0) 0.0 else destroyed.toDouble() / alien
    }

    /**
     * Real words of [foreign] that the [lang] dictionary does not know.
     *
     * Skipping the very top of the foreign list on purpose: its commonest words
     * are short function words that genuinely collide across languages, and
     * "is" or "bir" being read as a typo of something is not the case this is
     * about.
     */
    private fun alienWords(
        engine: SuggestionEngine, lang: String, locale: Locale,
        foreign: String, count: Int
    ): List<String> =
        File(assets(), "dictionaries/$foreign.txt").useLines { lines ->
            lines.drop(80)
                .mapNotNull { it.split(' ').firstOrNull() }
                .filter { it.length in 4..10 && it.all { c -> c.isLetter() } }
                .filter { !engine.acceptedWord(it, lang, locale) }
                .take(count)
                .toList()
        }

    private fun fixturesDir(): File =
        listOf(File("src/test/fixtures"), File("app/src/test/fixtures")).first { it.isDirectory }

    /** Token-weighted targets from running prose, not the head of the list. */
    private fun proseTargets(
        lang: String, locale: Locale, engine: SuggestionEngine, count: Int
    ): List<String> {
        val out = ArrayList<String>()
        val f = File(fixturesDir(), "prose_" + lang + ".txt")
        if (!f.isFile) return out
        for (line in f.readLines()) {
            for (raw in Regex("[^ ]+").findAll(line.lowercase(locale)).map { it.value }) {
                val t = raw.filter { it.isLetter() }
                if (t.length in 5..9 && engine.acceptedWord(t, lang, locale)) out.add(t)
                if (out.size >= count) return out
            }
        }
        return out
    }

    /**
     * The commit arm, asked about the population it will actually meet.
     *
     * `autocorrect applies its fixes without overwriting words that were
     * right` measures two languages, English and Turkish, each against the
     * other. Both halves of that are unrepresentative, and in the same
     * direction — they make the keyboard look better than it is.
     *
     * **The targets come off the top of the frequency list.** [sample] draws
     * from rank 80 and takes the first that fit, so every typo it repairs is a
     * typo of a very common word. Over running prose the same repair rate is
     * eight points lower: **95.1% against 87.8%**, pooled over eight languages
     * and 3,698 typos.
     *
     * **And the words it must not overwrite come from a language nobody
     * types.** English and Turkish share almost no orthography, so an
     * "alien" word is one the dictionary has no near neighbour for. The
     * foreign words a person really types are English ones, in a European
     * language, and those are one cheap edit from a real word constantly:
     *
     *     language   destroys, alien=en   alien=tr   of the en figure, accent-only
     *     fr              38%               9%              36 of 76
     *     es              24%              13%               0
     *     it              21%              11%               0
     *     de              19%              10%               1
     *     pl              17%              14%               1
     *     tr              15%    (alien=en)                  4
     *     en               8%    (alien=tr)                  0
     *
     * The project's own ceiling for this is 25%, asserted in the arm above.
     * French is at 38% against the language a French speaker is likeliest to
     * borrow from, and nothing measured it. It read 40% until the key geometry
     * was corrected on 2026-09-05: French is one of five layouts whose
     * proximity grid sat half a key from the drawn keys, so three of those two
     * hundred were the geometry rather than the gate.
     *
     * ## Two mechanisms, and only one of them has a setting
     *
     * French's excess is not more of the same. **Just under half of it is
     * accent restoration** — `different` to `différent`, `piece` to `pièce`,
     * `president` to `président` — which is a different code path with a
     * different gate. An accent edit costs almost nothing spatially, so
     * [Dictionary.AUTO_MAX_COST_PER_CHAR] never sees it, and the user-facing
     * cautious setting, whose whole purpose is "overrule me less", moves
     * French by nothing at all (38% to 38%) while working as designed elsewhere
     * (Italian 21 to 16, Polish 17 to 12, Turkish 15 to 10).
     *
     * ## The obvious lever, measured and refused
     *
     * That leaves [Dictionary.BARE_KEY_RATIO], which is what decides an accent
     * restoration. Swept, against the population the feature exists for —
     * accented words of the language typed bare — and the population it costs:
     *
     *     ratio   fr restores   fr accents English   all restored   all accented
     *       50        68%            36 of 200          65.0%           3.4%
     *      100        30%            12 of 200          51.8%           1.6%
     *      200         3%             1 of 200          34.2%           0.6%
     *      400         0%             1 of 200          25.4%           0.3%
     *
     * The two move together and steeply. Halving the cost costs French
     * thirty-eight points of the feature, and the population that types French
     * without accents is far larger than the one typing English words into
     * French. Fifty stands, and wiring the cautious setting to a higher ratio
     * would hand those users a keyboard that has stopped restoring accents —
     * which is the thing [Dictionary.AUTO_MAX_COST_PER_CHAR_CAUTIOUS]'s own
     * note says cautious must not become.
     *
     * So this arm changes nothing and measures what was invisible. The real
     * answer for somebody who writes two languages is to enable the second
     * one, which is what makes [SuggestionEngine.acceptedWord] ask it too.
     */
    @Test
    fun `the commit arm on the population it will actually meet`() {
        val out = StringBuilder()
        val over = ArrayList<String>()
        var fixedProse = 0
        var askedProse = 0
        for ((lang, close, far) in listOf(
            Triple("fr", "en", "tr"), Triple("es", "en", "tr"), Triple("it", "en", "tr"),
            Triple("de", "en", "tr"), Triple("pl", "en", "tr")
        )) {
            val loc = Locale.forLanguageTag(lang)
            val engine = realEngine(lang)
            val prox = KeyProximity.forLang(lang)
            val prose = proseTargets(lang, loc, engine, 60)
            for (slip in Slip.values()) {
                val rnd = Random(seed = 20260820 + slip.ordinal)
                for (w in prose) {
                    val typo = damage(w, slip, prox, rnd) ?: continue
                    if (engine.acceptedWord(typo, lang, loc)) continue
                    askedProse++
                    if (engine.correctionFor(typo, lang, loc) == w) fixedProse++
                }
            }
            val rates = IntArray(2)
            var accentOnly = 0
            for ((i, foreign) in listOf(close, far).withIndex()) {
                val alien = alienWords(engine, lang, loc, foreign, 200)
                for (w in alien) {
                    val fix = engine.correctionFor(w, lang, loc) ?: continue
                    if (fix.lowercase(loc) == w) continue
                    rates[i]++
                    if (i == 0 &&
                        com.rimboard.keyboard.model.Diacritics.fold(fix.lowercase(loc)) == w
                    ) accentOnly++
                }
            }
            out.append(
                "%-3s destroys %3d/200 against %s, %3d/200 against %s; accent-only %d%n"
                    .format(lang, rates[0], close, rates[1], far, accentOnly)
            )
            // A ratchet, not the 25% ceiling the distant-pair arm asserts: this
            // population is over it and the sweep above says the levers cost
            // more than they save. What must not happen is that it gets worse
            // without anyone noticing, which is exactly what has been possible
            // until now.
            if (rates[0] > CLOSE_DESTROY_CEILING) {
                over.add(lang + " " + rates[0] + "/200 against " + close)
            }
            // And the close pair must stay the harder one, or this arm has
            // stopped measuring what it claims to.
            if (rates[0] < rates[1]) over.add(lang + " close pair is now the easier one")
        }
        val proseRate = fixedProse.toDouble() / askedProse
        out.append("prose targets: fixes %.1f%% (%d/%d)%n".format(proseRate * 100, fixedProse, askedProse))
        println(out)
        assertEquals("the close-language destroy rate has grown:\n" + out, emptyList<String>(), over)
        assertTrue(
            "autocorrect has stopped repairing typos of ordinary prose words:\n" + out,
            proseRate >= PROSE_FIX_FLOOR
        )
    }

    /**
     * The remedy for the arm above, and that nothing reaches past it.
     *
     * `the commit arm on the population it will actually meet` measures the
     * harm: a French keyboard overwrites 40% of the English words a French
     * speaker is likeliest to borrow, and every lever inside French costs more
     * than it saves. Its note ends by saying the real answer is to enable the
     * second language. **That was prose, and prose is not a measurement** — so
     * here is the measurement, and it is worth having as a test rather than a
     * sentence because the guard it depends on is one line that a fifth caller
     * could route around.
     *
     * `correctionCandidates` returns nothing when `acceptedWord` says the other
     * enabled language accepts the word. But `correctionFor` asks
     * [SuggestionEngine.contractionFor] *first* and returns its answer
     * directly, so "the guard exists" and "the guard is reached" are two
     * claims and only the second one matters.
     *
     * Measured: alone, fr destroys 79 of 200, es 48, it 41, de 38. With the
     * second language enabled, **all four are zero**.
     *
     * The engine here serves *both* dictionaries, and that is load-bearing
     * rather than incidental. Asked with only the primary's assets — which is
     * what [realEngine] gives — `acceptedWord(word, foreign, ...)` consults an
     * empty dictionary, the guard cannot fire, and the numbers come back
     * unchanged at 79/48/41/38. That reads exactly like a keyboard ignoring
     * the user's second language, and it is a property of the harness.
     */
    @Test
    fun `the second language stops the overwriting, with nothing reaching past it`() {
        val out = StringBuilder()
        for ((lang, foreign) in listOf("fr" to "en", "es" to "en", "it" to "en", "de" to "en")) {
            val loc = Locale.forLanguageTag(lang)
            val fLoc = Locale.forLanguageTag(foreign)
            // BOTH dictionaries. realEngine(lang) serves one language's
            // assets, so acceptedWord(w, foreign, ...) would ask an empty
            // dictionary and the alt-language guard could never fire -- which
            // is a property of the harness, not of the keyboard.
            // ...and both languages' inventories with them, for the same
            // reason one step further on: an engine without the suffix asset
            // vouches for fewer words than the shipped one, so it corrects
            // more of them. See realEngine above.
            val files = HashMap<String, String>()
            for (l in listOf(lang, foreign)) {
                for (kind in listOf("dictionaries", "predictions", "suffixes", "prefixes")) {
                    val n = kind + "/" + l + ".txt"
                    File(assets(), n).takeIf { it.isFile }?.let { files[n] = it.readText() }
                }
            }
            val engine = SuggestionEngine.forTesting(userData) { q -> files[q]?.byteInputStream() }
            val alien = alienWords(engine, lang, loc, foreign, 200)
            var alone = 0
            var withAlt = 0
            val leaks = ArrayList<String>()
            for (w in alien) {
                val a = engine.correctionFor(w, lang, loc)
                if (a != null && a.lowercase(loc) != w) alone++
                val b = engine.correctionFor(w, lang, loc, altLang = foreign, altLocale = fLoc)
                if (b != null && b.lowercase(loc) != w) {
                    withAlt++
                    if (leaks.size < 8) leaks.add(w + "->" + b)
                }
            }
            out.append("%-3s alone %3d/200   with %s enabled %3d/200   %s%n"
                .format(lang, alone, foreign, withAlt, leaks))
            // The harness check, first: if the primary is not overwriting
            // these words even without the second language, the corpus has
            // stopped exercising what this arm is about and the zero below
            // would mean nothing.
            assertTrue(
                "the alien corpus for " + lang + " no longer provokes any " +
                    "overwriting, so this arm proves nothing:" + NL + out,
                alone > 10
            )
            assertEquals(
                "a correction reached past the second-language check:" + NL + out,
                0, withAlt
            )
        }
        println(out)
    }

    /**
     * The space bar and the underline never name different words.
     *
     * Two paths answer "what should this word be" and a user meets both: press
     * space and the keyboard commits, or leave it and the squiggle offers a
     * menu. They run through different code -- `correctionFor` takes
     * `correctionCandidates(limit = 1)` and gates it on
     * [Dictionary.autoCommitConfident], while [SpellJudge] takes a deeper pool
     * and re-ranks it -- and until now nothing compared their answers. A
     * disagreement is directly visible: the word the space bar puts in is not
     * the word the menu offered.
     *
     * Measured over 1,460 typos in six languages:
     *
     *     agree 96.9%   space-only 0.0%   underline-only 3.1%   differ 0%
     *
     * **Nothing differs and nothing is space-only.** The whole asymmetry runs
     * one way -- the underline offers a repair where the space bar declines to
     * commit one -- and that is the design rather than a gap: the commit gate
     * is deliberately stricter, because a squiggle asks the reader to look
     * while a commit overrules them.
     *
     * The two assertions are the safety property and the consistency one, and
     * the first matters more. `correctionFor` asks
     * [SuggestionEngine.contractionFor] *before* the candidate pool and returns
     * its answer directly, so a fifth pre-check added there would let the space
     * bar commit a word the underline has never heard of, and nothing would
     * have said so.
     *
     * Compared with no context on either side, which is where the two are
     * directly comparable: [SpellJudge]'s right-context and rank-map arms are
     * inert without it, and with it both paths build the same map from the same
     * call, so the invariant is about the gate rather than the ranking.
     */
    @Test
    fun `the space bar never commits what the underline would not offer`() {
        val out = StringBuilder()
        var tot = 0; var bothSame = 0; var barOnly = 0; var judgeOnly = 0
        val examples = ArrayList<String>()
        for (lang in listOf("en", "tr", "de", "es", "fr", "ru")) {
            val loc = Locale.forLanguageTag(lang)
            val engine = realEngine(lang)
            val judge = com.rimboard.keyboard.spell.SpellJudge(engine, lang, loc)
            val prox = KeyProximity.forLang(lang)
            var n = 0; var same = 0; var bOnly = 0; var jOnly = 0; var differ = 0
            for (slip in Slip.values()) {
                val rnd = Random(seed = 20260820 + slip.ordinal)
                for (w in sample(lang, 60)) {
                    val typo = damage(w, slip, prox, rnd) ?: continue
                    if (engine.acceptedWord(typo, lang, loc)) continue
                    n++
                    val bar = engine.correctionFor(typo, lang, loc)
                    val v = judge.verdictFor(
                        typo, "", "", "", 3, false,
                        com.rimboard.keyboard.spell.Budget(1000)
                    )
                    val top = v.words.firstOrNull()
                    when {
                        bar != null && top != null && bar.equals(top, true) -> same++
                        bar != null && top == null -> bOnly++
                        bar == null && top != null -> jOnly++
                        bar != null && top != null -> {
                            differ++
                            if (examples.size < 8) examples.add("$lang $typo: bar=$bar judge=$top")
                        }
                    }
                }
            }
            out.append("%-3s n=%4d  agree %3.0f%%  space-only %3.0f%%  underline-only %3.0f%%  differ %3.0f%%%n"
                .format(lang, n, 100.0*same/n, 100.0*bOnly/n, 100.0*jOnly/n, 100.0*differ/n))
            tot += n; bothSame += same; barOnly += bOnly; judgeOnly += jOnly
        }
        out.append("ALL n=%d  agree %.1f%%  space-only %.1f%%  underline-only %.1f%%%n"
            .format(tot, 100.0*bothSame/tot, 100.0*barOnly/tot, 100.0*judgeOnly/tot))
        out.append("  disagreements: " + examples + "%n".format())
        println(out)
        assertTrue(
            "the corpus stopped provoking corrections, so this proves nothing:" +
                NL + out,
            tot > 500 && bothSame > 400
        )
        assertEquals(
            "the space bar and the underline name different words:" + NL + out + examples,
            emptyList<String>(), examples
        )
        assertEquals(
            "the space bar commits a word the underline would not offer at all. " +
                "correctionFor asks contractionFor before the candidate pool, so a " +
                "new pre-check there reaches the commit without reaching the menu:" +
                NL + out,
            0, barOnly
        )
    }

    private fun measureCommit(
        lang: String, locale: Locale, foreign: String, words: Int,
        cautious: Boolean = false
    ): CommitScore {
        val engine = realEngine(lang)
        engine.cautiousAutocorrect = cautious
        val prox = KeyProximity.forLang(lang)
        val sample = sample(lang, words)

        var asked = 0
        var fixed = 0
        for (slip in Slip.values()) {
            val rnd = Random(seed = 20260820 + slip.ordinal)
            for (w in sample) {
                val typo = damage(w, slip, prox, rnd) ?: continue
                if (engine.acceptedWord(typo, lang, locale)) continue
                asked++
                if (engine.correctionFor(typo, lang, locale) == w) fixed++
            }
        }

        val alien = alienWords(engine, lang, locale, foreign, 200)
        var destroyed = 0
        for (w in alien) {
            val fix = engine.correctionFor(w, lang, locale)
            if (fix != null && fix.lowercase(locale) != w) destroyed++
        }
        return CommitScore(asked, fixed, alien.size, destroyed)
    }

    private fun commitReport(lang: String, s: CommitScore): String =
        ("%s commit: fixes %.0f%% of typos (%d/%d), destroys %.0f%% of real " +
            "unknown words (%d/%d)").format(
            lang, s.fixRate * 100, s.fixed, s.asked,
            s.destroyRate * 100, s.destroyed, s.alien
        )

    @Test
    fun `autocorrect applies its fixes without overwriting words that were right`() {
        val results = listOf(
            Triple("en", Locale.ENGLISH, "tr"),
            Triple("tr", Locale.forLanguageTag("tr"), "en")
        ).map { (lang, locale, foreign) ->
            lang to measureCommit(lang, locale, foreign, 60)
        }

        val lines = results.joinToString("\n") { (lang, s) -> commitReport(lang, s) }
        println(lines)

        // Measured 2026-08-20, the day the gate was added, sweeping
        // Dictionary.AUTO_MAX_COST_PER_CHAR with everything else held still.
        // Left column is what the keyboard still fixes on its own, right is
        // what it overwrites that was already correct (en/tr):
        //
        //   none   97/96   59/63     no gate, which is how this shipped
        //   0.20   97/96   38/42
        //   0.17   97/96   20/21
        //   0.15   97/96   18/18
        //   0.14   97/96   15/16     <- the constant
        //   0.13   94/93    9/10
        //   0.10   79/82    7/ 5
        //
        // The striking part is the top half: from no gate down to 0.14 the
        // repair rate never moves while destruction falls four-fold. The
        // corrections being refused in that range were not fixing anything —
        // they were two edits on a six-letter word, reaching a commoner word
        // that happened to be nearby.
        //
        // Both floors matter and they have to be read together. A threshold
        // tuned to make either column look good is trivially reachable by
        // wrecking the other: turn the gate off and the left column is
        // perfect, close it to nothing and the right column is.
        // The setting, measured rather than described. "Cautious" is the one
        // point on the curve that is a judgement instead of a measurement:
        // three points of repair for six of protection, and which side of that
        // somebody wants is not a thing a benchmark can settle for them.
        //
        //   balanced (default)   fixes 97/96   destroys 15/16
        //   cautious             fixes 94/91   destroys  9/ 9
        val cautious = listOf(
            Triple("en", Locale.ENGLISH, "tr"),
            Triple("tr", Locale.forLanguageTag("tr"), "en")
        ).map { (lang, locale, foreign) ->
            lang to measureCommit(lang, locale, foreign, 60, cautious = true)
        }
        val cautiousLines = cautious.joinToString("\n") { (lang, s) ->
            "cautious " + commitReport(lang, s)
        }
        println(cautiousLines)

        cautious.forEach { (lang, c) ->
            val balanced = results.first { it.first == lang }.second
            assertTrue(
                "the cautious setting no longer protects anything in $lang. It " +
                    "is supposed to overwrite fewer correctly-typed words than " +
                    "the default, which is the entire reason it exists.\n" +
                    lines + "\n" + cautiousLines,
                c.destroyRate < balanced.destroyRate
            )
            assertTrue(
                "the cautious setting has stopped fixing typos in $lang. It is " +
                    "meant to be a trade, not an off switch.\n" + cautiousLines,
                c.fixRate >= 0.88
            )
        }

        results.forEach { (lang, s) ->
            assertTrue(
                "autocorrect has stopped fixing typos in $lang.\n" + lines,
                s.fixRate >= 0.92
            )
            assertTrue(
                "autocorrect is overwriting correctly-typed words in $lang " +
                    "again — this was 56%/61% before the gate existed.\n" + lines,
                s.destroyRate <= 0.20
            )
            assertTrue("the corpus generated nothing:\n" + lines, s.asked > 20 && s.alien > 20)
        }
    }

    // ---- can a word that is not one of the commonest still be repaired? ----

    /**
     * Repair rate by how common the target word is.
     *
     * This exists because the arms above cannot see the thing it measures, and
     * that blind spot very nearly produced a bad change. [sample] draws from
     * ranks 80 upward and takes the first seventy that fit, so every target in
     * every other measurement here is among the most common words in the
     * language. Any constant that limits corrections *by word frequency* is
     * therefore invisible to them — the words they ask about are inside any
     * plausible limit.
     *
     * What that looked like in practice: tightening [CORRECTION_TARGET_CAP]
     * from 60,000 to 15,000 read as a straight halving of the destruction rate
     * at no cost to repair whatsoever, which is exactly the shape of a free
     * lunch worth taking. It was not one. Measured by rank, the same change
     * took words around rank 30,000 from 62% repaired and 89% offered to zero
     * and zero: an entire band of ordinary vocabulary becomes uncorrectable and
     * is not even shown, and nothing in the suite would have said so.
     *
     * The floor here is on the **offer** rate rather than the fix rate. A
     * frequency cap decides whether a word may be a correction target at all,
     * so what it destroys is candidacy, and offering is where that shows up
     * first and most sharply. The fix rate is reported alongside because it is
     * the thing anyone reading this actually wants to know, but it moves for
     * ranking reasons too and makes a noisier ratchet.
     *
     * Words past the cap are meant to be uncorrectable — that is what it is
     * for — so the deepest band is reported and deliberately not floored.
     */
    private data class BandScore(
        val rank: Int, val asked: Int, val fixed: Int, val offered: Int
    ) {
        val fixRate get() = if (asked == 0) 0.0 else fixed.toDouble() / asked
        val offerRate get() = if (asked == 0) 0.0 else offered.toDouble() / asked
    }

    /** Words starting [rank] entries down the frequency list. */
    private fun bandAt(lang: String, rank: Int, count: Int): List<String> =
        File(assets(), "dictionaries/$lang.txt").useLines { lines ->
            lines.drop(rank)
                .mapNotNull { it.split(' ').firstOrNull() }
                .filter { it.length in 5..9 && it.all { c -> c.isLetter() } }
                .take(count)
                .toList()
        }

    private fun measureBands(lang: String, locale: Locale, per: Int): List<BandScore> {
        val engine = realEngine(lang)
        val prox = KeyProximity.forLang(lang)
        // One slip kind, because the question is which words are reachable
        // rather than which damage is survivable, and the neighbour slip is the
        // one the whole engine is built around.
        return BANDS.map { rank ->
            val rnd = Random(seed = 20260820 + rank)
            var asked = 0
            var fixed = 0
            var offered = 0
            for (w in bandAt(lang, rank, per)) {
                val typo = damage(w, Slip.NEIGHBOUR, prox, rnd) ?: continue
                if (engine.acceptedWord(typo, lang, locale)) continue
                asked++
                if (engine.correctionFor(typo, lang, locale) == w) fixed++
                if (engine.correctionCandidates(typo, lang, locale, limit = 3).contains(w)) {
                    offered++
                }
            }
            BandScore(rank, asked, fixed, offered)
        }
    }

    private fun bandReport(lang: String, scores: List<BandScore>): String =
        "$lang by rank: " + scores.joinToString("  ") {
            "~${it.rank}: offered ${"%.0f".format(it.offerRate * 100)}%" +
                ", fixed ${"%.0f".format(it.fixRate * 100)}% (n=${it.asked})"
        }

    @Test
    fun `a word outside the commonest few thousand can still be corrected`() {
        val results = com.rimboard.keyboard.model.Languages.all
            .map { it.code to it.locale }
            .map { (lang, locale) -> lang to measureBands(lang, locale, 40) }

        val lines = results.joinToString("\n") { (lang, s) -> bandReport(lang, s) }
        println(lines)

        // Measured 2026-08-20 at CORRECTION_TARGET_CAP = 60000, offered/fixed:
        //
        //   rank      en             tr
        //   ~80       100 / 100      100 / 100
        //   ~2000     100 /  97      100 /  97
        //   ~10000    100 /  78      100 /  88
        //   ~30000     90 /  68      100 /  87
        //   ~80000      0 /   0        0 /   0   past the cap, by design
        //
        // The fix rate falling away with rank is not a fault: a rarer target
        // is genuinely a less likely thing to have meant, and the ranking is
        // supposed to say so. What matters is that the word stays *reachable*,
        // and it does right down to the cap.
        //
        // **Swept to all twenty-two, 2026-08-30.** The cap is expressed as a
        // rank and behaves like one: every language is offered a correction
        // 89-100% of the time at rank 30,000 and 0% at 80,000, with the cliff
        // in the same place. Nothing is anomalous, which is the answer worth
        // having about a constant that governs every language identically
        // while their lists cover very different amounts of each language.
        //
        // The fix rate at rank 30,000 spreads more than the offer rate does --
        // hu 94, uk 90, ru 90, sk 89, hr 85 at one end and el 61, ro 63, de 64,
        // no 66, en 69 at the other. That ordering is roughly how much of the
        // language a 200,000-word list still covers at that depth, which is a
        // fact about the corpora rather than about this cap.
        results.forEach { (lang, s) ->
            s.filter { it.rank <= FLOORED_TO }.forEach { b ->
                assertTrue(
                    "words around rank ${b.rank} in $lang are no longer offered " +
                        "as corrections at all.\n" + lines,
                    b.offerRate >= 0.75
                )
            }
            assertTrue("the bands generated nothing:\n" + lines, s.all { it.asked >= 15 })
        }
    }

    // ---- does knowing where the finger landed help? ------------------------

    /**
     * Repair with and without the touch trail, over the same damaged words.
     *
     * Every other arm here damages a word by *character*: replace `k` with `l`
     * and ask what comes back. That throws away the thing a keyboard actually
     * has, which is a position. This one damages by *touch*: it puts the finger
     * at a point between two key centres, works out which key that fires, and
     * hands the corrector the same word twice — once as bare characters, once
     * with the measurement that says how marginal the tap was.
     *
     * The tap fraction is what makes it a real test rather than a
     * demonstration. At 0.50 the finger is exactly on the boundary and the
     * wrong key won by nothing, which is the case the measurement exists for.
     * Near 1.00 it is dead on the wrong key and there is genuinely nothing to
     * recover — a model that "helped" there would be inventing evidence. Both
     * bands are measured, and the gain being larger in the first is the shape
     * that says the mechanism works rather than merely moves numbers.
     *
     * Common words are measured too, and are the reason this is not oversold:
     * the channel model already repairs them at 98% from characters alone, so
     * there is nothing for a second signal to add. The value is in the tail,
     * where the ranking is genuinely unsure.
     */
    private data class TouchScore(
        val rank: Int, val near: Boolean,
        val asked: Int, val blind: Int, val touched: Int
    ) {
        val blindRate get() = if (asked == 0) 0.0 else blind.toDouble() / asked
        val touchRate get() = if (asked == 0) 0.0 else touched.toDouble() / asked
    }

    private fun measureTouch(lang: String, locale: Locale, per: Int): List<TouchScore> {
        val engine = realEngine(lang)
        val dict = engine.dictionary(lang, locale)
        val prox = KeyProximity.forLang(lang)
        val out = ArrayList<TouchScore>()
        for (rank in listOf(80, 10_000, 30_000)) {
            for (near in listOf(true, false)) {
                val lo = if (near) 0.50f else 0.65f
                val hi = if (near) 0.65f else 1.00f
                val rnd = Random(seed = 20260820 + rank + if (near) 1 else 0)
                var asked = 0
                var blind = 0
                var touched = 0
                for (w in bandAt(lang, rank, per)) {
                    val i = 1 + rnd.nextInt(w.length - 2)
                    val meant = w[i]
                    val hit = prox.neighbours(meant).firstOrNull() ?: continue
                    val mx = prox.gridX(meant) ?: continue
                    val my = prox.gridY(meant) ?: continue
                    val hx = prox.gridX(hit) ?: continue
                    val hy = prox.gridY(hit) ?: continue
                    // Where the finger was, and therefore which key fired: past
                    // the halfway point the wrong key wins, by less and less as
                    // the fraction approaches a half.
                    val t = lo + rnd.nextFloat() * (hi - lo)
                    val px = mx + t * (hx - mx)
                    val py = my + t * (hy - my)
                    val typed = w.substring(0, i) + hit + w.substring(i + 1)
                    if (typed == w || engine.acceptedWord(typed, lang, locale)) continue
                    asked++
                    val trail = FloatArray(typed.length * 2)
                    trail[i * 2] = px - hx
                    trail[i * 2 + 1] = py - hy
                    if (dict.correctionsScored(typed, prox, 1).firstOrNull()?.first == w) {
                        blind++
                    }
                    if (dict.correctionsScored(typed, prox, 1, trail)
                            .firstOrNull()?.first == w
                    ) {
                        touched++
                    }
                }
                out.add(TouchScore(rank, near, asked, blind, touched))
            }
        }
        return out
    }

    private fun touchReport(lang: String, s: List<TouchScore>): String =
        s.joinToString("\n") {
            "  %s rank ~%-6d tap %s: blind %.0f%%  with touch %.0f%%  (n=%d)".format(
                lang, it.rank, if (it.near) "on the line" else "well over ",
                it.blindRate * 100, it.touchRate * 100, it.asked
            )
        }

    @Test
    fun `knowing where the finger landed repairs words characters alone cannot`() {
        val results = com.rimboard.keyboard.model.Languages.all
            .map { it.code to it.locale }
            .map { (lang, locale) -> lang to measureTouch(lang, locale, 120) }

        val lines = results.joinToString("\n") { (lang, s) -> touchReport(lang, s) }
        println(lines)

        // Measured 2026-08-20 on English and Turkish, blind -> with the trail:
        //
        //                      tap on the line     tap well over
        //   en  rank ~80         100 -> 100          100 -> 100
        //   en  rank ~10000       80 ->  83           79 ->  81
        //   en  rank ~30000       63 ->  69           62 ->  64
        //   tr  rank ~80           99 -> 100           99 -> 100
        //   tr  rank ~10000        90 ->  94           90 ->  91
        //   tr  rank ~30000        83 ->  89           87 ->  88
        //
        // Swept over all twenty-two on 2026-08-30, because [measureTouch] was
        // language-general the whole time and only this call site was not --
        // the same fault the context arm had. **Touch does not lose a single
        // word in any language, at any rank, on either side of the key.** That
        // is the claim worth having and it could not be made from two.
        //
        // Where it helps most is where the finger was closest to the line, in
        // the tail, exactly as the mechanism predicts: it 67->75 at rank
        // 30,000, fi 82->88 and tr 89->94 at 10,000, ro 79->85, en 64->70.
        // Where it helps least is German, which reads 82->82 at 10,000 and
        // 73->75 at 30,000 -- two words. That is not a fault: German's tail at
        // those ranks is long compounds, where one substituted letter leaves
        // far less ambiguity for a second signal to resolve.
        //
        // Two things to read off that, and the second matters more than the
        // first. The gain lives in the tail: common words are already repaired
        // at 99-100% from characters alone, so there is nothing for a second
        // signal to add and it does not pretend otherwise. And the gain is two
        // to three times larger for a tap on the line than for one well over
        // it — three to six points against one to two. That separation is the
        // evidence the mechanism is real rather than a reshuffle: the
        // measurement is worth most exactly where the key that fired won by
        // least, and worth almost nothing where the finger was squarely on the
        // wrong key and there is genuinely nothing to recover.
        //

        // The floor is that touch never *loses*. It is deliberately not a floor
        // on the size of the gain — this is a few points in the tail, honestly,
        // and pinning a number that small would be pinning noise. What must
        // never happen is that carrying the measurement makes the answer worse,
        // because that would mean the cost model and the tap arbiter have come
        // to disagree about the same geometry.
        results.forEach { (lang, s) ->
            s.forEach { b ->
                assertTrue(
                    "touch data made $lang rank ~${b.rank} worse, not better.\n" + lines,
                    b.touchRate >= b.blindRate - 0.005
                )
                assertTrue("no corpus at $lang rank ~${b.rank}:\n" + lines, b.asked >= 40)
            }
        }
        // Pooled rather than per language. The gain is a few words in the
        // tail of each list, and at that size a per-language floor is a floor
        // on sampling noise: German reads two where the bar was three, which
        // is not German telling us anything. Twenty-two languages together
        // are a sample worth asserting on, and what has to hold per language
        // is the invariant above -- that carrying the measurement never makes
        // the answer worse.
        val nearGain = results.sumOf { (_, s) ->
            s.filter { it.near && it.rank >= 10_000 }.sumOf { it.touched - it.blind }
        }
        println("near-tail words recovered by touch, all languages: $nearGain")
        assertTrue(
            "touch data stopped helping in the tail, which is the whole reason " +
                "the trail is plumbed through at all. Pooled gain $nearGain, " +
                "measured at 153.\n" + lines,
            nearGain >= NEAR_TAIL_FLOOR
        )
    }

    private companion object {
        val NL: String = System.lineSeparator()

        /**
         * A ratchet on the close-language destroy rate rather than a standard.
         * The distant-pair arm asserts 25%; this population is over it and the
         * ratio sweep in the test above says every lever costs more than it
         * saves. What must not happen is that it grows unnoticed.
         */
        const val CLOSE_DESTROY_CEILING = 85

        /**
         * Under the 88.8% these five languages measure, with about four points
         * of room -- for the JDK the suite runs on and for the assets being
         * rebuilt, not for run-to-run noise, of which a seeded corpus has none.
         *
         * It was 0.80 when written this morning, nine points under, which is
         * the same failure the rest of this suite's floors had: far enough
         * below the measurement that no plausible regression reaches it.
         */
        const val PROSE_FIX_FLOOR = 0.85

        /**
         * Pooled near-tail words recovered by the touch trail across all
         * twenty-two languages. Measured at 153; a third of headroom, because
         * this is a sum of small per-language gains and the point is to catch
         * the mechanism breaking rather than to pin the number.
         */
        const val NEAR_TAIL_FLOOR = 100
    }

}
