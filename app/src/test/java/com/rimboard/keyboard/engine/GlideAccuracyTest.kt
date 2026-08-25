package com.rimboard.keyboard.engine

import com.rimboard.keyboard.model.GlidePath
import com.rimboard.keyboard.model.KeyProximity
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.random.Random

/**
 * How often glide typing produces the word that was actually swiped.
 *
 * [AutocorrectAccuracyTest] asks this of tapping and the answer is in the
 * nineties. Nothing has ever asked it of gliding, which is the input method
 * with the most to get wrong: a tapped word arrives as the letters the user
 * chose, and a glided word arrives as a smear that has to be read.
 *
 * **The corpus is a motion model, and that is the honest limit of it.** Real
 * thumbs are not sampled here; a finger travelling between key centres is,
 * with the four distortions that separate a real swipe from a straight line —
 * curvature through the corners, jitter, corner-cutting, and a sample rate
 * that thins out when the finger moves fast. Every arm is seeded, so the
 * corpus is identical on every machine and every run.
 *
 * What makes it a fair test rather than a flattering one is that both decoders
 * read the *same generated path*. The path is the ground truth; how much of it
 * a decoder chooses to look at is the thing being measured. A decoder that
 * reduces the path to the keys it crossed is free to do that, and pays for it
 * here only if crossing-order really does lose information — which is the
 * claim under test, not an assumption baked into the generator.
 *
 * ## Measured and rejected
 *
 * **Weighting the ends of the stroke more than the middle.** The first and last
 * points are aimed at from and to rest, so they ought to be the most reliable,
 * and a swipe of "hello" offering "help" first is exactly a last-letter
 * confusion: p and o are adjacent, the two curves differ only in where they
 * stop, and frequency settles it. Weighting the four points at each end by two,
 * three and five times gave mean top-1 of 85.4, 85.5 and 85.2 against 85.5 for
 * weighting nothing.
 *
 * The reason it cannot help is that the ends are also where *overshoot* lives:
 * a finger that carries past its last letter puts those same points beyond it.
 * Sharpening the ends helps the swipe that stopped cleanly and hurts the one
 * that did not, and the two cancel.
 */
class GlideAccuracyTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-glide", "").let { it.delete(); it.mkdirs(); it }
        userData = UserData.inDir(dir)
    }

    @After
    fun tearDown() {
        userData.shutdown()
        dir.deleteRecursively()
    }

    private fun assets(): File =
        listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }

    private fun realEngine(lang: String): SuggestionEngine {
        val files = listOf("dictionaries/$lang.txt", "predictions/$lang.txt")
            .associateWith { File(assets(), it).readText() }
        return SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
    }

    /**
     * Words worth gliding: long enough that the path has corners to read, and
     * common enough that somebody would swipe rather than tap them. Two- and
     * three-letter words are excluded because gliding them is not a thing
     * people do — the tap is faster — and they would measure nothing but the
     * frequency prior.
     */
    private fun sample(lang: String, count: Int): List<String> =
        File(assets(), "dictionaries/$lang.txt").useLines { lines ->
            lines.drop(40)
                .mapNotNull { it.split(' ').firstOrNull() }
                .filter { it.length in 4..10 && it.all { c -> c.isLetter() } }
                .take(count)
                .toList()
        }

    // ---- The motion model --------------------------------------------------

    /**
     * How a finger is allowed to differ from the straight line through the
     * letters. Fixed before either decoder was measured, and not touched
     * since — a generator retuned after seeing a score measures the tuner.
     */
    private enum class Hand(
        /** Fraction of the way each corner is rounded off by momentum. */
        val curve: Float,
        /**
         * Ceiling on how far back from a corner that rounding may begin, in key
         * widths — the radius the hand's momentum carries it through.
         *
         * The same correction as [cutMax] and made for the same reason: a
         * distortion measured as a share of the distance between letters grows
         * without limit as the letters get further apart, and a hand does not.
         * Both were fractions until the audit arm was written and reported a
         * generated finger missing letters by more than three key widths.
         */
        val curveMax: Float,
        /** Fraction of the way each interior letter is missed short. */
        val cut: Float,
        /**
         * Ceiling on that miss, in key widths.
         *
         * **Corrected 2026-08-23, after the audit arm below caught it.** The
         * miss used to be the fraction alone, and a fraction of *what* is the
         * whole question: a detour of six key widths out to `p` and back was
         * being cut by a fifth of six key widths, so the generated finger
         * missed some letters by more than three keys. That is not a sloppy
         * thumb, it is a thumb that swiped a different word, and the corpus was
         * scoring the decoder on gestures nobody made.
         *
         * A hand cuts a corner by roughly a fixed distance -- the radius its
         * momentum carries it through -- not by a share of how far the corner
         * stuck out. The fraction stays, because a shallow corner really is cut
         * less than a sharp one; this bounds it at something a hand can do.
         *
         * The correction made the corpus harder, not easier, on the arm that
         * mattered: it is the *long-detour* letters that were being missed by
         * three keys, and those are the letters that make a word identifiable.
         */
        val cutMax: Float,
        /** Gaussian wobble per sample, in key widths. */
        val jitter: Float,
        /** Samples per key width of travel. */
        val density: Float
    ) {
        /** A careful, slow swipe. The best case for any decoder. */
        DELIBERATE(0.10f, 0.50f, 0.00f, 0.00f, 0.02f, 6f),

        /** Ordinary typing: momentum rounds the corners, the hand shakes. */
        NATURAL(0.35f, 1.00f, 0.20f, 0.25f, 0.06f, 4f),

        /** Fast and loose — the corners get cut before the key is reached. */
        SLOPPY(0.55f, 1.60f, 0.45f, 0.55f, 0.10f, 3f),

        /** A flick. The touch stream itself thins out at speed. */
        HURRIED(0.45f, 1.40f, 0.35f, 0.40f, 0.08f, 1.2f)
    }

    /** [t] of the way along a leg, but never further back than [max] key widths. */
    private fun radiusLimited(t: Float, dx: Float, dy: Float, max: Float): Float {
        val len = hypot(dx, dy)
        return if (len <= 0f) 0f else minOf(t, max / len)
    }

    /** The letters a path actually aims at: doubles are one stop, not two. */
    private fun anchorsOf(word: String): String {
        val sb = StringBuilder(word.length)
        for (ch in word) if (sb.isEmpty() || sb[sb.length - 1] != ch) sb.append(ch)
        return sb.toString()
    }

    /**
     * A swiped path for [word], in the same key-width/row grid [KeyProximity]
     * uses everywhere else, as interleaved x,y.
     *
     * Corner-cutting is applied to the aim points first (the finger commits to
     * the next letter before it has arrived at this one), then the polyline is
     * rounded through those aim points by a quadratic through each corner, then
     * sampled by arc length and jittered.
     */
    private fun path(word: String, hand: Hand, prox: KeyProximity, rnd: Random): FloatArray? {
        val letters = anchorsOf(word)
        if (letters.length < 2) return null
        val ax = FloatArray(letters.length)
        val ay = FloatArray(letters.length)
        for (i in letters.indices) {
            // A finger can only cross keys the layout draws, so an accented
            // letter is traced at its base letter's key -- which is what the
            // decoder folds to as well. Without this the harness could not
            // build a path for most words in half the shipped languages, and
            // so could measure the folding's cost without ever seeing its
            // benefit: 94% of Greek was invisible to this benchmark.
            //
            // A fallback, not a rewrite. Turkish draws ı, ğ, ü, ş, ö and ç as
            // real keys, so those resolve to themselves and the Turkish figures
            // are untouched.
            val ch = letters[i]
            ax[i] = prox.gridX(ch)
                ?: prox.gridX(com.rimboard.keyboard.model.Diacritics.fold(ch))
                ?: return null
            ay[i] = prox.gridY(ch)
                ?: prox.gridY(com.rimboard.keyboard.model.Diacritics.fold(ch))
                ?: return null
        }
        // Corner-cutting: interior aim points drift toward the chord that
        // bypasses them. The first and last are where the finger starts and
        // stops, so they are not missed.
        val cx = ax.copyOf()
        val cy = ay.copyOf()
        for (i in 1 until letters.length - 1) {
            val mx = (ax[i - 1] + ax[i + 1]) / 2f
            val my = (ay[i - 1] + ay[i + 1]) / 2f
            var dx = (mx - ax[i]) * hand.cut
            var dy = (my - ay[i]) * hand.cut
            val d = hypot(dx, dy)
            if (d > hand.cutMax && d > 0f) {
                dx *= hand.cutMax / d
                dy *= hand.cutMax / d
            }
            cx[i] = ax[i] + dx
            cy[i] = ay[i] + dy
        }

        // Round each corner with a quadratic Bezier whose control point is the
        // corner itself, entered and left `curve` of the way along the
        // neighbouring segments. curve = 0 leaves the polyline untouched.
        val px = ArrayList<Float>()
        val py = ArrayList<Float>()
        px.add(cx[0]); py.add(cy[0])
        for (i in 1 until letters.length - 1) {
            val t = hand.curve.coerceIn(0f, 0.5f)
            val tIn = radiusLimited(t, cx[i - 1] - cx[i], cy[i - 1] - cy[i], hand.curveMax)
            val tOut = radiusLimited(t, cx[i + 1] - cx[i], cy[i + 1] - cy[i], hand.curveMax)
            val inX = cx[i] + (cx[i - 1] - cx[i]) * tIn
            val inY = cy[i] + (cy[i - 1] - cy[i]) * tIn
            val outX = cx[i] + (cx[i + 1] - cx[i]) * tOut
            val outY = cy[i] + (cy[i + 1] - cy[i]) * tOut
            px.add(inX); py.add(inY)
            for (s in 1..3) {
                val u = s / 4f
                val iv = 1 - u
                px.add(iv * iv * inX + 2 * iv * u * cx[i] + u * u * outX)
                py.add(iv * iv * inY + 2 * iv * u * cy[i] + u * u * outY)
            }
            px.add(outX); py.add(outY)
        }
        px.add(cx[letters.length - 1]); py.add(cy[letters.length - 1])

        // Resample the polyline at a constant arc-length step, which is what a
        // constant-velocity finger under a constant-rate digitiser produces.
        val step = 1f / hand.density
        val out = ArrayList<Float>()
        var carry = 0f
        out.add(px[0]); out.add(py[0])
        for (i in 0 until px.size - 1) {
            val dx = px[i + 1] - px[i]
            val dy = py[i + 1] - py[i]
            val len = hypot(dx, dy)
            if (len <= 1e-6f) continue
            var d = step - carry
            while (d <= len) {
                out.add(px[i] + dx * (d / len))
                out.add(py[i] + dy * (d / len))
                d += step
            }
            carry = (carry + len) % step
        }
        out.add(px[px.size - 1]); out.add(py[py.size - 1])

        val arr = FloatArray(out.size)
        for (i in out.indices) {
            // The endpoints are where the finger deliberately started and
            // stopped, so they wobble like the rest but are not displaced by
            // the model's own smoothing.
            arr[i] = out[i] + (rnd.nextFloat() - 0.5f) * 2f * hand.jitter
        }
        return arr
    }

    // ---- Is the motion model plausible? ------------------------------------

    /**
     * The four hands stated in key widths, which is a thing a person can judge.
     *
     * A hand is a set of four tuning numbers, and tuning numbers are where a
     * benchmark quietly becomes a strawman. What they *mean* is how far the
     * finger sits from the letter it is spelling, and a key is one unit wide,
     * so the figures below can be checked against the reader's own thumb rather
     * than taken on trust. Printed, never asserted on: this describes the
     * corpus, it does not grade the decoder.
     */
    @Test
    fun `what the four hands mean in key widths`() {
        val prox = KeyProximity.forLang("en")
        val lines = StringBuilder()
        for (hand in Hand.values()) {
            val rnd = Random(seed = 20260823 + hand.ordinal)
            var sumMiss = 0.0
            var worst = 0.0
            var offKey = 0
            var n = 0
            for (w in sample("en", 120)) {
                val p = path(w, hand, prox, rnd) ?: continue
                for (ch in anchorsOf(w)) {
                    val kx = prox.gridX(ch) ?: continue
                    val ky = prox.gridY(ch) ?: continue
                    // How close the path ever got to this letter's key.
                    var closest = Float.MAX_VALUE
                    var i = 0
                    while (i < p.size) {
                        val d = hypot(p[i] - kx, p[i + 1] - ky)
                        if (d < closest) closest = d
                        i += 2
                    }
                    sumMiss += closest
                    if (closest > worst) worst = closest.toDouble()
                    // Half a key width is the edge of the key.
                    if (closest > 0.5f) offKey++
                    n++
                }
            }
            lines.append(
                "$hand: closest approach to each letter, mean " +
                    "${"%.2f".format(sumMiss / n)} key widths, worst ${"%.2f".format(worst)}, " +
                    "missed the key entirely ${"%.0f".format(offKey * 100.0 / n)}%\n"
            )
        }
        println(lines)
    }

    // ---- Measuring ---------------------------------------------------------

    /**
     * [offered] is measured at three, which is how many slots the suggestion
     * strip has. Measuring a deeper list would count words the user has no way
     * to reach.
     */
    private data class Score(val top1: Double, val offered: Double, val asked: Int) {
        fun pct() = "${"%.0f".format(top1 * 100)}%/${"%.0f".format(offered * 100)}%"
    }

    private fun measure(lang: String, locale: Locale, words: List<String>, hand: Hand): Score {
        val engine = realEngine(lang)
        val prox = KeyProximity.forLang(lang)
        val rnd = Random(seed = 20260823 + hand.ordinal)
        var asked = 0
        var t1 = 0
        var t4 = 0
        for (w in words) {
            val pts = path(w, hand, prox, rnd) ?: continue
            val gp = GlidePath.of(pts, prox) ?: continue
            asked++
            val offered = engine.glideFor(gp, lang, locale, personalized = false)
            if (offered.firstOrNull() == w) t1++
            if (offered.contains(w)) t4++
        }
        return Score(
            top1 = if (asked == 0) 0.0 else t1.toDouble() / asked,
            offered = if (asked == 0) 0.0 else t4.toDouble() / asked,
            asked = asked
        )
    }

    /**
     * Every shipped language, not just the two the rest of this file measures.
     *
     * ## What this found
     *
     * Almost no layout draws its accented letters — they live under a long
     * press — so a word containing one had no key to be placed on, its cost was
     * infinite, and **it could not be swiped at all, by anyone**. Modern Greek
     * puts an accent on nearly every polysyllabic word, so Greek gliding was
     * effectively not a feature.
     *
     * Same corpus, same motion model, decoder folding off then on
     * (natural hand, top-1 / top-3):
     *
     *     el   5%/6%   ->  75%/83%        pl  61%/63%  ->  94%/98%
     *     cs  35%/38%  ->  84%/95%        sv  62%/65%  ->  90%/97%
     *     sk  48%/53%  ->  88%/97%        hu  67%/73%  ->  89%/98%
     *     fi  51%/62%  ->  77%/95%        hr  69%/78%  ->  84%/97%
     *     ro  60%/72%  ->  75%/92%        es  75%/86%  ->  83%/98%
     *
     * English, Dutch, Indonesian, Russian, Turkish and Ukrainian do not move by
     * a single point, which is the check that the change does what it says:
     * those six layouts already draw every letter their language spells with,
     * so there is nothing to fold.
     *
     * ## The cost, which is real and small
     *
     * Folding lets an accented word compete for a path that an unaccented word
     * also fits, so on the words that *were* already reachable the top-1 slips
     * a little — cs 91->87, fi 84->78, sk 90->87, es 86->83, most others one or
     * two points, en/tr/it/hu not at all. That is the whole of the downside,
     * and it buys the other seventy points. Recorded so nobody reads the small
     * regression on its own and reverses this.
     *
     * ## Reading the harness
     *
     * `path` folds too, and has to: a finger can only cross keys the layout
     * draws. Before it did, this benchmark could not build a path for most
     * words in half these languages and so could see the folding's cost while
     * being blind to its benefit — it scored Greek on 7 words out of 120 and
     * called it 86%.
     */
    @Test
    fun `every shipped language can be swiped`() {
        val langs = File(assets(), "dictionaries").list().orEmpty()
            .map { it.removeSuffix(".txt") }.sorted()
        val lines = StringBuilder()
        val weak = ArrayList<String>()
        val unreachable = ArrayList<String>()
        for (lang in langs) {
            val locale = Locale.forLanguageTag(lang)
            val words = sample(lang, 120)
            if (words.size < 40) continue
            val n = measure(lang, locale, words, Hand.NATURAL)
            lines.append("%-4s natural %s (n=%d)%n".format(lang, n.pct(), n.asked))
            // Every language's ordinary vocabulary must be swipeable at all.
            // This is what Greek failed: 7 of 120 words could even be given a
            // path, so the accuracy figure described almost nothing.
            if (n.asked < words.size * 9 / 10) unreachable.add("$lang ${n.asked}/${words.size}")
            if (n.top1 < 0.70 || n.offered < 0.80) weak.add("$lang ${n.pct()}")
        }
        println(lines)
        assertTrue(
            "words in these languages cannot be given a swipe path at all, " +
                "which is how Greek gliding was 5% without anyone noticing: " +
                unreachable.toString() + " || " + lines,
            unreachable.isEmpty()
        )
        assertTrue(
            "these languages decode too poorly to call gliding supported: " +
                weak.toString() + " || " + lines,
            weak.isEmpty()
        )
    }

    @Test
    fun `glide typing is right often enough, and says where it is not`() {
        val lines = StringBuilder()
        val scores = LinkedHashMap<String, Score>()
        for ((lang, locale) in listOf("en" to Locale.ENGLISH, "tr" to Locale.forLanguageTag("tr"))) {
            val words = sample(lang, 120)
            for (hand in Hand.values()) {
                val s = measure(lang, locale, words, hand)
                scores["$lang/$hand"] = s
                lines.append("$lang $hand (top1/offered): ${s.pct()}  n=${s.asked}\n")
            }
        }
        println(lines)

        assertTrue("the corpus generated nothing:\n$lines",
            scores.values.all { it.asked >= 50 })

        // A floor, not a target, and set on the *worst* hand, because a decoder
        // that only works for a careful swipe is precisely the thing this
        // replaced. Lowering it to make a change pass is the one use it must
        // never be put to.
        //
        // Measured 2026-08-23, the day the shape decoder landed, as
        // top1/offered, both at the strip’s three slots:
        //
        //     hand         shape decoder      crossing-sequence decoder
        //     en DELIBERATE  99% / 100%              86% / 94%
        //     en NATURAL     88% / 100%              17% / 22%
        //     en SLOPPY      70% /  95%               3% /  5%
        //     en HURRIED     75% /  98%               3% /  6%
        //     tr DELIBERATE 100% / 100%              84% / 87%
        //     tr NATURAL     92% / 100%               8% /  8%
        //     tr SLOPPY      78% /  96%               3% /  3%
        //     tr HURRIED     82% /  96%               2% /  3%
        //
        // The right-hand column is the old decoder at top1/top4, which flatters
        // it slightly; it had no arm measuring three and is not worth rerunning
        // to find out, since the gap is what it is.
        //
        // Read the offered column as the useful one: the word the finger drew
        // is somewhere on the strip essentially always, so top-1 is a question
        // about ranking rather than about whether the swipe was understood.
        //
        // The right-hand column is not a decoder that was tuned badly. See the
        // arm below: on those hands the answer was not in the data it was
        // given, so no ranking could have found it.
        val worst = scores.values.minOf { it.top1 }
        assertTrue(
            "glide top-1 accuracy has fallen below the floor.\n$lines",
            worst >= GLIDE_TOP1_FLOOR
        )
    }


    /**
     * The trade between how common a word is and how well it fits, swept.
     *
     * Read the columns, not the peak. What matters is that there is a broad
     * plateau and that the shipped value sits on it — a constant that only
     * works at one setting is a constant that has been fitted to this corpus.
     */
    @Test
    fun `the weight between frequency and fit, swept`() {
        val weights = listOf(3.0, 4.0, 5.0, 6.0, 7.0, 9.0, 12.0)
        val lines = StringBuilder()
        for (lang in listOf("en", "tr")) {
            val prox = KeyProximity.forLang(lang)
            val dict = Dictionary(
                File(assets(), "dictionaries/$lang.txt").readText().byteInputStream(),
                null, Locale.forLanguageTag(lang)
            )
            val words = sample(lang, 120)
            for (hand in Hand.values()) {
                lines.append("$lang %-11s".format(hand.name))
                for (wgt in weights) {
                    val rnd = Random(seed = 20260823 + hand.ordinal)
                    var n = 0
                    var right = 0
                    for (w in words) {
                        val pts = path(w, hand, prox, rnd) ?: continue
                        val gp = GlidePath.of(pts, prox) ?: continue
                        n++
                        val best = dict.glideScored(gp, 40).map { it.first }
                            .maxByOrNull { c -> ln(dict.frequency(c) + 1.0) - wgt * gp.costOf(c) }
                        if (best == w) right++
                    }
                    lines.append(" w=%.0f %3.0f%%".format(wgt, right * 100.0 / n))
                }
                lines.append("\n")
            }
        }
        println(lines)
    }

    /**
     * What a swipe costs to read, and what the fits actually measure.
     *
     * This runs when the finger lifts, on the UI thread of whatever phone the
     * keyboard is installed on, so it is a number worth knowing rather than
     * assuming. A desktop JVM is not a phone — treat the figure as an order of
     * magnitude and a regression tripwire, not as a device measurement.
     *
     * The fit column is the other half: it is the scale every weight in this
     * decoder is denominated in, so a constant sized against it (the personal
     * bonus, the shape weight) can be checked against what the model really
     * produces rather than against a guess at it.
     */
    @Test
    fun `what a swipe costs to read`() {
        val lines = StringBuilder()
        for (lang in listOf("en", "tr")) {
            val prox = KeyProximity.forLang(lang)
            val dict = Dictionary(
                File(assets(), "dictionaries/$lang.txt").readText().byteInputStream(),
                null, Locale.forLanguageTag(lang)
            )
            val words = sample(lang, 120)
            for (hand in Hand.values()) {
                val rnd = Random(seed = 20260823 + hand.ordinal)
                val paths = words.mapNotNull { w ->
                    path(w, hand, prox, rnd)?.let { GlidePath.of(it, prox) }?.let { w to it }
                }
                // Warm the JIT before timing it.
                repeat(2) { for ((_, gp) in paths) dict.glideScored(gp, 4) }
                val t0 = System.nanoTime()
                for ((_, gp) in paths) dict.glideScored(gp, 4)
                val perSwipeUs = (System.nanoTime() - t0) / 1000.0 / paths.size

                var truth = 0.0
                var rival = 0.0
                var rivals = 0
                for ((w, gp) in paths) {
                    truth += gp.costOf(w)
                    val other = dict.glideScored(gp, 4).map { it.first }.firstOrNull { it != w }
                    if (other != null) { rival += gp.costOf(other); rivals++ }
                }
                lines.append(
                    "$lang %-11s %6.0f us/swipe   fit: truth %.3f  best rival %.3f\n".format(
                        hand.name, perSwipeUs, truth / paths.size,
                        if (rivals == 0) 0.0 else rival / rivals
                    )
                )
            }
        }
        println(lines)
    }

    /**
     * What the crossing sequence loses before any ranking happens.
     *
     * This is the measurement the shape decoder was built on, and it is kept
     * because it is the one that says *why*. A decoder handed only the keys a
     * finger crossed cannot name a word whose letters are not in that sequence,
     * however good its ranking is — so the figure below is a hard ceiling on
     * everything that approach can ever score, and it does not move when the
     * ranking is improved.
     *
     * Measured 2026-08-23, and the reason there is a shape model at all:
     *
     *     hand         word survives     shipped decoder was
     *                  the sequence      scoring (top1/top4)
     *     DELIBERATE   en 100%  tr  98%    86%/94%   84%/87%
     *     NATURAL      en  22%  tr   8%    17%/22%    8%/8%
     *     SLOPPY       en   5%  tr   3%     3%/5%     3%/3%
     *     HURRIED      en   6%  tr   3%     3%/6%     2%/3%
     *
     * The two columns sit on top of each other, which is the whole finding: the
     * old decoder was already at its ceiling. Its ranking was not the problem
     * and no amount of tuning it would have moved these numbers. Only reading
     * the path could.
     */
    @Test
    fun `how much the crossing sequence loses before any ranking happens`() {
        val lines = StringBuilder()
        for (lang in listOf("en", "tr")) {
            val prox = KeyProximity.forLang(lang)
            val words = sample(lang, 120)
            for (hand in Hand.values()) {
                val rnd = Random(seed = 20260823 + hand.ordinal)
                var asked = 0
                var recoverable = 0
                for (w in words) {
                    val p = path(w, hand, prox, rnd) ?: continue
                    asked++
                    val seq = crossed(p, prox)
                    val c = anchorsOf(w)
                    if (seq.isNotEmpty() && c[0] == seq[0] && isSubsequence(c, seq)) recoverable++
                }
                lines.append(
                    "$lang $hand: word survives the crossing sequence " +
                        "${"%.0f".format(recoverable * 100.0 / asked)}%  n=$asked\n"
                )
            }
        }
        println(lines)
    }

    /**
     * The sequence of keys a path crosses, deduplicated — what `KeyboardView`
     * still builds as a fallback, and what the decoder used to be given instead
     * of the path.
     *
     * Nearest key centre stands in for the view's rectangular hit test. The
     * grid is regular, so the two agree everywhere except within a hair of a
     * key boundary, and a path that close to a boundary is ambiguous on a real
     * device too.
     */
    private fun crossed(path: FloatArray, prox: KeyProximity): String {
        val sb = StringBuilder()
        var i = 0
        while (i < path.size) {
            val ch = nearestKey(path[i], path[i + 1], prox)
            if (ch != null && (sb.isEmpty() || sb[sb.length - 1] != ch)) sb.append(ch)
            i += 2
        }
        return sb.toString()
    }

    private fun nearestKey(x: Float, y: Float, prox: KeyProximity): Char? {
        var best: Char? = null
        var bestD = Float.MAX_VALUE
        for (ch in prox.letters()) {
            val kx = prox.gridX(ch) ?: continue
            val ky = prox.gridY(ch) ?: continue
            val d = hypot(kx - x, ky - y)
            if (d < bestD) { bestD = d; best = ch }
        }
        return best
    }

    private fun isSubsequence(needle: String, hay: String): Boolean {
        var i = 0
        for (ch in hay) if (i < needle.length && needle[i] == ch) i++
        return i == needle.length
    }

    private companion object {
        /** Under the worst arm measured, with room for corpus noise. */
        const val GLIDE_TOP1_FLOOR = 0.60
    }

    // ---- Two languages at once ---------------------------------------------

    private fun engineFor(vararg langs: String): SuggestionEngine {
        val files = HashMap<String, String>()
        for (l in langs) {
            files["dictionaries/$l.txt"] = File(assets(), "dictionaries/$l.txt").readText()
            files["predictions/$l.txt"] = File(assets(), "predictions/$l.txt").readText()
        }
        return SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
    }

    /**
     * Swiping [words] on [layoutLang]'s layout, over all four hands.
     *
     * The layout stays the primary language's throughout, because that is what
     * a bilingual user is looking at: they do not switch layouts to type one
     * word of the other language, which is the whole point of the feature.
     * Letters the layout does not draw are folded onto the key that hosts them,
     * by the same fold the decoder itself uses.
     */
    private fun measureBilingual(
        engine: SuggestionEngine,
        layoutLang: String,
        locale: Locale,
        words: List<String>,
        altLang: String? = null,
        altLocale: Locale? = null
    ): Score {
        val prox = KeyProximity.forLang(layoutLang)
        var asked = 0
        var t1 = 0
        var t4 = 0
        for (hand in Hand.values()) {
            val rnd = Random(seed = 20260825 + hand.ordinal)
            for (w in words) {
                val pts = path(w, hand, prox, rnd) ?: continue
                val gp = GlidePath.of(pts, prox) ?: continue
                asked++
                val offered = engine.glideFor(
                    gp, layoutLang, locale, personalized = false,
                    altLang = altLang, altLocale = altLocale
                )
                if (offered.firstOrNull() == w) t1++
                if (offered.contains(w)) t4++
            }
        }
        return Score(
            top1 = if (asked == 0) 0.0 else t1.toDouble() / asked,
            offered = if (asked == 0) 0.0 else t4.toDouble() / asked,
            asked = asked
        )
    }

    /**
     * A word of the user's *other* language, swiped without switching layouts.
     *
     * This was the last thing in the keyboard a second language did not reach.
     * Tapping has consulted both dictionaries for a long time -- that is what
     * makes "merhab" offer "Merhaba" on the English layout -- but the glide
     * decoder asked the primary dictionary alone, so the same word could be
     * tapped and not swiped. A bilingual user got a feature that quietly
     * stopped working on half of what they typed.
     *
     * Nothing about a swipe belongs to a language. It is a shape over the keys
     * that are drawn, and the decoder already folds the letters no layout draws
     * onto the keys that host them -- the fix Greek needed, which turns out to
     * be exactly what a Turkish word needs on an English layout.
     *
     * Measured over 400 words per language and all four hands (1600 paths per
     * cell), before and after the second dictionary is consulted:
     *
     *                                  before        after
     *     en primary, swiping tr        1%/3%       63%/85%
     *     tr primary, swiping en       27%/53%      74%/95%
     *
     * The English figure was not low, it was zero-shaped: 1% top-1 is what the
     * handful of Turkish words that are also English words gets you. The
     * Turkish column starts higher for a reason worth knowing -- the Turkish
     * list is built from a subtitle corpus and simply *contains* a lot of
     * English, the same contamination [BilingualTest] had to derive its fixture
     * around -- and that inflated baseline is why the English arm is the honest
     * one to quote.
     */
    @Test
    fun `a word in the second language can be swiped`() {
        val en = Locale.ENGLISH
        val tr = Locale.forLanguageTag("tr")
        val both = engineFor("en", "tr")

        val trOnEn = measureBilingual(both, "en", en, sample("tr", 400), "tr", tr)
        val enOnTr = measureBilingual(both, "tr", tr, sample("en", 400), "en", en)
        val lines = "en primary, swiping tr: ${trOnEn.pct()}\n" +
            "tr primary, swiping en: ${enOnTr.pct()}"

        // Both floors sit well under what was measured and well over what the
        // primary dictionary alone could reach (3% and 53% offered), so this
        // fails loudly if the second dictionary stops being consulted, and
        // does not fail for corpus noise.
        assertTrue(
            "a Turkish word swiped on the English layout is no longer offered.\n$lines",
            trOnEn.offered >= 0.75 && trOnEn.top1 >= 0.55
        )
        assertTrue(
            "an English word swiped on the Turkish layout is no longer offered.\n$lines",
            enOnTr.offered >= 0.88 && enOnTr.top1 >= 0.65
        )
    }

    /**
     * What the second language costs the first.
     *
     * It is not free, and pretending otherwise would be the easy mistake here:
     * every word of the other language is one more candidate the right one has
     * to beat. Same corpus as above, primary language swiped:
     *
     *                     one language    two languages
     *     swiping en        75%/96%          71%/94%
     *     swiping tr        79%/96%          75%/94%
     *
     * Four points of top-1 and two of top-3 -- and the word stays reachable in
     * the strip either way. That is the price of the other half of a user's
     * vocabulary working at all, and it is only paid by someone who asked for
     * two languages. It is also the worst case rather than the typical one: the
     * service picks which dictionary is primary from what the user has actually
     * been typing (`altBoost`), so a run of words in one language moves that
     * language into the primary slot and out of this table.
     *
     * **The discount was swept here and left alone.** An extra penalty in log
     * space -- 0.25 through 3.0 -- buys the primary back a point or two and
     * costs the second language more than it gains, monotonically, from the
     * very first step: summed over all four arms it is highest with no extra
     * penalty at all (283.5) and falls away without ever rising (283.0, 282.0,
     * 280.6, 279.8, ...). A narrower sweep over 120 words showed a flat tie
     * across the first three steps, which is what a sample that size shows for
     * a difference of three words; widening it to 1600 paths per cell resolved
     * it. `ALT_WEIGHT` translated into log space is the whole of the discount.
     */
    @Test
    fun `the second language costs the first only a little`() {
        val en = Locale.ENGLISH
        val tr = Locale.forLanguageTag("tr")
        val both = engineFor("en", "tr")
        val enWords = sample("en", 400)
        val trWords = sample("tr", 400)

        val enAlone = measureBilingual(both, "en", en, enWords)
        val enWithTr = measureBilingual(both, "en", en, enWords, "tr", tr)
        val trAlone = measureBilingual(both, "tr", tr, trWords)
        val trWithEn = measureBilingual(both, "tr", tr, trWords, "en", en)
        val lines = "swiping en: ${enAlone.pct()} alone, ${enWithTr.pct()} with tr\n" +
            "swiping tr: ${trAlone.pct()} alone, ${trWithEn.pct()} with en"

        assertTrue(
            "a second language now costs the primary more of its top slot.\n$lines",
            enAlone.top1 - enWithTr.top1 <= 0.07 &&
                trAlone.top1 - trWithEn.top1 <= 0.07
        )
        assertTrue(
            "a second language now pushes primary words out of the strip.\n$lines",
            enAlone.offered - enWithTr.offered <= 0.04 &&
                trAlone.offered - trWithEn.offered <= 0.04
        )
    }

    /**
     * One language enabled decodes exactly as it did before any of this.
     *
     * The invariance that says the change does only what it claims, and the
     * same one the diacritic fold was held to. A user with a single language
     * passes `altLang = null`, so every candidate comes from the one dictionary
     * through the path it always took -- these two measurements are not merely
     * close, they are the same number, and the comparison is written as
     * equality to keep it that way.
     */
    @Test
    fun `a single language is untouched by the second-language path`() {
        val en = Locale.ENGLISH
        val words = sample("en", 400)
        val alone = measureBilingual(engineFor("en"), "en", en, words)
        val secondLoaded = measureBilingual(engineFor("en", "tr"), "en", en, words)
        assertTrue(
            "loading a second dictionary changed monolingual gliding: " +
                "${alone.pct()} vs ${secondLoaded.pct()}",
            alone.top1 == secondLoaded.top1 && alone.offered == secondLoaded.offered
        )
    }
}
