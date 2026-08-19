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
class PersonalWordsTest {

    private fun of(vararg names: String) = PersonalWords.of(names.asSequence())

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
        assertTrue("digits should have dropped these", PersonalWords.of(many).isEmpty())
        assertEquals(setOf("aaa", "bbb", "ccc"), PersonalWords.of(plain))
        assertEquals(2, PersonalWords.of(sequenceOf("one two three"), limit = 2).size)
    }

    @Test
    fun `matching folds both sides the same way`() {
        val names = of("Ipek")
        assertTrue(PersonalWords.contains(names, "ipek"))
        assertTrue(PersonalWords.contains(names, "IPEK"))
        assertTrue(PersonalWords.contains(names, "Ipek"))
        assertFalse(PersonalWords.contains(names, "ipel"))
        assertFalse("an empty book matches nothing", PersonalWords.contains(emptySet(), "ipek"))
    }

    // ---- the one difference between the two sources ----

    @Test
    fun `a dictionary entry keeps its digits, a contact drops them`() {
        // The single genuine difference, and the reason this is one rule with
        // a parameter rather than two rules that would drift apart. A phone
        // number in a contact's name is not a name; "covid19" typed into the
        // personal dictionary is a word somebody sat down and added.
        assertEquals(
            emptySet<String>(),
            PersonalWords.of(sequenceOf("covid19"), dropEntriesWithDigits = true)
        )
        assertEquals(
            setOf("covid"),
            PersonalWords.of(sequenceOf("covid19"), dropEntriesWithDigits = false)
        )
    }

    @Test
    fun `a multi-word dictionary entry gives up both words`() {
        // Android's personal dictionary allows phrases, and the spell checker
        // is handed one word at a time, so a phrase kept whole would match
        // nothing it ever sees.
        assertEquals(
            setOf("new", "york"),
            PersonalWords.of(sequenceOf("New York"), dropEntriesWithDigits = false)
        )
    }
}
