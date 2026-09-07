package com.rimboard.keyboard.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every preference screen must let its titles wrap.
 *
 * androidx's own row layout declares the title `android:singleLine="true"`,
 * so a title too long for one line is cut off with an ellipsis and the setting
 * cannot be read — "Return to keyboard after pastin..", "Long-press symbols key
 * for nu..". `SettingsFragment.unclipTitles` undoes it for the whole tree, and
 * this holds the two ways that could quietly stop being true: a second screen
 * loaded without the walk, and the walk itself being dropped.
 *
 * A source scan, because the alternative is an instrumented test that inflates
 * a preference list — and the failure being guarded against is somebody adding
 * a tenth `prefs_*.xml` or a second fragment and not knowing this seam exists,
 * which is visible in the text. Nine of this app's titles are long enough to
 * be clipped today, so the regression would be immediate and invisible in
 * every test that does not open the screen on a phone.
 */
class PreferenceTitlesTest {

    private fun source(): String {
        for (p in listOf(
            "src/main/java/com/rimboard/keyboard/settings/SettingsActivity.kt",
            "app/src/main/java/com/rimboard/keyboard/settings/SettingsActivity.kt"
        )) {
            val f = File(p)
            if (f.isFile) return f.readText()
        }
        throw AssertionError("SettingsActivity not found from ${File(".").absolutePath}")
    }

    @Test
    fun `every screen that is loaded also has its titles unclipped`() {
        val s = source()
        val loads = Regex("""setPreferencesFromResource\(""").findAll(s).count()
        val walks = Regex("""unclipTitles\(preferenceScreen\)""").findAll(s).count()
        assertTrue(
            "no screen is loaded any more — this scan has stopped matching and " +
                "is reporting success by measuring nothing",
            loads >= 1
        )
        assertTrue(
            "$loads preference screens are loaded but unclipTitles runs for " +
                "$walks of them. A screen loaded without it renders every long " +
                "title cut off mid-word, and nothing else in this suite can see " +
                "that — it only shows on a phone.",
            walks >= loads
        )
    }

    @Test
    fun `nothing re-enables the single-line title it exists to switch off`() {
        val res = listOf(File("src/main/res/xml"), File("app/src/main/res/xml"))
            .first { it.isDirectory }
        val offenders = res.listFiles().orEmpty()
            .filter { it.name.startsWith("pref") && it.extension == "xml" }
            .filter { it.readText().contains("singleLineTitle=\"true\"") }
            .map { it.name }
        assertTrue(
            "these screens set singleLineTitle=\"true\", which the XML attribute " +
                "applies per row and which overrides the whole-tree fix: " +
                offenders.joinToString(),
            offenders.isEmpty()
        )
    }
}
