package com.rimboard.keyboard.engine

import com.rimboard.keyboard.model.GlidePath
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
        /**
         * How hard the shape of a swipe argues against how common a word is,
         * in log-frequency units per key width of average miss.
         *
         * The same trade [correctionsScored] makes for tapping, where the
         * constant is 3.5 per unit of edit cost.
         *
         * **Swept in `GlideAccuracyTest`, top-1 over the four hands:**
         *
         *     w=      3    4    5    6    7    9   12
         *     en dlb  92   97   98   99  100  100  100
         *     en nat  75   84   88   88   88   89   89
         *     en slp  63   67   66   70   69   66   63
         *     en hur  66   73   74   75   73   72   68
         *     tr dlb  95   98   99  100  100  100  100
         *     tr nat  88   91   93   92   93   93   91
         *     tr slp  82   79   79   78   77   73   65
         *     tr hur  82   83   83   82   78   73   68
         *     mean    80   84   85   86   85   83   81
         *
         * Six is the middle of a plateau running from four to seven, and it is
         * both the best mean and the best *worst* arm — which is the figure
         * worth optimising, since a decoder that only works for a careful
         * swipe is what this replaced. The two ends of the sweep say what the
         * constant does: too low and a common word wins on frequency whatever
         * the finger drew, too high and a rare word wins on a hair of fit.
         *
         * The plateau is broad, so this is not a number to agonise over. What
         * it is not is arbitrary: the fits it weighs run from about 0.10 key
         * widths for a careful swipe to about 0.8 for a hurried one, so six
         * makes the whole usable range of shape worth roughly four log-units
         * of frequency — enough to overturn a fifty-fold difference in how
         * common two words are, and not enough to overturn a thousand-fold one.
         */
        const val GLIDE_SHAPE_WEIGHT = 6.0

        /**
         * How many surviving words get a shape computed.
         *
         * The bit-mask filter in front of this is a recall filter, so on a
         * short swipe across a crowded row it can pass thousands. This is the
         * budget that keeps a swipe's cost flat instead of scaling with how
         * unlucky its geometry was; the words outside it are the rarest ones.
         */
        private const val MAX_GLIDE_SCORED = 1500

        /** Marker for the word-initial position in the character model. */
        const val WORD_START = ' '
        private const val LN_UNSEEN = -6.0

        /**
         * Longest edit distance a typo may be corrected across: 1, or 2 for
         * words of 6+ characters. One rule, shared so the personal-vocabulary
         * scan in UserData cannot drift from the dictionary scan here.
         *
         * **Swept 2026-08-20 and kept** (fixes en/tr, then what it overwrites
         * that was already correct):
         *
         *     n>=6 ? 2 : 1   97/96   15/16   <- here
         *     n>=8 ? 2 : 1   97/96   16/19
         *     always 1       97/96   16/18
         *     n>=5 ? 2 : 1   95/96    8/13
         *     always 2       94/96    6/13
         *
         * Tightening is strictly worse: both narrower rules repair exactly as
         * much and destroy more, because the candidate that wins is then a
         * near one the confidence gate waves through rather than a distant one
         * it stops.
         *
         * Loosening does cut destruction, and the reason is worth knowing
         * before anyone reads it as an improvement. A wider budget lets a
         * distance-2 candidate outrank the distance-1 one, and that candidate
         * then fails [autoCommitConfident] — so the keyboard destroys less by
         * *doing nothing* more often, which is also why the repair rate falls
         * with it. That is a blunt version of what the threshold already does
         * precisely. Tune the threshold, not this.
         */
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
        /**
         * **Swept 2026-08-20 and kept**, and the sweep is a cautionary tale.
         * Fixes en/tr, then what gets overwritten that was already correct:
         *
         *     15000   97/96    8/ 6
         *     30000   97/96   11/12
         *     60000   97/96   15/16   <- here
         *     120000  97/95   19/21
         *     250000  97/95   24/32
         *
         * Read alone that table says tighten to 15,000: half the destruction,
         * no cost to repair at all. It is wrong, and the reason is that the
         * corpus behind those columns draws its targets from the top of the
         * frequency list, so a limit on *which words may be corrected to* is
         * invisible to it — every word it asks about is inside any cap worth
         * considering.
         *
         * Measured by rank instead, 15,000 takes words around rank 30,000 from
         * 90% offered and 68% repaired to zero and zero. A whole band of
         * ordinary vocabulary stops being correctable and stops being shown.
         * `AutocorrectAccuracyTest` now has an arm that measures by rank and
         * fails on exactly that, so the trap is closed rather than remembered.
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

        /**
         * How much keyboard distance a repair may carry, per character of the
         * word, and still be applied without a tap.
         *
         * The gate this belongs to is the one thing AOSP LatinIME has here
         * that this engine did not. There, no correction is committed unless
         * a *normalized* score clears `config_default_auto_correction_threshold`
         * — normalized meaning divided by the length of the word, so the same
         * absolute slip counts for more in a short word than a long one. Here
         * there was no threshold at all: whatever [correctionsScored] ranked
         * first was committed on the space bar, however far it sat from what
         * was typed.
         *
         * What that cost, measured before the gate existed: **56% of English
         * and 61% of Turkish correctly-typed unknown words were destroyed** —
         * real words of the other language standing in for the names, brands
         * and jargon no 200k-word list contains. "olacak" became "black",
         * "buraya" became "bury", "asking" became "sakin", "worked" became
         * "world". Every one of those is two edits on a six-letter word, which
         * an absolute cost lets a much commoner candidate buy its way past and
         * a per-character cost does not.
         *
         * 0.14 is the edge of a free lunch, and that is the whole reason it
         * is the number. Swept against both arms of `AutocorrectAccuracyTest`
         * (en/tr, what it still fixes against what it still destroys):
         *
         *     none   97/96   59/63     as shipped before this existed
         *     0.20   97/96   38/42
         *     0.17   97/96   20/21
         *     0.15   97/96   18/18
         *     0.14   97/96   15/16     <- here
         *     0.13   94/93    9/10     repair starts being paid for
         *     0.10   79/82    7/ 5     well past the knee
         *
         * Everything from "none" down to 0.14 is bought with nothing: the
         * repair rate does not move at all while silent destruction falls
         * four-fold. 0.13 is the first value that costs a real fix, and below
         * it the trade gets steadily worse. Tightening further is defensible
         * — 0.13 buys six more points of destruction for three of repair, and
         * the two failures are not equally bad, since a fix declined is
         * visible on the strip and a word silently overwritten is found later
         * by whoever reads the message — but that is a judgement, and 0.14
         * needs none.
         *
         * See [autoCommitConfident] for what the number does.
         */
        private const val AUTO_MAX_COST_PER_CHAR = 0.14

        /**
         * The same bar for someone who would rather it left their words alone.
         *
         * Offered as a setting because it is the one point on the curve that is
         * a genuine judgement rather than a measurement. Everything from no
         * gate at all down to 0.14 was free — repair never moved while
         * destruction fell four-fold — and below 0.14 it stops being free:
         *
         *     0.14   fixes 97/96   destroys 15/16   (balanced, the default)
         *     0.12   fixes 94/93   destroys  9/10   (cautious)
         *     0.11   fixes 79/82   destroys  7/ 6   (too steep, not offered)
         *
         * Three points of repair for six of protection is a real trade and
         * people will not all want the same side of it: a refused fix is
         * visible on the strip one tap away, a silently overwritten word is
         * found later by whoever reads the message. 0.11 is not offered
         * because the repair rate falls off a cliff there and nobody choosing
         * "cautious" is asking for a keyboard that has stopped working.
         *
         * **There is deliberately no setting in the other direction.** Above
         * 0.14 the repair rate is flat all the way to 0.30 while destruction
         * climbs to 38-42%, and the by-rank arm says the same for uncommon
         * words as for common ones. A "more eager" option would be a strictly
         * worse keyboard behind a friendly label.
         */
        private const val AUTO_MAX_COST_PER_CHAR_CAUTIOUS = 0.12

        /**
         * How common a stem must be before a suffix may be peeled onto it.
         *
         * The morphology guard used to accept any stem the dictionary held at
         * all, and a 200k-word list built from subtitles holds a great deal
         * that is not a Turkish root: fragments, foreign scraps, and other
         * people's typos, all sitting at the bottom of the frequency table.
         * That is how "srlam" came apart onto "sr", "bsyan" onto "bs" and
         * "heken" onto "hek" — stems with 37 to 68 occurrences in a corpus of
         * millions — and each of those typos was then pronounced correct.
         *
         * The gap between noise and root is wide enough to sit in comfortably:
         * the junk above ran 37-98, while the genuine stems in the same sample
         * ran 1,261 for "ola", 14,056 for "sor" and three and a half million
         * for "bu". 500 is the same number [SPLIT_MIN_FREQ] uses for the halves
         * of a split, and for the same reason — it is asking whether this is a
         * word people actually write, not merely a string that occurs.
         */
        internal const val STEM_MIN_FREQ = 500

        /**
         * How much commoner an accented word must be before a bare spelling
         * of it that is *also* in the corpus stops counting as a word.
         *
         * Measured rather than picked, over every bare/accented pair in all 22
         * shipped dictionaries. The band below thirty holds genuine distinct
         * words — Turkish "cop" and "çöp" at 28x, "cami" and "camı" at 3x,
         * "ucu" and "üçü" at 1x — and above fifty there is nothing but
         * accents somebody did not type: "nasilsa", "kalir", "uzaklas",
         * Spanish "expresion", French "poete", Polish "reki". A sample of the
         * 50-110x band in Turkish, the language this affects most, contained
         * not one word that stands on its own.
         *
         * The cases most at risk of being caught wrongly are the ones where
         * both spellings are real and common, and they are safe for the reason
         * that makes them risky: "si"/"sí", "ou"/"où", "schon"/"schön" all have
         * the *bare* form as the commoner, so this never fires on them at all.
         */
        private const val BARE_KEY_RATIO = 50

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

        /**
         * Optimal string alignment (Damerau-Levenshtein) distance with early
         * cutoff: anything beyond [max] comes back as max + 1.
         *
         * Companion because it reads no dictionary state, and UserData uses it
         * to rank learned words as correction candidates by the same measure.
         *
         * Allocates three small arrays per call. That is fine for the handful
         * of calls UserData makes and ruinous for the scan in
         * [correctionsScored], which asks this of every word in the dictionary
         * within two characters of what was typed -- so that scan passes its
         * own [EditScratch] and pays for the arrays once per keystroke instead
         * of a hundred thousand times.
         */
        fun editDistance(a: String, b: String, max: Int): Int =
            editDistance(a, b, max, EditScratch())

        /**
         * Reusable row buffers for [editDistance].
         *
         * One per correction scan, never shared between threads: the keyboard
         * and the system spell checker hold the same Dictionary from two
         * threads, so this is deliberately something a caller owns rather than
         * a field on the dictionary.
         */
        class EditScratch {
            var a = IntArray(0)
            var b = IntArray(0)
            var c = IntArray(0)

            fun ensure(n: Int) {
                if (a.size <= n) {
                    val cap = n + 1
                    a = IntArray(cap); b = IntArray(cap); c = IntArray(cap)
                }
            }
        }

        /**
         * The same distance, computing only the cells that can matter.
         *
         * Two things make this the version worth having on the hot path.
         *
         * **The band.** An alignment that strays more than [max] columns from
         * the diagonal has already spent more than [max] edits getting there,
         * so cells outside `|i - j| <= max` cannot contribute to an answer
         * within budget. Computing them anyway is what the previous version
         * did: for a seven-letter word it filled forty-nine cells to decide a
         * question that five per row can answer. Out-of-band cells are left at
         * `max + 1` so the minimums above them stay correct without a special
         * case.
         *
         * **The early exit, which now fires.** The row minimum was checked
         * before too, but over the whole row -- and a full row nearly always
         * contains a cheap cell somewhere off the diagonal, so the check
         * almost never tripped until the last rows. Over the band it is a real
         * bound: once every in-band cell of a row exceeds the budget, no
         * completion of that alignment can come back under it.
         *
         * Verified against a plain unbanded Damerau-Levenshtein over random
         * pairs in `DictionaryTest`; this is an optimisation, not a redefinition.
         */
        fun editDistance(a: String, b: String, max: Int, scratch: EditScratch): Int {
            val m = a.length
            val n = b.length
            if (abs(m - n) > max) return max + 1
            val inf = max + 1
            scratch.ensure(n)
            var prevPrev = scratch.a
            var prev = scratch.b
            var curr = scratch.c
            for (j in 0..n) prev[j] = if (j <= max) j else inf
            for (j in 0..n) prevPrev[j] = inf
            for (i in 1..m) {
                val lo = if (i - max > 1) i - max else 1
                val hi = if (i + max < n) i + max else n
                curr[0] = if (i <= max) i else inf
                // The cell just left of the band is read as curr[j - 1]; it has
                // to be inf rather than whatever the last row left there.
                if (lo - 1 >= 1) curr[lo - 1] = inf
                var rowMin = curr[0]
                for (j in lo..hi) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    var v = prev[j - 1] + cost
                    val del = prev[j] + 1
                    if (del < v) v = del
                    val ins = curr[j - 1] + 1
                    if (ins < v) v = ins
                    if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                        val tr = prevPrev[j - 2] + 1
                        if (tr < v) v = tr
                    }
                    if (v > inf) v = inf
                    curr[j] = v
                    if (v < rowMin) rowMin = v
                }
                if (hi < n) curr[hi + 1] = inf
                if (rowMin > max) return max + 1
                val recycled = prevPrev
                prevPrev = prev
                prev = curr
                curr = recycled
            }
            return prev[n]
        }
    }

    private val words: Array<String>
    private val freqs: IntArray
    /** Bare-letter form -> index of the most frequent accented word matching it. */
    private val foldedIndex = HashMap<String, Int>()
    /**
     * Kept from the build, because [correctionFloor] copies and sorts the whole
     * frequency array and this is asked per correction.
     */
    private var floorFreq = 0

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
        floorFreq = floor
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

    fun contains(wordLower: String): Boolean = indexOf(wordLower) >= 0

    /**
     * Where [word] sits in the sorted word list, or -1.
     *
     * This replaced a `HashSet<String>` holding every word in the language.
     * The set answered membership in one step and cost about half the memory
     * of the whole dictionary to do it -- a hash node and a table slot per
     * word, for a question the array beside it already answers, because the
     * array is sorted and a binary search over three hundred thousand words is
     * eighteen comparisons.
     *
     * Memory is not a nicety for an input method. It is the lowest-priority
     * process on the device that the user can still see, and the way this app
     * fails worst is to be killed and vanish mid-sentence.
     */
    fun indexOf(word: String): Int {
        var lo = 0
        var hi = words.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (words[mid] < word) lo = mid + 1 else hi = mid
        }
        return if (lo < words.size && words[lo] == word) lo else -1
    }

    /**
     * The accented dictionary word a bare-letter query spells, or null.
     *
     * Null when the query already contains accents (it would just fold to
     * itself), so a correctly-accented word is never second-guessed.
     *
     * The interesting case is a query that is *itself* in the dictionary. This
     * used to stop there — being a word was taken as proof of being the word
     * meant — and the instinct is right for "cam", which is valid Turkish and
     * must stay "cam". It is wrong for "gunaydin", and the difference is not
     * presence but plausibility. A corpus built from subtitles contains what
     * people type, and people type Turkish without accents, so the bare
     * spellings are all in there at a rounding error of the real word:
     *
     *     gunaydin     88 : günaydın    41,743        cocuklar 353 : çocuklar 107,130
     *     tesekkurler 530 : teşekkürler 224,510       uzgunum  461 : üzgünüm  182,876
     *
     * Each of those was a word as far as this function was concerned, so accent
     * restoration never fired for any of them — it worked only for bare forms
     * the corpus happened *not* to contain. It was dead for exactly the words
     * it is for.
     *
     * So the query has to hold its own against its accented counterpart, by
     * [BARE_KEY_RATIO]. The pairs that must survive protect themselves, because
     * what the test measures is precisely what makes them safe: "si" and "sí",
     * "ou" and "où", "schon" and "schön" are distinct words and the bare one is
     * the *commoner*, so the ratio never comes near. The ones that do not
     * survive are the ones nobody writes on purpose.
     */
    fun accentedFormOf(bareLower: String): String? {
        // Cheapest question first, and it is the one that answers almost every
        // call. The index holds only accented words, keyed by their bare form,
        // so an ordinary word misses it outright -- and a correctly accented
        // one misses it too, because a key is a folded form and folding is
        // what removes the accents. Everything below allocates: foldDiacritics
        // runs a Unicode normalisation and builds a string, and this sits on
        // the per-keystroke path and on the spell checker's binder thread.
        //
        // It used to be second, behind an `exact.contains` that short-circuited
        // the common case. Asking about frequency instead of presence removed
        // that early exit without replacing it, and left a normalisation on
        // every lookup of every word that is in the dictionary.
        val i = foldedIndex[bareLower] ?: return null
        if (foldDiacritics(bareLower) != bareLower) return null // already accented
        if (contains(bareLower) &&
            freqs[i].toLong() < BARE_KEY_RATIO * maxOf(1, freqOf(bareLower)).toLong()
        ) {
            return null
        }
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
            if (!contains(a) || !contains(b)) continue
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

    /**
     * Whether the corpus has this word and thinks it too rare to offer.
     *
     * Every edit-distance candidate already clears the correction floor: the
     * length buckets are built from words above it and nothing below can ever
     * be scored. Paths that reach a word another way bypassed that entirely,
     * and could lead the list with something the ranking would never have
     * considered. Turkish "hayı" is the case that found it — frequency 65
     * against a floor of 185, which is to say corpus noise, offered ahead of
     * "haydi" for the typo "hayi". "haydi" is two thousand times commoner.
     *
     * Asks the question this way round on purpose. A generated inflection is
     * *absent* from the corpus rather than rare in it, and absence is exactly
     * what an agglutinative language produces: "kitaplarımızdan" is a
     * perfectly good word that no frequency list will ever contain. Rejecting
     * unknown words here would switch that feature off. What is rejected is
     * only a word the corpus saw, counted, and ranked below the bar.
     */
    internal fun tooRareToOffer(wordLower: String): Boolean {
        val f = freqOf(wordLower)
        return f > 0 && f < floorFreq
    }

    private fun freqOf(word: String): Int {
        val i = indexOf(word)
        return if (i < 0) 0 else freqs[i]
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
        typedLower: String, prox: KeyProximity?, limit: Int, touch: FloatArray? = null
    ): List<Pair<String, Double>> {
        val n = typedLower.length
        if (n < 2 || words.isEmpty()) return emptyList()
        val maxDist = maxEditDistance(n)
        val scored = ArrayList<Pair<String, Double>>()
        // One set of row buffers for the whole scan. This walks every word
        // within maxDist characters of what was typed -- on English that is
        // over a hundred thousand words for a seven-letter prefix -- and
        // allocating three arrays inside each of those was most of what a
        // keystroke cost.
        val scratch = EditScratch()
        // A lower bound on the distance, in about ten instructions, so the
        // hundred-thousand-cell question below is only asked of words that
        // could possibly answer it. One edit changes which letters a word
        // contains by at most two -- a substitution can drop one and add
        // another; an insertion or deletion moves one; a transposition moves
        // none -- so two words whose letter sets differ by more than twice the
        // budget cannot be within it.
        //
        // Exact, not heuristic: it can only ever refuse to reject. Letters are
        // hashed into sixty-four bits, and a collision makes two different
        // letters look like the same one, which *understates* the difference
        // and lets a word through to be measured properly. Nothing is lost, a
        // little work is wasted.
        val typedMask = letterMask(typedLower)
        val maxSetDiff = 2 * maxDist
        for (bl in maxOf(1, n - maxDist)..minOf(24, n + maxDist)) for (i in byLen[bl]) {
            val cand = words[i]
            if (java.lang.Long.bitCount(letterMask(cand) xor typedMask) > maxSetDiff) continue
            val d = editDistance(typedLower, cand, maxDist, scratch)
            if (d in 1..maxDist) {
                var score = ln((freqs[i] + 1).toDouble()) -
                    3.5 * spatialCost(typedLower, cand, prox, touch)
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
     * Whether [candidate] is a confident enough repair of [typedLower] to be
     * committed on a space rather than merely offered on the strip.
     *
     * This decides *auto*-correction only. Everything [correctionsScored]
     * ranked is still shown and still one tap away; the question here is the
     * narrower and much more damaging one of what gets applied silently.
     *
     * Two words that are the same word written differently are not a guess and
     * do not face the bar. Accents are the first case — someone typing
     * "gunaydin" for "günaydın" on bare keys has not made a mistake anyone
     * needs protecting from, and the substitutions involved are between keys
     * that sit nowhere near each other, so a spatial cost reads them as a wild
     * repair. Repeated letters are the second: "naberr" for "naber" and
     * "hellooo" for "hello" are the word itself with a key held down.
     *
     * Everything else pays [AUTO_MAX_COST_PER_CHAR] per character. Dividing by
     * the length is the whole point — it is what makes two edits acceptable in
     * a long word and refused in a short one, which is exactly the difference
     * between "accomodation" and turning somebody's name into a different word.
     */
    fun autoCommitConfident(
        typedLower: String, candidate: String, prox: KeyProximity?, cautious: Boolean = false
    ): Boolean {
        if (typedLower.isEmpty() || candidate.isEmpty()) return false
        if (sameWordDifferentlyWritten(typedLower, candidate)) return true
        // Deliberately blind to the touch trail, where [correctionsScored] is
        // not. A marginal tap makes its neighbour a cheaper *reading*, which is
        // evidence about what was meant and belongs in the ranking; letting it
        // also lower this bar would loosen the one safety bound the keyboard
        // has, on the strength of a signal whose downside nothing here can yet
        // measure — the destruction corpus is typed text and carries no taps.
        // The result is that a marginal tap improves what is offered without
        // widening what is committed, which is the conservative half.
        val len = maxOf(typedLower.length, candidate.length)
        val bar =
            if (cautious) AUTO_MAX_COST_PER_CHAR_CAUTIOUS else AUTO_MAX_COST_PER_CHAR
        return spatialCost(typedLower, candidate, prox) / len <= bar
    }

    /**
     * Whether the two differ only in accents, or only in a held-down key.
     *
     * The elongation half demands a run of **three**, and the first draft of
     * this demanded two, which was measurably wrong: it read "sell" as an
     * elongation of "sel" and "tabii" as one of "tabi", and waved both through
     * the bar to be committed silently. A doubled letter is ordinary spelling
     * in most languages and carries real information; three in a row is
     * somebody leaning on a key. "naberr" does not need the exemption and does
     * not get it — one deletion in a six-letter word clears the bar on cost.
     */
    private fun sameWordDifferentlyWritten(a: String, b: String): Boolean {
        val fa = foldDiacritics(a)
        val fb = foldDiacritics(b)
        if (fa == fb) return true
        if (!hasRunOfThree(fa) && !hasRunOfThree(fb)) return false
        return collapseRuns(fa) == collapseRuns(fb)
    }

    private fun hasRunOfThree(s: String): Boolean {
        var run = 1
        for (i in 1 until s.length) {
            run = if (s[i] == s[i - 1]) run + 1 else 1
            if (run >= 3) return true
        }
        return false
    }

    private fun collapseRuns(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) if (sb.isEmpty() || sb.last() != ch) sb.append(ch)
        return sb.toString()
    }

    /**
     * Keyboard-weighted edit cost between the typed word and a candidate: the
     * minimum-cost alignment where a substitution costs [KeyProximity.cost] of
     * the two keys (0 same, ~0.35 adjacent, up to 1.0 far), an insertion or
     * deletion costs 0.9, and a transposition costs 0.35. Lower means a more
     * plausible typo. With no proximity data it degrades to plain edit distance.
     */
    private fun spatialCost(
        a: String, b: String, prox: KeyProximity?, touch: FloatArray? = null
    ): Double {
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
        // Transposition. **Swept 2026-08-20 and kept**: flat from 0.20 to
        // 0.50 on every figure, with the cliff immediately above — at 0.70 the
        // swapped-letter accuracy falls to 95% in English and 96% in Turkish,
        // and at 0.50 Turkish has already started to slip (100 -> 98). 0.35
        // sits in the middle of the flat band rather than on its edge, which is
        // where a constant wants to be. Neither end moved what the keyboard
        // overwrites, so this one trades against nothing.
        val transp = 0.35
        var prevPrev: DoubleArray? = null
        var prev = DoubleArray(n + 1) { it * ins }
        var curr = DoubleArray(n + 1)
        for (i in 1..m) {
            curr[0] = i * ins
            for (j in 1..n) {
                val subCost = subCostAt(a, b, i - 1, j - 1, prox, touch)
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
     * What one substitution costs, using where the finger actually landed when
     * that is known.
     *
     * [touch] holds two floats per typed character — how far the tap sat from
     * its key's centre, in key widths and rows — or NaN where there is no
     * measurement. Only the keyboard can ever supply it: the spell checker is
     * handed finished text by other apps and has no touch to report, so it
     * passes null and gets exactly the behaviour it had before this existed.
     *
     * **An identical letter always costs zero, measurement or not.** Charging
     * a marginal tap for the letter it actually produced would give every
     * correctly-typed word a nonzero spatial cost, which feeds the per-character
     * confidence bar and would quietly make the keyboard less willing to commit
     * the more precisely it was measured. The touch point exists here to make
     * the *alternative* cheap, never to make the literal reading expensive.
     */
    private fun subCostAt(
        a: String, b: String, ai: Int, bj: Int, prox: KeyProximity?, touch: FloatArray?
    ): Double {
        if (a[ai] == b[bj]) return 0.0
        if (prox == null) return 1.0
        if (touch != null && ai * 2 + 1 < touch.size) {
            val dx = touch[ai * 2]
            val dy = touch[ai * 2 + 1]
            if (!dx.isNaN() && !dy.isNaN()) {
                val gx = prox.gridX(a[ai])
                val gy = prox.gridY(a[ai])
                if (gx != null && gy != null) {
                    return prox.costFromPoint(gx + dx, gy + dy, b[bj])
                }
            }
        }
        return prox.cost(a[ai], b[bj])
    }

    /**
     * Candidate words for a swiped path, best fit first.
     *
     * ## The model
     *
     * A word is a claim about where the finger meant to be at each moment. Score
     * it by making that claim explicit: cut the path into as many consecutive
     * runs as the word has distinct letters, in order, and charge every point
     * its distance from the letter whose run it fell in. The cheapest such
     * cutting is the word's cost, and a dynamic program finds it in one pass.
     *
     * That single number answers both halves of the question at once, which is
     * why it replaced the two rules that used to be here.
     *
     *  - **Are the word's letters on the path?** A letter whose key the finger
     *    never approached has no cheap run available anywhere, so every cutting
     *    that includes it is expensive.
     *  - **Is the whole path accounted for?** Every point is charged to some
     *    letter. "hell" cannot quietly ignore the tail of a swipe that carried
     *    on to `o`: those points fall in `l`'s run and are charged the distance
     *    from `l` to where the finger actually was. The rule this replaced --
     *    the word's letters must be a subsequence of the keys crossed -- could
     *    only ask the first question, and answered it with a yes or a no.
     *
     * Doubled letters collapse to one run, because a finger cannot stop twice in
     * the same place. "hello" and "helo" are therefore the same shape and are
     * separated by frequency alone, which is correct: the path genuinely does
     * not distinguish them.
     *
     * ## Why it is affordable
     *
     * The program is O(letters x points) per word, which is far too much to run
     * over a whole dictionary. It does not have to be: [GlidePath.nearMask]
     * turns "could this word have been swiped here at all" into a few bit
     * operations, and only what survives that is scored properly. The scan
     * itself is bounded to words that start and end where the finger did.
     */
    fun glideScored(path: GlidePath, limit: Int): List<Pair<String, Double>> {
        if (words.isEmpty()) return emptyList()

        val endKeys = path.endKeys
        if (endKeys.isEmpty()) return emptyList()

        val survivors = ArrayList<Int>(256)
        for (firstCh in path.startKeys) {
            var i = lowerBound(firstCh)
            while (i < words.size && words[i][0] == firstCh) {
                val w = words[i]
                if (w.length >= 2 && endKeys.contains(w[w.length - 1])) survivors.add(i)
                i++
            }
        }
        if (survivors.isEmpty()) return emptyList()

        // Starting and ending where the finger did leaves about 1,700 words of
        // a 300,000-word list standing, which is scored whole. This cap is for
        // the swipe that is unluckier than that -- a short one between two
        // crowded rows -- and keeps the cost of reading any swipe bounded.
        // Frequency is the only ordering available before a shape has been
        // computed, so it is the commonest words that get looked at; a word
        // rare enough to fall outside this was not going to beat a
        // shape-matched common one anyway.
        if (survivors.size > MAX_GLIDE_SCORED) {
            survivors.sortByDescending { freqs[it] }
            survivors.subList(MAX_GLIDE_SCORED, survivors.size).clear()
        }

        val out = ArrayList<Pair<String, Double>>(survivors.size)
        for (idx in survivors) {
            val fit = path.costOf(words[idx])
            if (fit.isInfinite()) continue
            out.add(words[idx] to ln(freqs[idx] + 1.0) - GLIDE_SHAPE_WEIGHT * fit)
        }
        out.sortByDescending { it.second }
        return if (out.size > limit) ArrayList(out.subList(0, limit)) else out
    }

    /**
     * Which letters [w] contains, as a bit each, hashed into sixty-four.
     *
     * Hashed rather than indexed because the alphabets here are not Latin --
     * Cyrillic, Greek, and the accented halves of Turkish and Czech all have to
     * land somewhere. Collisions are harmless where this is used; see the note
     * at the call site.
     */
    private fun letterMask(w: String): Long {
        var m = 0L
        for (ch in w) m = m or (1L shl (ch.code and 63))
        return m
    }

    /** First index whose word starts with [ch], by binary search. */
    private fun lowerBound(ch: Char): Int {
        val key = ch.toString()
        var lo = 0
        var hi = words.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (words[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

}
