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

    @Test
    fun `a password field does not have its keys read out loud`() {
        assertFalse(KeyPreview.maySpeak(isPassword = true, privateAudio = false))
    }

    @Test
    fun `headphones make it private, so the keys are named again`() {
        // The rule is about who can hear, not about passwords being secret from
        // their owner. A screen-reader user with a headset gets a working
        // keyboard in a password field, which is the whole point of not simply
        // going silent everywhere.
        assertTrue(KeyPreview.maySpeak(isPassword = true, privateAudio = true))
    }

    @Test
    fun `an ordinary field is spoken with or without headphones`() {
        assertTrue(KeyPreview.maySpeak(isPassword = false, privateAudio = false))
        assertTrue(KeyPreview.maySpeak(isPassword = false, privateAudio = true))
    }

    @Test
    fun `the two channels are decided separately`() {
        // Show passwords is about a screen and privateAudio is about a room, so
        // neither answers the other's question. Pinned because collapsing them
        // into one flag is the obvious tidy-up and it would be wrong in both
        // directions: headphones would light up the preview, and turning Show
        // passwords on would start reading passwords to the bus.
        assertTrue(
            KeyPreview.mayShow(enabled = true, isPassword = true, systemShowsPasswords = true)
        )
        assertFalse(KeyPreview.maySpeak(isPassword = true, privateAudio = false))
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
    fun `the spoken label asks the rule, and only withholds characters`() {
        val kv = src().resolve("com/rimboard/keyboard/ui/KeyboardView.kt").readText()
        val fn = kv.substring(kv.indexOf("private fun spokenLabel("))
            .substringBefore("    /**")
        assertTrue("spokenLabel does not consult speakCharacters: $fn",
            fn.contains("speakCharacters"))
        assertTrue(
            "the withheld branch is not limited to character keys, so a password " +
                "field would lose the names of shift, backspace and enter too: $fn",
            fn.contains("key.type == KeyType.CHARACTER")
        )
        // Every named key must still come from the branches above the gate.
        // If the gate were moved to the top of the function this would pass on
        // the string check alone, so pin the order.
        assertTrue(
            "the gate is above the named keys, which would silence all of them",
            fn.indexOf("a11y_key_backspace") < fn.indexOf("speakCharacters")
        )
    }

    @Test
    fun `the service decides speech from audio, not from the screen setting`() {
        val svc = src().resolve("com/rimboard/keyboard/RimBoardService.kt").readText()
        assertTrue("the view is never told", svc.contains("kv.speakCharacters ="))
        assertTrue("the rule is not consulted", svc.contains("KeyPreview.maySpeak("))
        val fn = svc.substring(svc.indexOf("private fun privateAudio()"))
            .substringBefore("    /**")
        assertTrue(
            "privateAudio does not ask for output devices: $fn",
            fn.contains("GET_DEVICES_OUTPUTS")
        )
        assertTrue(
            "privateAudio does not fail closed, so an audio lookup that throws " +
                "would speak the password: $fn",
            fn.contains("catch") && fn.substringAfter("catch").contains("false")
        )
        assertTrue(
            "a masked field goes quiet without saying so",
            svc.contains("a11y_password_quiet")
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
