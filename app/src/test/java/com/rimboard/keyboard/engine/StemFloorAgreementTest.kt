package com.rimboard.keyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The engine and the deriver must mean the same thing by "stem".
 *
 * [Dictionary.stemFloorFor] decides how often a word must occur before the
 * morphology walk will let it vouch for a longer one. `derive_suffixes.py`
 * decides the same thing when it counts which endings a language uses. Both
 * hold a flat 500, both scale it down for a named set of languages, and both
 * say in as many words that the other holds the same rule:
 *
 *     Dictionary.stemFloorFor: "Adding a language here is a measurement, not a
 *     preference: it changes what the morphology walk will vouch for."
 *
 *     derive_suffixes.py: "Dictionary.stemFloorFor holds the same rule and the
 *     same set; the two must not disagree about what a stem is."
 *
 * Nothing checked. Two hand-maintained copies of one set, in two languages,
 * with a stated invariant and no test — which is the shape of four separate
 * defects found in this repository on 2026-09-05 alone: a MIN_PAIR the test
 * suite named itself, a strip width that stayed three after the strip became
 * five, inventory counts that drifted from the assets, and engine helpers that
 * loaded neither inventory. Each was correct the day it was written.
 *
 * ## What either direction of drift would do
 *
 * Silent damage, in opposite directions, which is why neither would announce
 * itself:
 *
 *  - **Scaled in the engine and not in the deriver.** The inventory was counted
 *    against a strict idea of a stem and is then walked against a loose one, so
 *    the engine vouches for words on stems the endings were never measured
 *    against. That is the direction that waves typos through.
 *  - **Scaled in the deriver and not in the engine.** Endings counted off rare
 *    stems ship in the asset and can never fire, because the walk will not
 *    accept the stems they were derived from. Dead weight in the APK and a
 *    language quietly getting less morphology than its file claims.
 *
 * The Kotlin side is read through behaviour rather than through the private
 * set, because a test that reads a field agrees with the field rather than with
 * what the engine does.
 */
class StemFloorAgreementTest {

    private fun tools(): File =
        listOf(File("../tools"), File("tools")).first { it.isDirectory }

    private fun deriver(): String = File(tools(), "derive_suffixes.py").readText()

    private fun assets(): File =
        listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }

    /** Languages `derive_suffixes.py` scales the stem floor for. */
    private fun scaledInDeriver(): Set<String> {
        val m = Regex("""SCALED_STEM_LANGS = [{]([^}]*)[}]""").find(deriver())
        assertTrue("SCALED_STEM_LANGS has moved or been renamed in the deriver", m != null)
        return Regex("""([a-z][a-z])""").findAll(m!!.groupValues[1])
            .map { it.groupValues[1] }.toSet()
    }

    /**
     * Languages the engine scales for, asked of the engine.
     *
     * A language scales if its floor moves when its corpus is smaller than the
     * one the flat number was measured on. Anything that does not move is on
     * the flat value whatever the private set says.
     */
    private fun scaledInEngine(): Set<String> {
        val langs = File(assets(), "dictionaries").list().orEmpty()
            .map { it.removeSuffix(".txt") }.sorted()
        val small = 1L                       // far under TURKISH_TOKENS
        return langs.filter { Dictionary.stemFloorFor(it, small) != Dictionary.STEM_MIN_FREQ }
            .toSet()
    }

    @Test
    fun `the engine and the deriver scale the same languages`() {
        val engine = scaledInEngine()
        val script = scaledInDeriver()
        assertEquals(
            "Dictionary.SCALED_STEM_LANGS and derive_suffixes.SCALED_STEM_LANGS have" +
                " come apart. Both decide what may count as a stem: the engine when" +
                " it walks an inventory, the script when it counts one. Scaled in" +
                " the engine alone waves typos through on stems the endings were" +
                " never measured against; scaled in the script alone ships endings" +
                " that can never fire.",
            script, engine
        )
        assertTrue("neither side scales anything, which the notes on both say is wrong",
            engine.isNotEmpty())
    }

    @Test
    fun `both sides hold the same flat floor and the same reference corpus`() {
        val flat = Regex("""(?m)^STEM_MIN_FREQ = ([0-9]+)""").find(deriver())
        assertTrue("STEM_MIN_FREQ has moved or been renamed in the deriver", flat != null)
        assertEquals(
            "the flat stem floor differs between the engine and the deriver",
            Dictionary.STEM_MIN_FREQ, flat!!.groupValues[1].toInt()
        )
        // The scale is a ratio against one corpus, so the two must divide by the
        // same number or "scaled down" means two different amounts.
        val tok = Regex("""(?m)^TURKISH_TOKENS = ([0-9.]+)""").find(deriver())
        assertTrue("TURKISH_TOKENS has moved or been renamed in the deriver", tok != null)
        val scriptTokens = tok!!.groupValues[1].toDouble().toLong()
        // Asked of the engine: at exactly the reference size, a scaled language
        // must land on the flat floor.
        val scaled = scaledInEngine().firstOrNull()
        assertTrue("no scaled language to ask", scaled != null)
        assertEquals(
            "the engine and the deriver divide by different reference corpora," +
                " so \"scaled down\" means two different amounts on each side",
            Dictionary.STEM_MIN_FREQ,
            Dictionary.stemFloorFor(scaled!!, scriptTokens)
        )
    }
}
