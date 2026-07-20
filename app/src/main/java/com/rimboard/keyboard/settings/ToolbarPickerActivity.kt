package com.rimboard.keyboard.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rimboard.keyboard.R
import com.rimboard.keyboard.ui.IconView
import com.rimboard.keyboard.ui.ToolCatalog

/**
 * Arrange the shortcuts pinned to the idle suggestion strip.
 *
 * Replaces a multi-select checkbox dialog, which could say *which* tools you
 * wanted but never what order they appeared in.
 *
 * Built on RecyclerView + [ItemTouchHelper] rather than a hand-rolled drag: it
 * brings edge auto-scroll (the list is twenty rows tall, so reaching the top
 * from the bottom has to scroll mid-gesture) and it owns the touch stream, so
 * dragging cannot fight the scroll container or the row's own checkbox.
 */
class ToolbarPickerActivity : AppCompatActivity() {

    /** Every tool, in display order; the checked ones are what get pinned. */
    private val order = ArrayList<String>()
    private val enabled = HashSet<String>()

    private lateinit var touchHelper: ItemTouchHelper
    private val adapter = Adapter()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(L10n.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setTitle(R.string.pref_toolbar_title)

        val pinned = Prefs.pinnedTools(this)
        enabled.addAll(pinned)
        // Pinned tools first in the user's order, then everything still unused.
        order.addAll(pinned.filter { ToolCatalog.byId(it) != null })
        order.addAll(ToolCatalog.defaultOrder.filter { it !in enabled })

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(TextView(this).apply {
            setText(R.string.toolbar_picker_hint)
            textSize = 13f
            alpha = 0.7f
            setPadding(dp(20), dp(16), dp(20), dp(12))
        })

        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ToolbarPickerActivity)
            adapter = this@ToolbarPickerActivity.adapter
            setHasFixedSize(true)
        }
        touchHelper = ItemTouchHelper(DragCallback()).also { it.attachToRecyclerView(list) }

        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    /** Persist the checked tools in their current on-screen order. */
    private fun save() {
        Prefs.setPinnedTools(this, order.filter { it in enabled })
    }

    /**
     * Reorders without persisting: a drag fires this once per row crossed, and
     * saving each step would mean a preferences write per frame of the gesture.
     * Callers that end an interaction call [save].
     */
    private fun move(from: Int, to: Int) {
        if (from == to) return
        order.add(to, order.removeAt(from))
        adapter.notifyItemMoved(from, to)
    }

    // ---- list ----

    private class Holder(
        val row: LinearLayout,
        val icon: IconView,
        val label: TextView,
        val check: CheckBox,
        val handle: TextView
    ) : RecyclerView.ViewHolder(row)

    private inner class Adapter : RecyclerView.Adapter<Holder>() {

        override fun getItemCount() = order.size

        @SuppressLint("ClickableViewAccessibility")
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = LinearLayout(this@ToolbarPickerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(12), 0)
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(56)
                )
            }
            val icon = IconView(this@ToolbarPickerActivity, 0).apply { color = Color.GRAY }
            row.addView(icon, LinearLayout.LayoutParams(dp(30), dp(30)))

            val label = TextView(this@ToolbarPickerActivity).apply {
                textSize = 16f
                setPadding(dp(16), 0, 0, 0)
            }
            row.addView(
                label,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )

            val check = CheckBox(this@ToolbarPickerActivity)
            row.addView(check)

            val handle = TextView(this@ToolbarPickerActivity).apply {
                text = "≡"
                textSize = 20f
                alpha = 0.45f
                setPadding(dp(14), 0, dp(6), 0)
            }
            row.addView(handle)

            return Holder(row, icon, label, check, handle)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val tool = ToolCatalog.byId(order[position]) ?: return
            holder.icon.icon = tool.icon
            holder.label.setText(tool.labelRes)

            // Rebinding a recycled row must not fire the previous row's listener.
            holder.check.setOnCheckedChangeListener(null)
            holder.check.isChecked = tool.id in enabled
            // The label already names the tool; without this TalkBack announces
            // every row as an anonymous "checkbox".
            holder.check.contentDescription = getString(tool.labelRes)
            holder.check.setOnCheckedChangeListener { _, checked ->
                if (checked) enabled.add(tool.id) else enabled.remove(tool.id)
                save()
            }

            // Touching the handle starts a drag immediately — no long-press race
            // with the checkbox, and no ambiguity with the list's own scrolling.
            holder.handle.contentDescription = getString(R.string.a11y_reorder_handle)
            holder.handle.setOnTouchListener { v, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    touchHelper.startDrag(holder)
                    v.performClick()
                }
                false
            }

            // Dragging is unusable with a screen reader, so expose the same
            // reordering as two explicit actions on the row.
            ViewCompat.addAccessibilityAction(
                holder.row, getString(R.string.a11y_move_up)
            ) { _, _ ->
                val i = holder.bindingAdapterPosition
                if (i > 0) { move(i, i - 1); save(); true } else false
            }
            ViewCompat.addAccessibilityAction(
                holder.row, getString(R.string.a11y_move_down)
            ) { _, _ ->
                val i = holder.bindingAdapterPosition
                if (i in 0 until order.size - 1) { move(i, i + 1); save(); true } else false
            }
        }
    }

    // ---- drag ----

    private inner class DragCallback : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
    ) {
        override fun onMove(
            rv: RecyclerView,
            holder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            move(holder.bindingAdapterPosition, target.bindingAdapterPosition)
            return true
        }

        override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) = Unit

        /** Long-pressing a row works too, but the handle is the obvious target. */
        override fun isLongPressDragEnabled() = true

        override fun onSelectedChanged(holder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(holder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                holder?.itemView?.let {
                    it.elevation = dp(8).toFloat()
                    it.alpha = 0.95f
                }
            }
        }

        override fun clearView(rv: RecyclerView, holder: RecyclerView.ViewHolder) {
            super.clearView(rv, holder)
            // Always restore: the row is recycled and would otherwise stay lifted.
            holder.itemView.elevation = 0f
            holder.itemView.alpha = 1f
            save() // the gesture is over, so persist the arrangement once
        }
    }
}
