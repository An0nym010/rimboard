package com.rimboard.keyboard.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * One switch was governing two features, and only one end of one of them.
 *
 * "Text shortcuts -- Expand short codes into full phrases" is the whole of
 * what that feature claims, and it has no switch of its own: a shortcut exists
 * by being written down. Expansion was nevertheless read off `autocorrectActive`,
 * so turning off Auto-correct silently stopped shortcuts expanding.
 *
 * Silently is doing work in that sentence. The strip went on offering the
 * expansion as its **bold first chip** regardless of the setting, because that
 * path never asked the question at all -- so with autocorrect off the keyboard
 * showed "on my way" as the word the space bar was about to commit, and then
 * committed "omw". `AutocorrectGate.mayCorrect` states the rule being broken
 * in its own KDoc: "a word this refuses to commit must never be shown as the
 * one that will be."
 *
 * The fix is not to stop promising. It is that a shortcut was never the
 * autocorrect switch's business: turning autocorrect off says do not guess at
 * my words, not forget the phrases I defined. What still governs it is the
 * field, which is the half of `autocorrectActive` that was never about
 * preferences.
 */
class ShortcutExpansionTest {

    @Test
    fun `a shortcut expands with autocorrect switched off`() {
        // The gate takes no autocorrect argument at all, which is the point:
        // there is no value of that setting this function can be asked about.
        assertTrue(
            AutocorrectGate.mayExpandShortcut(
                fieldTakesProse = true, identifierContext = false, separator = " "
            )
        )
    }

    @Test
    fun `the field still governs it`() {
        assertFalse(
            "a password field, where nothing should silently rewrite what was " +
                "typed -- a shortcut is a replacement like any other",
            AutocorrectGate.mayExpandShortcut(
                fieldTakesProse = false, identifierContext = false, separator = " "
            )
        )
        assertFalse(
            "mid-identifier, where the letters are not prose",
            AutocorrectGate.mayExpandShortcut(
                fieldTakesProse = true, identifierContext = true, separator = " "
            )
        )
    }

    @Test
    fun `a separator that ends an identifier does not expand`() {
        // The same rule mayCommit applies, for the same reason: some
        // separators say the run of characters was never a word.
        val ends = listOf("@", "/", ".").filter { ProseContext.separatorEndsIdentifier(it) }
        assertTrue("no separator in the sample ends an identifier", ends.isNotEmpty())
        for (sep in ends) {
            assertFalse(
                "\"$sep\" ends an identifier, so the run before it was not a word",
                AutocorrectGate.mayExpandShortcut(
                    fieldTakesProse = true, identifierContext = false, separator = sep
                )
            )
        }
    }

    /** Unit tests run from the module directory; tolerate the project root too. */
    private fun src(): File =
        listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }

    /**
     * Both ends must ask, and must ask the same thing.
     *
     * The defect was one end asking a different question and the other not
     * asking at all, so a rule that only one of them consults is the shape to
     * refuse.
     */
    @Test
    fun `both the strip and the commit consult the gate`() {
        val svc = src().resolve("com/rimboard/keyboard/RimBoardService.kt").readText()
        val lookups = Regex("""Shortcuts\.expansionFor\(""").findAll(svc).toList()
        assertTrue(
            "expected the strip's lookup and the commit's; found ${lookups.size}",
            lookups.size == 2
        )
        for (m in lookups) {
            val before = svc.substring(maxOf(0, m.range.first - 220), m.range.first)
            assertTrue(
                "a shortcut lookup that does not consult shortcutMayExpand: " +
                    "the strip promising what the commit will not do is exactly " +
                    "how this broke.\n..." + before.takeLast(160),
                before.contains("shortcutMayExpand(")
            )
        }
    }

    @Test
    fun `expansion is not gated on the autocorrect preference`() {
        val svc = src().resolve("com/rimboard/keyboard/RimBoardService.kt").readText()
        val gate = Regex("""fun shortcutMayExpand\([^)]*\)[^=]*=\s*([^\n]+)""")
            .find(svc)?.groupValues?.get(1) ?: ""
        assertTrue(
            "shortcutMayExpand is not defined, or not on one line",
            gate.isNotBlank()
        )
        assertFalse(
            "the shortcut gate reads autocorrectActive again: $gate",
            gate.contains("autocorrectActive") || gate.contains("Prefs.autocorrect")
        )
    }
}
