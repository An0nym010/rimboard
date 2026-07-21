package com.rimboard.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.graphics.drawable.GradientDrawable
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.LinearLayout
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

    private val list: GridView
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
            // Icon-only, so it was silent to screen readers — and it is the one
            // destructive control on the panel.
            contentDescription = context.getString(R.string.a11y_clear_clipboard)
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
        list = GridView(context).apply {
            adapter = adapterImpl
            numColumns = 2
            horizontalSpacing = dp(8)
            verticalSpacing = dp(8)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setPadding(dp(10), dp(2), dp(10), dp(10))
            clipToPadding = false
            selector = android.graphics.drawable.ColorDrawable(0)
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
            val th = t
            // Gboard/Yandex-style card: rounded key-coloured tile with the clip
            // text filling it and the pin riding the top-right corner. Built
            // once and rebound thereafter — the grid recycles, and inflating a
            // card plus two children per pass allocated on every scroll frame.
            val card = (convertView as? FrameLayout) ?: FrameLayout(context).apply {
                minimumHeight = dp(72)
                addView(TextView(context).apply {
                    setPadding(dp(12), dp(10), dp(30), dp(10))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                    maxLines = 4
                    ellipsize = TextUtils.TruncateAt.END
                }, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ))
                addView(IconView(context, Icons.PIN),
                    FrameLayout.LayoutParams(dp(34), dp(34), Gravity.TOP or Gravity.END))
            }

            card.background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(th?.keyBg ?: 0xFF333333.toInt())
                if (item.pinned && th != null) {
                    setStroke(dp(1), (th.accent and 0x00FFFFFF) or 0x66000000)
                }
            }
            card.setOnClickListener { listener?.onClipPicked(item.text) }
            card.contentDescription = item.text

            (card.getChildAt(0) as TextView).apply {
                text = item.text
                setTextColor(th?.keyText ?: 0xFF000000.toInt())
            }
            (card.getChildAt(1) as IconView).apply {
                color = if (item.pinned) th?.accent ?: 0xFF888888.toInt()
                    else th?.keyHint ?: 0xFF888888.toInt()
                alpha = if (item.pinned) 1f else 0.55f
                contentDescription = context.getString(
                    if (item.pinned) R.string.a11y_unpin else R.string.a11y_pin
                )
                setOnClickListener { listener?.onClipPinToggle(item.text, !item.pinned) }
            }
            return card
        }
    }
}
