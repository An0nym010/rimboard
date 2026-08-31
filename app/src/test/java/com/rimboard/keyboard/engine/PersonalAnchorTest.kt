package com.rimboard.keyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.ln

/**
 * How much room a learned word has to win by, in each shipped list.
 *
 * The glide decoder scores a word the user has typed as
 * `PERSONAL_GLIDE_LN_FREQ + ln(count + 1) - shape`, and that anchor is a flat
 * 7.0. It was chosen as a percentile of English — `ln(freq + 1)` is 2.83 at the
 * median English word and 9.51 at the 99th, so 7.0 sits at the 94.5th and beats
 * the long tail without touching the function words.
 *
 * Every one of those numbers is English's. The same 7.0 lands at the **99.79th
 * percentile of Ukrainian**, whose corpus is 4.7 million tokens against
 * English's 728 million. Above that percentile there is almost nothing left: a
 * word the user typed twice outranks nearly the whole dictionary on frequency,
 * which is close to the "add a billion to the score" rule the anchor replaced.
 *
 * **This test does not assert that the anchor is right.** Replacing it with a
 * per-list percentile was built and measured, and it loses: it buys Ukrainian
 * 2.3 points of dictionary-word accuracy and costs it 30 points of
 * learned-word recall, with the same trade in five more languages. The numbers
 * are on `SuggestionEngine.PERSONAL_GLIDE_LN_FREQ`.
 *
 * What this asserts is the assumption the trade rests on: that there is still
 * a body of words above the anchor for a learned word to have to beat. If a
 * rebuilt list or a new language leaves nothing up there, the flat number stops
 * being a percentile choice and becomes an unconditional override, and the
 * argument for keeping it no longer holds.
 */
class PersonalAnchorTest {

    /** The engine's constant, restated here so a change to it fails this. */
    private val anchor = 7.0

    private fun assets(): File =
        listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }

    private fun freqs(lang: String): IntArray =
        assets().resolve("dictionaries/$lang.txt").readLines()
            .asSequence()
            .mapNotNull {
                val i = it.indexOf(' ')
                if (i <= 0) null else it.substring(i + 1).trim().toIntOrNull()
            }
            .toList().toIntArray()

    private fun langs(): List<String> =
        assets().resolve("dictionaries").listFiles().orEmpty()
            .map { it.name.removeSuffix(".txt") }.sorted()

    @Test
    fun `a learned word still has words above it to beat, in every language`() {
        val thin = ArrayList<String>()
        val report = StringBuilder()
        for (lang in langs()) {
            val fr = freqs(lang)
            assertTrue("$lang: no frequencies parsed", fr.size > 1000)
            val above = fr.count { ln(it + 1.0) > anchor }
            val pct = 100.0 * (fr.size - above) / fr.size
            report.append("%s %.2f/%d  ".format(lang, pct, above))
            // Measured 2026-08-31: Ukrainian is thinnest at 419 words above
            // the anchor (99.79th percentile of 200,000); English has 16,471.
            // Two hundred is half of Ukrainian's and is a tripwire, not a
            // target — it fires when a list stops having a top for a learned
            // word to be measured against, not when one gets slightly smaller.
            if (above < 200) thin.add("$lang: only $above words above the anchor")
        }
        println("percentile of ln=$anchor, and words above it: $report")
        assertEquals(
            "the flat glide anchor has risen above a whole dictionary, which " +
                "makes it an unconditional override rather than a percentile " +
                "choice. See SuggestionEngine.PERSONAL_GLIDE_LN_FREQ for the " +
                "trade this assumption carries.",
            emptyList<String>(), thin
        )
    }

    /**
     * And the anchor itself has not moved without the note moving with it.
     *
     * The KDoc on the constant carries a measured table of what 7.0 costs and
     * buys in each language. A different number invalidates all of it, so this
     * fails rather than letting the note quietly stop describing the code.
     */
    @Test
    fun `the constant matches the measurement recorded for it`() {
        val src = listOf(
            File("src/main/java/com/rimboard/keyboard/engine/SuggestionEngine.kt"),
            File("app/src/main/java/com/rimboard/keyboard/engine/SuggestionEngine.kt")
        ).first { it.exists() }.readText()
        assertTrue(
            "PERSONAL_GLIDE_LN_FREQ changed; the per-language table on its KDoc " +
                "was measured at 7.0 and needs measuring again.",
            src.contains("PERSONAL_GLIDE_LN_FREQ = $anchor")
        )
        assertTrue(
            "the KDoc no longer records what the flat anchor costs the smaller " +
                "corpora; that measurement is the reason it is still flat.",
            src.contains("uk 99.79")
        )
    }
}
