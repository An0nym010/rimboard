package com.rimboard.keyboard.engine

import com.rimboard.keyboard.model.ExtendedDicts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * The manifest in the APK against the files it describes.
 *
 * `assets/extended.json` is the catalogue of downloadable dictionaries: a
 * language, a word count, a size in bytes and a SHA-256, one entry per file in
 * `dist/dictionaries/`. `ExtendedDicts.matches` refuses any download whose size
 * or hash disagrees with its entry, which is the right behaviour and also the
 * reason nothing would ever have said the two had drifted.
 *
 * **The failure this catches is silent.** Regenerate the dictionaries without
 * rebuilding the manifest, or publish a stale set, and every download is
 * fetched and then refused. The user sees a download that never finishes and
 * the keyboard sees a file it will not install. Nothing throws, nothing logs at
 * a level anyone reads, and the whole feature is dead until somebody tries it.
 * `tools/fetch_dictionaries.py --extended` writes both in one run, so they
 * agree the day they are made and can only drift afterwards — which is exactly
 * the kind of thing a test is for.
 *
 * The manifest is parsed with [ExtendedDicts.parse] and hashed with
 * [ExtendedDicts.sha256] rather than re-read here, because a test that rebuilds
 * the rule it is checking measures its own copy. That fault has been found four
 * times in this project already.
 *
 * ## The declaration this needs to be real
 *
 * `dist/` is above the module and is not a source set, so Gradle does not know
 * the test task depends on it. Undeclared, editing a dictionary would leave this
 * UP-TO-DATE across exactly the change it exists to catch — the sixth time this
 * project has walked into that. `app/build.gradle.kts` declares `../dist`, and
 * the declaration was checked the way the note there demands rather than
 * reasoned about: a byte was flipped and the suite run *without*
 * `--rerun-tasks`. The task re-ran and named the language.
 *
 * The declaration is conditional on the directory existing, and that is not
 * tidiness. Gradle refuses to *run* a task whose declared input directory is
 * missing — "An input file was expected to be present but it doesn't exist" —
 * so an unconditional line broke the build on every machine without the
 * archives, which is almost all of them. `.optional(true)` does not help; AGP
 * validates the property either way. Both were found by moving `dist/` aside
 * and running, which is the only way this project has ever learned anything
 * about Gradle inputs.
 */
class ExtendedDictManifestTest {

    /**
     * The published archives, or null when they are not on this machine.
     *
     * `dist/` is written by `tools/fetch_dictionaries.py --extended` and is
     * gitignored: the files themselves live on the `dictionaries` branch, so a
     * fresh clone or a CI runner has no copy. That is the normal state and not
     * a failure — there is nothing for the manifest to disagree with.
     *
     * It is also not a hole. The only way the two can drift is for somebody to
     * regenerate one without the other, and that somebody has `dist/` in front
     * of them at the moment they do it, which is exactly when this runs.
     */
    private fun dist(): File? =
        listOf(File("../dist/dictionaries"), File("dist/dictionaries"))
            .firstOrNull { it.isDirectory }

    private fun assets(): File =
        listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }

    private fun catalogue(): ExtendedDicts.Catalogue =
        ExtendedDicts.parse(File(assets(), ExtendedDicts.ASSET).readText())

    /** Runs everywhere: the manifest ships inside the APK. */
    @Test
    fun `the shipped manifest parses at all`() {
        val cat = catalogue()
        assertTrue(
            "assets/${ExtendedDicts.ASSET} does not parse as a catalogue, so the " +
                "download screen ships with nothing to offer",
            cat.entries.isNotEmpty()
        )
        assertTrue(
            "the catalogue base is not an https directory: ${cat.base}",
            cat.base.startsWith("https://") && cat.base.endsWith("/")
        )
    }

    @Test
    fun `every entry describes the file that is actually there`() {
        val dir = dist() ?: return
        val report = StringBuilder()
        val wrong = StringBuilder()
        for (e in catalogue().entries.sortedBy { it.lang }) {
            val f = File(dir, "${e.lang}.txt.gz")
            if (!f.isFile) {
                wrong.append("  ${e.lang}: the manifest offers it and dist/ has no file\n")
                continue
            }
            val bytes = f.readBytes()
            val sha = ExtendedDicts.sha256(bytes)
            report.append("    %-3s %,10d bytes  %s\n".format(e.lang, bytes.size, sha.take(16)))
            if (bytes.size.toLong() != e.bytes) {
                wrong.append(
                    "  ${e.lang}: manifest says ${e.bytes} bytes, file is ${bytes.size}\n"
                )
            }
            if (sha != e.sha256) {
                wrong.append("  ${e.lang}: manifest hash ${e.sha256}, file hashes $sha\n")
            }
        }
        println(report)
        assertEquals(
            "the manifest and dist/ have drifted apart. Every download would be " +
                "fetched and then refused by ExtendedDicts.matches, which is a " +
                "feature that fails without saying anything. Re-run " +
                "`python tools/fetch_dictionaries.py --extended`.\n$wrong",
            "", wrong.toString()
        )
    }

    @Test
    fun `the word count the manifest advertises is the one in the file`() {
        // Not covered by the hash: the count is a separate field, it is what the
        // download screen puts in front of the user, and a wrong one is wrong in
        // the only place the user can see.
        val dir = dist() ?: return
        val wrong = StringBuilder()
        for (e in catalogue().entries.sortedBy { it.lang }) {
            val f = File(dir, "${e.lang}.txt.gz")
            if (!f.isFile) continue
            var n = 0
            // A corrupt archive is one of the two things this notices, so it is
            // reported rather than thrown: a stack trace out of the zip library
            // says less than the name of the language that is broken.
            val broke = try {
                GZIPInputStream(f.inputStream()).bufferedReader().useLines { lines ->
                    lines.forEach { if (it.isNotBlank()) n++ }
                }
                null
            } catch (ex: Exception) {
                ex
            }
            if (broke != null) {
                wrong.append("  ${e.lang}: the published archive will not read back: $broke\n")
                continue
            }
            if (n != e.words) {
                wrong.append("  ${e.lang}: manifest says ${e.words} words, file has $n\n")
            }
        }
        assertEquals("the advertised word counts are wrong.\n$wrong", "", wrong.toString())
    }

    @Test
    fun `no dictionary sits in dist without an entry`() {
        val dir = dist() ?: return
        val offered = catalogue().entries.map { it.lang }.toSet()
        val present = dir.listFiles().orEmpty()
            .filter { it.name.endsWith(".txt.gz") }
            .map { it.name.removeSuffix(".txt.gz") }
            .toSortedSet()
        val orphans = present - offered
        assertEquals(
            "these dictionaries are published and the manifest does not offer " +
                "them, so nobody can download them: $orphans",
            emptySet<String>(), orphans
        )
        assertTrue("dist/ holds no dictionaries at all", present.isNotEmpty())
    }
}
