package com.rimboard.keyboard.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * "hellooo" is in the shipped English dictionary. So are "helloooo" and
 * "hellooooo" — the frequency lists come from web text, where people write
 * that way often enough to clear the cutoff.
 *
 * The keyboard therefore called them correctly spelled, which is why marking
 * an unrecognised word worked on some words and not others: whether a
 * held-down letter was noticed depended entirely on whether that particular
 * elongation had made it into the corpus. Nothing about the behaviour was
 * random, but from the outside it looked it.
 */
class ElongationEngineTest {

    private lateinit var dir: File
    private lateinit var userData: UserData
    private val en = Locale.ENGLISH

    // "hellooo" is present, exactly as it is in the real asset, and rarer than
    // the word it is an elongation of — which is the shape that matters.
    private val assets = mapOf(
        "dictionaries/en.txt" to
            "hello 9000\nhellos 900\nhelloo 120\nhellooo 60\ncool 5000\ncol 40\nbrr 30" +
            // The other shape: a trebled spelling the corpus prefers to its
            // own collapse. German's 1996 reform makes real words of this form.
            "\nvolllaufen 175\nvollaufen 30"
    )

    @Before
    fun setUp() {
        dir = File.createTempFile("elong", "").let { it.delete(); it.mkdirs(); it }
        userData = UserData.inDir(dir)
    }

    @After
    fun tearDown() {
        userData.shutdown()
        dir.deleteRecursively()
    }

    private fun engine() = SuggestionEngine.forTesting(userData) { p -> assets[p]?.byteInputStream() }

    private fun assetDir(): File =
        listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }

    private fun realEngine(lang: String): SuggestionEngine {
        val files = listOf("dictionaries/$lang.txt", "predictions/$lang.txt")
            .associateWith { File(assetDir(), it).readText() }
        return SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
    }

    @Test
    fun `a word in the dictionary is still not accepted if it is an elongation`() {
        assertFalse(engine().acceptedWord("hellooo", "en", en))
        // The word it collapses to is a word, and stays one.
        assertTrue(engine().acceptedWord("hello", "en", en))
    }

    @Test
    fun `the spelling it is an elongation of leads the suggestions`() {
        val out = engine().suggestionsFor(
            "hellooo", "en", en, allowAutocorrect = true, personalized = false
        ).items
        assertEquals("hellooo", out.first())        // slot 0 is the verbatim word
        assertEquals("hello", out.drop(1).first())  // then what it meant
    }

    @Test
    fun `frequency picks between the one and two letter collapses`() {
        // "coool" collapses to "col" and "cool" and both are words here. The
        // common one is what was meant; the rare one is usually the same corpus
        // dust that put the elongation in the list to begin with.
        val out = engine().suggestionsFor(
            "coool", "en", en, allowAutocorrect = true, personalized = false
        ).items
        assertEquals("cool", out.drop(1).first())
    }

    @Test
    fun `an elongation is never offered as a completion of the real word`() {
        // Typing "hello" must not be answered with "hellooo".
        val out = engine().suggestionsFor(
            "hello", "en", en, allowAutocorrect = false, personalized = false
        ).items
        assertTrue("offered an elongation: $out", out.none { it == "hellooo" })
    }

    /**
     * The corpus has to prefer the collapsed spelling, not merely hold it.
     *
     * Being *a* word was the whole test, which is the mistake the bare-key rule
     * made before [Dictionary.accentedFormOf] started asking whether the bare
     * form holds its own. Both rules overrule what somebody typed, and both
     * were deciding on presence rather than on evidence.
     */
    @Test
    fun `a trebled spelling the corpus prefers is not an elongation`() {
        val e = engine()
        // Asked of the fixture list above, which is the one that holds the
        // pair; the language it came from is German and the rule is not.
        assertTrue(e.acceptedWord("volllaufen", "en", en))
        assertNull(e.correctionFor("volllaufen", "en", en))
        // And the ordinary direction is untouched by that.
        assertFalse(e.acceptedWord("hellooo", "en", en))
    }

    /**
     * The words it was destroying, against the shipped German list.
     *
     * The 1996 spelling reform *created* trebled letters in German by ending
     * the rule that dropped one of three, so an ordinary modern word can carry
     * one — and a corpus spanning the change holds both spellings, with the
     * modern one commoner:
     *
     *     helllichten  339 : hellichten   52
     *     volllaufen   175 : vollaufen    30
     *     brennnesseln  39 : brennesseln   8
     *     rollladen     42 : rolladen     10
     *
     * Every one of those was underlined, and committed on the space bar as the
     * spelling that stopped being correct thirty years ago.
     */
    @Test
    fun `German words the 1996 reform trebled survive the space bar`() {
        val e = realEngine("de")
        val de = Locale.GERMAN
        val casualties = StringBuilder()
        for (w in listOf(
            "helllichten", "volllaufen", "brennnesseln", "rollladen",
            // These collapse onto nothing the list holds, so they were always
            // safe. They are here because they are the same shape, and a change
            // to the rule must not start taking them.
            "fitnessstudio", "schifffahrt", "stilllegung", "schlussstrich"
        )) {
            if (!e.acceptedWord(w, "de", de)) casualties.append(" $w(underlined)")
            e.correctionFor(w, "de", de)?.let { casualties.append(" $w->$it") }
        }
        assertEquals("", casualties.toString())
    }

    @Test
    fun `a word the user added by hand is left alone`() {
        // An explicit statement about their own spelling outranks any rule
        // here — someone who adds "hellooo" means it.
        userData.addUserWord("hellooo", en)
        assertTrue(engine().acceptedWord("hellooo", "en", en))
    }

    @Test
    fun `a trebled letter that collapses to nothing known is just an unknown word`() {
        // "brrr" collapses to "br" and "brr"; only "brr" is a word here, so it
        // is an elongation of that. If neither were, it would be an ordinary
        // unknown word rather than a misspelling of something.
        val e = engine()
        assertFalse(e.acceptedWord("brrr", "en", en))
        assertEquals(
            "brr",
            e.suggestionsFor("brrr", "en", en, allowAutocorrect = true, personalized = false)
                .items.drop(1).firstOrNull()
        )
    }
}
