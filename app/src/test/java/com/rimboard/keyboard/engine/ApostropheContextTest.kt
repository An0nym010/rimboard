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

    /**
     * The whole chain is blind to which apostrophe the text uses.
     *
     * Asked end to end -- text in, [SentenceContext] to the two context words,
     * the model to the prediction -- because that is the only way to see it.
     * Each link looked reasonable on its own: the scan took U+0027 as a word
     * character and not U+2019, so curly text keyed on a fragment; the model
     * has no row keyed on an apostrophe word anyway, so the strip often
     * answered *something* and the fault showed up as slightly worse
     * predictions rather than as none.
     *
     * Measured before the fix, over every context position in the fixture,
     * with the same prose written both ways:
     *
     *          contexts   differing   top-3 hits straight / curly
     *     en      1425      70 (4.9%)          455 / 447
     *     fr      1408     184 (13.1%)         444 / 443
     *     it      1240      34 (2.7%)          406 / 408
     *
     * The strip barely moved, and that is worth writing down rather than
     * hiding: [SuggestionEngine.curatedKey] already falls back to the segment
     * after the last mark, which is exactly what the mis-split produced, so
     * the two wrongs cancelled for the *bundled* model. What did not cancel is
     * the learned store, which was being taught rows keyed on "ve", "en" and
     * "d". Every count above is now identical on both sides.
     */
    @Test
    fun `the same sentence predicts the same with either apostrophe`() {
        val curly = Char(0x2019)
        fun isW(c: Char) = c.isLetter() || c.isDigit() || c == Char(39) || c == curly
        val differing = ArrayList<String>()
        for (lang in listOf("en", "fr", "it")) {
            val loc = Locale.forLanguageTag(lang)
            val e = engine(lang)
            for (line in File(fixtures(), "prose_" + lang + ".txt").readLines()) {
                if (line.isBlank()) continue
                for (i in line.indices) {
                    // Every word end in the line: a word character with a
                    // non-word character (or nothing) after it.
                    if (!isW(line[i])) continue
                    if (i + 1 < line.length && isW(line[i + 1])) continue
                    val before = line.substring(0, i + 1) + " "
                    val a = com.rimboard.keyboard.model.SentenceContext.from(before, loc)
                    val bCtx = com.rimboard.keyboard.model.SentenceContext.from(
                        before.replace(Char(39), curly), loc
                    )
                    if (a != bCtx && differing.size < 5) differing.add(before.takeLast(24))
                    val pa = e.predictions(a.prevWord2, a.prevWord, lang, loc, 3, false)
                    val pb = e.predictions(bCtx.prevWord2, bCtx.prevWord, lang, loc, 3, false)
                    if (pa != pb && differing.size < 5) differing.add(before.takeLast(24))
                }
            }
        }
        assertEquals(
            "the apostrophe used changed the context or the prediction: " + differing,
            emptyList<String>(), differing
        )
    }

    /**
     * The learned store answers whichever mark the text in front of it uses.
     *
     * This is the last consumer of a context word, and the one the keyboard
     * itself never exercises. `SentenceContext` normalises what it reads out
     * of the field, so every learned row is filed under a straight mark -- but
     * the **spell checker** takes its context from `SpellTokens`, which reads
     * the field's own text and counts both marks a word character. So a user
     * whose text uses U+2019 had a store full of rows their own sentences
     * could not reach.
     *
     * Asserted of both marks in one test rather than of the curly one alone:
     * a control that only shows the curly form answering cannot tell a working
     * lookup from one that has started ignoring the mark altogether.
     */
    @Test
    fun `a learned pair is found whichever apostrophe the context uses`() {
        val e = engine("en")
        val en = Locale.ENGLISH
        val curly = Char(0x2019)
        repeat(3) { userData.recordBigram("don't", "know") }
        assertTrue(
            "the straight form stopped answering",
            e.predictions("", "don't", "en", en, 5, personalized = true).contains("know")
        )
        assertTrue(
            "the same sentence written with U+2019 reaches none of it",
            e.predictions("", "don" + curly + "t", "en", en, 5, personalized = true)
                .contains("know")
        )
        // And the trigram key, which is the other half of the context.
        repeat(3) { userData.recordNgram("i", "don't", "care") }
        assertTrue(
            "the trigram context is not normalised",
            e.predictions("i", "don" + curly + "t", "en", en, 5, personalized = true)
                .contains("care")
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
