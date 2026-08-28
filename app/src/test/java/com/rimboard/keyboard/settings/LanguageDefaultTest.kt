package com.rimboard.keyboard.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The one preference default this app computes rather than declares.
 *
 * androidx.preference seeds a widget from its XML `defaultValue` when nothing
 * is stored, which makes a declared default a decision that outranks the
 * code's. For every setting here that is fine, because the two agree --
 * `PrefDefaultsTest` walks them and says so. The enabled languages are the
 * exception: the code's answer is the device's own language beside English,
 * worked out at first use, and XML cannot express that.
 *
 * So the XML declared `@array/lang_default`, which was the literal pair `en`
 * and `tr`. A German user's keyboard ran German and English until the moment
 * they opened the settings screen, which showed them English and Turkish
 * ticked and wrote that down the first time they touched the dialog. The
 * keyboard was right until the user looked at it.
 */
class LanguageDefaultTest {

    private fun prefsSrc(): String =
        listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
            .resolve("com/rimboard/keyboard/settings/Prefs.kt").readText()

    @Test
    fun `the one default the XML cannot know is not declared there`() {
        // androidx.preference seeds a widget from its XML default when nothing
        // is stored, so a declared default is a decision that outranks the
        // code's. For the enabled languages the code's answer is the device's
        // own language beside English and the XML's was the literal pair
        // en + tr, which meant opening the settings screen showed a German
        // user two languages they had not chosen and offered to save them.
        val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        val xml = res.resolve("xml/preferences.xml").readText()
        val block = xml.substring(xml.indexOf("android:key=\"languages\""))
            .substringBefore("/>")
        assertTrue(
            "the languages preference declares a static default again: $block",
            !block.contains("defaultValue")
        )
        assertTrue(
            "the array that default pointed at is back",
            !res.resolve("values/arrays.xml").readText().contains("lang_default")
        )
        val prefs = prefsSrc()
        assertTrue(
            "nothing writes the computed default before the screen reads it",
            prefs.contains("fun seedComputedDefaults(")
        )
        val seed = prefs.substring(prefs.indexOf("fun seedComputedDefaults("))
            .substringBefore("\n    }")
        assertTrue(
            "the seed overwrites a stored choice instead of filling an absence: $seed",
            seed.contains("!p.contains(KEY_LANGUAGES)")
        )
        val settings = listOf(File("src/main/java"), File("app/src/main/java"))
            .first { it.isDirectory }
            .resolve("com/rimboard/keyboard/settings/SettingsActivity.kt").readText()
        val order = settings.indexOf("Prefs.seedComputedDefaults(")
        assertTrue("the settings screen never seeds", order > 0)
        assertTrue(
            "the seed runs after the screen inflates, which is too late -- the " +
                "widget has already taken its value",
            order < settings.indexOf("setPreferencesFromResource(")
        )
    }
}
