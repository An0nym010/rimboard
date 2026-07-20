package com.rimboard.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.SparseArray
import android.view.MotionEvent
import com.rimboard.keyboard.Haptics
import android.view.View
import android.view.ViewConfiguration
import com.rimboard.keyboard.model.Codes
import com.rimboard.keyboard.model.Key
import com.rimboard.keyboard.model.KeyType
import com.rimboard.keyboard.model.KeyboardLayout
import com.rimboard.keyboard.model.LayoutKind
import com.rimboard.keyboard.theme.KeyboardTheme
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

@SuppressLint("ViewConstructor")
class KeyboardView(context: Context) : View(context) {

    interface Listener {
        fun onKeyPressed(key: Key)
        fun onKeyRepeated(key: Key)
        fun onPopupKeySelected(key: Key)
        fun onCursorMove(steps: Int)
        fun onKeyDownFeedback(key: Key)
        fun onLanguageSwipe(direction: Int)
        fun onHideKeyboard()
        fun onSpaceLongPress()
        fun onGlideComplete(sequence: String)
        fun onOneHandedChanged(mode: Int)
        fun onBackspaceWord()
        fun onBackspaceWordRestore()
    }

    enum class ShiftState { NONE, AUTO, MANUAL, CAPSLOCK }

    var listener: Listener? = null

    var layout: KeyboardLayout? = null
        set(value) {
            // Cross-fade the labels on a plane change (ABC ↔ ?123 ↔ =\<), but
            // not on the very first layout, which would fade in from nothing.
            if (field != null && value !== field) {
                layoutFadeAt = SystemClock.uptimeMillis()
            }
            field = value
            shiftedLabelCache.clear()
            if (width > 0) computeBounds(width)
            requestLayout()
            invalidate()
        }

    private var layoutFadeAt = 0L

    /** Uppercased key labels, cached so the draw loop never allocates strings. */
    private val shiftedLabelCache = HashMap<Key, String>()

    var theme: KeyboardTheme? = null
        set(value) {
            field = value
            invalidate()
        }

    var shiftState: ShiftState = ShiftState.NONE
        set(value) {
            field = value
            invalidate()
        }

    var showDigitHints: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var previewEnabled: Boolean = true
    var spaceCursorEnabled: Boolean = true
    var glideEnabled: Boolean = true
    var hapticFeedback: Boolean = true
    var repeatInitialMs: Long = 300L
    var repeatIntervalMs: Long = 50L

    var spaceLabel: String = ""
        set(value) {
            field = value
            invalidate()
        }

    /** Language name flashed on the spacebar after a switch, then fading out. */
    private var spaceFlash = ""
    private var spaceFlashAt = 0L

    private companion object {
        /** Full-strength hold before the flashed name starts to fade. */
        const val FLASH_HOLD_MS = 650L
        const val FLASH_FADE_MS = 350f
    }

    /** Shows [text] on the spacebar briefly — Gboard-style switch feedback. */
    fun flashSpaceLabel(text: String) {
        spaceFlash = text
        spaceFlashAt = SystemClock.uptimeMillis()
        invalidate()
    }

    var enterLabel: String = "\u21B5"
        set(value) {
            field = value
            invalidate()
        }

    var incognito: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var keyHeightFactor: Float = 1f
        set(value) {
            field = value
            requestLayout()
        }

    /** 0 = off, 1 = anchored left, 2 = anchored right. Forced off in landscape. */
    var oneHanded: Int = 0
        set(value) {
            field = value
            if (width > 0) computeBounds(width)
            invalidate()
        }

    // ---------- geometry ----------

    private class KeyBounds(val key: Key) {
        var x = 0f
        var y = 0f
        var w = 0f
        var h = 0f
        fun contains(px: Float, py: Float) = px >= x && px < x + w && py >= y && py < y + h
        fun centerX() = x + w / 2f
    }

    private val bounds = ArrayList<KeyBounds>()

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private fun wordTick() {
        if (hapticFeedback) Haptics.tap(this)
    }

    private val gapX = dp(4f)
    private val gapY = dp(5f)
    private val baseSidePad = dp(3f)
    private val topPad = dp(6f)
    private val bottomPad = dp(5f)
    private val keyRadius = dp(11f)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26000000 }
    private val shadowRect = RectF()
    private val bgPaint = Paint()
    private var bgShaderH = 0
    private var bgShaderColor = 1
    private val lastPressed = HashSet<Key>()
    private val nowPressed = HashSet<Key>()
    private val releaseAnim = HashMap<Key, Long>()

    private var bgBm: android.graphics.Bitmap? = null
    private var bgBmStamp = -1

    private fun drawBackgroundImage(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val f = java.io.File(
            com.rimboard.keyboard.engine.UserData.dataDir(context), "bg_image.jpg")
        if (!f.exists()) {
            bgBm = null
            return
        }
        val stamp = BgImageState.version * 31 + width * 7 + height
        if (bgBm == null || bgBmStamp != stamp) {
            bgBm = try {
                decodeCentered(f, width, height)
            } catch (_: Exception) {
                null
            }
            bgBmStamp = stamp
        }
        bgBm?.let { bm ->
            canvas.drawBitmap(bm, 0f, 0f, null)
            keyPaint.color = (bgDimAlpha shl 24)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), keyPaint)
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

    private fun mixColor(a: Int, b: Int, f: Float): Int {
        if (f <= 0f) return a
        if (f >= 1f) return b
        fun ch(s: Int) = (((a shr s and 0xFF) * (1 - f)) + ((b shr s and 0xFF) * f)).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rectF = RectF()

    // ---------- touch state ----------

    private class PointerState(val kb: KeyBounds, val downX: Float, val downY: Float) {
        val downTime = SystemClock.uptimeMillis()
        var cancelled = false
        var cursorMode = false
        var langSwiped = false
        var cursorLastX = 0f
        var popupOpen = false
        var popupIndex = 0
        var longPress: Runnable? = null
        var repeat: Runnable? = null
        var handledOnDown = false
        var glide = false
        var wordDelete = false
        var wordDeleteLastX = 0f
        var wordsDeleted = 0
        val trail = ArrayList<Float>()
        val glideSeq = StringBuilder()
    }

    private val pointers = SparseArray<PointerState>()
    private val uiHandler = Handler(Looper.getMainLooper())
    var longPressTimeoutMs =
        minOf(ViewConfiguration.getLongPressTimeout(), 350).toLong()
    var labelScale = 1f
    var showTrail = true
    var bgDimAlpha = 110
    var keyBorders = true
    var narrowGaps = false
    var splitFraction = 0f
    var sidePadPct = 0
    var bottomPadPct = 0
    var customTypeface: Typeface? = null
    var spaceSwipeH = 1      // 0 none, 1 cursor, 2 language
    var spaceSwipeV = 0      // 0 none, 1 hide keyboard
    var spaceLongPressMode = 1 // 0 none, 1 handled by listener
    var numpadOnSymbolsLongPress = false
    var tldPopups = false

    /**
     * Optional probabilistic arbiter for taps that land near a letter-key
     * boundary (adaptive touch targeting). Receives the candidate letters and
     * their spatial log-likelihoods (Gaussian around each key centre); returns
     * the index of the chosen candidate, or -1 to keep the primary key. Only
     * consulted when a touch falls inside more than one letter key's slightly
     * expanded bounds, so a tap comfortably inside a key can never be diverted.
     */
    var tapArbiter: ((candidates: CharArray, spatialLogP: DoubleArray) -> Int)? = null

    private var popupOwner: PointerState? = null
    private var popupKeys: List<Key> = emptyList()
    private val popupRect = RectF()
    private var popupCell = 0f
    private var popupShownAt = 0L
    private var previewKb: KeyBounds? = null
        set(value) {
            val old = field
            if (value !== old) {
                if (value != null) {
                    previewShownAt = SystemClock.uptimeMillis()
                    // Replaced rather than dismissed: during fast typing the next
                    // key is already down, and fading the old bubble out beneath
                    // the new one would just look like clutter.
                    previewOutKb = null
                } else if (old != null) {
                    previewOutKb = old
                    previewOutAt = SystemClock.uptimeMillis()
                }
            }
            field = value
        }
    private var previewShownAt = 0L

    /** The bubble left behind when a key is released, shrinking away. */
    private var previewOutKb: KeyBounds? = null
    private var previewOutAt = 0L
    private val switchBtn = RectF()
    private val expandBtn = RectF()

    private fun effectiveOneHanded(): Int {
        val landscape =
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return if (landscape) 0 else oneHanded
    }

    // ---------- measurement ----------

    private fun rowHeightPx(): Float {
        val landscape =
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val base = dp(54f) * keyHeightFactor
        return if (landscape) base * 0.78f else base
    }

    fun measureKeyboardHeight(): Int {
        val rows = layout?.rows?.size ?: 4
        return (((rows * rowHeightPx() + (rows - 1) * gapY + topPad + bottomPad).toInt()) + dp(4f) * bottomPadPct).toInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, measureKeyboardHeight())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeBounds(w)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Re-read each time the keyboard is shown: the setting can change while
        // the process is alive, and a keyboard process is long-lived.
        Anim.durationScale = try {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
        } catch (_: Exception) {
            1f // setting unavailable: animate normally
        }
    }

    private fun computeBounds(w: Int) {
        bounds.clear()
        switchBtn.setEmpty()
        expandBtn.setEmpty()
        val lay = layout ?: return
        val mode = effectiveOneHanded()
        val sidePad = baseSidePad + w * sidePadPct / 100f
        val contentW = if (mode == 0) w.toFloat() else w * 0.82f
        val offsetX = if (mode == 2) w - contentW else 0f
        val rowH = rowHeightPx()
        val innerW = contentW - 2 * sidePad
        val unit = innerW / lay.unitsPerRow
        var y = topPad
        for ((rowIndex, row) in lay.rows.withIndex()) {
            var rowUnits = 0f
            for (key in row.keys) rowUnits += key.width
            val splitting = splitFraction > 0f && rowIndex < lay.rows.size - 1
            val s = if (splitting) 1f - splitFraction else 1f
            val gapPx = if (splitting) innerW * splitFraction else 0f
            var x = offsetX + sidePad + (innerW - rowUnits * unit * s - gapPx) / 2f
            var acc = 0f
            var gapInserted = false
            for (key in row.keys) {
                if (splitting && !gapInserted && acc >= rowUnits / 2f - 0.01f) {
                    x += gapPx
                    gapInserted = true
                }
                val kb = KeyBounds(key)
                kb.x = x
                kb.y = y
                kb.w = key.width * unit * s
                kb.h = rowH
                bounds.add(kb)
                x += kb.w
                acc += key.width
            }
            y += rowH + gapY
        }
        if (mode != 0) {
            val bottom = y - gapY
            val gLeft = if (mode == 2) dp(6f) else contentW + dp(6f)
            val gRight = if (mode == 2) w - contentW - dp(6f) else w - dp(6f)
            val mid = topPad + (bottom - topPad) / 2f
            switchBtn.set(gLeft, topPad + dp(8f), gRight, mid - dp(6f))
            expandBtn.set(gLeft, mid + dp(6f), gRight, bottom - dp(8f))
        }
    }

    // ---------- drawing ----------

    override fun onDraw(canvas: Canvas) {
        val t = theme ?: return
        val lay = layout ?: return
        if (bgPaint.shader == null || bgShaderH != height || bgShaderColor != t.background) {
            bgShaderColor = t.background
            bgShaderH = height
            val bottom = mixColor(t.background, 0xFF000000.toInt(), if (t.isDark) 0.20f else 0.07f)
            bgPaint.shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(), t.background, bottom, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        val tf = customTypeface ?: Typeface.DEFAULT
        if (textPaint.typeface !== tf) {
            textPaint.typeface = tf
            hintPaint.typeface = tf
        }
        drawBackgroundImage(canvas)
        nowPressed.clear()
        val now = SystemClock.uptimeMillis()
        var needsAnimFrame = false

        // Label cross-fade after a plane switch. Key caps stay put; only what
        // is written on them changes, so only the writing fades.
        val lf = Anim.progress(now, layoutFadeAt, Anim.LAYOUT_FADE_MS)
        val labelAlpha = (255f * Anim.easeOut(lf)).toInt()
        if (lf < 1f) needsAnimFrame = true

        for (kb in bounds) {
            val key = kb.key
            val pressed = isPressedKb(kb)

            if (pressed) nowPressed.add(key)
            var press = if (pressed) 1f else 0f
            if (!pressed) {
                val rel = releaseAnim[key]
                if (rel != null) {
                    val p = Anim.progress(now, rel, Anim.PRESS_RELEASE_MS)
                    if (p < 1f) {
                        // Runs the accelerating curve backwards, so the key
                        // springs back quickly and then settles.
                        press = Anim.easeIn(1f - p)
                    } else releaseAnim.remove(key)
                }
            }
            val bgColor = when {
                key.type == KeyType.ENTER -> t.accent
                key.code == Codes.SHIFT && shiftState == ShiftState.CAPSLOCK -> t.accent
                key.type == KeyType.FUNCTION -> mixColor(t.keyBgFunc, t.keyBgPressed, press)
                else -> mixColor(t.keyBg, t.keyBgPressed, press)
            }
            keyPaint.color = bgColor
            val gap = if (narrowGaps) gapX * 0.45f else gapX
            rectF.set(kb.x + gap / 2f, kb.y, kb.x + kb.w - gap / 2f, kb.y + kb.h)
            if (press > 0f) {
                rectF.inset(rectF.width() * 0.018f * press, rectF.height() * 0.018f * press)
            }
            val radius = if (key.type == KeyType.ENTER) rectF.height() / 2f else keyRadius
            if (keyBorders || press > 0f || key.type != KeyType.CHARACTER) {
                shadowRect.set(rectF)
                shadowRect.offset(0f, dp(1.4f))
                canvas.drawRoundRect(shadowRect, radius, radius, shadowPaint)
                canvas.drawRoundRect(rectF, radius, radius, keyPaint)
            }
            if (press > 0f && (key.type == KeyType.ENTER ||
                    (key.code == Codes.SHIFT && shiftState == ShiftState.CAPSLOCK))
            ) {
                keyPaint.color = ((0x33 * press).toInt() shl 24)
                canvas.drawRoundRect(rectF, radius, radius, keyPaint)
            }

            // Radial press highlight: a soft circle grows from the touch point
            // for the first ~160 ms of a press (Telegram-style micro-feedback).
            if (pressed) {
                val ps = pressedPointer(kb)
                if (ps != null && !ps.glide && !ps.cursorMode) {
                    val rp = Anim.progress(now, ps.downTime, Anim.RIPPLE_MS)
                    if (rp < 1f) {
                        val ease = Anim.easeOut(rp)
                        val maxR = if (kb.w > kb.h) kb.w else kb.h
                        val alpha = (0x2A * (1f - 0.6f * ease)).toInt()
                        keyPaint.color = (alpha shl 24) or
                            (if (t.isDark) 0xFFFFFF else 0x000000)
                        canvas.save()
                        canvas.clipRect(rectF)
                        canvas.drawCircle(
                            ps.downX, ps.downY, maxR * (0.30f + 0.65f * ease), keyPaint
                        )
                        canvas.restore()
                        needsAnimFrame = true
                    }
                }
            }

            var label = displayLabel(key, lay.locale)
            // Freshly switched language: its name rides the spacebar, holds,
            // then fades back into the ordinary label.
            var flashAlpha = -1
            if (key.type == KeyType.SPACE && spaceFlash.isNotEmpty() && !incognito) {
                val fp = Anim.progress(now, spaceFlashAt + FLASH_HOLD_MS, FLASH_FADE_MS)
                if (fp < 1f) {
                    label = spaceFlash
                    flashAlpha = (255f * (1f - Anim.easeIn(fp))).toInt()
                    needsAnimFrame = true
                } else spaceFlash = ""
            }
            if (label.isNotEmpty()) {
                textPaint.color = when {
                    key.type == KeyType.ENTER -> t.onAccent
                    key.code == Codes.SHIFT && shiftState == ShiftState.CAPSLOCK -> t.onAccent
                    key.code == Codes.SHIFT && shiftState != ShiftState.NONE -> t.accent
                    key.type == KeyType.SPACE -> t.keyHint
                    else -> t.keyText
                }
                val keyIcon = Icons.forCode(key.code) ?: Icons.forLabel(label)
                if (keyIcon != null) {
                    val ic = (labelAlpha shl 24) or (textPaint.color and 0x00FFFFFF)
                    Icons.draw(canvas, keyIcon, rectF.centerX(), rectF.centerY(),
                        kb.h * 0.42f, ic)
                } else {
                textPaint.alpha = labelAlpha
                if (flashAlpha >= 0) {
                    // The flashed name reads at full text colour, not hint grey.
                    textPaint.color = t.keyText
                    textPaint.alpha = minOf(labelAlpha, flashAlpha)
                }
                textPaint.textSize = labelScale * when {
                    key.type == KeyType.SPACE ->
                        if (incognito) kb.h * 0.38f
                        else if (flashAlpha >= 0) kb.h * 0.34f
                        else kb.h * 0.26f
                    key.type == KeyType.ENTER -> kb.h * 0.38f
                    key.type == KeyType.CHARACTER -> kb.h * 0.42f
                    label.length > 2 -> kb.h * 0.28f
                    else -> kb.h * 0.40f
                }
                val cy = rectF.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f
                canvas.drawText(label, rectF.centerX(), cy, textPaint)
                textPaint.alpha = 255
                }
            }

            val hint = key.hint
            if (hint != null && showDigitHints && key.type == KeyType.CHARACTER) {
                hintPaint.color = t.keyHint
                hintPaint.alpha = hintPaint.alpha * labelAlpha / 255
                hintPaint.textSize = kb.h * 0.22f
                canvas.drawText(hint, rectF.right - dp(5f), rectF.top + kb.h * 0.30f, hintPaint)
                hintPaint.alpha = 255
            }
        }

        for (k in lastPressed) if (k !in nowPressed) releaseAnim[k] = now
        lastPressed.clear()
        lastPressed.addAll(nowPressed)
        if (releaseAnim.isNotEmpty() || needsAnimFrame) postInvalidateOnAnimation()

        drawOneHandedControls(canvas, t)
        drawTrails(canvas, t)
        drawPopup(canvas, t)
        drawPreview(canvas, t)
    }

    private fun drawOneHandedControls(canvas: Canvas, t: KeyboardTheme) {
        if (switchBtn.isEmpty) return
        keyPaint.color = t.keyBgFunc
        canvas.drawRoundRect(switchBtn, keyRadius, keyRadius, keyPaint)
        canvas.drawRoundRect(expandBtn, keyRadius, keyRadius, keyPaint)
        textPaint.color = t.keyText
        textPaint.textSize = dp(18f)
        val arrow = if (effectiveOneHanded() == 1) "\u25B6" else "\u25C0"
        var cy = switchBtn.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(arrow, switchBtn.centerX(), cy, textPaint)
        cy = expandBtn.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText("\u21D4", expandBtn.centerX(), cy, textPaint)
    }

    private fun drawTrails(canvas: Canvas, t: KeyboardTheme) {
        for (p in 0 until pointers.size()) {
            val ps = pointers.valueAt(p)
            if (!showTrail || !ps.glide || ps.trail.size < 4) continue
            trailPaint.color = t.accent
            trailPaint.strokeWidth = dp(5f)
            trailPaint.strokeCap = Paint.Cap.ROUND
            val n = ps.trail.size / 2
            var px = ps.trail[0]
            var py = ps.trail[1]
            for (j in 1 until n) {
                val x = ps.trail[j * 2]
                val y = ps.trail[j * 2 + 1]
                trailPaint.alpha = 40 + (180 * j) / n
                canvas.drawLine(px, py, x, y, trailPaint)
                px = x
                py = y
            }
        }
    }

    private fun displayLabel(key: Key, locale: Locale): String {
        return when (key.code) {
            Codes.SPACE -> if (incognito) "\uD83D\uDD76" else spaceLabel
            Codes.ENTER -> enterLabel
            Codes.SHIFT -> "\u21E7"
            else -> {
                if (key.type == KeyType.CHARACTER && key.label.length == 1 &&
                    key.label[0].isLetter() && shiftState != ShiftState.NONE
                ) shiftedLabelCache.getOrPut(key) { key.label.uppercase(locale) }
                else key.label
            }
        }
    }

    private fun popupDisplayLabel(k: Key): String {
        val loc = layout?.locale ?: Locale.getDefault()
        return if (k.code > 0 && k.label.length == 1 && k.label[0].isLetter() &&
            shiftState != ShiftState.NONE
        ) k.label.uppercase(loc) else k.label
    }

    private fun drawPopup(canvas: Canvas, t: KeyboardTheme) {
        val now = SystemClock.uptimeMillis()

        // A popup that was just dismissed shrinks back into its key instead of
        // vanishing between frames, mirroring how it arrived.
        if (popupOutKeys.isNotEmpty()) {
            val p = Anim.progress(now, popupOutAt, Anim.POPUP_OUT_MS)
            if (p >= 1f) {
                popupOutKeys = emptyList()
            } else {
                val e = Anim.easeIn(p)
                val layer = canvas.saveLayerAlpha(
                    popupOutRect.left - dp(8f), popupOutRect.top - dp(8f),
                    popupOutRect.right + dp(8f), popupOutRect.bottom + dp(10f),
                    (255f * (1f - e)).toInt()
                )
                canvas.scale(
                    1f - 0.14f * e, 1f - 0.14f * e,
                    popupOutRect.centerX(), popupOutRect.bottom
                )
                drawPopupBody(canvas, t, popupOutRect, popupOutKeys, popupOutIndex, popupOutCell)
                canvas.restoreToCount(layer)
                postInvalidateOnAnimation()
            }
        }

        val owner = popupOwner ?: return
        if (!owner.popupOpen || popupKeys.isEmpty()) return
        // Entrance: scale up from the key with a slight overshoot, Telegram-style.
        val et = Anim.progress(now, popupShownAt, Anim.POPUP_IN_MS)
        val scale = 0.82f + 0.18f * Anim.overshoot(et)
        val restore = canvas.save()
        canvas.scale(scale, scale, popupRect.centerX(), popupRect.bottom)
        if (et < 1f) postInvalidateOnAnimation()
        drawPopupBody(canvas, t, popupRect, popupKeys, owner.popupIndex, popupCell)
        canvas.restoreToCount(restore)
    }

    /** Renders one popup: shared by the live popup and its exit animation. */
    private fun drawPopupBody(
        canvas: Canvas, t: KeyboardTheme,
        rect: RectF, keys: List<Key>, selected: Int, cell: Float
    ) {
        shadowRect.set(rect)
        shadowRect.offset(0f, dp(2f))
        canvas.drawRoundRect(shadowRect, keyRadius, keyRadius, shadowPaint)
        keyPaint.color = t.previewBg
        canvas.drawRoundRect(rect, keyRadius, keyRadius, keyPaint)
        textPaint.textSize = rect.height() * 0.42f
        for (i in keys.indices) {
            val left = rect.left + i * cell
            if (i == selected) {
                keyPaint.color = t.accent
                rectF.set(
                    left + dp(2f), rect.top + dp(2f),
                    left + cell - dp(2f), rect.bottom - dp(2f)
                )
                canvas.drawRoundRect(rectF, keyRadius * 0.75f, keyRadius * 0.75f, keyPaint)
            }
            textPaint.color = if (i == selected) t.onAccent else t.keyText
            val pIcon = Icons.forCode(keys[i].code) ?: Icons.forLabel(keys[i].label)
            if (pIcon != null) {
                Icons.draw(canvas, pIcon, left + cell / 2f, rect.centerY(),
                    rect.height() * 0.46f, textPaint.color)
            } else {
                val cy = rect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f
                canvas.drawText(popupDisplayLabel(keys[i]), left + cell / 2f, cy, textPaint)
            }
        }
    }

    /** Snapshot of a dismissed popup, drawn shrinking away. */
    private val popupOutRect = RectF()
    private var popupOutKeys: List<Key> = emptyList()
    private var popupOutIndex = -1
    private var popupOutCell = 0f
    private var popupOutAt = 0L

    private fun snapshotPopupOut() {
        if (popupKeys.isEmpty()) return
        popupOutRect.set(popupRect)
        popupOutKeys = popupKeys
        popupOutIndex = popupOwner?.popupIndex ?: -1
        popupOutCell = popupCell
        popupOutAt = SystemClock.uptimeMillis()
    }

    private fun drawPreview(canvas: Canvas, t: KeyboardTheme) {
        if (popupOwner?.popupOpen == true) return
        val now = SystemClock.uptimeMillis()

        // The bubble being dismissed draws first, so a new one always sits on
        // top of it in the rare frame where both are visible.
        val out = previewOutKb
        if (out != null) {
            val p = Anim.progress(now, previewOutAt, Anim.PREVIEW_OUT_MS)
            if (p >= 1f) {
                previewOutKb = null
            } else {
                // Mirrors the entrance in reverse: shrinks back toward the key
                // while fading, instead of blinking out of existence.
                val e = Anim.easeIn(p)
                drawPreviewBubble(canvas, t, out, 1f - 0.15f * e, 1f - e)
                postInvalidateOnAnimation()
            }
        }

        val kb = previewKb ?: return
        val et = Anim.progress(now, previewShownAt, Anim.PREVIEW_IN_MS)
        drawPreviewBubble(canvas, t, kb, 0.85f + 0.15f * Anim.easeOut(et), 1f)
        if (et < 1f) postInvalidateOnAnimation()
    }

    /** Draws [kb]'s preview bubble at [scale], faded to [alpha] (0..1). */
    private fun drawPreviewBubble(
        canvas: Canvas, t: KeyboardTheme, kb: KeyBounds, scale: Float, alpha: Float
    ) {
        if (Icons.forCode(kb.key.code) != null || Icons.forLabel(kb.key.label) != null) return
        val w = max(kb.w * 1.15f, dp(46f))
        val h = kb.h * 1.05f
        var left = kb.centerX() - w / 2f
        val maxLeft = width - w - dp(2f)
        left = if (maxLeft < dp(2f)) dp(2f) else left.coerceIn(dp(2f), maxLeft)
        var top = kb.y - h - dp(8f)
        if (top < dp(2f)) top = dp(2f)
        rectF.set(left, top, left + w, top + h)
        val a = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        val restore = canvas.save()
        canvas.scale(scale, scale, rectF.centerX(), rectF.bottom)
        shadowRect.set(rectF)
        shadowRect.offset(0f, dp(2f))
        // These paints are shared across the whole frame, so every alpha
        // touched here is put back before returning.
        val shadowAlpha = shadowPaint.alpha
        shadowPaint.alpha = shadowAlpha * a / 255
        canvas.drawRoundRect(shadowRect, keyRadius, keyRadius, shadowPaint)
        shadowPaint.alpha = shadowAlpha
        keyPaint.color = t.previewBg
        keyPaint.alpha = a
        canvas.drawRoundRect(rectF, keyRadius, keyRadius, keyPaint)
        keyPaint.alpha = 255
        textPaint.color = t.keyText
        textPaint.alpha = a
        textPaint.textSize = h * 0.5f
        val cy = rectF.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(displayLabel(kb.key, layout?.locale ?: Locale.getDefault()),
            rectF.centerX(), cy, textPaint)
        textPaint.alpha = 255
        canvas.restoreToCount(restore)
    }

    private fun isPressedKb(kb: KeyBounds): Boolean {
        for (i in 0 until pointers.size()) {
            val ps = pointers.valueAt(i)
            if (ps.kb === kb && !ps.cancelled) return true
        }
        return false
    }

    private fun pressedPointer(kb: KeyBounds): PointerState? {
        for (i in 0 until pointers.size()) {
            val ps = pointers.valueAt(i)
            if (ps.kb === kb && !ps.cancelled) return ps
        }
        return null
    }

    // ---------- touch ----------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                onPointerDown(event.getPointerId(idx), event.getX(idx), event.getY(idx))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    onPointerMove(event.getPointerId(i), event.getX(i), event.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                onPointerUp(event.getPointerId(idx))
            }
            MotionEvent.ACTION_CANCEL -> cancelAll()
        }
        return true
    }

    private fun onPointerDown(pid: Int, x: Float, y: Float) {
        if (handleGutterTap(x, y)) return
        // Rollover typing: when a new finger lands, flush every still-held key
        // so keys commit in press order, not release order (GBoard behavior).
        for (i in 0 until pointers.size()) {
            val held = pointers.valueAt(i)
            if (held.cancelled || held.cursorMode) continue
            if (held.popupOpen) {
                commitPopup(held)
            } else if (held.glide) {
                finishGlide(held)
            } else if (held.handledOnDown) {
                cancelTimers(held) // stop backspace auto-repeat
            } else {
                cancelTimers(held)
                held.handledOnDown = true
                if (previewKb === held.kb) previewKb = null
                listener?.onKeyPressed(held.kb.key)
            }
        }
        pointers.get(pid)?.let { cancelTimers(it) } // safety: recycled pointer id
        val kb = arbitrate(keyAt(x, y) ?: return, x, y)
        val ps = PointerState(kb, x, y)
        pointers.put(pid, ps)
        listener?.onKeyDownFeedback(kb.key)
        if (previewEnabled && kb.key.type == KeyType.CHARACTER) previewKb = kb

        if (kb.key.repeatable) {
            listener?.onKeyPressed(kb.key)
            ps.handledOnDown = true
            val r = object : Runnable {
                override fun run() {
                    listener?.onKeyRepeated(kb.key)
                    uiHandler.postDelayed(this, repeatIntervalMs)
                }
            }
            ps.repeat = r
            uiHandler.postDelayed(r, repeatInitialMs)
        } else if (kb.key.popup.isNotEmpty()) {
            val lp = Runnable { openPopup(ps) }
            ps.longPress = lp
            uiHandler.postDelayed(lp, longPressTimeoutMs)
        }
        invalidate()
    }

    private fun onPointerMove(pid: Int, x: Float, y: Float) {
        val ps = pointers.get(pid) ?: return
        if (ps.cancelled) return

        if (ps.kb.key.code == Codes.SPACE && !ps.cursorMode && !ps.cancelled) {
            if (spaceSwipeH == 2 && !ps.langSwiped &&
                abs(x - ps.downX) > ps.kb.w * 0.3f
            ) {
                ps.langSwiped = true
                ps.cancelled = true
                cancelTimers(ps)
                if (previewKb === ps.kb) previewKb = null
                listener?.onLanguageSwipe(if (x > ps.downX) 1 else -1)
                invalidate()
                return
            }
            if (spaceSwipeV == 1 && (y - ps.downY) > ps.kb.h * 0.9f) {
                ps.cancelled = true
                cancelTimers(ps)
                if (previewKb === ps.kb) previewKb = null
                listener?.onHideKeyboard()
                invalidate()
                return
            }
        }
        if (ps.kb.key.code == Codes.SPACE && spaceSwipeH == 1) {
            if (!ps.cursorMode && abs(x - ps.downX) > dp(20f)) {
                ps.cursorMode = true
                ps.cursorLastX = x
                cancelTimers(ps)
                if (previewKb === ps.kb) previewKb = null
                invalidate()
            }
            if (ps.cursorMode) {
                val step = dp(14f)
                var steps = 0
                var last = ps.cursorLastX
                while (x - last > step) { steps++; last += step }
                while (last - x > step) { steps--; last -= step }
                if (steps != 0) {
                    ps.cursorLastX = last
                    listener?.onCursorMove(steps)
                }
                return
            }
        }

        // Swipe left on backspace: delete whole words (one per step of travel)
        if (ps.kb.key.code == Codes.BACKSPACE) {
            if (!ps.wordDelete && x - ps.downX < -dp(30f)) {
                ps.wordDelete = true
                cancelTimers(ps)
                ps.wordDeleteLastX = x
                ps.wordsDeleted = 1
                wordTick()
                listener?.onBackspaceWord()
            }
            if (ps.wordDelete) {
                val step = dp(40f)
                while (ps.wordDeleteLastX - x > step) {
                    ps.wordDeleteLastX -= step
                    ps.wordsDeleted++
                    wordTick()
                    listener?.onBackspaceWord()
                }
                // Slide back to the right to restore deleted words.
                while (x - ps.wordDeleteLastX > step && ps.wordsDeleted > 0) {
                    ps.wordDeleteLastX += step
                    ps.wordsDeleted--
                    wordTick()
                    listener?.onBackspaceWordRestore()
                }
                return
            }
        }

        if (ps.popupOpen) {
            val n = popupKeys.size
            if (n > 0) {
                val i = ((x - popupRect.left) / popupCell).toInt().coerceIn(0, n - 1)
                if (i != ps.popupIndex) {
                    ps.popupIndex = i
                    invalidate()
                }
            }
            return
        }

        // glide typing: a letter-key press that travels becomes a swipe
        val glideCapable = glideEnabled && layout?.kind == LayoutKind.MAIN &&
            isGlideKey(ps.kb.key) && !ps.handledOnDown
        if (glideCapable && !ps.glide &&
            (abs(x - ps.downX) > dp(14f) || abs(y - ps.downY) > dp(14f))
        ) {
            ps.glide = true
            cancelTimers(ps)
            if (previewKb === ps.kb) previewKb = null
            ps.glideSeq.append(ps.kb.key.label)
            ps.trail.add(ps.downX)
            ps.trail.add(ps.downY)
        }
        if (ps.glide) {
            ps.trail.add(x)
            ps.trail.add(y)
            while (ps.trail.size > 240) {
                ps.trail.removeAt(0)
                ps.trail.removeAt(0)
            }
            val kb = keyAt(x, y)
            if (kb != null && isGlideKey(kb.key)) {
                val ch = kb.key.label[0]
                if (ps.glideSeq.isEmpty() || ps.glideSeq[ps.glideSeq.length - 1] != ch) {
                    ps.glideSeq.append(ch)
                }
            }
            invalidate()
            return
        }

        // slide far off the key => cancel it (glide-capable keys never cancel)
        if (!glideCapable &&
            (x < ps.kb.x - dp(12f) || x > ps.kb.x + ps.kb.w + dp(12f) ||
                y < ps.kb.y - dp(18f) || y > ps.kb.y + ps.kb.h + dp(18f))
        ) {
            ps.cancelled = true
            cancelTimers(ps)
            if (previewKb === ps.kb) previewKb = null
            invalidate()
        }
    }

    private fun onPointerUp(pid: Int) {
        val ps = pointers.get(pid) ?: return
        pointers.remove(pid)
        cancelTimers(ps)
        if (previewKb === ps.kb) previewKb = null
        when {
            ps.cancelled -> {}
            ps.glide -> finishGlide(ps)
            ps.popupOpen -> commitPopup(ps)
            ps.cursorMode -> {}
            ps.handledOnDown -> {}
            else -> listener?.onKeyPressed(ps.kb.key)
        }
        if (popupOwner === ps) {
            snapshotPopupOut()
            popupOwner = null
            popupKeys = emptyList()
        }
        invalidate()
    }

    private fun handleGutterTap(x: Float, y: Float): Boolean {
        val mode = effectiveOneHanded()
        if (mode == 0) return false
        if (switchBtn.contains(x, y)) {
            oneHanded = if (mode == 1) 2 else 1
            listener?.onOneHandedChanged(oneHanded)
            return true
        }
        if (expandBtn.contains(x, y)) {
            oneHanded = 0
            listener?.onOneHandedChanged(0)
            return true
        }
        // dead gutter space: swallow the touch instead of snapping to a key
        val contentW = width * 0.82f
        return if (mode == 2) x < width - contentW else x > contentW
    }

    private fun isGlideKey(key: Key): Boolean =
        key.type == KeyType.CHARACTER && key.label.length == 1 && key.label[0].isLetter()

    private fun finishGlide(ps: PointerState) {
        if (ps.glide && ps.glideSeq.length >= 2) {
            listener?.onGlideComplete(ps.glideSeq.toString())
        }
        ps.glide = false
        ps.handledOnDown = true
        ps.trail.clear()
        invalidate()
    }

    private fun commitPopup(ps: PointerState) {
        if (ps.popupOpen && popupKeys.isNotEmpty()) {
            val i = ps.popupIndex.coerceIn(0, popupKeys.size - 1)
            listener?.onPopupKeySelected(popupKeys[i])
        }
        ps.popupOpen = false
        ps.cancelled = true
        if (popupOwner === ps) {
            snapshotPopupOut()
            popupOwner = null
            popupKeys = emptyList()
        }
        invalidate()
    }

    private val tldKeys = listOf(
        ".com", ".net", ".org", ".io", ".co", ".app", ".dev", ".tr", ".edu", ".gov"
    ).map { Key(0, it, type = KeyType.CHARACTER) }

    private fun openPopup(ps: PointerState) {
        if (ps.cancelled || ps.cursorMode || ps.glide) return
        if (ps.kb.key.code == Codes.SPACE) {
            if (spaceLongPressMode != 0) {
                ps.cancelled = true
                if (previewKb === ps.kb) previewKb = null
                listener?.onSpaceLongPress()
                invalidate()
            }
            return
        }
        if (numpadOnSymbolsLongPress && ps.kb.key.code == Codes.MODE_SYM) {
            ps.cancelled = true
            if (previewKb === ps.kb) previewKb = null
            listener?.onKeyPressed(Key(Codes.NUMPAD, "123#", type = KeyType.FUNCTION))
            invalidate()
            return
        }
        val baseKeys = ps.kb.key.popup
        val keys = if (tldPopups && ps.kb.key.label == ".") tldKeys else baseKeys
        if (keys.isEmpty()) return
        popupOwner = ps
        popupKeys = keys
        ps.popupOpen = true
        popupShownAt = SystemClock.uptimeMillis()
        previewKb = null
        val kb = ps.kb
        val cellW = max(kb.w, dp(44f))
        var total = cellW * keys.size
        val maxW = width - dp(8f)
        val cell = if (total > maxW) maxW / keys.size else cellW
        total = cell * keys.size
        var left = kb.centerX() - total / 2f
        val maxLeft = width - total - dp(4f)
        left = if (maxLeft < dp(4f)) dp(4f) else left.coerceIn(dp(4f), maxLeft)
        val h = kb.h * 1.1f
        var top = kb.y - h - dp(8f)
        if (top < dp(2f)) top = dp(2f)
        popupRect.set(left, top, left + total, top + h)
        popupCell = cell
        ps.popupIndex = ((ps.downX - left) / cell).toInt().coerceIn(0, keys.size - 1)
        if (hapticFeedback) Haptics.longPress(this)
        invalidate()
    }

    private fun cancelTimers(ps: PointerState) {
        ps.longPress?.let { uiHandler.removeCallbacks(it) }
        ps.repeat?.let { uiHandler.removeCallbacks(it) }
        ps.longPress = null
        ps.repeat = null
    }

    private fun cancelAll() {
        for (i in 0 until pointers.size()) cancelTimers(pointers.valueAt(i))
        pointers.clear()
        // No exit animation here — the whole keyboard is going away — and any
        // snapshot mid-flight is dropped so it cannot ghost on the next show.
        popupOutKeys = emptyList()
        popupOwner = null
        popupKeys = emptyList()
        previewKb = null
        invalidate()
    }

    private fun isLetterKey(k: Key): Boolean =
        k.type == KeyType.CHARACTER && k.label.length == 1 && k.label[0].isLetter()

    /**
     * Adaptive touch targeting: when a tap lands where the slightly expanded
     * bounds of two or more letter keys overlap, let [tapArbiter] pick the
     * intended letter from spatial likelihood + language model instead of
     * blindly taking the bounding box.
     */
    private fun arbitrate(primary: KeyBounds, x: Float, y: Float): KeyBounds {
        val arb = tapArbiter ?: return primary
        if (layout?.kind != LayoutKind.MAIN || !isLetterKey(primary.key)) return primary
        var cands: ArrayList<KeyBounds>? = null
        for (kb in bounds) {
            if (!isLetterKey(kb.key)) continue
            val ex = kb.w * 0.18f
            val ey = kb.h * 0.15f
            if (x >= kb.x - ex && x < kb.x + kb.w + ex &&
                y >= kb.y - ey && y < kb.y + kb.h + ey
            ) {
                (cands ?: ArrayList<KeyBounds>(4).also { cands = it }).add(kb)
            }
        }
        val list = cands ?: return primary
        if (list.size < 2 || primary !in list) return primary
        val chars = CharArray(list.size)
        val logp = DoubleArray(list.size)
        for (i in list.indices) {
            val kb = list[i]
            chars[i] = kb.key.label[0]
            val dx = (x - (kb.x + kb.w / 2f)) / (kb.w * 0.40f)
            val dy = (y - (kb.y + kb.h / 2f)) / (kb.h * 0.40f)
            logp[i] = -0.5 * (dx * dx + dy * dy).toDouble()
        }
        val idx = arb(chars, logp)
        return if (idx in list.indices) list[idx] else primary
    }

    private fun keyAt(x: Float, y: Float): KeyBounds? {
        var best: KeyBounds? = null
        var bestD = Float.MAX_VALUE
        for (kb in bounds) {
            if (kb.contains(x, y)) return kb
            val dx = when {
                x < kb.x -> kb.x - x
                x > kb.x + kb.w -> x - (kb.x + kb.w)
                else -> 0f
            }
            val dy = when {
                y < kb.y -> kb.y - y
                y > kb.y + kb.h -> y - (kb.y + kb.h)
                else -> 0f
            }
            val d = dx * dx + dy * dy * 4f // vertical misses count 2x
            if (d < bestD) {
                bestD = d
                best = kb
            }
        }
        return best
    }
}

/** Bumped whenever the background image file changes, so the view reloads it. */
object BgImageState {
    @Volatile
    var version = 0
}
