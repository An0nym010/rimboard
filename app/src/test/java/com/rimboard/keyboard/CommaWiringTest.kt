package com.rimboard.keyboard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The seams between a committed word and a comma in front of it.
 *
 * `CommaRuleTest` holds the policy and `CommaAssetTest` the lists; neither can
 * see whether the keyboard ever asks, because the asking happens in an
 * `InputMethodService`. Source-read, like `PostCorrectionWiringTest`,
 * `PersonalCaseWiringTest` and `ExpandedStripWiringTest` -- and this feature
 * needs it more than most, since it is the third thing in this app that edits
 * text the user has already read past.
 */
class CommaWiringTest {

    private fun source(rel: String): String {
        for (p in listOf("src/main/java/$rel", "app/src/main/java/$rel")) {
            val f = File(p)
            if (f.isFile) return f.readText()
        }
        throw AssertionError("$rel not found from ${File(".").absolutePath}")
    }

    /** Code with its comments removed; a scan cannot tell code from prose. */
    private fun codeOnly(src: String): String =
        src.lineSequence()
            .map { line ->
                val i = line.indexOf("//")
                if (i >= 0) line.substring(0, i) else line
            }
            .joinToString(separator = "\n")
            .replace(Regex("(?s)/\\*.*?\\*/"), "")

    private fun service() = codeOnly(source("com/rimboard/keyboard/RimBoardService.kt"))

    private fun bodyOf(src: String, decl: String): String {
        val start = src.indexOf(decl)
        assertTrue("$decl not found - was it renamed?", start >= 0)
        val ends = listOf("\n    private fun ", "\n    fun ", "\n    override fun ")
            .map { src.indexOf(it, start + decl.length) }
            .filter { it >= 0 }
        return src.substring(start, ends.minOrNull() ?: src.length)
    }

    @Test
    fun `the field is read back before anything is written`() {
        // The refusal that is not in CommaRule, because it needs an
        // InputConnection. Unless the text reads exactly `" " + word +
        // separator`, the commit is not where the rule thinks it is -- and a
        // comma written on that assumption lands after a full stop, after a
        // comma already there, or across a newline.
        val body = bodyOf(service(), "private fun maybeComma(")
        val read = body.indexOf("getTextBeforeCursor(")
        val write = body.indexOf("deleteSurroundingText(")
        assertTrue("nothing reads the field back any more", read >= 0)
        assertTrue("nothing writes the comma any more", write >= 0)
        assertTrue(
            "the comma is written before the text it assumes is checked, so it " +
                "can land anywhere the cursor has since moved",
            read < write
        )
    }

    @Test
    fun `it is off in the fields that take no punctuation help`() {
        val body = bodyOf(service(), "private fun maybeComma(")
        assertTrue(
            "the preference is no longer read, so the switch does nothing",
            body.contains("Prefs.commaHints(this)")
        )
        for (gate in listOf("isPassword", "isEmailOrUri", "fieldNoSuggestions")) {
            assertTrue(
                "a comma can now be written into a $gate field",
                body.contains(gate)
            )
        }
    }

    @Test
    fun `one silent change per commit`() {
        // Post-correction's own rule, and it applies unchanged: two changes
        // would leave the revert chip able to undo only one of them, and a
        // chip that undoes half of what just happened is worse than no chip.
        val body = bodyOf(service(), "private fun commitComposedWord(")
        val call = body.indexOf("maybeComma(")
        assertTrue("the comma rule is never asked", call >= 0)
        val guard = body.indexOf("post == null && finalWord == typed &&")
        assertTrue(
            "the comma no longer stands down when the commit already changed " +
                "something -- so a post-correction and a comma can now both " +
                "fire on one word, with one chip between them",
            guard in 0 until call
        )
    }

    @Test
    fun `the undo reaches back over the space`() {
        // The comma sits before the space, so a revert region that starts at
        // the word leaves the comma behind and the chip does nothing visible.
        val body = bodyOf(service(), "private fun commitComposedWord(")
        assertTrue(
            "the comma revert no longer includes the space in front of the " +
                "word, so undoing it would leave the comma in place",
            body.contains("original = \" \" + finalWord") &&
                body.contains("committed = \", \" + finalWord")
        )
        assertTrue(
            "the chip has lost its label and would name a leading space",
            body.contains("label = finalWord")
        )
    }

    @Test
    fun `the sentence position is what the shares were counted against`() {
        // The lists count mid-sentence occurrences only, because a sentence
        // cannot open with a comma before its first word. Passing anything but
        // the live sentence position here would apply a number measured over
        // one population to another.
        val body = bodyOf(service(), "private fun maybeComma(")
        assertTrue(
            "the rule is no longer told where in the sentence it is",
            body.contains("atSentenceStart")
        )
    }
}
