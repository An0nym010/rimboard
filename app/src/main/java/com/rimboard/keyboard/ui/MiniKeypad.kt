package com.rimboard.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.TextView
import com.rimboard.keyboard.theme.KeyboardTheme

/**
 * A compact QWERTY strip that a full-height panel can host to collect a search
 * query.
 *
 * Panels cannot use an `EditText`: inside an IME window it competes for focus
 * with the keyboard it is part of, and the field being typed into belongs to
 * another app entirely. The way round it is for the panel to draw its own keys
 * and keep the query in a `StringBuilder` — which is what the emoji panel has
 * always done inline.
 *
 * Pulled out here so the GIF and sticker panel does not become a second copy
 * of it. `EmojiView` still has its own; converting it is worth doing but wants
 * on-device checking of emoji search first, so it is deliberately left alone
 * rather than changed blind.
 *
 * ASCII-only on purpose. It exists to type search terms for services that index
 * in ASCII, not to replace the keyboard — the real one is a tap away on ABC.
 */
@SuppressLint("ViewConstructor")
class MiniKeypad(context: Context) : LinearLayout(context) {

    interface Listener {
        fun onKeypadChar(c: Char)
        fun onKeypadBackspace()
    }

    var listener: Listener? = null

    private val keys = ArrayList<TextView>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
        addView(row("qwertyuiop", false), LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(row("asdfghjkl", false), LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(row("zxcvbnm", true), LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun row(letters: String, trailingBackspace: Boolean): LinearLayout {
        val r = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        // A space key on the letter rows rather than a fourth row: search terms
        // are two or three words at most, and a row of its own would cost the
        // panel height that the results need more.
        for (ch in letters) {
            r.addView(key(ch.toString()) { listener?.onKeypadChar(ch) },
                LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        if (trailingBackspace) {
            r.addView(key("␣") { listener?.onKeypadChar(' ') },
                LayoutParams(0, LayoutParams.MATCH_PARENT, 1.6f))
            val back = key("⌫") { /* driven by the touch listener below */ }
            setupBackspaceRepeat(back)
            r.addView(back, LayoutParams(0, LayoutParams.MATCH_PARENT, 1.6f))
        }
        return r
    }

    // Held at class level rather than captured per listener, so a detach can
    // cancel a repeat that is still in flight — otherwise dismissing the
    // keyboard mid-hold leaves a Runnable deleting from a query nobody is
    // looking at.
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatRun: Runnable? = null

    private fun stopRepeat() {
        repeatRun?.let { repeatHandler.removeCallbacks(it) }
        repeatRun = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopRepeat()
    }

    /**
     * Hold to delete, matching the real keyboard's backspace.
     *
     * Without this, clearing a mistyped search means tapping once per
     * character — which on a panel whose whole purpose is typing a query is
     * the difference between usable and not.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupBackspaceRepeat(v: TextView) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    listener?.onKeypadBackspace()
                    val r = object : Runnable {
                        override fun run() {
                            listener?.onKeypadBackspace()
                            repeatHandler.postDelayed(this, 60)
                        }
                    }
                    repeatRun = r
                    repeatHandler.postDelayed(r, 350)
                    view.alpha = 0.5f
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopRepeat()
                    view.alpha = 1f
                }
            }
            true
        }
    }

    private fun key(label: String, onTap: () -> Unit): TextView {
        val tv = TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            isClickable = true
            // Announced by their label already; the point is that they are
            // reachable at all, which needs them focusable.
            isFocusable = true
            setOnClickListener {
                performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                onTap()
            }
        }
        keys.add(tv)
        return tv
    }

    fun applyTheme(t: KeyboardTheme) {
        keys.forEach {
            it.setTextColor(t.keyText)
            it.background = keyBackground(t)
        }
    }

    /**
     * Rounded key caps with a pressed state, inset so adjacent keys keep a gap
     * without the row needing margins. Same shape the emoji panel's keypad
     * uses, so the two do not look like different keyboards.
     */
    private fun keyBackground(t: KeyboardTheme): Drawable {
        fun rounded(color: Int): Drawable = InsetDrawable(
            GradientDrawable().apply {
                cornerRadius = dp(7).toFloat()
                setColor(color)
            },
            dp(2), dp(3), dp(2), dp(3)
        )
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), rounded(t.keyBgPressed))
            addState(intArrayOf(), rounded(t.keyBg))
        }
    }
}
