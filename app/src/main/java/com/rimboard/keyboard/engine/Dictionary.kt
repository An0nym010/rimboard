package com.rimboard.keyboard.engine

import android.content.Context
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

/**
 * A static word list loaded from assets/dictionaries/<lang>.txt.
 * File format: one "word frequency" pair per line, ordered by frequency.
 * Internally sorted alphabetically for binary-search prefix lookups.
 */
class Dictionary(context: Context, lang: String, private val locale: Locale) {

    private val words: Array<String>
    private val freqs: IntArray
    private val exact = HashSet<String>()
    private val byLen: Array<IntArray>

    init {
        val entries = ArrayList<Pair<String, Int>>(12000)
        try {
            context.assets.open("dictionaries/$lang.txt").bufferedReader().useLines { lines ->
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
        }
        entries.sortBy { it.first }
        words = Array(entries.size) { entries[it].first }
        freqs = IntArray(entries.size) { entries[it].second }
        exact.addAll(words)
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
     * Best correction for a lowercase typed word, or null.
     * Edit distance 1 (2 for words of 6+ chars), highest-frequency candidate wins.
     */
    fun bestCorrection(typedLower: String): String? {
        val n = typedLower.length
        if (n < 2 || words.isEmpty()) return null
        val maxDist = if (n >= 6) 2 else 1
        var best: String? = null
        var bestFreq = 0
        var bestDist = maxDist + 1
        for (bl in maxOf(1, n - maxDist)..minOf(24, n + maxDist)) for (i in byLen[bl]) {
            val cand = words[i]
            val d = damerau(typedLower, cand, maxDist)
            if (d in 1..maxDist) {
                if (d < bestDist || (d == bestDist && freqs[i] > bestFreq)) {
                    bestDist = d
                    best = cand
                    bestFreq = freqs[i]
                }
            }
        }
        return best
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

    /** Optimal string alignment (Damerau-Levenshtein) distance with early cutoff. */
    private fun damerau(a: String, b: String, max: Int): Int {
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
