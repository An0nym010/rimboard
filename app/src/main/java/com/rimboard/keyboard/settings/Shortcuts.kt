package com.rimboard.keyboard.settings

import android.content.Context
import com.rimboard.keyboard.engine.UserData
import org.json.JSONObject
import java.io.File

/** User-defined text shortcuts, e.g. "brb" -> "be right back". */
object Shortcuts {

    private var map: LinkedHashMap<String, String>? = null

    private fun file(c: Context) = File(UserData.dataDir(c), "shortcuts.json")

    @Synchronized
    private fun load(c: Context) {
        if (map != null) return
        val m = LinkedHashMap<String, String>()
        try {
            val o = JSONObject(file(c).readText())
            for (k in o.keys()) m[k] = o.getString(k)
        } catch (_: Exception) {
        }
        map = m
    }

    /** Loads if needed and returns the map, never null. */
    @Synchronized
    private fun loaded(c: Context): LinkedHashMap<String, String> {
        load(c)
        return map ?: LinkedHashMap<String, String>().also { map = it }
    }

    @Synchronized
    fun all(c: Context): Map<String, String> = LinkedHashMap(loaded(c))

    /**
     * Runs on the typing path, and was the one accessor without the lock the
     * others hold — it read the map while put and remove could be rewriting it,
     * and invalidate could null it between the load and the read.
     */
    @Synchronized
    fun expansionFor(c: Context, typed: String): String? {
        if (typed.isEmpty()) return null
        return loaded(c)[typed.lowercase()]
    }

    @Synchronized
    fun put(c: Context, key: String, phrase: String) {
        val m = loaded(c)
        val k = key.trim().lowercase()
        if (k.isEmpty() || phrase.isEmpty()) return
        m[k] = phrase
        save(c, m)
    }

    @Synchronized
    fun remove(c: Context, key: String) {
        val m = loaded(c)
        m.remove(key)
        save(c, m)
    }

    @Synchronized
    fun invalidate() {
        map = null
    }

    /**
     * Takes the map rather than reading the field: casting the nullable field
     * threw when it was null, and the throw was swallowed here, so a shortcut
     * would be accepted in the UI and silently never written.
     */
    private fun save(c: Context, m: Map<String, String>) {
        try {
            file(c).writeText(JSONObject(m as Map<*, *>).toString())
        } catch (e: Exception) {
            android.util.Log.w("RimBoard", "shortcuts save failed", e)
        }
    }
}
