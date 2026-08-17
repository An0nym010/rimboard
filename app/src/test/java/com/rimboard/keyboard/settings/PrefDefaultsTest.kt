package com.rimboard.keyboard.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A preference's XML default and the default the code reads must agree.
 *
 * They are two separate declarations of the same fact, and when they disagree
 * the feature behaves one way before the user ever opens that settings screen
 * and another way afterwards — because the screen writes the XML default in on
 * first display, while everything else has been reading the Kotlin one. Nothing
 * errors, nothing logs, and the report is "it changed by itself".
 *
 * Nothing is wrong today: 48 preferences declare both and all 48 match. This
 * exists so that stays true, since adding a preference means writing the
 * default twice and only one of the two is near the code that reads it.
 */
class PrefDefaultsTest {

    private fun res(): File =
        listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    private fun prefsSource(): String =
        listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
            .resolve("com/rimboard/keyboard/settings/Prefs.kt").readText()

    @Test
    fun `every preference declares the same default in xml and in code`() {
        val xml = HashMap<String, Pair<String, String>>()   // key -> (default, file)
        File(res(), "xml").listFiles().orEmpty()
            .filter { it.name.startsWith("pref") && it.extension == "xml" }
            .forEach { f ->
                val text = f.readText()
                Regex("""<[A-Za-z]+Preference.*?(?:/>|>)""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(text)
                    .forEach { m ->
                        val key = Regex("""android:key="([^"]+)"""").find(m.value)?.groupValues?.get(1)
                        val def = Regex("""android:defaultValue="([^"]+)"""")
                            .find(m.value)?.groupValues?.get(1)
                        if (key != null && def != null) xml[key] = def to f.name
                    }
            }

        val source = prefsSource()
        val consts = Regex("""const val (KEY_\w+)\s*=\s*"([^"]+)"""")
            .findAll(source).associate { it.groupValues[1] to it.groupValues[2] }
        val code = HashMap<String, String>()
        Regex("""\.get(?:Boolean|String|Int|Float)\(\s*(KEY_\w+)\s*,\s*("?[^,)]+?"?)\s*\)""")
            .findAll(source)
            .forEach { m ->
                consts[m.groupValues[1]]?.let { code[it] = m.groupValues[2].trim().trim('"') }
            }

        val compared = xml.keys.filter { it in code }
        // Guards the guard: a scan that compares nothing reports clean, which
        // is the same output as a codebase with no problems.
        assertTrue(
            "only ${compared.size} preferences were compared — the scan has stopped matching",
            compared.size >= 40
        )

        val mismatched = compared.mapNotNull { k ->
            val (xd, file) = xml.getValue(k)
            val cd = code.getValue(k)
            if (!xd.equals(cd, ignoreCase = true)) "$k: $file says '$xd', Prefs says '$cd'" else null
        }
        assertTrue(
            "these defaults disagree, so the setting changes the first time its " +
                "screen is opened:\n" + mismatched.joinToString("\n"),
            mismatched.isEmpty()
        )
    }
}
