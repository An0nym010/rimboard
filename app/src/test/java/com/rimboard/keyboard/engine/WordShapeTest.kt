package com.rimboard.keyboard.engine

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * "Does this look like a word of the language", and what it is allowed to do.
 *
 * The dictionary answers membership. [Dictionary.looksLikeAWord] answers the
 * question membership cannot, and autocorrect uses it to decide how much
 * licence a correction gets: a string shaped like a word of the language keeps
 * only part of the allowance, so a correct word the list happens to lack is no
 * longer treated exactly like a mistyped one.
 *
 * It reuses the character-transition model the dictionary already builds for
 * adaptive tap targeting. A separate trigram model was written first, measured
 * against that one on the same two populations, and thrown away: it prevented
 * no more destruction, cost 0.3 MB and a second pass over the word list, and
 * would have been a second opinion in one file about which letters follow
 * which.
 */
class WordShapeTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-shape", "").let { it.delete(); it.mkdirs(); it }
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
        val files = mapOf(
            "dictionaries/$lang.txt" to File(assets(), "dictionaries/$lang.txt").readText(),
            "predictions/$lang.txt" to File(assets(), "predictions/$lang.txt").readText()
        )
        return SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
    }

    private fun dict(lang: String) = engine(lang).dictionary(lang, Locale.forLanguageTag(lang))

    /**
     * The bar is calibrated, and calling it is not the same as it working.
     *
     * It is a percentile of what this language's own words score, computed in
     * `init`. The field that holds it was once declared further down the file
     * with an initialiser, and Kotlin runs property initialisers and `init`
     * blocks in declaration order — so `init` computed the bar and the
     * initialiser overwrote it a moment later with negative infinity, leaving
     * this true for every string ever passed to it. Nothing failed; the gate
     * was simply inert, and a sweep over the percentile changed no number at
     * all, which is what gave it away.
     */
    @Test
    fun `the bar actually discriminates`() {
        val d = dict("en")
        val nonsense = listOf("qxzjvfw", "zzzqqxk", "vbnmqwx", "jjkkzzq")
        val ordinary = listOf("something", "different", "important", "understand")
        assertTrue(
            "every one of $nonsense is being called word-shaped, so the bar is " +
                "not calibrated and the gate is doing nothing",
            nonsense.none { d.looksLikeAWord(it) }
        )
        assertTrue(
            "ordinary English is not being recognised as English",
            ordinary.all { d.looksLikeAWord(it) }
        )
    }

    @Test
    fun `a word the list lacks can still be recognised as English`() {
        val d = dict("en")
        // None of these is in the shipped list; all of them are English.
        for (w in listOf("idempotent", "deduplicate", "refactored")) {
            assertFalse("$w is in the dictionary now, so it proves nothing", d.contains(w))
            assertTrue("$w does not look like English", d.looksLikeAWord(w))
        }
    }

    @Test
    fun `and plenty of English words are not, which is the honest limit`() {
        // A counted model of letter sequences is not a speaker. "observability"
        // and "unhelpfully" are unarguably English and score below the bar,
        // so they get no protection and `unhelpfully` is still rewritten to
        // `unhelpful` on a phone. This is recorded rather than tuned away:
        // moving the bar to admit them admits typos with it, and the sweep in
        // [Dictionary.WORDLIKE_TIGHTEN] is what settled where it sits.
        val d = dict("en")
        val missed = listOf("observability", "unhelpfully").filter { !d.looksLikeAWord(it) }
        assertTrue(
            "these are recognised now, so the model has improved and the " +
                "figures around it want remeasuring: " +
                listOf("observability", "unhelpfully").minus(missed.toSet()),
            missed.isNotEmpty()
        )
    }

    @Test
    fun `an ordinary slip does not look like a word, and keeps its repair`() {
        // The other half of the trade. If a mistyped word were word-shaped it
        // would be held to the tighter bar too, and the repair rate would pay
        // for the protection above.
        val e = engine("en")
        val d = e.dictionary("en", Locale.ENGLISH)
        for ((typo, fixed) in listOf(
            "keybpard" to "keyboard", "problrm" to "problem",
            "togethsr" to "together", "importsnt" to "important"
        )) {
            assertFalse("$typo is being treated as a word of the language",
                d.looksLikeAWord(typo))
            assertTrue(
                "$typo is no longer repaired to $fixed",
                e.correctionFor(typo, "en", Locale.ENGLISH) == fixed
            )
        }
    }

    @Test
    fun `the words destroyed on a phone are the ones this protects`() {
        // Typed on a Redmi Note 8 with the shipped dictionary, before this
        // existed: `unhelpfully refactored deduplicate idempotent prepopulated`
        // committed as `Unhelpful factored duplicate impotent repopulate`.
        val e = engine("en")
        for (w in listOf("refactored", "deduplicate", "idempotent")) {
            assertTrue(
                "$w is rewritten again; it used to commit as " +
                    e.correctionFor(w, "en", Locale.ENGLISH),
                e.correctionFor(w, "en", Locale.ENGLISH) == null
            )
        }
    }

    @Test
    fun `every shipped language calibrates its own bar`() {
        // The scale is not comparable across languages -- Finnish spelling is
        // far more regular than English, so Finnish words score higher and so
        // do Finnish typos. A shared constant would be tight in one language
        // and inert in another, which is why the bar is a percentile of each
        // language's own vocabulary.
        val langs = File(assets(), "dictionaries").list().orEmpty()
            .map { it.removeSuffix(".txt") }.sorted()
        var checked = 0
        for (lang in langs) {
            val d = dict(lang)
            val sample = File(assets(), "dictionaries/$lang.txt").useLines { lines ->
                lines.drop(200).mapNotNull { it.split(' ').firstOrNull() }
                    .filter { it.length in 5..12 }.take(200).toList()
            }
            if (sample.size < 100) continue
            val recognised = sample.count { d.looksLikeAWord(it) }
            assertTrue(
                "$lang calls only $recognised of ${sample.size} of its own words " +
                    "word-shaped, so its bar is far too high",
                recognised >= sample.size * 6 / 10
            )
            checked++
        }
        assertTrue("no language was checked", checked >= 15)
    }
}
