package com.rimboard.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * That the strip actually asks whether its words can be read.
 *
 * `StripLayoutTest` holds the arithmetic of
 * [com.rimboard.keyboard.model.StripLayout.chipsThatRead]; it cannot see
 * whether the view calls it, and a `ViewGroup` laying text out is not
 * something a JVM test can drive. Source-read, like the other wiring tests
 * here, and for the sharper of the two reasons they exist: **two of the three
 * things below are settable properties that nobody sets**, which is a fault
 * with no failing behaviour to observe -- the feature is simply never reached.
 *
 * `label_scale_pct` had been exactly that for a long time. It scaled the key
 * labels, the suggestion strip ignored it, and the row most likely to hold a
 * word you have never seen was the one that would not honour a request to make
 * the text bigger.
 */
class StripLegibilityWiringTest {

    private fun source(rel: String): String {
        for (p in listOf("src/main/java/$rel", "app/src/main/java/$rel")) {
            val f = File(p)
            if (f.isFile) return f.readText()
        }
        throw AssertionError("$rel not found from ${File(".").absolutePath}")
    }

    private fun codeOnly(src: String): String =
        src.lineSequence()
            .map { line ->
                val i = line.indexOf("//")
                if (i >= 0) line.substring(0, i) else line
            }
            .joinToString(separator = "\n")
            .replace(Regex("(?s)/\\*.*?\\*/"), "")

    private fun strip() = codeOnly(source("com/rimboard/keyboard/ui/SuggestionStripView.kt"))
    private fun service() = codeOnly(source("com/rimboard/keyboard/RimBoardService.kt"))
    private fun panel() = codeOnly(source("com/rimboard/keyboard/ui/SuggestionsPanelView.kt"))
    private fun chipText() = codeOnly(source("com/rimboard/keyboard/ui/ChipText.kt"))

    @Test
    fun `the strip drops a chip it cannot show legibly`() {
        val s = strip()
        assertTrue(
            "the strip no longer asks chipsThatRead, so it is back to fitting " +
                "five chips by touch target alone -- which is how a row of four " +
                "ellipsised words that all begin alike got shipped",
            s.contains("chipsThatRead(")
        )
        assertTrue(
            "the strip no longer reaches the shared measurement, so it is " +
                "measuring some other way or not at all",
            s.contains("ChipText.needDp(")
        )
        assertTrue(
            "the need is no longer measured. A character count decides the " +
                "width shares, where it runs per keystroke and the difference " +
                "does not matter; here it decides whether a chip is dropped, " +
                "and 'iii' against 'mmm' is a factor of three.",
            chipText().contains("measureText(")
        )
    }

    @Test
    fun `the row is sized once, not chip by chip`() {
        val s = strip()
        assertTrue(
            "the row no longer computes a single text size, so the chips are " +
                "back to being sized independently and the row reads as a list " +
                "of things of different importance",
            s.contains("rowTextSize(")
        )
        assertEquals(
            "Android's own auto-sizing is per chip, which is the raggedness " +
                "this replaced. If it comes back, the row picks its own sizes " +
                "again and rowTextSize is decoration.",
            0, Regex("setAutoSizeTextTypeUniformWithConfiguration").findAll(s).count()
        )
    }

    /**
     * The rule is one rule, and both surfaces that draw chips reach it.
     *
     * The strip stopped auto-sizing chip by chip in `6ba580a`; the expanded
     * panel kept doing it for two months, because the rule had been written
     * into the strip rather than into something both could call. On a phone
     * that is `control` drawn larger than `conversation` in grid columns of
     * identical width. Nothing executes the missing call, so the guard is
     * structural -- the same shape as the rest of this file.
     */
    @Test
    fun `the expanded panel sizes its grid by the same rule`() {
        val pnl = panel()
        assertEquals(
            "the panel is auto-sizing each chip again, which is what made " +
                "one column of a fixed-width grid smaller than the next",
            0, Regex("setAutoSizeTextTypeUniformWithConfiguration").findAll(pnl).count()
        )
        assertTrue(
            "the panel no longer reaches StripLayout.uniformTextSp, so it has " +
                "its own idea of chip size again",
            pnl.contains("uniformTextSp(")
        )
        assertTrue(
            "the strip no longer reaches StripLayout.uniformTextSp, so the " +
                "two surfaces have drifted apart from the other side",
            strip().contains("uniformTextSp(")
        )
        assertTrue(
            "the panel measures its own text instead of using the shared " +
                "measurement, which is how the two rules diverged before",
            pnl.contains("ChipText.needDp(")
        )
    }

    /**
     * The chevron yields its 34dp to the chips while a word is being typed.
     *
     * Two halves, and both have to hold. The view has to *hide* it, and the
     * width arithmetic has to *notice* -- a `fixed` that keeps subtracting
     * `CHEVRON_W` for a chevron that is GONE gives the 34dp to nobody, and
     * nothing fails: the row simply goes on fitting what it fitted before.
     * That is the same shape as every other guard in this file, which is why
     * it is here rather than left to a measurement nobody re-runs.
     *
     * Measured on the phone over fifteen five-letter prefixes: 5/15 reached
     * five chips before, 9/15 after.
     */
    @Test
    fun `the chevron yields its width while a word is composing`() {
        val s = strip()
        assertTrue(
            "showSuggestions no longer takes composingWord, so the strip " +
                "cannot tell a typed word from an idle prediction",
            s.contains("composingWord: Boolean")
        )
        assertTrue(
            "the chevron is unconditionally VISIBLE again, so it holds the " +
                "left end of the row at the moment the chips have least to spare",
            s.contains("if (composingWord) GONE else VISIBLE")
        )
        assertTrue(
            "the fixed width still charges for the chevron unconditionally. " +
                "Hiding it then is free of benefit: the 34dp goes to nobody " +
                "and no test fails.",
            s.contains("if (expandBtn.visibility == VISIBLE) dp(CHEVRON_W) else 0")
        )
        assertTrue(
            "the keyboard never tells the strip a word is composing, so the " +
                "chevron never yields in practice",
            service().contains("composingWord = true")
        )
    }

    @Test
    fun `the keyboard hands the strip the label scale`() {
        val s = strip()
        assertTrue("the strip has no labelScale to set", s.contains("var labelScale"))
        assertTrue(
            "nothing sets the strip's labelScale, so the setting moves the key " +
                "labels and leaves the suggestions where they were -- a " +
                "settable property with no setter, which is the shape of fault " +
                "this file exists for",
            service().contains("strip?.labelScale = Prefs.labelScalePct(this)")
        )
    }
}
