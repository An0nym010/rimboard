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
class KeyProximity private constructor(rows: List<String>) {

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
        private fun letterRows(lang: String): List<String> = try {
            Languages.byCode(lang)
                .layout(false, false)
                .rows
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
        } catch (_: Exception) {
            qwerty
        }

        @Synchronized
        fun forLang(lang: String): KeyProximity =
            cache.getOrPut(lang) { KeyProximity(letterRows(lang)) }
    }
}
