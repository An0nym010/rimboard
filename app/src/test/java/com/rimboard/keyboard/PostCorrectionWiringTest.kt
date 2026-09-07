package com.rimboard.keyboard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the four seams that decide whether post-correction runs at all.
 *
 * [com.rimboard.keyboard.model.PostCorrection] is a pure decision and is tested
 * as one; `PostCorrectionAccuracyTest` measures what that decision is worth
 * against the real dictionary. **Neither of them can tell whether the keyboard
 * ever asks.** A unit test cannot drive an `InputMethodService`, so this reads
 * the source the way `InputRoutingTest` and `NetGateTest` do.
 *
 * It exists because the feature shipped dead for the length of one afternoon
 * and nothing noticed. `clearWordState()` drops everything describing the word
 * just committed and is called from nine places, one of which is `typeText` —
 * so the pending word was wiped by the first letter of the very word it was
 * waiting for. Every test downstream still passed, because every one of them
 * reaches the decision directly instead of through a keystroke, and the two
 * that measure accuracy went on reporting four-fifths of typos rescued by a
 * code path the keyboard could no longer reach.
 *
 * That is the shape of fault this file is for: not a wrong answer, an
 * unreachable one.
 */
class PostCorrectionWiringTest {

    private fun serviceSource(): String {
        for (p in listOf(
            "src/main/java/com/rimboard/keyboard/RimBoardService.kt",
            "app/src/main/java/com/rimboard/keyboard/RimBoardService.kt"
        )) {
            val f = File(p)
            if (f.isFile) return f.readText()
        }
        throw AssertionError("RimBoardService not found from ${File(".").absolutePath}")
    }

    /** The body of a function, from its declaration to the next one at the same depth. */
    private fun bodyOf(src: String, decl: String): String {
        val start = src.indexOf(decl)
        assertTrue("$decl not found — was it renamed?", start >= 0)
        val next = src.indexOf("\n    private fun ", start + decl.length)
        val alt = src.indexOf("\n    fun ", start + decl.length)
        val end = listOf(next, alt).filter { it >= 0 }.minOrNull() ?: src.length
        return src.substring(start, end)
    }

    @Test
    fun `an ordinary keystroke does not throw the pending word away`() {
        val body = bodyOf(serviceSource(), "private fun typeText(")
        val clear = body.indexOf("clearWordState()")
        val save = body.indexOf("val carried = pendingPost")
        val restore = body.indexOf("pendingPost = carried")
        assertTrue(
            "typeText no longer saves the pending word before clearWordState(). " +
                "clearWordState() nulls it, and typeText runs on every " +
                "keystroke — so post-correction is dead: the word waiting to " +
                "be reconsidered is dropped by the first letter of the word " +
                "that would have settled it. Nothing else in the suite can see " +
                "this, which is why it is asserted here.",
            save in 0 until clear
        )
        assertTrue(
            "typeText no longer restores the pending word after " +
                "clearWordState(); see above, the effect is the same",
            restore > clear
        )
    }

    @Test
    fun `anything that is not a keystroke does throw it away`() {
        val body = bodyOf(serviceSource(), "private fun clearWordState()")
        assertTrue(
            "clearWordState() no longer clears pendingPost. It is the one " +
                "place a backspace, a paste, a focus change and a committed " +
                "emoji all reach, and a pending word that outlives them names " +
                "a position in the text that has moved.",
            body.contains("pendingPost = null")
        )
    }

    @Test
    fun `the field is proved before any of it is deleted`() {
        val body = bodyOf(serviceSource(), "private fun maybePostCorrect(")
        val read = body.indexOf("getTextBeforeCursor")
        val guard = body.indexOf("!= expect) return")
        val delete = body.indexOf("deleteSurroundingText")
        assertTrue("maybePostCorrect no longer reads the field back", read >= 0)
        assertTrue(
            "maybePostCorrect no longer bails out when the field does not read " +
                "as the two words it is about. This reaches back over a whole " +
                "word in an editor belonging to another app; deleting by " +
                "length without checking is how it would eat something else.",
            guard in 0 until delete
        )
    }

    @Test
    fun `the previous word is reconsidered before the pending slot is overwritten`() {
        val body = bodyOf(serviceSource(), "private fun commitComposedWord(")
        val ask = body.indexOf("maybePostCorrect(")
        val arm = body.indexOf("pendingPost =\n")
        assertTrue("commitComposedWord no longer calls maybePostCorrect", ask >= 0)
        assertTrue("commitComposedWord no longer arms a pending word", arm >= 0)
        assertTrue(
            "the pending word is armed for *this* commit before the previous " +
                "one has been reconsidered, so every word would be judged " +
                "against itself and post-correction would never fire",
            ask < arm
        )
    }
}
