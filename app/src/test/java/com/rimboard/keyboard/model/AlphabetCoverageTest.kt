package com.rimboard.keyboard.model

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every letter a language actually uses must be typable on that language's
 * keyboard, whether on a key or behind a long-press.
 *
 * The expectation is derived, not written down: each bundled dictionary was
 * filtered by a per-language alphabet regex when it was generated, so the
 * letters occurring in its most frequent words are exactly that language's
 * working alphabet. Comparing the two means a layout cannot quietly lose a
 * letter — which for the person typing is not a cosmetic problem, it is a word
 * they cannot write.
 */
class AlphabetCoverageTest {

    private fun assets(): File {
        for (p in listOf("src/main/assets", "app/src/main/assets")) {
            val f = File(p)
            if (f.isDirectory) return f
        }
        throw AssertionError("assets directory not found from ${File(".").absolutePath}")
    }

    /** Every letter reachable on [code]'s main layout, keys and popups alike. */
    private fun reachable(code: String): Set<Char> {
        val lay = Languages.byCode(code).layout(false, true)
        val out = HashSet<Char>()
        fun take(label: String) {
            for (ch in label) if (ch.isLetter()) out.add(ch.lowercaseChar())
        }
        for (row in lay.rows) for (key in row.keys) {
            if (key.type == KeyType.CHARACTER) take(key.label)
            for (p in key.popup) take(p.label)
        }
        return out
    }

    /** Every letter occurring anywhere in [code]'s dictionary. */
    private fun used(code: String): Set<Char> {
        val out = HashSet<Char>()
        File(assets(), "dictionaries/$code.txt").useLines { lines ->
            for (line in lines) {
                val sp = line.indexOf(' ')
                val w = if (sp > 0) line.substring(0, sp) else line
                for (ch in w) if (ch.isLetter()) out.add(ch.lowercaseChar())
            }
        }
        return out
    }

    @Test
    fun `every language can type its own alphabet`() {
        // Reads the lists in full rather than sampling their head. Sampling the
        // top 2000 words was the first version of this, and it passed: Italian
        // and Portuguese each had a letter they could not type that first
        // appeared around rank 10,000. A letter is no less unreachable for
        // being rare, and the suggestion engine ranks over the whole list.
        val problems = ArrayList<String>()
        for (code in Languages.codes) {
            val missing = (used(code) - reachable(code)).sorted()
            if (missing.isNotEmpty()) {
                problems.add("$code cannot type: ${missing.joinToString(" ")}")
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
