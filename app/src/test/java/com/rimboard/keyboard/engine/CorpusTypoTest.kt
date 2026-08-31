package com.rimboard.keyboard.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * The corpus contains its own typos, and a word in the list is a word.
 *
 * `recieve` is in the shipped English dictionary at 125 occurrences, `thier` at
 * 127, `seperate` at 166, `becuase` at 132, `teh` at 124. The frequency lists
 * are built from subtitle text, and people misspell things there. Autocorrect
 * asks `contains(word)` first and stops, so none of them was ever repaired --
 * and the strip, having no correction to show, filled with completions of the
 * misspelling:
 *
 *     recieve   ->  recieve, recieved, recieves
 *     seperate  ->  seperate, seperated, seperately
 *     thier     ->  thier, thierry, thiers
 *
 * The right spelling appeared nowhere: not committed, not offered, not
 * underlined. Nine of seventeen common English misspellings behaved that way;
 * twelve of the seventeen are now offered the right word.
 *
 * ## Why this is a chip and not a correction
 *
 * The three obvious wider rules were built and measured and all three fail,
 * because "in the list and much rarer than a near neighbour" is mostly
 * ordinary vocabulary — see [Dictionary.TRANSPOSE_SUGGEST_RATIO] for the
 * counts. What is left is the narrow case the engine already treats as the
 * commonest slip there is: two adjacent letters in the wrong order, priced at
 * 0.35 against 1.0 for a substitution.
 *
 * Even that is not certain enough to commit — `acme`, `toady` and `mien` are
 * words, `Greta` and `Romo` are names — so it takes a chip and never the space
 * bar. Slot 0 is always the verbatim word, so being wrong costs one chip. That
 * is the same trade [Dictionary.accentedSuggestionFor] makes at a ratio of ten
 * while the committing rule needs fifty.
 */
class CorpusTypoTest {

    private lateinit var dir: File
    private lateinit var userData: UserData
    private val en = Locale.ENGLISH

    @Before
    fun setUp() {
        dir = File.createTempFile("corpustypo", "").let { it.delete(); it.mkdirs(); it }
        userData = UserData.inDir(dir)
    }

    @After
    fun tearDown() {
        userData.shutdown()
        dir.deleteRecursively()
    }

    private fun assets(): File =
        listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }

    private fun engine(lang: String): SuggestionEngine {
        val files = listOf("dictionaries/$lang.txt", "predictions/$lang.txt")
            .filter { assets().resolve(it).isFile }
            .associateWith { assets().resolve(it).readText() }
        return SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
    }

    private fun langs(): List<String> =
        assets().resolve("dictionaries").listFiles().orEmpty()
            .map { it.name.removeSuffix(".txt") }.sorted()

    @Test
    fun `a misspelling the corpus recorded is offered the right word`() {
        val e = engine("en")
        val missing = listOf(
            "teh" to "the", "hte" to "the", "adn" to "and", "thier" to "their",
            "becuase" to "because", "beleive" to "believe", "freind" to "friend",
            "konw" to "know", "waht" to "what", "wnat" to "want"
        ).filterNot { (typo, right) ->
            e.suggestionsFor(typo, "en", en, allowAutocorrect = true, personalized = false)
                .items.any { it.lowercase(en) == right }
        }
        assertEquals(
            "a word the English list holds only because somebody typed it wrong " +
                "was offered no way to fix it.",
            emptyList<Pair<String, String>>(), missing
        )
    }

    /** And in the other languages, from each list's own evidence. */
    @Test
    fun `the same holds in the other languages`() {
        val missing = StringBuilder()
        for ((lang, typo, right) in listOf(
            Triple("de", "dei", "die"), Triple("de", "ads", "das"),
            Triple("fr", "qiu", "qui"), Triple("fr", "onn", "non"),
            Triple("es", "qeu", "que"), Triple("es", "uan", "una"),
            Triple("tr", "bri", "bir"), Triple("tr", "bne", "ben"),
            Triple("ru", "тэо", "это"), Triple("ru", "ент", "нет")
        )) {
            val loc = Locale.forLanguageTag(lang)
            val out = engine(lang)
                .suggestionsFor(typo, lang, loc, allowAutocorrect = true, personalized = false)
                .items.map { it.lowercase(loc) }
            if (!out.contains(right)) missing.append(" $lang: $typo wanted $right, got $out")
        }
        assertEquals("a transposition the corpus records was not offered.$missing", "", missing.toString())
    }

    /**
     * It offers and never commits.
     *
     * The population is not all typos — `acme` and `toady` are words, `Greta`
     * and `Romo` are names — so the space bar must not act on it. Slot 0 stays
     * the verbatim word either way.
     */
    @Test
    fun `it never reaches the space bar`() {
        val e = engine("en")
        for (w in listOf("teh", "thier", "becuase", "konw", "acme", "toady", "greta")) {
            assertNull(
                "\"$w\" was committed on the separator; this rule may only offer a chip",
                e.correctionFor(w, "en", en)
            )
            val items = e.suggestionsFor(w, "en", en, allowAutocorrect = true, personalized = false)
            assertEquals(
                "slot 0 must be what was typed",
                w, items.items.first().lowercase(en)
            )
            assertEquals("nothing here may be marked as the autocorrect", -1, items.autocorrectIndex)
        }
    }

    /**
     * How many entries the rule speaks about, per list.
     *
     * A tripwire on the ratio: at 1,000 this is a few hundred words per
     * language and every one of them costs at most a chip. If a rebuilt list
     * or a lowered ratio makes it thousands, it has stopped being the narrow
     * case it was argued as. Measured 2026-08-31: English 910, Indonesian
     * highest at 1,055, Ukrainian lowest at 34.
     */
    @Test
    fun `the rule speaks about a few hundred words, not thousands`() {
        val loud = ArrayList<String>()
        val report = StringBuilder()
        for (lang in langs()) {
            val loc = Locale.forLanguageTag(lang)
            val d = engine(lang).dictionary(lang, loc)
            var n = 0
            for (line in assets().resolve("dictionaries/$lang.txt").readLines()) {
                val i = line.indexOf(' ')
                if (i <= 0) continue
                if (d.transposedCommoner(line.substring(0, i)) != null) n++
            }
            report.append("$lang $n  ")
            if (n > 3000) loud.add("$lang: $n")
        }
        println("entries with a transposition the corpus overwhelmingly prefers: $report")
        assertEquals(
            "the transposition rule now speaks about thousands of words in a " +
                "language, which is not the narrow case it was measured as.",
            emptyList<String>(), loud
        )
        assertTrue("nothing measured at all", report.isNotEmpty())
    }
}
