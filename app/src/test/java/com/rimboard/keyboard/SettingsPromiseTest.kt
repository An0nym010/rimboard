package com.rimboard.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What a setting says it does, against what it does.
 *
 * A preference summary is the only explanation most people will ever read of
 * a behaviour, and several of them are precise enough to be wrong. "Insert a
 * space when a letter follows . , ! ?" named four characters; the code has six
 * — `AUTO_SPACE_PUNCT` is `".,!?;:"` — so the feature also fires after a
 * semicolon and a colon, which is correct typography and not what anyone
 * turning it on was told. All eight locales carried the same four, because
 * they are translations of the same wrong sentence.
 *
 * The behaviour is right and the sentence was wrong, so the sentence moved.
 *
 * This is the third documented promise this project has found stale in a day:
 * the README's download size (29 MB against 33), the README's language count,
 * and this. They have the same cause — a number or a list written once in
 * prose beside a constant that later changed — and the same fix, which is to
 * make the prose fail when the constant moves.
 *
 * ## Why the source is read rather than the constant imported
 *
 * `AUTO_SPACE_PUNCT` is private to the service, and making it internal so a
 * test could see it would widen an API for the benefit of a test. Reading the
 * file is honest about what is being checked and costs nothing: touching any
 * Kotlin recompiles the module, so this cannot go stale behind an UP-TO-DATE
 * task the way a data file can.
 */
class SettingsPromiseTest {

    private fun root(): File =
        listOf(File(".."), File(".")).first { File(it, "README.md").isFile }

    private fun strings(qualifier: String): String =
        File(root(), "app/src/main/res/$qualifier/strings.xml").readText()

    private fun summary(qualifier: String, key: String): String? =
        Regex("<string name=\"$key\">([^<]*)</string>").find(strings(qualifier))
            ?.groupValues?.get(1)

    private fun serviceSource(): String =
        File(root(), "app/src/main/java/com/rimboard/keyboard/RimBoardService.kt").readText()

    @Test
    fun `the punctuation autospace names is the punctuation it fires on`() {
        val m = Regex("AUTO_SPACE_PUNCT = \"([^\"]+)\"").find(serviceSource())
        assertTrue("AUTO_SPACE_PUNCT is no longer a literal in the service", m != null)
        val chars = m!!.groupValues[1]
        assertTrue("the autospace set is suspiciously small: $chars", chars.length >= 4)
        val said = summary("values", "pref_autospace_summary")
        assertTrue("no pref_autospace_summary in values/strings.xml", said != null)
        val unnamed = chars.filterNot { said!!.contains(it) }
        assertEquals(
            "autospace fires after these and the setting does not say so, which " +
                "is the only explanation of the behaviour anybody reads: " +
                "\"$unnamed\" missing from \"$said\"",
            "", unnamed
        )
    }

    /**
     * The same sentence in every locale, because the characters are not
     * translated.
     *
     * A locale that names four while English names six is telling its readers
     * something the keyboard does not do, and `TranslationGapTest` cannot see
     * it: that pins which keys exist, never what they say.
     */
    @Test
    fun `every translation of it names the same punctuation`() {
        val chars = Regex("AUTO_SPACE_PUNCT = \"([^\"]+)\"")
            .find(serviceSource())!!.groupValues[1]
        val wrong = StringBuilder()
        for (q in File(root(), "app/src/main/res").listFiles().orEmpty().sortedBy { it.name }) {
            if (!q.name.startsWith("values")) continue
            if (!File(q, "strings.xml").isFile) continue
            val said = summary(q.name, "pref_autospace_summary") ?: continue
            val unnamed = chars.filterNot { said.contains(it) }
            if (unnamed.isNotEmpty()) wrong.append("  ${q.name}: missing \"$unnamed\"\n")
        }
        assertEquals("a locale describes a different autospace than the code.\n$wrong",
            "", wrong.toString())
    }

    /**
     * Two neighbours checked at the same time, because the value of this test
     * is the sweep rather than the one string that was wrong.
     *
     * Both hold today. They are here so that changing the constant without the
     * sentence fails, which is the failure the autospace one actually had.
     */
    @Test
    fun `the clipboard chip and the double space say what they do`() {
        val clip = File(
            root(), "app/src/main/java/com/rimboard/keyboard/model/ClipChip.kt"
        ).readText()
        val ms = Regex("WINDOW_MS = ([0-9_]+)L").find(clip)!!.groupValues[1]
            .replace("_", "").toLong()
        assertEquals(
            "the Paste chip's window is no longer the minute the setting promises",
            60_000L, ms
        )
        assertTrue(
            "pref_clipboard_summary no longer says how long the chip lasts",
            summary("values", "pref_clipboard_summary")!!.contains("a minute")
        )
        assertTrue(
            "double space no longer commits \". \", which is what its setting says",
            serviceSource().contains("commitText(\". \", 1)")
        )
    }
}
