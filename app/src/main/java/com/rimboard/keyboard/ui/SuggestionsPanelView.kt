package com.rimboard.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.rimboard.keyboard.model.ChipRows
import com.rimboard.keyboard.theme.KeyboardTheme

/**
 * The suggestion strip, opened out.
 *
 * The strip is five chips wide and drops the rest from the right, and the
 * measurement in `open-items` says that is the tightest constraint in the
 * whole keyboard: taking the target from the top N of the engine's own ranked
 * list is worth 41.8% of keystrokes at three chips, 47.7% at four and 53.4% at
 * six, for English. One more chip is worth six to nine points, which is an
 * order of magnitude more than any ranking change measured in this project.
 *
 * **Read that as an upper bound, and a loose one.** It assumes the words are
 * on screen and the right one gets tapped. Behind a deliberate gesture that is
 * not what happens: what this actually buys is the case where the word you
 * wanted was not in the five, which is a smaller and rarer thing than the
 * table suggests. Yandex ships `use_expandable_suggestions` and Gboard a
 * long-press for exactly this, and neither is a feature people live in.
 *
 * And the panel does not hold twenty-four words either, whatever
 * [ChipRows.CAPACITY] says. `SuggestionEngine.COMPLETION_FETCH` is 12 and is a
 * swept constant, so what actually arrives is **twelve or thirteen words
 * against the strip's five** -- measured in `ExpandedSuggestionsTest`, the same
 * in English, German and Turkish, because the ceiling is the fetch rather than
 * the language. Which is the end of the range the keystroke table covers, so
 * the panel goes as far as anything here has been measured to and no further.
 *
 * ## Why a panel and not a taller strip
 *
 * The strip is a fixed 44dp child of the column that holds it, and everything
 * about it -- `StripLayout.chipsThatFit`, the bold chip's promise, the
 * weighted widths -- is arithmetic about one row. Growing it would mean
 * teaching all of that about height. Covering the keyboard instead is a shape
 * this app already has four of: `ClipboardView`, `EditPanelView` and the tools
 * panel are all siblings in the same frame, all sized by `revealPanel` and all
 * kept mutually exclusive by the one `panels()` list.
 *
 * The words are the *same* words in the same order -- `suggestionsFor` with a
 * bigger `slots`, not a second ranking. A "show me more" gesture that reorders
 * what it was showing is worse than no gesture.
 */
@SuppressLint("ViewConstructor")
class SuggestionsPanelView(context: Context) : ScrollView(context) {

    interface Listener {
        /** A word was tapped. The panel does not close itself; the service does. */
        fun onExpandedWordPicked(word: String)
    }

    var listener: Listener? = null

    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    private var theme: KeyboardTheme? = null
    private var words: List<String> = emptyList()

    /** The width the rows were last built for, so a scroll does not rebuild. */
    private var builtForWidth = -1

    private companion object {
        /** Chip height, matching the strip's own row. */
        const val CHIP_H_DP = 44
        const val CHIP_GAP_DP = 4
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    init {
        isFillViewport = true
        addView(
            column,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
    }

    fun applyTheme(t: KeyboardTheme) {
        theme = t
        setBackgroundColor(t.background)
        builtForWidth = -1
        rebuild()
    }

    /** The ranked words, best first, exactly as the strip would have shown them. */
    fun setWords(w: List<String>) {
        words = w
        scrollTo(0, 0)
        builtForWidth = -1
        rebuild()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // The number of columns is a function of the width, and the width is
        // not known until this fires -- the panel is built before it is laid
        // out, so the first pass runs at zero and answers [ChipRows.MIN_COLUMNS].
        //
        // **Posted, not called.** This runs inside a layout traversal, and
        // `addView` during a traversal calls `requestLayout()` at exactly the
        // moment `requestLayout()` is ignored: the flag is already set and the
        // pass is already past this view. So the rows built here are never
        // measured, and they sit at zero by zero -- while the rows built
        // *before* the panel was shown, which were about to be laid out
        // correctly, have just been removed to make way for them.
        //
        // The panel opened completely empty on a phone and no test could see
        // it: every one of them asks `ChipRows` for the arithmetic or reads
        // the source for the wiring, and neither of those is a layout pass.
        post { rebuild() }
    }

    private fun rebuild() {
        val t = theme ?: return
        if (width == builtForWidth) return
        builtForWidth = width
        column.removeAllViews()
        if (words.isEmpty()) return
        val density = resources.displayMetrics.density
        val availableDp = ((width - paddingLeft - paddingRight) / density).toInt()
        val columns = ChipRows.columnsFor(availableDp)
        for (row in ChipRows.rows(words, columns)) {
            val rowView = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            for (i in 0 until columns) {
                val word = row.getOrNull(i)
                val cell: View = if (word == null) {
                    // A blank cell rather than a short row. Without it the last
                    // row's chips would share the whole width between them and
                    // sit under nothing, which reads as a different kind of
                    // suggestion rather than as the end of the list.
                    View(context)
                } else {
                    chipFor(word, t)
                }
                val lp = LinearLayout.LayoutParams(0, dp(CHIP_H_DP), 1f)
                lp.setMargins(dp(CHIP_GAP_DP), dp(CHIP_GAP_DP), dp(CHIP_GAP_DP), 0)
                rowView.addView(cell, lp)
            }
            column.addView(
                rowView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        // Room under the last row, so the bottom chip is not flush against the
        // close button.
        column.addView(
            View(context),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(CHIP_GAP_DP)
            )
        )
    }

    private fun chipFor(word: String, t: KeyboardTheme): TextView = TextView(context).apply {
        text = word
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        maxLines = 1
        // The same rule as the strip, and needed here too: a four-column grid
        // on a 393dp phone gives a chip about 90dp, and "congratulations"
        // arrived as `cong...ions`. A panel opened on purpose to read more
        // words is the last place a word should be unreadable.
        setAutoSizeTextTypeUniformWithConfiguration(12, 15, 1, TypedValue.COMPLEX_UNIT_SP)
        // MIDDLE, like the strip: the ends of a long word are what tell you
        // which word it is.
        ellipsize = TextUtils.TruncateAt.MIDDLE
        setPadding(dp(8), 0, dp(8), 0)
        setTextColor(t.stripText)
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(t.keyBg)
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { listener?.onExpandedWordPicked(word) }
    }
}
