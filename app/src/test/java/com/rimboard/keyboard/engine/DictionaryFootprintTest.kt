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
 * What was left after that was `String` object overhead: about 56 bytes of
 * header and pointer per word to carry eight bytes of text. The words are now
 * concatenated into one `CharArray` with an offset index, so a word is a range
 * rather than an object, and only the handful that survive a scan are ever
 * built.
 *
 *     bytes per English word   107  ->  64  ->  31
 *     en  30.6 MB  ->  18.4  ->   9.1        de  22.2  ->  13.1  ->  6.4
 *     tr  25.4 MB  ->  16.2  ->   7.5        ru  23.4  ->  14.3  ->  6.4
 *
 * The second step made keystrokes *faster* as well, which was not the point of
 * it: worst p99 went from 1.6-2.1 ms to 0.8-1.0. A scan of a hundred thousand
 * candidates now walks one contiguous array instead of chasing a pointer per
 * word, and the correction scan compares in place rather than through objects.
 *
 * The diacritic index was flattened the same way afterwards, which is what that
 * column had been printed to reveal: it briefly made Turkish the largest
 * language despite having a third fewer words than English, and it cost two
 * megabytes of Turkish and none at all of English.
 *
 * ## All twenty-two, 2026-08-30
 *
 * Measured on four languages and reasoned about as though that settled it. It
 * does now: **English is the largest at 9.1 MB and nothing else reaches eight**,
 * so the ceiling below is a real bound rather than a bound on the four that
 * happened to be listed.
 *
 *     el 7.6   hu 7.5   tr 7.5   pl 6.8   sv 6.6   es 6.5   fi 6.5   uk 6.5
 *     de 6.4   ru 6.4   da 6.4   no 6.4   it 6.3   nl 6.3   cs 6.0   sk 6.0
 *     ro 5.6   fr 5.6   pt 5.5   hr 5.5   id 5.2
 *
 * The diacritic-index column now says something the four could not. Greek
 * holds 52,131 folded forms, half again as many as Turkish's 31,475, with
 * Hungarian at 35,571 and Slovak at 31,779 -- so the structure that briefly
 * made Turkish the largest language would have made *Greek* the largest, and
 * by more. It is flat now and Greek costs 7.6 MB, which is the whole point of
 * having flattened it.
 *
 * The two languages carrying no accented words at all, English and Indonesian,
 * hold an index of zero, and Indonesian is the cheapest language that ships at
 * 5.2 MB. Nothing here is anomalous; that is the result.
 *
 * English at 9.1 MB is now close to the floor of this design: about 5 MB of it
 * is the characters themselves, and the rest is one offset and one frequency
 * per word. Storing Latin-1 languages one byte to a character would save
 * another two and a half, at the price of a branch in every accessor and no
 * benefit to Cyrillic or Greek. That is where this stops being worth it.
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
        val langs = com.rimboard.keyboard.model.Languages.codes
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
         * English measures 9.1 MB and is the largest -- checked against all
         * twenty-two now rather than the four this test used to load, with
         * Greek the next heaviest at 7.6. Twelve is close enough to catch
         * something being added back and loose enough not to fail on a heap
         * that settled differently.
         */
        const val PER_LANGUAGE_CEILING_MB = 12.0
    }
}
