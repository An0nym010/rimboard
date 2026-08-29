package com.rimboard.keyboard.model

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A long-press offers a language's own letters commonest first.
 *
 * For nineteen of the twenty-two languages the alphabet does not fit on the
 * keys, so some of its letters are only reachable by holding one — Greek and
 * Czech reach for a hold on one letter in eight. What sits first in that popup
 * is therefore not decoration: it is the one the finger gets to soonest, and
 * every position after it costs travel on a letter the user did not choose to
 * make difficult.
 *
 * The order was a judgement call, and [KeyPopups] says so about its own table:
 * "orderings ... are judgement calls about worldwide usage rather than
 * measurements". For the *worldwide* table that is the only thing available —
 * it has to serve every language at once. For a single language's own letters
 * it is not: the shipped dictionary says exactly how often each one is written.
 *
 * Nine were against the measurement. Slovak offered `ĺ` before `ľ`, which is a
 * letter a hundred times rarer first; Swedish offered `å` before `ä` though `ä`
 * is half again as common; Czech offered `é` before `ě` at a third of its rate.
 *
 * The rule here is non-increasing rather than strictly decreasing, because
 * several of these letters are genuinely absent from a language and tie at
 * zero — Finnish `å` is a Swedish loan and Italian `ó` never appears at all.
 * Ties keep whatever order the layout chose.
 */
class PopupOrderTest {

    private fun assets(): File =
        listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }

    /** Share of all letters written in [lang] that each letter is. */
    private fun letterFrequency(lang: Languages.Lang): Map<Char, Double> {
        val f = HashMap<Char, Double>()
        var total = 0.0
        assets().resolve("dictionaries/${lang.code}.txt").useLines { lines ->
            for (line in lines) {
                val parts = line.split(' ')
                if (parts.size < 2) continue
                val n = parts[1].trim().toDoubleOrNull() ?: continue
                for (c in parts[0].lowercase(lang.locale)) {
                    if (!c.isLetter()) continue
                    f[c] = (f[c] ?: 0.0) + n
                    total += n
                }
            }
        }
        if (total <= 0.0) return emptyMap()
        return f.mapValues { it.value * 100.0 / total }
    }

    /** The letters of [lang] that appear in a popup, in the order offered. */
    private fun nativeRuns(lang: Languages.Lang): List<Pair<Char, List<Char>>> {
        val freq = letterFrequency(lang)
        // "Of this language" means it is written in it at all; the worldwide
        // accent table adds letters no one here uses and those are not what
        // this is about.
        val own = freq.filterValues { it > 0.0 }.keys
        val out = ArrayList<Pair<Char, List<Char>>>()
        for (row in lang.layout(false, false).rows) {
            for (key in row.keys) {
                if (key.type != KeyType.CHARACTER || key.label.length != 1) continue
                val host = key.label[0]
                if (!host.isLetter()) continue
                val run = key.popup
                    .mapNotNull { it.label.singleOrNull() }
                    .filter { it.isLetter() && it in own }
                if (run.size >= 2) out.add(host to run)
            }
        }
        return out
    }

    @Test
    fun `every popup offers this language's letters commonest first`() {
        val wrong = StringBuilder()
        for (lang in Languages.all) {
            val freq = letterFrequency(lang)
            for ((host, run) in nativeRuns(lang)) {
                for (i in 1 until run.size) {
                    val a = freq[run[i - 1]] ?: 0.0
                    val b = freq[run[i]] ?: 0.0
                    if (b > a) {
                        wrong.append(
                            "%n  %s long-press %s offers %s: %s at %.3f%% comes before %s at %.3f%%"
                                .format(lang.code, host, run.joinToString(""),
                                    run[i - 1], a, run[i], b)
                        )
                        break
                    }
                }
            }
        }
        assertTrue(
            "a rarer letter is offered before a commoner one, so the finger " +
                "travels further for the letter that is wanted more:$wrong",
            wrong.isEmpty()
        )
    }

    @Test
    fun `the scan is looking at something`() {
        // Guards the guard: if the popups stopped carrying native letters, or
        // the frequency read broke, the test above would pass by checking
        // nothing at all.
        val runs = Languages.all.sumOf { nativeRuns(it).size }
        assertTrue("only $runs popups carry two or more of their own letters", runs >= 20)
    }
}
