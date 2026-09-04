package com.rimboard.keyboard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The next-word context is read out of the field on focus, not assumed.
 *
 * `onStartInputView` sets `prevWordForBigram = ""` and `atSentenceStart =
 * true`, which together mean "the start of a sentence". That is a guess about
 * the field made without looking at it: right for an empty box, wrong for
 * every other one — a draft being edited, a search box holding the last query,
 * and above all the ordinary case of hiding the keyboard mid-sentence, or
 * leaving the app and coming back, and typing on.
 *
 * It cost two things. The strip offered sentence openers ("hi", "thanks",
 * "I") in the middle of a sentence, until a cursor move happened to bring
 * `onUpdateSelection` along with the real context. And the next word committed
 * was filed under `UserData.START`, teaching the opener model that a
 * mid-sentence word starts sentences — so the fault fed the very list it
 * spoiled, since the openers are what fills the strip on an empty field.
 *
 * A unit test cannot drive an `InputMethodService`, so this reads the source
 * the way [InputRoutingTest] and `NetGateTest` do. What it guards is an
 * *ordering*, which is the part that cannot be seen by looking at either line
 * on its own: the read has to happen before the strip is drawn, or the first
 * draw is still the guess.
 *
 * The answer the read produces is `SentenceContext.from`'s, and that is tested
 * directly and thoroughly in `SentenceContextTest`. Only the wiring is here.
 */
class FocusContextTest {

    private fun serviceSource(): String {
        for (p in listOf(
            "src/main/java/com/rimboard/keyboard/RimBoardService.kt",
            "app/src/main/java/com/rimboard/keyboard/RimBoardService.kt"
        )) {
            val f = File(p)
            if (f.isFile) return f.readText()
        }
        throw AssertionError("RimBoardService not found")
    }

    /** The body of `configureAll`, which every focus and rebuild goes through. */
    private fun configureAllBody(): String {
        val src = serviceSource()
        val start = src.indexOf("private fun configureAll(")
        assertTrue("configureAll not found — was it renamed?", start >= 0)
        val end = src.indexOf("override fun onFinishInputView", start)
        assertTrue("could not find the end of configureAll", end > start)
        return src.substring(start, end)
    }

    @Test
    fun `focus reads the context from the field before drawing the strip`() {
        val body = configureAllBody()
        val read = body.indexOf("refreshContextFromCursor()")
        val draw = body.indexOf("updateStrip()")
        assertTrue(
            "configureAll no longer reads the context from the cursor, so the " +
                "first strip of every focus is drawn from the assumption that " +
                "the field is at a sentence start",
            read >= 0
        )
        assertTrue("configureAll no longer draws the strip — was it renamed?", draw >= 0)
        assertTrue(
            "the context is read after the strip is drawn, so the first draw " +
                "is still the guess",
            read < draw
        )
    }

    /**
     * And the read is skipped while a word is being composed.
     *
     * Mid-word the text before the cursor ends in half a word, and half a word
     * is not the previous word — the same reason `onUpdateSelection` guards
     * its own call this way. Reachable through the two callers that rebuild
     * the input view without restarting input: a rotation and the floating
     * toggle, both of which leave the composing text in place.
     */
    @Test
    fun `the read is guarded on there being no composing word`() {
        val body = configureAllBody()
        val read = body.indexOf("refreshContextFromCursor()")
        assertTrue(read >= 0)
        val line = body.lastIndexOf('\n', read).let { body.substring(it + 1, read) }
        assertTrue(
            "the context read is not guarded on composing being empty: " + line.trim(),
            line.contains("composing.isEmpty()")
        )
    }

    /**
     * The assumption itself stays, as the fallback.
     *
     * A field that will not answer `getTextBeforeCursor` leaves
     * `refreshContextFromCursor` returning without touching anything, and what
     * it leaves behind has to be a sentence start rather than whatever the
     * previous field ended on.
     */
    @Test
    fun `the sentence-start default is still set when input starts`() {
        val src = serviceSource()
        val start = src.indexOf("override fun onStartInputView(")
        assertTrue(start >= 0)
        val body = src.substring(start, src.indexOf("private fun configureAll(", start))
        assertTrue(
            "onStartInputView no longer resets the context, so a field that " +
                "will not answer keeps the last field's previous word",
            body.contains("prevWordForBigram = \"\"") && body.contains("atSentenceStart = true")
        )
    }
}
