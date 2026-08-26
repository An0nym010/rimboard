package com.rimboard.keyboard.model

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every file the keyboard keeps is either carried by a backup or excused here.
 *
 * "This replaces your current settings, learned words and predictions," says
 * the restore dialog, and a backup carried five files to make that true. The
 * keyboard keeps eight. Three of the missing ones are deliberate and two of
 * those say so: `pinned.txt` is excluded with a reason written into
 * `UserData`, and `stats.json` counts what *this* install typed, which is the
 * same argument `BackupDoc.EXCLUDED` already makes for `net_sent_count`.
 *
 * The third was `blocked.txt`, and nothing anywhere gave a reason, because
 * there was not one. Blocking a word is the most deliberate thing anyone does
 * to this keyboard's vocabulary -- it is reached by long-pressing a suggestion
 * and saying never show me this again -- and a restore brought back every
 * learned word while silently dropping every ban. The word the user had
 * banned started being suggested again on the new phone, with nothing to
 * explain why.
 *
 * How it happened is visible in the code: `BackupDoc.FILES` describes the file
 * list in a comment that calls it "the five files a backup carries", and
 * nothing read it. `Backup.export` and `Backup.import` each hand-wrote the
 * same five names, so the list that documented what travelled was not the list
 * that decided it, and adding a file meant editing three places of which only
 * two had any effect. FILES is now the one that decides, which is what makes
 * the first test below worth having.
 */
class BackupCoverageTest {

    /** Unit tests run from the module directory; tolerate the project root too. */
    private fun src(): File =
        listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }

    private fun mainSources(): List<File> =
        src().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** Every file this app keeps in its own data directory. */
    private fun filesKept(): Set<String> {
        val out = LinkedHashSet<String>()
        val userData = src().resolve("com/rimboard/keyboard/engine/UserData.kt").readText()
        Regex("""File\(dir,\s*"([^"]+)"\)""").findAll(userData)
            .forEach { out.add(it.groupValues[1]) }
        for (f in mainSources()) {
            Regex("""dataDir\([^)]*\),\s*"([^"]+)"""").findAll(f.readText())
                .forEach { out.add(it.groupValues[1]) }
        }
        // A name built per language reaches the scan as its prefix, because
        // that is all the source holds literally. Rendered the way the
        // exclusion list names it, so the two can be compared at all.
        return out.mapTo(LinkedHashSet()) { if (it.endsWith("_")) it + "*.txt" else it }
    }

    @Test
    fun `every file kept is carried or excused`() {
        val carried = BackupDoc.FILES.map { it.second }.toSet()
        val missing = filesKept() - carried - BackupDoc.NOT_BACKED_UP.keys
        assertTrue(
            "these files hold user data that a backup silently leaves behind. " +
                "Add them to BackupDoc.FILES, or to NOT_BACKED_UP with the " +
                "reason: " + missing.joinToString(", "),
            missing.isEmpty()
        )
    }

    @Test
    fun `the scan found the files it is meant to be checking`() {
        // Guards the guard: a regex that stops matching compares nothing and
        // reports clean, which looks exactly like a clean run.
        val kept = filesKept()
        assertTrue(
            "the scan found only $kept -- it has stopped matching how these " +
                "files are declared",
            kept.size >= 8 && "learned.txt" in kept && "blocked.txt" in kept
        )
    }

    @Test
    fun `nothing is excused without a reason`() {
        val blank = BackupDoc.NOT_BACKED_UP.filterValues { it.isBlank() }.keys
        assertTrue(
            "an exclusion with no reason is how blocked.txt was lost: " +
                blank.joinToString(", "),
            blank.isEmpty()
        )
    }

    @Test
    fun `no file key collides with the document's own`() {
        // Now that both directions loop over FILES, a key is written straight
        // into the root object. "settings" as a file key would put a file's
        // text where the preferences block goes and take every setting with
        // it, and the restore would report success.
        val reserved = setOf("app", "format", "exportedAt", "settings")
        val keys = BackupDoc.FILES.map { it.first }
        assertTrue(
            "a file key collides with the document's own: " +
                keys.filter { it in reserved }.joinToString(", "),
            keys.none { it in reserved }
        )
        assertTrue(
            "two files share a document key, so one would overwrite the other",
            keys.size == keys.toSet().size
        )
        assertTrue(
            "two entries name the same file",
            BackupDoc.FILES.map { it.second }.let { it.size == it.toSet().size }
        )
    }

    @Test
    fun `the writer uses the declared list`() {
        // The fault underneath the fault. Three copies of one list, of which
        // one was decorative, is why a file could be in the documentation and
        // out of the backup at the same time.
        val backup = src().resolve("com/rimboard/keyboard/settings/Backup.kt").readText()
        assertTrue(
            "Backup.kt does not read BackupDoc.FILES, so the declared list and " +
                "the real one can differ again",
            backup.contains("BackupDoc.FILES")
        )
        val handWritten = Regex(""""(learned|bigrams|trigrams)\.txt"""").findAll(backup).count()
        assertTrue(
            "Backup.kt still names data files by hand ($handWritten of them), " +
                "which is the drift this list exists to prevent",
            handWritten == 0
        )
    }
}
