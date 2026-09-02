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
import android.view.ViewGroup
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

        /**
         * The paste chip's window has run out. The strip does not decide what
         * replaces it — an empty field can want the incognito label, the idle
         * tools or nothing — so it asks to be rebuilt rather than hiding the
         * chip itself.
         */
        fun onClipChipExpired()
        fun onClipboardPanelRequested()
        fun onQuickAction(code: Int)
        fun onSuggestionLongPressed(word: String, anchor: View)

        /** The word-to-emoji chip, which sits beside the words rather than in
         *  one of their slots. */
        fun onEmojiSuggestionPicked(emoji: String)
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
    private val emojiScroll: HorizontalScrollView
    private val autofillScroll: HorizontalScrollView
    private val autofillRow: LinearLayout
    private val incogIcon: IconView
    private val emojiChip: TextView
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
            // Drawn as a bordered pill rather than as plain text, because it is
            // not a suggestion: tapping it inserts something the user copied
            // somewhere else, and nothing about a bare word on the strip says
            // that. The outline is what makes it read as an offer rather than
            // as the keyboard's guess at what they are typing.
            setPadding(dp(12), dp(4), dp(12), dp(4))
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
        val rowHolder = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(toolRow, LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }
        // Scrollable so a long list of pinned shortcuts never gets clipped off
        // the end of the strip.
        emojiScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            visibility = GONE
            addView(rowHolder)
        }
        // The password manager's chips. Their own row, because the views in
        // it belong to another process and are placed by it — nothing here may
        // restyle, measure around or reach inside them, so they get a
        // container of their own rather than sharing one.
        autofillRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        autofillScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            visibility = GONE
            addView(
                autofillRow,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
            )
        }
        centerBox.addView(autofillScroll,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        centerBox.addView(emojiScroll,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        centerBox.addView(clipChip,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(centerBox, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        for (i in 0 until com.rimboard.keyboard.model.StripLayout.SLOTS) {
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

        // Its own chip at the end of the row, not one of the three slots. It
        // used to take the third slot whenever a typed word matched, which
        // spent a word suggestion on exactly the words most likely to have had
        // a useful one. Narrow, because it holds a single glyph.
        emojiChip = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            visibility = GONE
            isClickable = true
            setOnClickListener {
                val e = text?.toString()
                if (!e.isNullOrEmpty()) listener?.onEmojiSuggestionPicked(e)
            }
        }
        addView(emojiChip, LayoutParams(dp(38), LayoutParams.MATCH_PARENT))

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
        clipChip.setTextColor(t.accent)
        // Accent outline over the theme's own key colour: visible on light and
        // dark alike, and it moves with the per-app tint like everything else.
        clipChip.background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(t.keyBg)
            setStroke(dp(1).coerceAtLeast(1), t.accent)
        }
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

    @JvmOverloads
    fun showSuggestions(words: List<String>, highlightIndex: Int, emoji: String? = null) {
        if (drawerOpen) return showDrawer()
        expandBtn.visibility = VISIBLE
        centerBox.visibility = GONE
        boldIndex = highlightIndex
        clipChip.visibility = GONE
        removeCallbacks(clipExpiry)
        centerLabel.visibility = GONE
        // The mark stays up alongside the suggestions rather than replacing
        // them: incognito changes where a suggestion may come from, not
        // whether you get one.
        incogIcon.visibility = if (incognitoMark) VISIBLE else GONE
        emojiChip.text = emoji.orEmpty()
        emojiChip.visibility = if (emoji.isNullOrEmpty()) GONE else VISIBLE
        // Sized to what each chip holds, and an empty one takes no room at
        // all. Equal shares were what ellipsised "Bananenkuchen" into
        // "Banane...uchen" while "Kinde" beside it sat two-thirds empty --
        // and equal shares are what would make five chips unreadable. See
        // [com.rimboard.keyboard.model.StripLayout.weights].
        val shown = List(slots.size) { words.getOrNull(it) ?: "" }
        val weights = com.rimboard.keyboard.model.StripLayout.weights(shown)
        for (i in slots.indices) {
            val tv = slots[i]
            val w = shown[i]
            tv.text = w
            // GONE rather than INVISIBLE: a weighted row gives width to
            // everything it can see, so an invisible slot would still take its
            // share and the visible chips would be narrower for nothing.
            tv.visibility = if (w.isEmpty()) GONE else VISIBLE
            (tv.layoutParams as? LayoutParams)?.let { lp ->
                if (lp.weight != weights[i]) {
                    lp.weight = weights[i]
                    tv.layoutParams = lp
                }
            }
            tv.setTypeface(null, if (i == highlightIndex && w.isNotEmpty()) Typeface.BOLD else Typeface.NORMAL)
        }
        // A divider belongs to the chip on its right, so it goes when that
        // chip does -- otherwise a half-filled strip ends in a row of rules
        // with nothing between them.
        for (i in dividers.indices) {
            dividers[i].visibility = if (shown.getOrNull(i + 1).isNullOrEmpty()) GONE else VISIBLE
        }
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

    /**
      * Shows the paste chip, for [expiresIn] milliseconds.
      *
      * The timer lives here rather than in the service because this is the
      * thing that can go away: the input view is torn down and rebuilt
      * constantly — every rotation goes through `onConfigurationChanged` —
      * and a runnable posted from the service would outlive the strip it was
      * posted for. Detaching cancels it, which is the rule `DelayedWorkTest`
      * enforces on every view here.
      *
      * It is needed at all because nothing else redraws the strip while
      * someone sits looking at an empty field, and that is precisely the
      * moment the chip is shown in. Without a timer the window would only be
      * noticed at the next keystroke, which is the one thing that has not
      * happened yet.
      */
    fun showClipboard(label: String, expiresIn: Long) {
        if (drawerOpen) return showDrawer()
        showEmpty()
        clipChip.text = label
        clipChip.visibility = VISIBLE
        emojiScroll.visibility = GONE
        // After showEmpty, which goes through hideAll and takes the previous
        // one back off the queue.
        if (expiresIn > 0L) postDelayed(clipExpiry, expiresIn)
    }

    private val clipExpiry = Runnable { listener?.onClipChipExpired() }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(clipExpiry)
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

    /**
     * Shows the autofill chips, or clears them when [views] is empty.
     *
     * **Re-attaching is avoided rather than merely cheap.** These views are
     * surfaces owned by the autofill provider's process, and taking one out of
     * the hierarchy and putting it back tears its surface down and builds a new
     * one. This is called from `updateStrip`, which runs on every keystroke and
     * every selection change, so a version that detached and re-added
     * unconditionally re-created every chip's surface several times a second
     * while they sat there apparently doing nothing — flicker at best, and
     * blank rectangles where a provider was slower to redraw than the strip was
     * to rebuild.
     *
     * So the children are only touched when they actually change. The
     * visibility flags are re-asserted either way, because [hideAll] runs
     * between calls and turns the row off without disturbing what is in it.
     * Scroll position survives an unchanged call too, which is the small
     * visible benefit of the same rule.
     */
    fun showAutofill(views: List<View>) {
        if (drawerOpen) return showDrawer()
        if (views.isEmpty()) {
            autofillRow.removeAllViews()
            autofillScroll.visibility = GONE
            return
        }
        val unchanged = attachedAre(views)
        if (!unchanged) autofillRow.removeAllViews()
        hideAll()
        expandBtn.visibility = VISIBLE
        centerBox.visibility = VISIBLE
        setCenterWidth(0)
        if (!unchanged) {
            for (v in views) {
                (v.parent as? ViewGroup)?.removeView(v)
                autofillRow.addView(v)
            }
        }
        autofillScroll.visibility = VISIBLE
        if (!unchanged) autofillScroll.scrollTo(0, 0)
    }

    /** Whether exactly [views], in that order, are already the row's children. */
    private fun attachedAre(views: List<View>): Boolean {
        if (autofillRow.childCount != views.size) return false
        for (i in views.indices) if (autofillRow.getChildAt(i) !== views[i]) return false
        return true
    }

    fun showEmpty() {
        if (drawerOpen) return showDrawer()
        hideAll()
        expandBtn.visibility = VISIBLE
        centerBox.visibility = VISIBLE
        clipChip.visibility = GONE
        // Nothing occupies the idle strip: the pinned tools are what the
        // drawer is for, and duplicating them here would make the chevron
        // pointless.
        toolRow.visibility = GONE
        setCenterWidth(0)
        emojiScroll.visibility = GONE
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
        autofillScroll.visibility = GONE
        expandBtn.visibility = GONE
        toolRow.visibility = GONE
        for (s in slots) { s.text = ""; s.visibility = GONE }
        dividers.forEach { it.visibility = GONE }
        centerBox.visibility = GONE
        clipChip.visibility = GONE
        removeCallbacks(clipExpiry)
        emojiScroll.visibility = GONE
        centerLabel.visibility = GONE
        incogIcon.visibility = GONE
        emojiChip.visibility = GONE
    }

    /**
     * Whether the incognito mark rides along with whatever else is shown.
     *
     * Previously incognito replaced the strip with a label, because there was
     * nothing to put there — nothing was suggested at all. Now that the
     * dictionary and the bundled model still answer, the mark has to coexist
     * with them.
     */
    var incognitoMark = false
        set(value) {
            field = value
            if (!value) incogIcon.visibility = GONE
        }
}
