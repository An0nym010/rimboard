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
 * The apostrophe rules, asked of the lists that actually ship.
 *
 * `ElisionTest` puts the same questions to a hand-written frequency map, and
 * for one of them that was not enough: its curly-apostrophe case invented an
 * entry keyed on U+2019, asserted it came back, and passed for as long as the
 * feature was broken. **No shipped dictionary contains a single U+2019** — all
 * 22 are written with U+0027 — so the real lookup was for a key that cannot
 * exist, and every contraction and elision typed the common way was underlined
 * as a misspelling in every app on the phone.
 *
 * Measured here before the fix, over the apostrophe word types in the prose
 * fixtures:
 *
 *     en   20 of 20 accepted straight     0 of 20 curly
 *     fr   69 of 70                       0 of 70
 *     it   22 of 22                       0 of 22
 *     tr   10 of 10                      10 of 10
 *     uk    3 of 3                        3 of 3
 *
 * Turkish and Ukrainian were never affected because their clauses
 * ([Morphology.apostropheSuffixed], [InnerApostrophe.isWord]) look the halves
 * up *without* the mark. Elision sliced its halves out of the typed word, so
 * the mark came along.
 *
 * This is the test the fix needed: the same question, asked of the real data.
 */
class ElisionRealListsTest {

    private val CURLY = Char(0x2019)

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("elisionreal", "").let { it.delete(); it.mkdirs(); it }
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

    private fun engine(lang: String): SuggestionEngine {
        val files = HashMap<String, String>()
        for (kind in listOf("dictionaries", "suffixes", "prefixes")) {
            val name = kind + "/" + lang + ".txt"
            File(assets(), name).takeIf { it.isFile }?.let { files[name] = it.readText() }
        }
        return SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
    }

    /** Apostrophe word types in the language's prose fixture, lower case. */
    private fun apostropheWords(lang: String, locale: Locale): List<String> {
        val out = LinkedHashSet<String>()
        val cur = StringBuilder()
        for (line in File(fixtures(), "prose_" + lang + ".txt").readLines()) {
            for (ch in line.lowercase(locale)) {
                if (ch.isLetter() || ch == '\'' || ch == CURLY) cur.append(ch)
                else {
                    if (cur.isNotEmpty()) out.add(cur.toString())
                    cur.setLength(0)
                }
            }
            if (cur.isNotEmpty()) out.add(cur.toString())
            cur.setLength(0)
        }
        return out.filter { w -> w.any { it == '\'' || it == CURLY } }
    }

    /**
     * The claim, over real prose and real lists: which of the two marks was
     * used cannot change the answer.
     *
     * `underlining = true` because the spell checker is the caller that pays
     * for this — it is asked about text the user never typed, which is exactly
     * where the curly mark comes from.
     */
    @Test
    fun `the two apostrophes are the same word to every language`() {
        val report = StringBuilder()
        val broken = ArrayList<String>()
        for (lang in listOf("en", "fr", "it", "tr", "uk")) {
            val loc = Locale.forLanguageTag(lang)
            val e = engine(lang)
            val words = apostropheWords(lang, loc)
            var straight = 0
            var curly = 0
            val lost = ArrayList<String>()
            for (w in words) {
                val s = w.replace(CURLY, '\'')
                val c = w.replace('\'', CURLY)
                val okS = e.acceptedWord(s, lang, loc, underlining = true)
                val okC = e.acceptedWord(c, lang, loc, underlining = true)
                if (okS) straight++
                if (okC) curly++
                if (okS != okC && lost.size < 5) lost.add(w)
            }
            report.append(
                "%-3s of %2d apostrophe types: straight %2d, curly %2d%n"
                    .format(lang, words.size, straight, curly)
            )
            // Enough of a population to mean anything, and the same answer
            // both ways for every single word -- not merely the same total.
            if (words.size >= 3 && lost.isNotEmpty()) {
                broken.add(lang + " " + lost)
            }
        }
        println(report)
        assertEquals(
            "the mark used decides whether a word is underlined: " + broken,
            emptyList<String>(), broken
        )
    }

    /**
     * The words this is really about, named, so a regression says which.
     *
     * All five are among the commonest things anybody writes in the language,
     * and all five were underlined when written with the mark most text uses.
     */
    @Test
    fun `the commonest contractions are words with either mark`() {
        val cases = listOf(
            Triple("en", "don" + CURLY + "t", "don't"),
            Triple("en", "isn" + CURLY + "t", "isn't"),
            Triple("fr", "l" + CURLY + "homme", "l'homme"),
            Triple("fr", "qu" + CURLY + "il", "qu'il"),
            Triple("it", "dell" + CURLY + "amore", "dell'amore")
        )
        val underlined = ArrayList<String>()
        for ((lang, curly, straightForm) in cases) {
            val loc = Locale.forLanguageTag(lang)
            val e = engine(lang)
            assertTrue(
                "the fixture is wrong: " + straightForm + " was not a word to begin with",
                e.acceptedWord(straightForm, lang, loc, underlining = true)
            )
            if (!e.acceptedWord(curly, lang, loc, underlining = true)) underlined.add(curly)
        }
        assertEquals(emptyList<String>(), underlined)
    }

    /**
     * The control. Accepting either mark must not become accepting anything:
     * both halves still have to be known, and the mark still has to be inside
     * the word rather than at either end.
     */
    @Test
    fun `a curly apostrophe does not make a non-word a word`() {
        val e = engine("en")
        val en = Locale.ENGLISH
        for (half in listOf("asdf'qwer", "xyzzy't", "don'xyzzy", "qwer'")) {
            val curly = half.replace('\'', CURLY)
            // Both halves still have to be known. Asserted of the straight form
            // as well, in the same loop: the claim is that the mark decides
            // nothing, and a control that only shows the curly one refused
            // cannot tell a working guard from a broken lookup — which is the
            // exact confusion this whole fix is about.
            assertFalse("accepted as a word: " + half, e.acceptedWord(half, "en", en, underlining = true))
            assertFalse("accepted as a word: " + curly, e.acceptedWord(curly, "en", en, underlining = true))
        }
        // And a word the list really holds is accepted either way, including
        // one that begins with the mark: "'tis" is an entry, at 2,778.
        assertTrue(e.acceptedWord("'tis", "en", en, underlining = true))
        assertTrue(e.acceptedWord(CURLY + "tis", "en", en, underlining = true))
    }
}
