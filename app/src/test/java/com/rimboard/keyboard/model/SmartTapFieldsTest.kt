package com.rimboard.keyboard.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where the language model may re-aim a tap, and where it may not.
 *
 * Adaptive tap targeting picks among the letter keys a touch actually lands on
 * by weighing the spatial fit against P(letter | previous letter). It is the
 * quietest thing this keyboard does to what you typed: no word is replaced, no
 * chip is shown, and the character that appears is simply not always the key
 * nearest the thumb.
 *
 * It was refused in password fields alone, and the reason written beside it is
 * not about passwords: "people type precisely and unusual sequences (no
 * language prior should second-guess them)". That describes every field this
 * keyboard already declines to treat as prose. An email local part is not
 * English; neither is a URL, nor a field whose app asked for no suggestions,
 * nor an address typed in the middle of an ordinary message. A nudged letter
 * in any of them is a wrong address with nothing on screen to explain it --
 * and unlike an autocorrect there is no word to point at, no revert, and no
 * chip that was promising anything.
 *
 * `fieldTakesProse` already names that set, and every other rule in this file
 * takes it together with `identifierContext`. This one took neither.
 */
class SmartTapFieldsTest {

    @Test
    fun `ordinary prose is arbitrated`() {
        assertTrue(
            AutocorrectGate.mayArbitrateTap(
                enabled = true, fieldTakesProse = true, identifierContext = false
            )
        )
    }

    @Test
    fun `a field that does not take prose is not`() {
        // Password, email, URL, and "no suggestions" all reach here as the
        // same false: they are the four conditions fieldTakesProse is made of.
        assertFalse(
            "an address is not English, and a letter moved inside one is a " +
                "wrong address",
            AutocorrectGate.mayArbitrateTap(
                enabled = true, fieldTakesProse = false, identifierContext = false
            )
        )
    }

    @Test
    fun `an address typed inside a prose field is not`() {
        // The per-word half. A message is prose; the URL in the middle of it
        // is not, and that is exactly what identifierContext is computed for.
        assertFalse(
            AutocorrectGate.mayArbitrateTap(
                enabled = true, fieldTakesProse = true, identifierContext = true
            )
        )
    }

    @Test
    fun `the setting still switches it off`() {
        assertFalse(
            AutocorrectGate.mayArbitrateTap(
                enabled = false, fieldTakesProse = true, identifierContext = false
            )
        )
    }

    /** Unit tests run from the module directory; tolerate the project root too. */
    private fun src(): File =
        listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }

    @Test
    fun `the service asks the gate rather than the field type`() {
        val svc = src().resolve("com/rimboard/keyboard/RimBoardService.kt").readText()
        assertTrue(
            "resolveAmbiguousTap does not consult the gate",
            svc.contains("mayArbitrateTap(")
        )
        assertFalse(
            "the tap arbiter is still testing isPassword directly, which is " +
                "how the other three field types came to be missed",
            svc.contains("if (isPassword || !Prefs.smartTap(this))")
        )
    }
}
