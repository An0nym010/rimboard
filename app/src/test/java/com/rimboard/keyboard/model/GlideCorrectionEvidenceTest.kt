package com.rimboard.keyboard.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Correcting a swiped word is the strongest thing the user ever says about
 * which language they are writing, and it was the one commit that said nothing.
 *
 * [LanguageBoost] decides which of a bilingual user's two languages holds the
 * primary slot, and it decides it from committed words: "each committed word is
 * evidence". `ALT_RUN` is 1, so a single word known only to the second language
 * swaps the ranking for every keystroke that follows.
 *
 * Every path that puts a word in the field calls `noteCommittedWord`, which is
 * what feeds that machine -- except `replaceLastGlideWith`, the path taken when
 * the user swipes a word and then taps a different one from the strip. So the
 * machine was not merely missing an observation there. The glide's own commit
 * had already noted the word the user went on to *reject*, and nothing ever
 * corrected it: the evidence on record was the wrong word, and the tap that
 * said so was thrown away.
 *
 * It is also the case where the evidence is best. A glide decodes against the
 * ranked language, so the wrong-language guess is exactly what the user is
 * correcting when they reach for the strip.
 */
class GlideCorrectionEvidenceTest {

    /** A word only the second language knows -- unambiguous evidence. */
    private fun LanguageBoost.altWord() = note(inPrimary = false, inAlt = true)

    /** A word the first language knows. Weak evidence; most words are in both. */
    private fun LanguageBoost.primWord() = note(inPrimary = true, inAlt = false)

    @Test
    fun `the correction is what swaps the languages`() {
        // What the user did: swiped, got a word from the primary language,
        // tapped the second-language word they meant.
        val b = LanguageBoost()
        b.primWord()          // the glide's guess, noted at the glide commit
        assertFalse("nothing has swapped yet", b.boosted)
        b.altWord()           // the tap that corrected it
        assertTrue(
            "the tap that corrected a swipe is the clearest statement there " +
                "is about which language is being written, and ALT_RUN is 1",
            b.boosted
        )
    }

    @Test
    fun `without the correction the rejected word is the only evidence`() {
        // The same sequence with the tap unrecorded, which is what shipped:
        // the machine keeps the guess the user threw away.
        val b = LanguageBoost()
        b.primWord()
        assertFalse(
            "the engine goes on ranking the language the user just corrected " +
                "away from, for every keystroke after it",
            b.boosted
        )
    }

    /** Unit tests run from the module directory; tolerate the project root too. */
    private fun src(): File =
        listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }

    private fun bodyOf(name: String): String {
        val svc = src().resolve("com/rimboard/keyboard/RimBoardService.kt").readText()
        val start = svc.indexOf("private fun $name(")
        assertTrue("$name is gone; this scan needs rewriting", start >= 0)
        val next = Regex("\n    private fun ").find(svc, start + 10)?.range?.first ?: svc.length
        return svc.substring(start, next)
    }

    @Test
    fun `replacing a glided word records the evidence`() {
        assertTrue(
            "replaceLastGlideWith commits a word without telling the language " +
                "machine, so the rejected guess stays on the record",
            bodyOf("replaceLastGlideWith").contains("noteCommittedWord(")
        )
    }

    @Test
    fun `and does not count it as a second word typed`() {
        // One word was written, not two. The glide already counted it, so the
        // replacement must take the evidence without taking the tally.
        assertTrue(
            "the replacement counts a second word in the typing statistics",
            bodyOf("replaceLastGlideWith").contains("countAsWord = false")
        )
    }
}
