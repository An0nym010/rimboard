package com.rimboard.keyboard.model

import com.rimboard.keyboard.engine.SuggestionEngine
import com.rimboard.keyboard.engine.UserData
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * What accepting closed compounds would cost each language, re-measured.
 *
 * [Compounds] carries a table deciding which languages get the feature, one
 * row per candidate: the share of missing words it would accept against the
 * share of one-key typos it would newly wave through. German shipped on it and
 * five languages were refused on it.
 *
 * The table could not be re-asked. Every route to the split runs through
 * [Compounds.writesClosed], which answers no for exactly the languages the
 * question is about, so the numbers had to be trusted rather than checked.
 * [Compounds.splitParts] exists so they can be checked, and this is the check.
 *
 * ## Method
 *
 * The engine's own pieces throughout, because a cost measured with anything
 * else is a measurement of the difference between two methods. Take the
 * commonest words of at least [Compounds.MIN_PART] * 2 letters; mistype each
 * one once onto a physically adjacent key, using the layout that language
 * really draws; drop any typo that is already a word, since the dictionary
 * accepts those today and this feature is not why; and count what share of the
 * rest [Compounds.splitParts] would newly accept, at the same
 * `Dictionary.stemMinFreq` the engine passes it.
 *
 * ## Measured 2026-09-05, and it disagrees with the table
 *
 * ```
 *            recorded   here
 *     de       0.5%     0.73%     ships the feature
 *     fi       0.7%     0.45%
 *     nl       1.3%     1.10%
 *     da       1.6%     0.53%
 *     tr       2.6%     1.13%
 *     en       1.6%     1.79%     where accepting "alot" is the bug
 * ```
 *
 * The magnitudes agree — everything is a fraction of a per cent to about two —
 * and Dutch and English land close. **German and Finnish come out the other way
 * round.** The recorded table has Finnish costing 40% more than German; this
 * has it costing a third less, which matters because German is the only
 * language whose cost the project has ever accepted, and so the only bar there
 * is.
 *
 * The original script is not in the repository, so its word sample and length
 * floor are unknown and either moves a number like this. Four readings of
 * "one-key typos" were therefore tried, varying only which words get mistyped:
 * length floor 8 or none, top 20,000 or 200,000 or all. **All four put German
 * above Finnish by about half again**, and one of them — any length, top
 * 20,000 — lands German at 0.45% against a recorded 0.5%, which is as close as
 * an unspecified method can be validated. None comes near Finnish's 0.7%.
 *
 * That is not proof the recorded figure is wrong. It is a method that hits the
 * one number it can be checked against and misses the other in the same
 * direction under every sampling, which is worth more than a single
 * measurement and less than a reproduction.
 *
 * It asserts only that German — the shipped case, the one row that is not
 * hypothetical — stays under a bar the project has already accepted in
 * practice, so that a change making compound acceptance wildly more permissive
 * fails here rather than in a user's Finnish.
 *
 * ## Why anyone cares
 *
 * `StripAccuracyTest.what the fixture's selection rule costs` measures the
 * other side on an honest fixture, and the two languages come out level:
 * enabling compounds is worth **+0.18 points** of keystroke savings in German
 * and **+0.15 in Finnish**, with words the strip can never offer at all
 * dropping 0.90%→0.51% and 3.18%→2.69%. The recorded table's 24.4% against
 * 8.5% does not survive contact with that, because it is a share of a base:
 * German is missing 0.8% of its tokens and Finnish 3.1%, and the two effects
 * very nearly cancel.
 *
 * So Finnish gets 83% of German's benefit for 62% of its cost, and has the
 * worst never-offered rate of any language that ships. On the numbers the case
 * is better than the one already accepted.
 *
 * Nothing here changes [Compounds.writesClosed] anyway, and the reason is not a
 * number: every figure above says the strip would offer the word more often,
 * and none says the words it offers are ones a Finn would want. No measurement
 * in this file can answer that.
 */
class CompoundCostTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-cost", "").let { it.delete(); it.mkdirs(); it }
        userData = UserData.inDir(dir)
    }

    @After
    fun tearDown() {
        userData.shutdown()
        dir.deleteRecursively()
    }

    private fun assets(): File =
        listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }

    @Test
    fun `what enabling closed compounds would newly accept`() {
        val report = StringBuilder()
        var german = -1.0
        for (lang in LANGS) {
            val file = File(assets(), "dictionaries/$lang.txt")
            if (!file.isFile) continue
            val locale = Locale.forLanguageTag(lang)
            val files = listOf("dictionaries/$lang.txt", "predictions/$lang.txt")
                .associateWith { File(assets(), it).readText() }
            val engine = SuggestionEngine.forTesting(userData) { p ->
                files[p]?.byteInputStream()
            }
            val dict = engine.dictionary(lang, locale)
            val prox = KeyProximity.forLang(lang)
            val words = file.readLines().asSequence()
                .mapNotNull { it.trim().split(' ').firstOrNull() }
                .filter { it.length >= Compounds.MIN_PART * 2 }
                .take(WORDS)
                .toList()
            var tried = 0
            var accepted = 0
            for (w in words) for (i in w.indices) for (n in prox.neighbours(w[i])) {
                if (n == w[i]) continue
                val typo = w.substring(0, i) + n + w.substring(i + 1)
                if (dict.contains(typo)) continue
                tried++
                if (Compounds.splitParts(typo, dict.stemMinFreq, Compounds.linkingS(lang)) { dict.frequency(it) } != null) {
                    accepted++
                }
            }
            val pct = 100.0 * accepted / maxOf(tried, 1)
            if (lang == "de") german = pct
            report.append(
                "    %-3s %6d words  %8d typos  %6d newly accepted  %.2f%%%s%n"
                    .format(lang, words.size, tried, accepted, pct,
                        if (Compounds.writesClosed(lang)) "   <- ships" else "")
            )
        }
        println(report)
        assertTrue("no dictionaries found", german >= 0.0)
        // The only row that is not hypothetical. German ships this, so whatever
        // it costs is what the project has accepted; a change that made the
        // split markedly freer would show up here first.
        assertTrue(
            "German compound acceptance has risen to %.2f%%, over the %.2f%%"
                .format(german, GERMAN_CEILING) +
                " this has been measured at. That is the cost the whole" +
                " language table is calibrated against:" +
                System.lineSeparator() + report,
            german <= GERMAN_CEILING
        )
    }

    private companion object {
        /** Recorded in [Compounds]' table, plus German which ships. */
        val LANGS = listOf("de", "fi", "nl", "da", "tr", "en")

        /** Commonest words tried per language. */
        const val WORDS = 20000

        /**
         * German measures 0.73%. One and a half points of room, which is
         * generous for a dictionary rebuild and nowhere near the doubling that
         * a loosened [Compounds.MIN_PART] or frequency floor would produce.
         */
        const val GERMAN_CEILING = 1.1
    }
}
