package com.rimboard.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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

        /**
         * The strip was swiped upwards while it was showing words.
         *
         * The one gesture on this row that was still free. The chevron is the
         * tool drawer, a tap on a chip commits it and a long press blocks it,
         * so the note that stood in `competitor-gaps` for a week said both
         * obvious gestures were taken and an expandable strip needed a design
         * decision first. It did not: this view had no touch handling at all,
         * and a vertical drag on it collided with nothing.
         *
         * The strip does not decide what happens -- whether the setting is on,
         * whether there is anything to expand, what the panel shows -- because
         * it does not know any of that. It reports the gesture.
         */
        fun onSuggestionsExpandRequested()
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

        /**
         * The other two fixed children of the row, in dp.
         *
         * Named because the width they take is subtracted when the strip works
         * out how many chips it can afford, and a second copy of a number is
         * only safe while something compares the two. These are used at
         * `addView` and by that subtraction, and nowhere else.
         */
        const val EMOJI_CHIP_W = 38
        const val INCOG_W = 30

        /** Padding either side, and the hairline between two chips. */
        const val ROW_PAD = 8
        const val DIVIDER_W = 1

        /**
         * How small a chip's text may shrink before it gives up and ellipsises.
         *
         * A suggestion nobody can read is not a suggestion. Five chips share
         * about 347dp of a 393dp phone once the chevron and the dividers are
         * taken out, and [com.rimboard.keyboard.model.StripLayout.weights]
         * only helps when the words differ in length -- when they are all
         * long, which is exactly when this matters, every chip gets the same
         * 69dp. At 15sp that fits eight or nine characters, so a German
         * compound arrives as `unte...tung` and an English one as
         * `autho...tion`, and the reader is left guessing between four
         * candidates that all begin the same way.
         *
         * Middle truncation is not the fault and must not be "fixed" to END:
         * the chips on a completion row share the prefix the user has just
         * typed, so cutting the tail makes all five identical. The tail is the
         * only part that distinguishes them. What is wrong is the size.
         *
         * **12sp, and the number is the accessibility floor rather than a
         * fitting one.** The first version of this went to 10sp because that
         * is what makes "unterhaltung" fit, and that is the wrong trade: a
         * keyboard answering "the word did not fit" by shrinking it has helped
         * the people who needed no help and hurt the ones who did. Below about
         * 12sp a suggestion stops being readable for anybody whose eyes are
         * not perfect.
         *
         * So the shrinking is small and deliberate, and when it runs out the
         * strip drops a chip instead -- see
         * [com.rimboard.keyboard.model.StripLayout.chipsThatRead]. Four
         * ellipsised candidates that all begin alike are worth less than two
         * anybody can read.
         *
         * Both sizes are in SP, so the system font-size setting scales them,
         * and both are multiplied by [labelScale] as well: somebody who has
         * enlarged the key labels has said "make the text bigger", and until
         * now the strip was the one row that did not listen.
         */
        const val MIN_CHIP_SP = 12f
        const val CHIP_SP = 15f

        /** Breathing room inside a chip, either side of the word. */
        const val CHIP_PAD = 5
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
        setPadding(dp(ROW_PAD / 2), 0, dp(ROW_PAD / 2), 0)

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
                val lp = LayoutParams(dp(DIVIDER_W), dp(20))
                lp.gravity = Gravity.CENTER_VERTICAL
                addView(d, lp)
            }
            val idx = i
            val tv = TextView(context).apply {
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, CHIP_SP)
                maxLines = 1
                // So a word does not touch the hairline beside it.
                // `authorities|authorized` read as one string without this.
                setPadding(dp(CHIP_PAD), 0, dp(CHIP_PAD), 0)
                // The last resort, and it should now be unreachable: the row
                // picks a size every word fits at, and drops the chips it
                // cannot. Left in place because a measurement and a layout can
                // still disagree by a pixel, and one ellipsised word is a
                // better failure than a clipped one.
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

        // Its own chip at the end of the row, not one of the word slots. It
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
        addView(emojiChip, LayoutParams(dp(EMOJI_CHIP_W), LayoutParams.MATCH_PARENT))

        incogIcon = IconView(context, Icons.INCOGNITO).apply { visibility = GONE }
        addView(incogIcon, LayoutParams(dp(INCOG_W), LayoutParams.MATCH_PARENT))
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
     * The user's key-label scale, applied to the suggestion text too.
     *
     * `label_scale_pct` has existed for a long time and moved the key labels
     * and nothing else, so the row most likely to hold an unfamiliar word was
     * the one row that ignored a request to make the text bigger.
     */
    var labelScale: Float = 1f
        set(value) {
            if (field == value) return
            field = value
            applyChipSizes()
            requestLayout()
        }

    private fun scaledSp(sp: Float): Int =
        (sp * labelScale).toInt().coerceAtLeast(1)

    /**
     * One text size for the whole row, and the largest every word fits at.
     *
     * Sizing each chip on its own is what Android's auto-size does, and it
     * left the row ragged -- "unterhaltsam" visibly smaller than "unterhalte"
     * beside it, because they had the same width and different lengths. A row
     * of mixed sizes reads as a list of things of different importance, which
     * is not what a suggestion strip is saying, and the eye stops at every
     * step. It also picks the *smallest* size independently per chip, so one
     * long word no longer drags its neighbours down with it -- but nothing was
     * gained by that, since the neighbours had room to spare.
     *
     * So the row is measured once: for each chip, how much bigger than
     * [MIN_CHIP_SP] its own share would allow, and the smallest of those wins.
     * [com.rimboard.keyboard.model.StripLayout.chipsThatRead] has already
     * guaranteed every chip clears the floor, so this can only ever size *up*.
     */
    private fun rowTextSize(shown: List<String>, weights: List<Float>, freeDp: Int): Float {
        val minSp = MIN_CHIP_SP * labelScale
        val maxSp = CHIP_SP * labelScale
        val total = weights.sum()
        val n = shown.count { it.isNotEmpty() }
        if (n == 0 || total <= 0f) return maxSp
        val base = com.rimboard.keyboard.model.StripLayout.chipFloorDp(freeDp, n)
        val surplus = (freeDp - n * base).coerceAtLeast(0)
        // Weighted shares are the strip's own business; the rule that turns
        // them into one size is shared with the expanded panel.
        return com.rimboard.keyboard.model.StripLayout.uniformTextSp(
            needDp = shown.map { needDp(it) },
            shareDp = shown.indices.map { base + surplus * (weights[it] / total) },
            minSp = minSp,
            maxSp = maxSp
        )
    }

    private fun applyChipSize(sp: Float) {
        for (tv in slots) tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }

    private fun applyChipSizes() = applyChipSize(CHIP_SP * labelScale)

    /** How wide [word] has to be to be read, in dp. See [ChipText]. */
    private fun needDp(word: String): Int = ChipText.needDp(
        word,
        slots.firstOrNull()?.typeface,
        MIN_CHIP_SP * labelScale,
        CHIP_PAD * 2,
        resources.displayMetrics
    )

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var swipeArmed = false
    private var consumingSwipe = false

    /** Whether there is anything on this row an expanded view could show more of. */
    private fun wordsShowing(): Boolean =
        !drawerOpen && slots.any { it.visibility == VISIBLE && it.text.isNotEmpty() }

    /**
     * Claims an upward drag, and only an upward drag.
     *
     * Intercepting rather than handling: the chips are real clickable views
     * and must stay that way, so the gesture is taken out from under them only
     * once it has passed the touch slop and is more vertical than horizontal.
     * A tap never gets that far, and the child receives ACTION_CANCEL when
     * this fires, so a swipe that begins on a chip does not also commit it.
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                swipeArmed = wordsShowing()
                consumingSwipe = false
            }
            MotionEvent.ACTION_MOVE -> if (swipeArmed) {
                val dy = ev.y - downY
                val dx = ev.x - downX
                if (dy < -touchSlop && kotlin.math.abs(dy) > kotlin.math.abs(dx)) {
                    swipeArmed = false
                    consumingSwipe = true
                    listener?.onSuggestionsExpandRequested()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                swipeArmed = false
                consumingSwipe = false
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_UP ||
            ev.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            val was = consumingSwipe
            consumingSwipe = false
            if (was) return true
        }
        return consumingSwipe || super.onTouchEvent(ev)
    }

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
        // Turned, not swapped. `>` rotated half a turn *is* `<`, so the two
        // drawables were always the same shape twice — and swapping them made
        // the drawer open with a jump where every other surface in this
        // keyboard moves. The icon stays CHEVRON and the view rotates.
        turnChevron(open)
        expandBtn.contentDescription = context.getString(
            if (open) R.string.a11y_drawer_close else R.string.a11y_drawer_open
        )
        if (open) showDrawer() else if (changed) listener?.onDrawerClosed()
    }

    fun isDrawerOpen() = drawerOpen

    /** The pinned tools across the full strip, with nothing competing. */
    private fun showDrawer() {
        val wasShowing = toolRow.visibility == VISIBLE
        hideAll()
        expandBtn.visibility = VISIBLE
        centerBox.visibility = VISIBLE
        emojiScroll.visibility = VISIBLE
        toolRow.visibility = VISIBLE
        setCenterWidth(0)
        emojiScroll.scrollTo(0, 0)
        // Only on the way in. `showDrawer` is re-entered on every redraw while
        // the drawer is open, and animating each time would leave the row
        // permanently fading.
        if (!wasShowing) riseIn(toolRow)
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
    /**
     * [composingWord] is true while a word is being typed, and it buys the
     * chips the chevron's 34dp.
     *
     * The chevron holds the left end of the most-looked-at row in the product,
     * permanently, to reach a drawer that is opened rarely -- and it holds it
     * hardest at the moment the chips have least to spare. Measured on the
     * phone over fifteen five-letter prefixes, whose candidates are long
     * enough for the fifth chip to be at risk: see the commit for the numbers.
     *
     * It comes back the moment the word is committed, so the drawer is one
     * space bar away rather than unreachable.
     */
    fun showSuggestions(
        words: List<String>,
        highlightIndex: Int,
        emoji: String? = null,
        composingWord: Boolean = false
    ) {
        if (drawerOpen) return showDrawer()
        expandBtn.visibility = if (composingWord) GONE else VISIBLE
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
        // What the row can afford, not what it would like. See
        // [com.rimboard.keyboard.model.StripLayout.chipsThatFit]: the fixed
        // children come to about 114dp, and in floating mode on a narrow phone
        // five chips would be 39dp each.
        val fixed = (if (expandBtn.visibility == VISIBLE) dp(CHEVRON_W) else 0) +
            dp(ROW_PAD) + dividers.size * dp(DIVIDER_W) +
            (if (emojiChip.visibility == VISIBLE) dp(EMOJI_CHIP_W) else 0) +
            (if (incogIcon.visibility == VISIBLE) dp(INCOG_W) else 0)
        val freeDp = ((width - fixed) / resources.displayMetrics.density).toInt()
        val fits = com.rimboard.keyboard.model.StripLayout.chipsThatFit(
            freeDp, slots.size, keepAtLeast = highlightIndex + 1
        )
        // And then again, for legibility rather than for the touch target.
        // Five chips can all be comfortably tappable and still be four
        // ellipsised words that begin alike; see [MIN_CHIP_SP].
        val candidates = List(fits) { words.getOrNull(it) ?: "" }
        val readable = com.rimboard.keyboard.model.StripLayout.chipsThatRead(
            freeDp, candidates, candidates.map { needDp(it) },
            fits, keepAtLeast = highlightIndex + 1
        )
        val shown = List(slots.size) { if (it < readable) words.getOrNull(it) ?: "" else "" }
        val weights = com.rimboard.keyboard.model.StripLayout.weights(shown)
        applyChipSize(rowTextSize(shown, weights, freeDp))
        val floorPx = dp(
            com.rimboard.keyboard.model.StripLayout.chipFloorDp(freeDp, fits)
        )
        for (i in slots.indices) {
            val tv = slots[i]
            val w = shown[i]
            tv.text = w
            // GONE rather than INVISIBLE: a weighted row gives width to
            // everything it can see, so an invisible slot would still take its
            // share and the visible chips would be narrower for nothing.
            tv.visibility = if (w.isEmpty()) GONE else VISIBLE
            (tv.layoutParams as? LayoutParams)?.let { lp ->
                if (lp.weight != weights[i] || lp.width != floorPx) {
                    lp.weight = weights[i]
                    // Width first, then the weighted surplus. See
                    // [com.rimboard.keyboard.model.StripLayout.chipFloorDp]:
                    // with a width of zero the whole row is shared out by
                    // weight, which put a chip under the touch target on one
                    // row in seven.
                    lp.width = floorPx
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

    private var pinnedItems: List<ToolCatalog.Tool> = emptyList()

    /**
     * Rebuilds the pinned tool row shown in the drawer.
     *
     * **Takes tool ids, and resolves them here.** This used to take the icon
     * and code already looked up, which made [RimBoardService] a second place
     * that decided what a tool id means -- and `ToolbarPanelView`, filling the
     * very panel that arranges this row, resolved them a third way through
     * [ToolCatalog]. Twice this month the bar and the panel disagreed about
     * which icon a tool draws, because a change to the catalog reached one
     * caller and not the other. One argument type, one lookup, one answer.
     */
    fun setPinnedTools(ids: List<String>) {
        pinnedItems = ids.mapNotNull { ToolCatalog.byId(it) }
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
        for (tool in pinnedItems) {
            toolRow.addView(IconView(context, tool.icon).apply {
                color = t?.stripText ?: 0xFF888888.toInt()
                contentDescription = descFor(tool.code)
                setOnClickListener { listener?.onQuickAction(tool.code) }
            }, LayoutParams(w, LayoutParams.MATCH_PARENT))
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Width decides the slot size, and it changes with floating mode,
        // one-handed mode and rotation.
        //
        // **Posted, because onSizeChanged runs inside a layout traversal.**
        // `addView` there calls `requestLayout()` at the one moment it does
        // nothing, so the new icons were added and never measured: six children
        // of 0x0 inside a row that still reported its old width. Nothing
        // throws, nothing logs, and the drawer simply comes up empty.
        //
        // The same fault, in the same shape, as `SuggestionsPanelView` on
        // 2026-09-08 — a view rebuilding its own children in response to being
        // laid out. It went unseen here because the drawer was only ever opened
        // by a tap, long after layout had settled; opening it on an idle strip
        // put it in the same frame as the first layout and it failed every time.
        if (w != oldw && pinnedItems.isNotEmpty()) post { rebuildToolRow() }
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

    /**
     * Which way the chevron is pointing, so a redraw does not re-run the turn.
     *
     * `showSuggestions` and `showEmpty` both reach `setDrawerOpen`-adjacent
     * code on every keystroke; animating from the current value each time
     * would restart the turn continuously and the chevron would never settle.
     */
    private var chevronTurned = false

    private fun turnChevron(open: Boolean) {
        if (chevronTurned == open) return
        chevronTurned = open
        val d = (Anim.POPUP_IN_MS * Anim.durationScale).toLong()
        expandBtn.animate().cancel()
        if (d <= 0L) {
            expandBtn.rotation = if (open) 180f else 0f
            return
        }
        expandBtn.animate()
            .rotation(if (open) 180f else 0f)
            .setDuration(d)
            .start()
    }

    /**
     * Fades a row in from slightly below.
     *
     * Used where the strip changes what it is showing rather than what it
     * says: tools appearing, chips returning. Not on every word change — the
     * words change on every keystroke, and a strip that flickers under fast
     * typing is worse than one that simply updates.
     */
    private fun riseIn(v: View) {
        val d = (Anim.PREVIEW_IN_MS * Anim.durationScale).toLong()
        v.animate().cancel()
        if (d <= 0L) {
            v.alpha = 1f
            v.translationY = 0f
            return
        }
        v.alpha = 0f
        v.translationY = dp(4).toFloat()
        v.animate().alpha(1f).translationY(0f).setDuration(d).start()
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
