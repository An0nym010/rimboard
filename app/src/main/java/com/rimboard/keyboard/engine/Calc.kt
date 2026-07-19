package com.rimboard.keyboard.engine

/**
 * The inline calculator behind the "= 408" suggestion chip.
 *
 * Pure logic with no Android dependencies so it can be unit tested directly:
 * see `CalcTest`.
 */
object Calc {

    private val expression = Regex(
        "(?:\\d+(?:[.,]\\d+)?)(?:\\s*[+\\-*/×÷]\\s*\\d+(?:[.,]\\d+)?)+=?$"
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

    /** Two-pass evaluation: * / × ÷ first, then + -. Null if malformed. */
    fun eval(raw: String): Double? {
        val s = raw
        val nums = ArrayList<Double>()
        val ops = ArrayList<Char>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == ' ') {
                i++
                continue
            }
            if (c.isDigit() || ((c == '-' || c == '+') && nums.size == ops.size)) {
                // Two operands with no operator between them ("1 2") is malformed;
                // whitespace must not silently glue them into one number.
                if (nums.size != ops.size) return null
                val start = i
                if (c == '-' || c == '+') i++
                while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == ',')) i++
                nums.add(s.substring(start, i).replace(',', '.').toDoubleOrNull() ?: return null)
            } else if (c in "+-*/×÷") {
                if (nums.size != ops.size + 1) return null
                ops.add(c)
                i++
            } else return null
        }
        if (nums.size != ops.size + 1) return null
        var k = 0
        while (k < ops.size) {
            val op = ops[k]
            if (op == '*' || op == '×' || op == '/' || op == '÷') {
                val b = nums[k + 1]
                if ((op == '/' || op == '÷') && b == 0.0) return null
                nums[k] = if (op == '*' || op == '×') nums[k] * b else nums[k] / b
                nums.removeAt(k + 1)
                ops.removeAt(k)
            } else k++
        }
        var acc = nums[0]
        for (j in ops.indices) acc = if (ops[j] == '+') acc + nums[j + 1] else acc - nums[j + 1]
        return if (acc.isFinite()) acc else null
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
