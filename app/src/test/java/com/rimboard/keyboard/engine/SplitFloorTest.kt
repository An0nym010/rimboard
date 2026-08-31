package com.rimboard.keyboard.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * The split rule's floor was a flat count, and a count is twenty-two bars.
 *
 * "alot" becomes "a lot" when both halves clear [Dictionary.SPLIT_MIN_FREQ],
 * which was 500 occurrences for every language. English was built from 728
 * million tokens and Ukrainian from 4.7 million, so 500 is 0.69 per million in
 * one list and 106.78 in the other -- the same trap `STEM_MIN_FREQ` documents
 * three times in the same file, one rule along and unfixed.
 *
 * **Ukrainian recovered 32.1% of its missing spaces. Every other language
 * recovered between 57% and 96%.**
 *
 * The measurement below is the feature's own job, done with no curated list:
 * take every adjacent word pair in a language's prose fixture, join them, and
 * ask the keyboard to put the space back. Joins that are themselves real words
 * are skipped, because those are not missing spaces.
 *
 * The cost is measured on the same prose: every distinct word of four letters
 * or more that must *not* be split. It does not move at all --
 * [Dictionary.SPLIT_DOMINANCE] is a ratio and never had this problem, and it
 * is what actually keeps real words whole. The floor was only excluding
 * genuine splits in the corpora too small to reach it.
 */
class SplitFloorTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("splitfloor", "").let { it.delete(); it.mkdirs(); it }
        userData = UserData.inDir(dir)
    }

    @After
    fun tearDown() {
        userData.shutdown()
        dir.deleteRecursively()
    }

    private fun assets(): File =
        listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }

    private fun path(rel: String): File =
        listOf(File(rel), File("app/$rel")).first { it.exists() }

    private fun engine(lang: String): SuggestionEngine {
        val files = listOf("dictionaries/$lang.txt", "predictions/$lang.txt")
            .filter { assets().resolve(it).isFile }
            .associateWith { assets().resolve(it).readText() }
        return SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
    }

    private fun langs(): List<String> =
        assets().resolve("dictionaries").listFiles().orEmpty()
            .map { it.name.removeSuffix(".txt") }.sorted()

    /**
     * Measured 2026-08-31 with the scaled floors. The languages that moved:
     *
     *     uk 32.1 -> 84.1   sk 84.8 -> 96.1   no 84.6 -> 94.3   id 89.9 -> 98.2
     *     fi 85.6 -> 91.0   da 88.8 -> 92.5   sv 91.2 -> 94.1   ru 76.6 -> 78.7
     *
     * The floor is well under every one of those, and well under the twelve
     * that did not move. German sits lowest at 57.6% and is not a floor
     * problem: it writes its compounds closed, so joining two German words
     * often makes a real German word, which this correctly declines to split.
     */
    @Test
    fun `every language can put back a missing space`() {
        val report = StringBuilder()
        val below = ArrayList<String>()
        var totalPairs = 0
        for (lang in langs()) {
            val loc = Locale.forLanguageTag(lang)
            val e = engine(lang)
            val d = e.dictionary(lang, loc)
            val words = Regex("""\p{L}+""")
                .findAll(path("src/test/fixtures/prose_$lang.txt").readText())
                .map { it.value.lowercase(loc) }.toList()
            var pairs = 0
            var recovered = 0
            for (i in 0 until words.size - 1) {
                val a = words[i]
                val b = words[i + 1]
                if (a.length + b.length < 4) continue
                if (d.contains(a + b)) continue
                pairs++
                if (e.splitFor(a + b, lang, loc)?.lowercase(loc) == "$a $b") recovered++
            }
            totalPairs += pairs
            if (pairs < 100) continue
            val pct = 100.0 * recovered / pairs
            report.append("%s %.1f%% (%d/%d, floor %d)  ".format(lang, pct, recovered, pairs, d.splitMinFreq))
            // German is the documented exception; see the note above.
            if (pct < 70.0 && lang != "de") below.add("$lang ${"%.1f".format(pct)}%")
        }
        println("missing spaces put back: $report")
        assertTrue("no pairs to measure from", totalPairs >= 10_000)
        assertEquals(
            "a language recovers far fewer of its own missing spaces than the " +
                "rest, which is what a floor measured against somebody else's " +
                "corpus looks like.",
            emptyList<String>(), below
        )
    }

    /** And the floor is still a floor: real words stay whole. */
    @Test
    fun `real words are not split`() {
        val loud = ArrayList<String>()
        for (lang in langs()) {
            val loc = Locale.forLanguageTag(lang)
            val e = engine(lang)
            val words = Regex("""\p{L}+""")
                .findAll(path("src/test/fixtures/prose_$lang.txt").readText())
                .map { it.value.lowercase(loc) }.filter { it.length >= 4 }
                .distinct().toList()
            if (words.size < 100) continue
            val split = words.count { e.splitFor(it, lang, loc) != null }
            val pct = 100.0 * split / words.size
            // Measured 2026-08-31: 0.00% in eleven languages, 0.77% at worst
            // (Dutch, 4 of 518), and *identical* to what the flat floor scored
            // -- SPLIT_DOMINANCE is what does this work, not the floor.
            if (pct > 2.0) loud.add("$lang ${"%.2f".format(pct)}% ($split/${words.size})")
        }
        assertEquals(
            "the split is being offered on ordinary words of running prose.",
            emptyList<String>(), loud
        )
    }

    /**
     * The floor may fall for a small corpus and must never rise for a big one.
     *
     * Scaling in both directions was tried and measured worse: English's floor
     * becomes 1,693 and its recovery drops from 95.0% to 91.8%, Spanish from
     * 93.1% to 89.2%, French 88.3% to 85.5%. A minimum credibility bar that
     * 500 already clears in a 728-million-token corpus buys nothing by rising.
     */
    @Test
    fun `the floor is scaled down and never up`() {
        val turkish = 215_064_959L
        assertEquals(500, Dictionary.scaledDown(500, turkish))
        assertEquals(500, Dictionary.scaledDown(500, turkish * 4))   // English's size
        assertTrue(Dictionary.scaledDown(500, 4_700_000L) in 2..20)  // Ukrainian's
        assertEquals(1, Dictionary.scaledDown(500, 0L))
        val raised = langs().filter { lang ->
            val d = engine(lang).dictionary(lang, Locale.forLanguageTag(lang))
            d.splitMinFreq > 500 || d.splitSingleMinFreq > 20_000
        }
        assertEquals("a language's floor was raised above the flat value", emptyList<String>(), raised)
    }
}
