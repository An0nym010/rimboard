package com.rimboard.keyboard.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where a swipe may be read as a word, and where it may not.
 *
 * Tapping has always known about these fields. `suggestionsActive` and
 * `autocorrectActive` are both switched off in a password, an address, or a
 * field whose app asked for no suggestions, and [AutocorrectGate.mayCommit]'s
 * own note says why: "a password, email or URL field, where words are not prose
 * and a correction is a wrong password."
 *
 * Gliding asked two questions — is gliding switched on, and is this a text
 * field — and a password field is a text field. So a swipe in one was decoded
 * against the dictionary and the winning word committed, in the one kind of
 * field where the user cannot read back what they got. It is the same class of
 * replacement as an autocorrect and a stronger one: a correction replaces a
 * word somebody typed, and a swipe replaces a shape they drew, with no
 * keystroke in between and nothing on screen to compare against.
 *
 * The rule lives beside the other one rather than in the service, for the
 * reason that file already gives: inside an `InputMethodService` the rule
 * deciding whether the keyboard may silently rewrite what somebody typed could
 * not be executed by anything but a thumb.
 */
class SwipeInPasswordTest {

    @Test
    fun `an ordinary text field decodes swipes`() {
        assertTrue(
            AutocorrectGate.mayDecodeSwipe(
                enabled = true, isTextClass = true, isPassword = false,
                noSuggestions = false, isEmailOrUri = false
            )
        )
    }

    @Test
    fun `a password field does not`() {
        assertFalse(
            "a swipe in a password field is decoded into a dictionary word and " +
                "committed, and the field shows dots",
            AutocorrectGate.mayDecodeSwipe(
                enabled = true, isTextClass = true, isPassword = true,
                noSuggestions = false, isEmailOrUri = false
            )
        )
    }

    @Test
    fun `nor an address, nor a field that asked for no suggestions`() {
        assertFalse(
            "an address is not prose, and a decoded word is not an address",
            AutocorrectGate.mayDecodeSwipe(
                enabled = true, isTextClass = true, isPassword = false,
                noSuggestions = false, isEmailOrUri = true
            )
        )
        assertFalse(
            "the app asked for no suggestions and a swipe is one",
            AutocorrectGate.mayDecodeSwipe(
                enabled = true, isTextClass = true, isPassword = false,
                noSuggestions = true, isEmailOrUri = false
            )
        )
    }

    @Test
    fun `the setting still switches it off`() {
        assertFalse(
            AutocorrectGate.mayDecodeSwipe(
                enabled = false, isTextClass = true, isPassword = false,
                noSuggestions = false, isEmailOrUri = false
            )
        )
        assertFalse(
            "a number pad has no words to decode into",
            AutocorrectGate.mayDecodeSwipe(
                enabled = true, isTextClass = false, isPassword = false,
                noSuggestions = false, isEmailOrUri = false
            )
        )
    }

    /**
     * The gate is asked in both places, and has to be.
     *
     * Switching gliding off at the view means the gesture is read as an
     * ordinary key press rather than decoded and then discarded, which is what
     * the user wants in a password field. Asking again at the commit is the
     * guard that survives a view that was configured before the field flags
     * were read.
     */
    @Test
    fun `the service asks the gate rather than the setting`() {
        val src = listOf(File("src/main/java"), File("app/src/main/java"))
            .first { it.isDirectory }
            .resolve("com/rimboard/keyboard/RimBoardService.kt")
            .readText()
        val asks = Regex("""mayDecodeSwipe\(""").findAll(src).count()
        assertTrue(
            "the glide gate is asked $asks times; it belongs at the view, where " +
                "it decides whether the gesture is a swipe at all, and at the " +
                "commit, which must not depend on the view being configured first",
            asks >= 2
        )
        assertFalse(
            "onGlideComplete is testing the setting directly again, which is " +
                "how the field type came to be ignored in the first place",
            Regex("""if \(!Prefs\.glide\(this\) \|\| !isTextClass\)""").containsMatchIn(src)
        )
    }
}
