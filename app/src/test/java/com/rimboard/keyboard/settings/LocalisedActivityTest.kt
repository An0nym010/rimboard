package com.rimboard.keyboard.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The interface language has to reach screens that were already open.
 *
 * Every activity wrapped its base context, so each was correct when it was
 * built — and that was the whole of the bug. Changing the language recreated
 * only the screen the picker was on; everything else was already in the back
 * stack, built for the old locale, and Back returned to it unchanged. The
 * setup screen stayed in the previous language until the app was killed.
 */
class LocalisedActivityTest {

    @Test
    fun `an activity built for another language is rebuilt`() {
        assertTrue(LocalisedActivity.needsRebuild("tr", "en"))
        assertTrue(LocalisedActivity.needsRebuild("system", "de"))
    }

    @Test
    fun `an activity built for the current language is left alone`() {
        assertFalse(LocalisedActivity.needsRebuild("tr", "tr"))
        assertFalse(LocalisedActivity.needsRebuild("system", "system"))
    }

    @Test
    fun `nothing recorded is not a reason to rebuild`() {
        // An activity that recreates on the way up, with nothing to compare
        // against, would recreate forever. The null is "not recorded yet", and
        // it has to be told apart from "recorded, and different".
        assertFalse(LocalisedActivity.needsRebuild(null, "tr"))
        assertFalse(LocalisedActivity.needsRebuild(null, "system"))
    }

    /** Unit tests run from the module directory; tolerate the project root too. */
    private fun src(): File {
        for (p in listOf("src", "app/src")) {
            val f = File(p)
            if (f.isDirectory) return f
        }
        throw AssertionError("source directory not found from ${File(".").absolutePath}")
    }

    @Test
    fun `every activity is a localised one`() {
        // The fix is only as good as its coverage, and the failure is silent:
        // an activity extending AppCompatActivity directly still *looks* right,
        // because it is right until the language changes while it is open.
        // There is no way to notice that except by looking for it.
        val offenders = File(src(), "main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it to it.readText() }
            // The base class is the one place AppCompatActivity is the right
            // parent — it is what supplies the behaviour to everyone else.
            .filterNot { (_, text) -> text.contains("abstract class LocalisedActivity") }
            .filter { (_, text) -> Regex("""class \w+Activity\s*:""").containsMatchIn(text) }
            .filter { (_, text) -> text.contains(": AppCompatActivity()") }
            .map { (f, _) -> f.name }
            .toList()
        assertTrue(
            "these extend AppCompatActivity directly and will not pick up an " +
                "interface-language change made while they are open: $offenders",
            offenders.isEmpty()
        )
    }
}
