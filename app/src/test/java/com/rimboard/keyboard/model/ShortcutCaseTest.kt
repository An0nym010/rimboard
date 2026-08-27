package com.rimboard.keyboard.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * A shortcut is a replacement, and replacements take the case of what they
 * replace.
 *
 * Every other path that swaps a word in the field says so explicitly -- the
 * correction path's own KDoc reads "Cased to match what was typed, so 'Dont'
 * -> 'Don't'" -- and each of them runs the candidate past the same rule. The
 * one replacement that happens outside the engine, a text shortcut looked up
 * by the service, was assigned verbatim.
 *
 * The result is visible in the most ordinary case there is. Auto-capitalisation
 * capitalises the trigger as it is typed, so the composing word at the start of
 * a sentence is "Omw"; the expansion then committed "on my way" in lower-case,
 * where "Teh" in that same position commits "The". Observed on the device
 * before it was traced: the log line read `typed=Omw` and the field read "on
 * my way".
 */
class ShortcutCaseTest {

    private val en: Locale = Locale.ENGLISH

    @Test
    fun `a sentence-start trigger capitalises its expansion`() {
        assertEquals("On my way", WordCase.match("Omw", "on my way", en))
    }

    @Test
    fun `a lower-case trigger leaves the expansion alone`() {
        // The expansion is what the user wrote down. Nothing here restyles it
        // when there was no capital to carry over.
        assertEquals("on my way", WordCase.match("omw", "on my way", en))
    }

    @Test
    fun `shouting the trigger shouts the expansion`() {
        // The same rule the correction path applies to "TEH" -> "THE".
        assertEquals("ON MY WAY", WordCase.match("OMW", "on my way", en))
    }

    @Test
    fun `a single capital does not shout`() {
        // "I" is a whole word in English and is capitalised by the language
        // rather than by emphasis, and one auto-capitalised letter is not a
        // request for capitals throughout.
        assertEquals("Address", WordCase.match("A", "address", en))
    }

    @Test
    fun `an expansion that does not start with a lower-case letter is untouched`() {
        // A street number, or a name the user capitalised on purpose. The
        // first character is only ever raised, never lowered.
        assertEquals("10 Downing Street", WordCase.match("Addr", "10 Downing Street", en))
        assertEquals("Sam Smith", WordCase.match("Sig", "Sam Smith", en))
    }

    @Test
    fun `the case rule is applied in the caller's locale`() {
        // Turkish: the capital of "i" is "İ", not "I". Getting this wrong is
        // how a word comes back under a key nothing will ever look up again --
        // the same trap `UserData.addUserWord` carries a comment about.
        val tr = Locale.forLanguageTag("tr")
        assertEquals("İyi günler", WordCase.match("Ig", "iyi günler", tr))
    }

    /** Unit tests run from the module directory; tolerate the project root too. */
    private fun src(): File =
        listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }

    @Test
    fun `the engine and the shortcut path share one rule`() {
        // The defect was two copies of a decision, one of which did not exist.
        val engine = src().resolve("com/rimboard/keyboard/engine/SuggestionEngine.kt").readText()
        val svc = src().resolve("com/rimboard/keyboard/RimBoardService.kt").readText()
        assertEquals(
            "SuggestionEngine still defines its own casing rule instead of " +
                "delegating to WordCase, so the two can drift apart again",
            true,
            engine.contains("WordCase.match(")
        )
        assertEquals(
            "the shortcut expansion is committed without taking the case of " +
                "the trigger, which is what every other replacement does",
            true,
            svc.contains("WordCase.match(")
        )
    }
}
