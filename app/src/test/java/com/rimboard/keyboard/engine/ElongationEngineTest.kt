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

    /**
     * "www" is not "w" held down.
     *
     * Found on the device: typing "www." into a field gave **"W."**. "www" is
     * in none of the 22 shipped dictionaries, and it collapses onto "w" --
     * 9,179 in the English list -- so the corrector called it an elongation,
     * underlined it, and committed a single letter on the separator. Every URL
     * anyone types starts that way. It is also the one URL shape the rest of
     * the app cannot protect: [com.rimboard.keyboard.model.ProseContext]
     * declines to correct inside an address on the evidence of "@", "/" and
     * ":", and "www." has none of those, only the full stop it deliberately
     * ignores.
     *
     * **And it was never only "www".** The cases below are enumerated from the
     * shipped word lists rather than from what somebody happened to notice:
     * **756** entries across all 22 languages are one letter repeated, and
     * **751** of them collapse onto a spelling the corpus ranks above them,
     * which is exactly what [SuggestionEngine.elongationBase] asks before it
     * overrules anybody. English "mmm" runs at 49,655, Greek "εεε" at 12,158,
     * Turkish "eee" at 12,100, Hungarian "ööö" at 6,866 -- every one of them
     * called a misspelling of a shorter run of itself. Greek, Russian and
     * Ukrainian have no "w" and so were untouched by the case that found this;
     * they were not untouched by the rule.
     *
     * A floor on the length of the base does not fix it, which is why the rule
     * is about the word instead: "www" collapses to "ww" as well, and "ww" is
     * in the English list at 215, so a length floor would have committed
     * "ww" -- the same bug one letter longer.
     *
     * **One correction of this shape survives and should.** Hungarian answers
     * "ooo" with "ööö", which is not the elongation rule: o and ö are the same
     * key, the corpus holds 6,866 of the accented spelling against 127 of the
     * bare one, and [Dictionary.accentedFormOf] asks for a ratio of 50 before
     * it will say so. That is a skipped long press, not a held-down key, and
     * the two rules are told apart here by folding the accents off the answer
     * -- which is the whole of what makes it a different rule.
     */
    @Test
    fun `no word that is one letter repeated is corrected to a shorter run of it`() {
        val casualties = StringBuilder()
        var population = 0
        for (lang in shippedLanguages()) {
            val loc = Locale.forLanguageTag(lang)
            val e = realEngine(lang)
            // "www" is in no list at all, so it has to be added by hand; the
            // rest come from the language's own dictionary.
            for (w in oneLetterEntries(lang) + "www") {
                population++
                val c = e.correctionFor(w, lang, loc) ?: continue
                if (com.rimboard.keyboard.model.Diacritics.fold(c.lowercase(loc)) == w) continue
                casualties.append(" $lang: $w->$c")
            }
        }
        println("one-letter-repeated entries checked: $population")
        assertTrue("no entries found; the enumeration is broken", population >= 700)
        assertEquals(
            "the keyboard replaced what was typed with a shorter run of the " +
                "same letter, on the space bar.$casualties",
            "", casualties.toString()
        )
    }

    /** Every entry in [lang]'s shipped list that is one letter over and over. */
    private fun oneLetterEntries(lang: String): List<String> =
        File(assetDir(), "dictionaries/$lang.txt").readLines()
            .mapNotNull { it.substringBefore(' ').ifEmpty { null } }
            .filter { w -> w.length >= 3 && w[0].isLetter() && w.all { it == w[0] } }

    /** And the rule it exists for still fires, in the languages that hold it. */
    @Test
    fun `a real word with a letter held down is still collapsed`() {
        val missed = StringBuilder()
        for (lang in shippedLanguages()) {
            val e = realEngine(lang)
            val loc = Locale.forLanguageTag(lang)
            // Only where the collapsed spelling is actually in that language's
            // list -- "hello" is not Greek vocabulary and this is not a claim
            // that it should be.
            if (!e.dictionary(lang, loc).contains("hello")) continue
            if (e.correctionFor("hellooo", lang, loc) != "hello") {
                missed.append(" $lang: ${e.correctionFor("hellooo", lang, loc)}")
            }
        }
        assertEquals("elongation stopped being collapsed.$missed", "", missed.toString())
    }

    private fun shippedLanguages(): List<String> =
        File(assetDir(), "dictionaries").listFiles().orEmpty()
            .map { it.name.removeSuffix(".txt") }.sorted()

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
