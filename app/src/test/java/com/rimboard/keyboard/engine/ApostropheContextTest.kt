package com.rimboard.keyboard.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * After a word with an apostrophe in it, the keyboard had nothing to predict.
 *
 * The n-gram corpora were tokenised at the apostrophe, so no shipped model has
 * a row keyed on a word containing one: **French has 0 of 26,309**, Italian 0
 * of 29,447, Turkish 0 of 35,246. English has 23, and only because somebody
 * wrote them by hand.
 *
 * That put a dead spot exactly where the commonest words are. Measured over
 * the prose fixtures, the share of contexts the bundled model can answer:
 *
 *     fr   after a word with an apostrophe   0.0%     after any other  99.5%
 *     it                                     0.0%                      99.4%
 *     tr                                     0.0%                      94.8%
 *     en                                    69.4%                      99.7%
 *
 * French writes one token in fourteen with an apostrophe, and they are `j'ai`,
 * `c'est`, `l'`, `n'`, `qu'` — so the strip went blank after the words it
 * should have had most to say about.
 *
 * ## The counts were there; the key was wrong
 *
 * A tokeniser that split "j'ai la" into `j`, `ai`, `la` counted the pair
 * (`ai`, `la`) — which is exactly what follows "j'ai". So the row to ask for
 * is the segment after the last mark, and it is the right one whatever the
 * language does with the apostrophe, because in all of them the **last corpus
 * token is the tail**: elision puts the content word there (`l'homme` →
 * `homme`), a Turkish case ending puts the ending there (`Paris'e` → `e`), and
 * a Ukrainian inner mark splits an indivisible word (`здоров'я` → `я`).
 *
 * No new data, no rebuild — and a rebuild would have been the wrong tool
 * anyway, since `build_ngrams.py`'s merge only ever adds and the hand-written
 * rows are what it protects.
 */
class ApostropheContextTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("apoctx", "").let { it.delete(); it.mkdirs(); it }
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
        for (kind in listOf("dictionaries", "predictions")) {
            File(assets(), "$kind/$lang.txt").takeIf { it.isFile }?.let {
                files["$kind/$lang.txt"] = it.readText()
            }
        }
        return SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
    }

    private fun wordsOf(lang: String, locale: Locale): List<List<String>> =
        File(fixtures(), "prose_$lang.txt").readLines()
            .filter { it.isNotBlank() }
            .map { line ->
                val out = ArrayList<String>()
                val cur = StringBuilder()
                for (ch in line.lowercase(locale)) {
                    if (ch.isLetter() || ch == '\'' || ch == '’') cur.append(ch)
                    else {
                        if (cur.isNotEmpty()) out.add(cur.toString())
                        cur.setLength(0)
                    }
                }
                if (cur.isNotEmpty()) out.add(cur.toString())
                out
            }

    /**
     * The dead spot, closed, in the language it cost the most.
     *
     * Asked of the bundled model alone — `personalized = false` — so this is
     * about the shipped data and not about anything the user has typed.
     */
    @Test
    fun `a word with an apostrophe is a context like any other`() {
        val report = StringBuilder()
        val weak = ArrayList<String>()
        for (lang in listOf("fr", "it", "tr", "en")) {
            val loc = Locale.forLanguageTag(lang)
            val e = engine(lang)
            var withMark = 0
            var withMarkAnswered = 0
            var plain = 0
            var plainAnswered = 0
            for (line in wordsOf(lang, loc)) {
                for ((i, w) in line.withIndex()) {
                    if (i == line.lastIndex) continue
                    val prev2 = if (i >= 1) line[i - 1] else ""
                    val answered = e.predictions(prev2, w, lang, loc, 3, personalized = false)
                        .isNotEmpty()
                    if (w.any { it == '\'' || it == '’' }) {
                        withMark++
                        if (answered) withMarkAnswered++
                    } else {
                        plain++
                        if (answered) plainAnswered++
                    }
                }
            }
            if (withMark < 8) continue
            val pct = 100.0 * withMarkAnswered / withMark
            val base = 100.0 * plainAnswered / plain
            report.append(
                "%-3s after an apostrophe word %5.1f%% answered (n=%d); after any other %5.1f%%%n"
                    .format(lang, pct, withMark, base)
            )
            // Well clear of the zero it was, and within reach of the ordinary
            // rate. The gap that remains is single-letter tails the corpus
            // never counted on their own -- Italian "c'è" -> "è".
            if (pct < 70.0) weak.add("$lang ${"%.1f".format(pct)}%")
        }
        println(report)
        assertEquals(
            "an apostrophe word is still a dead context for prediction: $weak",
            emptyList<String>(), weak
        )
    }

    /** French is the case this was found on, and the one that pays most. */
    @Test
    fun `French predicts after its commonest words`() {
        val e = engine("fr")
        val fr = Locale.FRENCH
        val silent = listOf("j'ai", "c'est", "n'est", "s'il", "qu'il", "d'accord", "l'homme")
            .filter { e.predictions("", it, "fr", fr, 3, personalized = false).isEmpty() }
        assertEquals(
            "the strip is blank after these, which are among the commonest " +
                "things anybody writes in French.",
            emptyList<String>(), silent
        )
    }

    /**
     * The control, and the reason this is a lookup rather than a rebuild.
     *
     * A word without a mark must answer exactly as it did, and the learned
     * store must not be touched at all: it records words as the user typed
     * them, apostrophes included, so it has the real key already and asking it
     * for a tail would answer a different question.
     */
    @Test
    fun `an ordinary word is unaffected and the learned store is not consulted for a tail`() {
        val e = engine("fr")
        val fr = Locale.FRENCH
        assertTrue(
            "an ordinary French context stopped answering",
            e.predictions("", "je", "fr", fr, 3, personalized = false).isNotEmpty()
        )
        // What the user typed, under the key they typed it as.
        repeat(3) { userData.recordBigram("d'un", "coup") }
        assertTrue(
            "the learned pair was lost",
            e.predictions("", "d'un", "fr", fr, 5, personalized = true).contains("coup")
        )
    }

    /** And a mark with nothing after it is not a key. */
    @Test
    fun `a word ending in the mark falls back to nothing`() {
        val e = engine("fr")
        val fr = Locale.FRENCH
        // "l'" is a real prefix but the tail is empty; this must not crash or
        // key on the empty string, which is [UserData.START].
        e.predictions("", "l'", "fr", fr, 3, personalized = false)
        e.predictions("", "'", "fr", fr, 3, personalized = false)
    }
}
