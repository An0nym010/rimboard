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
import com.rimboard.keyboard.engine.UserData

/** Personal dictionary: view, add and remove learned words. */
class PersonalDictActivity : AppCompatActivity() {

    private lateinit var userData: UserData
    private lateinit var adapterImpl: Adapter
    private lateinit var emptyView: TextView
    private var items: List<Pair<String, Int>> = emptyList()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(L10n.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setTitle(R.string.pref_personal_dict_title)
        userData = UserData(this)
        userData.reload()
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val rootL = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val addRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(4))
        }
        val input = EditText(this).apply {
            hint = getString(R.string.pd_add_hint)
            maxLines = 1
        }
        addRow.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val addBtn = Button(this).apply {
            text = "+"
            setOnClickListener {
                val w = input.text.toString().trim()
                if (w.isNotEmpty()) {
                    userData.addUserWord(w)
                    input.setText("")
                    Prefs.setPendingReload(this@PersonalDictActivity, true)
                    refresh()
                }
            }
        }
        addRow.addView(addBtn, LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT))
        rootL.addView(addRow)

        adapterImpl = Adapter()
        val list = ListView(this).apply { adapter = adapterImpl }
        rootL.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        emptyView = TextView(this).apply {
            text = getString(R.string.pd_empty)
            gravity = Gravity.CENTER
            setPadding(0, dp(48), 0, dp(48))
        }
        rootL.addView(emptyView)

        setContentView(rootL)
        refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        // This screen builds its own UserData, so it owns that executor's
        // thread and has to stop it.
        userData.shutdown()
    }

    private fun refresh() {
        items = userData.learnedEntries()
        adapterImpl.notifyDataSetChanged()
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val (word, count) = items[position]
            val d = resources.displayMetrics.density
            val row = LinearLayout(this@PersonalDictActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((16 * d).toInt(), (10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt())
            }
            row.addView(TextView(this@PersonalDictActivity).apply {
                text = "$word  \u00b7  $count"
                textSize = 16f
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this@PersonalDictActivity).apply {
                text = "\u2715"
                textSize = 18f
                setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
                setOnClickListener {
                    userData.removeLearned(word)
                    Prefs.setPendingReload(this@PersonalDictActivity, true)
                    refresh()
                }
            })
            return row
        }
    }
}
