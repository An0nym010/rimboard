package com.rimboard.keyboard.engine

import android.content.Context
import com.rimboard.keyboard.model.KeyProximity
import java.util.Locale

class SuggestionsResult(
    val items: List<String>,
    val autocorrectIndex: Int   // index that would be committed on space, or -1
)

object DictVersion {
    @Volatile
    var v = 0
}

class SuggestionEngine(private val context: Context, private val userData: UserData) {

    private companion object {
        const val TAG = "RimBoard"
    }

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Dictionary>()

    /** Preload dictionaries on a background thread so the first keystroke never stalls. */
    fun warm(lang: String, locale: Locale, altLang: String?, altLocale: Locale?) {
        Thread {
            try {
                val started = android.os.SystemClock.elapsedRealtime()
                dictionary(lang, locale)
                predictionModel(lang)
                if (altLang != null && altLocale != null) dictionary(altLang, altLocale)
                // End to end: how long after opening the keyboard suggestions
                // are actually ready. Nothing here had ever been measured.
                android.util.Log.i(
                    TAG, "warm($lang) ready in " +
                        "${android.os.SystemClock.elapsedRealtime() - started}ms"
                )
            } catch (e: Exception) {
                // This runs on a background thread, so a throw here vanishes
                // and the whole engine just appears to have no data.
                android.util.Log.w(TAG, "warm($lang) failed", e)
            }
        }.start()
    }

    @Synchronized
    fun dictionary(lang: String, locale: Locale): Dictionary {
        val key = lang + "#" + DictVersion.v
        cache[key]?.let { return it }
        val started = android.os.SystemClock.elapsedRealtime()
        // A missing asset yields an empty dictionary — never an exception, and
        // never another language's words standing in for this one.
        val dictStream = try {
            context.assets.open("dictionaries/$lang.txt")
        } catch (e: Exception) {
            // Silent here means no suggestions at all for this language, which
            // is indistinguishable from the engine being broken.
            android.util.Log.w(TAG, "no dictionary asset for $lang", e)
            null
        }
        val userStream = try {
            val f = java.io.File(UserData.dataDir(context), "userdict_" + lang + ".txt")
            if (f.exists()) f.inputStream() else null
        } catch (_: Exception) {
            null
        }
        // Covers parsing, the character-transition model and the length buckets
        // — everything on the warm path. `adb logcat -s RimBoard` to read it.
        return Dictionary(dictStream, userStream, locale).also {
            cache[key] = it
            android.util.Log.i(
                TAG,
                "dictionary $lang: ${it.size} words in " +
                    "${android.os.SystemClock.elapsedRealtime() - started}ms"
            )
        }
    }

    /**
     * The dictionary for [lang] only if it is already loaded, via a lock-free
     * read. Safe to call on the UI thread (per-keystroke tap arbitration): it
     * never blocks behind the warm thread's synchronous asset load, and simply
     * returns null (skip arbitration) until the load has finished.
     */
    fun cachedDictionary(lang: String): Dictionary? = cache[lang + "#" + DictVersion.v]

    var blockOffensive = true
    private val offensiveSets = HashMap<String, Set<String>>()

    /** Synchronized to match [predictionModel]: same pattern, same hazard if a
     *  future caller loads it off the UI thread. */
    @Synchronized
    private fun offensive(lang: String): Set<String> =
        offensiveSets.getOrPut(lang) {
            try {
                context.assets.open("offensive/$lang.txt").bufferedReader().readLines()
                    .map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
            } catch (e: Exception) {
                android.util.Log.w(TAG, "no offensive list for $lang", e)
                emptySet()
            }
        }

    private fun isOffensive(word: String, lang: String): Boolean {
        if (!blockOffensive) return false
        val w = word.lowercase()
        return w in offensive(lang) || (lang != "en" && w in offensive("en"))
    }

    private val emojiMaps = HashMap<String, Map<String, String>>()

    /** Word-to-emoji suggestion, current language first with English fallback. */
    fun emojiFor(wordLower: String, lang: String): String? =
        emojiMap(lang)[wordLower] ?: if (lang != "en") emojiMap("en")[wordLower] else null

    @Synchronized
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

    /**
     * Ranked corrections the keyboard would offer for [typed] (best first, case
     * matched to what was typed). Applies the same guards as autocorrect: skips
     * short words, digits, mid-word capitals, words already known, and words
     * valid in the user's other enabled language, and filters out offensive or
     * blocked results. Ranking is keyboard-proximity aware (see [Dictionary]).
     */
    fun correctionCandidates(
        typed: String,
        lang: String,
        locale: Locale,
        altLang: String? = null,
        altLocale: Locale? = null,
        limit: Int = 1
    ): List<String> {
        if (typed.length < 3) return emptyList()
        if (typed.any { it.isDigit() }) return emptyList()
        if (typed.drop(1).any { it.isUpperCase() }) return emptyList()
        val dict = dictionary(lang, locale)
        val lower = typed.lowercase(locale)
        if (dict.contains(lower) || userData.isKnown(lower)) return emptyList()
        // Bilingual typing: never "correct" a word that is valid in the
        // user's other enabled language (e.g. English words in Turkish mode).
        if (altLang != null && altLocale != null &&
            dictionary(altLang, altLocale).contains(typed.lowercase(altLocale))
        ) return emptyList()
        return dict.corrections(lower, KeyProximity.forLang(lang), limit + 4)
            .asSequence()
            .filter { !isOffensive(it, lang) && !userData.isBlocked(it) }
            .map { matchCase(typed, it, locale) }
            .take(limit)
            .toList()
    }

    /** Correction the keyboard would apply on a separator, or null. */
    fun correctionFor(
        typed: String,
        lang: String,
        locale: Locale,
        altLang: String? = null,
        altLocale: Locale? = null
    ): String? = correctionCandidates(typed, lang, locale, altLang, altLocale, 1).firstOrNull()

    fun suggestionsFor(
        composing: String,
        lang: String,
        locale: Locale,
        allowAutocorrect: Boolean,
        personalized: Boolean,
        altLang: String? = null,
        altLocale: Locale? = null
    ): SuggestionsResult {
        if (composing.isEmpty()) return SuggestionsResult(emptyList(), -1)
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

        // Up to two corrections, best first, promoted to the front of the strip.
        val corrs = correctionCandidates(composing, lang, locale, altLang, altLocale, 2)
        for (c in corrs.asReversed()) {
            val cl = c.lowercase(locale)
            ranked.remove(cl)
            ranked.add(0, cl)
        }
        val correction = corrs.firstOrNull()

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
        var outWords: List<String> = display
        var outAc = acIndex
        if (blockOffensive) {
            val acWord = display.getOrNull(acIndex)
            // Slot 0 is the verbatim word and is never filtered: the point is to
            // stop the keyboard *offering* offensive words, not to censor one
            // the user deliberately typed and can already see in the field.
            outWords = listOf(display.first()) +
                display.drop(1).filter { !isOffensive(it, lang) }
            outAc = if (acWord != null && !isOffensive(acWord, lang))
                outWords.indexOf(acWord) else -1
        }
        return SuggestionsResult(outWords, outAc)
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

    private val predictionModels = HashMap<String, Map<String, List<String>>>()

    /**
     * Bundled starter next-word model for [lang] (assets/predictions/<lang>.txt).
     *
     * Synchronized because warm() loads this on a background thread while
     * predictions() reads it on the UI thread. getOrPut can resize the map, and
     * concurrent HashMap mutation corrupts it rather than failing cleanly — an
     * intermittent fault that would look like predictions randomly misbehaving.
     */
    @Synchronized
    private fun predictionModel(lang: String): Map<String, List<String>> =
        predictionModels.getOrPut(lang) {
            try {
                val m = HashMap<String, List<String>>()
                context.assets.open("predictions/$lang.txt").bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val tab = line.indexOf('\t')
                        if (tab > 0) {
                            val prev = line.substring(0, tab)
                            val nexts = line.substring(tab + 1).trim()
                                .split(' ').filter { it.isNotEmpty() }
                            if (prev.isNotEmpty() && nexts.isNotEmpty()) m[prev] = nexts
                        }
                    }
                }
                m
            } catch (e: Exception) {
                android.util.Log.w(TAG, "no prediction model for $lang", e)
                emptyMap()
            }
        }

    /**
     * Next-word predictions after the two-word context (prevWord2, prevWord).
     * The user's own learned n-grams come first (trigram evidence outranks
     * bigram — see [UserData.predictNext]), then the bundled starter model
     * fills any remaining slots so predictions work from the very first word.
     */
    fun predictions(
        prevWord2: String, prevWord: String, lang: String, locale: Locale, limit: Int
    ): List<String> {
        val key = prevWord.lowercase(locale)
        val key2 = prevWord2.lowercase(locale)
        val out = LinkedHashSet<String>()
        for (w in userData.predictNext(key2, key, limit)) out.add(w)
        if (out.size < limit) {
            for (w in predictionModel(lang)[key].orEmpty()) {
                if (out.size >= limit) break
                if (!userData.isBlocked(w) && !isOffensive(w, lang)) out.add(w)
            }
        }
        return out.toList()
    }

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
