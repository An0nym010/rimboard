package com.rimboard.keyboard.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that decides whether the space bar may rewrite what was typed.
 *
 * Everything here is about *composition*. Each clause is simple on its own;
 * what matters is that they are asked as one question by callers that must
 * agree — the strip, which bolds what the separator will commit, and the
 * commit itself — and that the two things a separator can do are kept apart.
 */
class AutocorrectGateTest {

    private fun commit(
        active: Boolean = true,
        identifier: Boolean = false,
        separator: String = " "
    ) = AutocorrectGate.mayCommit(active, identifier, separator)

    private fun correct(
        active: Boolean = true,
        identifier: Boolean = false,
        separator: String = " ",
        composing: String,
        sentenceInitial: Boolean = false,
        lang: String = "en"
    ) = AutocorrectGate.mayCorrect(
        active, identifier, separator, composing, sentenceInitial, lang
    )

    @Test
    fun `an ordinary lowercase word may be corrected`() {
        assertTrue(correct(composing = "helko"))
    }

    @Test
    fun `a capitalised word in mid-sentence is a name and is left alone`() {
        // The fault this exists for. "César", "Noël", "Parijs" and "Sundays"
        // were all committed as some other word.
        assertFalse(correct(composing = "César"))
        assertFalse(correct(composing = "Parijs"))
    }

    @Test
    fun `the first word of a sentence is still corrected`() {
        // Not a detail: auto-capitalisation puts a capital on the first word of
        // every sentence, so a rule blind to position would switch autocorrect
        // off for a fifth of everything anyone writes.
        assertTrue(correct(composing = "Helko", sentenceInitial = true))
    }

    @Test
    fun `German capitalises every noun, so the name rule cannot apply there`() {
        // Same exclusion, and the same reason, as SpellCandidacy: in German
        // this rule would stop correcting most of the words in a sentence.
        assertTrue(correct(composing = "Hasu", lang = "de"))
        assertFalse(correct(composing = "Hasu", lang = "nl"))
    }

    @Test
    fun `a shortcut still expands for a capitalised trigger`() {
        // The regression this split exists to prevent. Shortcut expansion and
        // autocorrect shared one gate, so the name rule silently switched off
        // a rule the *user had written down themselves* — "Omw" stopped
        // becoming "On my way" anywhere but the start of a sentence.
        //
        // A shortcut is explicit configuration; a heuristic about names has no
        // standing to overrule it.
        assertTrue(commit())
        assertFalse(correct(composing = "Omw"))
    }

    @Test
    fun `the name rule does not rescue a word the other clauses already refuse`() {
        // Order must not matter: a password field is a password field whatever
        // the word looks like, and that has to hold for both questions.
        assertFalse(commit(identifier = true))
        assertFalse(correct(composing = "hunter", identifier = true))
        assertFalse(correct(composing = "Hunter", identifier = true))
        assertFalse(commit(active = false))
        assertFalse(correct(composing = "hunter", active = false))
    }

    @Test
    fun `a separator that ends an identifier still declines`() {
        assertFalse(commit(separator = "@"))
        assertFalse(correct(composing = "helko", separator = "@"))
    }

    @Test
    fun `an empty buffer is not a name`() {
        // composing is read unconditionally now, so the empty case has to be
        // safe rather than merely unreached.
        assertTrue(correct(composing = ""))
    }
}
