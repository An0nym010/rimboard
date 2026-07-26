package com.rimboard.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.rimboard.keyboard.R
import com.rimboard.keyboard.theme.KeyboardTheme

/**
 * Gboard-shaped translate bar: language pair on top, what you are typing
 * below, and the translation itself going straight into the message field.
 *
 * The first version was a full panel showing source *and* result stacked, with
 * an Insert button. That ate most of the keyboard to display two lines and put
 * the target language in Settings, several screens from where anyone would
 * look for it.
 *
 * The one thing deliberately not copied from Gboard is translating on every
 * keystroke. Google's translation is free to Google; here each request is a
 * metered call against the user's own key, so a 40-character sentence would be
 * twenty-odd requests instead of one. The result is inserted automatically —
 * so text appears in the field on its own, as it does in Gboard — but on a
 * pause in typing rather than per letter.
 *
 * There is no swap button because there is nothing to swap: the source
 * language is detected by the model rather than declared. Translating
 * something *into* your own language is done by setting that as the target,
 * which the same control already does.
 */
@SuppressLint("ViewConstructor")
class TranslateView(context: Context) : LinearLayout(context) {

    interface Listener {
        /** Source settled; translate it. */
        fun onTranslateRequest(text: String)

        /** Put this in the field, replacing whatever was last put there. */
        fun onTranslateApply(text: String)

        fun onTranslateClose()

        /** Target changed; the service owns the preference. */
        fun onTranslateTargetPicked(code: String)

        /** The language list needs more room than the bar has. */
        fun onTranslateExpand(expanded: Boolean)
    }

    var listener: Listener? = null

    private val source = StringBuilder()

    /**
     * Long enough to be a pause rather than a gap between keystrokes. Each
     * expiry is one billable request, so this is the difference between a
     * sentence costing one call and costing twenty.
     */
    private val debounceMs = 700L
    private val debounce = Runnable { fire() }

    private val pairRow: LinearLayout
    private val targetChip: TextView
    private val sourceView: TextView
    private val status: TextView
    private val langList: LinearLayout
    private val langScroll: ScrollView
    private var theme: KeyboardTheme? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL

        pairRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(6), dp(2))
        }
        pairRow.addView(TextView(context).apply {
            text = context.getString(R.string.tr_detect)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        })
        pairRow.addView(TextView(context).apply {
            text = "  →  "
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        })
        targetChip = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleLanguageList() }
        }
        pairRow.addView(targetChip)
        pairRow.addView(TextView(context), LayoutParams(0, 1, 1f))
        pairRow.addView(TextView(context).apply {
            text = "✕"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(4), dp(12), dp(4))
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(R.string.panel_close)
            setOnClickListener { listener?.onTranslateClose() }
        })
        addView(pairRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        sourceView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(12), dp(2), dp(12), dp(4))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.START
        }
        addView(sourceView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        status = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(12), 0, dp(12), dp(4))
            visibility = GONE
        }
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        langList = LinearLayout(context).apply { orientation = VERTICAL }
        langScroll = ScrollView(context).apply {
            visibility = GONE
            addView(langList)
        }
        addView(langScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        refresh()
    }

    /** Opens the bar, seeded with a selection when the field had one. */
    fun start(seed: String?, targetLabel: String) {
        removeCallbacks(debounce)
        source.setLength(0)
        seed?.let { source.append(it.take(MAX_CHARS)) }
        targetChip.text = targetLabel
        hideLanguageList()
        setStatus(null)
        refresh()
        if (source.isNotBlank()) fire()
    }

    fun setTargetLabel(label: String) {
        targetChip.text = label
    }

    fun appendQuery(c: Char) {
        if (langScroll.visibility == VISIBLE) return
        if (source.length >= MAX_CHARS) return
        source.append(c)
        onEdited()
    }

    fun backspaceQuery() {
        if (langScroll.visibility == VISIBLE) return
        if (source.isEmpty()) return
        source.deleteCharAt(source.length - 1)
        onEdited()
    }

    private fun onEdited() {
        refresh()
        removeCallbacks(debounce)
        if (source.isBlank()) setStatus(null) else postDelayed(debounce, debounceMs)
    }

    /** Forces the pending translation now, for Enter. */
    fun flush() {
        removeCallbacks(debounce)
        fire()
    }

    private fun fire() {
        val t = source.toString().trim()
        if (t.isNotEmpty()) listener?.onTranslateRequest(t)
    }

    fun setResult(text: String) {
        setStatus(null)
        // Straight into the field. The bar keeps showing what was typed, the
        // way Gboard's does — the translation belongs where it is being sent.
        listener?.onTranslateApply(text)
    }

    fun setStatus(text: String?) {
        status.text = text.orEmpty()
        status.visibility = if (text.isNullOrEmpty()) GONE else VISIBLE
    }

    fun cancelPending() = removeCallbacks(debounce)

    // ---- language list ----

    /**
     * The list lives inside the bar rather than in a dialog.
     *
     * An IME showing a Dialog has to borrow the input view's window token and
     * is fiddly about dismissal; growing the bar we already own avoids the
     * question entirely, and keeps the keys visible underneath.
     */
    fun setLanguages(items: List<Pair<String, String>>, onPick: (String) -> Unit) {
        langList.removeAllViews()
        for ((code, label) in items) {
            langList.addView(TextView(context).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(dp(16), dp(10), dp(16), dp(10))
                isClickable = true
                isFocusable = true
                theme?.let { setTextColor(it.keyText) }
                setOnClickListener {
                    onPick(code)
                    hideLanguageList()
                }
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    private fun toggleLanguageList() {
        if (langScroll.visibility == VISIBLE) hideLanguageList() else showLanguageList()
    }

    private fun showLanguageList() {
        langScroll.visibility = VISIBLE
        sourceView.visibility = GONE
        listener?.onTranslateExpand(true)
    }

    private fun hideLanguageList() {
        langScroll.visibility = GONE
        sourceView.visibility = VISIBLE
        listener?.onTranslateExpand(false)
    }

    /** True while the list is up, so Back closes it before closing the bar. */
    fun isPickingLanguage() = langScroll.visibility == VISIBLE

    fun closeLanguageList() = hideLanguageList()

    private fun refresh() {
        sourceView.text = source.toString().ifEmpty {
            context.getString(R.string.tr_source_hint)
        }
        applyColors()
    }

    fun applyTheme(t: KeyboardTheme) {
        theme = t
        setBackgroundColor(t.background)
        applyColors()
    }

    private fun applyColors() {
        val t = theme ?: return
        for (i in 0 until pairRow.childCount) {
            (pairRow.getChildAt(i) as? TextView)?.setTextColor(t.keyHint)
        }
        targetChip.setTextColor(t.accent)
        targetChip.background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(t.keyBg)
        }
        sourceView.setTextColor(if (source.isEmpty()) t.keyHint else t.keyText)
        status.setTextColor(t.keyHint)
        for (i in 0 until langList.childCount) {
            (langList.getChildAt(i) as? TextView)?.setTextColor(t.keyText)
        }
    }

    private companion object {
        /** Matches AiText's own cap, so the bar cannot build a request it will refuse. */
        const val MAX_CHARS = 2000
    }
}
