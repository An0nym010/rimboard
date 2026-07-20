package com.rimboard.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.View

/** A small view that renders one [Icons] glyph, tinted with [color]. */
@SuppressLint("ViewConstructor")
class IconView(context: Context, icon: Int) : View(context) {

    init {
        Icons.attach(context)
    }

    /** Mutable so a recycled row can be rebound to a different tool. */
    var icon: Int = icon
        set(value) {
            field = value
            invalidate()
        }

    var color: Int = 0xFF888888.toInt()
        set(value) {
            field = value
            invalidate()
        }

    override fun dispatchSetPressed(pressed: Boolean) {
        super.dispatchSetPressed(pressed)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = minOf(width, height) * 0.6f
        if (s <= 0f) return
        // Pressed feedback: a soft disc under the glyph, tinted from the glyph
        // colour so it works on any theme. These icons previously gave no
        // visual response to touch at all.
        if (isPressed && isClickable) {
            val prev = p.color
            p.color = (0x2E shl 24) or (color and 0x00FFFFFF)
            canvas.drawCircle(width / 2f, height / 2f, s * 0.85f, p)
            p.color = prev
        }
        Icons.draw(canvas, icon, width / 2f, height / 2f, s, color)
    }

    private companion object {
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    }
}
