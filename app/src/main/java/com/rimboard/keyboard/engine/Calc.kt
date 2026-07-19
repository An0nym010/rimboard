package com.rimboard.keyboard.engine

/**
 * The inline calculator behind the "= 408" suggestion chip.
 *
 * Pure logic with no Android dependencies so it can be unit tested directly:
 * see `CalcTest`.
 */
object Calc {

    private val expression = Regex(
        "(?=.*\\d)[0-9.,()\\s+\\-*/%×÷]*=?$"
    )

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
        val m = expression.find(before) ?: return null
        if (m.range.first == 0 && before.length >= window) return null
        val expr = m.value
        if (!expr.endsWith("=")) {
            val strongOp = expr.any { it == '+' || it == '*' || it == '×' || it == '÷' }
            val slashes = expr.count { it == '/' }
            if (!strongOp && slashes != 1) return null
            if (slashes > 1) return null
        }
        val value = eval(expr.removeSuffix("=")) ?: return null
        return "= " + (format(value) ?: return null)
    }

    /** Evaluate with operator precedence: parentheses > * / × ÷ % > + -. Null if malformed. */
    fun eval(raw: String): Double? {
        val cleaned = raw.replace(" ", "").replace("×", "*").replace("÷", "/")
        return try {
            evalExpr(cleaned, IntArray(1) { 0 })?.first
        } catch (_: Exception) {
            null
        }
    }

    private fun evalExpr(s: String, pos: IntArray): Pair<Double, Int>? {
        var term = evalTerm(s, pos) ?: return null
        while (pos[0] < s.length && s[pos[0]] in "+-") {
            val op = s[pos[0]++]
            val right = evalTerm(s, pos) ?: return null
            term = if (op == '+') term + right else term - right
        }
        return term to pos[0]
    }

    private fun evalTerm(s: String, pos: IntArray): Double? {
        var factor = evalFactor(s, pos) ?: return null
        while (pos[0] < s.length && s[pos[0]] in "*/%" ) {
            val op = s[pos[0]++]
            val right = evalFactor(s, pos) ?: return null
            factor = when (op) {
                '*' -> factor * right
                '/' -> if (right == 0.0) return null else factor / right
                '%' -> if (right == 0.0) return null else factor % right
                else -> return null
            }
        }
        return factor
    }

    private fun evalFactor(s: String, pos: IntArray): Double? {
        if (pos[0] >= s.length) return null
        return when {
            s[pos[0]] == '(' -> {
                pos[0]++
                val result = evalExpr(s, pos) ?: return null
                if (pos[0] >= s.length || s[pos[0]] != ')') return null
                pos[0]++
                result.first
            }
            s[pos[0]].isDigit() || s[pos[0]] == ',' || s[pos[0]] == '.' -> parseNumber(s, pos)
            s[pos[0]] == '-' || s[pos[0]] == '+' -> {
                val sign = if (s[pos[0]++] == '-') -1.0 else 1.0
                val n = parseNumber(s, pos) ?: return null
                sign * n
            }
            else -> null
        }
    }

    private fun parseNumber(s: String, pos: IntArray): Double? {
        val start = pos[0]
        while (pos[0] < s.length && (s[pos[0]].isDigit() || s[pos[0]] == '.' || s[pos[0]] == ',')) {
            pos[0]++
        }
        if (pos[0] == start) return null
        return s.substring(start, pos[0]).replace(',', '.').toDoubleOrNull()
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
