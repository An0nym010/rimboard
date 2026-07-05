package com.rimboard.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.View

/** A small view that renders one [Icons] glyph, tinted with [color]. */
@SuppressLint("ViewConstructor")
class IconView(context: Context, private val icon: Int) : View(context) {

    var color: Int = 0xFF888888.toInt()
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = minOf(width, height) * 0.6f
        if (s <= 0f) return
        Icons.draw(canvas, icon, width / 2f, height / 2f, s, color)
    }
}
