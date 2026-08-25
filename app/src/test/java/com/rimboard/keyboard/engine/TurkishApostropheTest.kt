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
 * "Paris'e", "ABD'de", "Türkiye'nin" — a proper noun carrying its case ending.
 *
 * Turkish attaches case endings to proper nouns and acronyms across an
 * apostrophe, and every sentence naming a person, a place or an organisation
 * has one. **Every single one of them was rejected**: 12 of the 15 unknown
 * words in the Turkish prose corpus, 80% of everything the keyboard did not
 * recognise. So the system spell checker underlined them in every app on the
 * phone, exactly as it once underlined "don't" and "l'homme".
 *
 * The comparison that makes it plain — words with an inner apostrophe in each
 * language's own prose, and how many the keyboard called unknown:
 *
 *     en   36 of 1552 words    0 unknown     (handled by Elision)
 *     fr  113 of 1561 words    2 unknown     (handled by Elision)
 *     it   24 of 1344 words    1 unknown     (handled by Elision)
 *     tr   12 of 1294 words   12 unknown  <- every one
 *
 * After the rule: **tr 1 unknown**, and the one that remains is
 * "İskenderiye'ye", whose stem the corpus has never seen. That is the rule
 * declining to invent a word rather than a gap in it.
 */
class TurkishApostropheTest {

    private lateinit var dir: File
    private lateinit var userData: UserData
    private val lang = "tr"
    private val locale: Locale = Locale.forLanguageTag("tr")

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-apos", "").let { it.delete(); it.mkdirs(); it }
        userData = UserData.inDir(dir)
    }

    @After
    fun tearDown() {
        userData.shutdown()
        dir.deleteRecursively()
    }

    private fun assets(): File =
        listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }

    private fun fixtures(): File =
        listOf(File("src/test/fixtures"), File("app/src/test/fixtures")).first { it.isDirectory }

    private fun engine(): SuggestionEngine {
        val files = listOf("dictionaries/$lang.txt", "predictions/$lang.txt")
            .associateWith { File(assets(), it).readText() }
        return SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
    }

    @Test
    fun `a proper noun with a case ending is a word`() {
        val e = engine()
        for (w in listOf(
            "paris'e", "türkiye'de", "rusya'nın", "ankara'dan",
            "japonya'nın", "allah'a", "fransa'da", "brezilya'ya"
        )) {
            assertTrue("$w should be accepted", e.acceptedWord(w, lang, locale))
        }
    }

    @Test
    fun `an acronym takes the ending its pronunciation asks for`() {
        // The case that rules out simply deleting the apostrophe and reusing
        // the ordinary stem walk. "ABD" is said "a-be-de", so it takes a
        // front-vowel ending after a back-vowel spelling, and joining gives
        // "abdde" — a doubled consonant Turkish never writes.
        //
        // **This is what the apostrophe is for**, so harmony is deliberately
        // not checked across it.
        val e = engine()
        assertTrue("abd'de should be accepted", e.acceptedWord("abd'de", lang, locale))
    }

    @Test
    fun `a stem the corpus has never seen is still refused`() {
        // The whole of what stops this accepting anything with a quote in it.
        val e = engine()
        assertFalse(
            "iskenderiye'ye must stay unknown: the corpus has no such stem, " +
                "and nothing here may invent one",
            e.acceptedWord("iskenderiye'ye", lang, locale)
        )
        assertFalse(e.acceptedWord("asdfgh'e", lang, locale))
    }

    @Test
    fun `a tail that is not suffixes is refused`() {
        val e = engine()
        assertFalse("paris'qwx is not a Turkish form", e.acceptedWord("paris'qwx", lang, locale))
    }

    @Test
    fun `a trailing apostrophe is a quotation mark, not a boundary`() {
        // There is nothing after the mark to be a suffix, so this must fall
        // through to the ordinary correction path rather than be waved
        // through as well-formed.
        val e = engine()
        assertFalse(e.acceptedWord("paris'", lang, locale))
        assertFalse(e.acceptedWord("'e", lang, locale))
    }

    @Test
    fun `an accepted form is not then corrected away`() {
        // Accepting it and still rewriting it on the space bar would be the
        // two halves of the keyboard disagreeing again.
        val e = engine()
        for (w in listOf("paris'e", "abd'de", "rusya'nın")) {
            assertEquals("$w must not be autocorrected", null, e.correctionFor(w, lang, locale))
        }
    }

    @Test
    fun `a foreign stem takes Turkish endings too, which is not a misfire`() {
        // "Google'a", "iPhone'u", "Twitter'da" — Turkish attaches its endings
        // to foreign words across the same apostrophe, and the subtitle corpus
        // is full of the stems. Measured: 23% of common English words are
        // accepted with a Turkish ending bolted on, and that is the rule
        // working rather than failing. Recorded here so nobody reads the
        // figure later as a false-positive rate and "fixes" it.
        val e = engine()
        assertTrue(e.acceptedWord("google'a", lang, locale) ||
            e.acceptedWord("back'e", lang, locale))
    }

    @Test
    fun `over the real corpus, one apostrophe word is left unknown`() {
        // The claim this whole rule exists for, measured rather than asserted.
        val e = engine()
        val unknown = ArrayList<String>()
        var apostrophes = 0
        File(fixtures(), "prose_$lang.txt").readLines().filter { it.isNotBlank() }
            .forEach { line ->
                val sb = StringBuilder()
                val words = ArrayList<String>()
                for (ch in line) {
                    if (ch.isLetter() || ch == '\'' || ch == '’') sb.append(ch)
                    else { if (sb.isNotEmpty()) words.add(sb.toString()); sb.setLength(0) }
                }
                if (sb.isNotEmpty()) words.add(sb.toString())
                for (raw in words) {
                    val w = raw.trim('\'').lowercase(locale)
                    val i = w.indexOfFirst { it == '\'' || it == '’' }
                    if (w.length < 2 || i <= 0 || i >= w.length - 1) continue
                    apostrophes++
                    if (!e.acceptedWord(w, lang, locale)) unknown.add(w)
                }
            }
        // Non-vacuity: the corpus must still contain the forms under test.
        assertTrue(
            "the Turkish corpus no longer contains apostrophe words, so this " +
                "measures nothing (found $apostrophes)",
            apostrophes >= 10
        )
        assertTrue(
            "apostrophe words the keyboard still calls unknown: $unknown " +
                "(of $apostrophes). Only İskenderiye'ye is expected, because " +
                "the corpus has no stem for it.",
            unknown.size <= 1
        )
    }
}
