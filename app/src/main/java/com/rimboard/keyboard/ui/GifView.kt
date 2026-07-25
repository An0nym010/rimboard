package com.rimboard.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.rimboard.keyboard.R
import com.rimboard.keyboard.net.Klipy
import com.rimboard.keyboard.theme.KeyboardTheme

/**
 * The GIF picker.
 *
 * Deliberately knows nothing about the network. It renders a query, a list of
 * results, and a status line; the service does the searching and pushes
 * thumbnails in as they arrive. That keeps every request inside the `net`
 * package where `NetGateTest` can see it, and keeps this file a view.
 *
 * There is no text field. An `EditText` inside an IME window fights the very
 * keyboard it is part of for focus — the emoji panel sidesteps this by
 * rendering its query as a label and routing keystrokes into it from the
 * service. Rather than half-build that here, this panel searches for whatever
 * the user already typed and offers category chips when they have typed
 * nothing, which needs no keystroke routing to be useful.
 */
@SuppressLint("ViewConstructor")
class GifView(context: Context) : LinearLayout(context) {

    interface Listener {
        fun onGifSearch(query: String, kind: Klipy.Kind)
        fun onGifPicked(gif: Klipy.Gif)
        fun onGifAbc()
    }

    /** Common openers, for when there is nothing typed to search for. */
    private val chips = listOf("lol", "thanks", "love", "yes", "no", "sorry", "wow", "bye")

    var listener: Listener? = null

    private val query = StringBuilder()
    private var kind = Klipy.Kind.GIF
    private val keypad: MiniKeypad
    private val gifTab: TextView
    private val stickerTab: TextView

    /**
     * Searching on every keystroke would fire a request per letter — billable,
     * rate-limited, and mostly for prefixes nobody wants results for. This
     * waits for a pause in typing instead.
     */
    private val searchDebounceMs = 450L
    private val debounce = Runnable { fireSearch() }

    private val queryView: TextView
    private val status: TextView
    private val attribution: TextView
    private val grid: GridView
    private val adapterImpl = GifAdapter()
    private val chipRow: LinearLayout
    private val chipScroll: HorizontalScrollView
    private val headerIcon: IconView
    private val abcBtn: TextView
    private var theme: KeyboardTheme? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL

        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerIcon = IconView(context, Icons.SEARCH)
        bar.addView(headerIcon, LayoutParams(dp(30), LayoutParams.MATCH_PARENT))
        gifTab = tab(context.getString(R.string.tb_gif)) { switchKind(Klipy.Kind.GIF) }
        bar.addView(gifTab, LayoutParams(dp(52), LayoutParams.MATCH_PARENT))
        stickerTab = tab(context.getString(R.string.tb_sticker)) { switchKind(Klipy.Kind.STICKER) }
        bar.addView(stickerTab, LayoutParams(dp(64), LayoutParams.MATCH_PARENT))
        queryView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(6), 0, 0, 0)
            gravity = Gravity.CENTER_VERTICAL
            // The query can outrun the space available; the tail is the part
            // being typed, so that is the end kept visible.
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.START
        }
        bar.addView(queryView, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        abcBtn = TextView(context).apply {
            text = "ABC"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setOnClickListener { listener?.onGifAbc() }
        }
        bar.addView(abcBtn, LayoutParams(dp(52), LayoutParams.MATCH_PARENT))
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, dp(46)))

        chipRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        chipScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipRow)
        }
        addView(chipScroll, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))

        grid = GridView(context).apply {
            adapter = adapterImpl
            numColumns = 2
            horizontalSpacing = dp(6)
            verticalSpacing = dp(6)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setPadding(dp(8), dp(2), dp(8), dp(8))
            clipToPadding = false
            selector = android.graphics.drawable.ColorDrawable(0)
            setOnItemClickListener { _, _, position, _ ->
                adapterImpl.items.getOrNull(position)?.let { listener?.onGifPicked(it.gif) }
            }
        }
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        status = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            visibility = GONE
        }
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Attribution is a condition of using the API, not a nicety, so it is
        // always present rather than shown when there is room. It sits outside
        // the results so an empty or failed search still carries it.
        attribution = TextView(context).apply {
            text = context.getString(R.string.gif_attribution)
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(0, dp(2), 0, dp(2))
        }
        addView(attribution, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        keypad = MiniKeypad(context).apply {
            listener = object : MiniKeypad.Listener {
                override fun onKeypadChar(c: Char) = appendQuery(c)
                override fun onKeypadBackspace() = backspaceQuery()
            }
        }
        addView(keypad, LayoutParams(LayoutParams.MATCH_PARENT, dp(132)))

        buildChips()
        updateTabs()
    }

    /**
     * The panel is exactly as tall as the keyboard it replaces, which is not a
     * fixed quantity: landscape on a short phone, a small device, and the
     * "compact" height setting can all leave a fraction of what a tall portrait
     * tablet gives. A fixed-height keypad plus chips plus a grid overflows
     * there, pushing the results off the bottom.
     *
     * So the two optional rows yield in order of how little they are missed —
     * chips first, since the keypad is the only way to type a query at all —
     * and the keypad takes a share of the height rather than a constant.
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h <= 0) return
        chipScroll.visibility =
            if (h < dp(320) || query.isNotEmpty()) GONE else VISIBLE
        val keypadH = minOf(dp(132), (h * 0.42f).toInt()).coerceAtLeast(dp(76))
        if (keypad.layoutParams.height != keypadH) {
            keypad.layoutParams = keypad.layoutParams.apply { height = keypadH }
            keypad.requestLayout()
        }
    }

    private fun tab(label: String, onTap: () -> Unit): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setOnClickListener { onTap() }
    }

    private fun switchKind(next: Klipy.Kind) {
        if (kind == next) return
        kind = next
        updateTabs()
        // Re-runs the same query against the other index rather than clearing
        // it: "the same search, as stickers" is the reason to tap this.
        if (query.isNotBlank()) fireSearch() else setResults(emptyList())
    }

    private fun updateTabs() {
        val t = theme ?: return
        gifTab.setTextColor(if (kind == Klipy.Kind.GIF) t.accent else t.keyHint)
        stickerTab.setTextColor(if (kind == Klipy.Kind.STICKER) t.accent else t.keyHint)
    }

    private fun appendQuery(c: Char) {
        if (query.length >= 40) return
        query.append(c)
        onQueryEdited()
    }

    private fun backspaceQuery() {
        if (query.isEmpty()) return
        query.deleteCharAt(query.length - 1)
        onQueryEdited()
    }

    private fun onQueryEdited() {
        queryView.text = query.toString()
        // Height may already have hidden the chips; never un-hide them here.
        chipScroll.visibility =
            if (query.isEmpty() && height >= dp(320)) VISIBLE else GONE
        removeCallbacks(debounce)
        if (query.isBlank()) {
            setResults(emptyList())
            setStatus(null)
        } else {
            postDelayed(debounce, searchDebounceMs)
        }
    }

    private fun fireSearch() {
        val q = query.toString().trim()
        if (q.isNotEmpty()) listener?.onGifSearch(q, kind)
    }

    /**
     * Seeds the panel with a query the service worked out from the field, as
     * though it had been typed — so backspacing edits it rather than starting
     * from nothing.
     */
    fun startWith(seed: String?, startKind: Klipy.Kind = kind) {
        removeCallbacks(debounce)
        kind = startKind
        updateTabs()
        query.setLength(0)
        seed?.let { query.append(it.take(40)) }
        queryView.text = query.toString()
        chipScroll.visibility =
            if (query.isEmpty() && height >= dp(320)) VISIBLE else GONE
        setResults(emptyList())
        setStatus(null)
    }

    /** The panel is going away; nothing should fire into a dead view. */
    fun cancelPending() {
        removeCallbacks(debounce)
    }

    private fun buildChips() {
        chipRow.removeAllViews()
        for (c in chips) {
            val chip = TextView(context).apply {
                text = c
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(14), dp(6), dp(14), dp(6))
                // Seeds the query rather than searching past it, so the chip is
                // a starting point that can then be edited on the keypad.
                setOnClickListener {
                    startWith(c)
                    fireSearch()
                }
            }
            chipRow.addView(chip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
                .apply { setMargins(dp(6), dp(6), 0, dp(6)) })
        }
    }

    fun setResults(results: List<Klipy.Gif>) {
        adapterImpl.items = results.mapTo(mutableListOf()) { Tile(it, null) }
        adapterImpl.notifyDataSetChanged()
        grid.visibility = if (results.isEmpty()) GONE else VISIBLE
    }

    /**
     * Thumbnails arrive one at a time as their downloads finish, so this
     * updates in place rather than rebuilding the list — a full rebuild would
     * reset the scroll position under the user's finger on every arrival.
     */
    fun setThumbnail(id: String, bitmap: Bitmap) {
        val i = adapterImpl.items.indexOfFirst { it.gif.id == id }
        if (i < 0) return
        adapterImpl.items[i].bitmap = bitmap
        adapterImpl.notifyDataSetChanged()
    }

    fun setStatus(text: String?) {
        status.text = text.orEmpty()
        status.visibility = if (text.isNullOrEmpty()) GONE else VISIBLE
    }

    fun applyTheme(t: KeyboardTheme) {
        theme = t
        setBackgroundColor(t.background)
        queryView.setTextColor(t.stripText)
        queryView.hint = context.getString(R.string.gif_pick_or_type)
        queryView.setHintTextColor(t.keyHint)
        headerIcon.color = t.stripText
        abcBtn.setTextColor(t.keyText)
        status.setTextColor(t.keyHint)
        attribution.setTextColor(t.keyHint)
        keypad.applyTheme(t)
        updateTabs()
        for (i in 0 until chipRow.childCount) {
            (chipRow.getChildAt(i) as TextView).apply {
                setTextColor(t.keyText)
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(t.keyBg)
                }
            }
        }
        adapterImpl.notifyDataSetChanged()
    }

    private class Tile(val gif: Klipy.Gif, var bitmap: Bitmap?)

    private inner class GifAdapter : BaseAdapter() {
        var items: MutableList<Tile> = mutableListOf()

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tile = items[position]
            val view = (convertView as? ImageView) ?: ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = android.widget.AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(92)
                )
            }
            view.setImageBitmap(tile.bitmap)
            // A placeholder while the thumbnail is still downloading, so the
            // grid has its final shape immediately instead of reflowing as
            // each image lands.
            if (tile.bitmap == null) {
                view.background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(theme?.keyBg ?: 0x22808080)
                }
            } else {
                view.background = null
            }
            view.contentDescription = tile.gif.description
            return view
        }
    }
}
