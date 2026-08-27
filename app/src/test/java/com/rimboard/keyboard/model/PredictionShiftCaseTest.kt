package com.rimboard.keyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * The case of a word offered before a letter of it has been typed.
 *
 * A correction takes its case from the word it replaces. A next-word
 * prediction has no such word: it is offered on an empty composing buffer, so
 * the only thing the user has said about its case is the shift key.
 *
 * The typing path reads all four shift states -- `applyShift` capitalises a
 * typed letter whenever the state is anything but NONE. The strip read one.
 * It capitalised its chips under AUTO and ignored MANUAL and CAPSLOCK, so
 * pressing shift for a name left the predictions lower-case and tapping one
 * committed the capital away. Caps lock did the same.
 *
 * The other half is that pressing shift did not redraw the strip at all, so
 * even the state it did understand was read at the wrong moment: at the start
 * of a sentence AUTO capitalises the chips, and pressing shift to *undo* that
 * left the capitals sitting there for a tap to commit.
 */
class PredictionShiftCaseTest {

    private val en: Locale = Locale.ENGLISH

    @Test
    fun `no shift leaves the prediction alone`() {
        assertEquals(
            "thanks",
            WordCase.forShift("thanks", capsLock = false, shifted = false, locale = en)
        )
    }

    @Test
    fun `a shift capitalises it`() {
        // AUTO and MANUAL both arrive here as shifted: the user has said the
        // next word starts with a capital, and how they said it is not the
        // strip's business.
        assertEquals(
            "Thanks",
            WordCase.forShift("thanks", capsLock = false, shifted = true, locale = en)
        )
    }

    @Test
    fun `caps lock shouts it`() {
        assertEquals(
            "THANKS",
            WordCase.forShift("thanks", capsLock = true, shifted = false, locale = en)
        )
    }

    @Test
    fun `a prediction that does not start with a lower-case letter is untouched`() {
        assertEquals("I", WordCase.forShift("I", capsLock = false, shifted = true, locale = en))
        assertEquals("3", WordCase.forShift("3", capsLock = false, shifted = true, locale = en))
    }

    @Test
    fun `the capital is the locale's`() {
        // Turkish again: the capital of "i" is "İ". A prediction capitalised
        // with the wrong one is a word the user has to fix by hand.
        val tr = Locale.forLanguageTag("tr")
        assertEquals("İyi", WordCase.forShift("iyi", capsLock = false, shifted = true, locale = tr))
    }

    /** Unit tests run from the module directory; tolerate the project root too. */
    private fun src(): File =
        listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }

    @Test
    fun `the strip reads every shift state`() {
        val svc = src().resolve("com/rimboard/keyboard/RimBoardService.kt").readText()
        assertTrue(
            "the strip still cases predictions by testing for AUTO alone",
            !svc.contains("keyboardView?.shiftState == KeyboardView.ShiftState.AUTO")
        )
        assertTrue(
            "the strip does not use the shared rule",
            svc.contains("WordCase.forShift(")
        )
    }

    @Test
    fun `pressing shift redraws the strip`() {
        val svc = src().resolve("com/rimboard/keyboard/RimBoardService.kt").readText()
        val start = svc.indexOf("private fun handleShift(")
        assertTrue("handleShift is gone; this scan needs rewriting", start >= 0)
        val next = svc.indexOf("\n    private fun ", start + 10)
        val body = svc.substring(start, if (next < 0) svc.length else next)
        assertTrue(
            "the chips keep the case they had before the shift, and tapping " +
                "one commits it",
            body.contains("updateStrip()")
        )
    }
}
