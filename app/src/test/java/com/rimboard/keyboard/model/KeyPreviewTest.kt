package com.rimboard.keyboard.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The bubble above a pressed key, in the one field where the screen is
 * deliberately not showing what you type.
 *
 * The preview renders the character magnified, above the key, at the moment
 * it is pressed. In a password field that is the same disclosure the masking
 * exists to prevent -- and it was governed by "Popup on keypress" alone, while
 * the line directly beneath it in the service already conditioned the TLD
 * popups on the field type.
 *
 * Android has a user-level answer to exactly this question. *Show passwords*
 * is what decides whether an `EditText` flashes the last character before
 * turning it into a dot, so it is the switch the bubble should answer to as
 * well. Deferring to it rather than inventing a rule is the point: someone who
 * turned it off has already said what they want, in the place Android provides
 * for saying it, and someone who left it on -- the platform default -- sees no
 * change.
 */
class KeyPreviewTest {

    @Test
    fun `an ordinary field is governed by the preference alone`() {
        assertTrue(
            KeyPreview.mayShow(enabled = true, isPassword = false, systemShowsPasswords = false)
        )
        assertFalse(
            "the preference still switches it off everywhere",
            KeyPreview.mayShow(enabled = false, isPassword = false, systemShowsPasswords = true)
        )
    }

    @Test
    fun `a password field follows the system setting`() {
        assertTrue(
            "Show passwords is on, which is the platform default, so nothing " +
                "changes for the great majority of people",
            KeyPreview.mayShow(enabled = true, isPassword = true, systemShowsPasswords = true)
        )
        assertFalse(
            "Show passwords is off, and the keyboard was still printing each " +
                "character above the key it was typed on",
            KeyPreview.mayShow(enabled = true, isPassword = true, systemShowsPasswords = false)
        )
    }

    @Test
    fun `the preference still wins over the system setting`() {
        // The two are not alternatives. Turning the popup off means no popup,
        // whatever Android thinks about passwords.
        assertFalse(
            KeyPreview.mayShow(enabled = false, isPassword = true, systemShowsPasswords = true)
        )
    }

    /** Unit tests run from the module directory; tolerate the project root too. */
    private fun src(): File =
        listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }

    @Test
    fun `the service asks the rule and reads the platform setting`() {
        val svc = src().resolve("com/rimboard/keyboard/RimBoardService.kt").readText()
        assertTrue(
            "the preview is still set from the preference alone",
            !svc.contains("kv.previewEnabled = Prefs.popupPreview(this)")
        )
        assertTrue("the service does not consult the rule", svc.contains("KeyPreview.mayShow("))
        assertTrue(
            "the system setting is not read, so the gate can only ever say yes",
            svc.contains("TEXT_SHOW_PASSWORD")
        )
    }

    @Test
    fun `an unreadable setting leaves the behaviour alone`() {
        // The failure direction is deliberate and worth pinning: if the
        // platform value cannot be read, the bubble stays. Removing a feature
        // on a failed lookup would be a worse bug than the one being fixed.
        val svc = src().resolve("com/rimboard/keyboard/RimBoardService.kt").readText()
        val fn = svc.substring(svc.indexOf("private fun systemShowsPasswords()"))
            .substringBefore("\n    private fun ")
        assertTrue(
            "the default for an unset or unreadable value is not 'show': $fn",
            fn.contains("TEXT_SHOW_PASSWORD, 1") && fn.contains("catch") && fn.contains("true")
        )
    }
}
