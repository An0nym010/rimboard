package com.rimboard.keyboard.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * That the theme row still leads somewhere.
 *
 * It used to be a `ListPreference`, which brought its own dialog, its own
 * summary and its own persistence for free. It is a plain `Preference` now and
 * has none of those: the dialog is [ThemePickerActivity], the summary is set in
 * code, and the write goes through `Prefs.setTheme`. Drop any one of the three
 * and the row still *renders* — it simply does nothing, or names the wrong
 * theme forever. None of that throws, so none of it is observable by running
 * the app briefly, which is the shape every wiring test in this project exists
 * for.
 *
 * The manifest check is the one that would otherwise cost a crash: an activity
 * that is started but not declared throws `ActivityNotFoundException` at the
 * moment of the tap, and no unit test reaches that code path.
 */
class ThemePickerWiringTest {

    private fun at(vararg candidates: String): File {
        for (c in candidates) File(c).let { if (it.isFile) return it }
        throw AssertionError("none of ${candidates.toList()} from ${File(".").absolutePath}")
    }

    private fun src(rel: String) =
        at("src/main/java/$rel", "app/src/main/java/$rel").readText()

    private fun res(rel: String) = at("src/main/res/$rel", "app/src/main/res/$rel").readText()

    private fun manifest() =
        at("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").readText()

    @Test
    fun `the theme row opens the picker`() {
        val settings = src("com/rimboard/keyboard/settings/SettingsActivity.kt")
        assertTrue(
            "nothing starts ThemePickerActivity, so the theme row is a row " +
                "that does nothing when tapped",
            settings.contains("ThemePickerActivity::class.java")
        )
        assertTrue(
            "the theme row has no click listener",
            Regex("""findPreference<Preference>\("theme"\)""").containsMatchIn(settings)
        )
    }

    @Test
    fun `the row still names the theme it is set to`() {
        val settings = src("com/rimboard/keyboard/settings/SettingsActivity.kt")
        assertTrue(
            "useSimpleSummaryProvider went with the ListPreference, and " +
                "nothing replaced it: the row would show no theme name at all",
            settings.contains("fun showThemeName(")
        )
        assertTrue(
            "the summary is never refreshed, so the row goes on naming " +
                "whichever theme was current when the screen was built — the " +
                "picker changes it behind this screen's back",
            settings.contains("override fun onResume()") &&
                settings.contains("showThemeName(it)")
        )
    }

    @Test
    fun `the picker is declared in the manifest`() {
        assertTrue(
            "ThemePickerActivity is started but not declared, which throws " +
                "ActivityNotFoundException when the row is tapped",
            manifest().contains(".settings.ThemePickerActivity")
        )
    }

    @Test
    fun `the theme row is no longer a list preference`() {
        val xml = res("xml/prefs_theme.xml")
        val row = Regex("""<(\w+)[^>]*android:key="theme"""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)
        assertEquals(
            "a ListPreference here brings back the dialog of names the picker " +
                "replaced, and both would fire on one tap",
            "Preference", row
        )
    }

    @Test
    fun `every theme has a name and the picker can show it`() {
        val arrays = res("values/arrays.xml")
        fun items(name: String): List<String> {
            val block = Regex(
                """<string-array name="$name">(.*?)</string-array>""",
                RegexOption.DOT_MATCHES_ALL
            ).find(arrays)?.groupValues?.get(1) ?: return emptyList()
            return Regex("""<item>([^<]*)</item>""").findAll(block)
                .map { it.groupValues[1] }.toList()
        }
        val values = items("theme_values")
        val entries = items("theme_entries")
        assertTrue("theme_values is empty", values.isNotEmpty())
        assertEquals(
            "the picker pairs these two arrays by index, so a difference in " +
                "length puts the wrong name under a thumbnail",
            entries.size, values.size
        )

        // Every value must be a branch of Themes.resolve, or the picker draws
        // the fallback palette under a name that promised something else.
        val theme = src("com/rimboard/keyboard/theme/Theme.kt")
        val missing = values.filter { !theme.contains("\"$it\" ->") }
        assertTrue(
            "Themes.resolve has no branch for these, so the picker shows the " +
                "default theme under their names: $missing",
            missing.isEmpty()
        )
    }
}
