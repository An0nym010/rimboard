package com.rimboard.keyboard.assets

import com.rimboard.keyboard.engine.UserData
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
    fun `no data file belongs to a language the app does not support`() {
        // The other direction, which nothing checked. tools/fetch_dictionaries.py
        // regenerates every language it has a pattern for, and it carried one —
        // Azerbaijani — that the registry never listed, so a no-argument run
        // would have written a dictionary the app can never read. Nothing would
        // have failed: it would just have ridden along in the APK, and the
        // dictionaries are 40 MB of it already.
        val problems = ArrayList<String>()
        for (kind in listOf("dictionaries", "offensive", "predictions")) {
            val files = File(assets(), kind).listFiles().orEmpty()
                .filter { it.name.endsWith(".txt") }
                .map { it.name.removeSuffix(".txt") }
            for (code in files.sorted()) {
                if (code !in Languages.codes) problems.add("$kind/$code.txt")
            }
        }
        assertTrue("data files for unsupported languages: $problems", problems.isEmpty())
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
    fun `every language has a row of sentence openers`() {
        // The empty context is a context. predictions() keys the start of a
        // sentence under UserData.START, and both the strip and the spell
        // checker rank the first word of a sentence against that row — so
        // without one, the word most likely to be capitalised, and the one a
        // reader sees first, is ranked on raw frequency alone.
        //
        // Five languages had a row and seventeen did not, and nothing said so:
        // a missing row is not an error, it is a ranking quietly falling back.
        // Keyed off UserData.START rather than a copy of the sentinel, so this
        // cannot pass while the loader looks somewhere else.
        val prefix = UserData.START + tab
        val problems = ArrayList<String>()
        for (code in Languages.codes) {
            val f = File(assets(), "predictions/$code.txt")
            if (!f.isFile) continue // the missing-file case has its own test
            val row = f.readLines().firstOrNull { it.startsWith(prefix) }
            if (row == null) {
                problems.add("$code.txt: no sentence-opener row")
                continue
            }
            val words = row.removePrefix(prefix).trim()
                .split(' ').filter { it.isNotEmpty() }
            if (words.size < 10) problems.add("$code.txt: only ${words.size} openers")
            val dupes = words.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            if (dupes.isNotEmpty()) problems.add("$code.txt: repeats $dupes")
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
            // Read in full. This used to sample the first 200 lines, on the
            // stated grounds that reading all 22 dictionaries "would make this
            // test cost minutes" — measured, it is under half a second, and
            // sampling the head cannot see a frequency inversion or a malformed
            // line anywhere below it.
            var last = Int.MAX_VALUE
            f.useLines { seq ->
                seq.forEachIndexed { i, line ->
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

    @Test
    fun `emoji search keywords are reachable from the mini keypad`() {
        // The search keypad is letters only — no space — and matches a folded,
        // lowercased query against the keyword with startsWith. A keyword with
        // an uppercase letter or a space can therefore never be typed, so the
        // entry is dead: it loads fine and can never be found.
        val problems = ArrayList<String>()
        for (f in File(assets(), "emoji").listFiles().orEmpty()
            .filter { it.name.startsWith("search_") }.sortedBy { it.name }) {
            lines(f).forEachIndexed { i, line ->
                val at = "${f.name}:${i + 1}"
                val tab = line.indexOf(tab)
                if (tab <= 0) {
                    problems.add("$at: no keyword before a tab")
                    return@forEachIndexed
                }
                val kw = line.substring(0, tab)
                if (kw != kw.lowercase()) problems.add("$at: keyword not lowercase: $kw")
                if (kw.any { it == ' ' }) problems.add("$at: keyword has a space: $kw")
                if (line.substring(tab + 1).isBlank()) problems.add("$at: no emoji for $kw")
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `word-to-emoji files are one tab-separated pair with no lost duplicates`() {
        // en.txt and tr.txt map a word to its emoji suggestion. The loader keeps
        // only lines that split into exactly two fields, and the later of two
        // duplicate words wins — both are silent, so guard both.
        val problems = ArrayList<String>()
        // Every word-to-emoji file, discovered rather than listed: the list was
        // "en.txt, tr.txt" and adding a language silently left it unchecked.
        // The picker's own keyword files are the search_* ones and are covered
        // by the test above.
        val names = File(assets(), "emoji").listFiles().orEmpty()
            .filter { it.isFile && it.extension == "txt" && !it.name.startsWith("search_") }
            .map { it.name }
            .sorted()
        assertTrue("no word-to-emoji files found at all", names.isNotEmpty())
        for (name in names) {
            val f = File(assets(), "emoji/$name")
            if (!f.isFile) {
                problems.add("$name: missing")
                continue
            }
            val seen = HashSet<String>()
            lines(f).forEachIndexed { i, line ->
                val at = "$name:${i + 1}"
                val parts = line.split(tab)
                if (parts.size != 2) {
                    problems.add("$at: ${parts.size} fields, need 2")
                    return@forEachIndexed
                }
                if (parts[0].isBlank()) problems.add("$at: blank word")
                if (parts[1].isBlank()) problems.add("$at: blank emoji")
                if (!seen.add(parts[0])) problems.add("$at: duplicate word ${parts[0]}")
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
