package com.rimboard.keyboard.settings

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.rimboard.keyboard.R

/** Manage text shortcuts: short codes that expand into full phrases. */
class ShortcutsActivity : AppCompatActivity() {

    private lateinit var adapterImpl: Adapter
    private lateinit var emptyView: TextView
    private var items: List<Pair<String, String>> = emptyList()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(L10n.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setTitle(R.string.pref_shortcuts_title)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val rootL = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val addRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(4))
        }
        val keyIn = EditText(this).apply {
            hint = getString(R.string.sc_key_hint)
            maxLines = 1
        }
        val phraseIn = EditText(this).apply {
            hint = getString(R.string.sc_phrase_hint)
            maxLines = 1
        }
        addRow.addView(keyIn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f))
        addRow.addView(phraseIn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
        val addBtn = Button(this).apply {
            text = "+"
            setOnClickListener {
                val k = keyIn.text.toString().trim()
                val v = phraseIn.text.toString().trim()
                if (k.isNotEmpty() && v.isNotEmpty()) {
                    Shortcuts.put(this@ShortcutsActivity, k, v)
                    keyIn.setText("")
                    phraseIn.setText("")
                    refresh()
                }
            }
        }
        addRow.addView(addBtn, LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT))
        rootL.addView(addRow)

        adapterImpl = Adapter()
        rootL.addView(ListView(this).apply { adapter = adapterImpl },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        emptyView = TextView(this).apply {
            text = getString(R.string.sc_empty)
            gravity = Gravity.CENTER
            setPadding(0, dp(48), 0, dp(48))
        }
        rootL.addView(emptyView)
        setContentView(rootL)
        refresh()
    }

    private fun refresh() {
        items = Shortcuts.all(this).toList()
        adapterImpl.notifyDataSetChanged()
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val (k, v) = items[position]
            val d = resources.displayMetrics.density
            val row = LinearLayout(this@ShortcutsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((16 * d).toInt(), (10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt())
            }
            row.addView(TextView(this@ShortcutsActivity).apply {
                text = "$k  \u2192  $v"
                textSize = 15f
                maxLines = 2
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this@ShortcutsActivity).apply {
                text = "\u2715"
                textSize = 18f
                setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
                setOnClickListener {
                    Shortcuts.remove(this@ShortcutsActivity, k)
                    refresh()
                }
            })
            return row
        }
    }
}
