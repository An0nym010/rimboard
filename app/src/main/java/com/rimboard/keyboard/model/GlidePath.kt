package com.rimboard.keyboard.model

import kotlin.math.hypot
import kotlin.math.min

/**
 * The shape a finger drew, in the units the rest of the engine already thinks in.
 *
 * A swipe used to reach the dictionary as a string: the keys the finger crossed,
 * in order, with repeats dropped. That throws away almost everything. Rounding a
 * corner a third of a key short of `l` does not make the word less obviously
 * "hello" to a human looking at the trail, but it removes `l` from the string
 * entirely, and with it every word that needs an `l`. The path was measured and
 * then discarded, which is the same fault [TouchTrail] was written to fix for
 * tapping — a keystroke arriving as an identity when it was recorded as a
 * position.
 *
 * This keeps the position. Points are in the key-width/row grid [KeyProximity]
 * places every letter on, so "how far is this point from `l`" is the same
 * question, in the same units, that the tap corrector already asks.
 *
 * Both this and the ideal curve of a candidate word are sampled to the same
 * fixed number of points, evenly along their own lengths, which is what lets
 * [costOf] compare them point against corresponding point. Sampling by distance
 * rather than by time is also what makes a swipe read the same on a phone
 * reporting touches at 240 Hz and one reporting them at 60, and the same
 * whether it was drawn quickly or slowly.
 */
class GlidePath private constructor(
    private val px: FloatArray,
    private val py: FloatArray,
    private val slots: HashMap<Char, Int>,
    /**
     * Letters the layout draws only inside a long press, mapped to the key that
     * hosts them. See [KeyProximity.hostOf]: this is the answer for letters that
     * are not accented forms of anything and so cannot be folded onto a base.
     */
    private val hosts: Map<Char, Char>,
    private val keyX: FloatArray,
    private val keyY: FloatArray,
    /** Total distance the finger travelled, in key widths. */
    val travel: Float
) {

    /** Points on the path. Always [SHAPE_POINTS]. */
    val size: Int get() = px.size

    fun x(i: Int): Float = px[i]
    fun y(i: Int): Float = py[i]

    /**
     * The table slot for [ch], or -1 when nothing on the layout can spell it.
     *
     * **An accented letter resolves to the key of its base letter**, and that
     * is the whole of why gliding works outside English. Almost no layout draws
     * its accented forms: they live under a long press, so `á`, `ł`, `ä` and
     * `ά` are on no key at all. A word containing one therefore had no slot,
     * [costOf] returned infinity, and the word could not be swiped — ever, by
     * anyone.
     *
     * Which is most of some languages. Measured as the share of a language's
     * common words that could be given a path at all, before this:
     *
     *     el   6%      cs  38%     sk  53%     fi  62%     pl  63%
     *     sv  66%      hu  73%     ro  74%     hr  78%     es  88%
     *
     * Greek is the extreme and the clearest case: modern orthography puts an
     * accent on nearly every polysyllabic word, and the layout draws none of
     * them, so **94% of ordinary Greek could not be swiped**.
     *
     * Folding is the right answer rather than a lenient one. The finger can
     * only cross keys the layout draws, so tracing "καλά" *is* tracing κ-α-λ-α;
     * the accent is not something a swipe can express. The word keeps its
     * accent — only the shape it is matched against is folded — so what the
     * strip offers is still "καλά". Where a folded pair really is two words
     * ("ποτε"/"πότε"), both reach the strip and frequency orders them, exactly
     * as it does for the tapped path through [Dictionary.accentedFormOf].
     *
     * Memoised per swipe. A miss costs one Unicode normalisation and there are
     * only a handful of distinct accented letters in any one language, so the
     * table fills in the first few candidate words and every later lookup is a
     * hash probe. This runs inside the per-swipe budget the latency benchmark
     * holds, for up to `MAX_GLIDE_SCORED` words.
     */
    fun slotOf(ch: Char): Int {
        slots[ch]?.let { return it }
        return foldedSlots.getOrPut(ch) {
            // Fold first, host second, and the order is the whole of the
            // compatibility argument: every letter that resolved before still
            // resolves the same way, and only letters that resolved to nothing
            // reach the host. Where the two disagree the fold is the better
            // answer anyway -- Ukrainian ї is drawn under х but is read as і
            // with a diaeresis, and a finger goes where the letter looks like
            // it belongs.
            slots[foldChar(ch)] ?: hosts[ch]?.let { slots[it] } ?: -1
        }
    }

    /**
     * Per-swipe memo for letters that are not on the layout.
     *
     * A [GlidePath] belongs to one swipe on one thread — the keyboard builds a
     * fresh one per gesture — so a mutable map here cannot be shared between
     * the two engine threads the way a cache on the dictionary could.
     */
    private val foldedSlots = HashMap<Char, Int>(8)

    /**
     * Whether a word beginning with [ch] could have been started by this swipe,
     * folding as [slotOf] does.
     *
     * The membership test has to fold for the same reason the slot lookup does:
     * "ώρα" begins with a letter no layout draws, so an unfolded test threw it
     * away before its shape was ever considered.
     */
    fun couldStart(ch: Char): Boolean = inSet(startKeys, ch, foldChar(ch))

    /**
     * [couldStart] for a caller that already knows what [ch] folds to.
     *
     * [Diacritics.fold] allocates a string and runs a Unicode normalisation for
     * anything above ASCII, and the dictionary scan asks this of every letter
     * its word list begins with — an alphabet's worth, on the UI thread, every
     * time a finger lifts. The caller folds once at load and hands the answer
     * in, so nothing is normalised or allocated per swipe.
     *
     * Worth about 7 microseconds of a Turkish decode, which is to say the
     * allocation rather than the arithmetic is the reason: an alphabet is small
     * and the scan is short. It is here because a phone pays for garbage in a
     * currency a desktop JVM does not show, not because it was the cost the
     * first-letter fix added — that one is the extra candidates being scored,
     * measured separately and kept deliberately.
     */
    fun couldStart(ch: Char, folded: Char): Boolean = inSet(startKeys, ch, folded)

    /** Whether a word ending in [ch] could have been ended by this swipe. */
    fun couldEnd(ch: Char): Boolean = inSet(endKeys, ch, foldChar(ch))

    private fun foldChar(ch: Char): Char = Diacritics.fold(ch)

    private fun inSet(keys: CharArray, ch: Char, folded: Char): Boolean {
        for (k in keys) if (k == ch) return true
        if (folded != ch) {
            for (k in keys) if (k == folded) return true
        }
        // Only a letter with no key of its own asks where it is hosted, and
        // the test has to be "not drawn anywhere" rather than "not among these
        // keys" -- [keys] is the handful near one end of the swipe, so a letter
        // that is drawn but simply not near would otherwise reach this and be
        // admitted from its popup parent as well.
        //
        // Greek is where that showed: the layout draws ς as a real key *and*
        // lists it under σ, so every word ending in final sigma -- which is
        // most masculine nouns -- became a candidate for swipes that ended
        // nowhere near it, and Greek lost a point of top-1 to the extra
        // company.
        if (slots.containsKey(ch)) return false
        val host = hosts[ch] ?: return false
        for (k in keys) if (k == host) return true
        return false
    }

    /**
     * The letters a word could plausibly begin with: those whose key the swipe
     * started on or beside.
     *
     * The first letter is aimed at from rest, so it is the most accurate point
     * on the whole path — but "most accurate" is not "exact", and pinning the
     * candidate list to the single nearest key is what makes a swipe that began
     * a few pixels into `g` unable to produce any word starting with `h`.
     */
    val startKeys: CharArray by lazy { keysNear(0, START_RADIUS) }

    /** The letters a word could plausibly end with, by the same argument. */
    val endKeys: CharArray by lazy { keysNear(px.size - 1, END_RADIUS) }

    private fun keysNear(i: Int, radius: Float): CharArray {
        val hits = slots.entries
            .map { it.key to hypot(px[i] - keyX[it.value], py[i] - keyY[it.value]) }
            .filter { it.second <= radius }
            .sortedBy { it.second }
        // The nearest key always qualifies, however far off the layout's edge
        // the finger strayed: a swipe that starts in the gutter still started
        // somewhere, and answering "no letter" would drop the whole word.
        if (hits.isEmpty()) {
            val best = slots.entries
                .minByOrNull { hypot(px[i] - keyX[it.value], py[i] - keyY[it.value]) }
                ?: return CharArray(0)
            return charArrayOf(best.key)
        }
        return CharArray(min(hits.size, MAX_ANCHOR_KEYS)) { hits[it].first }
    }

    /**
     * How far, in key widths, the finger strayed on average from the path that
     * spelling [word] would have drawn. Infinite when the word cannot be placed
     * on this layout at all.
     *
     * ## The model
     *
     * Every word names a curve: the polyline through its letters' keys, in
     * order, doubles collapsed because a finger cannot stop twice in the same
     * place. Sample that curve at even spacing along its own length, sample the
     * real path the same way, and compare them point against corresponding
     * point. The answer is a distance, and the word whose curve the finger
     * actually traced has the smallest one.
     *
     * Both curves keep their absolute position on the keyboard. Shape matching
     * normally throws that away — the same gesture drawn anywhere is the same
     * gesture — which is exactly wrong here, where *where* it was drawn is the
     * entire message.
     *
     * Doubled letters collapsing means "hello" and "helo" are the same curve
     * and are separated by frequency alone. That is correct rather than a
     * limitation: the path genuinely does not distinguish them.
     *
     * ## What this replaced, and why it had to
     *
     * The first version cut the path into one run per letter and charged every
     * point its distance from the letter whose run it fell in. That reads as a
     * coverage test, and coverage is not what a swipe means. A straight leg
     * from `s` to `o` passes two key widths from both of them at its midpoint,
     * so the *correct* word was charged for the ordinary business of travelling
     * between its own letters — while a word whose letters happened to lie
     * strewn along the way was charged almost nothing. Swiping "said" it
     * offered "stuff", because `t`, `u` and `f` sit along that route and
     * blanket it. The measured fit of the right word was 1.2 key widths on a
     * *flawless* swipe, and that is the number that gave the model away: a
     * perfect gesture should cost nothing, and under that model nothing could.
     *
     * Comparing curves has no such blind spot. "stuff" describes a different
     * line than "said" even though its letters sit near the same one, and the
     * comparison is against the whole line rather than against the letters.
     */
    fun costOf(word: String): Double {
        val m = collapseInto(word)
        if (m < 1) return Double.POSITIVE_INFINITY
        var total = 0f
        for (k in 0 until m) {
            val slot = slotOf(letters[k])
            if (slot < 0) return Double.POSITIVE_INFINITY
            idealX[k] = keyX[slot]
            idealY[k] = keyY[slot]
            if (k > 0) {
                total += hypot(idealX[k] - idealX[k - 1], idealY[k] - idealY[k - 1])
            }
            cum[k] = total
        }

        val n = px.size
        // A word of one distinct letter draws no line, so every sample of its
        // curve is that one point. Nothing reaches here through the dictionary
        // scan, which requires two letters; a caller asking directly gets an
        // answer rather than a division by zero.
        if (m == 1 || total <= 0f) {
            var sum = 0f
            for (i in 0 until n) sum += hypot(px[i] - idealX[0], py[i] - idealY[0])
            return sum.toDouble() / n
        }

        var sum = 0f
        var seg = 0
        val step = total / (n - 1)
        for (i in 0 until n) {
            val along = i * step
            // Both walks run forward, so the segment index never rewinds and
            // the comparison stays linear in the length of the path.
            while (seg < m - 2 && cum[seg + 1] < along) seg++
            val segLen = cum[seg + 1] - cum[seg]
            val t = if (segLen <= 0f) 0f else ((along - cum[seg]) / segLen).coerceIn(0f, 1f)
            val qx = idealX[seg] + (idealX[seg + 1] - idealX[seg]) * t
            val qy = idealY[seg] + (idealY[seg + 1] - idealY[seg]) * t
            sum += hypot(px[i] - qx, py[i] - qy)
        }
        return sum.toDouble() / n
    }

    /**
     * [word] with runs of the same letter reduced to one, into [letters],
     * returning the length.
     *
     * Longer words are truncated rather than rejected: nothing a finger draws
     * in one stroke has more turns in it than [MAX_LETTERS], and judging such a
     * word by its first stops beats dropping it.
     */
    private fun collapseInto(word: String): Int {
        var m = 0
        for (ch in word) {
            if (m > 0 && letters[m - 1] == ch) continue
            letters[m++] = ch
            if (m == MAX_LETTERS) break
        }
        return m
    }

    // Scratch for [costOf]. A GlidePath belongs to one swipe and is read on the
    // thread that decoded it, so this is safe where an instance field on the
    // shared Dictionary would not have been — and it matters, because the
    // alternative is allocating four arrays per candidate word across a scan of
    // a couple of thousand.
    private val letters = CharArray(MAX_LETTERS)
    private val idealX = FloatArray(MAX_LETTERS)
    private val idealY = FloatArray(MAX_LETTERS)
    private val cum = FloatArray(MAX_LETTERS)

    companion object {

        /**
         * How many points both curves are sampled to before they are compared.
         *
         * Fixed rather than proportional to length, because the comparison is
         * point against corresponding point and the two curves have to be
         * sampled the same way — and because a fixed count makes reading a
         * swipe cost the same for a long word as for a short one. Forty-eight
         * is finer than any hand draws.
         */
        const val SHAPE_POINTS = 48

        /**
         * Distinct consecutive letters a swipe is read for.
         *
         * Not a limit on word length — "committee" collapses to seven stops.
         * It bounds the scratch above, and no gesture a hand makes in one
         * stroke has more turns in it than this.
         */
        const val MAX_LETTERS = 24

        /** Below this a "swipe" is a smudge on one key, not a word. */
        private const val MIN_TRAVEL = 0.5f

        /** The finger aims from rest, so the first point is the sharpest. */
        private const val START_RADIUS = 0.75f

        /** The last point is a deceleration and drifts more than the first. */
        private const val END_RADIUS = 1.0f

        /**
         * How many letters each end of the swipe may be read as.
         *
         * These two sets are the whole of the candidate filter: a word is
         * scored if it starts and ends where the finger did. That sounds far
         * too weak to be affordable, and measures otherwise — about 1,700 words
         * of a 300,000-word English list survive an average swipe, which is a
         * few hundred thousand operations to score properly.
         *
         * It replaced a much cleverer filter that required *every* letter of a
         * word to lie near some point of the path, and the cleverness was the
         * problem: on a hurried swipe that filter threw away the right word
         * more than half the time, because a hurried finger misses keys — which
         * is the entire situation the decoder exists to handle. Recall measured
         * 47% on English and 22% on Turkish; no ranking underneath can recover
         * a word that was never scored. Both ends alone measure 100%.
         */
        private const val MAX_ANCHOR_KEYS = 4

        /**
         * A path from raw touch points, or null if there is not enough of one.
         *
         * [pts] is interleaved x,y **already in grid units** — the caller owns
         * that conversion because only the view knows how many pixels a key is
         * wide.
         */
        fun of(pts: FloatArray, prox: KeyProximity): GlidePath? {
            if (pts.size < 4 || pts.size % 2 != 0) return null
            val n = pts.size / 2

            var total = 0f
            for (i in 1 until n) {
                total += hypot(pts[i * 2] - pts[i * 2 - 2], pts[i * 2 + 1] - pts[i * 2 - 1])
            }
            if (total < MIN_TRAVEL) return null

            val want = SHAPE_POINTS
            val step = total / (want - 1)
            val rx = FloatArray(want)
            val ry = FloatArray(want)
            rx[0] = pts[0]; ry[0] = pts[1]
            rx[want - 1] = pts[(n - 1) * 2]; ry[want - 1] = pts[(n - 1) * 2 + 1]

            var out = 1
            var walked = 0f
            var i = 1
            while (i < n && out < want - 1) {
                val ax = pts[i * 2 - 2]; val ay = pts[i * 2 - 1]
                val bx = pts[i * 2]; val by = pts[i * 2 + 1]
                val seg = hypot(bx - ax, by - ay)
                if (seg > 0f) {
                    // Emit every resample point falling inside this segment
                    // before moving on, so one long segment still yields all
                    // the points it is due.
                    while (out < want - 1 && out * step <= walked + seg) {
                        val t = (out * step - walked) / seg
                        rx[out] = ax + (bx - ax) * t
                        ry[out] = ay + (by - ay) * t
                        out++
                    }
                }
                walked += seg
                i++
            }
            // Floating-point drift can leave the tail unfilled. Carrying the
            // last real point forward is right, because that is where the
            // finger was.
            while (out < want - 1) {
                rx[out] = rx[out - 1]; ry[out] = ry[out - 1]; out++
            }

            val alphabet = prox.letters().toList()
            if (alphabet.isEmpty()) return null
            val slots = HashMap<Char, Int>(alphabet.size * 2)
            val keyX = FloatArray(alphabet.size)
            val keyY = FloatArray(alphabet.size)
            for ((s, ch) in alphabet.withIndex()) {
                // Every letter the grid offers has a position, so these
                // branches are unreachable rather than lenient. Skipping the
                // slot instead would leave a key sitting at the origin, which
                // is a real place on this grid and would read as a match.
                slots[ch] = s
                keyX[s] = prox.gridX(ch) ?: return null
                keyY[s] = prox.gridY(ch) ?: return null
            }
            val hosts = HashMap<Char, Char>()
            for (ch in prox.lettersHosted()) {
                prox.hostOf(ch)?.let { if (slots.containsKey(it)) hosts[ch] = it }
            }
            return GlidePath(rx, ry, slots, hosts, keyX, keyY, total)
        }
    }
}
