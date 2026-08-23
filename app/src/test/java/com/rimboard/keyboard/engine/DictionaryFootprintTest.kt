package com.rimboard.keyboard.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * How much memory a loaded language costs.
 *
 * An input method is the lowest-priority process on the device that the user
 * can still see. When it is killed the keyboard vanishes mid-sentence, which is
 * the worst failure this app has, and the only defence is not to be the biggest
 * thing in the running-apps list. Two dictionaries and a spell checker are an
 * ordinary configuration, and until this file nothing had said what that costs.
 *
 * ## Reading these figures
 *
 * Measured by settling the heap, loading a dictionary, settling again and
 * taking the difference. That is crude — a JVM heap does not partition neatly
 * and the number moves a little run to run — so the figure is a size, not a
 * measurement to three digits. It is also a *desktop* JVM: Android's object
 * headers are smaller, so the real figure on a phone is somewhat lower. What it
 * is good for is the shape: what a language costs, what dominates it, and
 * whether a change made it worse.
 *
 * ## What this found
 *
 * English cost 30.6 MB of heap to hold a 3.2 MB file -- 107 bytes a word, for
 * words averaging eight letters. Two languages and the spell checker came to
 * about ninety.
 *
 * Two fifths of it was a `HashSet<String>` of every word in the language,
 * built to answer membership in one step, sitting beside a sorted array that
 * answers the same question in eighteen comparisons. Removing it took English
 * to 18.4 MB and cost about 0.2 ms of p99 keystroke latency, which is inside
 * the run-to-run spread of [StripLatencyTest] and well under its ceiling.
 *
 *     en  30.6 MB -> 18.4 MB      de  22.2 -> 13.1
 *     tr  25.4 MB -> 16.2 MB      ru  23.4 -> 14.3
 *
 * What is left is dominated by `String` object overhead: about 56 bytes of
 * header and pointer per word to carry eight bytes of text. Concatenating the
 * words into one char array with an offset index would take it to roughly 24
 * bytes a word, which is the obvious next thing and a much larger change than
 * deleting a redundant index.
 *
 * The diacritic index is printed beside each language because it is the one
 * part of the total that varies by language rather than by word count, and so
 * accounts for most of the difference between two lists of the same size. It
 * is not the remaining bulk: English holds none of it and is the largest.
 */
class DictionaryFootprintTest {

    private fun assets(): File =
        listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }

    private fun settle() {
        for (i in 0 until 4) {
            System.gc()
            Thread.sleep(30)
        }
    }

    private fun used(): Long {
        settle()
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }

    private fun load(lang: String): Dictionary =
        Dictionary(
            File(assets(), "dictionaries/$lang.txt").readText().byteInputStream(),
            null, Locale.forLanguageTag(lang)
        )

    @Test
    fun `what a loaded language costs`() {
        val langs = listOf("en", "tr", "de", "ru")
        val lines = StringBuilder()
        var worstMb = 0.0
        for (lang in langs) {
            val file = File(assets(), "dictionaries/$lang.txt")
            val before = used()
            var d: Dictionary? = load(lang)
            val after = used()
            val mb = (after - before) / 1048576.0
            val words = d!!.size
            lines.append(
                "%-3s %,8d words   %5.1f MB on the heap   %5.1f MB on disk   %,d bytes/word\n"
                    .format(lang, words, mb, file.length() / 1048576.0,
                        ((after - before) / maxOf(1, words)))
            )
            if (mb > worstMb) worstMb = mb
            lines.append("    (diacritic index holds %,d of them)%n".format(d.foldedIndexSize))
            d = null
            // Keep the reference alive across the measurement above.
            if (d != null) throw IllegalStateException()
        }
        println(lines)
        println("Two languages plus the spell checker sharing neither: " +
            "%.0f MB".format(worstMb * 3))

        assertTrue("nothing was measured:\n$lines", worstMb > 0.5)
        assertTrue(
            "a single language now costs more than the ceiling.\n$lines",
            worstMb <= PER_LANGUAGE_CEILING_MB
        )
    }

    private companion object {
        /**
         * Above the largest language measured, with room for a noisy heap.
         *
         * English measures 18.4 MB. Twenty-four is close enough to catch
         * something being added back and loose enough not to fail on a heap
         * that settled differently.
         */
        const val PER_LANGUAGE_CEILING_MB = 24.0
    }
}
