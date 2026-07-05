package com.rimboard.keyboard.engine

import android.content.Context
import java.util.Locale

class SuggestionsResult(
    val items: List<String>,
    val typedIndex: Int,        // index of the verbatim typed word, or -1
    val autocorrectIndex: Int   // index that would be committed on space, or -1
)

class SuggestionEngine(private val context: Context, private val userData: UserData) {

    private val cache = HashMap<String, Dictionary>()

    @Synchronized
    fun dictionary(lang: String, locale: Locale): Dictionary =
        cache.getOrPut(lang) { Dictionary(context, lang, locale) }

    /**
     * Correction the keyboard would apply on a separator, or null.
     * Skips short words, words with digits or mid-word capitals, and words
     * already known from the dictionary or the user's own vocabulary.
     */
    /** True if the exact lowercase word exists in the given language's dictionary. */

    private val emojiMaps = HashMap<String, Map<String, String>>()

    /** Word-to-emoji suggestion, current language first with English fallback. */
    fun emojiFor(wordLower: String, lang: String): String? =
        emojiMap(lang)[wordLower] ?: if (lang != "en") emojiMap("en")[wordLower] else null

    private fun emojiMap(lang: String): Map<String, String> =
        emojiMaps.getOrPut(lang) {
            try {
                context.assets.open("emoji/$lang.txt").bufferedReader().readLines()
                    .mapNotNull { line ->
                        val p = line.split('\t')
                        if (p.size == 2) p[0] to p[1] else null
                    }.toMap()
            } catch (_: Exception) {
                emptyMap()
            }
        }

    fun knownIn(wordLower: String, lang: String, locale: Locale): Boolean =
        dictionary(lang, locale).contains(wordLower)

    fun correctionFor(
        typed: String,
        lang: String,
        locale: Locale,
        altLang: String? = null,
        altLocale: Locale? = null
    ): String? {
        if (typed.length < 3) return null
        if (typed.any { it.isDigit() }) return null
        if (typed.drop(1).any { it.isUpperCase() }) return null
        val dict = dictionary(lang, locale)
        val lower = typed.lowercase(locale)
        if (dict.contains(lower) || userData.isKnown(lower)) return null
        // Bilingual typing: never "correct" a word that is valid in the
        // user's other enabled language (e.g. English words in Turkish mode).
        if (altLang != null && altLocale != null &&
            dictionary(altLang, altLocale).contains(typed.lowercase(altLocale))
        ) return null
        val corr = dict.bestCorrection(lower) ?: return null
        if (userData.isBlocked(corr)) return null
        return matchCase(typed, corr, locale)
    }

    fun suggestionsFor(
        composing: String,
        lang: String,
        locale: Locale,
        allowAutocorrect: Boolean,
        personalized: Boolean,
        altLang: String? = null,
        altLocale: Locale? = null
    ): SuggestionsResult {
        if (composing.isEmpty()) return SuggestionsResult(emptyList(), -1, -1)
        val dict = dictionary(lang, locale)
        val lower = composing.lowercase(locale)

        val merged = LinkedHashMap<String, Long>() // lowercase word -> score
        if (personalized) {
            for ((w, c) in userData.userMatches(lower, 8)) {
                // Learned words earn a place: suggest only after 3+ uses.
                if (c < 3 || userData.isBlocked(w)) continue
                merged[w] = 1_000_000_000L + c * 1000L
            }
        }
        for ((w, f) in dict.byPrefix(lower, 12)) {
            if (userData.isBlocked(w)) continue
            val existing = merged[w]
            if (existing == null || existing < f.toLong()) merged[w] = f.toLong()
        }
        val altWords = HashSet<String>()
        if (altLang != null && altLocale != null) {
            // Secondary-language candidates rank slightly below primary ones.
            for ((w, f) in dictionary(altLang, altLocale).byPrefix(lower, 6)) {
                if (userData.isBlocked(w)) continue
                val score = (f * 0.85).toLong()
                val existing = merged[w]
                if (existing == null && !dict.contains(w)) altWords.add(w)
                if (existing == null || existing < score) merged[w] = score
            }
        }
        val ranked = merged.entries.sortedByDescending { it.value }
            .map { it.key }
            .toMutableList()

        val correction = correctionFor(composing, lang, locale, altLang, altLocale)
        if (correction != null) {
            val cl = correction.lowercase(locale)
            ranked.remove(cl)
            ranked.add(0, cl)
        }

        val display = mutableListOf(composing) // slot 0: verbatim
        for (w in ranked) {
            // Case foreign words with their own locale (Turkish dotted I, etc.)
            val caseLocale = if (w in altWords && altLocale != null) altLocale else locale
            val cased = matchCase(composing, w, caseLocale)
            if (cased != composing && !display.contains(cased)) display.add(cased)
            if (display.size >= 3) break
        }

        var acIndex = -1
        if (allowAutocorrect && correction != null) {
            val idx = display.indexOf(correction)
            if (idx >= 0) acIndex = idx
        }
        return SuggestionsResult(display, 0, acIndex)
    }

    /** Ranked word candidates for a glide key sequence (lowercase results). */
    fun glideFor(seq: String, lang: String, locale: Locale, personalized: Boolean): List<String> {
        val s = seq.lowercase(locale)
        if (s.length < 2) return emptyList()
        val merged = LinkedHashMap<String, Double>()
        for ((w, score) in dictionary(lang, locale).glideCandidates(s, 20)) {
            merged[w] = score
        }
        if (personalized) {
            val last = s.last()
            val nearLast = if (s.length >= 3) s[s.length - 2] else last
            val floor = maxOf(2, kotlin.math.ceil(s.length / 4.5).toInt())
            for ((w, c) in userData.glideCandidates(s.first(), last, nearLast, 12)) {
                val cw = collapse(w)
                if (cw.length in floor..s.length && isSubsequence(cw, s)) {
                    merged[w] = maxOf(merged[w] ?: 0.0, 1.5e9 + c * 1000.0)
                }
            }
        }
        return merged.entries.sortedByDescending { it.value }.take(4).map { it.key }
    }

    private fun collapse(w: String): String {
        val sb = StringBuilder(w.length)
        for (ch in w) if (sb.isEmpty() || sb[sb.length - 1] != ch) sb.append(ch)
        return sb.toString()
    }

    private fun isSubsequence(needle: String, hay: String): Boolean {
        var i = 0
        for (ch in hay) if (i < needle.length && needle[i] == ch) i++
        return i == needle.length
    }

    fun predictions(prevWord: String, locale: Locale, limit: Int): List<String> =
        userData.predictNext(prevWord.lowercase(locale), limit)

    private fun matchCase(typed: String, candidate: String, locale: Locale): String {
        return when {
            typed.length > 1 && typed.all { it.isUpperCase() } -> candidate.uppercase(locale)
            typed.isNotEmpty() && typed.first().isUpperCase() ->
                candidate.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(locale) else it.toString()
                }
            else -> candidate
        }
    }
}
