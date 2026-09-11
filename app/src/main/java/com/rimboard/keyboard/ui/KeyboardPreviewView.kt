package com.rimboard.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.view.View
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
 * **It works under a finger, and produces nothing.** Keys light up, the
 * character bubble appears, a long press opens its popup, `?123` and shift
 * change the layout, and the chevron opens the tool drawer. What is missing is
 * the one thing a preview must not do: there is no input connection here, so
 * no key writes anywhere.
 *
 * That is a real distinction rather than a happy accident. Everything that
 * *shows* something is the view's own business and needs no listener; every
 * path that would produce text goes out through [KeyboardView.Listener], and
 * [Inert] implements exactly the members that change what is on screen and
 * ignores the rest. A key that would type is not blocked — it is simply never
 * carried anywhere.
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

    /** Which layout the preview is showing; `?123` and `ABC` move it. */
    private var kind = Kind.MAIN

    private enum class Kind { MAIN, SYMBOLS, SYMBOLS2 }

    init {
        keyboard.listener = Inert()
        strip.listener = Inert()
        // The same arrangement the service builds, so a popup opened here
        // reaches over the strip exactly as it does on the keyboard.
        clipChildren = false
        backdrop.clipChildren = false
        keyboard.popupHeadroom = dp(44).toFloat()
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

        KeyboardLook.applyBackdropTo(backdrop, look)

        // The character bubble is a Look and feel setting in its own right, so
        // the preview is where somebody would go to decide whether they want
        // it. `mayShow` also takes a password flag, and a preview has no field.
        keyboard.previewEnabled = Prefs.popupPreview(ctx)
        keyboard.longPressTimeoutMs = Prefs.longPressMs(ctx).toLong()
        applyLayout()

        strip.applyTheme(look.drawn)
        strip.labelScale = Prefs.labelScalePct(ctx) / 100f
        strip.showSuggestions(SAMPLE, 0)
        invalidate()
    }

    /** Builds whichever of the three layouts [kind] is on. */
    private fun applyLayout() {
        val ctx = context
        val numberRow = Prefs.numberRow(ctx)
        val langs = Prefs.languages(ctx)
        val code = langs.firstOrNull() ?: "en"
        val loc = java.util.Locale.forLanguageTag(code)
        val lay = when (kind) {
            Kind.MAIN -> Languages.byCode(code).layout(numberRow, langs.size > 1)
            Kind.SYMBOLS -> com.rimboard.keyboard.model.Layouts.symbols(
                loc, Prefs.currencies(ctx)
            )
            Kind.SYMBOLS2 -> com.rimboard.keyboard.model.Layouts.symbols2(loc)
        }
        keyboard.setLayout(
            lay,
            "preview/$kind/$code/$numberRow/${langs.size > 1}",
            KeyProximity.forLang(code)
        )
        keyboard.spaceLabel = Prefs.spaceText(ctx).takeIf { it.isNotBlank() } ?: ""
        keyboard.enterLabel = "↵"
    }

    /**
     * A listener that moves what is on screen and carries nothing off it.
     *
     * The members implemented here are the ones whose whole effect is visible
     * inside this view: switching layout, latching shift, opening the drawer,
     * rippling the sky under a press. Everything else — a character, a
     * backspace, a finished swipe, a picked suggestion — is a request to change
     * a document, and there is no document, so it is dropped where it arrives
     * rather than blocked earlier. That is why the keys still light up and the
     * popups still open: nothing about the *view* was disabled.
     */
    private inner class Inert : KeyboardView.Listener, SuggestionStripView.Listener {

        override fun onKeyPressed(key: com.rimboard.keyboard.model.Key, tapDx: Float, tapDy: Float) {
            when (key.code) {
                com.rimboard.keyboard.model.Codes.MODE_SYM -> {
                    kind = Kind.SYMBOLS; applyLayout()
                }
                com.rimboard.keyboard.model.Codes.MODE_SYM2 -> {
                    kind = Kind.SYMBOLS2; applyLayout()
                }
                com.rimboard.keyboard.model.Codes.MODE_ABC -> {
                    kind = Kind.MAIN; applyLayout()
                }
                com.rimboard.keyboard.model.Codes.SHIFT -> {
                    keyboard.shiftState = when (keyboard.shiftState) {
                        KeyboardView.ShiftState.NONE -> KeyboardView.ShiftState.MANUAL
                        KeyboardView.ShiftState.MANUAL -> KeyboardView.ShiftState.CAPSLOCK
                        else -> KeyboardView.ShiftState.NONE
                    }
                }
                // Anything else would have been a character.
            }
        }

        /** The press ripples the sky, as it does on the keyboard. */
        override fun onKeyDownFeedback(key: com.rimboard.keyboard.model.Key, x: Float, y: Float) {
            backdrop.nudgeStars(x, y + strip.height)
        }

        override fun onKeyRepeated(key: com.rimboard.keyboard.model.Key) {}
        override fun onPopupKeySelected(key: com.rimboard.keyboard.model.Key) {}
        override fun onCursorMove(steps: Int) {}
        override fun onLanguageSwipe(direction: Int) {}
        override fun onHideKeyboard() {}
        override fun onSpaceLongPress() {}
        override fun onGlideComplete(points: FloatArray, keys: String) {}
        override fun onOneHandedChanged(mode: Int) {}
        override fun onBackspaceWord() {}
        override fun onBackspaceWordRestore() {}

        // ---- the strip ----

        /** The one strip control with a visible job: the tool drawer. */
        override fun onToolbarToggle(expand: Boolean) {
            if (expand) strip.setPinnedTools(Prefs.pinnedTools(context))
            strip.setDrawerOpen(expand)
        }

        override fun onDrawerClosed() {
            strip.showSuggestions(SAMPLE, 0)
        }

        override fun onSuggestionPicked(index: Int, word: String) {}
        override fun onClipboardPasteRequested() {}
        override fun onClipChipExpired() {}
        override fun onClipboardPanelRequested() {}
        override fun onQuickAction(code: Int) {}
        override fun onSuggestionLongPressed(word: String, anchor: View) {}
        override fun onEmojiSuggestionPicked(emoji: String) {}

        /**
         * The expanded panel is a sibling of the keyboard in the service's
         * frame, not a child of the strip, so there is nothing here to show.
         * Swiping up on the preview's strip does nothing, which is the one
         * interaction that is less than the real keyboard's.
         */
        override fun onSuggestionsExpandRequested() {}
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
