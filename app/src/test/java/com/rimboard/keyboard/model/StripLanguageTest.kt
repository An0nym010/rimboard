package com.rimboard.keyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Both halves of the suggestion strip follow the language being written.
 *
 * The strip answers one question in two ways: what word is being typed, and
 * what word comes next. The first asked the effective language -- the one the
 * evidence says is being written -- and passed the other enabled language
 * alongside it. The second asked which language was selected on the space bar.
 *
 * So a Turkish keyboard with English enabled completed an English word and
 * then predicted the word after it in Turkish, in the same strip, on
 * consecutive keystrokes. Nobody switched languages; the strip did, halfway
 * through its own row.
 */
class StripLanguageTest {

    /** Unit tests run from the module directory; tolerate the project root too. */
    private fun src(): File =
        listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }

    private fun updateStrip(): String {
        val svc = src().resolve("com/rimboard/keyboard/RimBoardService.kt").readText()
        val body = svc.substring(svc.indexOf("private fun updateStrip()"))
        // Ends where the strip is handed its words; everything language-shaped
        // happens above that.
        return body.substring(0, body.indexOf("s.showSuggestions(shownWords"))
            .lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
    }

    @Test
    fun `the strip never resolves the selected language`() {
        val body = updateStrip()
        assertEquals(
            "updateStrip asks which language is selected rather than which is " +
                "being written; the completion call beside it asks effLang()",
            0, body.split("currentLangCode()").size - 1
        )
    }

    @Test
    fun `and never the selected locale`() {
        // effLocale/effAltLocale removed first, so what is left is the bare
        // call -- the layout's locale, which is not the words' locale once the
        // boost has moved.
        val body = updateStrip()
            .replace("effAltLocale()", "")
            .replace("effLocale()", "")
        assertEquals(
            "updateStrip cases a word with the layout's locale", 0,
            body.split("locale()").size - 1
        )
    }

    @Test
    fun `predictions and completions ask the same language`() {
        val body = updateStrip()
        assertTrue("the prediction call is gone", body.contains("engine.predictions("))
        assertTrue("the completion call is gone", body.contains("engine.suggestionsFor("))
        val pred = body.substring(body.indexOf("engine.predictions(")).take(200)
        assertTrue(
            "the prediction call does not follow the effective language: $pred",
            pred.contains("effLang(), effLocale()")
        )
    }

    @Test
    fun `casing a prediction with the wrong locale changes the letter`() {
        // Why the locale had to move with the language rather than being left
        // as it was. Turkish pairs i with İ and ı with I, so an English
        // prediction titlecased under a Turkish locale is not the same word
        // shouted -- it is spelled with a letter English does not have.
        val tr = Locale.forLanguageTag("tr")
        assertEquals(
            "İf", WordCase.forShift("if", capsLock = false, shifted = true, locale = tr)
        )
        assertEquals(
            "If",
            WordCase.forShift("if", capsLock = false, shifted = true, locale = Locale.ENGLISH)
        )
        // And the other direction, which is the one that was actually shipping:
        // a Turkish prediction cased under an English locale loses its dot.
        assertEquals(
            "İyi", WordCase.forShift("iyi", capsLock = false, shifted = true, locale = tr)
        )
        assertEquals(
            "Iyi",
            WordCase.forShift("iyi", capsLock = false, shifted = true, locale = Locale.ENGLISH)
        )
    }
}
