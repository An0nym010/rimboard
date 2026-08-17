package com.rimboard.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.widget.LinearLayout

/**
 * The vertical column holding the suggestion strip and the keyboard frame,
 * drawing the background photo behind both.
 *
 * The photo used to be drawn by KeyboardView, so it ended at the top of the
 * keys and the strip above stayed a flat block of theme colour — a hard seam
 * across the picture. Drawn here, one image covers the whole surface and the
 * strip goes transparent over it (the service arranges that), the way every
 * photo-theme keyboard presents it.
 *
 * Drawing order is View's own: the background colour (set by the service as
 * the base under the photo, and all anyone sees until the decode lands), then
 * [onDraw] with the photo and its dim overlay, then the children on top.
 */
@SuppressLint("ViewConstructor")
class PhotoBackdrop(context: Context) : LinearLayout(context) {

    /** Dim overlay strength (0..255) painted over the photo. */
    var dimAlpha = 110
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint()
    private val dst = Rect()
    private var bm: android.graphics.Bitmap? = null
    private var bmStamp = -1

    /** Stamp a decode is in flight for, so one frame never starts a second
     *  worker for the same size and image. */
    private var decodeFor = -1

    private var probeVersion = -1
    private var filePresent = false

    // ---- live starfield -----------------------------------------------------

    /**
     * Whether the animated night sky is drawn behind the keys.
     *
     * Off unless asked for. This is the only thing in the app that draws when
     * nobody has touched anything, and a keyboard is on screen for hours a day
     * — an always-on animation is a battery cost the user has to opt into
     * rather than discover.
     */
    var starsEnabled = false
        set(value) {
            if (field == value) return
            field = value
            if (value) startFrames() else stopFrames()
            invalidate()
        }

    /** Which live background is drawn: "none", "stars" or "particles". */
    var liveMode: String = "none"
        set(value) {
            if (field == value) return
            field = value
            starsEnabled = value != "none"
        }

    /** Star colour, taken from the theme so it works on any background. */
    var starColor = 0xFFFFFFFF.toInt()

    private val field = Starfield()
    private val grid by lazy {
        val d = resources.displayMetrics.density
        ParticleGrid(spacing = 14f * d, margin = 6f * d)
    }
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lastFrameNs = 0L
    private var framesRunning = false

    /**
     * One frame, then decide whether to ask for another.
     *
     * Posted through the view's own animation queue rather than a Handler, so
     * it is throttled to the display and stops with the view.
     *
     * The particle grid stops when it has come to rest, which is most of the
     * time: it only moves for about a second after a key is pressed. Redrawing
     * it at full rate in between would be an animation nobody can see, on a
     * surface that is on screen for hours a day. The night sky never stops,
     * because its stars are always drifting — that is the whole of what it is.
     */
    private val frame = object : Runnable {
        override fun run() {
            if (!framesRunning || !starsEnabled) return
            val now = android.os.SystemClock.uptimeMillis()
            val dt = if (lastFrameNs == 0L) 0f else (now - lastFrameNs) / 1000f
            lastFrameNs = now
            val particles = liveMode == "particles"
            if (particles) grid.step(dt) else field.step(dt)
            invalidate()
            if (particles && !grid.settling()) {
                // At rest. [nudgeStars] starts it again on the next keypress.
                framesRunning = false
                return
            }
            postOnAnimation(this)
        }
    }

    private fun startFrames() {
        if (framesRunning || !isAttachedToWindow) return
        framesRunning = true
        lastFrameNs = 0L
        postOnAnimation(frame)
    }

    private fun stopFrames() {
        framesRunning = false
        removeCallbacks(frame)
    }

    /** A key was pressed here; the stars nearby lean away from it. */
    fun nudgeStars(x: Float, y: Float) {
        if (!starsEnabled) return
        if (liveMode == "particles") {
            grid.touch(x, y)
            // The grid stops itself when it settles, so a push has to wake it.
            if (!framesRunning) startFrames()
        } else {
            field.touch(x, y)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (starsEnabled) startFrames()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // The keyboard is gone; nothing should still be animating for it. This
        // is the same rule the panels follow, and it matters more here because
        // this callback reschedules itself forever.
        stopFrames()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // Attached but hidden is the ordinary state of a keyboard: the view
        // survives between fields. Animating an invisible sky is pure cost.
        if (visibility == VISIBLE && starsEnabled) startFrames() else stopFrames()
    }

    private fun drawStars(canvas: Canvas) {
        field.resize(width.toFloat(), height.toFloat())
        val base = starColor and 0x00FFFFFF
        for (s in field.stars()) {
            val a = (field.brightness(s) * 255f).toInt().coerceIn(0, 255)
            starPaint.color = base or (a shl 24)
            canvas.drawCircle(s.x, s.y, s.radius, starPaint)
        }
    }

    private fun drawParticles(canvas: Canvas) {
        grid.resize(width.toFloat(), height.toFloat())
        if (grid.size() == 0) return
        // One call for the whole grid. Drawing several hundred circles
        // individually is several hundred draw commands per frame; drawPoints
        // takes the array as it already is and issues one.
        starPaint.color = (starColor and 0x00FFFFFF) or (0x7A shl 24)
        starPaint.strokeWidth = resources.displayMetrics.density * 1.7f
        starPaint.strokeCap = Paint.Cap.ROUND
        canvas.drawPoints(grid.points(), 0, grid.size() * 2, starPaint)
    }

    private fun drawLive(canvas: Canvas) {
        if (liveMode == "particles") drawParticles(canvas) else drawStars(canvas)
    }

    init {
        orientation = VERTICAL
        // A ViewGroup skips onDraw entirely unless told otherwise.
        setWillNotDraw(false)
    }

    /** Whether a background image is set, cached against [BgImageState.version]
     *  so the per-frame cost is a field read, not a filesystem probe. */
    private fun present(): Boolean {
        if (probeVersion != BgImageState.version) {
            probeVersion = BgImageState.version
            filePresent = try {
                java.io.File(
                    com.rimboard.keyboard.engine.UserData.dataDir(context), "bg_image.jpg"
                ).exists()
            } catch (_: Exception) {
                false
            }
        }
        return filePresent
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        if (!present()) {
            bm = null
            bmStamp = -1
            // No photo, so the sky is the background. With a photo it is not
            // drawn at all: two moving backgrounds behind the same keys is one
            // too many, and the photo is the more deliberate choice of the two.
            if (starsEnabled) drawLive(canvas)
            return
        }
        val stamp = BgImageState.version * 31 + width * 7 + height
        if (bmStamp != stamp && decodeFor != stamp) {
            // Decoding a photo costs tens of milliseconds; run it here and the
            // first frame of every keyboard open stalls by the price of a JPEG
            // decode. A worker decodes and posts back; until it lands, the
            // previous bitmap stands in, stretched to the new bounds, which
            // reads as the photo settling rather than the keyboard freezing.
            decodeFor = stamp
            val w = width
            val h = height
            val f = java.io.File(
                com.rimboard.keyboard.engine.UserData.dataDir(context), "bg_image.jpg")
            Thread {
                val decoded = try {
                    decodeCentered(f, w, h)
                } catch (e: Exception) {
                    // A background that silently fails to decode reads as the
                    // setting simply not working.
                    android.util.Log.w("RimBoard", "background image decode failed", e)
                    null
                }
                post {
                    if (decodeFor == stamp) {
                        bm = decoded
                        bmStamp = stamp
                        invalidate()
                    } else {
                        // A newer size or image superseded this decode.
                        decoded?.recycle()
                    }
                }
            }.start()
        }
        bm?.let { b ->
            dst.set(0, 0, width, height)
            canvas.drawBitmap(b, null, dst, null)
            paint.color = (dimAlpha shl 24)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
    }

    private fun decodeCentered(f: java.io.File, w: Int, h: Int): android.graphics.Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = android.graphics.BitmapFactory.decodeFile(f.path, opts) ?: return null
        val scale = maxOf(w.toFloat() / raw.width, h.toFloat() / raw.height)
        val sw = (raw.width * scale).toInt().coerceAtLeast(w)
        val sh = (raw.height * scale).toInt().coerceAtLeast(h)
        val scaled = android.graphics.Bitmap.createScaledBitmap(raw, sw, sh, true)
        if (scaled !== raw) raw.recycle()
        val out = android.graphics.Bitmap.createBitmap(scaled, (sw - w) / 2, (sh - h) / 2, w, h)
        if (out !== scaled) scaled.recycle()
        return out
    }
}
