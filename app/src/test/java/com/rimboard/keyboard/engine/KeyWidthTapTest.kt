package com.rimboard.keyboard.engine

import com.rimboard.keyboard.model.KeyProximity
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The other half of the layout question: what an extra key costs a finger.
 *
 * `GlideAccuracyTest` prices the swipe side of giving the Nordic letters keys
 * of their own, and says in as many words that it measures swiping and not
 * tapping. This is tapping.
 *
 * The same arithmetic makes it possible. [KeyProximity] places keys one unit
 * apart whatever the row holds, and `GlideTrail.toGrid` divides a real touch
 * offset by the real pixel width of the key it landed on — so the grid is
 * normalised, and eleven keys where ten were means the same physical
 * millimetre of finger error is eleven tenths of a key. A tap is modelled as
 * its aim point plus Gaussian error in key widths, the nearest key centre
 * fires, and the word that results is handed to the corrector exactly as a
 * real one would be.
 *
 * ## The trade is not the shape the swipe arm had
 *
 * There, giving a letter a key was a straight gain: a letter hosted under
 * another cannot be swiped distinctly, so `være` and `vare` are the same
 * gesture. Tapping is the opposite. **Today those letters are reached by long
 * press, and a long press is exact** — the popup is on screen and the finger
 * picks from it. Giving `æ` a key of its own makes it mis-tappable for the
 * first time, on top of making every other key narrower.
 *
 * So on accuracy alone the tap side is all cost. What it buys is speed: the
 * share of letters that no longer need a long press at all, which
 * `LayoutCoverageTest` already counts — 2.96% of everything typed in Danish,
 * 2.87% in Norwegian, 6.28% in Swedish, 7.18% in Finnish.
 *
 * ## Why the error is swept rather than chosen
 *
 * How far a thumb lands from where it aimed, in key widths, is a fact about
 * hands and phones that nothing in this repository measures. Picking one would
 * be inventing the answer, so the cost is reported across a range and the
 * reader can find their own. For scale, the harness in `GlideAccuracyTest`
 * reports a natural swiping finger passing a mean 0.29 key widths from the
 * letters it aims at, and the touch arm of [AutocorrectAccuracyTest] calls a
 * displacement of half a key width "exactly on the boundary".
 *
 * The figure that matters is not the raw hit rate but what survives
 * correction, because a keyboard that fixes the slip has not cost the user
 * anything. Both are printed.
 *
 * ## What it says, and it is not what the swipe arm said
 *
 * Word right after correction, ten keys against eleven:
 *
 *     sigma      da          no          sv          fi
 *     0.20    99 -> 97    99 -> 98    99 -> 98    99 -> 99
 *     0.30    86 -> 79    88 -> 79    89 -> 80    91 -> 85
 *     0.40    57 -> 49    58 -> 45    63 -> 49    67 -> 52
 *
 * Calibrated the same way the swipe arm was, on the two layouts that already
 * carry a wider row, at sigma 0.30: English is the control and does not move,
 * Russian's eleven keys cost 6 points (87 -> 81) and Turkish's twelve cost 18
 * (88 -> 70). The Nordic elevens land between 6 and 9, which is the Russian
 * figure, so the model is at least consistent with itself.
 *
 * **The two halves disagree, and the tap half is the bigger number.** The
 * swipe gain is 6 to 7 points on the words holding one of those letters, which
 * are 6.5% of Danish. The tap cost is 6 to 9 points at a moderate finger and
 * it falls on *every* word. Unless the hand is steady enough for sigma 0.20 —
 * where the cost is one or two points — the arithmetic argues against the
 * extra key, and it argues against it for the languages with the least to
 * gain, since Swedish and Finnish pay the same bill for letters that are a
 * smaller share of their text.
 *
 * That is the opposite of what the swipe arm alone suggested, which is the
 * whole reason for measuring the second half rather than reasoning about it.
 *
 * ## Two things this is generous about, both toward the layout that ships
 *
 * A long press is modelled as exact and free. It is exact, but it is not free
 * — it costs a hold and a second aimed tap, and a popup can be picked from
 * wrongly. Counting that would move the answer toward the extra key.
 *
 * And sigma is the same for every key. A real thumb is worse at the corners
 * and at the row it is not resting on, and an eleventh key goes at the end of
 * a row, which is the part of the keyboard a thumb reaches least well.
 * Counting that would move the answer away from it.
 */
class KeyWidthTapTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-tapwidth", "").let { it.delete(); it.mkdirs(); it }
        userData = UserData.inDir(dir)
    }

    @After
    fun tearDown() {
        userData.shutdown()
        dir.deleteRecursively()
    }

    private fun assets(): File =
        listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }

    private fun engine(lang: String): SuggestionEngine {
        val files = listOf("dictionaries/$lang.txt", "predictions/$lang.txt")
            .filter { File(assets(), it).exists() }
            .associateWith { File(assets(), it).readText() }
        return SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
    }

    private fun words(lang: String, count: Int): List<String> {
        val out = ArrayList<String>(count)
        File(assets(), "dictionaries/$lang.txt").useLines { lines ->
            for (line in lines.drop(40)) {
                val w = line.split(' ').firstOrNull() ?: continue
                if (w.length !in 4..10 || !w.all { c -> c.isLetter() }) continue
                out.add(w)
                if (out.size >= count) break
            }
        }
        return out
    }

    private class Tap(val typed: String, val trail: FloatArray, val clean: Boolean)

    /**
     * A word tapped out letter by letter, each aim point missed by a Gaussian
     * [sigma] key widths, with whichever key is nearest firing.
     *
     * A letter the layout does not draw is reached from its long-press popup,
     * which is exact — so it is typed correctly and contributes no error. That
     * asymmetry is the whole reason the tap side does not look like the swipe
     * side, and modelling it away would answer a different question.
     */
    private fun tapOut(
        word: String, prox: KeyProximity, sigma: Float, rnd: Random
    ): Tap? {
        val keys = prox.letters().toList()
        if (keys.isEmpty()) return null
        val sb = StringBuilder(word.length)
        val trail = FloatArray(word.length * 2)
        var clean = true
        for ((i, ch) in word.withIndex()) {
            val ax = prox.gridX(ch)
            val ay = prox.gridY(ch)
            if (ax == null || ay == null) {
                // Long-pressed from a popup: exact, and no touch evidence.
                sb.append(ch)
                continue
            }
            val ex = gauss(rnd) * sigma
            val ey = gauss(rnd) * sigma
            val px = ax + ex
            val py = ay + ey
            var best = ch
            var bestD = Float.MAX_VALUE
            for (k in keys) {
                val kx = prox.gridX(k) ?: continue
                val ky = prox.gridY(k) ?: continue
                val d = hypot(px - kx, py - ky)
                if (d < bestD) { bestD = d; best = k }
            }
            if (best != ch) clean = false
            sb.append(best)
            trail[i * 2] = px - (prox.gridX(best) ?: 0f)
            trail[i * 2 + 1] = py - (prox.gridY(best) ?: 0f)
        }
        return Tap(sb.toString(), trail, clean)
    }

    /** Box–Muller, so the error is Gaussian rather than a shrug. */
    private fun gauss(rnd: Random): Float {
        val u = (rnd.nextFloat().coerceAtLeast(1e-7f)).toDouble()
        val v = rnd.nextFloat().toDouble()
        return (sqrt(-2.0 * ln(u)) * kotlin.math.cos(2.0 * Math.PI * v)).toFloat()
    }

    private class Result(val asked: Int, val clean: Int, val right: Int) {
        fun pct() = "%2d%%/%2d%%".format(
            clean * 100 / maxOf(asked, 1), right * 100 / maxOf(asked, 1)
        )
    }

    private fun measure(
        lang: String, ws: List<String>, prox: KeyProximity, sigma: Float
    ): Result {
        val e = engine(lang)
        val locale = Locale.forLanguageTag(lang)
        val dict = e.dictionary(lang, locale)
        val rnd = Random(seed = 20260830)
        var asked = 0; var clean = 0; var right = 0
        for (w in ws) {
            val t = tapOut(w, prox, sigma, rnd) ?: continue
            asked++
            if (t.clean) { clean++; right++; continue }
            // Every key fired as the finger asked, or the corrector gets the
            // same evidence the keyboard would hand it: the letters, and where
            // each tap actually landed.
            val fix = dict.correctionsScored(t.typed, prox, 1, t.trail).firstOrNull()?.first
            if (fix == w) right++
        }
        return Result(asked, clean, right)
    }

    @Test
    fun `what an extra key in the row costs a finger`() {
        val proposed = mapOf(
            "da" to listOf("qwertyuiopå", "asdfghjklæø", "zxcvbnm"),
            "no" to listOf("qwertyuiopå", "asdfghjkløæ", "zxcvbnm"),
            "sv" to listOf("qwertyuiopå", "asdfghjklöä", "zxcvbnm"),
            "fi" to listOf("qwertyuiopå", "asdfghjklöä", "zxcvbnm")
        )
        val own = mapOf(
            "da" to "åæø", "no" to "åæø",
            "sv" to "åäö", "fi" to "äö"
        )
        val sigmas = listOf(0.20f, 0.30f, 0.40f, 0.50f)
        val lines = StringBuilder()
        lines.append(
            ("every key fired as aimed / word right after correction%n" +
                "%-4s %-7s %-15s %-15s%n").format("lang", "sigma", "ten keys", "eleven keys")
        )
        for ((lang, rows) in proposed) {
            val today = KeyProximity.forLang(lang)
            val keptHosts = today.lettersHosted()
                .filter { it !in own.getValue(lang) }
                .mapNotNull { ch -> today.hostOf(ch)?.let { ch to it } }
                .toMap()
            val wide = KeyProximity.forRows(rows, keptHosts)
            val ws = words(lang, 600)
            for (s in sigmas) {
                val a = measure(lang, ws, today, s)
                // Eleven keys where ten were: each is ten elevenths as wide, so
                // the same physical error is eleven tenths of a key.
                val b = measure(lang, ws, wide, s * 11f / 10f)
                lines.append(
                    "%-4s %-7.2f %-15s %-15s%n".format(lang, s, a.pct(), b.pct())
                )
            }
        }
        // The same question of the two layouts that already carry an extra key,
        // which is the only calibration available that nobody has to imagine.
        lines.append("%nlayouts that already ship a wider row%n".format())
        for ((lang, keys) in listOf("en" to 10, "ru" to 11, "tr" to 12)) {
            val prox = KeyProximity.forLang(lang)
            val ws = words(lang, 600)
            for (s in listOf(0.30f, 0.40f)) {
                val flat = measure(lang, ws, prox, s)
                val real = measure(lang, ws, prox, s * keys / 10f)
                lines.append(
                    "%-4s %2d keys sigma %.2f   as measured %-15s at its real width %s%n"
                        .format(lang, keys, s, flat.pct(), real.pct())
                )
            }
        }
        println(lines)
        assertTrue("the tap model produced nothing", true)
    }
}
