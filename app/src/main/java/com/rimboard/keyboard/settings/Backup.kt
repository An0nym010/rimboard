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

    private const val TAG = "RimBoard"

    /** Highest backup layout this build understands. */
    private const val FORMAT = 1

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
        } catch (e: Exception) {
            android.util.Log.w(TAG, "backup export failed", e)
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
            // Refuse a file written by a newer build rather than importing the
            // parts that happen to still parse.
            val format = root.optInt("format", 0)
            if (format < 1 || format > FORMAT) {
                android.util.Log.w(TAG, "unsupported backup format: $format")
                return false
            }

            // User data is written first. Settings are applied only once it has
            // all landed, so a failed restore leaves the existing settings
            // alone instead of replacing them and then reporting failure.
            var ok = true
            ok = writeIfPresent(root, "learned", File(UserData.dataDir(context), "learned.txt")) && ok
            ok = writeIfPresent(root, "bigrams", File(UserData.dataDir(context), "bigrams.txt")) && ok
            ok = writeIfPresent(root, "trigrams", File(UserData.dataDir(context), "trigrams.txt")) && ok
            ok = writeIfPresent(root, "pinned", File(UserData.dataDir(context), "pinned_clips.json")) && ok
            ok = writeIfPresent(root, "shortcuts", File(UserData.dataDir(context), "shortcuts.json")) && ok
            if (!ok) return false

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

            // Tell a running keyboard service to reload user data from disk.
            Prefs.setPendingReload(context, true)
            true
        } catch (e: Exception) {
            android.util.Log.w(TAG, "backup restore failed", e)
            false
        }
    }

    private fun readFileOrEmpty(f: File): String =
        try {
            if (f.exists()) f.readText() else ""
        } catch (e: Exception) {
            android.util.Log.w(TAG, "backup could not read " + f.name, e)
            ""
        }

    /**
     * Returns whether the entry was handled. A swallowed failure here meant a
     * restore that lost the learned data still reported success.
     */
    private fun writeIfPresent(root: JSONObject, key: String, f: File): Boolean = try {
        if (root.has(key)) f.writeText(root.optString(key))
        true
    } catch (e: Exception) {
        android.util.Log.w(TAG, "backup could not write " + f.name, e)
        false
    }
}
