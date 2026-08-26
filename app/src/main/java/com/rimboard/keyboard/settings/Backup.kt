package com.rimboard.keyboard.settings

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import com.rimboard.keyboard.engine.UserData
import com.rimboard.keyboard.model.BackupDoc
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

    private val EXCLUDED = BackupDoc.EXCLUDED

    fun export(context: Context, uri: Uri): Boolean {
        return try {
            val root = JSONObject()
            root.put("app", "RimBoard")
            // The constant, not a literal that happens to equal it today: the
            // reader below rejects anything above FORMAT, so a bump would
            // otherwise ship exports still labelled with the old number.
            root.put("format", FORMAT)
            root.put("exportedAt", System.currentTimeMillis())

            root.put("settings", BackupDoc.encodeSettings(Prefs.get(context).all))
            // From the declared list rather than a copy of it. The copy is
            // how blocked.txt came to be described as backed up in one place
            // and absent from the other two.
            for ((key, name) in BackupDoc.FILES) {
                root.put(key, readFileOrEmpty(File(UserData.dataDir(context), name)))
            }

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
            BackupDoc.refuse(root)?.let {
                android.util.Log.w(TAG, "backup refused: $it")
                return false
            }

            // User data is written first. Settings are applied only once it has
            // all landed, so a failed restore leaves the existing settings
            // alone instead of replacing them and then reporting failure.
            var ok = true
            for ((key, name) in BackupDoc.FILES) {
                ok = writeIfPresent(root, key, File(UserData.dataDir(context), name)) && ok
            }
            if (!ok) return false

            // Shortcuts are cached in a process-wide map that outlives this
            // call, and the keyboard service shares it. Without this the file
            // is restored but the stale map keeps answering, so imported text
            // shortcuts simply do not work until the process is killed.
            Shortcuts.invalidate()

            val incoming = BackupDoc.decodeSettings(root)
            val prefs = Prefs.get(context)
            val editor = prefs.edit()
            // "This replaces your current settings", says the dialog before
            // this runs, and until 2026-08-22 it did not: what the file held
            // was written and everything else was left alone, so a preference
            // changed after the backup was taken outlived the restore.
            // Somebody restoring a backup is asking to be back where they
            // were, and a setting that quietly persists is the one they will
            // not think to look for.
            for (key in BackupDoc.keysToRemove(prefs.all.keys, incoming.keys)) {
                editor.remove(key)
            }
            for ((key, value) in incoming) {
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is String -> editor.putString(key, value)
                    is Set<*> -> editor.putStringSet(key, value.map { it.toString() }.toSet())
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
