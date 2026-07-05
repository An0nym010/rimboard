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

    @Synchronized
    fun all(c: Context): Map<String, String> {
        load(c)
        return LinkedHashMap(map!!)
    }

    fun expansionFor(c: Context, typed: String): String? {
        if (typed.isEmpty()) return null
        load(c)
        return map!![typed.lowercase()]
    }

    @Synchronized
    fun put(c: Context, key: String, phrase: String) {
        load(c)
        val k = key.trim().lowercase()
        if (k.isEmpty() || phrase.isEmpty()) return
        map!![k] = phrase
        save(c)
    }

    @Synchronized
    fun remove(c: Context, key: String) {
        load(c)
        map!!.remove(key)
        save(c)
    }

    @Synchronized
    fun invalidate() {
        map = null
    }

    private fun save(c: Context) {
        try {
            file(c).writeText(JSONObject(map as Map<*, *>).toString())
        } catch (_: Exception) {
        }
    }
}
