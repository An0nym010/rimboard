package com.rimboard.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Whether the keyboard ever asks about capitals, and whether it ever answers
 * itself.
 *
 * `PersonalCaseTest` pins the rule, `PersonalCaseStoreTest` the counting and
 * `PersonalCaseEngineTest` the two halves that have to meet. **None of them
 * can see the join**, because the join is in an `InputMethodService` and a
 * unit test cannot drive one. So this reads the source, the way
 * `PostCorrectionWiringTest`, `InputRoutingTest` and `NetGateTest` do -- and
 * it exists for the same reason the first of those does: post-correction
 * shipped dead for an afternoon behind a suite that was entirely green,
 * because everything downstream of the decision was tested and the one line
 * that reached it was not.
 *
 * The evidence side matters more here than the offering side. A rule that
 * learns from its own output does not fail loudly; it drifts, and every drift
 * is confirmation of the last. That is the fault that cost `PostCorrection`
 * and `ContextError` their whole purpose one commit apart, and the guard
 * against it here is that exactly one place in the keyboard records a capital,
 * and it is the place where the user typed it.
 */
class PersonalCaseWiringTest {

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
        assertTrue("$decl not found - was it renamed?", start >= 0)
        val next = src.indexOf("\n    private fun ", start + decl.length)
        val alt = src.indexOf("\n    fun ", start + decl.length)
        val over = src.indexOf("\n    override fun ", start + decl.length)
        val end = listOf(next, alt, over).filter { it >= 0 }.minOrNull() ?: src.length
        return src.substring(start, end)
    }

    // ---- the evidence side --------------------------------------------------

    @Test
    fun `exactly one place records how a word is capitalised`() {
        val src = serviceSource()
        assertEquals(
            "every other commit path puts a word in the field whose capitals " +
                "this rule supplied -- a tapped chip, a swipe, an autocorrect " +
                "-- so a second caller would be the store reading its own " +
                "output back as evidence that it was right. That is the exact " +
                "shape of the fault that made post-correction dead on a phone " +
                "and made the context spell checker blind to the errors its " +
                "own user makes.",
            1, Regex("userData[.]noteCase[(]").findAll(src).count()
        )
    }

    @Test
    fun `the capital is recorded only for a word committed exactly as typed`() {
        val body = bodyOf(serviceSource(), "private fun commitComposedWord(")
        val block = body.indexOf("if (learnedIt) {")
        val note = body.indexOf("userData.noteCase(")
        val after = body.indexOf("val fw = finalWord.lowercase(loc)")
        assertTrue("the learnedIt block is gone", block >= 0)
        assertTrue("nothing records the case any more", note >= 0)
        assertTrue("the marker this test measures against has moved", after > block)
        assertTrue(
            "the case is recorded outside the `learnedIt` block, so it is " +
                "being taken from words autocorrect rewrote as well as from " +
                "words the user typed",
            note in (block + 1) until after
        )
    }

    @Test
    fun `the sentence position is read before the commit resets it`() {
        val body = bodyOf(serviceSource(), "private fun commitComposedWord(")
        val read = body.indexOf("sentenceInitial = atSentenceStart")
        val reset = body.indexOf("atSentenceStart = false")
        assertTrue("nothing passes the sentence position any more", read >= 0)
        assertTrue("the reset is gone from this function", reset >= 0)
        assertTrue(
            "atSentenceStart is cleared before it is read, so every commit " +
                "now looks mid-sentence -- which means the capital that " +
                "auto-capitalisation supplies at the start of every sentence " +
                "is counted as the user's own, and the openers of their " +
                "sentences become proper nouns",
            read < reset
        )
    }

    // ---- the offering side --------------------------------------------------

    @Test
    fun `the rule is off in incognito and behind its own preference`() {
        val body = bodyOf(serviceSource(), "private fun personalCase(word: String)")
        assertTrue(
            "personalCase no longer checks incognito -- the learned capitals " +
                "are history, and history is what an incognito field is for " +
                "not using",
            body.contains("isIncognito()")
        )
        assertTrue(
            "personalCase no longer reads its preference, so the switch in " +
                "settings does nothing",
            body.contains("Prefs.rememberCase(this)")
        )
    }

    @Test
    fun `the strip cases every chip but the one already in the field`() {
        val body = bodyOf(serviceSource(), "private fun updateStrip()")
        assertTrue(
            "the strip no longer cases its chips, so a learned capital never " +
                "reaches the one place it is most visible",
            body.contains("personalCase(it)")
        )
        assertTrue(
            "the verbatim chip is no longer excluded. Slot 0 is what is " +
                "already on screen, and echoing it back in different capitals " +
                "is the keyboard arguing with the field.",
            body.contains("it == verbatim")
        )
        assertTrue(
            "the next-word chips are no longer cased. They are offered with " +
                "no typed letters to take a case from, so this is the only " +
                "thing that can capitalise them at all.",
            body.contains("personalCase(p)")
        )
    }

    @Test
    fun `a swipe with no shift held still gets the capital`() {
        val body = bodyOf(serviceSource(), "override fun onGlideComplete(")
        assertTrue(
            "a swipe carries no case -- the letter keys are the same keys " +
                "whatever the shift state -- so without this a swiped name is " +
                "committed in lower case however often it has been written",
            body.contains("personalCase(w)")
        )
    }

    @Test
    fun `both corrections case what they commit`() {
        val src = serviceSource()
        val commit = bodyOf(src, "private fun commitComposedWord(")
        assertTrue(
            "the space bar's correction is no longer cased, so the bold chip " +
                "and the word it promises to commit can now disagree about " +
                "capitals",
            commit.contains("finalWord = personalCase(it)")
        )
        val post = bodyOf(src, "private fun maybePostCorrect(")
        assertTrue(
            "post-correction is the same ruling asked one word later, and it " +
                "has stopped answering the same way",
            post.contains("personalCase(it)")
        )
    }
}
