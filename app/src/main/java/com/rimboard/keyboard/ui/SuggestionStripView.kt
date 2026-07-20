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
        /** Chevron tapped: expand into the full toolbar, or collapse back. */
        fun onToolbarToggle(expand: Boolean)
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
    private val toolRow: LinearLayout
    private val emojiRow: LinearLayout
    private val emojiScroll: HorizontalScrollView
    private val incogIcon: IconView
    private var boldIndex = -1

    private val expandBtn: IconView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        /** Width of one pinned tool slot, in dp. */
        const val TOOL_W = 38
        /**
         * Tools kept visible while suggestions occupy the strip. Each one costs
         * a suggestion slot about 25dp of width, so two is the point where
         * ordinary words still fit without ellipsis.
         */
        const val MAX_TOOLS_WITH_WORDS = 2
    }


    /** TalkBack label for a toolbar action; the icons say nothing on their own. */
    private fun descFor(code: Int): String? = when (code) {
        Codes.UNDO -> context.getString(R.string.tb_undo)
        Codes.REDO -> context.getString(R.string.tb_redo)
        Codes.COPY -> context.getString(R.string.tb_copy)
        Codes.PASTE -> context.getString(R.string.tb_paste)
        Codes.CUT -> context.getString(R.string.tb_cut)
        Codes.SELECT_ALL -> context.getString(R.string.tb_selectall)
        Codes.ONE_HANDED -> context.getString(R.string.tb_onehanded)
        Codes.INCOGNITO -> context.getString(R.string.tb_incognito)
        Codes.EDIT_PANEL -> context.getString(R.string.tb_edit)
        Codes.FLOATING -> context.getString(R.string.tb_floating)
        Codes.NUMPAD -> context.getString(R.string.tb_numpad)
        Codes.HIDE_KB -> context.getString(R.string.tb_hide)
        Codes.EMOJI -> context.getString(R.string.tb_emoji)
        Codes.CLIPBOARD -> context.getString(R.string.tb_clipboard)
        Codes.LANG -> context.getString(R.string.tb_language)
        Codes.TRANSLATE -> context.getString(R.string.tb_translate)
        Codes.SHARE -> context.getString(R.string.tb_share)
        Codes.THEME -> context.getString(R.string.tb_theme)
        Codes.RESIZE -> context.getString(R.string.tb_resize)
        Codes.SETTINGS -> context.getString(R.string.tb_settings)
        else -> null
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), 0, dp(4), 0)

        expandBtn = IconView(context, Icons.CHEVRON).apply {
            visibility = GONE
            contentDescription = context.getString(R.string.a11y_toolbar_open)
            setOnClickListener { listener?.onToolbarToggle(true) }
        }
        addView(expandBtn, LayoutParams(dp(34), LayoutParams.MATCH_PARENT))

        settingsBtn = IconView(context, Icons.SETTINGS).apply {
            visibility = GONE
            contentDescription = context.getString(R.string.tb_settings)
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

        clipboardBtn = IconView(context, Icons.CLIPBOARD).apply {
            visibility = GONE
            contentDescription = context.getString(R.string.tb_clipboard)
            setOnClickListener { listener?.onQuickAction(Codes.CLIPBOARD) }
        }
        addView(clipboardBtn, LayoutParams(dp(42), LayoutParams.MATCH_PARENT))

    }

    /** Flips the chevron to point back once the toolbar panel is showing. */
    fun setToolbarOpen(open: Boolean) {
        expandBtn.icon = if (open) Icons.CHEVRON_L else Icons.CHEVRON
        expandBtn.contentDescription = context.getString(
            if (open) R.string.a11y_toolbar_close else R.string.a11y_toolbar_open
        )
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
        settingsBtn.color = t.stripText
        clipboardBtn.color = t.stripText
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
        expandBtn.visibility = GONE
        settingsBtn.visibility = GONE
        clipboardBtn.visibility = GONE
        // Pinned tools stay put while words are showing — otherwise they were
        // only ever visible on a completely idle strip, which in practice is
        // just the moment before you start typing. Capped to three slots so
        // they take a fixed bite rather than a quarter of the bar.
        val shown = minOf(toolRow.childCount, MAX_TOOLS_WITH_WORDS)
        if (shown > 0) {
            centerBox.visibility = VISIBLE
            emojiScroll.visibility = VISIBLE
            toolRow.visibility = VISIBLE
            emojiRow.visibility = GONE
            setCenterWidth(dp(TOOL_W) * shown)
        } else {
            centerBox.visibility = GONE
        }
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
        clipChip.text = label
        clipChip.visibility = VISIBLE
        // The paste chip used to replace the pinned tools outright, so having
        // anything on the clipboard hid them. Tools stay; recent emoji yield
        // the room to the chip.
        emojiRow.visibility = GONE
        emojiScroll.visibility = if (toolRow.childCount > 0) VISIBLE else GONE
    }

    /** Rebuilds the pinned tool row. Stays visible while words are showing. */
    fun setPinnedTools(items: List<Pair<Int, Int>>) {
        toolRow.removeAllViews()
        val t = theme
        for ((icon, code) in items) {
            toolRow.addView(IconView(context, icon).apply {
                color = t?.stripText ?: 0xFF888888.toInt()
                contentDescription = descFor(code)
                setOnClickListener { listener?.onQuickAction(code) }
            }, LayoutParams(dp(TOOL_W), LayoutParams.MATCH_PARENT))
        }
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
        hideAll()
        expandBtn.visibility = VISIBLE
        settingsBtn.visibility = VISIBLE
        centerBox.visibility = VISIBLE
        clipboardBtn.visibility = VISIBLE
        clipChip.visibility = GONE
        // Idle: tools and recent emoji together, filling the free space.
        toolRow.visibility = VISIBLE
        emojiRow.visibility = VISIBLE
        setCenterWidth(0)
        emojiScroll.visibility =
            if (toolRow.childCount + emojiRow.childCount > 0) VISIBLE else GONE
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
