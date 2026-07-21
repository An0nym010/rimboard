package com.rimboard.keyboard.settings

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.rimboard.keyboard.R
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Local typing statistics. Everything stays on this device. */
class StatsActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout

    /**
     * The locale this screen is actually rendered in.
     *
     * Deliberately not Locale.getDefault(). The interface-language setting is
     * applied by building a context with createConfigurationContext (see
     * [L10n]), which changes what resources resolve to but leaves the process
     * default locale alone. Formatting through the default meant that picking,
     * say, German for RimBoard on an English phone gave German labels next to
     * English thousands separators and an English date.
     */
    private val uiLocale: Locale
        get() = resources.configuration.locales.let {
            if (it.isEmpty) Locale.getDefault() else it[0]
        }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(L10n.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setTitle(R.string.pref_stats_title)
        Stats.load(this)
        val d = resources.displayMetrics.density
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (16 * d).toInt(), (20 * d).toInt(), (24 * d).toInt())
        }
        setContentView(ScrollView(this).apply { addView(container) })
        render()
    }

    private fun render() {
        container.removeAllViews()
        val d = resources.displayMetrics.density
        fun row(label: String, value: String) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (10 * d).toInt(), 0, (10 * d).toInt())
            }
            r.addView(TextView(this).apply { text = label; textSize = 16f },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(TextView(this).apply {
                text = value; textSize = 16f
                gravity = Gravity.END
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            container.addView(r)
        }
        val minutes = Stats.activeMs / 60000.0
        val wpm = if (minutes > 0.5) (Stats.words / minutes) else 0.0
        val backRate = if (Stats.keys > 0) Stats.backspaces * 100.0 / Stats.keys else 0.0
        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun tile(value: String, label: String): View {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, (18 * d).toInt(), 0, (16 * d).toInt())
                background = GradientDrawable().apply {
                    cornerRadius = 16 * d
                    setColor(0x1F1A73E8)
                }
            }
            box.addView(TextView(this).apply {
                text = value
                textSize = 30f
                setTextColor(0xFF1A73E8.toInt())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            box.addView(TextView(this).apply { text = label; textSize = 12f })
            return box
        }
        val lp1 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lp1.setMargins(0, 0, (6 * d).toInt(), (14 * d).toInt())
        val lp2 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lp2.setMargins((6 * d).toInt(), 0, 0, (14 * d).toInt())
        tiles.addView(tile(if (wpm > 0) "%.0f".format(uiLocale, wpm) else "\u2014", getString(R.string.st_wpm_short)), lp1)
        tiles.addView(tile("%,d".format(uiLocale, Stats.words), getString(R.string.st_words)), lp2)
        container.addView(tiles)
        row(getString(R.string.st_words), "%,d".format(uiLocale, Stats.words))
        row(getString(R.string.st_keys), "%,d".format(uiLocale, Stats.keys))
        row(getString(R.string.st_backspace), "%.1f%%".format(uiLocale, backRate))
        row(getString(R.string.st_autocorrect), "%,d".format(uiLocale, Stats.autocorrects))
        row(getString(R.string.st_time), "%dh %02dm".format(uiLocale,
            Stats.activeMs / 3600000, (Stats.activeMs / 60000) % 60))
        row(getString(R.string.st_wpm), if (wpm > 0) "%.0f".format(uiLocale, wpm) else "\u2014")
        container.addView(TextView(this).apply {
            text = getString(R.string.st_since,
                DateFormat.getDateInstance(DateFormat.DEFAULT, uiLocale)
                        .format(Date(Stats.since)))
            textSize = 13f
            setPadding(0, (14 * d).toInt(), 0, (18 * d).toInt())
        })
        container.addView(Button(this).apply {
            text = getString(R.string.st_reset)
            setOnClickListener {
                AlertDialog.Builder(this@StatsActivity)
                    .setMessage(R.string.st_reset_confirm)
                    .setPositiveButton(R.string.st_reset) { _, _ ->
                        Stats.reset(this@StatsActivity)
                        render()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        })
    }
}
