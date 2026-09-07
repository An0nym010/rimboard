package com.rimboard.keyboard.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * The words somebody typed into Android's own personal dictionary, offered.
 *
 * Both personal-dictionary screens are the same act -- a person writing a word
 * out to say "this is a word" -- and until now they did opposite things with
 * it. RimBoard's own screen calls [UserData.addUserWord], which writes a count
 * of three, and three is the bar the completion loop holds a learned word to,
 * so the word is on the strip immediately. Android's screen fed exactly one
 * caller, `acceptedWord`, which only ever answers "leave that word alone" --
 * so the word was recognised, never underlined, never corrected away, and
 * never once suggested. Typing `anthro` for a declared `anthropic` offered
 * `anthem`.
 *
 * Gboard offers both sources. This closes the half of the gap that is a
 * declaration; the address book is left alone deliberately, and the reason is
 * in the last test here.
 */
class DeclaredWordsTest {

    private lateinit var dir: File
    private lateinit var userData: UserData
    private val en = Locale.ENGLISH

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-declared", "").let { it.delete(); it.mkdirs(); it }
        userData = UserData.inDir(dir)
    }

    @After
    fun tearDown() {
        userData.shutdown()
        dir.deleteRecursively()
    }

    private fun assets(): File =
        listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }

    private fun engine(): SuggestionEngine {
        val files = HashMap<String, String>()
        for (kind in listOf("dictionaries", "predictions", "suffixes", "prefixes")) {
            File(assets(), "$kind/en.txt").takeIf { it.isFile }?.let {
                files["$kind/en.txt"] = it.readText()
            }
        }
        val e = SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
        e.dictionary("en", en)
        return e
    }

    private fun strip(e: SuggestionEngine, typed: String, personalized: Boolean = true) =
        e.suggestionsFor(
            typed, "en", en, allowAutocorrect = false, personalized = personalized
        ).items

    /**
     * The declaration, exactly as `UserDictionaryStore` hands it over.
     *
     * "Kubernetes" because it is absent from the shipped English list --
     * checked, not assumed. A control word the dictionary also holds proves
     * nothing here: it would be offered either way, and the test would pass
     * with the whole feature deleted. That trap has caught this project
     * before, in the other direction, with Croatian words English happens to
     * hold.
     */
    private val declared = mapOf("kubernetes" to "Kubernetes")

    @Test
    fun `a declared word is completed, where before it was only accepted`() {
        val e = engine()
        assertFalse(
            "the control has stopped being a control: this word is now in the " +
                "shipped English list, so nothing below distinguishes the " +
                "declaration from the dictionary. Pick another word.",
            strip(e, "kuber").any { it.equals("kubernetes", ignoreCase = true) }
        )
        assertTrue(
            "it was accepted before any of this, and still is",
            engine().also { it.userDictionary = { declared } }.acceptedWord("kubernetes", "en", en)
        )

        e.userDictionary = { declared }
        val items = strip(e, "kuber")
        assertTrue(
            "the declared word is still not offered, which is the whole defect: " +
                "the same act in RimBoard's own screen puts it on the strip at " +
                "once. Got $items",
            items.any { it.equals("kubernetes", ignoreCase = true) }
        )
    }

    @Test
    fun `it comes back spelled the way it was declared`() {
        val e = engine()
        e.userDictionary = { declared }
        assertEquals("Kubernetes", e.personalCase("kubernetes"))
        assertTrue(
            "the strip has the word and the declaration has its spelling, and " +
                "the two did not meet",
            strip(e, "kuber").map { e.personalCase(it) }.contains("Kubernetes")
        )
    }

    @Test
    fun `writing it in lower case does not overrule the declaration`() {
        // Worth pinning because it is the one place the two sources of an
        // opinion about capitals meet, and the answer is not the obvious one.
        //
        // `PersonalCase` can only ever *add* a capital: a majority form equal
        // to the word itself is reported as no opinion at all, precisely so
        // that a lower-case habit never overrules a language's own spelling.
        // The consequence here is that the habit cannot overrule a
        // declaration either, and that is the right way round. A declaration
        // is durable and explicit -- somebody opened a settings screen and
        // typed it out -- where the lower-case side of the vote is
        // deliberately not even written to disk, so making it win would give
        // this rule an answer that changes when the process is killed. One
        // chip somebody can decline beats that.
        val e = engine()
        e.userDictionary = { declared }
        repeat(3) {
            userData.learnWord("kubernetes")
            userData.noteCase("kubernetes", en)
        }
        userData.awaitIdle()
        assertEquals("Kubernetes", e.personalCase("kubernetes"))
    }

    @Test
    fun `a typo of a declared word is corrected toward it`() {
        val e = engine()
        val before = e.correctionCandidates("kubernets", "en", en)
        assertFalse(
            "the control has stopped being a control; see the first test",
            before.any { it.equals("kubernetes", ignoreCase = true) }
        )
        e.userDictionary = { declared }
        val after = e.correctionCandidates("kubernets", "en", en)
        assertTrue(
            "a word added through RimBoard's own screen is corrected toward at " +
                "three uses, and one added through Android's was not. Got $after",
            after.any { it.equals("kubernetes", ignoreCase = true) }
        )
    }

    @Test
    fun `blocking a word still hides it`() {
        // Long-pressing a chip says "never offer me this". A declaration is
        // not a way past that -- it is older, and the block is the more
        // recent instruction.
        val e = engine()
        e.userDictionary = { declared }
        userData.blockWord("kubernetes")
        assertFalse(
            strip(e, "kuber").any { it.equals("kubernetes", ignoreCase = true) }
        )
    }

    @Test
    fun `a declaration is not history, so an incognito field still has it`() {
        // Deliberate, and the one decision in this change worth arguing about.
        // `personalized` means "the words this person has typed", and that is
        // what an incognito field switches off. Android's personal dictionary
        // is not that: it is a list held by the system, shared with every app
        // on the phone, put there on purpose and already behind its own
        // setting and its own permission. `acceptedWord` consults it in an
        // incognito field today, and a shield and an offer that answer
        // different questions about the same list is the shape of fault this
        // project keeps finding.
        val e = engine()
        e.userDictionary = { declared }
        assertTrue(
            strip(e, "kuber", personalized = false)
                .any { it.equals("kubernetes", ignoreCase = true) }
        )
    }

    @Test
    fun `the address book is deliberately not offered`() {
        // Left as a shield, and not because it was forgotten. A contact's
        // display name is inferred rather than declared -- `PersonalWords` is
        // explicit that "Ahmet Yilmaz (work)" contributes "work" -- so these
        // belong at the spare-slot anchor the compound and morphology
        // completions use, not at parity with a word somebody typed out. That
        // is a ranking question of its own and is not answered here.
        val e = engine()
        // Absent from the English list -- "yilmaz" is in it, at 157, so it
        // would have been offered whatever this rule did.
        e.contactNames = { setOf("vrbaczek") }
        assertTrue("a contact name is still accepted", e.acceptedWord("vrbaczek", "en", en))
        assertFalse(
            "contacts have started being offered without the ranking question " +
                "being settled",
            strip(e, "vrbac").any { it.equals("vrbaczek", ignoreCase = true) }
        )
    }
}
