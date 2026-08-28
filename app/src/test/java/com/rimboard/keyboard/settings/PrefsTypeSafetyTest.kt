package com.rimboard.keyboard.settings

import com.rimboard.keyboard.model.BackupDoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.io.File

/**
 * A preferences file with the wrong type under a key must not take the
 * keyboard down.
 *
 * `getBoolean` on a key holding a String does not fall back to the default --
 * it throws `ClassCastException`, out of a getter nobody wraps, on a path that
 * runs on every focus change. Because the value is on disk the throw repeats
 * for every field the user touches and survives a restart, so the keyboard is
 * gone until the app's data is cleared.
 *
 * Nothing the app writes can produce that. A restored backup can: the document
 * carries its own type tag per entry, `decodeSettings` believes it, and
 * `Backup` writes whatever it says. The first test below is that vector,
 * demonstrated rather than described; the rest are the ratchet on the fix.
 */
class PrefsTypeSafetyTest {

    @Test
    fun `a backup document chooses the type, not the app`() {
        // The whole vector in six lines. "suggestions" is read with
        // getBoolean everywhere in the app; a document can hand back a String
        // for it and nothing between here and the disk disagrees.
        val doc = JSONObject(
            """{"settings":{"suggestions":{"t":"s","v":"yes"}}}"""
        )
        val out = BackupDoc.decodeSettings(doc)
        assertEquals("a String is what a String tag produces", "yes", out["suggestions"])
        assertTrue(
            "the decoder types by the document's tag, so import cannot be the " +
                "place this is caught without a schema it does not have",
            out["suggestions"] is String
        )
    }

    /** Unit tests run from the module directory; tolerate the project root too. */
    private fun prefsSrc(): String =
        listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
            .resolve("com/rimboard/keyboard/settings/Prefs.kt").readText()

    @Test
    fun `every preference read goes through a guarded helper`() {
        // Enumerated rather than sampled: the next setting somebody adds is
        // written by copying the line above it, and a raw getter copied in
        // would be silently outside the guard.
        val src = prefsSrc()
        val body = src.substringBefore("private fun SharedPreferences.boolOr(")
        for (raw in listOf(".getBoolean(", ".getInt(", ".getString(", ".getStringSet(")) {
            assertEquals(
                "Prefs still reads a preference with a raw $raw, which throws " +
                    "rather than defaulting when the stored type is wrong",
                0, body.split(raw).size - 1
            )
        }
    }

    @Test
    fun `each helper catches the cast and answers with the default`() {
        val src = prefsSrc()
        for (name in listOf("boolOr", "intOr", "stringOr", "stringSetOr")) {
            val fn = src.substring(src.indexOf("private fun SharedPreferences.$name("))
                // A blank line, not the first closing brace: the body has a
                // "} catch" in the middle of it and cutting there hid the catch.
                .substringBefore("\n\n")
            assertTrue("$name does not catch the cast: $fn", fn.contains("ClassCastException"))
            val caught = fn.substringAfter("ClassCastException) {")
                .substringBefore("}").trim()
            assertEquals(
                "$name catches the cast and then does something other than " +
                    "returning the default: $fn",
                "def", caught
            )
        }
    }

    @Test
    fun `the helpers are the only thing below them`() {
        // They are appended at the end of the object on purpose: anything
        // added after them would sit outside the scan above, which stops at
        // the first helper.
        val src = prefsSrc()
        val tail = src.substring(src.indexOf("private fun SharedPreferences.boolOr("))
        assertEquals(
            "something was added after the guarded helpers, so the enumeration " +
                "above no longer covers the whole file",
            4, tail.split("private fun SharedPreferences.").size - 1
        )
        assertTrue(
            "a preference read was added below the helpers",
            !tail.substringAfter("stringSetOr").contains("get(c)")
        )
    }
}
