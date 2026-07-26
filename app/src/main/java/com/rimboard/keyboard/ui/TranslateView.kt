package com.rimboard.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.rimboard.keyboard.R
import com.rimboard.keyboard.theme.KeyboardTheme

/**
 * Translation with somewhere to type.
 *
 * The tool used to require a selection and overwrite it in place, which meant
 * it could only ever translate text that already existed — there was no way to
 * compose something in your own language and send it in another, which is what
 * people actually reach for a keyboard translator to do. It also gave no
 * preview: you selected, tapped, and whatever came back had already replaced
 * your words.
 *
 * So this is a panel above the keyboard, typed on the real keys like the GIF
 * search. A selection seeds it when there is one, so the old flow still works
 * and now shows you the result before it lands.
 */
@SuppressLint("ViewConstructor")
class TranslateView(context: Context) : LinearLayout(context) {

    interface Listener {
        /** Source text settled; go and translate it. */
        fun onTranslateRequest(text: String)

        /** Put the translation into the field. */
        fun onTranslateInsert(text: String)

        fun onTranslateClose()
    }

    var listener: Listener? = null

    private val source = StringBuilder()

    /**
     * Waits for a pause before translating. Every keystroke is a billable
     * round trip otherwise, and a half-typed sentence is not worth translating.
     */
    private val debounceMs = 700L
    private val debounce = Runnable { fire() }

    private val targetLabel: TextView
    private val sourceView: TextView
    private val resultView: TextView
    private val status: TextView
    private val insertBtn: TextView
    private val closeBtn: TextView
    private var theme: KeyboardTheme? = null
    private var result = ""

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL

        targetLabel = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(12), dp(6), dp(12), dp(2))
        }
        addView(targetLabel)

        // Source above result, in reading order, both scrollable because a
        // paragraph should not push the buttons off the panel.
        sourceView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        addView(scroller(sourceView), LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        resultView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setTypeface(typeface, Typeface.BOLD)
            // Tapping the result inserts it — the same action as the button,
            // where the eye already is.
            isClickable = true
            setOnClickListener { insert() }
        }
        addView(scroller(resultView), LayoutParams(LayoutParams.MATCH_PARENT, 0, 1.2f))

        status = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(12), 0, dp(12), dp(2))
            visibility = GONE
        }
        addView(status)

        val bar = LinearLayout(context).apply { orientation = HORIZONTAL }
        closeBtn = action(context.getString(R.string.panel_close)) {
            listener?.onTranslateClose()
        }
        bar.addView(closeBtn, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        insertBtn = action(context.getString(R.string.tr_insert)) { insert() }
        bar.addView(insertBtn, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        refresh()
    }

    private fun scroller(child: TextView) = ScrollView(context).apply {
        isFillViewport = true
        addView(child)
    }

    private fun action(label: String, onTap: () -> Unit) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(0, dp(8), 0, dp(8))
        isClickable = true
        isFocusable = true
        setOnClickListener { onTap() }
    }

    /** Opens the panel, seeded with a selection if the field had one. */
    fun start(seed: String?, targetName: String) {
        removeCallbacks(debounce)
        source.setLength(0)
        seed?.let { source.append(it.take(MAX_CHARS)) }
        result = ""
        targetLabel.text = context.getString(R.string.tr_target_into, targetName)
        setStatus(null)
        refresh()
        if (source.isNotBlank()) fire()
    }

    fun appendQuery(c: Char) {
        if (source.length >= MAX_CHARS) return
        source.append(c)
        onEdited()
    }

    fun backspaceQuery() {
        if (source.isEmpty()) return
        source.deleteCharAt(source.length - 1)
        onEdited()
    }

    private fun onEdited() {
        // The old result belongs to text that no longer exists, so it goes
        // rather than sitting there looking like a translation of what is now
        // on screen.
        result = ""
        refresh()
        removeCallbacks(debounce)
        if (source.isBlank()) setStatus(null) else postDelayed(debounce, debounceMs)
    }

    private fun fire() {
        val t = source.toString().trim()
        if (t.isNotEmpty()) listener?.onTranslateRequest(t)
    }

    /** Translation arrived. */
    fun setResult(text: String) {
        result = text
        setStatus(null)
        refresh()
    }

    fun setStatus(text: String?) {
        status.text = text.orEmpty()
        status.visibility = if (text.isNullOrEmpty()) GONE else VISIBLE
    }

    fun cancelPending() = removeCallbacks(debounce)

    /**
     * Also reachable from Enter on the real keyboard, which is why it is not
     * private: finishing a translation and pressing Enter should send it, not
     * throw it away.
     */
    fun insertResult() {
        if (result.isBlank()) {
            // Nothing back yet — closing here would discard whatever has been
            // typed, so it stays open and says why instead.
            setStatus(context.getString(R.string.tr_not_ready))
            return
        }
        listener?.onTranslateInsert(result)
    }

    private fun insert() = insertResult()

    private fun refresh() {
        sourceView.text = source.toString().ifEmpty {
            context.getString(R.string.tr_source_hint)
        }
        resultView.text = result
        // Nothing to insert until something has come back, and a button that
        // does nothing is worse than one that is visibly not ready.
        insertBtn.isEnabled = result.isNotBlank()
        insertBtn.alpha = if (result.isNotBlank()) 1f else 0.4f
        applyColors()
    }

    fun applyTheme(t: KeyboardTheme) {
        theme = t
        setBackgroundColor(t.background)
        applyColors()
    }

    private fun applyColors() {
        val t = theme ?: return
        targetLabel.setTextColor(t.keyHint)
        sourceView.setTextColor(if (source.isEmpty()) t.keyHint else t.keyText)
        resultView.setTextColor(t.accent)
        status.setTextColor(t.keyHint)
        closeBtn.setTextColor(t.keyText)
        insertBtn.setTextColor(t.accent)
        insertBtn.background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(t.keyBg)
        }
    }

    private companion object {
        /** Matches AiText's own input cap, so the panel cannot build a request it will refuse. */
        const val MAX_CHARS = 2000
    }
}
