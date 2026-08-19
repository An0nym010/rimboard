package com.rimboard.keyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning an address book into words.
 *
 * The cost of getting this wrong is not a crash. Too greedy and ordinary words
 * stop being spell-checked because somebody put them in a contact's name; too
 * strict and the person you write to every day stays underlined, which is the
 * whole reason the permission was added.
 */
class ContactNamesTest {

    private fun of(vararg names: String) = ContactNames.of(names.asSequence())

    @Test
    fun `a plain name gives up both its parts`() {
        assertEquals(setOf("ahmet", "yilmaz"), of("Ahmet Yilmaz"))
    }

    @Test
    fun `a hyphenated name matches either half written alone`() {
        // The spell checker's own tokeniser splits on the hyphen too, so a
        // name kept whole here would never match anything it is handed.
        assertEquals(setOf("anne", "marie"), of("Anne-Marie"))
    }

    @Test
    fun `an apostrophe stays inside the name`() {
        assertEquals(setOf("o'brien"), of("O'Brien"))
    }

    @Test
    fun `a contact saved as a number contributes nothing`() {
        // Dropped whole rather than split: a name with a digit anywhere in it
        // is an identifier, and the letters around the digits are not names.
        assertEquals(emptySet<String>(), of("+90 555 1234"))
        assertEquals(emptySet<String>(), of("Ext 4021"))
    }

    @Test
    fun `initials are not words`() {
        // Accepting "a" or "j" would switch off spell checking for two of the
        // commonest typos there are.
        assertEquals(setOf("watson"), of("J. Watson"))
    }

    @Test
    fun `an empty or punctuation-only entry is ignored`() {
        assertEquals(emptySet<String>(), of("", "   ", "---", "..."))
    }

    @Test
    fun `duplicates across contacts are kept once`() {
        assertEquals(setOf("ahmet", "yilmaz", "kaya"), of("Ahmet Yilmaz", "Ahmet Kaya"))
    }

    @Test
    fun `the cap bounds what an address book can cost`() {
        val many = (1..500).asSequence().map { "Firstname$it Lastname" }
        // Digits in the generated names would drop them whole, so spell them.
        val plain = (1..500).asSequence().map { "Aaa Bbb Ccc" }
        assertTrue("digits should have dropped these", ContactNames.of(many).isEmpty())
        assertEquals(setOf("aaa", "bbb", "ccc"), ContactNames.of(plain))
        assertEquals(2, ContactNames.of(sequenceOf("one two three"), limit = 2).size)
    }

    @Test
    fun `matching folds both sides the same way`() {
        val names = of("Ipek")
        assertTrue(ContactNames.contains(names, "ipek"))
        assertTrue(ContactNames.contains(names, "IPEK"))
        assertTrue(ContactNames.contains(names, "Ipek"))
        assertFalse(ContactNames.contains(names, "ipel"))
        assertFalse("an empty book matches nothing", ContactNames.contains(emptySet(), "ipek"))
    }
}
