package com.rimboard.keyboard.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Which language a field opens in.
 *
 * Four answers can apply and they have to be tried in order of how specific
 * they are. Getting that order wrong is invisible from the outside — the
 * keyboard opens in *a* language either way — which is how the most specific
 * one came to be discarded entirely.
 */
class StartupLanguageTest {

    /**
     * Language-per-app decides which language a field opens in.
     *
     * The setting has existed, been listed in settings, and written its
     * preference on every language switch — while doing nothing whatsoever. The
     * per-app choice was applied in `onStartInputView` and then overwritten,
     * unconditionally, by `readPrefsAndFieldFlags` in the same pass and before
     * any layout was drawn. Nothing failed and nothing logged; the keyboard
     * simply always opened in the language last used *anywhere*.
     *
     * That is the shape of fault a pure rule is for. The precedence is one
     * function now, and this asks it the questions the service used to answer
     * by accident.
     */
    @Test
    fun `the per-app language beats the one last used anywhere`() {
        val enabled = listOf("en", "tr", "de")
        assertEquals(
            "a German app must open in German even though Turkish was last used",
            "de",
            Prefs.startupLang(enabled, perApp = "de", saved = "tr", systemLang = "en")
        )
    }

    @Test
    fun `without a per-app language the last one used still wins`() {
        val enabled = listOf("en", "tr", "de")
        assertEquals(
            "tr",
            Prefs.startupLang(enabled, perApp = null, saved = "tr", systemLang = "en")
        )
    }

    @Test
    fun `a language that has been disabled is skipped at every level`() {
        val enabled = listOf("en", "tr")
        // Each of these names a language the user has since turned off; the
        // answer must fall through rather than select something with no layout.
        assertEquals("tr", Prefs.startupLang(enabled, "de", "tr", "en"))
        assertEquals("en", Prefs.startupLang(enabled, "de", "el", "en"))
        assertEquals("en", Prefs.startupLang(enabled, null, null, "fr"))
        assertEquals("en", Prefs.startupLang(enabled, "xx", "yy", "zz"))
        assertEquals("en", Prefs.startupLang(emptyList(), "de", "tr", "fr"))
    }

    @Test
    fun `the device language is used before falling back to the first enabled`() {
        assertEquals(
            "tr",
            Prefs.startupLang(listOf("en", "tr"), perApp = null, saved = null, systemLang = "tr")
        )
    }

    /**
     * Nothing may assign the language index after the rule has answered.
     *
     * The bug was not a wrong rule, it was a second answer written later in the
     * same pass. One assignment, in the function that consults the preference,
     * is the property that keeps it fixed.
     */
    @Test
    fun `the service decides its language in exactly one place`() {
        val src = listOf(File("src/main/java"), File("app/src/main/java"))
            .first { it.isDirectory }
            .resolve("com/rimboard/keyboard/RimBoardService.kt")
        val lines = src.readLines()
        val assigns = lines.withIndex()
            .filter { (_, l) -> Regex("""^\s*langIndex\s*=""").containsMatchIn(l) }
            // The assignment can wrap, so each one is judged on its whole
            // statement rather than on the line the `=` happens to land on.
            .map { (i, _) -> i to lines.subList(i, minOf(i + 4, lines.size)).joinToString(" ") }
        // The three deliberate switches (globe, space swipe, system subtype)
        // plus the one that opens a field.
        val opening = assigns.filter { (_, l) -> l.contains("Prefs.startupLang") }
        assertEquals(
            "the language a field opens in must be decided once, by " +
                "Prefs.startupLang:\n" +
                assigns.joinToString("\n") { (i, l) -> "${i + 1}: ${l.trim()}" },
            1, opening.size
        )
        val bare = assigns.filter { (_, l) ->
            !l.contains("Prefs.startupLang") && !l.contains("langIndex +") &&
                !l.contains("langIndex -") && !l.contains("= idx")
        }
        assertTrue(
            "an unexplained assignment to langIndex is how the per-app language " +
                "was overwritten before it ever reached the screen:\n" +
                bare.joinToString("\n") { (i, l) -> "${i + 1}: ${l.trim()}" },
            bare.isEmpty()
        )
    }
}
