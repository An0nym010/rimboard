package com.rimboard.keyboard.spell

import com.rimboard.keyboard.engine.SuggestionEngine
import com.rimboard.keyboard.engine.UserData
import com.rimboard.keyboard.model.SpellTokens
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * What the system spell checker costs the app that asked.
 *
 * This is the one path in the project that runs inside somebody else's typing.
 * The framework calls it on a binder thread and the editor waits, so a slow
 * answer here is not RimBoard feeling slow — it is every other app's text field
 * feeling slow, with nothing on screen to blame.
 *
 * [SpellJudge.CORRECTION_BUDGET] exists for one case and says so: a paragraph
 * pasted in a language the user has not enabled, where every word is unknown
 * and every word therefore pays for a full dictionary scan, all inside a single
 * call. The bound was written from that reasoning and never from a number. This
 * is the number.
 *
 * Both arms are here because they measure different things. Ordinary prose is
 * what the checker actually does all day and should be nowhere near the bound.
 * Foreign text is the case the bound is for, measured with it and without it,
 * so the question "is this still worth having" has an answer rather than an
 * argument.
 *
 * ## The answer
 *
 *     English prose                     0.33 ms per sentence
 *     397-word foreign paste, budgeted 12.84 ms
 *     397-word foreign paste, unbounded 80.31 ms
 *
 * The budget is worth keeping and the reasoning behind it was right: eighty
 * milliseconds inside a call an editor is blocked on is a visible hang in
 * somebody else's app, and thirteen is not.
 *
 * Raising it now that a correction costs a fraction of what it used to buys
 * nothing. It has never fired on real writing — that is what the 0.33 ms line
 * says — and the only thing a larger budget would do is work out suggestions
 * for more of a four-hundred-word paste in a language the user does not have
 * enabled, which nobody is going to read.
 */
class SpellLatencyTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-spell-latency", "").let { it.delete(); it.mkdirs(); it }
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

    private fun judgeFor(lang: String, locale: Locale): SpellJudge {
        val files = listOf("dictionaries/$lang.txt", "predictions/$lang.txt")
            .associateWith { File(assets(), it).readText() }
        val engine = SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
        // The judge never loads the prediction model itself — that would parse
        // an asset on a binder thread — so a caller that wants the context
        // rules awake has to say so, exactly as warm() does in the app.
        engine.predictions("", "prime", lang, locale, 1)
        return SpellJudge(engine, lang, locale)
    }

    private fun sentences(name: String, n: Int): List<String> =
        File(fixtures(), "prose_$name.txt").readLines().filter { it.isNotBlank() }.take(n)

    /**
     * Judges [text] exactly as `RimSpellService.judgeSentence` does: one budget
     * for the sentence, two words of context carried forward.
     */
    private fun judgeSentence(judge: SpellJudge, text: String, budget: Int): Int {
        val tokens = SpellTokens.of(text)
        val b = Budget(budget)
        var prev2 = ""
        var prev = ""
        var flagged = 0
        for ((i, t) in tokens.withIndex()) {
            val next = tokens.getOrNull(i + 1)?.text.orEmpty()
            val v = judge.verdictFor(
                t.text, prev2, prev, next,
                suggestionsLimit = 5,
                sentenceInitial = t.startsSentence,
                budget = b
            )
            if (v.words.isNotEmpty()) flagged++
            prev2 = prev
            prev = t.text
        }
        return flagged
    }

    private fun timeMs(judge: SpellJudge, corpus: List<String>, budget: Int): Double {
        // Warm, so the first sentence does not carry the dictionary parse that
        // the app pays once on its own thread.
        for (s in corpus.take(20)) judgeSentence(judge, s, budget)
        val t0 = System.nanoTime()
        for (s in corpus) judgeSentence(judge, s, budget)
        return (System.nanoTime() - t0) / 1e6 / corpus.size
    }

    @Test
    fun `what a sentence costs the app that asked`() {
        val judge = judgeFor("en", Locale.ENGLISH)
        val ordinary = sentences("en", 120)

        // The case the budget is actually for, and it took a failing assertion
        // to build it correctly. The budget is twenty-four words *per
        // sentence*, and the fixture's sentences are five to fourteen words
        // long — so a foreign sentence, however completely unknown, can never
        // reach it. What reaches it is a paste: a wall of text the tokeniser
        // sees as one sentence because nothing in it ends one. Turkish prose
        // with the full stops removed and the lines run together is exactly
        // that, and every word of it is unknown to an English dictionary.
        val paste = listOf(
            sentences("tr", 60).joinToString(" ") { it.trimEnd('.', '!', '?') }
        )

        val own = timeMs(judge, ordinary, SpellJudge.CORRECTION_BUDGET)
        val alien = timeMs(judge, paste, SpellJudge.CORRECTION_BUDGET)
        val unbounded = timeMs(judge, paste, 100_000)

        val words = SpellTokens.of(paste.first()).size
        val report = ("English prose                    %.2f ms/sentence\n" +
            "pasted foreign wall ($words words)\n" +
            "   budgeted                      %.2f ms\n" +
            "   unbounded                     %.2f ms").format(own, alien, unbounded)
        println(report)
        println("the budget saves %.1fx on the case it is for".format(unbounded / alien))

        assertTrue(
            "spell checking ordinary prose has got expensive.\n$report",
            own <= ORDINARY_CEILING_MS
        )
        // Not merely "no worse than unbounded" -- that passed while the arm
        // was built wrongly and the budget never fired at all, which is how
        // this test came to be right. It has to be *bounding* something.
        assertTrue(
            "the correction budget has stopped bounding anything. It exists so " +
                "that a paragraph in an unenabled language cannot turn one " +
                "binder call into hundreds of dictionary scans.\n$report",
            alien <= unbounded / 2
        )
    }

    private companion object {
        /**
         * Above what ordinary prose measures, with room for a busy machine.
         *
         * A spell check is not on a frame deadline the way a keystroke is —
         * the editor asks and draws the underlines when the answer comes — so
         * this is looser than [com.rimboard.keyboard.engine.StripLatencyTest]'s
         * ceiling by design.
         */
        const val ORDINARY_CEILING_MS = 8.0
    }
}
