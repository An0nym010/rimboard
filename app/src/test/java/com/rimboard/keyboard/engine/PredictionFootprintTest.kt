package com.rimboard.keyboard.engine

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * What a loaded prediction model costs in memory.
 *
 * `DictionaryFootprintTest` measures the word list; nothing measured the model
 * beside it, and `MIN_PAIR` falling to 2 grew it by a third to a half
 * depending on the language. That matters more here than the disk figure: the
 * comment on `Dictionary` explains why -- "an input method is the lowest-
 * priority process on the device that the user can still see, so that overhead
 * is paid in the risk of being killed mid-sentence."
 *
 * Measured on the JVM rather than on a phone, deliberately. `dumpsys meminfo`
 * on a warmed keyboard was tried first and cannot answer this: within one
 * process it is stable to a tenth of a percent, but *between* launches of the
 * identical build the Java heap read 35 MB or 71 MB depending on whether the
 * second language's dictionary had warmed before the sample. A two-fold
 * variance cannot measure a few megabytes. Here the allocation is bracketed by
 * four collections on either side, which is the technique the dictionary
 * footprint test already uses.
 *
 * The number is printed rather than asserted tightly, for the reason that file
 * gives: it is a measurement, and a threshold that tracks it too closely is a
 * test that fails on a corpus refresh rather than on a regression.
 */
class PredictionFootprintTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-pf", "").let { it.delete(); it.mkdirs(); it }
        userData = UserData.inDir(dir)
    }

    @After
    fun tearDown() {
        userData.shutdown()
        dir.deleteRecursively()
    }

    private fun assets(): File =
        listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }

    private fun settle() {
        for (i in 0 until 4) {
            System.gc()
            Thread.sleep(30)
        }
    }

    private fun used(): Long {
        settle()
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }

    /** Holds the engine so the model it loaded cannot be collected mid-measure. */
    private var held: SuggestionEngine? = null

    private fun costOf(lang: String): Pair<Long, Int> {
        val text = File(assets(), "predictions/$lang.txt").readText()
        val rows = text.count { it == '\n' }
        val before = used()
        val e = SuggestionEngine.forTesting(userData) { p ->
            if (p == "predictions/$lang.txt") text.byteInputStream() else null
        }
        // Forces the model in; the dictionary is deliberately not provided, so
        // what this weighs is the model and nothing else.
        e.predictions("", "the", lang, Locale.forLanguageTag(lang), 3)
        held = e
        val after = used()
        return (after - before) to rows
    }

    /**
     * No shipped model has grown past what the builder caps a fresh one at.
     *
     * `tools/build_ngrams.py` bounds a freshly counted model at
     * `MAX_ROWS + TRI_ROWS` = 36,000 rows, and the note on that constant says
     * it is there so "a future corpus cannot silently produce a five-megabyte
     * asset". It cannot do that, because `merge` unions the new rows onto the
     * asset already on disk and never drops one — that is deliberate, so
     * hand-written entries survive a rebuild, but it means the cap binds each
     * run and not the file.
     *
     * Russian is already past it, at 39,226, and nothing said so. It is not too
     * big — 6.6 MB of heap against the 12 MB budget asserted below — it merely
     * arrived there unobserved, which is the part worth fixing. The builder now
     * warns, and this fails.
     *
     * Russian's overrun is pinned rather than removed: trimming it would throw
     * away rows that are earning their place, to satisfy a comment. What must
     * not happen is that it grows, or that a second language joins it quietly.
     */
    @Test
    fun `no shipped model has drifted past the builder's own cap`() {
        val cap = 36_000
        // Three languages sit past what the builder caps a fresh build at, each
        // deliberately and each pinned to the row it stands on. Russian arrived
        // there by accumulated merges and nobody noticed until this test
        // existed; Polish and Finnish were put there on purpose, because the
        // cap bounds a fresh build and what bounds the file is memory and the
        // APK, and all three sit under the 12 MB asserted below.
        val known = mapOf("ru" to 39_226, "pl" to 38_402, "fi" to 38_702)
        val dir = listOf(File("src/main/assets"), File("app/src/main/assets"))
            .first { it.isDirectory }.resolve("predictions")
        val over = ArrayList<String>()
        val out = StringBuilder()
        for (f in dir.list().orEmpty().sorted()) {
            val lang = f.removeSuffix(".txt")
            val rows = dir.resolve(f).readLines().count { it.contains('\t') }
            val allowed = known[lang] ?: cap
            if (rows > allowed) over.add("$lang $rows (allowed $allowed)")
            if (rows > cap) out.append("    %-3s %6d rows, %d past the cap%n".format(
                lang, rows, rows - cap))
        }
        println(if (out.isEmpty()) "    every model within $cap rows" else out)
        assertTrue(
            "a prediction model has grown past what tools/build_ngrams.py caps a " +
                "fresh build at, which merge() cannot undo: $over",
            over.isEmpty()
        )
    }

    @Test
    fun `what a loaded prediction model costs`() {
        val out = StringBuilder()
        var worstMb = 0.0
        for (lang in com.rimboard.keyboard.model.Languages.codes) {
            val (bytes, rows) = costOf(lang)
            val mb = bytes / 1024.0 / 1024.0
            if (mb > worstMb) worstMb = mb
            out.append("    %-3s %6d rows  %5.1f MB in memory%n".format(lang, rows, mb))
            held = null
            settle()
        }
        println(out)
        // A ceiling with room, not a tracking threshold. Two languages are held
        // at once at most, so this is the budget for one of the pair.
        assertTrue(
            "a single prediction model now costs %.1f MB, which is more than an\n%s"
                .format(worstMb, "IME should hold for one language:\n$out"),
            worstMb < 12.0
        )
    }

    /**
     * What a language costs when both halves of it are loaded.
     *
     * The two footprint tests each weigh one structure and neither adds them
     * up, and the sentence the project quotes came from the dictionary half
     * alone -- "two languages plus the spell checker sharing neither: 27 MB",
     * which is three times the largest *dictionary* and no model at all. A
     * loaded language is both. Models are 30-45% of one, so the quoted figure
     * understated an ordinary configuration by about a third.
     *
     * This is the arm that owns that question. Per language, dictionary and
     * model in one engine, then the worst realistic configuration: the two
     * heaviest languages a user could enable at once, plus the spell checker,
     * which shares neither and holds its own copy of one of them.
     */
    @Test
    fun `what an ordinary configuration costs`() {
        val out = StringBuilder()
        val totals = LinkedHashMap<String, Double>()
        for (lang in com.rimboard.keyboard.model.Languages.codes) {
            val locale = Locale.forLanguageTag(lang)
            val dictText = File(assets(), "dictionaries/$lang.txt").readText()
            val predText = File(assets(), "predictions/$lang.txt").readText()
            val before = used()
            val e = SuggestionEngine.forTesting(userData) { p ->
                when (p) {
                    "dictionaries/$lang.txt" -> dictText.byteInputStream()
                    "predictions/$lang.txt" -> predText.byteInputStream()
                    else -> null
                }
            }
            e.dictionary(lang, locale)
            e.predictions("", "the", lang, locale, 3)
            held = e
            val mb = (used() - before) / 1024.0 / 1024.0
            totals[lang] = mb
            out.append("    %-3s %5.1f MB loaded%n".format(lang, mb))
            held = null
            settle()
        }
        println(out)
        val ranked = totals.entries.sortedByDescending { it.value }
        val pair = ranked.take(2)
        // The spell checker resolves its own locale from the field and shares
        // nothing with the keyboard, so at worst it holds another copy of the
        // heaviest of the two.
        val configuration = pair.sumOf { it.value } + pair.first().value
        println(
            "worst configuration: %s + %s enabled, spell checker on %s = %.1f MB"
                .format(pair[0].key, pair[1].key, pair[0].key, configuration)
        )
        assertTrue("nothing was measured:%n$out".format(), configuration > 5.0)
        assertTrue(
            ("an ordinary configuration now costs %.1f MB, which is more than " +
                "an IME should be while the user can still see it:%n%s")
                .format(configuration, out),
            configuration < CONFIGURATION_CEILING_MB
        )
    }

    private companion object {
        /**
         * Two languages and a spell checker, both halves of each loaded.
         *
         * Measured at 41 MB on a desktop JVM, where object headers are larger
         * than Android's, so the figure on a phone is somewhat lower. Fifty is
         * far enough above to survive a heap that settled differently and
         * close enough to notice a structure being added back -- which is the
         * failure this whole file exists to catch, since an input method is
         * the lowest-priority process the user can still see.
         */
        const val CONFIGURATION_CEILING_MB = 50.0
    }
}
