package com.rimboard.keyboard.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.rimboard.keyboard.R
import com.rimboard.keyboard.ui.IconView
import com.rimboard.keyboard.ui.ToolCatalog

/**
 * Arrange the shortcuts pinned to the idle suggestion strip.
 *
 * Replaces a multi-select checkbox dialog, which could say *which* tools you
 * wanted but never what order they appeared in. Checked tools sit at the top in
 * the order shown; long-press any row to pick it up and drag it.
 */
class ToolbarPickerActivity : AppCompatActivity() {

    private lateinit var rows: LinearLayout
    private val checks = HashMap<String, CheckBox>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        /** Every row is this tall, which is what makes the drag maths exact. */
        const val ROW_H_DP = 56
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(L10n.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setTitle(R.string.pref_toolbar_title)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(TextView(this).apply {
            setText(R.string.toolbar_picker_hint)
            textSize = 13f
            alpha = 0.7f
            setPadding(dp(20), dp(16), dp(20), dp(12))
        })

        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Pinned tools first in the user's order, then everything still unused.
        val pinned = Prefs.pinnedTools(this)
        val ordered = pinned.mapNotNull { ToolCatalog.byId(it) } +
            ToolCatalog.all.filter { it.id !in pinned }
        for (tool in ordered) rows.addView(rowFor(tool, tool.id in pinned))

        root.addView(ScrollView(this).apply { addView(rows) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun rowFor(tool: ToolCatalog.Tool, enabled: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(12), 0)
            tag = tool.id
        }
        row.addView(IconView(this, tool.icon).apply {
            color = Color.GRAY
        }, LinearLayout.LayoutParams(dp(30), dp(30)))

        row.addView(TextView(this).apply {
            setText(tool.labelRes)
            textSize = 16f
            setPadding(dp(16), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val check = CheckBox(this).apply {
            isChecked = enabled
            // The label already names the tool; without this TalkBack reads
            // every checkbox as an anonymous "checkbox".
            contentDescription = getString(tool.labelRes)
            setOnCheckedChangeListener { _, _ -> save() }
        }
        checks[tool.id] = check
        row.addView(check)

        row.addView(TextView(this).apply {
            text = "≡" // drag handle
            textSize = 20f
            alpha = 0.45f
            setPadding(dp(14), 0, dp(6), 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })

        bindDrag(row)
        row.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_H_DP)
        )
        return row
    }

    // ---- drag-to-reorder ----

    private val dragHandler = Handler(Looper.getMainLooper())
    private var dragArm: Runnable? = null
    private var dragging: View? = null
    private var dragOriginY = 0f

    @SuppressLint("ClickableViewAccessibility")
    private fun bindDrag(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragOriginY = e.rawY
                    dragging = null
                    val r = Runnable {
                        dragging = view
                        view.alpha = 0.9f
                        view.scaleX = 1.03f
                        view.scaleY = 1.03f
                        view.elevation = dp(8).toFloat()
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }
                    dragArm = r
                    dragHandler.postDelayed(r, 280)
                    false // let the checkbox still receive taps
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging === view) {
                        view.translationY = e.rawY - dragOriginY
                        maybeSwap(view)
                        true
                    } else {
                        if (kotlin.math.abs(e.rawY - dragOriginY) > dp(10)) disarm()
                        false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    disarm()
                    if (dragging === view) {
                        drop(view)
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    }

    private fun disarm() {
        dragArm?.let { dragHandler.removeCallbacks(it) }
        dragArm = null
    }

    /**
     * Swap with a neighbour once the dragged row's centre passes theirs.
     *
     * Positions come from index arithmetic rather than `view.top`, which is
     * stale between the reorder and the next layout pass — reading it makes a
     * fast drag double-swap.
     */
    private fun maybeSwap(v: View) {
        val idx = rows.indexOfChild(v)
        if (idx < 0) return
        val h = dp(ROW_H_DP)
        val centre = idx * h + h / 2f + v.translationY
        if (idx > 0 && centre < (idx - 1) * h + h / 2f) {
            move(v, idx, idx - 1)
            return
        }
        if (idx < rows.childCount - 1 && centre > (idx + 1) * h + h / 2f) {
            move(v, idx, idx + 1)
        }
    }

    private fun move(v: View, from: Int, to: Int) {
        rows.removeViewAt(from)
        rows.addView(v, to)
        // Rows share a height, so the view shifts by exactly one slot: move the
        // drag origin with it to keep the row under the finger.
        dragOriginY += (to - from) * dp(ROW_H_DP).toFloat()
    }

    private fun drop(v: View) {
        dragging = null
        v.animate().translationY(0f).scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(140).withEndAction { v.elevation = 0f }.start()
        save()
    }

    /** Persist the checked tools in their current on-screen order. */
    private fun save() {
        val ids = ArrayList<String>(rows.childCount)
        for (i in 0 until rows.childCount) {
            val id = rows.getChildAt(i).tag as? String ?: continue
            if (checks[id]?.isChecked == true) ids.add(id)
        }
        Prefs.setPinnedTools(this, ids)
    }
}
