package com.rimboard.keyboard.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * The shape of a RimBoard backup file, with no Android in it.
 *
 * [com.rimboard.keyboard.settings.Backup] is the half that needs a Context: it
 * opens the document the user picked, reads and writes five files under the
 * app's own storage, and hands the preferences to a `SharedPreferences.Editor`.
 * This is the half that decides what a backup *is* — which keys travel, how a
 * value's type survives a round trip through JSON, and what makes a file
 * unacceptable.
 *
 * It is split out because it is the half that can be wrong quietly. A backup
 * is written once and read months later on another phone, so a type that does
 * not survive the round trip, or a key that is written and never read back,
 * fails at the moment there is nothing left to recover from. None of that was
 * testable while it lived inside two functions that each needed a Context.
 */
object BackupDoc {

    /** Bumped when the shape changes in a way an older build cannot read. */
    const val FORMAT = 1

    /**
     * Preferences that never travel between installs.
     *
     * Applied in both directions: excluded on the way out so the file does not
     * carry them, and excluded again on the way in so a hand-edited file
     * cannot smuggle one back.
     */
    val EXCLUDED = setOf(
        "incognito_session",
        "pending_clear",
        "pending_reload",
        // Restoring a file is not the same act as consenting to network
        // access. A backup taken on an online build would otherwise switch a
        // fresh install on without ever showing the choice.
        "net_mode",
        // A count of requests made by *this* install. Copying it forward would
        // put someone else's number under a claim about your device.
        "net_sent_count"
    )

    /**
     * The files a backup carries besides the preferences, as
     * `document key to file name`.
     *
     * Read by `Backup` in both directions. It used to describe the list while
     * `export` and `import` each hand-wrote the same names, so this was
     * documentation of what travelled rather than the thing that decided it --
     * and `blocked.txt` sat outside all three copies for as long as blocking
     * had existed.
     */
    val FILES = listOf(
        "learned" to "learned.txt",
        "bigrams" to "bigrams.txt",
        "trigrams" to "trigrams.txt",
        // Words the user banned by long-pressing a suggestion and saying never
        // show me this again. The most deliberate statement anyone makes about
        // this keyboard's vocabulary, and the one a restore used to drop while
        // faithfully bringing back every word it had learned by accident.
        "blocked" to "blocked.txt",
        "pinned" to "pinned_clips.json",
        "shortcuts" to "shortcuts.json"
    )

    /**
     * Files the keyboard keeps that a backup deliberately leaves behind, and
     * why.
     *
     * A map rather than a set because the reason is the point: the file this
     * pair of lists lost was lost by being in neither, and an exclusion nobody
     * had to justify is indistinguishable from an oversight. `BackupCoverageTest`
     * walks the data directory and requires every file to be in one list or
     * the other.
     */
    val NOT_BACKED_UP = mapOf(
        // A word is pinned by being added by hand in the personal dictionary
        // screen, and the pin only makes it unevictable from a cache the new
        // install does not have yet. Restoring learned.txt brings the word
        // back without its pin, which is the behaviour every install had
        // before pinning existed -- a fair price for not versioning another
        // file into the format. UserData says the same at greater length.
        "pinned.txt" to
            "a pin only affects eviction from a cache the restoring install " +
                "does not share; the word itself travels in learned.txt",
        // What this install typed. The same argument EXCLUDED makes for
        // net_sent_count: copying it forward puts someone else's number under
        // a claim about your device.
        "stats.json" to
            "counts what this install typed, so carrying it forward would " +
                "put another device's totals under your name",
        // The three below are excluded for size. A backup is one JSON
        // document the user saves and mails to themselves; a photo, a font
        // file and a word list of tens of thousands of entries do not belong
        // inside one, and each is either replaceable or absent-tolerant.
        "extdict" to
            "downloaded dictionaries, fetched again from a URL already in the " +
                "APK and too large to carry as text",
        "bg_image.jpg" to
            "a photo the user picked; the keyboard checks for it and falls " +
                "back to the plain theme when it is not there",
        "custom_font.ttf" to
            "a font file the user picked, replaceable by picking it again",
        "userdict_*.txt" to
            "a word list the user imported, held as the file they chose and " +
                "potentially tens of thousands of lines"
    )

    /**
     * Preferences as the file records them: `{"key": {"t": "b", "v": true}}`.
     *
     * The tag is what makes the round trip lossless. JSON has one number type
     * and SharedPreferences has four, so a value written as 3 comes back as an
     * Int unless the file says which of Int, Long or Float it was — and
     * putting an Int where the app later asks for a Long throws at the point
     * of reading, long after the restore reported success.
     */
    fun encodeSettings(all: Map<String, Any?>): JSONObject {
        val out = JSONObject()
        for ((key, value) in all) {
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
                    entry.put("v", JSONArray(value.filterNotNull().map { it.toString() }))
                }
                else -> continue
            }
            out.put(key, entry)
        }
        return out
    }

    /**
     * The preferences a document asks to be applied, typed, with anything
     * malformed dropped.
     *
     * Dropped rather than defaulted: an entry whose value is missing used to
     * be read with `optBoolean`, which answers false, so a corrupt line
     * silently *set* a preference instead of being ignored.
     */
    fun decodeSettings(root: JSONObject): Map<String, Any> {
        val settings = root.optJSONObject("settings") ?: return emptyMap()
        val out = LinkedHashMap<String, Any>()
        for (key in settings.keys()) {
            if (key in EXCLUDED) continue
            val entry = settings.optJSONObject(key) ?: continue
            if (!entry.has("v")) continue
            val value: Any = when (entry.optString("t")) {
                "b" -> entry.optBoolean("v")
                "i" -> entry.optInt("v")
                "l" -> entry.optLong("v")
                "f" -> entry.optDouble("v").toFloat()
                "s" -> entry.optString("v")
                "set" -> {
                    val arr = entry.optJSONArray("v") ?: continue
                    (0 until arr.length()).mapTo(LinkedHashSet()) { arr.optString(it) }
                }
                else -> continue
            }
            out[key] = value
        }
        return out
    }

    /**
     * The keys a restore has to delete.
     *
     * "This replaces your current settings", says the dialog, and for a long
     * time it did not: the restore wrote what the file held and left
     * everything else alone, so a preference changed after the backup was
     * taken survived it. Somebody restoring a backup is asking to be back
     * where they were, and a setting that quietly persists is the one they
     * will not think to look for.
     *
     * [EXCLUDED] survives regardless — those are properties of *this* install
     * rather than of the backup, and clearing the network mode would revoke a
     * consent the user gave here.
     */
    fun keysToRemove(current: Set<String>, incoming: Set<String>): Set<String> =
        current.filterNotTo(LinkedHashSet()) { it in EXCLUDED || it in incoming }

    /** Why this document cannot be restored, or null if it can be. */
    fun refuse(root: JSONObject): String? {
        if (root.optString("app") != "RimBoard") return "not a RimBoard backup"
        val format = root.optInt("format", 0)
        // Refuse a file written by a newer build rather than importing the
        // parts that happen to still parse.
        if (format < 1 || format > FORMAT) return "unsupported backup format: $format"
        return null
    }
}
