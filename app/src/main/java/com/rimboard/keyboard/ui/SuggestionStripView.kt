package com.rimboard.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.rimboard.keyboard.R
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
        /** Chevron tapped: open the pinned-tool drawer, or close it. */
        fun onToolbarToggle(expand: Boolean)
        /** Drawer closed: the strip needs its ordinary contents back. */
        fun onDrawerClosed()
    }

    var listener: Listener? = null

    private var theme: KeyboardTheme? = null
    private val slots = ArrayList<TextView>(3)
    private val dividers = ArrayList<View>(2)
    private val centerLabel: TextView
    private val clipChip: TextView
    private val centerBox: LinearLayout
    private val toolRow: LinearLayout
    private val emojiRow: LinearLayout
    private val emojiScroll: HorizontalScrollView
    private val incogIcon: IconView
    private var boldIndex = -1

    private val expandBtn: IconView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        /**
         * Bounds for a pinned tool slot, in dp. Slots divide the free width
         * between them, so a couple of tools sit comfortably large and a full
         * drawer packs tighter before it has to scroll. A fixed width overflowed
         * the narrower strip of floating mode, which is only 86% of the screen.
         */
        const val TOOL_W_MIN = 30
        const val TOOL_W_MAX = 46
        /** Width reserved for the chevron, which never scrolls away. */
        const val CHEVRON_W = 34
    }


    /**
     * TalkBack label for a toolbar action; the icons say nothing on their own.
     *
     * Read out of the catalog rather than from a copy of it. The copy that used
     * to live here listed twenty of the twenty-one tools, and the one it missed
     * was "All tools" — which is the first entry in the default pinned set, so
     * every fresh install had an unlabelled icon at the left of the strip.
     */
    private fun descFor(code: Int): String? =
        ToolCatalog.byCode(code)?.let { context.getString(it.labelRes) }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), 0, dp(4), 0)

        // Permanently visible: it is the only fixed control on the strip now,
        // and the one guaranteed route to whatever the user has pinned.
        expandBtn = IconView(context, Icons.CHEVRON).apply {
            contentDescription = context.getString(R.string.a11y_drawer_open)
            setOnClickListener { listener?.onToolbarToggle(!drawerOpen) }
            // Long-press always reaches the full panel. Without it, anyone who
            // had pinned a set before "All tools" existed would have no route
            // to the screen that lets them pin it.
            setOnLongClickListener {
                listener?.onQuickAction(Codes.TOOLBAR_PANEL)
                true
            }
        }
        addView(expandBtn, LayoutParams(dp(34), LayoutParams.MATCH_PARENT))

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
        // Pinned tools and recent emoji live in separate rows: the tools stay
        // on the strip while suggestions are showing, the emoji do not.
        toolRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        emojiRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        val rowHolder = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(toolRow, LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
            addView(emojiRow, LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }
        // Scrollable so a long list of pinned shortcuts (plus recent emoji)
        // never gets clipped off the end of the strip.
        emojiScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            visibility = GONE
            addView(rowHolder)
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

    }

    private var drawerOpen = false

    /**
     * Opens or closes the drawer of pinned tools. Open, the tools take the
     * whole strip; closed, the strip goes back to suggestions. The chevron
     * turns to point the way out.
     */
    fun setDrawerOpen(open: Boolean) {
        // Closing an already-closed drawer must not call back: onStartInputView
        // resets it defensively, and an unguarded callback would run
        // updateStrip before the new field's state had been read.
        val changed = drawerOpen != open
        drawerOpen = open
        expandBtn.icon = if (open) Icons.CHEVRON_L else Icons.CHEVRON
        expandBtn.contentDescription = context.getString(
            if (open) R.string.a11y_drawer_close else R.string.a11y_drawer_open
        )
        if (open) showDrawer() else if (changed) listener?.onDrawerClosed()
    }

    fun isDrawerOpen() = drawerOpen

    /** The pinned tools across the full strip, with nothing competing. */
    private fun showDrawer() {
        hideAll()
        expandBtn.visibility = VISIBLE
        centerBox.visibility = VISIBLE
        emojiScroll.visibility = VISIBLE
        toolRow.visibility = VISIBLE
        // Recent emoji belong to the idle strip, not the tool drawer.
        emojiRow.visibility = GONE
        setCenterWidth(0)
        emojiScroll.scrollTo(0, 0)
    }

    fun applyTheme(t: KeyboardTheme) {
        theme = t
        setBackgroundColor(t.background)
        val dividerColor = (t.keyHint and 0x00FFFFFF) or 0x40000000
        dividers.forEach { it.setBackgroundColor(dividerColor) }
        centerLabel.setTextColor(t.keyHint)
        incogIcon.color = t.keyHint
        for (i in 0 until toolRow.childCount) {
            (toolRow.getChildAt(i) as? IconView)?.color = t.stripText
        }
        clipChip.setTextColor(t.stripText)
        expandBtn.color = t.accent
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
        if (drawerOpen) return showDrawer()
        expandBtn.visibility = VISIBLE
        centerBox.visibility = GONE
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
        if (drawerOpen) return showDrawer()
        showEmpty()
        clipChip.text = label
        clipChip.visibility = VISIBLE
        // The paste chip takes the room the recent emoji would have used.
        emojiRow.visibility = GONE
        emojiScroll.visibility = GONE
    }

    private var pinnedItems: List<Pair<Int, Int>> = emptyList()

    /** Rebuilds the pinned tool row shown in the drawer. */
    fun setPinnedTools(items: List<Pair<Int, Int>>) {
        pinnedItems = items
        rebuildToolRow()
    }

    /**
     * Slot width for [n] tools: the free width split between them, clamped so
     * they never become untappable and never sprawl. Past the minimum the row
     * scrolls instead of overflowing.
     */
    private fun slotWidth(n: Int): Int {
        if (n <= 0) return dp(TOOL_W_MAX)
        val free = width - dp(CHEVRON_W) - dp(8)
        // Before the first layout there is no width to divide; the row is
        // rebuilt from onSizeChanged once there is.
        if (free <= 0) return dp(TOOL_W_MAX)
        return (free / n).coerceIn(dp(TOOL_W_MIN), dp(TOOL_W_MAX))
    }

    private fun rebuildToolRow() {
        toolRow.removeAllViews()
        val t = theme
        val w = slotWidth(pinnedItems.size)
        for ((icon, code) in pinnedItems) {
            toolRow.addView(IconView(context, icon).apply {
                color = t?.stripText ?: 0xFF888888.toInt()
                contentDescription = descFor(code)
                setOnClickListener { listener?.onQuickAction(code) }
            }, LayoutParams(w, LayoutParams.MATCH_PARENT))
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Width decides the slot size, and it changes with floating mode,
        // one-handed mode and rotation.
        if (w != oldw && pinnedItems.isNotEmpty()) rebuildToolRow()
    }

    /** Rebuilds the recent-emoji row, which only shows on an idle strip. */
    fun setRecentEmoji(emojis: List<String>) {
        emojiRow.removeAllViews()
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
        if (drawerOpen) return showDrawer()
        hideAll()
        expandBtn.visibility = VISIBLE
        centerBox.visibility = VISIBLE
        clipChip.visibility = GONE
        // Idle shows recent emoji only: the pinned tools are what the drawer
        // is for, and duplicating them here would make the chevron pointless.
        toolRow.visibility = GONE
        emojiRow.visibility = VISIBLE
        setCenterWidth(0)
        emojiScroll.visibility = if (emojiRow.childCount > 0) VISIBLE else GONE
    }

    /** [w] of 0 means "share the free space by weight"; otherwise a fixed cap. */
    private fun setCenterWidth(w: Int) {
        val lp = centerBox.layoutParams as LayoutParams
        val weight = if (w == 0) 1f else 0f
        if (lp.width != w || lp.weight != weight) {
            lp.width = w
            lp.weight = weight
            centerBox.layoutParams = lp
        }
    }

    private fun hideAll() {
        expandBtn.visibility = GONE
        toolRow.visibility = GONE
        for (s in slots) { s.text = ""; s.visibility = GONE }
        dividers.forEach { it.visibility = GONE }
        centerBox.visibility = GONE
        clipChip.visibility = GONE
        emojiScroll.visibility = GONE
        centerLabel.visibility = GONE
        incogIcon.visibility = GONE
    }
}
