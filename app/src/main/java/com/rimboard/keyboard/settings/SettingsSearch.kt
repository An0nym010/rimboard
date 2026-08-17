package com.rimboard.keyboard.settings

import java.text.Normalizer
import java.util.Locale

/**
 * Finding a setting by name across the nine screens it might be on.
 *
 * There are around fifty settings here behind nine sub-screens, which is the
 * point at which "I know it exists, I do not know where you put it" becomes the
 * normal way to look for one. Prompted by Telegram's settings search, whose
 * useful idea is not the matching but what a result *shows*: the setting's own
 * title together with the path to the screen holding it, so the answer to
 * "where is this" comes with the result rather than after tapping it.
 *
 * Matching is here, apart from the Android machinery that reads the titles,
 * because ranking is the part that can be quietly wrong — a search that returns
 * the right rows in the wrong order reads as a search that does not work.
 */
object SettingsSearch {

    /** One searchable setting: what it is called, where it lives, how to get there. */
    data class Entry(
        val key: String,
        val title: String,
        val summary: String,
        /** Localised name of the screen holding it — shown as the path. */
        val screenTitle: String,
        /** The preference-screen XML to open. */
        val screenXml: Int
    )

    /**
     * Folds a string for comparison: lower case and stripped of accents.
     *
     * [Locale.ROOT] deliberately, and not the UI language. This folds a *query*
     * against a *title*, and both are folded the same way — what matters is
     * that the two agree, not that either is correct for a particular language.
     * Using the device locale would fold the Turkish dotted I one way in the
     * title and, for a query typed on a different layout, another.
     *
     * The accent strip is what lets "duzeltme" find "düzeltme" and "themes"
     * find "thèmes", which is most of the value on a phone keyboard.
     */
    fun fold(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")

    /**
     * [entries] matching [query], best first.
     *
     * Ranked so that a title match always beats a summary match, and within
     * those a prefix beats a match in the middle. Someone typing "vib" wants
     * the row called "Vibrate on keypress", not the one whose description
     * happens to mention vibration — and the shorter title wins ties, because a
     * query matching a short name has matched more of it.
     */
    fun search(entries: List<Entry>, query: String, limit: Int = 12): List<Entry> {
        val q = fold(query.trim())
        if (q.isEmpty()) return emptyList()
        return entries
            .mapNotNull { e ->
                val t = fold(e.title)
                val s = fold(e.summary)
                val rank = when {
                    t.startsWith(q) -> 0
                    t.contains(q) -> 1
                    s.startsWith(q) -> 2
                    s.contains(q) -> 3
                    else -> return@mapNotNull null
                }
                e to rank
            }
            .sortedWith(compareBy({ it.second }, { it.first.title.length }, { it.first.title }))
            .take(limit)
            .map { it.first }
    }
}
