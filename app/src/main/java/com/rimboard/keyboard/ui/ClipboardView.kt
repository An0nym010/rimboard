package com.rimboard.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.rimboard.keyboard.R
import com.rimboard.keyboard.theme.KeyboardTheme

/**
 * Clipboard history panel with pinning. Recent items live only in memory;
 * pinned items persist (the user explicitly chose to keep them).
 */
@SuppressLint("ViewConstructor")
class ClipboardView(context: Context) : LinearLayout(context) {

    class Item(val text: String, val pinned: Boolean)

    interface Listener {
        fun onClipPicked(text: String)
        fun onClipPinToggle(text: String, pinned: Boolean)
        fun onClipsCleared()
        fun onAbc()
    }

    var listener: Listener? = null

    private val list: ListView
    private val adapterImpl: ClipAdapter
    private val emptyLabel: TextView
    private val title: TextView
    private val clearBtn: IconView
    private val headerIcon: IconView
    private val abcBtn: TextView
    private var t: KeyboardTheme? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL

        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleIcon = IconView(context, Icons.CLIPBOARD)
        bar.addView(titleIcon, LayoutParams(dp(34), LayoutParams.MATCH_PARENT))
        headerIcon = titleIcon
        title = TextView(context).apply {
            text = context.getString(R.string.clipboard_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(4), 0, 0, 0)
        }
        bar.addView(title, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
        clearBtn = IconView(context, Icons.TRASH).apply {
            setOnClickListener { listener?.onClipsCleared() }
        }
        bar.addView(clearBtn, LayoutParams(dp(52), LayoutParams.MATCH_PARENT))
        abcBtn = TextView(context).apply {
            text = "ABC"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setOnClickListener { listener?.onAbc() }
        }
        bar.addView(abcBtn, LayoutParams(dp(52), LayoutParams.MATCH_PARENT))
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, dp(46)))

        adapterImpl = ClipAdapter()
        list = ListView(context).apply {
            adapter = adapterImpl
            divider = null
        }
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        emptyLabel = TextView(context).apply {
            text = context.getString(R.string.clipboard_empty)
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            visibility = GONE
        }
        addView(emptyLabel, LayoutParams(LayoutParams.MATCH_PARENT, 0, 2f))
    }

    fun setClips(pinned: List<String>, recent: List<String>) {
        adapterImpl.items = pinned.map { Item(it, true) } + recent.map { Item(it, false) }
        adapterImpl.notifyDataSetChanged()
        val empty = adapterImpl.items.isEmpty()
        list.visibility = if (empty) GONE else VISIBLE
        emptyLabel.visibility = if (empty) VISIBLE else GONE
    }

    fun applyTheme(theme: KeyboardTheme) {
        t = theme
        setBackgroundColor(theme.background)
        title.setTextColor(theme.stripText)
        headerIcon.color = theme.stripText
        clearBtn.color = theme.keyText
        abcBtn.setTextColor(theme.keyText)
        emptyLabel.setTextColor(theme.keyHint)
        adapterImpl.notifyDataSetChanged()
    }

    private inner class ClipAdapter : BaseAdapter() {
        var items: List<Item> = emptyList()
        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val item = items[position]
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val tv = TextView(context).apply {
                setPadding(dp(16), dp(10), dp(8), dp(10))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                text = item.text
                setTextColor(t?.keyText ?: 0xFF000000.toInt())
                setOnClickListener { listener?.onClipPicked(item.text) }
            }
            row.addView(tv, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            val pin = IconView(context, Icons.PIN).apply {
                color = t?.keyText ?: 0xFF888888.toInt()
                alpha = if (item.pinned) 1f else 0.35f
                setOnClickListener { listener?.onClipPinToggle(item.text, !item.pinned) }
            }
            row.addView(pin, LayoutParams(dp(44), dp(40)))
            return row
        }
    }
}
