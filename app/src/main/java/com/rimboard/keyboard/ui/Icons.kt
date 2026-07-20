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
 *
 * The toolbar set is drawn in a 24x24 design grid (see [grid24]) with a bold
 * 2.4-unit stroke, generous corner radii and solid filled accents, so the icons
 * read as confident shapes rather than thin hairline outlines.
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
    const val COPY = 16
    const val PASTE = 17
    const val CUT = 18
    const val SELECT_ALL = 19
    const val HIDE = 20
    const val SHARE = 21
    const val THEME = 22
    const val RESIZE = 23
    const val CHEVRON = 24      // expand ">"
    const val CHEVRON_L = 25    // collapse "<"
    const val GRID = 26         // all tools

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val oval = RectF()

    /** Bold stroke weight in 24-grid units. */
    private const val SW = 2.4f

    fun forCode(code: Int): Int? = when (code) {
        Codes.LANG -> GLOBE
        Codes.TOOLBAR_PANEL -> GRID
        Codes.CLIPBOARD -> CLIPBOARD
        Codes.EDIT_PANEL -> EDIT
        Codes.ONE_HANDED -> ONE_HANDED
        Codes.FLOATING -> FLOATING
        Codes.INCOGNITO -> INCOGNITO
        Codes.SETTINGS -> SETTINGS
        Codes.EMOJI -> EMOJI
        Codes.IME_PICKER -> KEYBOARD
        Codes.UNDO -> UNDO
        Codes.REDO -> REDO
        Codes.COPY -> COPY
        Codes.PASTE -> PASTE
        Codes.CUT -> CUT
        Codes.SELECT_ALL -> SELECT_ALL
        Codes.HIDE_KB -> HIDE
        Codes.NUMPAD -> KEYBOARD
        Codes.TRANSLATE -> TRANSLATE
        Codes.SHARE -> SHARE
        Codes.THEME -> THEME
        Codes.RESIZE -> RESIZE
        else -> null
    }

    fun forLabel(label: String): Int? = when (label) {
        "🔍" -> SEARCH
        "🕶" -> INCOGNITO
        else -> null
    }

    /**
     * Runs [body] with the canvas mapped so a 24x24 design grid fills the icon
     * box. Lets the toolbar icons be written in whole design units, and makes a
     * stroke width of [SW] scale automatically with the icon size.
     */
    private inline fun grid24(c: Canvas, cx: Float, cy: Float, s: Float, body: () -> Unit) {
        val save = c.save()
        c.translate(cx - s / 2f, cy - s / 2f)
        c.scale(s / 24f, s / 24f)
        p.strokeWidth = SW
        body()
        c.restoreToCount(save)
    }

    // ---- Lucide vector set ----------------------------------------------
    // Icons migrating to Lucide (ISC, see NOTICE) are drawn from
    // VectorDrawables; anything without one falls through to the hand-drawn
    // glyph below. That fallback is the point: a missing or misrendering
    // drawable degrades to the icon that already worked, instead of a blank.

    private var appContext: android.content.Context? = null
    private val vectorRes = HashMap<Int, Int>()
    private val vectorCache = HashMap<Int, android.graphics.drawable.Drawable?>()

    /** Called from the views that draw icons; the application context is kept. */
    fun attach(context: android.content.Context) {
        if (appContext != null) return
        val ctx = context.applicationContext
        appContext = ctx
        fun res(name: String) = ctx.resources.getIdentifier(name, "drawable", ctx.packageName)
        vectorRes[CHEVRON] = res("ic_tool_chevron_right")
        vectorRes[CHEVRON_L] = res("ic_tool_chevron_left")
        vectorRes[SETTINGS] = res("ic_tool_settings")
        vectorRes[CLIPBOARD] = res("ic_tool_clipboard")
        vectorRes[GRID] = res("ic_tool_grid")
    }

    private fun vector(icon: Int): android.graphics.drawable.Drawable? {
        if (!vectorCache.containsKey(icon)) {
            val ctx = appContext
            val id = vectorRes[icon] ?: 0
            vectorCache[icon] = if (ctx == null || id == 0) null else try {
                androidx.appcompat.content.res.AppCompatResources.getDrawable(ctx, id)
                    ?.mutate()
            } catch (_: Exception) {
                null
            }
        }
        return vectorCache[icon]
    }

    fun draw(c: Canvas, icon: Int, cx: Float, cy: Float, s: Float, color: Int) {
        vector(icon)?.let { d ->
            val h = s / 2f
            d.setBounds((cx - h).toInt(), (cy - h).toInt(), (cx + h).toInt(), (cy + h).toInt())
            d.setTint(color)
            d.draw(c)
            return
        }
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
            // ---------- toolbar set: 24-grid, bold, rounded, filled accents ----------
            GRID -> grid24(c, cx, cy, s) {
                // Nine rounded cells: reads as "everything" at strip size, where
                // a more literal toolbox shape would collapse into a blob.
                p.style = Paint.Style.FILL
                for (row in 0 until 3) for (col in 0 until 3) {
                    val x = 4.2f + col * 6.0f
                    val y = 4.2f + row * 6.0f
                    oval.set(x, y, x + 4.0f, y + 4.0f)
                    c.drawRoundRect(oval, 1.3f, 1.3f, p)
                }
            }
            CHEVRON -> grid24(c, cx, cy, s) {
                p.strokeWidth = 2.7f
                path.reset()
                path.moveTo(9.5f, 5.5f); path.lineTo(16f, 12f); path.lineTo(9.5f, 18.5f)
                c.drawPath(path, p)
            }
            CHEVRON_L -> grid24(c, cx, cy, s) {
                p.strokeWidth = 2.7f
                path.reset()
                path.moveTo(14.5f, 5.5f); path.lineTo(8f, 12f); path.lineTo(14.5f, 18.5f)
                c.drawPath(path, p)
            }
            ONE_HANDED -> grid24(c, cx, cy, s) {
                oval.set(2.8f, 6.6f, 15.2f, 17.8f)
                c.drawRoundRect(oval, 3.4f, 3.4f, p)
                p.style = Paint.Style.FILL
                oval.set(17.6f, 8.4f, 20.8f, 16f)
                c.drawRoundRect(oval, 1.6f, 1.6f, p)
            }
            RESIZE -> grid24(c, cx, cy, s) {
                c.drawLine(12f, 8f, 12f, 16f, p)
                p.style = Paint.Style.FILL
                path.reset()
                path.moveTo(12f, 3.2f); path.lineTo(15.8f, 8.4f); path.lineTo(8.2f, 8.4f)
                path.close(); c.drawPath(path, p)
                path.reset()
                path.moveTo(12f, 20.8f); path.lineTo(15.8f, 15.6f); path.lineTo(8.2f, 15.6f)
                path.close(); c.drawPath(path, p)
            }
            FLOATING -> grid24(c, cx, cy, s) {
                oval.set(3.6f, 7.4f, 20.4f, 18.8f)
                c.drawRoundRect(oval, 3.4f, 3.4f, p)
                p.style = Paint.Style.FILL
                oval.set(9.2f, 3.6f, 14.8f, 5.7f)
                c.drawRoundRect(oval, 1.05f, 1.05f, p)
            }
            GLOBE -> grid24(c, cx, cy, s) {
                c.drawCircle(12f, 12f, 8.6f, p)
                oval.set(8.1f, 3.4f, 15.9f, 20.6f)
                c.drawOval(oval, p)
                c.drawLine(3.6f, 12f, 20.4f, 12f, p)
            }
            EDIT -> grid24(c, cx, cy, s) {
                path.reset()
                path.moveTo(5.2f, 18.8f)
                path.lineTo(8.7f, 18.8f)
                path.lineTo(18.5f, 9f)
                path.lineTo(15f, 5.5f)
                path.lineTo(5.2f, 15.3f)
                path.close()
                c.drawPath(path, p)
                c.drawLine(13.1f, 7.4f, 16.6f, 10.9f, p)
            }
            CLIPBOARD -> grid24(c, cx, cy, s) {
                oval.set(4.8f, 4.4f, 19.2f, 20.6f)
                c.drawRoundRect(oval, 3.4f, 3.4f, p)
                p.style = Paint.Style.FILL
                oval.set(8.6f, 2.4f, 15.4f, 6.6f)
                c.drawRoundRect(oval, 1.9f, 1.9f, p)
                p.style = Paint.Style.STROKE
                c.drawLine(8.6f, 11.8f, 15.4f, 11.8f, p)
                c.drawLine(8.6f, 15.6f, 13.2f, 15.6f, p)
            }
            EMOJI -> grid24(c, cx, cy, s) {
                c.drawCircle(12f, 12f, 8.6f, p)
                p.style = Paint.Style.FILL
                c.drawCircle(8.9f, 9.9f, 1.35f, p)
                c.drawCircle(15.1f, 9.9f, 1.35f, p)
                p.style = Paint.Style.STROKE
                oval.set(7.4f, 9.2f, 16.6f, 15.8f)
                c.drawArc(oval, 32f, 116f, false, p)
            }
            TRANSLATE -> grid24(c, cx, cy, s) {
                oval.set(3.2f, 3.2f, 13.6f, 13.6f)
                c.drawRoundRect(oval, 3.2f, 3.2f, p)
                oval.set(10.4f, 10.4f, 20.8f, 20.8f)
                c.drawRoundRect(oval, 3.2f, 3.2f, p)
                p.style = Paint.Style.FILL
                p.typeface = Typeface.DEFAULT_BOLD
                p.textSize = 6.6f
                c.drawText("A", 8.4f, 8.4f - (p.ascent() + p.descent()) / 2f, p)
                p.textSize = 6.2f
                c.drawText("文", 15.6f, 15.6f - (p.ascent() + p.descent()) / 2f, p)
            }
            SHARE -> grid24(c, cx, cy, s) {
                c.drawLine(8.2f, 10.8f, 15.3f, 7.2f, p)
                c.drawLine(8.2f, 13.2f, 15.3f, 16.8f, p)
                p.style = Paint.Style.FILL
                c.drawCircle(5.9f, 12f, 2.6f, p)
                c.drawCircle(17.6f, 6f, 2.6f, p)
                c.drawCircle(17.6f, 18f, 2.6f, p)
            }
            THEME -> grid24(c, cx, cy, s) {
                c.drawCircle(12f, 12f, 8.6f, p)
                p.style = Paint.Style.FILL
                oval.set(3.4f, 3.4f, 20.6f, 20.6f)
                c.drawArc(oval, -90f, 180f, true, p)
            }
            UNDO, REDO -> grid24(c, cx, cy, s) {
                val rad = 7.2f
                val start = -90f
                val sweep = if (icon == UNDO) -258f else 258f
                oval.set(12f - rad, 12f - rad, 12f + rad, 12f + rad)
                c.drawArc(oval, start, sweep, false, p)
                val end = Math.toRadians((start + sweep).toDouble())
                val ex = 12f + (cos(end) * rad).toFloat()
                val ey = 12f + (sin(end) * rad).toFloat()
                val tan = end + if (sweep < 0) -Math.PI / 2 else Math.PI / 2
                val hx = (cos(tan) * 4.4).toFloat()
                val hy = (sin(tan) * 4.4).toFloat()
                val nx = (cos(tan + Math.PI / 2) * 2.9).toFloat()
                val ny = (sin(tan + Math.PI / 2) * 2.9).toFloat()
                p.style = Paint.Style.FILL
                path.reset()
                path.moveTo(ex + hx, ey + hy)
                path.lineTo(ex + nx, ey + ny)
                path.lineTo(ex - nx, ey - ny)
                path.close()
                c.drawPath(path, p)
            }
            INCOGNITO -> grid24(c, cx, cy, s) {
                p.style = Paint.Style.FILL
                oval.set(5.6f, 9.4f, 18.4f, 16.4f)   // crown, sits on the brim
                c.drawArc(oval, 180f, 180f, true, p)
                oval.set(3.2f, 11.6f, 20.8f, 13.9f)  // brim
                c.drawRoundRect(oval, 1.15f, 1.15f, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2.1f
                c.drawCircle(8.6f, 17.3f, 2.5f, p)
                c.drawCircle(15.4f, 17.3f, 2.5f, p)
                c.drawLine(11.1f, 17.3f, 12.9f, 17.3f, p)
            }
            SETTINGS -> grid24(c, cx, cy, s) {
                path.reset()
                for (i in 0 until 8) {
                    val a = i * Math.PI / 4.0
                    val a1 = a - 0.23
                    val a2 = a + 0.23
                    val ax = 12f + (6.2 * cos(a1)).toFloat()
                    val ay = 12f + (6.2 * sin(a1)).toFloat()
                    val bx = 12f + (8.9 * cos(a1)).toFloat()
                    val by = 12f + (8.9 * sin(a1)).toFloat()
                    val ox = 12f + (8.9 * cos(a2)).toFloat()
                    val oy = 12f + (8.9 * sin(a2)).toFloat()
                    val dx = 12f + (6.2 * cos(a2)).toFloat()
                    val dy = 12f + (6.2 * sin(a2)).toFloat()
                    if (i == 0) path.moveTo(ax, ay) else path.lineTo(ax, ay)
                    path.lineTo(bx, by)
                    path.lineTo(ox, oy)
                    path.lineTo(dx, dy)
                }
                path.close()
                c.drawPath(path, p)
                c.drawCircle(12f, 12f, 2.9f, p)
            }
            HIDE -> grid24(c, cx, cy, s) {
                oval.set(3.4f, 5f, 20.6f, 15.4f)
                c.drawRoundRect(oval, 3f, 3f, p)
                p.style = Paint.Style.FILL
                c.drawCircle(7.6f, 8.6f, 0.9f, p)
                c.drawCircle(12f, 8.6f, 0.9f, p)
                c.drawCircle(16.4f, 8.6f, 0.9f, p)
                oval.set(8.8f, 11.2f, 15.2f, 12.9f)
                c.drawRoundRect(oval, 0.85f, 0.85f, p)
                p.style = Paint.Style.STROKE
                path.reset()
                path.moveTo(9f, 18.2f); path.lineTo(12f, 21f); path.lineTo(15f, 18.2f)
                c.drawPath(path, p)
            }

            // ---------- panel-only icons (unchanged r-space drawings) ----------
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
            SEARCH -> {
                c.drawCircle(cx - r * 0.18f, cy - r * 0.18f, r * 0.5f, p)
                p.strokeWidth = s * 0.14f
                c.drawLine(cx + r * 0.2f, cy + r * 0.2f, cx + r * 0.72f, cy + r * 0.72f, p)
            }
            COPY -> {
                oval.set(cx - r * 0.75f, cy - r * 0.75f, cx + r * 0.25f, cy + r * 0.25f)
                c.drawRoundRect(oval, r * 0.12f, r * 0.12f, p)
                oval.set(cx - r * 0.25f, cy - r * 0.25f, cx + r * 0.75f, cy + r * 0.75f)
                c.drawRoundRect(oval, r * 0.12f, r * 0.12f, p)
            }
            PASTE -> {
                oval.set(cx - r * 0.6f, cy - r * 0.68f, cx + r * 0.6f, cy + r * 0.85f)
                c.drawRoundRect(oval, r * 0.12f, r * 0.12f, p)
                p.style = Paint.Style.FILL
                oval.set(cx - r * 0.26f, cy - r * 0.88f, cx + r * 0.26f, cy - r * 0.55f)
                c.drawRoundRect(oval, r * 0.08f, r * 0.08f, p)
                p.style = Paint.Style.STROKE
                c.drawLine(cx, cy - r * 0.2f, cx, cy + r * 0.42f, p)
                path.reset()
                path.moveTo(cx - r * 0.24f, cy + r * 0.2f)
                path.lineTo(cx, cy + r * 0.46f)
                path.lineTo(cx + r * 0.24f, cy + r * 0.2f)
                c.drawPath(path, p)
            }
            CUT -> {
                c.drawCircle(cx - r * 0.45f, cy + r * 0.45f, r * 0.24f, p)
                c.drawCircle(cx + r * 0.45f, cy + r * 0.45f, r * 0.24f, p)
                c.drawLine(cx - r * 0.28f, cy + r * 0.28f, cx + r * 0.55f, cy - r * 0.7f, p)
                c.drawLine(cx + r * 0.28f, cy + r * 0.28f, cx - r * 0.55f, cy - r * 0.7f, p)
            }
            SELECT_ALL -> {
                val e = r * 0.8f
                val l = r * 0.36f
                c.drawLine(cx - e, cy - e, cx - e + l, cy - e, p)
                c.drawLine(cx - e, cy - e, cx - e, cy - e + l, p)
                c.drawLine(cx + e, cy - e, cx + e - l, cy - e, p)
                c.drawLine(cx + e, cy - e, cx + e, cy - e + l, p)
                c.drawLine(cx - e, cy + e, cx - e + l, cy + e, p)
                c.drawLine(cx - e, cy + e, cx - e, cy + e - l, p)
                c.drawLine(cx + e, cy + e, cx + e - l, cy + e, p)
                c.drawLine(cx + e, cy + e, cx + e, cy + e - l, p)
                p.style = Paint.Style.FILL
                oval.set(cx - r * 0.3f, cy - r * 0.3f, cx + r * 0.3f, cy + r * 0.3f)
                c.drawRoundRect(oval, r * 0.08f, r * 0.08f, p)
            }
        }
    }
}
