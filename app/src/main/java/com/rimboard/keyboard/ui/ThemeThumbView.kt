package com.rimboard.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.rimboard.keyboard.theme.KeyboardTheme

/**
 * A keyboard the size of a postage stamp, painted from a real [KeyboardTheme].
 *
 * The theme picker used to be twenty words in a list. A theme is the most
 * changed thing about a keyboard and it was chosen without seeing one — and
 * the names do not carry the information: nothing in "sage" against "mint"
 * says which is darker, or whether either has enough contrast to read.
 *
 * **Shapes rather than letters.** Real lettering at this size is a grey smear
 * and would say more about the font than the palette. Key caps as rounded
 * rectangles keep the silhouette a keyboard while letting the eleven colour
 * roles be the only thing that differs between two thumbnails — which is the
 * comparison the grid exists to make. Two themes that look alike here look
 * alike on the keyboard, and that is the point: the picker should be able to
 * answer "are these two the same theme twice" without the user installing
 * both.
 *
 * Every role that carries meaning on the real keyboard is drawn: [keyBg] and
 * [KeyboardTheme.keyBgFunc] differ on the bottom row as they do in life, the
 * enter key takes [KeyboardTheme.accent], and the strip band shows
 * [KeyboardTheme.stripText]. [KeyboardTheme.keyHint] is drawn too, small, on
 * the top row — it is one of the two roles the contrast test pins as under AA,
 * so leaving it out of the picture would hide the thing worth seeing.
 */
@SuppressLint("ViewConstructor")
class ThemeThumbView(context: Context) : View(context) {

    var theme: KeyboardTheme? = null
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Drawn with a ring in the accent colour.
     *
     * Not `selected`: `View` already has that, and a `var selected` here
     * compiles to `setSelected(Z)V` twice over.
     */
    var chosen: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val t = theme ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val pad = dp(1f)
        val radius = dp(6f)
        p.style = Paint.Style.FILL
        p.color = t.background
        r.set(pad, pad, w - pad, h - pad)
        canvas.drawRoundRect(r, radius, radius, p)

        // Geometry in the same proportions as the real keyboard: a 44dp bar
        // over three 54dp rows, so the strip reads as a strip rather than as a
        // fourth row of keys.
        val inset = dp(5f)
        val left = inset
        val right = w - inset
        val stripH = (h - inset * 2f) * 0.20f
        val rowGap = dp(2f)
        val rowH = (h - inset * 2f - stripH - rowGap * 3f) / 3f
        val keyR = dp(1.8f)

        // Strip: three bars standing in for chips, the first one accented the
        // way the bold suggestion is.
        val chipH = stripH * 0.34f
        val chipY = inset + (stripH - chipH) / 2f
        val chipGap = dp(4f)
        val chipW = (right - left - chipGap * 2f) / 3f
        for (i in 0 until 3) {
            p.color = if (i == 0) t.accent else t.stripText
            p.alpha = if (i == 0) 255 else 130
            val x = left + i * (chipW + chipGap)
            r.set(x, chipY, x + chipW * if (i == 0) 0.7f else 0.85f, chipY + chipH)
            canvas.drawRoundRect(r, chipH / 2f, chipH / 2f, p)
        }
        p.alpha = 255

        var y = inset + stripH + rowGap
        val keyGap = dp(1.6f)

        // Row 1: ten keys, with a hint tick on the first few — keyHint is one
        // of the roles pinned under AA, so it belongs in the picture.
        drawRow(canvas, t, left, right, y, rowH, 10, keyGap, keyR, hints = true)
        y += rowH + rowGap
        // Row 2: nine keys, indented, as the real layout is.
        val indent = (right - left) / 20f
        drawRow(canvas, t, left + indent, right - indent, y, rowH, 9, keyGap, keyR)
        y += rowH + rowGap
        // Row 3: a function key each end, and the accent enter.
        drawBottomRow(canvas, t, left, right, y, rowH, keyGap, keyR)

        if (chosen) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = dp(2f)
            p.color = t.accent
            r.set(pad, pad, w - pad, h - pad)
            canvas.drawRoundRect(r, radius, radius, p)
            p.style = Paint.Style.FILL
        }
    }

    private fun drawRow(
        canvas: Canvas, t: KeyboardTheme, left: Float, right: Float,
        top: Float, h: Float, keys: Int, gap: Float, keyR: Float,
        hints: Boolean = false
    ) {
        val kw = (right - left - gap * (keys - 1)) / keys
        for (i in 0 until keys) {
            val x = left + i * (kw + gap)
            p.color = t.keyBg
            r.set(x, top, x + kw, top + h)
            canvas.drawRoundRect(r, keyR, keyR, p)
            if (hints) {
                p.color = t.keyHint
                val hs = kw * 0.22f
                r.set(x + kw - hs - kw * 0.12f, top + h * 0.14f,
                      x + kw - kw * 0.12f, top + h * 0.14f + hs * 0.7f)
                canvas.drawRoundRect(r, keyR / 2f, keyR / 2f, p)
            }
            // The letter itself, as a bar: it is what keyText is for and it is
            // the role with the most contrast headroom, so it should be seen.
            p.color = t.keyText
            val lw = kw * 0.42f
            val lh = h * 0.26f
            r.set(x + (kw - lw) / 2f, top + h * 0.52f, x + (kw + lw) / 2f, top + h * 0.52f + lh)
            canvas.drawRoundRect(r, keyR / 2f, keyR / 2f, p)
        }
    }

    private fun drawBottomRow(
        canvas: Canvas, t: KeyboardTheme, left: Float, right: Float,
        top: Float, h: Float, gap: Float, keyR: Float
    ) {
        // 1.5 + 5 + 1.5 units, which is the shape of the real bottom row.
        val units = 8f
        val unit = (right - left - gap * 6f) / units
        var x = left

        p.color = t.keyBgFunc
        r.set(x, top, x + unit * 1.5f, top + h)
        canvas.drawRoundRect(r, keyR, keyR, p)
        x += unit * 1.5f + gap

        // The space bar, wide, in the ordinary key colour.
        p.color = t.keyBg
        r.set(x, top, x + unit * 5f, top + h)
        canvas.drawRoundRect(r, keyR, keyR, p)
        p.color = t.keyHint
        val sw = unit * 1.6f
        r.set(x + unit * 2.5f - sw / 2f, top + h * 0.44f,
              x + unit * 2.5f + sw / 2f, top + h * 0.44f + h * 0.14f)
        canvas.drawRoundRect(r, keyR / 2f, keyR / 2f, p)
        x += unit * 5f + gap

        // Enter: the accent, with onAccent on top of it. These two are the
        // pair the contrast test pins in five themes, so the thumbnail has to
        // show them touching.
        p.color = t.accent
        r.set(x, top, right, top + h)
        canvas.drawRoundRect(r, keyR, keyR, p)
        p.color = t.onAccent
        val ew = (right - x) * 0.4f
        r.set(x + (right - x - ew) / 2f, top + h * 0.42f,
              x + (right - x + ew) / 2f, top + h * 0.42f + h * 0.16f)
        canvas.drawRoundRect(r, keyR / 2f, keyR / 2f, p)
    }
}
