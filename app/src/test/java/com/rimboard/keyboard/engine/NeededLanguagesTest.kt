package com.rimboard.keyboard.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Who still needs which dictionary.
 *
 * The cache is one map for the process and two components fill it, wanting
 * different languages: the keyboard wants the two the user selected, the spell
 * checker wants whatever locale the field it was bound to declares. Each used
 * to trim to what *it* needed, which is a polite way of evicting the other
 * one's working set — invisible until the next word arrives and the asset is
 * parsed again to answer it, on a binder thread in the spell checker's case.
 */
class NeededLanguagesTest {

    @After
    fun clear() {
        // Process-wide state: a case that leaves an owner declared fails the
        // next one for reasons it cannot see.
        SuggestionEngine.declareNeeded(SuggestionEngine.NEEDED_KEYBOARD, emptySet())
        SuggestionEngine.declareNeeded(SuggestionEngine.NEEDED_SPELL, emptySet())
    }

    @Test
    fun `nothing is needed until something says so`() {
        assertEquals(emptySet<String>(), SuggestionEngine.neededLanguages())
    }

    @Test
    fun `what one component needs does not hide what the other does`() {
        // The whole point. A trim driven by the keyboard alone would have kept
        // tr and en and dropped fr, which is the language the spell checker is
        // in the middle of using.
        SuggestionEngine.declareNeeded(SuggestionEngine.NEEDED_KEYBOARD, setOf("tr", "en"))
        SuggestionEngine.declareNeeded(SuggestionEngine.NEEDED_SPELL, setOf("fr"))
        assertEquals(setOf("tr", "en", "fr"), SuggestionEngine.neededLanguages())
    }

    @Test
    fun `an overlap is counted once`() {
        SuggestionEngine.declareNeeded(SuggestionEngine.NEEDED_KEYBOARD, setOf("tr", "en"))
        SuggestionEngine.declareNeeded(SuggestionEngine.NEEDED_SPELL, setOf("en"))
        assertEquals(setOf("tr", "en"), SuggestionEngine.neededLanguages())
    }

    @Test
    fun `a component that goes away stops holding its languages`() {
        // What the spell checker says on destroy, and what makes the memory
        // actually reclaimable rather than merely trimmed around.
        SuggestionEngine.declareNeeded(SuggestionEngine.NEEDED_KEYBOARD, setOf("tr"))
        SuggestionEngine.declareNeeded(SuggestionEngine.NEEDED_SPELL, setOf("fr"))
        SuggestionEngine.declareNeeded(SuggestionEngine.NEEDED_SPELL, emptySet())
        assertEquals(setOf("tr"), SuggestionEngine.neededLanguages())
    }

    @Test
    fun `a redeclaration replaces rather than accumulates`() {
        // A session per text field, each declaring its own locale: without
        // replacement the set would only ever grow, and nothing would be
        // reclaimable after a few fields.
        SuggestionEngine.declareNeeded(SuggestionEngine.NEEDED_SPELL, setOf("fr"))
        SuggestionEngine.declareNeeded(SuggestionEngine.NEEDED_SPELL, setOf("de"))
        assertEquals(setOf("de"), SuggestionEngine.neededLanguages())
    }
}
