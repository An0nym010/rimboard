package com.rimboard.keyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The standalone English "i", and the language that question is asked of.
 *
 * The rule itself was never in doubt -- every phone keyboard capitalises the
 * pronoun. What was wrong is which language it consulted: the commit path
 * asked which language was *selected* while every other rule beside it asked
 * which language was being *typed*, so with two languages enabled it was wrong
 * in both directions at once.
 */
class PronounCaseTest {

    @Test
    fun `the English pronoun is capitalised`() {
        assertEquals("I", WordCase.pronoun("i", "en"))
    }

    @Test
    fun `in Turkish it is a different letter, not a louder one`() {
        // Turkish pairs i with İ and ı with I. Raising a Turkish "i" to "I"
        // does not capitalise the word, it swaps the letter for another one --
        // which is why this cannot be a language-blind rule that merely looks
        // untidy elsewhere.
        assertEquals("i", WordCase.pronoun("i", "tr"))
    }

    @Test
    fun `no other language borrows it`() {
        for (lang in listOf("de", "fr", "es", "it", "pt", "ru", "nl", "pl"))
            assertEquals("i in $lang", "i", WordCase.pronoun("i", lang))
    }

    @Test
    fun `only the pronoun, and only when it is lower-case`() {
        assertEquals("I", WordCase.pronoun("I", "en"))
        assertEquals("in", WordCase.pronoun("in", "en"))
        assertEquals("if", WordCase.pronoun("if", "en"))
        assertEquals("", WordCase.pronoun("", "en"))
        // Not the pronoun: a single letter that happens to be typed alone.
        assertEquals("a", WordCase.pronoun("a", "en"))
    }

    /** Unit tests run from the module directory; tolerate the project root too. */
    private fun src(): File =
        listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }

    @Test
    fun `the commit asks which language is being typed`() {
        val svc = src().resolve("com/rimboard/keyboard/RimBoardService.kt").readText()
        assertTrue(
            "the pronoun is still capitalised inline rather than by the rule",
            !svc.contains("standalone English pronoun")
        )
        assertTrue("the commit does not use the rule", svc.contains("WordCase.pronoun("))
        // A fixed slice rather than up to the first ")", which lands inside
        // effLang() itself and made the assertion unreadable.
        val call = svc.substring(svc.indexOf("WordCase.pronoun(")).take(48)
        assertTrue(
            "the pronoun rule is asked about the selected language again: $call",
            call.contains("effLang()") && !call.contains("currentLangCode()")
        )
    }

    @Test
    fun `the effective language is what the rules beside it ask`() {
        // The reason this was a defect and not a preference. Within the same
        // commit block the correction, the alternate dictionary and the
        // locale all come from effLang()/effLocale(); the pronoun was the one
        // line reaching past them to the selected language.
        val svc = src().resolve("com/rimboard/keyboard/RimBoardService.kt").readText()
        val block = svc.substring(
            svc.indexOf("private fun commitComposedWord")
        ).substringBefore("ic.commitText(finalWord + separator, 1)")
        assertTrue(
            "the commit block no longer resolves an effective language, so this " +
                "test has stopped describing it",
            block.contains("effLang()") && block.contains("effLocale()")
        )
        // Comments stripped first: the line that made this change explains
        // itself by naming the call it replaced, and a scan that cannot tell a
        // mention from a use reports its own documentation as the defect.
        val code = block.lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString(" ")
        assertEquals(
            "the selected language is consulted inside the commit block again",
            0, code.split("currentLangCode()").size - 1
        )
    }
}
