package com.rimboard.keyboard.model

import com.rimboard.keyboard.settings.Prefs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backup file's shape, which nothing tested until now.
 *
 * Export and restore each needed a Context, so the part that decides what a
 * backup *is* had never been exercised — in the one subsystem where being
 * quietly wrong is worst. A backup is written once and read months later on
 * another phone: a type that does not survive the round trip, or a key written
 * and never read back, fails at the moment there is nothing left to recover
 * from.
 */
class BackupDocTest {

    private fun doc(settings: JSONObject, format: Int = BackupDoc.FORMAT): JSONObject =
        JSONObject().put("app", "RimBoard").put("format", format).put("settings", settings)

    @Test
    fun `every preference type survives the round trip`() {
        // SharedPreferences has four numeric types and JSON has one, which is
        // the whole reason each entry carries a tag. Putting an Int back where
        // a Long belongs throws when the app next reads it — long after the
        // restore said it worked.
        val original = mapOf(
            "flag" to true,
            "count" to 7,
            "when" to 1_700_000_000_000L,
            "size" to 1.5f,
            "name" to "türkçe",
            "langs" to setOf("en", "tr")
        )
        val back = BackupDoc.decodeSettings(doc(BackupDoc.encodeSettings(original)))
        assertEquals(original.keys, back.keys)
        for ((key, value) in original) {
            assertEquals("$key changed type or value", value, back[key])
            assertEquals(
                "$key came back as ${back[key]!!::class.simpleName}",
                value!!::class.simpleName, back[key]!!::class.simpleName
            )
        }
    }

    @Test
    fun `the excluded keys travel in neither direction`() {
        val encoded = BackupDoc.encodeSettings(
            BackupDoc.EXCLUDED.associateWith { true } + mapOf("keep" to true)
        )
        assertEquals("only the ordinary key is written", setOf("keep"), encoded.keys().asSequence().toSet())

        // And again on the way in, so a hand-edited file cannot smuggle one
        // back — network consent in particular is a decision made on *this*
        // install.
        val smuggled = JSONObject()
        for (key in BackupDoc.EXCLUDED) {
            smuggled.put(key, JSONObject().put("t", "b").put("v", true))
        }
        assertTrue(BackupDoc.decodeSettings(doc(smuggled)).isEmpty())
    }

    @Test
    fun `the exclusion list names keys that actually exist`() {
        // The list is spelled out here rather than referencing Prefs, because
        // this object holds no Android. That copy is only safe while something
        // compares them: writing "net_sent" for KEY_NET_SENT — which is
        // "net_sent_count" — would silently stop excluding the request counter,
        // and the only symptom would be one person's number appearing under
        // somebody else's claim about their device.
        assertEquals(
            setOf(
                Prefs.KEY_INCOGNITO_SESSION,
                Prefs.KEY_PENDING_CLEAR,
                Prefs.KEY_PENDING_RELOAD,
                Prefs.KEY_NET_MODE,
                Prefs.KEY_NET_SENT
            ),
            BackupDoc.EXCLUDED
        )
    }

    @Test
    fun `restoring removes a setting the backup does not hold`() {
        // "This replaces your current settings", says the dialog. It did not:
        // whatever the file held was written and everything else was left
        // alone, so a preference changed after the backup was taken outlived
        // the restore.
        val remove = BackupDoc.keysToRemove(
            current = setOf("theme", "height", "net_mode", "pending_reload"),
            incoming = setOf("theme")
        )
        assertEquals(setOf("height"), remove)
    }

    @Test
    fun `a malformed entry is dropped rather than defaulted`() {
        // optBoolean on a missing value answers false, so a corrupt line used
        // to *set* a preference instead of being ignored — turning a feature
        // off during a restore that reported success.
        val settings = JSONObject()
            .put("novalue", JSONObject().put("t", "b"))
            .put("notype", JSONObject().put("v", true))
            .put("unknowntype", JSONObject().put("t", "q").put("v", 1))
            .put("notanobject", "just a string")
            .put("good", JSONObject().put("t", "b").put("v", true))
        assertEquals(mapOf<String, Any>("good" to true), BackupDoc.decodeSettings(settings.let { doc(it) }))
    }

    @Test
    fun `a document has to say what it is and how new it is`() {
        assertNull(BackupDoc.refuse(doc(JSONObject())))
        assertNotNull("another app's JSON", BackupDoc.refuse(JSONObject().put("app", "Other")))
        assertNotNull("no format at all", BackupDoc.refuse(JSONObject().put("app", "RimBoard")))
        assertNotNull(
            "written by a newer build",
            BackupDoc.refuse(doc(JSONObject(), format = BackupDoc.FORMAT + 1))
        )
    }

    @Test
    fun `a settingless document restores nothing rather than throwing`() {
        assertTrue(BackupDoc.decodeSettings(JSONObject()).isEmpty())
        assertTrue(BackupDoc.decodeSettings(JSONObject().put("settings", "oops")).isEmpty())
    }
}
