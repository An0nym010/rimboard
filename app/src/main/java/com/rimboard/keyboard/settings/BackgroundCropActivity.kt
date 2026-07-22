package com.rimboard.keyboard.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import com.rimboard.keyboard.R
import com.rimboard.keyboard.engine.UserData
import com.rimboard.keyboard.ui.BgImageState
import java.io.ByteArrayInputStream

/**
 * Position a picked photo before it becomes the keyboard background.
 *
 * The photo is shown behind a fixed window shaped like the keyboard; drag and
 * pinch move the picture inside it, a slider previews the dim live, and Apply
 * renders exactly the framed region to bg_image.jpg. Before this step existed
 * the pick saved immediately with a blind centre-crop, and whether your
 * subject survived it was luck.
 *
 * The frame's shape is the portrait keyboard's, derived from the current
 * settings (number row, height factor). At runtime the backdrop still
 * centre-crops the saved image to the live keyboard size, so a crop made at
 * this shape passes through roughly unchanged in portrait; landscape reframes
 * it, which is the same compromise every keyboard makes.
 */
class BackgroundCropActivity : AppCompatActivity() {

    private lateinit var crop: CropView
    private var saving = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(L10n.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setTitle(R.string.pref_bg_image_title)
        val uri = intent.data
        if (uri == null) {
            finish()
            return
        }
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        crop = CropView(this, keyboardAspect()).apply {
            dimAlpha = Prefs.bgDimAlpha(this@BackgroundCropActivity)
        }
        root.addView(crop, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val dimRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        dimRow.addView(TextView(this).apply {
            setText(R.string.pref_bg_dim_title)
            textSize = 14f
        })
        val dimBar = SeekBar(this).apply {
            max = 100
            progress = Prefs.bgDimPct(this@BackgroundCropActivity)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    crop.dimAlpha = value * 230 / 100
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        dimRow.addView(dimBar, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(dimRow)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(4), dp(16), dp(12))
        }
        val cancel = Button(this).apply {
            setText(android.R.string.cancel)
            setOnClickListener { finish() }
        }
        val apply = Button(this).apply {
            setText(android.R.string.ok)
            isEnabled = false
            setOnClickListener { save(dimBar.progress, this) }
        }
        buttons.addView(cancel, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(apply, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons)
        setContentView(root)

        // Decode off the main thread; a phone photo is a multi-second ANR when
        // decoded during onCreate. Apply stays disabled until there is
        // something to apply.
        Thread {
            val bm = try {
                decodeUpright(uri)
            } catch (e: Exception) {
                android.util.Log.w("RimBoard", "background crop decode failed", e)
                null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    bm?.recycle()
                    return@runOnUiThread
                }
                if (bm == null) {
                    Toast.makeText(this, R.string.bg_invalid, Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    crop.setImage(bm)
                    apply.isEnabled = true
                }
            }
        }.start()
    }

    /** Portrait keyboard width : height, from the current settings. */
    private fun keyboardAspect(): Float {
        val dm = resources.displayMetrics
        val rows = 4 + if (Prefs.numberRow(this)) 1 else 0
        val heightDp = 44f + rows * 54f * Prefs.heightFactor(this) + 11f
        val portraitWidthDp = minOf(dm.widthPixels, dm.heightPixels) / dm.density
        return portraitWidthDp / heightDp
    }

    /** The picked image, downsampled to display size and EXIF-upright. Cameras
     *  store the photo unrotated with the real orientation in a tag; without
     *  honouring it, portrait photos arrive here sideways. */
    private fun decodeUpright(uri: Uri): Bitmap? {
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val matrix = Matrix()
        when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f); matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f); matrix.preScale(-1f, 1f)
            }
        }
        if (!matrix.isIdentity) {
            val upright = Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, matrix, true)
            if (upright !== bm) {
                bm.recycle()
                bm = upright
            }
        }
        return bm
    }

    private fun save(dimPct: Int, applyBtn: Button) {
        if (saving) return
        saving = true
        applyBtn.isEnabled = false
        val ctx = applicationContext
        val framed = crop.renderFramed(1600) // on the UI thread: cheap draw of an in-memory bitmap
        Thread {
            val ok = try {
                // Mean luminance of what was actually framed, for the scrim
                // polarity (see Themes.overPhoto).
                val probe = Bitmap.createScaledBitmap(framed, 24, 24, true)
                var luma = 0L
                for (y in 0 until probe.height) for (x in 0 until probe.width) {
                    val p = probe.getPixel(x, y)
                    luma += (299 * (p shr 16 and 0xFF) +
                        587 * (p shr 8 and 0xFF) + 114 * (p and 0xFF)) / 1000
                }
                if (probe !== framed) probe.recycle()
                Prefs.setBgLuma(ctx, (luma / (24 * 24)).toInt())
                Prefs.setBgDimPct(ctx, dimPct)
                val f = java.io.File(UserData.dataDir(ctx), "bg_image.jpg")
                java.io.FileOutputStream(f).use {
                    framed.compress(Bitmap.CompressFormat.JPEG, 88, it)
                }
                BgImageState.version++
                true
            } catch (e: Exception) {
                android.util.Log.w("RimBoard", "background image save failed", e)
                false
            } finally {
                framed.recycle()
            }
            runOnUiThread {
                Toast.makeText(
                    ctx,
                    if (ok) R.string.bg_saved else R.string.bg_invalid,
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }.start()
    }

    /**
     * The image behind a keyboard-shaped window. Drag pans, pinch zooms around
     * the fingers, and the picture is clamped so the window is always fully
     * covered — a crop can never include blank space.
     */
    @SuppressLint("ViewConstructor")
    private class CropView(context: Context, private val aspect: Float) : View(context) {

        var dimAlpha = 110
            set(value) {
                field = value
                invalidate()
            }

        private var bm: Bitmap? = null
        private val frame = RectF()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            alpha = 0xB4
        }
        private val drawMatrix = Matrix()

        // Image placement: uniform scale plus translation, kept as numbers
        // rather than a Matrix so clamping stays arithmetic.
        private var scale = 1f
        private var tx = 0f
        private var ty = 0f
        private var placed = false

        private var lastX = 0f
        private var lastY = 0f
        private var panning = false

        private val scaleDetector = ScaleGestureDetector(context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    val b = bm ?: return true
                    val cover = coverScale(b)
                    val next = (scale * d.scaleFactor).coerceIn(cover, cover * 5f)
                    val f = next / scale
                    // The point between the fingers stays put while everything
                    // scales around it.
                    tx = d.focusX - (d.focusX - tx) * f
                    ty = d.focusY - (d.focusY - ty) * f
                    scale = next
                    clamp(b)
                    invalidate()
                    return true
                }
            })

        fun setImage(b: Bitmap) {
            bm = b
            placed = false
            requestLayout()
            invalidate()
        }

        fun release() {
            bm?.recycle()
            bm = null
        }

        /** Renders exactly the framed region at [outW] pixels wide. */
        fun renderFramed(outW: Int): Bitmap {
            val outH = (outW * frame.height() / frame.width()).toInt().coerceAtLeast(1)
            val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val s = outW / frame.width()
            c.scale(s, s)
            c.translate(-frame.left, -frame.top)
            drawImage(c)
            return out
        }

        private fun coverScale(b: Bitmap): Float =
            maxOf(frame.width() / b.width, frame.height() / b.height)

        /** Keeps the frame fully inside the image: no crop with blank edges. */
        private fun clamp(b: Bitmap) {
            val w = b.width * scale
            val h = b.height * scale
            tx = tx.coerceIn(frame.right - w, frame.left)
            ty = ty.coerceIn(frame.bottom - h, frame.top)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val pad = 16f * resources.displayMetrics.density
            var fw = w - 2 * pad
            var fh = fw / aspect
            val maxH = h - 2 * pad
            if (fh > maxH) {
                fh = maxH
                fw = fh * aspect
            }
            frame.set((w - fw) / 2f, (h - fh) / 2f, (w + fw) / 2f, (h + fh) / 2f)
            placed = false
            invalidate()
        }

        private fun placeIfNeeded() {
            val b = bm ?: return
            if (placed || frame.isEmpty) return
            // Start centred at cover scale — the same framing the blind crop
            // used to bake in, now just the starting point.
            scale = coverScale(b)
            tx = frame.centerX() - b.width * scale / 2f
            ty = frame.centerY() - b.height * scale / 2f
            clamp(b)
            placed = true
        }

        private fun drawImage(c: Canvas) {
            val b = bm ?: return
            drawMatrix.reset()
            drawMatrix.postScale(scale, scale)
            drawMatrix.postTranslate(tx, ty)
            c.drawBitmap(b, drawMatrix, paint)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            placeIfNeeded()
            canvas.drawColor(0xFF16181C.toInt())
            drawImage(canvas)
            // Outside the frame: flattened hard so the window is unmistakable.
            paint.color = 0x99000000.toInt()
            canvas.drawRect(0f, 0f, width.toFloat(), frame.top, paint)
            canvas.drawRect(0f, frame.bottom, width.toFloat(), height.toFloat(), paint)
            canvas.drawRect(0f, frame.top, frame.left, frame.bottom, paint)
            canvas.drawRect(frame.right, frame.top, width.toFloat(), frame.bottom, paint)
            // Inside: the dim the keyboard will actually apply, previewed live.
            paint.color = (dimAlpha shl 24)
            canvas.drawRect(frame, paint)
            stroke.strokeWidth = 2f * resources.displayMetrics.density
            canvas.drawRect(frame, stroke)
            paint.color = Color.WHITE // reset shared paint alpha side effects
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val b = bm ?: return false
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    panning = true
                }
                MotionEvent.ACTION_MOVE -> {
                    // While two fingers are down the scale detector owns the
                    // gesture; single-finger movement pans.
                    if (panning && event.pointerCount == 1 &&
                        !scaleDetector.isInProgress
                    ) {
                        tx += event.x - lastX
                        ty += event.y - lastY
                        clamp(b)
                        invalidate()
                    }
                    lastX = event.x
                    lastY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    panning = false
                    performClick()
                }
                MotionEvent.ACTION_CANCEL -> panning = false
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::crop.isInitialized) crop.release()
    }
}
