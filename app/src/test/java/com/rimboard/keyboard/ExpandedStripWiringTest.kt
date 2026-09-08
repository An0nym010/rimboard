package com.rimboard.keyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The seams between a swipe on the strip and a word in the field.
 *
 * `ChipRowsTest` holds the arithmetic and `ExpandedSuggestionsTest` holds the
 * list; neither can see whether the gesture reaches anything, because the
 * gesture ends in an `InputMethodService`. Source-read, like
 * `PostCorrectionWiringTest`, `PersonalCaseWiringTest` and `NetGateTest`.
 *
 * Four of these guard mistakes this project has already made once. The panel
 * list going stale is the fault `panels()` was written to prevent and then
 * grew a second copy of; a panel without a way out is what the tools panel
 * shipped as; and finishing the composing region before opening a panel is
 * what every *other* panel correctly does and is exactly wrong here.
 */
class ExpandedStripWiringTest {

    private fun source(rel: String): String {
        for (p in listOf("src/main/java/$rel", "app/src/main/java/$rel")) {
            val f = File(p)
            if (f.isFile) return f.readText()
        }
        throw AssertionError("$rel not found from ${File(".").absolutePath}")
    }

    private fun service() = codeOnly(source("com/rimboard/keyboard/RimBoardService.kt"))
    private fun strip() = codeOnly(source("com/rimboard/keyboard/ui/SuggestionStripView.kt"))

    /**
     * The same text with its comments removed.
     *
     * A source scan cannot tell code from prose, and this file is full of
     * prose about the code it is scanning -- the very function below carries a
     * comment saying "**not** `finishComposingSilently()`", which made the
     * assertion about that call pass on its own explanation. Every check here
     * runs on the code alone.
     */
    private fun codeOnly(src: String): String =
        src.lineSequence()
            .map { line ->
                val i = line.indexOf("//")
                if (i >= 0) line.substring(0, i) else line
            }
            .joinToString(separator = "\n")
            .replace(Regex("(?s)/\\*.*?\\*/"), "")

    /** The body of a function, from its declaration to the next one at the same depth. */
    private fun bodyOf(src: String, decl: String): String {
        val start = src.indexOf(decl)
        assertTrue("$decl not found - was it renamed?", start >= 0)
        val ends = listOf("\n    private fun ", "\n    fun ", "\n    override fun ")
            .map { src.indexOf(it, start + decl.length) }
            .filter { it >= 0 }
        return src.substring(start, ends.minOrNull() ?: src.length)
    }

    @Test
    fun `the strip claims an upward drag and reports it`() {
        val s = strip()
        assertTrue(
            "the strip no longer intercepts touches, so the swipe never " +
                "happens -- and this view having no touch handling at all is " +
                "what made the gesture available in the first place",
            s.contains("override fun onInterceptTouchEvent(")
        )
        val body = bodyOf(s, "override fun onInterceptTouchEvent(")
        assertTrue(
            "the gesture is no longer reported to the service",
            body.contains("onSuggestionsExpandRequested()")
        )
        assertTrue(
            "the drag is no longer held to the touch slop. Without it a tap " +
                "on a chip is stolen from the chip, and every suggestion on " +
                "the strip stops being tappable.",
            body.contains("touchSlop")
        )
    }

    @Test
    fun `only an upward drag, and only while words are showing`() {
        val body = bodyOf(strip(), "override fun onInterceptTouchEvent(")
        assertTrue(
            "the vertical test is gone, so a horizontal drag across the chips " +
                "now opens the panel",
            body.contains("abs(dy) > kotlin.math.abs(dx)") ||
                body.contains("abs(dy) > abs(dx)")
        )
        assertTrue(
            "the gesture is armed whatever the strip is showing. The drawer, " +
                "the clipboard chip and the autofill row are not suggestions, " +
                "and there is nothing to expand about them.",
            body.contains("wordsShowing()")
        )
    }

    @Test
    fun `the panel is in the one list that keeps panels exclusive`() {
        val body = bodyOf(service(), "private fun panels()")
        assertTrue(
            "the expanded suggestions panel is not in panels(), so opening " +
                "another panel leaves it on screen underneath -- which is the " +
                "exact fault that list's own comment says it exists to prevent",
            body.contains("suggestPanelHost")
        )
    }

    @Test
    fun `the panel carries its own way out`() {
        val src = service()
        assertTrue(
            "the expanded panel has no close control. The tools panel shipped " +
                "like that once, and the only way to leave it was to run " +
                "something.",
            src.contains("setOnClickListener { hideSuggestionsPanel() }")
        )
        assertTrue(
            "hideSuggestionsPanel no longer puts the keyboard back",
            bodyOf(src, "private fun hideSuggestionsPanel()").contains("showKeyboardBack()")
        )
    }

    @Test
    fun `the composing word is still composing when the panel opens`() {
        // Every other panel calls `finishComposingSilently()` before it opens,
        // because any of them might do anything next. This one ends in a word
        // replacing the one being typed, and `commitText` replaces the
        // composing region only while there still is one. Finishing first
        // would append instead: tapping "hello" after typing "hell" writes
        // "hellhello".
        val body = bodyOf(service(), "override fun onSuggestionsExpandRequested()")
        assertFalse(
            "the panel now finishes the composing region before it opens, so " +
                "picking a word from it appends instead of replacing",
            body.contains("finishComposingSilently()")
        )
        assertTrue("the panel is never shown", body.contains("revealPanel("))
        assertTrue(
            "the gesture no longer reads its preference, so the switch in " +
                "settings does nothing",
            body.contains("Prefs.expandSuggestions(this)")
        )
    }

    @Test
    fun `the panel rebuilds its rows outside the layout pass`() {
        // Found on a phone, and nothing in this suite could have found it.
        // The panel opened over the keyboard, correctly sized, with its close
        // bar in place -- and completely empty.
        //
        // `onSizeChanged` runs inside a layout traversal. Rebuilding the rows
        // there calls `addView`, which calls `requestLayout()` at the one
        // moment `requestLayout()` does nothing: the flag is already set and
        // the pass is already past this view. So the new rows were never
        // measured and sat at zero by zero -- and the rows built *before* the
        // panel was shown, which were about to be laid out correctly, had
        // just been removed to make way for them.
        //
        // Every other test here reads `ChipRows` for the arithmetic or the
        // source for the wiring, and a layout pass is neither.
        val src = codeOnly(source("com/rimboard/keyboard/ui/SuggestionsPanelView.kt"))
        val body = bodyOf(src, "override fun onSizeChanged(")
        assertTrue(
            "the panel rebuilds its rows directly from onSizeChanged again. " +
                "That is inside a layout traversal, so the rows it adds are " +
                "never measured and the panel opens empty -- which is exactly " +
                "how it shipped for one build.",
            body.contains("post {")
        )
    }

    @Test
    fun `a word picked in the panel goes through the same commit as a chip`() {
        val body = bodyOf(service(), "override fun onExpandedWordPicked(")
        assertTrue(
            "the panel commits a word by some route of its own. Everything a " +
                "chip does on the way -- the glide replacement, the learning, " +
                "the auto-space -- lives in onSuggestionPicked, and a second " +
                "route would have to grow all of it again.",
            body.contains("onSuggestionPicked(")
        )
        assertFalse(
            "index 0 is the revert chip on the strip. There is no revert chip " +
                "in the panel, and passing 0 would make a tap undo the last " +
                "autocorrect instead of committing the word.",
            body.contains("onSuggestionPicked(0,")
        )
    }
}
