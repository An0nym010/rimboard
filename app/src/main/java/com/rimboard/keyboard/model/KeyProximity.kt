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
        val ax = xs[a] ?: return 1.0
        val bx = xs[b] ?: return 1.0
        val d = hypot((ax - bx).toDouble(), (ys[a]!! - ys[b]!!).toDouble())
        return minOf(1.0, 0.35 * d)
    }

    companion object {
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
