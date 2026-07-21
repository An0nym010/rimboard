package com.rimboard.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.rimboard.keyboard.R
import com.rimboard.keyboard.theme.KeyboardTheme
import kotlin.math.max
import kotlin.math.min

/**
 * The full-height toolbar, replacing the row of icons that had to fit inside a
 * suggestion strip barely taller than a line of text.
 *
 * Two sections: the tools pinned to the suggestion bar, and everything else.
 * Dragging a tool between them is how pinning works — the same gesture that
 * reorders, rather than a separate settings screen. Tapping runs the tool.
 */
@SuppressLint("ViewConstructor")
class ToolbarPanelView(context: Context) : View(context) {

    interface Listener {
        /** A tool was tapped: run it and dismiss the panel. */
        fun onToolAction(code: Int)
        /** The pinned set or its order changed. */
        fun onPinnedChanged(ids: List<String>)
        /** Back/close was tapped. */
        fun onToolbarPanelClosed()
    }

    var listener: Listener? = null

    private val pinned = ArrayList<String>()
    private val rest = ArrayList<String>()
    private var theme: KeyboardTheme? = null

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rectF = RectF()

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private companion object {
        const val CELL_H_DP = 62f
        const val ICON_R_DP = 21f
        const val HEADER_H_DP = 26f
        const val PAD_DP = 10f
        const val MIN_CELL_W_DP = 62f
        /** How long a press must hold before the tool lifts off. */
        const val LIFT_MS = 220L
        /** Virtual-view ids are section * this + index. */
        const val SECTION_STRIDE = 1000
        /** Custom accessibility action standing in for the drag. */
        const val ACTION_TOGGLE_PIN = 0x10F0
    }

    // Layout, recomputed on resize.
    private var cols = 5
    private var cellW = 0f
    private var panelScroll = 0f
    private var contentH = 0f

    fun applyTheme(t: KeyboardTheme) {
        theme = t
        setBackgroundColor(t.background)
        invalidate()
    }

    /** [pinnedIds] appear in the top section, everything else below. */
    fun setTools(pinnedIds: List<String>) {
        pinned.clear()
        rest.clear()
        pinned.addAll(pinnedIds.filter { ToolCatalog.byId(it) != null })
        rest.addAll(ToolCatalog.defaultOrder.filter { it !in pinned })
        panelScroll = 0f
        requestLayout()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val usable = w - dp(PAD_DP) * 2
        cols = max(4, (usable / dp(MIN_CELL_W_DP)).toInt())
        cellW = usable / cols
    }

    // ---- geometry ----

    private fun pinnedRows() = max(1, (pinned.size + cols - 1) / cols)
    private fun restRows() = max(1, (rest.size + cols - 1) / cols)

    private fun pinnedTop() = dp(PAD_DP) + dp(HEADER_H_DP)
    private fun restHeaderTop() = pinnedTop() + pinnedRows() * dp(CELL_H_DP) + dp(PAD_DP)
    private fun restTop() = restHeaderTop() + dp(HEADER_H_DP)

    private fun cellRect(index: Int, top: Float, out: RectF) {
        val r = index / cols
        val c = index % cols
        val x = dp(PAD_DP) + c * cellW
        val y = top + r * dp(CELL_H_DP)
        out.set(x, y, x + cellW, y + dp(CELL_H_DP))
    }

    /** Section and slot under a point, or null. Section 0 is pinned, 1 is rest. */
    private fun hit(x: Float, y: Float): Pair<Int, Int>? {
        val yy = y + panelScroll
        if (yy >= pinnedTop() && yy < pinnedTop() + pinnedRows() * dp(CELL_H_DP)) {
            val i = slotAt(x, yy, pinnedTop(), pinned.size)
            return 0 to i
        }
        if (yy >= restTop() && yy < restTop() + restRows() * dp(CELL_H_DP)) {
            val i = slotAt(x, yy, restTop(), rest.size)
            return 1 to i
        }
        return null
    }

    private fun slotAt(x: Float, y: Float, top: Float, count: Int): Int {
        val c = ((x - dp(PAD_DP)) / cellW).toInt().coerceIn(0, cols - 1)
        val r = ((y - top) / dp(CELL_H_DP)).toInt()
        return min(r * cols + c, max(0, count))
    }

    // ---- drawing ----

    override fun onDraw(canvas: Canvas) {
        val t = theme ?: return
        contentH = restTop() + restRows() * dp(CELL_H_DP) + dp(PAD_DP)
        val maxScroll = max(0f, contentH - height)
        panelScroll = panelScroll.coerceIn(0f, maxScroll)

        val save = canvas.save()
        canvas.translate(0f, -panelScroll)

        drawHeader(canvas, t, context.getString(R.string.tb_section_pinned), dp(PAD_DP))

        // A tray behind the pinned row makes it read as a drop target rather
        // than just the first line of one long grid.
        rectF.set(
            dp(PAD_DP) / 2f, pinnedTop() - dp(4f),
            width - dp(PAD_DP) / 2f, pinnedTop() + pinnedRows() * dp(CELL_H_DP) + dp(4f)
        )
        bgPaint.color = (t.keyBg and 0x00FFFFFF) or 0x40000000
        canvas.drawRoundRect(rectF, dp(14f), dp(14f), bgPaint)

        if (pinned.isEmpty()) {
            headerPaint.color = t.keyHint
            headerPaint.textSize = dp(12f)
            headerPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(
                context.getString(R.string.tb_section_empty),
                width / 2f, pinnedTop() + dp(CELL_H_DP) / 2f, headerPaint
            )
            headerPaint.textAlign = Paint.Align.LEFT
        }
        for (i in pinned.indices) {
            if (dragging != null && dragFrom == 0 && dragIndex == i) continue
            cellRect(i, pinnedTop(), rectF)
            drawTool(canvas, t, pinned[i], rectF, true)
        }

        drawHeader(canvas, t, context.getString(R.string.tb_section_all), restHeaderTop())
        for (i in rest.indices) {
            if (dragging != null && dragFrom == 1 && dragIndex == i) continue
            cellRect(i, restTop(), rectF)
            drawTool(canvas, t, rest[i], rectF, false)
        }
        canvas.restoreToCount(save)

        // The lifted tool rides above everything and ignores the scroll.
        val held = dragging
        if (held != null) {
            val lift = Anim.easeOut(Anim.progress(SystemClock.uptimeMillis(), dragStart, 120f))
            val s = 1f + 0.18f * lift
            rectF.set(
                dragX - cellW / 2f, dragY - dp(CELL_H_DP) / 2f,
                dragX + cellW / 2f, dragY + dp(CELL_H_DP) / 2f
            )
            val sv = canvas.save()
            canvas.scale(s, s, rectF.centerX(), rectF.centerY())
            drawTool(canvas, t, held, rectF, dragFrom == 0)
            canvas.restoreToCount(sv)
            if (lift < 1f) postInvalidateOnAnimation()
        }
    }

    private fun drawHeader(canvas: Canvas, t: KeyboardTheme, label: String, top: Float) {
        headerPaint.color = t.keyHint
        headerPaint.textSize = dp(11.5f)
        headerPaint.isFakeBoldText = true
        headerPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(label, dp(PAD_DP) + dp(4f), top + dp(15f), headerPaint)
    }

    /** One tool: a filled circle with the glyph, and its name underneath. */
    private fun drawTool(
        canvas: Canvas, t: KeyboardTheme, id: String, cell: RectF, isPinned: Boolean
    ) {
        val tool = ToolCatalog.byId(id) ?: return
        val cx = cell.centerX()
        val cy = cell.top + dp(ICON_R_DP) + dp(5f)
        bgPaint.color = if (isPinned) t.accent else t.keyBg
        canvas.drawCircle(cx, cy, dp(ICON_R_DP), bgPaint)
        Icons.draw(
            canvas, tool.icon, cx, cy, dp(ICON_R_DP) * 1.05f,
            if (isPinned) t.onAccent else t.keyText
        )
        // Explicit pin toggle. Hold-and-drag alone is not discoverable, and it
        // is the only way to get a tool onto the suggestion bar.
        val bx = cx + dp(ICON_R_DP) * 0.80f
        val by = cy - dp(ICON_R_DP) * 0.80f
        val br = dp(8f)
        bgPaint.color = if (isPinned) t.keyHint else t.accent
        canvas.drawCircle(bx, by, br, bgPaint)
        bgPaint.style = Paint.Style.STROKE
        bgPaint.strokeWidth = dp(1.9f)
        bgPaint.strokeCap = Paint.Cap.ROUND
        bgPaint.color = if (isPinned) t.background else t.onAccent
        canvas.drawLine(bx - br * 0.42f, by, bx + br * 0.42f, by, bgPaint)
        if (!isPinned) canvas.drawLine(bx, by - br * 0.42f, bx, by + br * 0.42f, bgPaint)
        bgPaint.style = Paint.Style.FILL

        textPaint.color = t.keyHint
        textPaint.textSize = dp(10f)
        val name = context.getString(tool.labelRes)
        val maxW = cell.width() - dp(4f)
        var shown = name
        if (textPaint.measureText(shown) > maxW) {
            while (shown.length > 1 && textPaint.measureText("$shown…") > maxW) {
                shown = shown.substring(0, shown.length - 1)
            }
            shown = "$shown…"
        }
        canvas.drawText(shown, cx, cell.bottom - dp(7f), textPaint)
    }

    // ---- touch ----

    private var dragging: String? = null
    private var dragFrom = 0
    private var dragIndex = -1
    private var dragStart = 0L
    private var dragX = 0f
    private var dragY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downHit: Pair<Int, Int>? = null
    private var scrolling = false

    // The pick-up has to be driven by a timer, not checked inside ACTION_MOVE:
    // a finger held perfectly still generates no move events at all, so polling
    // there means a patient press never lifts anything.
    private val liftHandler = Handler(Looper.getMainLooper())
    private var liftArm: Runnable? = null

    private fun armLift(h: Pair<Int, Int>) {
        val r = Runnable {
            lift(h)
            dragX = downX
            dragY = downY
            invalidate()
        }
        liftArm = r
        liftHandler.postDelayed(r, LIFT_MS)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        disarmLift()
    }

    private fun disarmLift() {
        liftArm?.let { liftHandler.removeCallbacks(it) }
        liftArm = null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downHit = hit(event.x, event.y)
                scrolling = false
                dragging = null
                downHit?.let { armLift(it) }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging != null) {
                    dragX = event.x
                    dragY = event.y
                    invalidate()
                    return true
                }
                val moved = kotlin.math.hypot(event.x - downX, event.y - downY)
                if (!scrolling && moved > dp(12f)) {
                    // Travelled before the hold elapsed, so read it as a scroll
                    // and give up on lifting this tool.
                    disarmLift()
                    scrolling = true
                }
                if (scrolling) {
                    panelScroll -= event.y - downY
                    downY = event.y
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                disarmLift()
                val held = dragging
                if (held != null) {
                    drop(event.x, event.y)
                    return true
                }
                if (!scrolling) {
                    val h = downHit
                    if (h != null) {
                        if (badgeHit(h.first, h.second, event.x, event.y)) {
                            performClick()
                            togglePin(h.first, h.second)
                            return true
                        }
                        val list = if (h.first == 0) pinned else rest
                        val id = list.getOrNull(h.second)
                        val tool = id?.let { ToolCatalog.byId(it) }
                        if (tool != null) {
                            performClick()
                            listener?.onToolAction(tool.code)
                            return true
                        }
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                disarmLift()
                if (dragging != null) drop(dragX, dragY)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** True if [x],[y] landed on the pin badge of the tool in that slot. */
    private fun badgeHit(section: Int, index: Int, x: Float, y: Float): Boolean {
        val list = if (section == 0) pinned else rest
        if (index !in list.indices) return false
        cellRect(index, if (section == 0) pinnedTop() else restTop(), rectF)
        val bx = rectF.centerX() + dp(ICON_R_DP) * 0.80f
        val by = rectF.top + dp(5f) + dp(ICON_R_DP) - dp(ICON_R_DP) * 0.80f
        return kotlin.math.hypot(x - bx, (y + panelScroll) - by) <= dp(14f)
    }

    private fun togglePin(section: Int, index: Int) {
        val list = if (section == 0) pinned else rest
        val id = list.getOrNull(index) ?: return
        if (section == 0) {
            pinned.remove(id)
            rest.add(id)
            rest.sortBy { ToolCatalog.defaultOrder.indexOf(it) }
        } else {
            rest.remove(id)
            pinned.add(id)
        }
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        listener?.onPinnedChanged(pinned.toList())
        invalidate()
        invalidateRoot()
    }

    private fun lift(h: Pair<Int, Int>) {
        val list = if (h.first == 0) pinned else rest
        val id = list.getOrNull(h.second) ?: return
        dragging = id
        dragFrom = h.first
        dragIndex = h.second
        dragStart = SystemClock.uptimeMillis()
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun drop(x: Float, y: Float) {
        val held = dragging ?: return
        val target = hit(x, y)
        val from = if (dragFrom == 0) pinned else rest
        from.removeAt(dragIndex)
        // Dropped outside either section: put it back where it came from.
        val section = target?.first ?: dragFrom
        val into = if (section == 0) pinned else rest
        val at = (target?.second ?: dragIndex).coerceIn(0, into.size)
        into.add(at, held)
        // "All tools" is a fixed catalogue, so keep it in catalogue order and
        // let only the pinned row carry a user-chosen arrangement.
        rest.sortBy { ToolCatalog.defaultOrder.indexOf(it) }
        dragging = null
        dragIndex = -1
        listener?.onPinnedChanged(pinned.toList())
        invalidate()
        invalidateRoot()
    }

    /** Height this panel wants, matching the keyboard it replaces. */
    fun preferredRows(): Int = pinnedRows() + restRows()

    // ---- accessibility ----

    /**
     * The panel draws itself, so to a screen reader it is one blank rectangle:
     * twenty tools with no names, no states and no way to reach them. Each cell
     * is exposed as a virtual view instead, with the drag replaced by an
     * explicit pin/unpin action — dragging is not a gesture a screen reader
     * user can perform.
     */
    private inner class A11y : ExploreByTouchHelper(this@ToolbarPanelView) {

        override fun getVirtualViewAt(x: Float, y: Float): Int {
            val h = hit(x, y) ?: return HOST_ID
            val list = if (h.first == 0) pinned else rest
            if (h.second !in list.indices) return HOST_ID
            return virtualId(h.first, h.second)
        }

        override fun getVisibleVirtualViews(ids: MutableList<Int>) {
            for (i in pinned.indices) ids.add(virtualId(0, i))
            for (i in rest.indices) ids.add(virtualId(1, i))
        }

        override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
            val section = id / SECTION_STRIDE
            val index = id % SECTION_STRIDE
            val list = if (section == 0) pinned else rest
            val tool = list.getOrNull(index)?.let { ToolCatalog.byId(it) }
            if (tool == null) {
                // Never leave a node unpopulated: an empty bounds throws.
                node.contentDescription = ""
                node.setBoundsInParent(Rect(0, 0, 1, 1))
                return
            }
            val name = context.getString(tool.labelRes)
            val state = context.getString(
                if (section == 0) R.string.tb_section_pinned else R.string.tb_section_all
            )
            node.contentDescription = "$name, $state"
            node.className = "android.widget.Button"
            node.isEnabled = true
            node.isFocusable = true
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            node.addAction(
                AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                    ACTION_TOGGLE_PIN,
                    context.getString(
                        if (section == 0) R.string.a11y_unpin else R.string.a11y_pin
                    )
                )
            )
            cellRect(index, if (section == 0) pinnedTop() else restTop(), rectF)
            node.setBoundsInParent(
                Rect(
                    rectF.left.toInt(),
                    (rectF.top - panelScroll).toInt(),
                    rectF.right.toInt(),
                    (rectF.bottom - panelScroll).toInt()
                )
            )
        }

        override fun onPerformActionForVirtualView(
            id: Int, action: Int, arguments: Bundle?
        ): Boolean {
            val section = id / SECTION_STRIDE
            val index = id % SECTION_STRIDE
            val list = if (section == 0) pinned else rest
            val tool = list.getOrNull(index)?.let { ToolCatalog.byId(it) } ?: return false
            return when (action) {
                AccessibilityNodeInfoCompat.ACTION_CLICK -> {
                    listener?.onToolAction(tool.code)
                    true
                }
                ACTION_TOGGLE_PIN -> {
                    togglePin(section, index)
                    invalidateRoot()
                    true
                }
                else -> false
            }
        }
    }

    private val a11y = A11y().also { ViewCompat.setAccessibilityDelegate(this, it) }

    private fun virtualId(section: Int, index: Int) = section * SECTION_STRIDE + index

    /** Rebuilds the whole virtual tree: pinning moves cells between sections. */
    private fun invalidateRoot() {
        a11y.invalidateRoot()
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        a11y.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)
}
