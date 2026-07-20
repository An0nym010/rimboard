package com.rimboard.keyboard.engine

/**
 * The inline calculator behind the "= 408" suggestion chip.
 *
 * Handles + - * / (and the × ÷ glyphs), parentheses, and percentages.
 * Pure logic with no Android dependencies so it can be unit tested directly:
 * see `CalcTest`.
 */
object Calc {

    /** A number, optionally wrapped in parens and/or followed by a percent sign. */
    private const val OPERAND = "\\(*\\d+(?:[.,]\\d+)?%?\\)*"

    /** At least one operator is required, so a bare "2026" is never a sum. */
    private val expression = Regex(OPERAND + "(?:\\s*[+\\-*/×÷]\\s*" + OPERAND + ")+=?$")

    /**
     * The chip to offer for the text immediately before the cursor, e.g.
     * "= 408" for "12*34", or null when there is nothing sensible to show.
     *
     * [before] is the last [window] characters of the field. Things that merely
     * look like arithmetic — dates (12/07/2026) and phone-style digit runs
     * (555-1234) — are only evaluated when the user types an explicit trailing
     * "=". An expression that fills the whole window is rejected too, since the
     * real one may start further back than we can see.
     */
    fun chipFor(before: String, window: Int = 40): String? {
        unitChip(before, window)?.let { return it }
        val m = expression.find(before) ?: return null
        if (m.range.first == 0 && before.length >= window) return null
        val expr = m.value
        if (!expr.endsWith("=")) {
            // "-" alone is weak because phone numbers use it, but a percent sign
            // never appears in one, so it settles "200-10%" as a real sum.
            val strongOp = expr.any {
                it == '+' || it == '*' || it == '×' || it == '÷' || it == '%'
            }
            val slashes = expr.count { it == '/' }
            if (!strongOp && slashes != 1) return null
            if (slashes > 1) return null
        }
        val value = eval(expr.removeSuffix("=")) ?: return null
        return "= " + (format(value) ?: return null)
    }

    /**
     * A quantity with a unit, e.g. "5km". Longer unit names come first in the
     * alternation so "mi" is not read as a bare "m".
     */
    private val unitExpr = Regex(
        "(\\d+(?:[.,]\\d+)?)\\s*(km|mi|cm|in|ft|kg|lb|oz|gal|°?c|°?f|m|g|l)=$",
        RegexOption.IGNORE_CASE
    )

    /**
     * "5km=" -> "= 3.1069 mi". Converting between the metric and imperial
     * counterpart is the only sensible default without asking the user for a
     * target unit, and it is the direction people actually want on a phone.
     *
     * The trailing "=" is required, so ordinary prose ("a 5km run") is left
     * alone and "3pm=" is not mistaken for a quantity.
     */
    private fun unitChip(before: String, window: Int): String? {
        val m = unitExpr.find(before) ?: return null
        if (m.range.first == 0 && before.length >= window) return null
        val value = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val (converted, unit) = convert(value, m.groupValues[2].lowercase()) ?: return null
        return "= " + (format(converted) ?: return null) + " " + unit
    }

    private fun convert(v: Double, unit: String): Pair<Double, String>? = when (unit) {
        "km" -> v * 0.621371 to "mi"
        "mi" -> v * 1.609344 to "km"
        "m" -> v * 3.280840 to "ft"
        "ft" -> v * 0.3048 to "m"
        "cm" -> v * 0.393701 to "in"
        "in" -> v * 2.54 to "cm"
        "kg" -> v * 2.204623 to "lb"
        "lb" -> v * 0.453592 to "kg"
        "g" -> v * 0.035274 to "oz"
        "oz" -> v * 28.349523 to "g"
        "l" -> v * 0.264172 to "gal"
        "gal" -> v * 3.785412 to "l"
        "c", "°c" -> v * 9.0 / 5.0 + 32.0 to "°F"
        "f", "°f" -> (v - 32.0) * 5.0 / 9.0 to "°C"
        else -> null
    }

    /**
     * Evaluates [raw] with normal precedence: parentheses, then * /, then + -.
     * Null if the input is malformed, unbalanced, or divides by zero.
     *
     * The whole string must be consumed, so trailing junk ("1+2)") and two
     * operands with nothing between them ("1 2") are both rejected rather than
     * silently producing a number.
     */
    fun eval(raw: String): Double? {
        val s = raw.replace('×', '*').replace('÷', '/')
        val pos = IntArray(1)
        val v = evalExpr(s, pos) ?: return null
        skipSpaces(s, pos)
        if (pos[0] != s.length) return null
        return if (v.value.isFinite()) v.value else null
    }

    /** An evaluated operand, remembering whether it was written as a percentage. */
    private class Val(val value: Double, val percent: Boolean)

    private fun evalExpr(s: String, pos: IntArray): Val? {
        var acc = evalTerm(s, pos) ?: return null
        while (true) {
            skipSpaces(s, pos)
            val op = s.getOrNull(pos[0]) ?: break
            if (op != '+' && op != '-') break
            pos[0]++
            val rhs = evalTerm(s, pos) ?: return null
            // A pocket calculator reads "150+18%" as 18% *of 150*, not as +0.18.
            val delta = if (rhs.percent) acc.value * rhs.value else rhs.value
            acc = Val(if (op == '+') acc.value + delta else acc.value - delta, false)
        }
        return acc
    }

    private fun evalTerm(s: String, pos: IntArray): Val? {
        var acc = evalFactor(s, pos) ?: return null
        while (true) {
            skipSpaces(s, pos)
            val op = s.getOrNull(pos[0]) ?: break
            if (op != '*' && op != '/') break
            pos[0]++
            val rhs = evalFactor(s, pos) ?: return null
            if (op == '/' && rhs.value == 0.0) return null
            acc = Val(if (op == '*') acc.value * rhs.value else acc.value / rhs.value, false)
        }
        return acc
    }

    private fun evalFactor(s: String, pos: IntArray): Val? {
        skipSpaces(s, pos)
        // A sign binds to the operand, which is what lets "-5+3" work and makes
        // "1++2" read as 1 + (+2) rather than being rejected.
        var sign = 1.0
        while (pos[0] < s.length && (s[pos[0]] == '-' || s[pos[0]] == '+')) {
            if (s[pos[0]] == '-') sign = -sign
            pos[0]++
            skipSpaces(s, pos)
        }
        if (pos[0] >= s.length) return null
        val base: Double
        if (s[pos[0]] == '(') {
            pos[0]++
            val inner = evalExpr(s, pos) ?: return null
            skipSpaces(s, pos)
            if (pos[0] >= s.length || s[pos[0]] != ')') return null
            pos[0]++
            base = inner.value
        } else {
            base = parseNumber(s, pos) ?: return null
        }
        val percent = pos[0] < s.length && s[pos[0]] == '%'
        if (percent) pos[0]++
        return Val(sign * base / (if (percent) 100.0 else 1.0), percent)
    }

    private fun parseNumber(s: String, pos: IntArray): Double? {
        val start = pos[0]
        while (pos[0] < s.length && (s[pos[0]].isDigit() || s[pos[0]] == '.' || s[pos[0]] == ',')) {
            pos[0]++
        }
        if (pos[0] == start) return null
        return s.substring(start, pos[0]).replace(',', '.').toDoubleOrNull()
    }

    private fun skipSpaces(s: String, pos: IntArray) {
        while (pos[0] < s.length && s[pos[0]] == ' ') pos[0]++
    }

    /** Trims a result to something readable, or null if it is unreasonably big. */
    fun format(v: Double): String? {
        if (kotlin.math.abs(v) >= 1e12) return null
        val rounded = Math.round(v)
        if (v == rounded.toDouble()) return rounded.toString()
        val s = String.format(java.util.Locale.US, "%.4f", v).trimEnd('0').trimEnd('.')
        return if (s == "-0") "0" else s
    }
}
