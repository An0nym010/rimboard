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
 * The row strings here intentionally mirror the letter rows in [Layouts]; they
 * are duplicated rather than extracted so this stays a tiny pure-data class
 * with no dependency on layout construction. Languages not listed fall back to
 * the standard QWERTY grid.
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
        private val qwertz = listOf("qwertzuiop", "asdfghjkl", "yxcvbnm")

        private val rowsByLang: Map<String, List<String>> = mapOf(
            "tr" to listOf("qwertyuıopğü", "asdfghjklşi", "zxcvbnmöç"),
            "de" to qwertz, "hu" to qwertz, "hr" to qwertz, "sk" to qwertz,
            "fr" to listOf("azertyuiop", "qsdfghjklm", "wxcvbn'"),
            "ru" to listOf(
                "йцукенгшщзх",
                "фывапролджэ",
                "ячсмитьбю"
            ),
            "uk" to listOf(
                "йцукенгшщзх",
                "фівапролджє",
                "ячсмитьбю"
            ),
            "el" to listOf(
                "ςερτυθιοπ",
                "ασδφγηξκλ",
                "ζχψωβνμ"
            )
        )

        @Synchronized
        fun forLang(lang: String): KeyProximity =
            cache.getOrPut(lang) { KeyProximity(rowsByLang[lang] ?: qwerty) }
    }
}
