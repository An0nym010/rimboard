package com.rimboard.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
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
        fun onGlideComplete(sequence: String)
        fun onOneHandedChanged(mode: Int)
        fun onBackspaceWord()
        fun onBackspaceWordRestore()
    }

    enum class ShiftState { NONE, AUTO, MANUAL, CAPSLOCK }

    var listener: Listener? = null

    var layout: KeyboardLayout? = null
        set(value) {
            field = value
            if (width > 0) computeBounds(width)
            requestLayout()
            invalidate()
        }

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

    private val gapX = dp(3f)
    private val gapY = dp(5f)
    private val sidePad = dp(3f)
    private val topPad = dp(6f)
    private val bottomPad = dp(5f)
    private val keyRadius = dp(8f)

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
        var cancelled = false
        var cursorMode = false
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
    private val longPressTimeoutMs =
        minOf(ViewConfiguration.getLongPressTimeout(), 350).toLong()

    private var popupOwner: PointerState? = null
    private var popupKeys: List<Key> = emptyList()
    private val popupRect = RectF()
    private var popupCell = 0f
    private var previewKb: KeyBounds? = null
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
        return (rows * rowHeightPx() + (rows - 1) * gapY + topPad + bottomPad).toInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, measureKeyboardHeight())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeBounds(w)
    }

    private fun computeBounds(w: Int) {
        bounds.clear()
        switchBtn.setEmpty()
        expandBtn.setEmpty()
        val lay = layout ?: return
        val mode = effectiveOneHanded()
        val contentW = if (mode == 0) w.toFloat() else w * 0.82f
        val offsetX = if (mode == 2) w - contentW else 0f
        val rowH = rowHeightPx()
        val innerW = contentW - 2 * sidePad
        val unit = innerW / lay.unitsPerRow
        var y = topPad
        for (row in lay.rows) {
            var rowUnits = 0f
            for (key in row.keys) rowUnits += key.width
            var x = offsetX + sidePad + (lay.unitsPerRow - rowUnits) * unit / 2f
            for (key in row.keys) {
                val kb = KeyBounds(key)
                kb.x = x
                kb.y = y
                kb.w = key.width * unit
                kb.h = rowH
                bounds.add(kb)
                x += kb.w
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
        canvas.drawColor(t.background)

        for (kb in bounds) {
            val key = kb.key
            val pressed = isPressedKb(kb)

            val bgColor = when {
                key.type == KeyType.ENTER -> t.accent
                key.code == Codes.SHIFT && shiftState == ShiftState.CAPSLOCK -> t.accent
                key.type == KeyType.FUNCTION -> if (pressed) t.keyBgPressed else t.keyBgFunc
                else -> if (pressed) t.keyBgPressed else t.keyBg
            }
            keyPaint.color = bgColor
            rectF.set(kb.x + gapX / 2f, kb.y, kb.x + kb.w - gapX / 2f, kb.y + kb.h)
            val radius = if (key.type == KeyType.ENTER) rectF.height() / 2f else keyRadius
            canvas.drawRoundRect(rectF, radius, radius, keyPaint)
            if (pressed && (key.type == KeyType.ENTER ||
                    (key.code == Codes.SHIFT && shiftState == ShiftState.CAPSLOCK))
            ) {
                keyPaint.color = 0x33000000
                canvas.drawRoundRect(rectF, radius, radius, keyPaint)
            }

            val label = displayLabel(key, lay.locale)
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
                    Icons.draw(canvas, keyIcon, rectF.centerX(), rectF.centerY(),
                        kb.h * 0.42f, textPaint.color)
                } else {
                textPaint.textSize = when {
                    key.type == KeyType.SPACE -> if (incognito) kb.h * 0.38f else kb.h * 0.26f
                    key.type == KeyType.ENTER -> kb.h * 0.38f
                    key.type == KeyType.CHARACTER -> kb.h * 0.42f
                    label.length > 2 -> kb.h * 0.28f
                    else -> kb.h * 0.40f
                }
                val cy = rectF.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f
                canvas.drawText(label, rectF.centerX(), cy, textPaint)
                }
            }

            val hint = key.hint
            if (hint != null && showDigitHints && key.type == KeyType.CHARACTER) {
                hintPaint.color = t.keyHint
                hintPaint.textSize = kb.h * 0.22f
                canvas.drawText(hint, rectF.right - dp(5f), rectF.top + kb.h * 0.30f, hintPaint)
            }
        }

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
            if (!ps.glide || ps.trail.size < 4) continue
            trailPaint.color = t.accent
            trailPaint.strokeWidth = dp(5f)
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
                ) key.label.uppercase(locale) else key.label
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
        val owner = popupOwner ?: return
        if (!owner.popupOpen || popupKeys.isEmpty()) return
        keyPaint.color = t.previewBg
        canvas.drawRoundRect(popupRect, keyRadius, keyRadius, keyPaint)
        textPaint.textSize = popupRect.height() * 0.42f
        for (i in popupKeys.indices) {
            val left = popupRect.left + i * popupCell
            if (i == owner.popupIndex) {
                keyPaint.color = t.accent
                rectF.set(
                    left + dp(2f), popupRect.top + dp(2f),
                    left + popupCell - dp(2f), popupRect.bottom - dp(2f)
                )
                canvas.drawRoundRect(rectF, keyRadius * 0.75f, keyRadius * 0.75f, keyPaint)
            }
            textPaint.color = if (i == owner.popupIndex) t.onAccent else t.keyText
            val pIcon = Icons.forCode(popupKeys[i].code) ?: Icons.forLabel(popupKeys[i].label)
            if (pIcon != null) {
                Icons.draw(canvas, pIcon, left + popupCell / 2f, popupRect.centerY(),
                    popupRect.height() * 0.46f, textPaint.color)
            } else {
                val cy = popupRect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f
                canvas.drawText(popupDisplayLabel(popupKeys[i]), left + popupCell / 2f, cy, textPaint)
            }
        }
    }

    private fun drawPreview(canvas: Canvas, t: KeyboardTheme) {
        val kb = previewKb ?: return
        if (popupOwner?.popupOpen == true) return
        if (Icons.forCode(kb.key.code) != null || Icons.forLabel(kb.key.label) != null) return
        val w = max(kb.w * 1.15f, dp(46f))
        val h = kb.h * 1.05f
        var left = kb.centerX() - w / 2f
        val maxLeft = width - w - dp(2f)
        left = if (maxLeft < dp(2f)) dp(2f) else left.coerceIn(dp(2f), maxLeft)
        var top = kb.y - h - dp(8f)
        if (top < dp(2f)) top = dp(2f)
        rectF.set(left, top, left + w, top + h)
        keyPaint.color = t.previewBg
        canvas.drawRoundRect(rectF, keyRadius, keyRadius, keyPaint)
        textPaint.color = t.keyText
        textPaint.textSize = h * 0.5f
        val cy = rectF.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(displayLabel(kb.key, layout?.locale ?: Locale.getDefault()),
            rectF.centerX(), cy, textPaint)
    }

    private fun isPressedKb(kb: KeyBounds): Boolean {
        for (i in 0 until pointers.size()) {
            val ps = pointers.valueAt(i)
            if (ps.kb === kb && !ps.cancelled) return true
        }
        return false
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
        val kb = keyAt(x, y) ?: return
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

        if (ps.kb.key.code == Codes.SPACE && spaceCursorEnabled) {
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
            popupOwner = null
            popupKeys = emptyList()
        }
        invalidate()
    }

    private fun openPopup(ps: PointerState) {
        if (ps.cancelled || ps.cursorMode || ps.glide) return
        val keys = ps.kb.key.popup
        if (keys.isEmpty()) return
        popupOwner = ps
        popupKeys = keys
        ps.popupOpen = true
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
        popupOwner = null
        popupKeys = emptyList()
        previewKb = null
        invalidate()
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
