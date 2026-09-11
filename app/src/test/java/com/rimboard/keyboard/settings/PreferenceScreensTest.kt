package com.rimboard.keyboard.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * That the settings screens are navigable, and that a switch which does
 * nothing says so.
 *
 * Two faults this guards, both of which the app shipped with:
 *
 * **A preference that is inert is still drawn live.** `haptic_strength` is read
 * only inside `Haptics.tap`, and every caller of that is behind
 * `if (Prefs.haptic(...))` -- so with vibration off, the strength picker is a
 * control that changes nothing, offered at full contrast. There were three
 * `android:dependency` declarations in the whole app against 109 preferences.
 *
 * **Nine flat lists.** Not one `PreferenceCategory` existed; the Look and feel
 * screen was eighteen unrelated rows in a column.
 *
 * Neither has behaviour a JVM test can execute -- an absent attribute throws
 * nothing -- so this reads the XML, as the other wiring tests here read source.
 * `src/main/res` is already a declared input of the test task.
 *
 * **The dependency target must live in the same file.** `Preference` resolves
 * it through `findPreferenceInHierarchy`, and a name that is not on the same
 * screen throws `IllegalStateException` while the screen is being inflated --
 * a crash on opening settings, not a silent miss. That is the assertion below
 * worth the most.
 */
class PreferenceScreensTest {

    private fun dir(vararg candidates: String): File {
        for (c in candidates) File(c).let { if (it.isDirectory) return it }
        throw AssertionError("none of ${candidates.toList()} from ${File(".").absolutePath}")
    }

    private val xmlDir by lazy { dir("src/main/res/xml", "app/src/main/res/xml") }
    private val valuesDir by lazy { dir("src/main/res", "app/src/main/res") }

    private fun screens(): List<Pair<String, String>> =
        xmlDir.listFiles()!!
            .filter { it.name.startsWith("prefs_") || it.name == "preferences.xml" }
            .sortedBy { it.name }
            .map { it.name to it.readText() }

    /** Every `android:key` in [xml], with the tag that carries it. */
    private fun keys(xml: String): List<Pair<String, String>> =
        Regex("""<(\w+)\b[^>]*?android:key="([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()

    @Test
    fun `every dependency names a preference on the same screen`() {
        val broken = mutableListOf<String>()
        for ((name, xml) in screens()) {
            val present = keys(xml).map { it.second }.toSet()
            for (m in Regex("""android:dependency="([^"]+)"""").findAll(xml)) {
                val target = m.groupValues[1]
                if (target !in present) broken += "$name -> $target"
            }
        }
        assertEquals(
            "a dependency whose target is not on the same screen throws " +
                "IllegalStateException while that screen inflates, so settings " +
                "will not open at all: $broken",
            emptyList<String>(), broken
        )
    }

    @Test
    fun `every dependency target is a two-state preference`() {
        val wrong = mutableListOf<String>()
        for ((name, xml) in screens()) {
            val tagOf = keys(xml).toMap(HashMap<String, String>().also { m ->
                keys(xml).forEach { (tag, key) -> m[key] = tag }
            })
            for (m in Regex("""android:dependency="([^"]+)"""").findAll(xml)) {
                val target = m.groupValues[1]
                val tag = tagOf[target] ?: continue
                if (!tag.contains("Switch") && !tag.contains("CheckBox")) {
                    wrong += "$name: $target is a $tag"
                }
            }
        }
        assertTrue(
            "only a two-state preference reports shouldDisableDependents from " +
                "its own value; depending on anything else disables the child " +
                "whenever the parent is merely disabled, which is not the rule " +
                "anyone meant: $wrong",
            wrong.isEmpty()
        )
    }

    /**
     * The six added on 2026-09-11, each checked against the code that reads it.
     *
     * Pinned by name because the interesting part is not that *some*
     * dependencies exist but that these particular ones do: each was verified,
     * and three plausible-looking others were rejected because the code says
     * otherwise -- `number_row_passwords` is OR-ed with `number_row` rather
     * than gated by it, `glide_delete` is a backspace gesture that does not
     * need glide typing, and `app_color_source` is reached when either
     * `theme_per_app` or `match_app_mode` is on. Deleting a line here should
     * take an argument, not a tidy-up.
     */
    @Test
    fun `the verified dependencies are still declared`() {
        val all = screens().flatMap { (_, xml) ->
            Regex("""<(?:.|\n)*?android:key="([^"]+)"(?:.|\n)*?/>""")
                .findAll(xml)
                .mapNotNull { m ->
                    val dep = Regex("""android:dependency="([^"]+)"""")
                        .find(m.value)?.groupValues?.get(1)
                    if (dep == null) null else m.groupValues[1] to dep
                }
        }.toMap()
        val expected = mapOf(
            "haptic_strength" to "haptic",
            "sound_volume" to "sound",
            "glide_trail" to "glide_typing",
            "remember_case" to "learn_words",
            "calc_chip" to "suggestions",
            "expand_suggestions" to "suggestions",
            "autocorrect_cautious" to "autocorrect",
            "post_correct" to "autocorrect",
            "tint_strength" to "theme_per_app"
        )
        for ((child, parent) in expected) {
            assertEquals(
                "$child no longer depends on $parent, so it is drawn live " +
                    "while it does nothing",
                parent, all[child]
            )
        }
    }

    @Test
    fun `a long screen is grouped`() {
        val flat = mutableListOf<String>()
        for ((name, xml) in screens()) {
            if (name == "preferences.xml") continue   // the hub: rows, not settings
            val n = keys(xml).size
            val cats = Regex("<PreferenceCategory").findAll(xml).count()
            if (n > 8 && cats == 0) flat += "$name ($n rows)"
        }
        assertTrue(
            "a screen of more than eight settings with no category headers is " +
                "a wall of switches -- which all nine of these were: $flat",
            flat.isEmpty()
        )
    }

    @Test
    fun `every category header has a string, in every locale`() {
        val used = screens().flatMap { (_, xml) ->
            Regex("""<PreferenceCategory[^>]*android:title="@string/([^"]+)"""")
                .findAll(xml).map { it.groupValues[1] }.toList()
        }.toSet()
        assertTrue("no category headers found at all", used.isNotEmpty())

        val locales = valuesDir.listFiles()!!
            .filter { it.isDirectory && it.name.startsWith("values") }
            .sortedBy { it.name }
        val missing = mutableListOf<String>()
        for (d in locales) {
            val f = File(d, "strings.xml")
            if (!f.isFile) continue
            val text = f.readText()
            for (k in used) {
                if (!text.contains("name=\"$k\"")) missing += "${d.name}/$k"
            }
        }
        assertEquals(
            "a category title missing from a locale fails lint's " +
                "MissingTranslation, which this project stopped suppressing: " +
                missing,
            emptyList<String>(), missing
        )
    }
}
