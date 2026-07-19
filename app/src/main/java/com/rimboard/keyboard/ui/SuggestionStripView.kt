package com.rimboard.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.text.TextUtils
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.rimboard.keyboard.model.Codes
import com.rimboard.keyboard.settings.Prefs
import com.rimboard.keyboard.theme.KeyboardTheme

@SuppressLint("ViewConstructor")
class SuggestionStripView(context: Context) : LinearLayout(context) {

    interface Listener {
        fun onSuggestionPicked(index: Int, word: String)
        fun onClipboardPasteRequested()
        fun onClipboardPanelRequested()
        fun onQuickAction(code: Int)
        fun onQuickEmoji(emoji: String)
        fun onSuggestionLongPressed(word: String, anchor: View)
        /** Chevron tapped: expand into the full toolbar, or collapse back. */
        fun onToolbarToggle(expand: Boolean)
        /** Toolbar icons were dragged into a new order (action codes, left to right). */
        fun onToolbarReordered(codes: List<Int>)
    }

    var listener: Listener? = null

    private var theme: KeyboardTheme? = null
    private val slots = ArrayList<TextView>(3)
    private val dividers = ArrayList<View>(2)
    private val centerLabel: TextView
    private val clipChip: TextView
    private val settingsBtn: IconView
    private val clipboardBtn: IconView
    private val centerBox: LinearLayout
    private val emojiRow: LinearLayout
    private val emojiScroll: HorizontalScrollView
    private val incogIcon: IconView
    private var boldIndex = -1

    private val expandBtn: IconView
    private val toolbarScroll: HorizontalScrollView
    private val toolbarRow: LinearLayout
    private var toolbarOpen = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        /** Width of one toolbar tool slot, in dp. */
        const val TOOL_W_DP = 46
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), 0, dp(4), 0)

        expandBtn = IconView(context, Icons.CHEVRON).apply {
            visibility = GONE
            setOnClickListener { listener?.onToolbarToggle(true) }
        }
        addView(expandBtn, LayoutParams(dp(34), LayoutParams.MATCH_PARENT))

        settingsBtn = IconView(context, Icons.SETTINGS).apply {
            visibility = GONE
            setOnClickListener { listener?.onQuickAction(Codes.SETTINGS) }
        }
        addView(settingsBtn, LayoutParams(dp(42), LayoutParams.MATCH_PARENT))

        clipChip = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            visibility = GONE
            setOnClickListener { listener?.onClipboardPasteRequested() }
            setOnLongClickListener {
                listener?.onClipboardPanelRequested()
                true
            }
        }
        centerBox = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            visibility = GONE
        }
        emojiRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            visibility = GONE
        }
        // Scrollable so a long list of pinned shortcuts (plus recent emoji)
        // never gets clipped off the end of the strip.
        emojiScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            visibility = GONE
            addView(emojiRow)
        }
        centerBox.addView(emojiScroll,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        centerBox.addView(clipChip,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(centerBox, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        for (i in 0 until 3) {
            if (i > 0) {
                val d = View(context)
                dividers.add(d)
                val lp = LayoutParams(dp(1), dp(20))
                lp.gravity = Gravity.CENTER_VERTICAL
                addView(d, lp)
            }
            val idx = i
            val tv = TextView(context).apply {
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                setOnClickListener {
                    val word = text?.toString() ?: return@setOnClickListener
                    if (word.isNotEmpty()) listener?.onSuggestionPicked(idx, word)
                }
                setOnLongClickListener {
                    val word = text?.toString()
                    if (word.isNullOrEmpty()) {
                        false
                    } else {
                        listener?.onSuggestionLongPressed(word, this)
                        true
                    }
                }
            }
            slots.add(tv)
            addView(tv, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }

        incogIcon = IconView(context, Icons.INCOGNITO).apply { visibility = GONE }
        addView(incogIcon, LayoutParams(dp(30), LayoutParams.MATCH_PARENT))
        centerLabel = TextView(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
            visibility = GONE
        }
        addView(centerLabel, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        clipboardBtn = IconView(context, Icons.CLIPBOARD).apply {
            visibility = GONE
            setOnClickListener { listener?.onQuickAction(Codes.CLIPBOARD) }
        }
        addView(clipboardBtn, LayoutParams(dp(42), LayoutParams.MATCH_PARENT))

        toolbarRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        toolbarScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            visibility = GONE
            addView(toolbarRow)
        }
        addView(toolbarScroll, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
    }

    /** Expand the strip into a scrollable Gboard-style toolbar of [items]
     *  (icon id to action code). A leading chevron collapses it again; the tool
     *  icons can be long-pressed and dragged to reorder. */
    fun showToolbar(items: List<Pair<Int, Int>>) {
        hideAll()
        toolbarOpen = true
        val t = theme
        toolbarRow.removeAllViews()
        toolbarRow.addView(IconView(context, Icons.CHEVRON_L).apply {
            color = t?.accent ?: 0xFF3E7BFA.toInt()
            setOnClickListener { listener?.onToolbarToggle(false) }
        }, LayoutParams(dp(38), LayoutParams.MATCH_PARENT))
        for ((icon, code) in items) {
            val iv = IconView(context, icon).apply {
                color = t?.stripText ?: 0xFF888888.toInt()
                tag = code
            }
            bindTool(iv, code)
            toolbarRow.addView(iv, LayoutParams(dp(TOOL_W_DP), LayoutParams.MATCH_PARENT))
        }
        toolbarScroll.scrollTo(0, 0)
        toolbarScroll.visibility = VISIBLE
    }

    // ---- drag-to-reorder ----

    private val dragHandler = Handler(Looper.getMainLooper())
    private var dragArm: Runnable? = null
    private var dragging: View? = null
    private var dragOriginX = 0f

    /** Tap runs the action; press-and-hold picks the icon up to reorder it. */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindTool(v: View, code: Int) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragOriginX = e.rawX
                    dragging = null
                    val r = Runnable {
                        dragging = view
                        // Take the gesture away from the scroll view once we're dragging.
                        toolbarScroll.requestDisallowInterceptTouchEvent(true)
                        view.alpha = 0.85f
                        view.scaleX = 1.18f
                        view.scaleY = 1.18f
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }
                    dragArm = r
                    dragHandler.postDelayed(r, 280)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging === view) {
                        view.translationX = e.rawX - dragOriginX
                        maybeSwap(view)
                    } else if (kotlin.math.abs(e.rawX - dragOriginX) > dp(10)) {
                        disarmDrag() // it's a scroll, not a hold
                    }
                }
                MotionEvent.ACTION_UP -> {
                    disarmDrag()
                    if (dragging === view) {
                        dropTool(view)
                    } else {
                        listener?.onQuickAction(code)
                        listener?.onToolbarToggle(false)
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    disarmDrag()
                    if (dragging === view) dropTool(view)
                }
            }
            true
        }
    }

    private fun disarmDrag() {
        dragArm?.let { dragHandler.removeCallbacks(it) }
        dragArm = null
    }

    /** Swap with a neighbour once the dragged icon's centre passes theirs. */
    private fun maybeSwap(v: View) {
        val idx = toolbarRow.indexOfChild(v)
        if (idx < 1) return
        val centre = v.left + v.width / 2f + v.translationX
        if (idx > 1) {
            val prev = toolbarRow.getChildAt(idx - 1)
            if (centre < prev.left + prev.width / 2f) {
                moveChild(v, idx, idx - 1)
                return
            }
        }
        if (idx < toolbarRow.childCount - 1) {
            val next = toolbarRow.getChildAt(idx + 1)
            if (centre > next.left + next.width / 2f) moveChild(v, idx, idx + 1)
        }
    }

    private fun moveChild(v: View, from: Int, to: Int) {
        toolbarRow.removeViewAt(from)
        toolbarRow.addView(v, to)
        // All tool icons share a width, so the view's left shifts by exactly one
        // slot: move the drag origin with it to keep the icon under the finger.
        dragOriginX += (to - from) * dp(TOOL_W_DP).toFloat()
    }

    private fun dropTool(v: View) {
        dragging = null
        v.animate().translationX(0f).scaleX(1f).scaleY(1f).alpha(1f).setDuration(140).start()
        val codes = ArrayList<Int>(toolbarRow.childCount)
        for (i in 1 until toolbarRow.childCount) {
            (toolbarRow.getChildAt(i).tag as? Int)?.let { codes.add(it) }
        }
        listener?.onToolbarReordered(codes)
    }

    fun applyTheme(t: KeyboardTheme) {
        theme = t
        setBackgroundColor(t.background)
        val dividerColor = (t.keyHint and 0x00FFFFFF) or 0x40000000
        dividers.forEach { it.setBackgroundColor(dividerColor) }
        centerLabel.setTextColor(t.keyHint)
        incogIcon.color = t.keyHint
        for (i in 0 until emojiRow.childCount) {
            (emojiRow.getChildAt(i) as? IconView)?.color = t.stripText
        }
        clipChip.setTextColor(t.stripText)
        settingsBtn.color = t.stripText
        clipboardBtn.color = t.stripText
        expandBtn.color = t.accent
        (toolbarRow.getChildAt(0) as? IconView)?.color = t.accent
        for (i in 1 until toolbarRow.childCount) {
            (toolbarRow.getChildAt(i) as? IconView)?.color = t.stripText
        }
        refreshSlotColors()
    }

    private fun refreshSlotColors() {
        val t = theme ?: return
        for (i in slots.indices) {
            val hl = i == boldIndex && slots[i].text.isNotEmpty()
            slots[i].setTextColor(if (hl) t.accent else t.stripText)
            if (hl) {
                val pill = GradientDrawable()
                pill.cornerRadius = dp(16).toFloat()
                pill.setColor((t.accent and 0x00FFFFFF) or 0x26000000)
                // Inset so the highlight reads as a compact pill, not a full-height bar.
                slots[i].background = InsetDrawable(pill, dp(6), dp(6), dp(6), dp(6))
            } else {
                slots[i].background = null
            }
        }
    }

    fun showSuggestions(words: List<String>, highlightIndex: Int) {
        expandBtn.visibility = GONE
        toolbarScroll.visibility = GONE
        toolbarOpen = false
        settingsBtn.visibility = GONE
        centerBox.visibility = GONE
        clipboardBtn.visibility = GONE
        boldIndex = highlightIndex
        clipChip.visibility = GONE
        centerLabel.visibility = GONE
        incogIcon.visibility = GONE
        for (i in 0 until 3) {
            val tv = slots[i]
            val w = words.getOrNull(i) ?: ""
            tv.text = w
            tv.visibility = VISIBLE
            tv.setTypeface(null, if (i == highlightIndex && w.isNotEmpty()) Typeface.BOLD else Typeface.NORMAL)
        }
        dividers.forEach { it.visibility = VISIBLE }
        refreshSlotColors()
    }

    fun showIncognito(label: String) {
        hideAll()
        // Keep the toolbar reachable: incognito is toggled off from in there.
        expandBtn.visibility = VISIBLE
        centerLabel.text = label
        incogIcon.visibility = VISIBLE
        centerLabel.visibility = VISIBLE
    }

    fun showClipboard(label: String) {
        showEmpty()
        emojiScroll.visibility = GONE
        clipChip.text = label
        clipChip.visibility = VISIBLE
    }

    /**
     * Fills the idle row with the user's pinned shortcuts followed by their
     * recent emoji. Both are shown together (the row scrolls), rather than the
     * shortcuts hiding the emoji as they used to.
     */
    fun setIdleRow(items: List<Pair<Int, Int>>, emojis: List<String>) {
        emojiRow.removeAllViews()
        val t = theme
        for ((icon, code) in items) {
            emojiRow.addView(IconView(context, icon).apply {
                color = t?.stripText ?: 0xFF888888.toInt()
                setOnClickListener { listener?.onQuickAction(code) }
            }, LayoutParams(dp(38), LayoutParams.MATCH_PARENT))
        }
        for (e in emojis) {
            emojiRow.addView(TextView(context).apply {
                text = e
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
                setPadding(dp(7), 0, dp(7), 0)
                setOnClickListener { listener?.onQuickEmoji(e) }
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }
        emojiScroll.scrollTo(0, 0)
    }

    fun showEmpty() {
        hideAll()
        expandBtn.visibility = VISIBLE
        settingsBtn.visibility = VISIBLE
        centerBox.visibility = VISIBLE
        clipboardBtn.visibility = VISIBLE
        clipChip.visibility = GONE
        emojiScroll.visibility = if (emojiRow.childCount > 0) VISIBLE else GONE
    }

    private fun hideAll() {
        toolbarOpen = false
        toolbarScroll.visibility = GONE
        expandBtn.visibility = GONE
        for (s in slots) { s.text = ""; s.visibility = GONE }
        dividers.forEach { it.visibility = GONE }
        settingsBtn.visibility = GONE
        centerBox.visibility = GONE
        clipChip.visibility = GONE
        emojiScroll.visibility = GONE
        clipboardBtn.visibility = GONE
        centerLabel.visibility = GONE
        incogIcon.visibility = GONE
    }
}
