package com.rimboard.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.rimboard.keyboard.theme.KeyboardTheme

@SuppressLint("ViewConstructor")
class SuggestionStripView(context: Context) : LinearLayout(context) {

    interface Listener {
        fun onSuggestionPicked(index: Int, word: String)
        fun onClipboardPasteRequested()
    }

    var listener: Listener? = null

    private var theme: KeyboardTheme? = null
    private val slots = ArrayList<TextView>(3)
    private val dividers = ArrayList<View>(2)
    private val centerLabel: TextView
    private val clipChip: TextView
    private var boldIndex = -1

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), 0, dp(4), 0)

        clipChip = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            visibility = GONE
            setOnClickListener { listener?.onClipboardPasteRequested() }
        }
        addView(clipChip, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

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
            }
            slots.add(tv)
            addView(tv, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }

        centerLabel = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
            visibility = GONE
        }
        addView(centerLabel, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
    }

    fun applyTheme(t: KeyboardTheme) {
        theme = t
        setBackgroundColor(t.background)
        val dividerColor = (t.keyHint and 0x00FFFFFF) or 0x40000000
        dividers.forEach { it.setBackgroundColor(dividerColor) }
        centerLabel.setTextColor(t.keyHint)
        clipChip.setTextColor(t.stripText)
        refreshSlotColors()
    }

    private fun refreshSlotColors() {
        val t = theme ?: return
        for (i in slots.indices) {
            slots[i].setTextColor(if (i == boldIndex) t.accent else t.stripText)
        }
    }

    fun showSuggestions(words: List<String>, highlightIndex: Int) {
        boldIndex = highlightIndex
        clipChip.visibility = GONE
        centerLabel.visibility = GONE
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
        centerLabel.text = "\uD83D\uDD76  $label"
        centerLabel.visibility = VISIBLE
    }

    fun showClipboard(label: String) {
        hideAll()
        clipChip.text = label
        clipChip.visibility = VISIBLE
    }

    fun showEmpty() {
        hideAll()
    }

    private fun hideAll() {
        boldIndex = -1
        slots.forEach {
            it.text = ""
            it.visibility = GONE
        }
        dividers.forEach { it.visibility = GONE }
        clipChip.visibility = GONE
        centerLabel.visibility = GONE
    }
}
