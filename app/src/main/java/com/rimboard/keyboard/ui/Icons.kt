package com.rimboard.keyboard.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.rimboard.keyboard.model.Codes
import kotlin.math.cos
import kotlin.math.sin

/**
 * Hand-drawn vector icons for the keyboard chrome. They replace emoji so the
 * interface looks identical on every device and tints with the theme.
 */
object Icons {

    const val GLOBE = 1
    const val CLIPBOARD = 2
    const val EDIT = 3
    const val ONE_HANDED = 4
    const val FLOATING = 5
    const val INCOGNITO = 6
    const val SETTINGS = 7
    const val EMOJI = 8
    const val KEYBOARD = 9
    const val PIN = 10
    const val TRASH = 11
    const val TRANSLATE = 12
    const val UNDO = 13
    const val REDO = 14
    const val SEARCH = 15

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val oval = RectF()

    fun forCode(code: Int): Int? = when (code) {
        Codes.LANG -> GLOBE
        Codes.CLIPBOARD -> CLIPBOARD
        Codes.EDIT_PANEL -> EDIT
        Codes.ONE_HANDED -> ONE_HANDED
        Codes.FLOATING -> FLOATING
        Codes.INCOGNITO -> INCOGNITO
        Codes.SETTINGS -> SETTINGS
        Codes.EMOJI -> EMOJI
        Codes.IME_PICKER -> KEYBOARD
        else -> null
    }

    fun forLabel(label: String): Int? = when (label) {
        "\uD83D\uDD0D" -> SEARCH
        else -> null
    }

    fun draw(c: Canvas, icon: Int, cx: Float, cy: Float, s: Float, color: Int) {
        val r = s / 2f
        p.reset()
        p.isAntiAlias = true
        p.color = color
        p.style = Paint.Style.STROKE
        p.strokeWidth = s * 0.11f
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.DEFAULT

        when (icon) {
            GLOBE -> {
                c.drawCircle(cx, cy, r * 0.9f, p)
                oval.set(cx - r * 0.42f, cy - r * 0.9f, cx + r * 0.42f, cy + r * 0.9f)
                c.drawOval(oval, p)
                c.drawLine(cx - r * 0.9f, cy, cx + r * 0.9f, cy, p)
            }
            CLIPBOARD -> {
                oval.set(cx - r * 0.62f, cy - r * 0.72f, cx + r * 0.62f, cy + r * 0.92f)
                c.drawRoundRect(oval, r * 0.14f, r * 0.14f, p)
                p.style = Paint.Style.FILL
                oval.set(cx - r * 0.28f, cy - r * 0.92f, cx + r * 0.28f, cy - r * 0.56f)
                c.drawRoundRect(oval, r * 0.1f, r * 0.1f, p)
                p.style = Paint.Style.STROKE
                c.drawLine(cx - r * 0.32f, cy - r * 0.08f, cx + r * 0.32f, cy - r * 0.08f, p)
                c.drawLine(cx - r * 0.32f, cy + r * 0.32f, cx + r * 0.32f, cy + r * 0.32f, p)
            }
            EDIT -> {
                p.strokeWidth = s * 0.2f
                c.drawLine(cx - r * 0.55f, cy + r * 0.55f, cx + r * 0.28f, cy - r * 0.28f, p)
                p.style = Paint.Style.FILL
                path.reset()
                path.moveTo(cx + r * 0.66f, cy - r * 0.66f)
                path.lineTo(cx + r * 0.42f, cy - r * 0.12f)
                path.lineTo(cx + r * 0.12f, cy - r * 0.42f)
                path.close()
                c.drawPath(path, p)
            }
            ONE_HANDED -> {
                oval.set(cx - r * 0.9f, cy - r * 0.55f, cx + r * 0.9f, cy + r * 0.55f)
                c.drawRoundRect(oval, r * 0.12f, r * 0.12f, p)
                p.style = Paint.Style.FILL
                path.reset()
                path.moveTo(cx + r * 0.38f, cy)
                path.lineTo(cx - r * 0.1f, cy - r * 0.26f)
                path.lineTo(cx - r * 0.1f, cy + r * 0.26f)
                path.close()
                c.drawPath(path, p)
                c.drawRect(cx + r * 0.48f, cy - r * 0.28f, cx + r * 0.62f, cy + r * 0.28f, p)
            }
            FLOATING -> {
                oval.set(cx - r * 0.85f, cy - r * 0.7f, cx + r * 0.45f, cy + r * 0.35f)
                c.drawRoundRect(oval, r * 0.12f, r * 0.12f, p)
                p.style = Paint.Style.FILL
                oval.set(cx + r * 0.02f, cy + r * 0.05f, cx + r * 0.85f, cy + r * 0.7f)
                c.drawRoundRect(oval, r * 0.1f, r * 0.1f, p)
            }
            INCOGNITO -> {
                p.strokeWidth = s * 0.14f
                c.drawLine(cx - r * 0.85f, cy - r * 0.05f, cx + r * 0.85f, cy - r * 0.05f, p)
                p.style = Paint.Style.FILL
                oval.set(cx - r * 0.5f, cy - r * 0.6f, cx + r * 0.5f, cy + r * 0.05f)
                c.drawArc(oval, 180f, 180f, true, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = s * 0.1f
                c.drawCircle(cx - r * 0.34f, cy + r * 0.38f, r * 0.2f, p)
                c.drawCircle(cx + r * 0.34f, cy + r * 0.38f, r * 0.2f, p)
                c.drawLine(cx - r * 0.14f, cy + r * 0.38f, cx + r * 0.14f, cy + r * 0.38f, p)
            }
            SETTINGS -> {
                p.strokeWidth = s * 0.14f
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    c.drawLine(
                        cx + (cos(a) * r * 0.32f).toFloat(), cy + (sin(a) * r * 0.32f).toFloat(),
                        cx + (cos(a) * r * 0.52f).toFloat(), cy + (sin(a) * r * 0.52f).toFloat(), p
                    )
                }
                p.strokeWidth = s * 0.11f
                c.drawCircle(cx, cy, r * 0.32f, p)
                c.drawCircle(cx, cy, r * 0.12f, p)
            }
            EMOJI -> {
                c.drawCircle(cx, cy, r * 0.88f, p)
                p.style = Paint.Style.FILL
                c.drawCircle(cx - r * 0.3f, cy - r * 0.22f, s * 0.06f, p)
                c.drawCircle(cx + r * 0.3f, cy - r * 0.22f, s * 0.06f, p)
                p.style = Paint.Style.STROKE
                oval.set(cx - r * 0.45f, cy - r * 0.25f, cx + r * 0.45f, cy + r * 0.5f)
                c.drawArc(oval, 25f, 130f, false, p)
            }
            KEYBOARD -> {
                oval.set(cx - r * 0.9f, cy - r * 0.55f, cx + r * 0.9f, cy + r * 0.55f)
                c.drawRoundRect(oval, r * 0.12f, r * 0.12f, p)
                p.style = Paint.Style.FILL
                for (dx in intArrayOf(-1, 0, 1)) {
                    c.drawCircle(cx + dx * r * 0.42f, cy - r * 0.2f, s * 0.05f, p)
                    c.drawCircle(cx + dx * r * 0.42f, cy + r * 0.05f, s * 0.05f, p)
                }
                p.style = Paint.Style.STROKE
                c.drawLine(cx - r * 0.35f, cy + r * 0.32f, cx + r * 0.35f, cy + r * 0.32f, p)
            }
            PIN -> {
                p.style = Paint.Style.FILL
                c.drawCircle(cx, cy - r * 0.28f, r * 0.36f, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = s * 0.12f
                c.drawLine(cx, cy + r * 0.08f, cx, cy + r * 0.85f, p)
            }
            TRASH -> {
                c.drawLine(cx - r * 0.62f, cy - r * 0.52f, cx + r * 0.62f, cy - r * 0.52f, p)
                oval.set(cx - r * 0.2f, cy - r * 0.78f, cx + r * 0.2f, cy - r * 0.52f)
                c.drawRoundRect(oval, r * 0.08f, r * 0.08f, p)
                path.reset()
                path.moveTo(cx - r * 0.48f, cy - r * 0.52f)
                path.lineTo(cx - r * 0.38f, cy + r * 0.8f)
                path.lineTo(cx + r * 0.38f, cy + r * 0.8f)
                path.lineTo(cx + r * 0.48f, cy - r * 0.52f)
                c.drawPath(path, p)
                c.drawLine(cx - r * 0.16f, cy - r * 0.24f, cx - r * 0.13f, cy + r * 0.52f, p)
                c.drawLine(cx + r * 0.16f, cy - r * 0.24f, cx + r * 0.13f, cy + r * 0.52f, p)
            }
            TRANSLATE -> {
                oval.set(cx - r * 0.85f, cy - r * 0.85f, cx + r * 0.25f, cy + r * 0.25f)
                c.drawRoundRect(oval, r * 0.14f, r * 0.14f, p)
                oval.set(cx - r * 0.25f, cy - r * 0.25f, cx + r * 0.85f, cy + r * 0.85f)
                c.drawRoundRect(oval, r * 0.14f, r * 0.14f, p)
                p.style = Paint.Style.FILL
                p.typeface = Typeface.DEFAULT_BOLD
                p.textSize = s * 0.44f
                val tcy = cy + r * 0.3f - (p.ascent() + p.descent()) / 2f
                c.drawText("A", cx + r * 0.3f, tcy, p)
            }
            UNDO, REDO -> {
                val start = if (icon == UNDO) -90f else -90f
                val sweep = if (icon == UNDO) -265f else 265f
                oval.set(cx - r * 0.62f, cy - r * 0.62f, cx + r * 0.62f, cy + r * 0.62f)
                c.drawArc(oval, start, sweep, false, p)
                val endDeg = Math.toRadians((start + sweep).toDouble())
                val ex = cx + (cos(endDeg) * r * 0.62f).toFloat()
                val ey = cy + (sin(endDeg) * r * 0.62f).toFloat()
                val tangent = endDeg + if (sweep < 0) -Math.PI / 2 else Math.PI / 2
                val hx = (cos(tangent) * r * 0.34f).toFloat()
                val hy = (sin(tangent) * r * 0.34f).toFloat()
                val px = (cos(tangent + Math.PI / 2) * r * 0.2f).toFloat()
                val py = (sin(tangent + Math.PI / 2) * r * 0.2f).toFloat()
                p.style = Paint.Style.FILL
                path.reset()
                path.moveTo(ex + hx, ey + hy)
                path.lineTo(ex + px, ey + py)
                path.lineTo(ex - px, ey - py)
                path.close()
                c.drawPath(path, p)
            }
            SEARCH -> {
                c.drawCircle(cx - r * 0.18f, cy - r * 0.18f, r * 0.5f, p)
                p.strokeWidth = s * 0.14f
                c.drawLine(cx + r * 0.2f, cy + r * 0.2f, cx + r * 0.72f, cy + r * 0.72f, p)
            }
        }
    }
}
