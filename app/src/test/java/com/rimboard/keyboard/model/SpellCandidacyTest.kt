package com.rimboard.keyboard.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * What gets a red underline, which until this was extracted was the one part
 * of the spell checker nothing could reach: a private method on a class that
 * needs a bound text field to exist.
 */
class SpellCandidacyTest {

    private fun en(word: String, initial: Boolean = true) =
        SpellCandidacy.worthChecking(word, initial, "en", Locale.ENGLISH)

    @Test
    fun `an ordinary word is judged`() {
        assertTrue(en("hello"))
        assertTrue(en("stroe"))
        assertTrue(en("don't"))
    }

    @Test
    fun `the things that are not words are declined`() {
        assertFalse("too short", en("ok"))
        assertFalse("version number", en("covid19"))
        assertFalse("acronym", en("NASA"))
        assertFalse("camelCase", en("myVariable"))
        assertFalse("an address", en("a@b.com"))
        assertFalse("a path", en("usr/bin"))
    }

    @Test
    fun `a capitalised word opening a sentence is still judged`() {
        // "Helo, there" must still be caught: the capital is the sentence
        // starting, not a name.
        assertTrue(en("Helo", initial = true))
    }

    @Test
    fun `a capitalised word inside a sentence is left alone`() {
        // The false positive this exists to stop. Names are in no dictionary,
        // and this service never learns, so judging them meant every name the
        // user typed came back underlined with a "correction" to some real
        // word a letter away.
        assertFalse(en("Sam", initial = false))
        assertFalse(en("Ankara", initial = false))
    }

    @Test
    fun `German capitals are ordinary and stay judged`() {
        // Every German noun is capitalised, so the rule above would decline
        // most of a German sentence. This is the caveat that makes the rule
        // safe rather than the rule itself being wrong.
        val de = { w: String -> SpellCandidacy.worthChecking(w, false, "de", Locale.GERMAN) }
        assertTrue(de("Haus"))
        assertTrue(de("Strasse"))
    }

    @Test
    fun `Turkish uppercase folding does not misread a lowercase word`() {
        // uppercase() is locale-sensitive here on purpose, and Turkish is
        // where that bites: "i" uppercases to "I-with-dot", so a lowercase
        // Turkish word must not compare equal to its own uppercase form and
        // be mistaken for an acronym.
        val tr = { w: String -> SpellCandidacy.worthChecking(w, true, "tr", Locale("tr")) }
        assertTrue(tr("gidiyor"))
        assertTrue(tr("bilgi"))
        assertFalse("a real acronym is still declined", tr("TRT"))
    }
}
