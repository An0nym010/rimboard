package com.rimboard.keyboard.engine

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * What the system spell checker will and will not underline.
 *
 * `acceptedWord` is the one call standing between the engine and a red line
 * under a word in someone else's app, and a false positive there is far more
 * annoying than a missed typo — an underline under every second Turkish word
 * is the exact complaint that started this engine work.
 *
 * The properties pinned here are the four ways a word can be real, plus the
 * one case that looks like knowledge and must not be treated as it.
 */
class AcceptedWordTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    private fun engine(assets: Map<String, String>): SuggestionEngine =
        SuggestionEngine.forTesting(userData) { path -> assets[path]?.byteInputStream() }

    /**
     * An engine on the dictionaries that actually ship.
     *
     * The compound cases below cannot be written against a fixture: what they
     * assert is that a word is absent from the shipped 200,000-entry list
     * while both of its halves are in it, which is a fact about that list.
     */
    private fun realEngine(): SuggestionEngine {
        val assets = listOf(File("src/main/assets"), File("app/src/main/assets"))
            .first { it.isDirectory }
        return SuggestionEngine.forTesting(userData) { path ->
            File(assets, path).takeIf { it.isFile }?.inputStream()
        }
    }

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-spell-test", "").let {
            it.delete(); it.mkdirs(); it
        }
        userData = UserData.inDir(dir)
    }

    @After
    fun tearDown() {
        userData.shutdown()
        dir.deleteRecursively()
    }

    private val en = Locale.ENGLISH
    private val tr = Locale.forLanguageTag("tr")

    @Test
    fun `a dictionary word is accepted`() {
        val e = engine(mapOf("dictionaries/en.txt" to "hello 900\nworld 800"))
        assertTrue(e.acceptedWord("hello", "en", en))
        assertTrue(e.acceptedWord("world", "en", en))
    }

    @Test
    fun `a word that is in no list is not accepted`() {
        val e = engine(mapOf("dictionaries/en.txt" to "hello 900"))
        assertFalse(e.acceptedWord("helllo", "en", en))
    }

    @Test
    fun `capitalisation does not decide whether a word is real`() {
        // Sentence case arrives constantly and the dictionary is lower case.
        val e = engine(mapOf("dictionaries/en.txt" to "hello 900"))
        assertTrue(e.acceptedWord("Hello", "en", en))
    }

    @Test
    fun `Turkish capital I folds by Turkish rules, not English ones`() {
        // "İstanbul".lowercase() is "istanbul" only under the Turkish locale;
        // under the default it keeps a combining dot and misses the entry.
        val e = engine(mapOf("dictionaries/tr.txt" to "istanbul 900"))
        assertTrue(e.acceptedWord("İstanbul", "tr", tr))
    }

    @Test
    fun `an inflected Turkish word is accepted through its stem`() {
        // The whole reason this engine grew morphology: no frequency list can
        // contain every surface form an agglutinative language produces, and
        // underlining them all is what the platform checker does today.
        val e = engine(mapOf("dictionaries/tr.txt" to "kitap 900\nev 800"))
        assertTrue(e.acceptedWord("kitaplar", "tr", tr))
        assertTrue(e.acceptedWord("kitaplarımızdan", "tr", tr))
        assertTrue(e.acceptedWord("evler", "tr", tr))
    }

    @Test
    fun `a Turkish word with no known stem is still rejected`() {
        // Morphology must not turn into "accept anything ending in a suffix".
        val e = engine(mapOf("dictionaries/tr.txt" to "kitap 900"))
        assertFalse(e.acceptedWord("qwertylar", "tr", tr))
    }

    @Test
    fun `a learned word is accepted so the keyboard and the underlines agree`() {
        val e = engine(mapOf("dictionaries/en.txt" to "hello 900"))
        // isKnown needs the word seen more than once; one sighting is not yet
        // vocabulary, which is the keyboard's own threshold.
        repeat(3) { userData.learnWord("zabernathy") }
        assertTrue(e.acceptedWord("zabernathy", "en", en))
    }

    @Test
    fun `a word added by hand is recognised in the language it was added in`() {
        // The personal dictionary is written from a text field and read from the
        // typing path, and the two used to fold case differently: the store used
        // no locale at all, so "Işık" was filed under "işık" while the keyboard
        // looked for "ışık", and "İstanbul" was filed under i + U+0307. Words
        // added expressly to stop autocorrect touching them went on being
        // corrected, which is the opposite of what adding one means.
        val e = engine(mapOf("dictionaries/tr.txt" to "kitap 900"))
        for (typed in listOf("Işık", "İstanbul", "Irmak")) {
            userData.addUserWord(typed, tr)
            assertTrue(
                "\"$typed\" was added by hand but is not accepted when typed",
                e.acceptedWord(typed, "tr", tr)
            )
            // And in the form the keyboard actually holds while composing.
            assertTrue(e.acceptedWord(typed.lowercase(tr), "tr", tr))
        }
    }

    @Test
    fun `a hand-added word keeps the letters of its own language`() {
        // Turkish 'ı' and 'i' are different letters, so the fold must not quietly
        // merge them: adding "ışık" must not make the unrelated "isik" a word.
        val e = engine(mapOf("dictionaries/tr.txt" to "kitap 900"))
        userData.addUserWord("ışık", tr)
        assertTrue(e.acceptedWord("ışık", "tr", tr))
        assertFalse(e.acceptedWord("isik", "tr", tr))
    }

    @Test
    fun `a word valid in the other enabled language is not underlined`() {
        // Bilingual typing: English words inside a Turkish message are not
        // misspelled Turkish.
        val e = engine(
            mapOf(
                "dictionaries/tr.txt" to "kitap 900",
                "dictionaries/en.txt" to "meeting 900"
            )
        )
        assertFalse(e.acceptedWord("meeting", "tr", tr))
        assertTrue(e.acceptedWord("meeting", "tr", tr, "en", en))
    }

    @Test
    fun `a bare-key spelling of an accented word is a typo, not a known word`() {
        // The one case that must NOT be accepted despite folding onto a real
        // entry: "gunaydin" is what you get from typing the accented word on
        // bare keys, and offering "günaydın" is the entire point.
        val e = engine(mapOf("dictionaries/tr.txt" to "günaydın 900"))
        assertTrue(e.acceptedWord("günaydın", "tr", tr))
        assertFalse(e.acceptedWord("gunaydin", "tr", tr))
    }

    @Test
    fun `acceptance agrees with autocorrect about what counts as a word`() {
        // The two must not diverge: a word the strip would never correct must
        // not be underlined, and vice versa. Restricted to words long enough
        // and plain enough for correctionCandidates to judge at all.
        val e = engine(
            mapOf(
                "dictionaries/tr.txt" to "kitap 900\ngünaydın 800",
                "dictionaries/en.txt" to "hello 900"
            )
        )
        for (w in listOf("kitap", "kitaplar", "gunaydin", "helllo", "günaydın")) {
            val accepted = e.acceptedWord(w, "tr", tr)
            val corrected = e.correctionCandidates(w, "tr", tr, limit = 1).isNotEmpty()
            assertFalse(
                "\"$w\": accepted=$accepted but autocorrect would change it",
                accepted && corrected
            )
        }
    }

    @Test
    fun `a German compound of two known words is not underlined`() {
        // None of these are in the shipped 200,000-entry list and all of them
        // are ordinary German. Compounding is productive, so a frequency list
        // holds whichever compounds its corpus happened to contain — 24.4% of
        // the German words the list misses are two words in the list joined.
        val e = realEngine()
        for (w in listOf("bananenkuchen", "nervenzelle", "flugzeugunfall", "landtiere")) {
            assertTrue(
                "$w is ordinary German and must not be underlined",
                e.acceptedWord(w, "de", java.util.Locale.GERMAN)
            )
        }
    }

    @Test
    fun `a German compound is not offered as two words`() {
        // The other half of the same decision. Offering to put a space in
        // "Bananenkuchen" is offering to misspell it, and the strip and the
        // underline have to agree about what a compound is.
        val e = realEngine()
        for (w in listOf("bananenkuchen", "flugzeugunfall")) {
            assertNull(
                "$w should not be offered as two words",
                e.splitFor(w, "de", java.util.Locale.GERMAN)
            )
        }
    }

    @Test
    fun `an English run-together word is still offered as two words`() {
        // The scoping, from the other side: English writes compounds open, so
        // the same shape of word gets the opposite answer.
        val e = realEngine()
        assertNotNull(
            "alot is still a missing space, not a compound",
            e.splitFor("alot", "en", java.util.Locale.ENGLISH)
        )
    }
}
