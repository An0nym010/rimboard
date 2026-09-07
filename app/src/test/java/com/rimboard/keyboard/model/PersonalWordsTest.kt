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

    // ---- offering them, not only accepting them ----

    @Test
    fun `the index keeps the spelling each word was declared with`() {
        // The shield never needed this; offering does. Somebody typed
        // "Kubernetes" into a settings screen on purpose, and a strip that
        // hands back "kubernetes" has taken the declaration and dropped the
        // half of it that was hardest to type.
        val ix = PersonalWords.index(sequenceOf("Kubernetes", "New York"))
        assertEquals(mapOf("kubernetes" to "Kubernetes", "new" to "New", "york" to "York"), ix)
        assertEquals("of() is this with the spellings thrown away", ix.keys, PersonalWords.of(sequenceOf("Kubernetes", "New York")))
    }

    @Test
    fun `the first spelling wins where two entries fold together`() {
        // Arbitrary, and the only honest answer: both were declared, and
        // nothing here can tell which one was meant.
        assertEquals(
            mapOf("ada" to "Ada"),
            PersonalWords.index(sequenceOf("Ada", "ada"))
        )
    }

    @Test
    fun `a prefix finds the words that continue it, shortest first`() {
        val ix = PersonalWords.index(sequenceOf("Anthropic", "Anthropoid", "Ant", "Beta"))
        // "ant" itself is not a continuation of "ant" -- offering the word
        // already in the field is the strip arguing with the screen.
        assertEquals(
            listOf("anthropic", "anthropoid"),
            PersonalWords.startingWith(ix, "ant", 8)
        )
        assertEquals(listOf("anthropic"), PersonalWords.startingWith(ix, "ant", 1))
    }

    @Test
    fun `the prefix search folds both sides the way the shield does`() {
        val ix = PersonalWords.index(sequenceOf("Kubernetes"))
        assertEquals(listOf("kubernetes"), PersonalWords.startingWith(ix, "KUBE", 8))
        assertEquals(listOf("kubernetes"), PersonalWords.startingWith(ix, "Kube", 8))
        assertEquals(emptyList<String>(), PersonalWords.startingWith(ix, "kube", 0))
    }

    @Test
    fun `nothing is offered from an empty index or an empty prefix`() {
        val ix = PersonalWords.index(sequenceOf("Kubernetes"))
        assertEquals(emptyList<String>(), PersonalWords.startingWith(emptyMap(), "kube", 8))
        assertEquals(emptyList<String>(), PersonalWords.startingWith(ix, "", 8))
        assertEquals(emptyList<String>(), PersonalWords.within(emptyMap(), "kube", 2, ::naive))
        assertEquals(emptyList<String>(), PersonalWords.within(ix, "", 2, ::naive))
    }

    @Test
    fun `a typo of a declared word is found and an unrelated one is not`() {
        val ix = PersonalWords.index(sequenceOf("Kubernetes", "Anthropic"))
        assertEquals(listOf("kubernetes"), PersonalWords.within(ix, "kubernets", 2, ::naive))
        assertEquals(emptyList<String>(), PersonalWords.within(ix, "elephant", 2, ::naive))
        assertEquals(
            "a word is not a correction of itself",
            emptyList<String>(), PersonalWords.within(ix, "Kubernetes", 2, ::naive)
        )
    }

    @Test
    fun `the nearer typo comes first`() {
        val ix = PersonalWords.index(sequenceOf("Kubernetes", "Kuberneres"))
        // One edit from "kuberneres", two from "kubernetes".
        assertEquals(
            listOf("kuberneres", "kubernetes"),
            PersonalWords.within(ix, "kubernerez", 2, ::naive)
        )
    }

    /** Plain Levenshtein: this file tests the walk, not the measure. */
    private fun naive(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(
                    prev[j] + 1,
                    cur[j - 1] + 1,
                    prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                )
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return prev[b.length]
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
