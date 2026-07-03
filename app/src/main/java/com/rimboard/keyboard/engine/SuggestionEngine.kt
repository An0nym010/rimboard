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
    fun correctionFor(typed: String, lang: String, locale: Locale): String? {
        if (typed.length < 3) return null
        if (typed.any { it.isDigit() }) return null
        if (typed.drop(1).any { it.isUpperCase() }) return null
        val dict = dictionary(lang, locale)
        val lower = typed.lowercase(locale)
        if (dict.contains(lower) || userData.isKnown(lower)) return null
        val corr = dict.bestCorrection(lower) ?: return null
        return matchCase(typed, corr, locale)
    }

    fun suggestionsFor(
        composing: String,
        lang: String,
        locale: Locale,
        allowAutocorrect: Boolean,
        personalized: Boolean
    ): SuggestionsResult {
        if (composing.isEmpty()) return SuggestionsResult(emptyList(), -1, -1)
        val dict = dictionary(lang, locale)
        val lower = composing.lowercase(locale)

        val merged = LinkedHashMap<String, Long>() // lowercase word -> score
        if (personalized) {
            for ((w, c) in userData.userMatches(lower, 8)) {
                merged[w] = 1_000_000_000L + c * 1000L
            }
        }
        for ((w, f) in dict.byPrefix(lower, 12)) {
            val existing = merged[w]
            if (existing == null || existing < f.toLong()) merged[w] = f.toLong()
        }
        val ranked = merged.entries.sortedByDescending { it.value }
            .map { it.key }
            .toMutableList()

        val correction = correctionFor(composing, lang, locale)
        if (correction != null) {
            val cl = correction.lowercase(locale)
            ranked.remove(cl)
            ranked.add(0, cl)
        }

        val display = mutableListOf(composing) // slot 0: verbatim
        for (w in ranked) {
            val cased = matchCase(composing, w, locale)
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
