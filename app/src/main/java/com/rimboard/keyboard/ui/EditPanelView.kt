package com.rimboard.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.TextView
import com.rimboard.keyboard.R
import com.rimboard.keyboard.theme.KeyboardTheme

/**
 * Text editing panel: cursor navigation, selection and clipboard actions.
 * Original implementation; the concept is common to many keyboards.
 */
@SuppressLint("ViewConstructor")
class EditPanelView(context: Context) : LinearLayout(context) {

    enum class Action { UP, DOWN, LEFT, RIGHT, HOME, END, SELECT, SELECT_ALL, COPY, CUT, PASTE, UNDO, REDO, TRANSLATE }

    interface Listener {
        fun onEditAction(action: Action)
        fun onAbc()
    }

    var listener: Listener? = null

    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatRun: Runnable? = null

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Cancelled on touch release, but a panel swapped away mid-hold never
        // sees one, leaving the repeat firing at a detached view's listener.
        repeatRun?.let { repeatHandler.removeCallbacks(it) }
        repeatRun = null
    }
    private var t: KeyboardTheme? = null
    private var selectOn = false
    private val buttons = ArrayList<Pair<TextView, Action>>()
    private val title: TextView
    private val abcBtn: TextView
    private val headerBtns = ArrayList<IconView>()
    private val headerIcon: IconView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL

        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleIcon = IconView(context, Icons.EDIT)
        bar.addView(titleIcon, LayoutParams(dp(34), LayoutParams.MATCH_PARENT))
        headerIcon = titleIcon
        title = TextView(context).apply {
            text = context.getString(R.string.edit_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(4), 0, 0, 0)
        }
        bar.addView(title, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        // These three are icon-only, so without a description a screen reader
        // has nothing to announce them by. The labels already existed for the
        // same actions on the toolbar.
        for ((icon, action, desc) in listOf(
            Triple(Icons.TRANSLATE, Action.TRANSLATE, R.string.tb_translate),
            Triple(Icons.UNDO, Action.UNDO, R.string.tb_undo),
            Triple(Icons.REDO, Action.REDO, R.string.tb_redo)
        )) {
            val hb = IconView(context, icon).apply {
                contentDescription = context.getString(desc)
                setOnClickListener { listener?.onEditAction(action) }
            }
            headerBtns.add(hb)
            bar.addView(hb, LayoutParams(dp(44), LayoutParams.MATCH_PARENT))
        }
        abcBtn = TextView(context).apply {
            text = "ABC"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setOnClickListener { listener?.onAbc() }
        }
        bar.addView(abcBtn, LayoutParams(dp(52), LayoutParams.MATCH_PARENT))
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, dp(46)))

        val body = LinearLayout(context).apply { orientation = HORIZONTAL }

        val nav = LinearLayout(context).apply { orientation = VERTICAL }
        nav.addView(
            navRow(listOf("\u21E4" to Action.HOME, "\u25B2" to Action.UP, "\u21E5" to Action.END)),
            rowLp()
        )
        nav.addView(
            navRow(listOf("\u25C0" to Action.LEFT, "\u25BC" to Action.DOWN, "\u25B6" to Action.RIGHT)),
            rowLp()
        )
        body.addView(nav, LayoutParams(0, LayoutParams.MATCH_PARENT, 1.1f))

        val actions = LinearLayout(context).apply { orientation = VERTICAL }
        actions.addView(makeBtn(context.getString(R.string.edit_select), Action.SELECT, false), rowLp())
        actions.addView(makeBtn(context.getString(android.R.string.selectAll), Action.SELECT_ALL, false), rowLp())
        actions.addView(makeBtn(context.getString(android.R.string.copy), Action.COPY, false), rowLp())
        actions.addView(makeBtn(context.getString(android.R.string.cut), Action.CUT, false), rowLp())
        actions.addView(makeBtn(context.getString(android.R.string.paste), Action.PASTE, false), rowLp())
        body.addView(actions, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun rowLp() = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
        setMargins(dp(4), dp(3), dp(4), dp(3))
    }

    private fun navRow(items: List<Pair<String, Action>>): LinearLayout {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        for ((label, action) in items) {
            row.addView(
                makeBtn(label, action, true),
                LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(dp(3), 0, dp(3), 0)
                }
            )
        }
        return row
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeBtn(label: String, action: Action, repeatable: Boolean): TextView {
        val b = TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            maxLines = 1
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (repeatable) 18f else 13f)
        }
        if (repeatable) {
            b.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        listener?.onEditAction(action)
                        val r = object : Runnable {
                            override fun run() {
                                listener?.onEditAction(action)
                                repeatHandler.postDelayed(this, 60)
                            }
                        }
                        repeatRun?.let { repeatHandler.removeCallbacks(it) }
                        repeatRun = r
                        repeatHandler.postDelayed(r, 300)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        repeatRun?.let { repeatHandler.removeCallbacks(it) }
                        repeatRun = null
                    }
                }
                true
            }
        } else {
            b.setOnClickListener { listener?.onEditAction(action) }
        }
        buttons.add(b to action)
        return b
    }

    fun setSelectOn(on: Boolean) {
        selectOn = on
        applyColors()
    }

    fun applyTheme(theme: KeyboardTheme) {
        t = theme
        setBackgroundColor(theme.background)
        title.setTextColor(theme.stripText)
        headerIcon.color = theme.stripText
        abcBtn.setTextColor(theme.keyText)
        headerBtns.forEach { it.color = theme.keyText }
        applyColors()
    }

    private fun applyColors() {
        val theme = t ?: return
        for ((b, action) in buttons) {
            val active = action == Action.SELECT && selectOn
            b.setBackgroundColor(if (active) theme.accent else theme.keyBgFunc)
            b.setTextColor(if (active) theme.onAccent else theme.keyText)
        }
    }
}
