package com.rimboard.keyboard.ui

import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.util.DisplayMetrics

/**
 * How wide a suggestion word has to be drawn to be read.
 *
 * The other half of the shared chip rule; the arithmetic that turns these
 * widths into one text size is
 * [com.rimboard.keyboard.model.StripLayout.uniformTextSp], which is pure and
 * therefore testable on the JVM. This half needs a `Paint` and cannot be, so
 * it is kept as small as possible.
 *
 * **Measured, not counted.** This decides whether a chip is dropped, and
 * "iii" against "mmm" is a factor of three at the same length. The counting
 * approximation is fine for the width *shares*, where it runs once per chip
 * per keystroke and being a little out costs nothing; it is not fine here.
 */
object ChipText {

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    /**
     * The width [word] needs, in dp, drawn at [minSp] in [typeface], including
     * [padDp] of horizontal padding.
     *
     * Returns 0 for an empty word, which is how both callers mark a blank
     * slot; [com.rimboard.keyboard.model.StripLayout.uniformTextSp] skips
     * those rather than letting a zero need divide.
     */
    fun needDp(
        word: String,
        typeface: Typeface?,
        minSp: Float,
        padDp: Int,
        dm: DisplayMetrics
    ): Int {
        if (word.isEmpty()) return 0
        paint.typeface = typeface
        paint.textSize = minSp * dm.scaledDensity
        val px = paint.measureText(word) + padDp * dm.density
        return (px / dm.density).toInt() + 1
    }
}
