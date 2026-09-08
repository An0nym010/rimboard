package com.rimboard.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * That a session ends.
 *
 * `incognito_session` is the toolbar's dark-glasses toggle, and for a long time
 * it was an ordinary boolean in SharedPreferences that exactly one place wrote
 * and **nothing ever cleared**. So one tap stopped the keyboard learning
 * permanently -- across the field, the app, process death and a reboot -- while
 * the setting's own summary said "per-session" and the only sign was a small
 * mark on the suggestion strip.
 *
 * It was found on a real phone that had been in that state for an unknown
 * length of time, with its owner asking why the suggestions felt thin. No test
 * could have found it by running: the fault is the *absence* of a caller, and
 * absences are what a source scan is for.
 */
class IncognitoSessionTest {

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

    private fun service() = codeOnly(source("com/rimboard/keyboard/RimBoardService.kt"))

    @Test
    fun `something clears the session flag, and it is the service starting`() {
        val src = service()
        val create = src.indexOf("override fun onCreate()")
        assertTrue("RimBoardService has no onCreate any more", create >= 0)
        val clear = src.indexOf("Prefs.setIncognitoSession(this, false)")
        assertTrue(
            "nothing sets incognito_session back to false. It is a plain " +
                "boolean in SharedPreferences, so without a caller the " +
                "toolbar's per-session toggle is permanent: one tap and the " +
                "keyboard stops learning across process death and reboots, " +
                "with a strip mark as the only sign.",
            clear >= 0
        )
        // Inside onCreate, before the store is built -- so nothing reads the
        // stale value on the way past.
        val userData = src.indexOf("userData = UserData(this)")
        assertTrue("the store is no longer built in onCreate", userData > create)
        assertTrue(
            "the session flag is cleared somewhere other than the start of " +
                "onCreate, so a reader between the two sees the old value",
            clear in (create + 1) until userData
        )
    }

    @Test
    fun `the toggle is still the only thing that turns it on`() {
        val src = service()
        val writes = Regex("Prefs[.]setIncognitoSession[(]").findAll(src).count()
        assertEquals(
            "there should be exactly two writers: the toolbar toggle, and the " +
                "one line that ends the session. A third would be a second " +
                "opinion about what a session is.",
            2, writes
        )
        assertTrue(
            "the toggle no longer flips the flag",
            src.contains("Prefs.setIncognitoSession(this, !Prefs.incognitoSession(this))")
        )
    }

    @Test
    fun `the setting no longer promises something it does not do`() {
        // The other half of the fix. Either the word "session" was wrong or
        // the behaviour was; the behaviour is now a session, and the summary
        // says what ends it rather than leaving the reader to find out.
        val strings = listOf(File("src/main/res"), File("app/src/main/res"))
            .first { it.isDirectory }
            .resolve("values/strings.xml").readText()
        val summary = Regex(
            "<string name=\"pref_incognito_summary\">(.*?)</string>",
            RegexOption.DOT_MATCHES_ALL
        ).find(strings)?.groupValues?.get(1)
        assertTrue("the incognito summary is gone", summary != null)
        assertTrue(
            "the summary does not say when the per-session toggle ends, which " +
                "is the one thing about it nobody could discover: $summary",
            summary!!.contains("restart", ignoreCase = true)
        )
    }
}
