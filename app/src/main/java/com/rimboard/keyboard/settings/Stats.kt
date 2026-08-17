package com.rimboard.keyboard.settings

import android.content.Context
import com.rimboard.keyboard.engine.UserData
import org.json.JSONObject
import java.io.File

/** Local-only typing statistics. Nothing ever leaves the device. */
object Stats {

    var words = 0L; private set
    var keys = 0L; private set
    var backspaces = 0L; private set
    var autocorrects = 0L; private set
    var activeMs = 0L; private set
    var since = 0L; private set

    private var loaded = false
    private var lastKeyAt = 0L
    private var dirty = false

    private fun file(c: Context) = File(UserData.dataDir(c), "stats.json")

    @Synchronized
    fun load(c: Context) {
        if (loaded) return
        loaded = true
        since = System.currentTimeMillis()
        try {
            val o = JSONObject(file(c).readText())
            words = o.optLong("words")
            keys = o.optLong("keys")
            backspaces = o.optLong("backspaces")
            autocorrects = o.optLong("autocorrects")
            activeMs = o.optLong("activeMs")
            since = o.optLong("since", since)
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun key(c: Context) {
        load(c)
        keys++
        // A monotonic clock, because this is a *gap between two keystrokes* and
        // wall-clock time can move backwards — an automatic correction, or the
        // user changing the clock. A negative gap is under the 3s threshold, so
        // it was added: the total time spent typing could go down, and did so
        // silently, since nothing about the number says it is impossible.
        // `since` below stays wall-clock, because a date is what it is.
        val now = android.os.SystemClock.elapsedRealtime()
        val gap = now - lastKeyAt
        if (lastKeyAt != 0L && gap in 0 until 3000) activeMs += gap
        lastKeyAt = now
        dirty = true
    }

    @Synchronized
    fun backspace(c: Context) {
        load(c)
        backspaces++
        dirty = true
    }

    @Synchronized
    fun word(c: Context) {
        load(c)
        words++
        dirty = true
    }

    @Synchronized
    fun autocorrect(c: Context) {
        load(c)
        autocorrects++
        dirty = true
    }

    @Synchronized
    fun flush(c: Context) {
        if (!dirty) return
        dirty = false
        try {
            file(c).writeText(
                JSONObject()
                    .put("words", words).put("keys", keys)
                    .put("backspaces", backspaces).put("autocorrects", autocorrects)
                    .put("activeMs", activeMs).put("since", since)
                    .toString()
            )
        } catch (e: Exception) {
            // dirty was cleared before the attempt, so without this a single
            // failed write would mean these counts are never saved again.
            dirty = true
            android.util.Log.w("RimBoard", "stats flush failed", e)
        }
    }

    @Synchronized
    fun reset(c: Context) {
        words = 0; keys = 0; backspaces = 0; autocorrects = 0; activeMs = 0
        since = System.currentTimeMillis()
        dirty = true
        flush(c)
    }
}
