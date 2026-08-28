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

    @Test
    fun `what a loaded prediction model costs`() {
        val out = StringBuilder()
        var worstMb = 0.0
        for (lang in listOf("en", "tr", "ru", "hr")) {
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
}
