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
        val buckets = Array(25) { ArrayList<Int>() }
        for (i in words.indices) {
            if (freqs[i] < 200) continue // very rare words make bad corrections
            val len = words[i].length
            if (len in 1..24) buckets[len].add(i)
        }
        byLen = Array(25) { buckets[it].toIntArray() }
    }

    val size: Int get() = words.size

    fun contains(wordLower: String): Boolean = exact.contains(wordLower)

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

    /** Top [limit] dictionary words starting with [prefixRaw], ranked by frequency. */
    fun byPrefix(prefixRaw: String, limit: Int): List<Pair<String, Int>> {
        val prefix = prefixRaw.lowercase(locale)
        if (prefix.isEmpty() || words.isEmpty()) return emptyList()
        var lo = 0
        var hi = words.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (words[mid] < prefix) lo = mid + 1 else hi = mid
        }
        val out = ArrayList<Pair<String, Int>>()
        var i = lo
        while (i < words.size && words[i].startsWith(prefix) && out.size < 80) {
            out.add(words[i] to freqs[i])
            i++
        }
        out.sortByDescending { it.second }
        return if (out.size > limit) ArrayList(out.subList(0, limit)) else out
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
    fun corrections(typedLower: String, prox: KeyProximity?, limit: Int): List<String> {
        val n = typedLower.length
        if (n < 2 || words.isEmpty()) return emptyList()
        val maxDist = maxEditDistance(n)
        val scored = ArrayList<Pair<String, Double>>()
        for (bl in maxOf(1, n - maxDist)..minOf(24, n + maxDist)) for (i in byLen[bl]) {
            val cand = words[i]
            val d = editDistance(typedLower, cand, maxDist)
            if (d in 1..maxDist) {
                val score = ln((freqs[i] + 1).toDouble()) - 3.5 * spatialCost(typedLower, cand, prox)
                scored.add(cand to score)
            }
        }
        if (scored.isEmpty()) return emptyList()
        scored.sortByDescending { it.second }
        val out = ArrayList<String>(minOf(limit, scored.size))
        for (p in scored) {
            out.add(p.first)
            if (out.size >= limit) break
        }
        return out
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
        val ins = 0.9
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
