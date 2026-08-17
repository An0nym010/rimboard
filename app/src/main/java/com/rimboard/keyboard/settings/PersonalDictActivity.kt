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
class PersonalDictActivity : LocalisedActivity() {

    private lateinit var userData: UserData
    private lateinit var adapterImpl: Adapter
    private lateinit var emptyView: TextView
    private var items: List<Pair<String, Int>> = emptyList()

    /** Whether the asynchronous load has landed, so "empty" means anything. */
    private var loaded = false


    /**
     * The rules a hand-added word is folded to lower case with.
     *
     * One personal dictionary serves every enabled language, so there is no
     * answer that is right for all of them at once. The language currently being
     * typed is the one the engine will look the word up under, which makes it
     * the best available guess — and an exactly right one whenever a single
     * language is in use. What matters far more is that it is *a* locale:
     * folding without one mangles Turkish `İ` into a two-character sequence no
     * lookup can produce.
     */
    private fun wordLocale(): java.util.Locale {
        val code = Prefs.currentLang(this)
            ?: Prefs.languages(this).firstOrNull()
            ?: "en"
        return com.rimboard.keyboard.model.Languages.byCode(code).locale
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setTitle(R.string.pref_personal_dict_title)
        userData = UserData(this)
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
                    userData.addUserWord(w, wordLocale())
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
        // The load runs on UserData's writer thread, so the words are not there
        // yet. This used to call refresh() straight after reload() and read the
        // maps reload() had just cleared, so the screen opened empty however
        // many words had been learned — and only filled in if you happened to
        // add or remove one, which refreshes again.
        refresh()
        userData.reload {
            if (isFinishing || isDestroyed) return@reload
            loaded = true
            refresh()
        }
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
        // "No words yet" only once that is actually known: saying it while the
        // load is still in flight is the same wrong answer, just briefer.
        emptyView.visibility = if (loaded && items.isEmpty()) View.VISIBLE else View.GONE
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
