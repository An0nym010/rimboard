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
            "the need is no longer measured. A character count decides the " +
                "width shares, where it runs per keystroke and the difference " +
                "does not matter; here it decides whether a chip is dropped, " +
                "and 'iii' against 'mmm' is a factor of three.",
            s.contains("measureText(")
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
