package com.rimboard.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.rimboard.keyboard.theme.KeyboardTheme

@SuppressLint("ViewConstructor")
class EmojiView(context: Context) : LinearLayout(context) {

    interface Listener {
        fun onEmoji(emoji: String)
        fun onAbc()
        fun onBackspace()
    }

    var listener: Listener? = null

    private val grid: GridView
    private val adapterImpl: EmojiAdapter
    private val glyphPaint = android.graphics.Paint()
    private val filteredCache = HashMap<Int, List<String>>()

    private fun categoryEmojis(i: Int): List<String> =
        filteredCache.getOrPut(i) {
            EmojiData.categories[i].emojis.filter { glyphPaint.hasGlyph(it) }
        }
    private val tabViews = ArrayList<TextView>()
    private val abcBtn: TextView
    private val backBtn: TextView
    private var t: KeyboardTheme? = null
    private var recents: List<String> = emptyList()
    private var tab = 1

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL

        adapterImpl = EmojiAdapter()
        grid = GridView(context).apply {
            numColumns = 8
            adapter = adapterImpl
            isVerticalScrollBarEnabled = false
            setOnItemClickListener { _, _, position, _ ->
                adapterImpl.items.getOrNull(position)?.let { listener?.onEmoji(it) }
            }
        }
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        abcBtn = barButton("ABC", 14f) { listener?.onAbc() }
        bar.addView(abcBtn, LayoutParams(dp(52), LayoutParams.MATCH_PARENT))

        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
        }
        val tabs = LinearLayout(context).apply { orientation = HORIZONTAL }
        val icons = ArrayList<String>()
        icons.add("\uD83D\uDD50") // recents
        EmojiData.categories.forEach { icons.add(it.icon) }
        icons.forEachIndexed { i, icon ->
            val tv = barButton(icon, 18f) { selectTab(i) }
            tabViews.add(tv)
            tabs.addView(tv, LayoutParams(dp(44), LayoutParams.MATCH_PARENT))
        }
        scroll.addView(tabs)
        bar.addView(scroll, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        backBtn = barButton("\u232B", 18f) { }
        setupBackspaceRepeat(backBtn)
        bar.addView(backBtn, LayoutParams(dp(52), LayoutParams.MATCH_PARENT))

        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, dp(46)))
        selectTab(1)
    }

    private fun barButton(label: String, sp: Float, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setOnClickListener { onClick() }
        }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupBackspaceRepeat(v: TextView) {
        val h = Handler(Looper.getMainLooper())
        var run: Runnable? = null
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    listener?.onBackspace()
                    val r = object : Runnable {
                        override fun run() {
                            listener?.onBackspace()
                            h.postDelayed(this, 60)
                        }
                    }
                    run = r
                    h.postDelayed(r, 350)
                    view.alpha = 0.5f
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    run?.let { h.removeCallbacks(it) }
                    run = null
                    view.alpha = 1f
                }
            }
            true
        }
    }

    fun setRecents(list: List<String>) {
        recents = list
        if (tab == 0) refresh()
    }

    private fun selectTab(i: Int) {
        tab = i
        refresh()
        applyTabColors()
    }

    private fun refresh() {
        adapterImpl.items = if (tab == 0) recents else categoryEmojis(tab - 1)
        adapterImpl.notifyDataSetChanged()
        grid.setSelection(0)
    }

    fun applyTheme(theme: KeyboardTheme) {
        t = theme
        setBackgroundColor(theme.background)
        abcBtn.setTextColor(theme.keyText)
        backBtn.setTextColor(theme.keyText)
        applyTabColors()
        adapterImpl.notifyDataSetChanged()
    }

    private fun applyTabColors() {
        val theme = t ?: return
        tabViews.forEachIndexed { i, tv ->
            tv.setTextColor(if (i == tab) theme.accent else theme.keyHint)
            tv.setBackgroundColor(
                if (i == tab) (theme.accent and 0x00FFFFFF) or 0x22000000 else 0x00000000
            )
        }
    }

    private inner class EmojiAdapter : BaseAdapter() {
        var items: List<String> = emptyList()
        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tv = (convertView as? TextView) ?: TextView(context).apply {
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                layoutParams = AbsListView.LayoutParams(
                    AbsListView.LayoutParams.MATCH_PARENT, dp(46)
                )
            }
            tv.text = items[position]
            return tv
        }
    }
}
