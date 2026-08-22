package com.rimboard.keyboard.model

import com.rimboard.keyboard.engine.SuggestionEngine
import com.rimboard.keyboard.engine.UserData
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.random.Random

/**
 * The Turkish morphology guard, in both columns at once.
 *
 * [Morphology] exists so that ordinary inflected Turkish the 200,000-word list
 * cannot hold — "evlerimizdekiler", "hocalarının" — is not underlined as a
 * misspelling. The cost of that is real: it also vouches for typos, and for a
 * while it vouched for *more* of them than the dictionary did.
 *
 * # Why this test has two floors and not one
 *
 * Every knob here trades one column against the other, and every previous
 * attempt to tune it was reasoned about in one column alone:
 *
 *  - the suffix list is what makes a held final key ("nasılsınn") look like a
 *    valid derivation, which is what [Morphology.doubledLetter] exists for;
 *  - raising the minimum stem length to three rejects a sixth of the typos and
 *    also rejects "evde", which peels to "ev";
 *  - raising [com.rimboard.keyboard.engine.Dictionary.STEM_MIN_FREQ] from 500
 *    to 2000 gives up eleven points of ordinary Turkish for 1.3 points of typo
 *    rejection, because it filters real stems rather than short ones.
 *
 * So the guard is pinned from both sides, and a change that improves one
 * column at the other's expense has to say so out loud by editing a number
 * here.
 *
 * # The two corpora
 *
 * The typos are generated — a one-key slip, using the real key geometry, on
 * the commonest words of the shipped list — so they are reproducible and
 * nothing about them was chosen to make a point.
 *
 * The ordinary Turkish is *not* generated, and that matters: a list of forms
 * built by applying this file's own suffix rules would be a test of whether
 * the code agrees with itself. `fixtures/tr_unlisted.txt` is 500 words taken
 * from the Tatoeba corpus that appear at least three times there and do not
 * appear in the shipped dictionary at all — which is exactly the vocabulary
 * this guard was written to cover.
 */
class MorphologyGuardTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before fun setUp() {
        dir = File.createTempFile("guard", "").let { it.delete(); it.mkdirs(); it }
        userData = UserData.inDir(dir)
    }

    @After fun tearDown() {
        userData.shutdown()
        dir.deleteRecursively()
    }

    private fun path(rel: String): File =
        listOf(File(rel), File("app/$rel")).first { it.exists() }

    @Test
    fun `the guard vouches for ordinary Turkish and for few typos`() {
        val lang = "tr"
        val locale = Locale.forLanguageTag(lang)
        val dictText = path("src/main/assets/dictionaries/tr.txt").readText()
        val preds = path("src/main/assets/predictions/tr.txt").readText()
        val engine = SuggestionEngine.forTesting(userData) { p ->
            when {
                p.endsWith("dictionaries/tr.txt") -> dictText.byteInputStream()
                p.endsWith("predictions/tr.txt") -> preds.byteInputStream()
                else -> null
            }
        }
        val prox = KeyProximity.forLang(lang)
        val rnd = Random(20260822)

        val common = dictText.lineSequence().drop(80)
            .mapNotNull { it.split(' ').firstOrNull() }
            .filter { w -> w.length in 5..9 && w.all { it.isLetter() } }
            .take(1500).toList()
        var slips = 0
        var typosAccepted = 0
        for (w in common) {
            val i = 1 + rnd.nextInt(w.length - 2)
            val c = prox.neighbours(w[i]).firstOrNull() ?: continue
            val typo = w.substring(0, i) + c + w.substring(i + 1)
            if (typo == w) continue
            slips++
            if (engine.acceptedWord(typo, lang, locale)) typosAccepted++
        }

        val ordinary = path("src/test/fixtures/tr_unlisted.txt")
            .readLines().filter { it.isNotBlank() }
        val ordinaryAccepted = ordinary.count { engine.acceptedWord(it, lang, locale) }

        val report = "typos accepted %d of %d (%.1f%%), ordinary unlisted Turkish %d of %d (%.1f%%)"
            .format(
                typosAccepted, slips, 100.0 * typosAccepted / slips,
                ordinaryAccepted, ordinary.size, 100.0 * ordinaryAccepted / ordinary.size
            )
        println(report)

        // Measured 2026-08-22: 4.5% typos, 33.6% ordinary.
        //
        // The typo ceiling is 5% and that is deliberately tight — seven more
        // accepted typos trip it. It has to sit between the measured 4.5% and
        // the 5.5% this guard scored before TR_SHORT_ROOTS existed, or
        // deleting that list would leave the test green. The ordinary floor
        // has the loose end of the trade instead, at 28% against 33.6%,
        // because that column moves with the dictionary and the corpus while
        // the typo column mostly moves with this file.
        assertTrue(
            "the guard is waving typos through: $report",
            typosAccepted * 100 <= slips * 5
        )
        assertTrue(
            "the guard has stopped covering ordinary Turkish, which is the " +
                "only reason it exists: $report",
            ordinaryAccepted * 100 >= ordinary.size * 28
        )
    }
}
