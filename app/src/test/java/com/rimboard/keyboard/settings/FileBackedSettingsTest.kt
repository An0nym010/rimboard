package com.rimboard.keyboard.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The two Look and feel settings that are files rather than preferences.
 *
 * A custom font and a background photo are stored as `custom_font.ttf` and
 * `bg_image.jpg`, and that puts them outside everything the preference
 * framework does for free:
 *
 * - **`android:dependency` cannot name them**, so the rows that only make sense
 *   when the file exists have to be driven from code. "Image dimming" moved a
 *   number nobody could see whenever there was no photo — `PhotoBackdrop`
 *   paints the dim inside `bm?.let {}` and nowhere else.
 * - **No `OnSharedPreferenceChangeListener` fires**, so the pinned preview went
 *   on drawing a font that had just been removed until the screen was left and
 *   reopened.
 * - **`File.delete()` returns false rather than throwing** when there is
 *   nothing to delete, so "Remove custom font" reported success on a keyboard
 *   that had never had one.
 *
 * None of the three fails loudly, which is why they are asserted here.
 */
class FileBackedSettingsTest {

    private fun source(): String {
        for (p in listOf(
            "src/main/java/com/rimboard/keyboard/settings/SettingsActivity.kt",
            "app/src/main/java/com/rimboard/keyboard/settings/SettingsActivity.kt"
        )) File(p).let { if (it.isFile) return it.readText() }
        throw AssertionError("SettingsActivity.kt not found from ${File(".").absolutePath}")
    }

    private val src by lazy { source() }

    /**
     * The block that follows [marker], to its matching close brace.
     *
     * The marker has to be specific enough to be unique: `"font_clear"` alone
     * matches both the click listener and the visibility line in
     * `syncFileRows`, and the first of those in file order is not the one any
     * of these tests means.
     */
    private fun bodyOf(marker: String): String {
        val at = src.indexOf(marker)
        assertTrue("$marker not found", at >= 0)
        var i = src.indexOf('{', at)
        var depth = 0
        val start = i
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return src.substring(start, i + 1) }
            }
            i++
        }
        throw AssertionError("unbalanced braces after $marker")
    }

    @Test
    fun `a removal is only announced when something was removed`() {
        val markers = listOf(
            "\"font_clear\")?.setOnPreferenceClickListener",
            "\"bg_clear\")?.setOnPreferenceClickListener"
        )
        for (marker in markers) {
            val body = bodyOf(marker)
            assertTrue(
                "$marker throws the toast away from the result of delete(), " +
                    "so it says the file is gone whether or not there was one",
                body.contains("val gone =") && body.contains("if (gone)")
            )
        }
    }

    @Test
    fun `the rows that need a file are driven by whether it is there`() {
        val body = bodyOf("private fun syncFileRows(")
        assertTrue(
            "font_clear is no longer hidden when there is no font, so the " +
                "screen offers an action that cannot be performed",
            body.contains("""findPreference<Preference>("font_clear")?.isVisible""")
        )
        assertTrue(
            "bg_clear is no longer hidden when there is no photo",
            body.contains("""findPreference<Preference>("bg_clear")?.isVisible""")
        )
        assertTrue(
            "the dim slider is live with no photo again, where it moves a " +
                "number that reaches nothing",
            body.contains("bg_dim_pct") && body.contains("isEnabled = hasBg")
        )
    }

    /**
     * Every path that writes or deletes one of the two files has to say so.
     *
     * There are four: picking a font, clearing a font, clearing a photo, and
     * coming back from the crop screen — which is a separate activity, so that
     * one is `onResume` rather than a callback.
     */
    @Test
    fun `every file change tells the screen`() {
        val callers = listOf(
            "\"font_clear\")?.setOnPreferenceClickListener" to "clearing the font",
            "\"bg_clear\")?.setOnPreferenceClickListener" to "clearing the background",
            "private fun saveFont(" to "saving a font"
        )
        val missing = callers.filter { (marker, _) ->
            !bodyOf(marker).contains("afterFileChange()")
        }.map { it.second }
        assertEquals(
            "these change a file the keyboard's look depends on and do not " +
                "refresh the screen, so the preview keeps drawing the old one " +
                "and the Remove row keeps its old visibility: $missing",
            emptyList<String>(), missing
        )
        assertTrue(
            "onResume does not re-check the files, so returning from the " +
                "background crop screen leaves the rows describing what was " +
                "there before it ran",
            bodyOf("override fun onResume(").contains("syncFileRows()")
        )
        assertTrue(
            "afterFileChange no longer refreshes the preview, which is the " +
                "half of it a preference listener cannot do",
            bodyOf("private fun afterFileChange(").contains("preview?.refresh()")
        )
    }
}
