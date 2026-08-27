package com.rimboard.keyboard.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "Never suggest or autocorrect to profanity" has to mean the plural too.
 *
 * [SuggestionEngine.isOffensive] is an exact membership test. It is careful
 * about case and about which locale folds the word, and it has no idea that a
 * plural is the same word -- so the lists holding base forms only meant the
 * keyboard went on offering the inflections. Sixty-one of them in English were
 * sitting in the shipped dictionary, ready to be completed or corrected to,
 * with the setting switched on.
 *
 * Fixing it in the matcher was tried and rejected: the suffix inventories in
 * `assets/suffixes/` are derivational -- they contain "the", "you", "land",
 * "town" -- so peeling with them turns ordinary vocabulary into listed words,
 * including one with a corpus frequency of 232,845. A filter that over-blocks
 * common words is worse than one that under-blocks rare ones.
 *
 * So it is data, and `tools/expand_offensive.py` maintains it. This is the
 * ratchet: a form that is (a) a listed word plus a grammatical ending, (b)
 * attested in the shipped dictionary, and (c) no more frequent than the word it
 * derives from must itself be listed. The third condition is what keeps
 * ordinary words out -- a real inflection is rarer than its base, and anything
 * commoner is a different word that merely looks derived.
 *
 * Three languages rather than twelve, chosen for different morphology. The
 * tool owns the rest; the property is the same everywhere.
 */
class OffensiveInflectionTest {

    private val endings = mapOf(
        "en" to listOf("s", "es", "ed", "ing", "er", "ers", "y", "ies"),
        "de" to listOf("e", "en", "er", "es", "n", "s"),
        "tr" to listOf("ler", "lar", "i", "u")
    )

    private fun assets(): File =
        listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }

    private fun listed(lang: String): Set<String> =
        File(assets(), "offensive/$lang.txt").readLines()
            .map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    private fun frequencies(lang: String): Map<String, Int> {
        val out = HashMap<String, Int>()
        File(assets(), "dictionaries/$lang.txt").forEachLine { line ->
            val p = line.split(" ")
            if (p.size >= 2) p[1].toIntOrNull()?.let { out[p[0].lowercase()] = it }
        }
        return out
    }

    @Test
    fun `an attested inflection of a listed word is listed too`() {
        val missing = ArrayList<String>()
        var checked = 0
        for ((lang, sufs) in endings) {
            val off = listed(lang)
            val freq = frequencies(lang)
            for (w in off) {
                val base = freq[w] ?: continue
                for (s in sufs) {
                    val form = w + s
                    val f = freq[form] ?: continue
                    checked++
                    // Rarer than its base, so it is that word in another form
                    // rather than a different word that looks like one.
                    if (f <= base && form !in off) missing.add("$lang:${form.length}-letter form")
                }
            }
        }
        // Guards the guard: a scan that matches nothing reports clean, which
        // looks exactly like a scan that found nothing wrong.
        assertTrue(
            "the scan examined $checked candidate forms -- it has stopped " +
                "finding the lists or the dictionaries",
            checked >= 100
        )
        assertTrue(
            "these are inflections of listed words, present in the shipped " +
                "dictionary and rarer than the word they come from, and the " +
                "filter would offer them: " + missing.joinToString(", "),
            missing.isEmpty()
        )
    }

    @Test
    fun `the lists stay sorted and free of duplicates`() {
        // They are read as a set, so order costs nothing at runtime -- but the
        // file is reviewed by a human, and a word list nobody can scan is a
        // word list nobody checks.
        for (f in File(assets(), "offensive").listFiles().orEmpty().sortedBy { it.name }) {
            val w = f.readLines().map { it.trim() }.filter { it.isNotEmpty() }
            assertTrue("${f.name} is not sorted", w == w.sorted())
            assertTrue("${f.name} has duplicates", w.size == w.toSet().size)
        }
    }
}
