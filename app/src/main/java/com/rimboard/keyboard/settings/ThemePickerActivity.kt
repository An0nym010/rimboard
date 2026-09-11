package com.rimboard.keyboard.settings

import android.content.res.Configuration
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rimboard.keyboard.R
import com.rimboard.keyboard.theme.Themes
import com.rimboard.keyboard.ui.ThemeThumbView

/**
 * Pick a theme by looking at it.
 *
 * This replaces a `ListPreference` of twenty-three words. A theme is the thing
 * people change most about a keyboard, and the names do not carry what the
 * choice is about: nothing in "sage" against "mint" says which is darker, and
 * nothing at all says whether the lettering on one of them can be read.
 *
 * **The grid is also the instrument for a decision that is owed.**
 * `KeyboardContrastTest` pins eight theme pairs under WCAG AA and the fix is a
 * design call nobody has made. Twenty themes in a list gives no way to ask
 * whether twenty is the right number; twenty thumbnails answers it on sight —
 * two that are indistinguishable at this size are indistinguishable on the
 * keyboard too.
 *
 * `system` and `dynamic` are resolved against the current configuration, so
 * what they show is what they would actually give *now*. That is honest rather
 * than tidy: "system" genuinely is a different keyboard in the evening.
 */
class ThemePickerActivity : LocalisedActivity() {

    private lateinit var values: List<String>
    private lateinit var labels: List<String>
    private var chosen: String = "system"
    private var grid: RecyclerView? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setTitle(R.string.pref_theme_title)

        values = resources.getStringArray(R.array.theme_values).toList()
        labels = resources.getStringArray(R.array.theme_entries).toList()
        chosen = Prefs.theme(this)

        val list = RecyclerView(this).apply {
            // Two columns on a phone, four when there is room. A thumbnail
            // narrower than about 140dp stops being a keyboard and starts
            // being a colour swatch, which is the picker this replaces.
            val wide = resources.configuration.screenWidthDp >= 600
            layoutManager = GridLayoutManager(this@ThemePickerActivity, if (wide) 4 else 2)
            adapter = Adapter()
            setPadding(dp(8), dp(8), dp(8), dp(8))
            clipToPadding = false
        }
        grid = list
        setContentView(list)
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {

        override fun getItemCount() = values.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val cell = LinearLayout(this@ThemePickerActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), dp(6), dp(6), dp(10))
            }
            val thumb = ThemeThumbView(this@ThemePickerActivity)
            cell.addView(
                thumb,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96))
            )
            val label = TextView(this@ThemePickerActivity).apply {
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dp(7), 0, 0)
                maxLines = 1
            }
            cell.addView(
                label,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            cell.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            return Holder(cell, thumb, label)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val value = values[position]
            holder.thumb.theme = Themes.resolve(this@ThemePickerActivity, value)
            holder.thumb.chosen = value == chosen
            holder.label.text = labels.getOrNull(position) ?: value
            holder.label.alpha = if (value == chosen) 1f else 0.72f
            holder.itemView.setOnClickListener {
                val was = values.indexOf(chosen)
                chosen = value
                Prefs.setTheme(this@ThemePickerActivity, value)
                if (was >= 0) notifyItemChanged(was)
                notifyItemChanged(position)
                // Left open rather than finishing: choosing a theme is a thing
                // people do several times before settling, and a picker that
                // closes on the first tap makes comparing two of them a
                // navigation exercise.
            }
        }
    }

    private class Holder(
        cell: LinearLayout,
        val thumb: ThemeThumbView,
        val label: TextView
    ) : RecyclerView.ViewHolder(cell)

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // `system` and `dynamic` resolve against night mode, so the grid is
        // stale the moment it changes.
        grid?.adapter?.notifyDataSetChanged()
    }
}
