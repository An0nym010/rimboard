package com.rimboard.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Hue/saturation disc with a brightness bar underneath.
 *
 * Replaces a grid of twenty-four fixed swatches, which could only ever offer
 * the colours someone had thought of in advance — and on a keyboard where the
 * whole point of the custom theme is that it is yours, "pick from this list"
 * is the wrong shape of control.
 *
 * HSV rather than RGB sliders because the two things people actually adjust
 * are "which colour" and "how dark", and those are one gesture and one bar
 * here instead of three numbers that have to be solved simultaneously.
 */
@SuppressLint("ViewConstructor")
class ColorWheelView(context: Context) : View(context) {

    /** Fired continuously while dragging, so a preview can follow the finger. */
    var onColorChanged: ((Int) -> Unit)? = null

    private val hsv = floatArrayOf(0f, 0f, 1f)

    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        color = Color.WHITE
    }
    private val markerShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
        color = 0x66000000
    }
    private val barRect = RectF()

    private var cx = 0f
    private var cy = 0f
    private var radius = 0f

    /** Which control the current gesture owns; a drag must not jump between them. */
    private var draggingBar = false

    private fun dp(v: Float) = v * resources.displayMetrics.density

    var color: Int
        get() = Color.HSVToColor(hsv)
        set(value) {
            Color.colorToHSV(value, hsv)
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        // Disc plus the bar and the gap it needs; squared off the width so the
        // wheel stays circular whatever the dialog does.
        setMeasuredDimension(w, (w + dp(56f)).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val barH = dp(28f)
        val discH = h - barH - dp(20f)
        radius = min(w, discH.toInt()) / 2f - dp(4f)
        cx = w / 2f
        cy = radius + dp(4f)
        barRect.set(dp(8f), h - barH, w - dp(8f), h.toFloat())
        buildShaders(w)
    }

    private fun buildShaders(w: Int) {
        if (radius <= 0f) return
        // Hue around, saturation outward: a sweep for the angle and a radial
        // white-to-transparent on top for the middle.
        val sweep = SweepGradient(cx, cy, HUES, null)
        val sat = RadialGradient(
            cx, cy, radius, Color.WHITE, 0x00FFFFFF, Shader.TileMode.CLAMP
        )
        discPaint.shader = ComposeShader(sweep, sat, PorterDuff.Mode.SRC_OVER)
    }

    override fun onDraw(canvas: Canvas) {
        if (radius <= 0f) return

        // The disc is drawn at full brightness and dimmed to match value, so
        // picking a dark colour still shows where on the wheel you are.
        canvas.drawCircle(cx, cy, radius, discPaint)
        if (hsv[2] < 1f) {
            canvas.drawCircle(cx, cy, radius, Paint().apply {
                color = (((1f - hsv[2]) * 255).toInt() shl 24)
            })
        }

        val a = Math.toRadians(hsv[0].toDouble())
        val mx = cx + (cos(a) * hsv[1] * radius).toFloat()
        val my = cy + (sin(a) * hsv[1] * radius).toFloat()
        canvas.drawCircle(mx, my, dp(9f), markerShadow)
        canvas.drawCircle(mx, my, dp(9f), markerPaint)

        // Brightness bar: black to the pure hue at full saturation.
        val pure = Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f))
        barPaint.shader = LinearGradient(
            barRect.left, 0f, barRect.right, 0f,
            Color.BLACK, pure, Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(barRect, barRect.height() / 2f, barRect.height() / 2f, barPaint)
        val bx = barRect.left + hsv[2] * barRect.width()
        canvas.drawCircle(bx, barRect.centerY(), barRect.height() / 2f - dp(1f), markerShadow)
        canvas.drawCircle(bx, barRect.centerY(), barRect.height() / 2f - dp(1f), markerPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Decided once, on the way down: sliding off the bar and over
                // the disc mid-drag should keep adjusting brightness, not
                // suddenly rewrite the hue.
                draggingBar = event.y >= barRect.top - dp(10f)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        if (draggingBar) {
            hsv[2] = ((event.x - barRect.left) / barRect.width()).coerceIn(0f, 1f)
        } else {
            val dx = event.x - cx
            val dy = event.y - cy
            hsv[0] = ((Math.toDegrees(atan2(dy, dx).toDouble()) + 360.0) % 360.0).toFloat()
            hsv[1] = (hypot(dx, dy) / radius).coerceIn(0f, 1f)
        }
        invalidate()
        onColorChanged?.invoke(color)
        return true
    }

    private companion object {
        /** Full turn of hue, ending where it started so the seam is invisible. */
        val HUES = intArrayOf(
            0xFFFF0000.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(),
            0xFF00FFFF.toInt(), 0xFF0000FF.toInt(), 0xFFFF00FF.toInt(),
            0xFFFF0000.toInt()
        )
    }
}
