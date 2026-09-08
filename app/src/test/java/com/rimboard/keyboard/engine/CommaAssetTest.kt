package com.rimboard.keyboard.engine

import com.rimboard.keyboard.model.CommaRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * The shipped comma lists, and what they are allowed to contain.
 *
 * `tools/build_commas.py` decides *which* languages ship and holds the
 * measurement that justifies each one; this holds the properties the runtime
 * depends on and the ones a regenerated asset could quietly break. A word list
 * this small is easy to rebuild by accident against a different corpus, and
 * the failure would be silent: commas in the wrong places, in a language
 * nobody here reads.
 */
class CommaAssetTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-commas", "").let { it.delete(); it.mkdirs(); it }
        userData = UserData.inDir(dir)
    }

    @After
    fun tearDown() {
        userData.shutdown()
        dir.deleteRecursively()
    }

    private fun assets(): File =
        listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }

    private fun commaDir() = File(assets(), "commas")

    private fun listFor(lang: String): Map<String, Int> =
        File(commaDir(), "$lang.txt").readLines()
            .filter { it.isNotBlank() }
            .associate {
                val p = it.split('\t')
                p[0] to p[1].trim().toInt()
            }

    private fun shipped(): List<String> =
        commaDir().listFiles().orEmpty()
            .map { it.name.removeSuffix(".txt") }.sorted()

    @Test
    fun `the languages that ship are the ones the measurement chose`() {
        // Seven of twenty-two, and the fifteen without a list are a refusal
        // rather than a gap: a language ships one only if a model built from
        // nine tenths of its corpus gets 95% of its commas right on the tenth
        // it never saw, and only if it fires often enough to be worth having.
        // Italian reaches 97.4% and still does not ship, because it would fire
        // once in 547 sentences; English has four candidate words and fires
        // once in 2,749; Turkish has none at all.
        assertEquals(
            listOf("cs", "de", "hu", "pl", "ru", "sk", "uk"),
            shipped()
        )
    }

    @Test
    fun `every word clears the bar it was written for`() {
        // The asset is the output of a threshold and the runtime applies the
        // same threshold again. If a rebuild ever wrote rows under it, the
        // engine would carry words it then refuses on every keystroke -- dead
        // weight that looks like a working feature.
        for (lang in shipped()) {
            for ((word, share) in listFor(lang)) {
                assertTrue(
                    "$lang: '$word' is listed at $share, under CommaRule.MIN_SHARE",
                    share >= CommaRule.MIN_SHARE
                )
                assertTrue("$lang: '$word' has an impossible share", share <= 1000)
            }
        }
    }

    @Test
    fun `every word is lower case and a word`() {
        // Looked up under `word.lowercase(locale)`, so an entry carrying a
        // capital is an entry nothing can ever match.
        for (lang in shipped()) {
            val locale = Locale.forLanguageTag(lang)
            for (word in listFor(lang).keys) {
                assertEquals("$lang: '$word' is not lower case", word.lowercase(locale), word)
                assertTrue("$lang: '$word' is not a word", word.all { it.isLetter() })
                assertTrue("$lang: '$word' is too short to be one", word.length >= 1)
            }
        }
    }

    @Test
    fun `every word is one the language's own dictionary holds`() {
        // The guard that caught the vocative of Tom. Tatoeba is a
        // language-teaching corpus written overwhelmingly about a character
        // called Tom, and Czech and Ukrainian decline names, so `Tome` and
        // `Томе` sat at 90%+ in both -- direct address takes a comma, so the
        // rule was right about the corpus and absurd about the language.
        // `build_commas.py` drops a candidate that is over-represented against
        // the shipped frequency list; this is that decision, held.
        for (lang in shipped()) {
            val dict = File(assets(), "dictionaries/$lang.txt")
            assertTrue("no dictionary for $lang", dict.isFile)
            val known = HashSet<String>()
            dict.forEachLine { line ->
                val i = line.indexOf(' ')
                if (i > 0) known.add(line.substring(0, i))
            }
            for (word in listFor(lang).keys) {
                assertTrue(
                    "$lang: '$word' is a comma word the $lang dictionary has never " +
                        "heard of, which is what a corpus artifact looks like",
                    word in known
                )
            }
        }
    }

    @Test
    fun `the whole feature costs under two kilobytes`() {
        val bytes = commaDir().listFiles().orEmpty().sumOf { it.length() }
        println("comma lists: ${shipped().size} languages, $bytes bytes")
        assertTrue(
            "the comma lists have grown to $bytes bytes. They are meant to be a " +
                "short high-precision list rather than a model, and the asset " +
                "budget is the tightest in this project.",
            bytes in 100..2048
        )
    }

    @Test
    fun `the engine finds a listed word and nothing else`() {
        val files = HashMap<String, String>()
        for (lang in shipped() + listOf("en")) {
            File(assets(), "commas/$lang.txt").takeIf { it.isFile }?.let {
                files["commas/$lang.txt"] = it.readText()
            }
            File(assets(), "dictionaries/$lang.txt").takeIf { it.isFile }?.let {
                files["dictionaries/$lang.txt"] = it.readText()
            }
        }
        val e = SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
        val de = Locale.GERMAN
        assertTrue(
            "the German list no longer answers about 'dass', which is the word " +
                "this whole feature is for",
            (e.commaShare("dass", "de", de) ?: 0) >= CommaRule.MIN_SHARE
        )
        assertEquals(
            "the lookup is not folding the way the list is written",
            e.commaShare("dass", "de", de), e.commaShare("Dass", "de", de)
        )
        assertNull("an ordinary word has no opinion", e.commaShare("haus", "de", de))
        assertNull(
            "English has no list, and a language without one must answer null " +
                "rather than throwing or blocking",
            e.commaShare("but", "en", Locale.ENGLISH)
        )
    }
}
