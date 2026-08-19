package com.rimboard.keyboard.engine

import com.rimboard.keyboard.model.KeyProximity
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

    /** The engine, backed by the dictionary this app actually ships. */
    private fun realEngine(lang: String): SuggestionEngine {
        val path = "dictionaries/$lang.txt"
        val text = File(assets(), path).readText()
        return SuggestionEngine.forTesting(userData) { p ->
            if (p == path) text.byteInputStream() else null
        }
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

    private enum class Slip { NEIGHBOUR, DOUBLED, DROPPED, SWAPPED }

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
        }?.takeIf { it != word }
    }

    private fun measure(lang: String, locale: Locale, words: List<String>): Map<Slip, Double> {
        val engine = realEngine(lang)
        val prox = KeyProximity.forLang(lang)
        val out = LinkedHashMap<Slip, Double>()
        for (slip in Slip.values()) {
            // Seeded per kind, so the corpus is the same on every run and on
            // every machine. A benchmark that moves on its own measures noise.
            val rnd = Random(seed = 20260819 + slip.ordinal)
            var asked = 0
            var right = 0
            for (w in words) {
                val typo = damage(w, slip, prox, rnd) ?: continue
                // Skip damage that lands on another real word: "hat" from
                // "hate" is not a typo the engine should be scored on.
                if (engine.acceptedWord(typo, lang, locale)) continue
                asked++
                if (engine.correctionCandidates(typo, lang, locale, limit = 1)
                        .firstOrNull() == w
                ) right++
            }
            out[slip] = if (asked == 0) 0.0 else right.toDouble() / asked
        }
        return out
    }

    private fun report(lang: String, scores: Map<Slip, Double>): String =
        scores.entries.joinToString(", ") { (k, v) -> "$k ${"%.0f".format(v * 100)}%" }
            .let { "$lang: $it" }

    @Test
    fun `the autocorrect is right often enough, and says where it is not`() {
        val results = listOf(
            "en" to Locale.ENGLISH,
            "tr" to Locale.forLanguageTag("tr")
        ).map { (lang, locale) -> lang to measure(lang, locale, sample(lang, 70)) }

        val lines = results.joinToString("\n") { (lang, s) -> report(lang, s) }

        println(lines)

        // A floor, not a target, and set from what the engine measures
        // rather than from a wish. On the day it was written:
        //
        //   en: neighbour 100%, doubled 100%, dropped 96%, swapped 100%
        //   tr: neighbour  96%, doubled  91%, dropped 88%, swapped 100%
        //
        // 0.80 sits eight points under the worst of those, which leaves room
        // for ordinary tuning to move things about and still catches anything
        // falling off a cliff. Raise it when the engine earns it. Lowering it
        // to make a change pass is the one use this must never be put to.
        val worst = results.flatMap { it.second.values }.min()
        assertTrue(
            "autocorrect accuracy has fallen below the floor.\n$lines",
            worst >= 0.80
        )
        // Guards the guard: a corpus that generated nothing would score a
        // perfect zero-of-zero and pass every assertion above.
        assertTrue("the corpus generated nothing:\n$lines",
            results.all { r -> r.second.values.all { it > 0.0 } })
    }
}
