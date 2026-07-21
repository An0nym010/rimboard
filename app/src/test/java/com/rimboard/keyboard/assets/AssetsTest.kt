package com.rimboard.keyboard.assets

import com.rimboard.keyboard.model.Languages
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the bundled data files against the registry that decides which
 * languages exist.
 *
 * These were validated by hand as they were written, which is worth nothing
 * once someone edits one. Every failure mode here is silent at runtime: a
 * missing prediction model just means the strip stays empty, a malformed line
 * is skipped by the parser, and a missing offensive list means the filter
 * quietly stops covering that language.
 */
class AssetsTest {

    private val tab = '\t'

    /** Unit tests run from the module directory; tolerate the project root too. */
    private fun assets(): File {
        for (p in listOf("src/main/assets", "app/src/main/assets")) {
            val f = File(p)
            if (f.isDirectory) return f
        }
        throw AssertionError("assets directory not found from ${File(".").absolutePath}")
    }

    private fun lines(f: File) = f.readText().split("\n").filter { it.isNotEmpty() }

    @Test
    fun `every registered language has a dictionary`() {
        val dir = File(assets(), "dictionaries")
        val missing = Languages.codes.filter { !File(dir, "$it.txt").isFile }
        assertTrue("no dictionary for: $missing", missing.isEmpty())
    }

    @Test
    fun `every registered language has an offensive-word list`() {
        // The filter falls back to English, so a missing list is invisible at
        // runtime: that language simply stops being covered natively.
        val dir = File(assets(), "offensive")
        val missing = Languages.codes.filter { !File(dir, "$it.txt").isFile }
        assertTrue("no offensive list for: $missing", missing.isEmpty())
    }

    @Test
    fun `every registered language has a starter prediction model`() {
        // Predictions have no cross-language fallback at all, so a missing file
        // means that language gets nothing until the user's own n-grams build.
        val dir = File(assets(), "predictions")
        val missing = Languages.codes.filter { !File(dir, "$it.txt").isFile }
        assertTrue("no prediction model for: $missing", missing.isEmpty())
    }

    @Test
    fun `offensive lists are lowercase, unique and blank-free`() {
        val problems = ArrayList<String>()
        for (f in File(assets(), "offensive").listFiles().orEmpty().sortedBy { it.name }) {
            val words = lines(f)
            if (words.isEmpty()) problems.add("${f.name}: empty")
            words.forEachIndexed { i, w ->
                if (w != w.trim()) problems.add("${f.name}:${i + 1} has surrounding space")
                if (w != w.lowercase()) problems.add("${f.name}:${i + 1} not lowercase: $w")
            }
            val dupes = words.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            if (dupes.isNotEmpty()) problems.add("${f.name}: duplicates $dupes")
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `prediction models parse as one tab-separated pair per line`() {
        val problems = ArrayList<String>()
        for (f in File(assets(), "predictions").listFiles().orEmpty().sortedBy { it.name }) {
            val keys = ArrayList<String>()
            lines(f).forEachIndexed { i, line ->
                val at = "${f.name}:${i + 1}"
                if (line.count { it == tab } != 1) {
                    // The loader splits on the first tab and drops anything that
                    // does not fit, so a stray tab silently loses the entry.
                    problems.add("$at: expected exactly one tab")
                    return@forEachIndexed
                }
                val (key, targets) = line.split(tab)
                if (key.isBlank()) problems.add("$at: blank key")
                if (key != key.lowercase()) problems.add("$at: key not lowercase: $key")
                if (targets.isBlank()) problems.add("$at: no predictions for '$key'")
                keys.add(key)
            }
            val dupes = keys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            // A duplicate key is dead weight: the map keeps whichever came last.
            if (dupes.isNotEmpty()) problems.add("${f.name}: duplicate keys $dupes")
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `dictionaries are word-then-frequency, ordered by frequency`() {
        val problems = ArrayList<String>()
        for (code in Languages.codes) {
            val f = File(assets(), "dictionaries/$code.txt")
            if (!f.isFile) continue
            // Sampling the head is enough to catch a format or ordering change;
            // reading 22 full dictionaries would make this test cost minutes.
            var last = Int.MAX_VALUE
            f.useLines { seq ->
                seq.take(200).forEachIndexed { i, line ->
                    val sp = line.indexOf(' ')
                    val freq = if (sp > 0) line.substring(sp + 1).trim().toIntOrNull() else null
                    if (freq == null) {
                        problems.add("$code.txt:${i + 1}: expected 'word frequency', got '$line'")
                    } else {
                        if (freq > last) problems.add("$code.txt:${i + 1}: frequency rises")
                        last = freq
                    }
                }
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
