package com.rimboard.keyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules [PersonalCase] refuses on, and the vote underneath them.
 *
 * Like `PostCorrectionTest`, most of this file is refusals, and for the same
 * reason: the interesting question about a rule that changes a letter nobody
 * typed is never "does it fire" but "does it decline". `PersonalCaseStoreTest`
 * asks what the counting does over a run of commits, and
 * `PersonalCaseEngineTest` asks whether the word ever reaches the strip to be
 * cased in the first place.
 */
class PersonalCaseTest {

    // ---- what counts as evidence -------------------------------------------

    @Test
    fun `a word typed mid-sentence is evidence about that word`() {
        assertTrue(PersonalCase.counts("Anthropic", sentenceInitial = false, capsLock = false))
    }

    @Test
    fun `the first word of a sentence says nothing about the word`() {
        // Auto-capitalisation put the capital there. Counting these would
        // teach the keyboard that "The", "But" and "So" are proper nouns,
        // which is most of the sentences anybody writes.
        assertFalse(PersonalCase.counts("The", sentenceInitial = true, capsLock = false))
        // And a lower-case opener is no better as evidence the other way: it
        // is the same position, so it is refused too rather than counted as a
        // vote against a capital.
        assertFalse(PersonalCase.counts("the", sentenceInitial = true, capsLock = false))
    }

    @Test
    fun `a latched shift key is a tone of voice, not a spelling`() {
        assertFalse(PersonalCase.counts("HELLO", sentenceInitial = false, capsLock = true))
    }

    @Test
    fun `a single letter is not a word to remember the case of`() {
        assertFalse(PersonalCase.counts("I", sentenceInitial = false, capsLock = false))
    }

    // ---- the vote -----------------------------------------------------------

    @Test
    fun `the first form seen takes the slot`() {
        assertEquals("Anthropic" to 1, PersonalCase.vote("", 0, "Anthropic"))
    }

    @Test
    fun `agreement builds the lead and disagreement spends it`() {
        assertEquals("Anthropic" to 2, PersonalCase.vote("Anthropic", 1, "Anthropic"))
        assertEquals("Anthropic" to 1, PersonalCase.vote("Anthropic", 2, "anthropic"))
    }

    @Test
    fun `a form that loses its lead is replaced by the next one seen`() {
        // Boyer-Moore: the held form survives exactly as long as it has been
        // written more often than everything else put together.
        val (form, lead) = PersonalCase.vote("Anthropic", 0, "anthropic")
        assertEquals("anthropic" to 1, form to lead)
    }

    @Test
    fun `a word written both ways never settles`() {
        // Six commits, alternating. The lead never reaches the bar, so nothing
        // is ever offered -- which is the right answer for somebody who
        // genuinely writes a word both ways.
        var form = ""
        var lead = 0
        var offered = 0
        for (w in listOf("Rose", "rose", "Rose", "rose", "Rose", "rose")) {
            PersonalCase.vote(form, lead, w).let { form = it.first; lead = it.second }
            if (PersonalCase.formFor("rose", form, lead) != null) offered++
        }
        assertEquals("a word written both ways must never settle on one", 0, offered)
    }

    @Test
    fun `a name written twice settles at once`() {
        var form = ""
        var lead = 0
        repeat(2) { PersonalCase.vote(form, lead, "Anthropic").let { form = it.first; lead = it.second } }
        assertEquals("Anthropic", PersonalCase.formFor("anthropic", form, lead))
    }

    // ---- the bar ------------------------------------------------------------

    @Test
    fun `one sighting is not enough`() {
        assertNull(PersonalCase.formFor("anthropic", "Anthropic", 1))
        assertEquals("Anthropic", PersonalCase.formFor("anthropic", "Anthropic", PersonalCase.MIN_LEAD))
    }

    @Test
    fun `a form identical to the key is no opinion at all`() {
        // Somebody who writes a word in lower case does not get told so; they
        // get nothing, which leaves whatever the curated model said standing.
        assertNull(PersonalCase.formFor("dank", "dank", 9))
    }

    @Test
    fun `a form that is not a casing of the key is refused`() {
        // These two fields are read back off a file. A mismatched pair would
        // put an unrelated word on the strip under another word's key, which
        // is the one way this rule could do real damage.
        assertNull(PersonalCase.formFor("anthropic", "wolfram", 9))
        assertNull(PersonalCase.formFor("anthropic", "Anthropics", 9))
    }

    // ---- where it may speak -------------------------------------------------

    @Test
    fun `a word already carrying a capital is left exactly as it is`() {
        // WordCase.match has already copied the case of what was typed, and
        // the curated prediction model has already capitalised the German
        // noun. This speaks only where neither of them had anything to say.
        val store = { _: String -> "Anthropic" }
        assertEquals("ANTHROPIC", PersonalCase.cased("ANTHROPIC", store))
        assertEquals("Anthropic", PersonalCase.cased("Anthropic", store))
        assertEquals("iPhone", PersonalCase.cased("iPhone") { "IPhone" })
    }

    @Test
    fun `a lower-case word is the one case this answers`() {
        assertEquals("Anthropic", PersonalCase.cased("anthropic") { "Anthropic" })
        assertEquals("anthropic", PersonalCase.cased("anthropic") { null })
    }

    @Test
    fun `the store is asked under the word itself`() {
        // Valid as a key precisely because anything reaching the lookup is
        // already entirely lower case, which is the previous test's rule.
        val asked = ArrayList<String>()
        PersonalCase.cased("anthropic") { asked.add(it); null }
        assertEquals(listOf("anthropic"), asked)
    }

    @Test
    fun `a single letter is never looked up`() {
        var asked = 0
        assertEquals("a", PersonalCase.cased("a") { asked++; "A" })
        assertEquals(0, asked)
    }
}
