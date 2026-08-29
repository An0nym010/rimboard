package com.rimboard.keyboard.model

import kotlin.math.hypot

/**
 * Keyboard key geometry for proximity-aware autocorrect. Each letter of a
 * language's layout is placed on the same staggered three-row grid that
 * [Layouts] draws, so a substitution between physically adjacent keys
 * (a<->s) is scored as a far more likely typo than one between distant keys
 * (a<->p). Only the letter rows matter for proximity, so the side function
 * keys (shift, backspace) are ignored.
 *
 * Rows are read from the language's real layout in [Layouts], so a layout
 * change can never leave tap targeting pointing at the wrong keys.
 */
class KeyProximity private constructor(
    rows: List<String>,
    /**
     * For a letter the layout does not draw, the key that hosts it under a long
     * press. See [hostOf].
     */
    private val hosts: Map<Char, Char>
) {

    private val xs = HashMap<Char, Float>()
    private val ys = HashMap<Char, Float>()

    init {
        // Column centres per row, matching the visual stagger: the middle row
        // is inset ~1 unit and the bottom row ~2 units (it sits between the
        // shift and backspace keys).
        val offsets = floatArrayOf(0.5f, 1.0f, 2.0f)
        rows.forEachIndexed { r, row ->
            val off = offsets.getOrElse(r) { 0.5f }
            row.forEachIndexed { i, ch ->
                xs[ch] = i + off
                ys[ch] = r.toFloat()
            }
        }
    }

    /**
     * Substitution cost in [0, 1]: 0 for the same key, ~0.35 for a horizontal
     * neighbour, growing with distance, and 1.0 for keys far apart or not on
     * this layout (accents, punctuation, cross-script).
     */
    fun cost(a: Char, b: Char): Double {
        if (a == b) return 0.0
        // Every lookup is guarded rather than relying on xs and ys being filled
        // together. This runs per keystroke for tap arbitration, so a null here
        // would crash the keyboard mid-word.
        val ax = xs[a] ?: return 1.0
        val bx = xs[b] ?: return 1.0
        val ay = ys[a] ?: return 1.0
        val by = ys[b] ?: return 1.0
        val d = hypot((ax - bx).toDouble(), (ay - by).toDouble())
        return minOf(1.0, 0.35 * d)
    }

    /**
     * Where [ch] sits on the grid, in key widths and rows, or null.
     *
     * Exposed so a caller holding a real touch point can express it in these
     * same units: the key's own position plus however far the finger was from
     * its centre.
     */
    fun gridX(ch: Char): Float? = xs[ch]
    fun gridY(ch: Char): Float? = ys[ch]

    /**
     * The key [ch] lives on when the layout draws no key of its own for it.
     *
     * Some letters are not accented forms of anything and so cannot be folded
     * onto a base: German ß, Danish and Norwegian æ, French œ, Russian ъ,
     * Ukrainian ґ. Unicode has nothing to decompose, and a decoder that only
     * knew how to strip accents could place none of them -- which meant no word
     * containing one could be swiped at all, 7.8% of the Danish list among them.
     *
     * The layout has known the answer the whole time: every one of those
     * letters is drawn in the long-press popup of a real key, and that key is
     * where a finger would go looking for it. This reads that mapping back out
     * of the layout rather than keeping a second table of it, so the two cannot
     * drift the way a copy would.
     */
    fun hostOf(ch: Char): Char? = hosts[ch]

    /** Every letter this layout draws only inside a long press. */
    fun lettersHosted(): Set<Char> = hosts.keys

    /**
     * Every letter this layout actually draws.
     *
     * The alphabet is a property of the layout, not of the language's script:
     * Turkish draws twenty-nine keys and German thirty. A caller that needs to
     * ask a question of *all* the keys -- which one is nearest a point, which
     * ones a swipe passed close to -- has otherwise no way to enumerate them
     * and would have to guess at `'a'..'z'`, which is wrong on half the
     * layouts this ships.
     */
    fun letters(): Set<Char> = xs.keys

    /**
     * Substitution cost from a point on the grid to key [b].
     *
     * [cost] asks how wrong it is to have hit `a` when `b` was meant, which is
     * the best anyone can do knowing only which key was reported. This asks the
     * same question of the place the finger actually landed, which is strictly
     * more information: a tap on the very edge of `k` is nearly free to read as
     * `l`, and a tap dead in the middle of `k` is not, and the two are the same
     * keystroke as far as [cost] can tell.
     *
     * Deliberately the same formula and the same 0.35 scale as [cost], so a
     * touch exactly at a key's centre gives an identical answer. That makes
     * this a strict generalisation rather than a second model to keep in step
     * — there is no calibration here that can drift away from the one above.
     */
    fun costFromPoint(px: Float, py: Float, b: Char): Double {
        val bx = xs[b] ?: return 1.0
        val by = ys[b] ?: return 1.0
        val d = hypot((px - bx).toDouble(), (py - by).toDouble())
        return minOf(1.0, 0.35 * d)
    }

    /**
     * The keys close enough to [ch] to be plausible slips for it, nearest first.
     *
     * The cost function above answers "how wrong is this substitution" for a
     * pair already in hand. This answers the generative version — "what might
     * they have meant" — which is what lets a half-typed word with a typo in it
     * still be completed, instead of waiting for the whole word to be finished
     * before anything can be offered.
     *
     * Cached: the alphabet is small and fixed per layout, and this is on the
     * per-keystroke path.
     *
     * Synchronized because [forLang] hands the same instance to every caller,
     * and the two entry points into this engine — the keyboard on the UI thread
     * and the system spell checker on a binder thread — live in one process.
     * Only the keyboard reaches this today, but a plain HashMap resized from two
     * threads corrupts rather than failing cleanly, and the cost of the lock on
     * a hit is nothing next to that.
     */
    @Synchronized
    fun neighbours(ch: Char): List<Char> = neighbourCache.getOrPut(ch) {
        val cx = xs[ch] ?: return@getOrPut emptyList()
        val cy = ys[ch] ?: return@getOrPut emptyList()
        xs.keys
            .filter { it != ch }
            .mapNotNull { other ->
                val ox = xs[other] ?: return@mapNotNull null
                val oy = ys[other] ?: return@mapNotNull null
                val d = hypot((cx - ox).toDouble(), (cy - oy).toDouble())
                if (d <= NEIGHBOUR_RADIUS) other to d else null
            }
            .sortedBy { it.second }
            .map { it.first }
    }

    private val neighbourCache = HashMap<Char, List<Char>>()

    companion object {
        /**
         * One key away, on the diagonal too. Widening this multiplies the work
         * on every keystroke and starts proposing keys nobody's thumb could
         * have confused.
         */
        private const val NEIGHBOUR_RADIUS = 1.5

        private val cache = HashMap<String, KeyProximity>()
        private val qwerty = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

        /**
         * The letter rows of [lang]'s real layout, so proximity can never drift
         * out of sync with what is actually drawn. Non-letter keys (digits,
         * comma/period, shift, space) are dropped, which leaves exactly the
         * three letter rows in top-to-bottom order.
         */
        /**
         * The letter rows and the popup hosts of [lang]'s real layout, read in
         * one traversal so they cannot describe different keyboards.
         */
        private fun geometry(lang: String): Pair<List<String>, Map<Char, Char>> = try {
            val layout = Languages.byCode(lang).layout(false, false)
            val rows = layout.rows
                .map { row ->
                    row.keys
                        .filter {
                            it.type == KeyType.CHARACTER && it.label.length == 1 &&
                                it.label[0].isLetter()
                        }
                        .joinToString("") { it.label }
                }
                .filter { it.length >= 4 }
                .take(3)
                .ifEmpty { qwerty }
            val hosts = HashMap<Char, Char>()
            for (row in layout.rows) {
                for (key in row.keys) {
                    if (key.type != KeyType.CHARACTER || key.label.length != 1) continue
                    val host = key.label[0]
                    if (!host.isLetter()) continue
                    for (p in key.popup) {
                        if (p.label.length != 1) continue
                        val ch = p.label[0]
                        // Letters only: a popup also carries digits and
                        // punctuation, and neither is something a swipe spells.
                        if (!ch.isLetter()) continue
                        // First host wins, so the answer is the layout's own
                        // reading order rather than whichever key was visited
                        // last.
                        if (!hosts.containsKey(ch)) hosts[ch] = host
                    }
                }
            }
            rows to hosts
        } catch (_: Exception) {
            qwerty to emptyMap()
        }

        @Synchronized
        fun forLang(lang: String): KeyProximity = cache.getOrPut(lang) {
            val (rows, hosts) = geometry(lang)
            KeyProximity(rows, hosts)
        }

        /**
         * A geometry that is not any shipped layout, for asking what a layout
         * change would cost before making one.
         *
         * Nineteen languages keep letters of their own alphabet behind a long
         * press, and whether to give them keys has been an open question with
         * no way to price it: an extra key in a row makes every key narrower,
         * and narrower keys are mis-tapped more often. The grid this class
         * builds is the instrument, and it was there all along -- it measures
         * in key widths, and `GlideTrail.toGrid` divides real touch offsets by
         * the real key width, so a row of eleven keys is arithmetically the
         * same thing as a hand whose wobble is a tenth larger.
         *
         * Not cached: a hypothetical is asked about once, and caching it under
         * a language code would let it be served to the keyboard.
         */
        internal fun forRows(rows: List<String>, hosts: Map<Char, Char>): KeyProximity =
            KeyProximity(rows, hosts)
    }
}
