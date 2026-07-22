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
