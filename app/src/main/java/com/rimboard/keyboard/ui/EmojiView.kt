package com.rimboard.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.rimboard.keyboard.R
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
    private val searchBtn: TextView
    private val backBtn: TextView
    private val tabScroll: HorizontalScrollView
    private var t: KeyboardTheme? = null
    private var recents: List<String> = emptyList()
    private var tab = 1

    // ---- search state ----
    private val searchBar: LinearLayout
    private val queryView: TextView
    private val clearBtn: TextView
    private val resultsScroll: HorizontalScrollView
    private val resultsRow: LinearLayout
    private val keypad: LinearLayout
    private val keypadKeys = ArrayList<TextView>()
    private val query = StringBuilder()
    private var searching = false
    private var searchLang = "en"
    private val searchIndexCache = HashMap<String, List<Pair<String, List<String>>>>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        /** Unicode skin-tone modifiers, light to dark. */
        val TONES = listOf("🏻", "🏼", "🏽", "🏾", "🏿")
    }

    init {
        orientation = VERTICAL

        // -- search bar (top, hidden until searching) --
        searchBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = GONE
        }
        val searchIcon = TextView(context).apply {
            text = "🔍"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        searchBar.addView(searchIcon, LayoutParams(dp(40), LayoutParams.MATCH_PARENT))
        queryView = TextView(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            hint = context.getString(R.string.emoji_search)
        }
        searchBar.addView(queryView, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        clearBtn = barButton("✕", 16f) { onClear() }
        searchBar.addView(clearBtn, LayoutParams(dp(44), LayoutParams.MATCH_PARENT))
        addView(searchBar, LayoutParams(LayoutParams.MATCH_PARENT, dp(42)))

        // -- category / recents grid (normal mode) --
        adapterImpl = EmojiAdapter()
        grid = GridView(context).apply {
            numColumns = 8
            adapter = adapterImpl
            isVerticalScrollBarEnabled = false
            setOnItemClickListener { _, _, position, _ ->
                adapterImpl.items.getOrNull(position)?.let { listener?.onEmoji(it) }
            }
            setOnItemLongClickListener { _, view, position, _ ->
                val e = adapterImpl.items.getOrNull(position)
                val variants = e?.let { skinToneVariants(it) } ?: emptyList()
                if (variants.size > 1) {
                    showVariants(variants, view)
                    true
                } else false
            }
        }
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        // -- search results row (hidden until searching) --
        resultsRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        resultsScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            visibility = GONE
            addView(resultsRow)
        }
        addView(resultsScroll, LayoutParams(LayoutParams.MATCH_PARENT, dp(52)))

        // -- mini QWERTY keypad (hidden until searching) --
        keypad = LinearLayout(context).apply {
            orientation = VERTICAL
            visibility = GONE
        }
        keypad.addView(keypadRow("qwertyuiop", null), LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        keypad.addView(keypadRow("asdfghjkl", null), LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        keypad.addView(keypadRow("zxcvbnm", "⌫"), LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(keypad, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        // -- bottom bar (always visible): ABC | search | tabs | backspace --
        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        abcBtn = barButton("ABC", 14f) { listener?.onAbc() }
        bar.addView(abcBtn, LayoutParams(dp(52), LayoutParams.MATCH_PARENT))
        searchBtn = barButton("🔍", 16f) { if (searching) exitSearch() else enterSearch() }
        bar.addView(searchBtn, LayoutParams(dp(40), LayoutParams.MATCH_PARENT))

        tabScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
        }
        val tabs = LinearLayout(context).apply { orientation = HORIZONTAL }
        val icons = ArrayList<String>()
        icons.add("🕐") // recents
        EmojiData.categories.forEach { icons.add(it.icon) }
        icons.forEachIndexed { i, icon ->
            val tv = barButton(icon, 18f) { selectTab(i) }
            tabViews.add(tv)
            tabs.addView(tv, LayoutParams(dp(44), LayoutParams.MATCH_PARENT))
        }
        tabScroll.addView(tabs)
        bar.addView(tabScroll, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        backBtn = barButton("⌫", 18f) { }
        setupBackspaceRepeat(backBtn)
        bar.addView(backBtn, LayoutParams(dp(52), LayoutParams.MATCH_PARENT))

        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, dp(46)))
        selectTab(1)
    }

    /**
     * [base] plus its five skin-tone variants, or just [base] when the emoji
     * doesn't take a tone. Support is probed with `hasGlyph` rather than kept as
     * a hand-maintained list, so this follows whatever the device's font
     * actually renders.
     */
    private fun skinToneVariants(base: String): List<String> {
        // Strip any tone already applied (recents store the toned form), so a
        // long-press on a toned emoji still offers the full set.
        val neutral = TONES.fold(base) { acc, t -> acc.replace(t, "") }
        val stem = neutral.replace("️", "")
        val out = ArrayList<String>(6)
        out.add(neutral)
        for (tone in TONES) {
            val v = stem + tone
            if (glyphPaint.hasGlyph(v)) out.add(v)
        }
        return out
    }

    /** Little popup row of tone variants above the long-pressed emoji. */
    private fun showVariants(variants: List<String>, anchor: View) {
        val theme = t
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(theme?.previewBg ?: 0xFF303030.toInt())
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val popup = android.widget.PopupWindow(
            row, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, true
        )
        popup.isOutsideTouchable = true
        for (v in variants) {
            row.addView(TextView(context).apply {
                text = v
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                theme?.let { setTextColor(it.keyText) }
                setOnClickListener {
                    listener?.onEmoji(v)
                    popup.dismiss()
                }
            }, LayoutParams(dp(44), dp(44)))
        }
        row.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        // Directly above the pressed emoji, clamped inside the panel.
        val a = IntArray(2)
        anchor.getLocationInWindow(a)
        val me = IntArray(2)
        getLocationInWindow(me)
        val x = (a[0] + anchor.width / 2 - row.measuredWidth / 2)
            .coerceIn(me[0], (me[0] + width - row.measuredWidth).coerceAtLeast(me[0]))
        val y = (a[1] - row.measuredHeight - dp(6)).coerceAtLeast(me[1])
        popup.showAtLocation(this, Gravity.NO_GRAVITY, x, y)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    /** Rounded key-like background with a pressed state, inset so keys keep a gap. */
    private fun keyBackground(theme: KeyboardTheme): Drawable {
        fun rounded(color: Int): Drawable {
            val g = GradientDrawable()
            g.cornerRadius = dp(7).toFloat()
            g.setColor(color)
            return InsetDrawable(g, dp(2), dp(3), dp(2), dp(3))
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), rounded(theme.keyBgPressed))
            addState(intArrayOf(), rounded(theme.keyBg))
        }
    }

    private fun barButton(label: String, sp: Float, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setOnClickListener { onClick() }
        }

    /** One row of the mini search keypad; [trailing] adds a wide backspace key. */
    private fun keypadRow(letters: String, trailing: String?): LinearLayout {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        for (ch in letters) {
            val key = TextView(context).apply {
                text = ch.toString()
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setOnClickListener { appendQuery(ch) }
            }
            keypadKeys.add(key)
            row.addView(key, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        if (trailing != null) {
            val bk = TextView(context).apply {
                text = trailing
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            }
            keypadKeys.add(bk)
            setupBackspaceRepeat(bk)
            row.addView(bk, LayoutParams(0, LayoutParams.MATCH_PARENT, 3f))
        }
        return row
    }

    // Held at class level rather than captured per listener, so a detach can
    // cancel a repeat that is still in flight.
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatRun: Runnable? = null

    private fun stopRepeat() {
        repeatRun?.let { repeatHandler.removeCallbacks(it) }
        repeatRun = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopRepeat()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupBackspaceRepeat(v: TextView) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    handleBackspace()
                    val r = object : Runnable {
                        override fun run() {
                            handleBackspace()
                            repeatHandler.postDelayed(this, 60)
                        }
                    }
                    repeatRun = r
                    repeatHandler.postDelayed(r, 350)
                    view.alpha = 0.5f
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopRepeat()
                    view.alpha = 1f
                }
            }
            true
        }
    }

    /** Backspace deletes from the search query while searching, else from text. */
    private fun handleBackspace() {
        if (searching) {
            if (query.isNotEmpty()) {
                query.deleteCharAt(query.length - 1)
                onQueryChanged()
            }
        } else {
            listener?.onBackspace()
        }
    }

    fun setRecents(list: List<String>) {
        recents = list
        if (!searching && tab == 0) refresh()
    }

    /** Sets the language used for the emoji search index (falls back to English). */
    fun setSearchLang(lang: String) {
        searchLang = lang
        if (searching) exitSearch()
    }

    private fun selectTab(i: Int) {
        val changed = tab != i
        tab = i
        refresh()
        applyTabColors()
        if (changed) {
            // The category swap used to be a hard cut; a short fade reads as
            // the grid changing content rather than flickering.
            grid.animate().cancel()
            grid.alpha = 0.25f
            grid.animate().alpha(1f).setDuration(120)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            ensureTabVisible(i)
        }
    }

    /** Scrolls the tab strip so the selected category sits centred, not offscreen. */
    private fun ensureTabVisible(i: Int) {
        val tv = tabViews.getOrNull(i) ?: return
        tabScroll.post {
            val target = tv.left - (tabScroll.width - tv.width) / 2
            tabScroll.smoothScrollTo(target.coerceAtLeast(0), 0)
        }
    }

    private fun refresh() {
        adapterImpl.items = if (tab == 0) recents else categoryEmojis(tab - 1)
        adapterImpl.notifyDataSetChanged()
        grid.setSelection(0)
    }

    // ---------------------------------------------------------------- search

    /** Same 140ms rise the keyboard's panels use, so search feels related. */
    private fun rise(v: View) {
        v.animate().cancel()
        v.alpha = 0f
        v.translationY = dp(8).toFloat()
        v.animate().alpha(1f).translationY(0f).setDuration(140)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    private fun enterSearch() {
        searching = true
        query.setLength(0)
        searchBar.visibility = VISIBLE
        grid.visibility = GONE
        resultsScroll.visibility = VISIBLE
        keypad.visibility = VISIBLE
        tabScroll.visibility = GONE
        rise(searchBar)
        rise(keypad)
        onQueryChanged()
    }

    private fun exitSearch() {
        searching = false
        query.setLength(0)
        searchBar.visibility = GONE
        resultsScroll.visibility = GONE
        keypad.visibility = GONE
        tabScroll.visibility = VISIBLE
        grid.visibility = VISIBLE
        rise(grid)
        refresh()
    }

    /** Clear the query, or leave search if it is already empty. */
    private fun onClear() {
        if (query.isEmpty()) exitSearch()
        else {
            query.setLength(0)
            onQueryChanged()
        }
    }

    private fun appendQuery(ch: Char) {
        if (query.length >= 24) return
        query.append(ch)
        onQueryChanged()
    }

    private fun onQueryChanged() {
        queryView.text = query.toString()
        val results = searchResults(query.toString())
        resultsRow.removeAllViews()
        val theme = t
        for (e in results) {
            val tv = TextView(context).apply {
                text = e
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                if (theme != null) {
                    setTextColor(theme.keyText)
                    background = keyBackground(theme)
                }
                setOnClickListener { listener?.onEmoji(e) }
            }
            resultsRow.addView(tv, LayoutParams(dp(46), LayoutParams.MATCH_PARENT))
        }
        resultsScroll.scrollTo(0, 0)
    }

    /**
     * Fold letters to ASCII so the ascii search keypad can reach accented
     * keywords (type "kopek" to find "köpek", "cicek" to find "çiçek").
     */
    private fun fold(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(
            when (c) {
                'ı', 'î', 'í', 'ï', 'ì' -> 'i'
                'ş' -> 's'
                'ç' -> 'c'
                'ğ' -> 'g'
                'ö', 'ô', 'ó', 'ò', 'õ' -> 'o'
                'ü', 'û', 'ú', 'ù' -> 'u'
                'á', 'à', 'â', 'ä', 'ã', 'å' -> 'a'
                'é', 'è', 'ê', 'ë' -> 'e'
                'ñ' -> 'n'
                else -> c
            }
        )
        return sb.toString()
    }

    /** Emoji whose keyword starts with [raw], nearest (shortest) keyword first. */
    private fun searchResults(raw: String): List<String> {
        val q = fold(raw.trim().lowercase())
        if (q.isEmpty()) return emptyList()
        val matched = searchIndex()
            .filter { fold(it.first).startsWith(q) }
            .sortedWith(compareBy({ fold(it.first) != q }, { it.first.length }, { it.first }))
        val out = LinkedHashSet<String>()
        for ((_, emojis) in matched) {
            for (e in emojis) {
                if (glyphPaint.hasGlyph(e)) out.add(e)
                if (out.size >= 60) break
            }
            if (out.size >= 60) break
        }
        return out.toList()
    }

    private fun searchIndex(): List<Pair<String, List<String>>> =
        searchIndexCache.getOrPut(searchLang) {
            val merged = LinkedHashMap<String, MutableList<String>>()
            for (lang in listOf(searchLang, "en").distinct()) {
                try {
                    context.assets.open("emoji/search_$lang.txt").bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            val tab = line.indexOf('\t')
                            if (tab > 0) {
                                val kw = line.substring(0, tab)
                                val list = merged.getOrPut(kw) { ArrayList() }
                                line.substring(tab + 1).trim().split(' ').forEach {
                                    if (it.isNotEmpty() && it !in list) list.add(it)
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
            merged.entries.map { it.key to it.value.toList() }
        }

    // ---------------------------------------------------------------- theme

    fun applyTheme(theme: KeyboardTheme) {
        t = theme
        setBackgroundColor(theme.background)
        abcBtn.setTextColor(theme.keyText)
        searchBtn.setTextColor(theme.keyHint)
        backBtn.setTextColor(theme.keyText)
        queryView.setTextColor(theme.keyText)
        queryView.setHintTextColor(theme.keyHint)
        clearBtn.setTextColor(theme.keyHint)
        val panelTint = (theme.accent and 0x00FFFFFF) or 0x14000000
        searchBar.setBackgroundColor(panelTint)
        keypadKeys.forEach {
            it.setTextColor(theme.keyText)
            it.background = keyBackground(theme)
        }
        applyTabColors()
        adapterImpl.notifyDataSetChanged()
        if (searching) onQueryChanged()
    }

    private fun applyTabColors() {
        val theme = t ?: return
        fun pill(color: Int): Drawable {
            val g = GradientDrawable()
            g.cornerRadius = dp(15).toFloat()
            g.setColor(color)
            return InsetDrawable(g, dp(3), dp(7), dp(3), dp(7))
        }
        tabViews.forEachIndexed { i, tv ->
            tv.setTextColor(if (i == tab) theme.accent else theme.keyHint)
            // Rounded pill under the active category (the old flat rectangle
            // ignored the keyboard's corner language), plus a pressed state so
            // the tabs respond to touch like everything else now does.
            tv.background = StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    pill((theme.keyHint and 0x00FFFFFF) or 0x1E000000)
                )
                addState(
                    intArrayOf(),
                    if (i == tab) pill((theme.accent and 0x00FFFFFF) or 0x26000000)
                    else android.graphics.drawable.ColorDrawable(0)
                )
            }
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
