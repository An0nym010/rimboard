package com.rimboard.keyboard.settings

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import com.rimboard.keyboard.engine.UserData
import java.io.File

/**
 * Export/restore of everything personal: all preferences plus the learned
 * words and bigram prediction data. Files are written and read through
 * Storage Access Framework URIs, so no storage permission is ever needed.
 */
object Backup {

    private val EXCLUDED = setOf(
        Prefs.KEY_INCOGNITO_SESSION,
        Prefs.KEY_PENDING_CLEAR,
        Prefs.KEY_PENDING_RELOAD
    )

    fun export(context: Context, uri: Uri): Boolean {
        return try {
            val root = JSONObject()
            root.put("app", "RimBoard")
            root.put("format", 1)
            root.put("exportedAt", System.currentTimeMillis())

            val settings = JSONObject()
            for ((key, value) in Prefs.get(context).all) {
                if (key in EXCLUDED) continue
                val entry = JSONObject()
                when (value) {
                    is Boolean -> { entry.put("t", "b"); entry.put("v", value) }
                    is Int -> { entry.put("t", "i"); entry.put("v", value) }
                    is Long -> { entry.put("t", "l"); entry.put("v", value) }
                    is Float -> { entry.put("t", "f"); entry.put("v", value.toDouble()) }
                    is String -> { entry.put("t", "s"); entry.put("v", value) }
                    is Set<*> -> {
                        entry.put("t", "set")
                        entry.put("v", JSONArray(value.map { it.toString() }))
                    }
                    else -> continue
                }
                settings.put(key, entry)
            }
            root.put("settings", settings)
            root.put("learned", readFileOrEmpty(File(UserData.dataDir(context), "learned.txt")))
            root.put("bigrams", readFileOrEmpty(File(UserData.dataDir(context), "bigrams.txt")))
            root.put("trigrams", readFileOrEmpty(File(UserData.dataDir(context), "trigrams.txt")))
            root.put("pinned", readFileOrEmpty(File(UserData.dataDir(context), "pinned_clips.json")))
            root.put("shortcuts", readFileOrEmpty(File(UserData.dataDir(context), "shortcuts.json")))

            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(root.toString(2).toByteArray(Charsets.UTF_8))
            } ?: return false
            true
        } catch (_: Exception) {
            false
        }
    }

    fun restore(context: Context, uri: Uri): Boolean {
        return try {
            val text = context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: return false
            val root = JSONObject(text)
            if (root.optString("app") != "RimBoard") return false

            val settings = root.optJSONObject("settings") ?: JSONObject()
            val editor = Prefs.get(context).edit()
            val keys = settings.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key in EXCLUDED) continue
                val entry = settings.optJSONObject(key) ?: continue
                when (entry.optString("t")) {
                    "b" -> editor.putBoolean(key, entry.optBoolean("v"))
                    "i" -> editor.putInt(key, entry.optInt("v"))
                    "l" -> editor.putLong(key, entry.optLong("v"))
                    "f" -> editor.putFloat(key, entry.optDouble("v").toFloat())
                    "s" -> editor.putString(key, entry.optString("v"))
                    "set" -> {
                        val arr = entry.optJSONArray("v") ?: JSONArray()
                        val set = HashSet<String>()
                        for (i in 0 until arr.length()) set.add(arr.optString(i))
                        editor.putStringSet(key, set)
                    }
                }
            }
            editor.apply()

            writeIfPresent(root, "learned", File(UserData.dataDir(context), "learned.txt"))
            writeIfPresent(root, "bigrams", File(UserData.dataDir(context), "bigrams.txt"))
            writeIfPresent(root, "trigrams", File(UserData.dataDir(context), "trigrams.txt"))
            writeIfPresent(root, "pinned", File(UserData.dataDir(context), "pinned_clips.json"))
            writeIfPresent(root, "shortcuts", File(UserData.dataDir(context), "shortcuts.json"))

            // Tell a running keyboard service to reload user data from disk.
            Prefs.setPendingReload(context, true)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun readFileOrEmpty(f: File): String =
        try { if (f.exists()) f.readText() else "" } catch (_: Exception) { "" }

    private fun writeIfPresent(root: JSONObject, key: String, f: File) {
        try {
            if (root.has(key)) f.writeText(root.optString(key))
        } catch (_: Exception) {
        }
    }
}
