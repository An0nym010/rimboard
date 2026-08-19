package com.rimboard.keyboard.engine

import com.rimboard.keyboard.model.KeyProximity
import java.io.InputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln

/**
 * A static word list read from [dictStream] (in the app, assets/dictionaries/<lang>.txt)
 * merged with the user's learned words from [userDictStream]. Either may be null,
 * which simply yields fewer words rather than an error.
 *
 * File format: one "word frequency" pair per line, ordered by frequency.
 * Internally sorted alphabetically for binary-search prefix lookups.
 *
 * Taking streams rather than a Context is what makes the engine unit testable:
 * see `DictionaryTest`. Both streams are consumed and closed here.
 */
class Dictionary(
    dictStream: InputStream?,
    userDictStream: InputStream?,
    private val locale: Locale
) {

    companion object {
        /** Marker for the word-initial position in the character model. */
        const val WORD_START = ' '
        private const val LN_UNSEEN = -6.0

        /** Longest edit distance a typo may be corrected across: 1, or 2 for
         *  words of 6+ characters. One rule, shared so the personal-vocabulary
         *  scan in UserData cannot drift from the dictionary scan here. */
        fun maxEditDistance(n: Int): Int = if (n >= 6) 2 else 1

        /**
         * Whether [cand] differs from [typed] by having a *different* first
         * letter, rather than by one being missing or spare at the front.
         *
         * The distinction earns its keep. The first key of a word is aimed at
         * from rest rather than in the middle of a run, and it is the letter
         * read back first, so swapping it is both the least likely slip and
         * the most jarring correction {EM} "cello" for "hello". A letter
         * dropped from the front ("ello") or struck by accident before the
         * word ("ghello") is an ordinary slip and should be fixed without
         * hesitation.
         *
         * This used to be approximated by "the lengths are equal", which is
         * true of a substitution and also of nothing else being wrong. A word
         * with a first-letter substitution *and* an unrelated slip elsewhere
         * has unequal lengths, and escaped the penalty entirely: Turkish
         * "naberr" scored "haber" over "naber", because "haber" is fourteen
         * times commoner and the one thing that should have counted against it
         * {EM} that it starts with a different letter {EM} was not counted at
         * all.
         */
        internal fun firstLetterSubstituted(typed: String, cand: String): Boolean {
            if (typed.isEmpty() || cand.isEmpty()) return false
            if (cand[0] == typed[0]) return false
            // Missing from the front: "ello" -> "hello".
            if (cand.length == typed.length + 1 &&
                cand.regionMatches(1, typed, 0, typed.length)
            ) return false
            // Spare on the front: "ghello" -> "hello".
            if (typed.length == cand.length + 1 &&
                typed.regionMatches(1, cand, 0, cand.length)
            ) return false
            return true
        }

        /**
         * How many words, at most, are eligible to be corrected *toward*.
         *
         * "Very rare words make bad corrections" is true, but rarity is
         * relative to the corpus. A flat frequency cutoff kept about 55k of
         * English's 200k words and only a few thousand of a smaller language's,
         * because the same raw count means something completely different when
         * one corpus is twice the size of another — Slovak's 50,000th word has
         * a frequency of 41 where English's still has 140. Capping by rank
         * instead gives every language a correction vocabulary of the same
         * size, so spell-check is not quietly worse for the languages with
         * smaller corpora.
         */
        private const val CORRECTION_TARGET_CAP = 60000

        /** Absolute noise floor beneath the rank cap: drops hapax and one-off junk. */
        private const val CORRECTION_MIN_FREQ = 2

        /** Below this there is not enough typed yet for a near-miss to mean anything. */
        private const val FUZZY_MIN_PREFIX = 3

        /** How far back a fuzzy prefix search looks for the slip. */
        private const val FUZZY_EDIT_WINDOW = 4

        /** A near-miss completion always ranks under an exact one. */
        private const val FUZZY_PENALTY = 0.30

        /**
         * What a substituted first letter costs, on the `ln(frequency)` scale
         * the correction score is built on. At 1.2 a word that replaces the
         * first letter has to be about three times commoner to win against one
         * that keeps it — enough to settle the ordinary case without ever
         * making the fix unreachable.
         */
        /**
         * What a swapped first letter costs a candidate.
         *
         * Measured, once the benchmark grew a slip that damages the first
         * letter — until then this constant and the corpus were talking
         * past each other and it could not be tuned at all. Swept with
         * everything else held still, against the accuracy figures and against
         * the reported "naberr" case:
         *
         *   0.0  naberr fails   en first-letter 98%
         *   0.6  naberr fails   en first-letter 95%
         *   1.2  naberr ok      en first-letter 93%   <- lowest that holds
         *   1.8  naberr ok      en first-letter 93%
         *   2.4  naberr ok      en first-letter 93%, tr falls to 93%
         *
         * So the trade is real and now has a number on it: five points of
         * first-letter accuracy in English buys not turning "naberr" into
         * "haber". 1.2 is the cheapest value that pays for it, and the band
         * either side is flat, which is the comfortable place to sit.
         */
        private const val FIRST_LETTER_PENALTY = 1.2

        /** Neither half of a split may be rarer than this. */
        private const val SPLIT_MIN_FREQ = 500

        /**
         * And a one-letter half must be far commoner still. Only a handful of
         * single letters are real words in any language ("a" and "I" in
         * English, "y" and "o" in Spanish); every other letter appears in a
         * corpus as an initial or a list marker, at counts that would let any
         * word be split anywhere.
         */
        private const val SPLIT_SINGLE_MIN_FREQ = 20_000

        /**
         * How many times rarer than its own halves an attested word must be
         * before it is treated as a missing space. See [splitInto] for the
         * measured values this sits between.
         */
        private const val SPLIT_DOMINANCE = 150

        /**
         * Strips diacritics to their base letter: é→e, ü→u, ç→c, ł→l, ı→i.
         *
         * People routinely type accented languages with the bare keys — "cafe"
         * for "café", "gunaydin" for "günaydın" — and expect the real word
         * back. This is what lets the dictionary be looked up by the flattened
         * form. Combining marks fall out through Unicode decomposition; the
         * handful of letters that are atomic code points with no decomposition
         * (dotless ı, Polish ł, Scandinavian ø) are mapped explicitly.
         */
        fun foldDiacritics(s: String): String {
            val decomposed = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            val sb = StringBuilder(decomposed.length)
            for (ch in decomposed) {
                when {
                    Character.getType(ch) == Character.NON_SPACING_MARK.toInt() -> {}
                    else -> sb.append(ATOMIC_FOLD[ch] ?: ch)
                }
            }
            return sb.toString()
        }

        private val ATOMIC_FOLD: Map<Char, Char> = mapOf(
            'ı' to 'i', 'ł' to 'l', 'ø' to 'o', 'đ' to 'd', 'ð' to 'd'
        )

        /** Optimal string alignment (Damerau-Levenshtein) distance with early
         *  cutoff: anything beyond [max] comes back as max + 1. Companion
         *  because it reads no dictionary state, and UserData uses it to rank
         *  learned words as correction candidates by the same measure. */
        fun editDistance(a: String, b: String, max: Int): Int {
            val m = a.length
            val n = b.length
            if (abs(m - n) > max) return max + 1
            var prevPrev: IntArray? = null
            var prev = IntArray(n + 1) { it }
            var curr = IntArray(n + 1)
            for (i in 1..m) {
                curr[0] = i
                var rowMin = curr[0]
                for (j in 1..n) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    var v = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
                    if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                        val pp = prevPrev
                        if (pp != null && pp[j - 2] + 1 < v) v = pp[j - 2] + 1
                    }
                    curr[j] = v
                    if (v < rowMin) rowMin = v
                }
                if (rowMin > max) return max + 1
                val recycled = prevPrev ?: IntArray(n + 1)
                prevPrev = prev
                prev = curr
                curr = recycled
            }
            return prev[n]
        }
    }

    private val words: Array<String>
    private val freqs: IntArray
    private val exact = HashSet<String>()
    /** Bare-letter form -> index of the most frequent accented word matching it. */
    private val foldedIndex = HashMap<String, Int>()
    private val byLen: Array<IntArray>
    // The transition model lives in flat primitive arrays. As a nested
    // HashMap<Char, HashMap<Char, Double>> it allocated on the order of a
    // million map nodes while loading a 200k-word list, and boxed both
    // arguments on every lookup — including the per-keystroke ones on the
    // typing path. `charSlot` maps a character code to a dense index and
    // `rows[i]` holds the weight of everything observed after character i.
    private var charBase = 0
    private var charSlot = IntArray(0)
    private var charTotals = DoubleArray(0)
    private var rows = arrayOfNulls<DoubleArray>(0)

    init {
        val entries = ArrayList<Pair<String, Int>>(12000)
        try {
            dictStream?.bufferedReader()?.useLines { lines ->
                lines.forEach { line ->
                    val sp = line.indexOf(' ')
                    if (sp > 0) {
                        val w = line.substring(0, sp)
                        val f = line.substring(sp + 1).trim().toIntOrNull() ?: 0
                        entries.add(w to f)
                    }
                }
            }
        } catch (_: Exception) {
            // Missing dictionary: keyboard still works, just without suggestions.
        } finally {
            // useLines already closes on the happy path; this covers the case
            // where the reader itself could not be opened.
            try { dictStream?.close() } catch (_: Exception) {}
        }
        try {
            userDictStream?.bufferedReader()?.useLines { lines ->
                val seen = HashSet<String>(entries.size * 2)
                for (e in entries) seen.add(e.first)
                lines.forEach { line ->
                    val sp = line.indexOf(' ')
                    if (sp > 0) {
                        val w = line.substring(0, sp)
                        val f = line.substring(sp + 1).trim().toIntOrNull() ?: 0
                        if (w.isNotEmpty() && w.length <= 24 && seen.add(w)) entries.add(w to f)
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try { userDictStream?.close() } catch (_: Exception) {}
        }
        entries.sortBy { it.first }
        words = Array(entries.size) { entries[it].first }
        freqs = IntArray(entries.size) { entries[it].second }
        exact.addAll(words)
        // Character-transition model for adaptive tap targeting: how likely is
        // letter b to follow letter a in this language, weighted by ln(freq) so
        // common words dominate without drowning everything else. ' ' marks
        // the word-initial position.
        // Pass one assigns every character a dense index, so pass two can
        // accumulate into plain arrays without allocating or boxing.
        var lo = WORD_START.code
        var hi = WORD_START.code
        for (w in words) for (ch in w) {
            val c = ch.code
            if (c < lo) lo = c
            if (c > hi) hi = c
        }
        charBase = lo
        charSlot = IntArray(hi - lo + 1) { -1 }
        var dense = 0
        charSlot[WORD_START.code - lo] = dense++
        for (w in words) for (ch in w) {
            val k = ch.code - lo
            if (charSlot[k] < 0) charSlot[k] = dense++
        }
        charTotals = DoubleArray(dense)
        rows = arrayOfNulls(dense)
        for (i in words.indices) {
            val wgt = ln((freqs[i] + 1).toDouble())
            var pi = 0 // WORD_START always takes the first slot
            for (ch in words[i]) {
                val ci = charSlot[ch.code - lo]
                val row = rows[pi] ?: DoubleArray(dense).also { rows[pi] = it }
                row[ci] += wgt
                charTotals[pi] += wgt
                pi = ci
            }
        }
        val floor = correctionFloor()
        val buckets = Array(25) { ArrayList<Int>() }
        for (i in words.indices) {
            if (freqs[i] < floor) continue
            val len = words[i].length
            if (len in 1..24) buckets[len].add(i)
            // Diacritic index: only words that actually carry an accent, keyed
            // by their bare-letter form, keeping the most frequent on a clash
            // ("şık" and a hypothetical "sık" both fold to "sik"). Words with no
            // accent are reached by the ordinary exact lookup and would only
            // bloat this.
            val folded = foldDiacritics(words[i])
            if (folded != words[i]) {
                val prev = foldedIndex[folded]
                if (prev == null || freqs[prev] < freqs[i]) foldedIndex[folded] = i
            }
        }
        byLen = Array(25) { buckets[it].toIntArray() }
    }

    /**
     * The frequency a word must reach to be a correction target: the frequency
     * of the [CORRECTION_TARGET_CAP]-th most common word, or the noise floor
     * for a dictionary smaller than the cap (where every real word qualifies).
     */
    private fun correctionFloor(): Int {
        if (freqs.size <= CORRECTION_TARGET_CAP) return CORRECTION_MIN_FREQ
        // Partial information is all that is needed — the cap-th largest value —
        // but a copy-and-sort is a few milliseconds once on the warm thread and
        // not worth a selection algorithm.
        val sorted = freqs.copyOf()
        sorted.sort()
        val cutoff = sorted[sorted.size - CORRECTION_TARGET_CAP]
        return maxOf(CORRECTION_MIN_FREQ, cutoff)
    }

    val size: Int get() = words.size

    fun contains(wordLower: String): Boolean = exact.contains(wordLower)

    /**
     * The accented dictionary word a bare-letter query spells, or null.
     *
     * Only fires when the query is not itself a word: "cam" is valid Turkish
     * and stays "cam", but "gunaydin" is not a word and spells "günaydın". Null
     * when the query already contains the accents (it would just fold to
     * itself) so this never second-guesses a correctly-accented word.
     */
    fun accentedFormOf(bareLower: String): String? {
        if (exact.contains(bareLower)) return null
        if (foldDiacritics(bareLower) != bareLower) return null // already accented
        val i = foldedIndex[bareLower] ?: return null
        return words[i]
    }

    /**
     * Smoothed log P(next | prev) from the character-transition model. [prev]
     * is [WORD_START] at the beginning of a word. Used to arbitrate ambiguous
     * taps near key boundaries (Gboard-style adaptive touch targeting).
     * Floored at [LN_UNSEEN] so no single transition can pull a tap across
     * more than a small fraction of a key.
     */
    fun charLogP(prev: Char, next: Char): Double {
        val pi = slot(prev)
        if (pi < 0) return LN_UNSEEN
        // No row means the character was never followed by anything, which is
        // the same "unseen" case the map lookup used to report as absent.
        val row = rows[pi] ?: return LN_UNSEEN
        val ni = slot(next)
        val c = if (ni < 0) 0.0 else row[ni]
        return maxOf(LN_UNSEEN, ln((c + 0.5) / (charTotals[pi] + 40.0)))
    }

    /** Dense index of [ch], or -1 if it never appeared in this dictionary. */
    private fun slot(ch: Char): Int {
        val i = ch.code - charBase
        return if (i >= 0 && i < charSlot.size) charSlot[i] else -1
    }

    /**
     * Top [limit] dictionary words starting with [prefixRaw], ranked by frequency.
     *
     * Every match is considered, not a slice of them. This used to collect the
     * first 80 matches and rank *those* — but [words] is ordered alphabetically,
     * which is what the binary search above needs, so the first 80 matches of a
     * short prefix are the alphabetically earliest ones and those are
     * overwhelmingly the rarest. "th" filled its 80 slots with "tha", "thai",
     * "thailand" and the long tail of "thank..." forms and never reached "the";
     * "s" never reached "so". Measured on the shipped 200k lists that was the
     * wrong top completion for 91% of one-letter and 64% of two-letter prefixes,
     * in English and Turkish alike — the commonest prefixes in the language, and
     * the moment the strip is leaned on hardest. Capping a candidate pool is
     * only sound when the pool is ordered by the thing being selected on, and
     * this one never was.
     *
     * Selection is a bounded insertion into a [limit]-sized window rather than a
     * sort of everything matched, so the ~20k words behind a one-letter prefix
     * cost an integer compare each and allocate nothing per candidate. Ties keep
     * the alphabetically earlier word, which is what the stable sort did.
     */
    fun byPrefix(prefixRaw: String, limit: Int): List<Pair<String, Int>> {
        val prefix = prefixRaw.lowercase(locale)
        if (prefix.isEmpty() || words.isEmpty() || limit <= 0) return emptyList()
        var lo = 0
        var hi = words.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (words[mid] < prefix) lo = mid + 1 else hi = mid
        }
        val bestIdx = IntArray(limit)
        val bestFreq = IntArray(limit)
        var n = 0
        var i = lo
        while (i < words.size && words[i].startsWith(prefix)) {
            val f = freqs[i]
            // Only a candidate that beats the weakest one held is worth placing,
            // so the common case past the first [limit] words is one compare.
            if (n < limit || f > bestFreq[n - 1]) {
                var p = if (n < limit) n++ else limit - 1
                while (p > 0 && bestFreq[p - 1] < f) {
                    bestFreq[p] = bestFreq[p - 1]
                    bestIdx[p] = bestIdx[p - 1]
                    p--
                }
                bestFreq[p] = f
                bestIdx[p] = i
            }
            i++
        }
        val out = ArrayList<Pair<String, Int>>(n)
        for (k in 0 until n) out.add(words[bestIdx[k]] to bestFreq[k])
        return out
    }

    /**
     * Completions for a prefix that itself contains a typo.
     *
     * [byPrefix] is exact: one wrong letter and it returns nothing, so the
     * suggestion strip goes blank at exactly the moment it would be most
     * useful. The strip only recovers when the word is finished and the
     * correction path takes over — meaning a typo in the second letter of a
     * long word leaves eight keystrokes with no suggestions at all.
     *
     * This fills that gap by asking the same question of nearby prefixes. The
     * variants are not all strings within edit distance one — that would be the
     * whole alphabet at every position, thousands of binary searches per
     * keystroke. They are the typos a thumb actually makes: a key adjacent to
     * the one intended, two letters swapped, one letter doubled, one letter
     * missed. Around thirty lookups for a six-letter prefix.
     *
     * Results are scored below their exact counterparts by [FUZZY_PENALTY], so
     * a real prefix match always wins and these fill in underneath.
     */
    fun byPrefixFuzzy(
        prefixRaw: String,
        prox: KeyProximity?,
        limit: Int
    ): List<Pair<String, Int>> {
        val prefix = prefixRaw.lowercase(locale)
        if (prefix.length < FUZZY_MIN_PREFIX || words.isEmpty()) return emptyList()
        val out = LinkedHashMap<String, Int>()
        for (variant in prefixVariants(prefix, prox)) {
            for ((w, f) in byPrefix(variant, limit)) {
                val scored = (f * FUZZY_PENALTY).toInt()
                val prev = out[w]
                if (prev == null || prev < scored) out[w] = scored
            }
        }
        // Anything the exact prefix already reaches is not a fuzzy match; the
        // caller merges both lists and the exact score must be the one used.
        return out.entries
            .filter { !it.key.startsWith(prefix) }
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key to it.value }
    }

    /**
     * Near-misses of [prefix]: one adjacent-key slip, one transposition, one
     * doubled letter, or one dropped letter.
     *
     * Substitutions are limited to the last [FUZZY_EDIT_WINDOW] characters. A
     * typo in the first letter of a word is both rare and expensive to chase —
     * it changes which part of the sorted array is searched entirely — while
     * the recent characters are where a mistake has not yet been noticed.
     */
    private fun prefixVariants(prefix: String, prox: KeyProximity?): List<String> {
        val out = ArrayList<String>(48)
        val n = prefix.length
        val from = maxOf(1, n - FUZZY_EDIT_WINDOW)
        if (prox != null) {
            for (i in from until n) {
                for (nb in prox.neighbours(prefix[i])) {
                    out.add(prefix.substring(0, i) + nb + prefix.substring(i + 1))
                }
            }
        }
        for (i in from until n - 1) {
            // Transposition: "teh" for "the".
            if (prefix[i] == prefix[i + 1]) continue
            val sb = StringBuilder(prefix)
            sb[i] = prefix[i + 1]
            sb[i + 1] = prefix[i]
            out.add(sb.toString())
        }
        for (i in from until n) {
            // A letter typed twice, and a letter missed: both leave the prefix
            // the wrong length, which an exact search can never recover from.
            out.add(prefix.substring(0, i) + prefix.substring(i + 1))
        }
        return out
    }

    /**
     * The word pair a run-together typing splits into, or null.
     *
     * "alot", "infact", "thankyou" and their equivalents in every language are
     * missing spaces rather than misspellings, and no amount of edit distance
     * finds them: "a lot" is four edits from "alot" once the space counts, and
     * the space is not a key the proximity model knows about.
     *
     * Both halves must be real words in their own right. The word as typed may
     * also be one, because "alot", "infact" and "thankyou" are all *in* the
     * shipped English list — a web corpus records the mistake alongside the
     * word — so refusing to split anything attested would refuse exactly the
     * cases this exists for.
     *
     * What separates them is how much rarer the run-together form is than its
     * own halves. Measured on the shipped list, the ratio to the rarer half is
     * ~495 for "alot", ~496 for "thankyou" and ~363 for "infact", against ~37
     * for "cannot", ~49 for "awhile", ~1.6 for "everyone" and below 1 for
     * "alright" and "himself". [SPLIT_DOMINANCE] sits in that gap. A ratio
     * rather than a frequency cut-off, so it means the same thing in a corpus
     * of a different size — the same reason the correction floor is by rank.
     *
     * Single-letter halves are allowed, since "a lot" is the example everyone
     * reaches for first, but held to a much higher frequency bar: corpora are
     * full of stray single letters at low counts, and "u", "s" and "t" as
     * "words" would turn every unrecognised typing into a split.
     */
    fun splitInto(typedLower: String): Pair<String, String>? {
        if (typedLower.length < 3) return null
        val typedFreq = freqOf(typedLower)
        var best: Pair<String, String>? = null
        var bestScore = 0.0
        for (i in 1 until typedLower.length) {
            val a = typedLower.substring(0, i)
            val b = typedLower.substring(i)
            if (!exact.contains(a) || !exact.contains(b)) continue
            val fa = freqOf(a)
            val fb = freqOf(b)
            if (fa < floorFor(a) || fb < floorFor(b)) continue
            // An attested word is only a missing space if it is overwhelmingly
            // rarer than the two words it would become.
            // Long: the product overflows Int above a typed frequency of about
            // 14.3 million, and an overflowed product goes negative, which
            // makes the comparison false and *skips the guard* — inverting it
            // for the commonest words in the corpus. Unreachable today (the
            // most frequent word of four or more letters in any shipped
            // dictionary is "that" at 10.2M) but only by 40%, and an imported
            // dictionary carries no bound at all.
            if (typedFreq > 0 && minOf(fa, fb) < typedFreq.toLong() * SPLIT_DOMINANCE) continue
            // Both halves count, so a split into two common words beats one
            // into a common word and a rare one.
            val score = ln((fa + 1).toDouble()) + ln((fb + 1).toDouble())
            if (score > bestScore) {
                bestScore = score
                best = a to b
            }
        }
        return best
    }

    /** A one-letter half has to be a genuinely common word, not corpus dust. */
    private fun floorFor(half: String): Int =
        if (half.length == 1) SPLIT_SINGLE_MIN_FREQ else SPLIT_MIN_FREQ

    /** Corpus frequency of [wordLower], or 0. Used to choose between two
     *  spellings that are both in the list — see [SuggestionEngine.elongationBase]. */
    internal fun frequency(wordLower: String): Int = freqOf(wordLower)

    private fun freqOf(word: String): Int {
        var lo = 0
        var hi = words.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (words[mid] < word) lo = mid + 1 else hi = mid
        }
        return if (lo < words.size && words[lo] == word) freqs[lo] else 0
    }

    /**
     * Ranked corrections for a lowercase typed word, best first (may be empty).
     *
     * Candidates are gated to integer edit distance 1 (2 for words of 6+ chars),
     * then scored noisy-channel style: `ln(freq) - 3.5 * spatialCost`, where the
     * spatial cost weights each substitution by how far apart the two keys sit on
     * the layout. So an adjacent-key slip (helko -> hello) beats a distant one,
     * and a much more frequent word can still outrank a slightly closer rare one.
     */
    fun corrections(typedLower: String, prox: KeyProximity?, limit: Int): List<String> =
        correctionsScored(typedLower, prox, limit).map { it.first }

    /**
     * As [corrections], but returns each candidate with its noisy-channel score
     * so a caller can blend in evidence the dictionary does not have — the
     * preceding word, most usefully. "the stroe" is edit-distance-1 from both
     * "store" and "stone"; only context can say the sentence wanted the shop.
     * Scores are comparable within one call, not across calls.
     */
    fun correctionsScored(
        typedLower: String, prox: KeyProximity?, limit: Int
    ): List<Pair<String, Double>> {
        val n = typedLower.length
        if (n < 2 || words.isEmpty()) return emptyList()
        val maxDist = maxEditDistance(n)
        val scored = ArrayList<Pair<String, Double>>()
        val first = typedLower[0]
        for (bl in maxOf(1, n - maxDist)..minOf(24, n + maxDist)) for (i in byLen[bl]) {
            val cand = words[i]
            val d = editDistance(typedLower, cand, maxDist)
            if (d in 1..maxDist) {
                var score = ln((freqs[i] + 1).toDouble()) -
                    3.5 * spatialCost(typedLower, cand, prox)
                // A word whose first letter was swapped for another is a
                // different kind of guess, and worth less than its edit
                // distance suggests. See [firstLetterSubstituted] for which
                // differences count as that and which are ordinary slips.
                if (firstLetterSubstituted(typedLower, cand)) score -= FIRST_LETTER_PENALTY
                scored.add(cand to score)
            }
        }
        if (scored.isEmpty()) return emptyList()
        scored.sortByDescending { it.second }
        return if (scored.size > limit) ArrayList(scored.subList(0, limit)) else scored
    }

    /**
     * Keyboard-weighted edit cost between the typed word and a candidate: the
     * minimum-cost alignment where a substitution costs [KeyProximity.cost] of
     * the two keys (0 same, ~0.35 adjacent, up to 1.0 far), an insertion or
     * deletion costs 0.9, and a transposition costs 0.35. Lower means a more
     * plausible typo. With no proximity data it degrades to plain edit distance.
     */
    private fun spatialCost(a: String, b: String, prox: KeyProximity?): Double {
        val m = a.length
        val n = b.length
        // Missing a key, or striking one too many. Measured rather than
        // chosen: AutocorrectAccuracyTest was swept across this value with
        // everything else held still, and the four slip kinds in two languages
        // said 0.7 and said it clearly.
        //
        //   0.9  en dropped 92%  tr dropped 84%
        //   0.8  en dropped 92%  tr dropped 88%
        //   0.7  en dropped 96%  tr dropped 88%   <- nothing else moves
        //   0.6  same, and tr transposed falls to 98%
        //   0.5  same, and four of the eight figures fall
        //
        // It began at 0.9, which said a typist is two and a half times likelier
        // to hit the wrong key than to miss one — and dropped letters were
        // the worst category in both languages because of it. Below 0.7 the
        // insertion gets cheap enough to start explaining words it should not,
        // and transpositions and neighbour slips pay for it. The cliff is
        // immediately under the answer, which is worth knowing before anyone
        // rounds this down.
        val ins = 0.7
        val transp = 0.35
        var prevPrev: DoubleArray? = null
        var prev = DoubleArray(n + 1) { it * ins }
        var curr = DoubleArray(n + 1)
        for (i in 1..m) {
            curr[0] = i * ins
            for (j in 1..n) {
                val subCost = prox?.cost(a[i - 1], b[j - 1])
                    ?: if (a[i - 1] == b[j - 1]) 0.0 else 1.0
                var v = minOf(prev[j] + ins, curr[j - 1] + ins, prev[j - 1] + subCost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    val pp = prevPrev
                    if (pp != null && pp[j - 2] + transp < v) v = pp[j - 2] + transp
                }
                curr[j] = v
            }
            val recycled = prevPrev ?: DoubleArray(n + 1)
            prevPrev = prev
            prev = curr
            curr = recycled
        }
        return prev[n]
    }

    /**
     * Candidate words for a glide path: same first letter, last letter matching
     * the path's last (or second-to-last, to forgive overshoot), and the word's
     * letters (doubles collapsed) forming a subsequence of the swiped keys.
     * Scored by frequency, sharply discounted when the word length doesn't fit
     * the path length. A relaxed second pass runs if the strict one is empty.
     */
    fun glideCandidates(seqLower: String, limit: Int): List<Pair<String, Double>> {
        if (seqLower.length < 2 || words.isEmpty()) return emptyList()
        val strict = glidePass(seqLower, limit, 4.5)
        return if (strict.isNotEmpty()) strict else glidePass(seqLower, limit, 6.0)
    }

    private fun glidePass(seq: String, limit: Int, floorDiv: Double): List<Pair<String, Double>> {
        val firstStr = seq.first().toString()
        val firstCh = seq.first()
        val last = seq.last()
        val nearLast = if (seq.length >= 3) seq[seq.length - 2] else last
        val floor = maxOf(2, ceil(seq.length / floorDiv).toInt())
        val ideal = seq.length / 2.6
        var lo = 0
        var hi = words.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (words[mid] < firstStr) lo = mid + 1 else hi = mid
        }
        val out = ArrayList<Pair<String, Double>>()
        var i = lo
        while (i < words.size && words[i][0] == firstCh) {
            val w = words[i]
            val wl = w[w.length - 1]
            if (w.length >= 2 && (wl == last || wl == nearLast)) {
                val c = collapse(w)
                if (c.length in floor..seq.length && isSubsequence(c, seq)) {
                    out.add(w to freqs[i] * Math.pow(0.2, abs(c.length - ideal)))
                }
            }
            i++
        }
        out.sortByDescending { it.second }
        return if (out.size > limit) ArrayList(out.subList(0, limit)) else out
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

}
