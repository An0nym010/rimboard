package com.rimboard.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.rimboard.keyboard.model.KeyProximity
import com.rimboard.keyboard.model.Languages
import com.rimboard.keyboard.settings.Prefs
import com.rimboard.keyboard.theme.KeyboardLook

/**
 * The keyboard, on the Look and feel screen, showing what the settings on that
 * screen actually do.
 *
 * **It is the real [KeyboardView] inside the real [PhotoBackdrop].** Not a
 * drawing of one. Every setting on the screen above it — theme, per-app tint,
 * key borders, gaps, side and bottom padding, label scale, custom font, split,
 * height, the number row, the live background, a background photo — reaches it
 * through the same [KeyboardLook] the keyboard itself goes through, so there is
 * no version of "what it looks like" that can be right here and wrong in the
 * field. A preview that quietly disagreed with the keyboard would be worse than
 * no preview: it would be a picture of a keyboard nobody has.
 *
 * **It does not type.** The keyboard is a live view with a full touch
 * implementation — long-press popups, glide, key repeat, cursor control on the
 * space bar — and all of it would fire under a finger here, against no input
 * connection, with a `listener` of null. So touches are swallowed whole. The
 * cost is that the preview does not respond to a press, which is the right
 * trade: a preview that half-works is a bug report, and the keyboard is one
 * tap away in the field at the bottom of the setup screen if somebody wants to
 * feel it.
 *
 * The strip carries three fixed words rather than real suggestions, because the
 * engine is not running in this process and a strip drawn empty would say the
 * theme had no strip colours at all.
 */
@SuppressLint("ViewConstructor")
class KeyboardPreviewView(context: Context) : FrameLayout(context) {

    /**
     * The same root the service builds: `PhotoBackdrop` paints the photo or the
     * live background, and the strip and the keyboard are its children, so
     * whatever it paints is underneath both. It is `final`, so this contains one
     * rather than being one — which is also what `RimBoardService` does.
     */
    private val backdrop = PhotoBackdrop(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    private val strip = SuggestionStripView(context)
    private val keyboard = KeyboardView(context)

    init {
        isClickable = false
        isFocusable = false
        backdrop.addView(
            strip,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44))
        )
        backdrop.addView(
            keyboard,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        addView(
            backdrop,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /**
     * Swallowed at the group, before the keyboard sees a DOWN.
     *
     * `onInterceptTouchEvent` rather than disabling the children: a disabled
     * `KeyboardView` still draws its pressed states from a stale pointer map if
     * anything ever re-enables it, and the strip's chips are real clickable
     * views that would otherwise report a suggestion nobody can accept.
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

    override fun onTouchEvent(ev: MotionEvent): Boolean = true

    /**
     * Re-reads every Look and feel preference and redraws.
     *
     * Called when the screen resumes and on every preference change, which is
     * what makes this a preview rather than a screenshot.
     */
    fun refresh() {
        val ctx = context
        // The host app is this one: the preview is drawn *in* the settings app,
        // so per-app tint answering for RimBoard's own package is not an
        // approximation, it is the truth.
        // Fill this process's palette cache if it is cold, and draw again when
        // it is warm. Without this the preview shows the package-name fallback
        // hue while the keyboard shows the app's real one -- two different
        // keyboards, one of them a fiction.
        KeyboardLook.warm(ctx, ctx.packageName) { post { refresh() } }
        val look = KeyboardLook.of(ctx, ctx.packageName)
        val landscape =
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        KeyboardLook.applyTo(keyboard, ctx, look, landscape)

        // The backdrop's own three settings, exactly as the service sets them.
        backdrop.dimAlpha = look.dimAlpha
        backdrop.starColor = look.drawn.keyText
        backdrop.liveMode = look.liveMode
        backdrop.setBackgroundColor(if (look.clearSurfaces) 0 else look.drawn.background)

        val numberRow = Prefs.numberRow(ctx)
        val langs = Prefs.languages(ctx)
        val code = langs.firstOrNull() ?: "en"
        keyboard.setLayout(
            Languages.byCode(code).layout(numberRow, langs.size > 1),
            "preview/$code/$numberRow/${langs.size > 1}",
            KeyProximity.forLang(code)
        )
        keyboard.spaceLabel = Prefs.spaceText(ctx).takeIf { it.isNotBlank() } ?: ""
        keyboard.enterLabel = "↵"

        strip.applyTheme(look.drawn)
        strip.labelScale = Prefs.labelScalePct(ctx) / 100f
        strip.showSuggestions(SAMPLE, 0)
        invalidate()
    }

    private companion object {
        /**
         * Three words that exercise the strip's three colours: the bold first
         * chip takes the accent, the others `stripText`. Deliberately short, so
         * the row never drops one and the preview cannot look broken because of
         * a legibility rule doing its job.
         */
        val SAMPLE = listOf("the", "and", "you")
    }
}
