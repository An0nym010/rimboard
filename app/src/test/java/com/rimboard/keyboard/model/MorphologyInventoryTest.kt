package com.rimboard.keyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The counted morphology inventories, and the prose that describes them.
 *
 * Two KDoc paragraphs state how many languages ship a suffix list and how many
 * ship a prefix list, and both were wrong: eighteen and six against twenty and
 * twelve on disk. Neither loader is gated, so every file that exists is live —
 * the numbers had simply been written down once and not looked at again when
 * `derive_suffixes.py` and `derive_prefixes.py` were next run.
 *
 * That is the same failure as three copies of a keystroke table going stale two
 * days apart, and it has the same fix: something that fails when the written
 * number and the shipped reality disagree. This is that something.
 *
 * It is deliberately not clever. Adding a language's inventory is meant to make
 * this fail, so that whoever adds it updates the two sentences that count them.
 */
class MorphologyInventoryTest {

    private fun assets(): File =
        listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }

    private fun count(dir: String): Int =
        File(assets(), dir).list().orEmpty().count { it.endsWith(".txt") }

    @Test
    fun `the documented inventory counts are the shipped ones`() {
        assertEquals(
            "assets/suffixes holds a different number of languages than the" +
                " KDoc on Morphology and SuggestionEngine.prefixesFor say." +
                " Update both sentences, then this number.",
            SUFFIX_LANGUAGES, count("suffixes")
        )
        assertEquals(
            "assets/prefixes holds a different number of languages than the" +
                " KDoc on SuggestionEngine.prefixesFor says. Update that" +
                " sentence, then this number.",
            PREFIX_LANGUAGES, count("prefixes")
        )
    }

    /**
     * Every language with an inventory has a dictionary to walk it against.
     *
     * An inventory for a language that ships no word list is inert: the walk
     * peels endings and asks whether the remainder is a known word, and with no
     * dictionary the answer is always no. It would be a silent no-op rather
     * than a failure, which is why it is worth asserting.
     */
    @Test
    fun `every inventory belongs to a language that ships a dictionary`() {
        val shipped = File(assets(), "dictionaries").list().orEmpty()
            .map { it.removeSuffix(".txt") }.toSet()
        for (dir in listOf("suffixes", "prefixes")) {
            val orphans = File(assets(), dir).list().orEmpty()
                .map { it.removeSuffix(".txt") }
                .filter { it !in shipped }
            assertTrue("$dir has inventories for languages with no dictionary: $orphans",
                orphans.isEmpty())
        }
    }

    private companion object {
        /** Languages with a counted ending inventory. See `tools/derive_suffixes.py`. */
        const val SUFFIX_LANGUAGES = 20

        /**
         * Languages with a counted prefix inventory.
         *
         * Fewer, and the gap is a measurement rather than an unfinished job —
         * see the note on `SuggestionEngine.prefixesFor`.
         */
        const val PREFIX_LANGUAGES = 12
    }
}
