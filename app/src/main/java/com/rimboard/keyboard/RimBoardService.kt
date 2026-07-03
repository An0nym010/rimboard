package com.rimboard.keyboard

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.rimboard.keyboard.engine.SuggestionEngine
import com.rimboard.keyboard.engine.UserData
import com.rimboard.keyboard.model.Codes
import com.rimboard.keyboard.model.Key
import com.rimboard.keyboard.model.KeyboardLayout
import com.rimboard.keyboard.model.LayoutKind
import com.rimboard.keyboard.model.Layouts
import com.rimboard.keyboard.settings.Prefs
import com.rimboard.keyboard.settings.SettingsActivity
import com.rimboard.keyboard.theme.KeyboardTheme
import com.rimboard.keyboard.theme.Themes
import com.rimboard.keyboard.ui.EmojiView
import com.rimboard.keyboard.ui.KeyboardView
import com.rimboard.keyboard.ui.SuggestionStripView
import java.util.Locale
import kotlin.math.abs

class RimBoardService : InputMethodService(),
    KeyboardView.Listener, SuggestionStripView.Listener, EmojiView.Listener {

    private lateinit var userData: UserData
    private lateinit var engine: SuggestionEngine

    private var rootView: LinearLayout? = null
    private var strip: SuggestionStripView? = null
    private var keyboardView: KeyboardView? = null
    private var emojiView: EmojiView? = null

    private val composing = StringBuilder()
    private var prevWordForBigram = ""

    private var kind = LayoutKind.MAIN
    private var langs: List<String> = listOf("en", "tr")
    private var langIndex = 0
    private var kbTheme: KeyboardTheme? = null

    // per-editor flags
    private var isPassword = false
    private var fieldNoLearning = false
    private var fieldNoSuggestions = false
    private var isEmailOrUri = false
    private var isTextClass = false
    private var suggestionsActive = false
    private var autocorrectActive = false

    private var lastSpaceTime = 0L
    private var lastShiftTapTime = 0L

    private class Revert(val original: String, val committed: String, val separator: String)

    private var revert: Revert? = null

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        userData = UserData(this)
        userData.loadAsync()
        engine = SuggestionEngine(this, userData)
        // Warm the dictionaries off the main thread so the first keystroke
        // doesn't pay the load-and-sort cost.
        Thread {
            try {
                for (code in Prefs.languages(this)) {
                    engine.dictionary(code, localeFor(code))
                }
            } catch (_: Exception) {
            }
        }.start()
    }

    override fun onDestroy() {
        userData.saveIfDirty()
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val s = SuggestionStripView(this).apply { listener = this@RimBoardService }
        root.addView(s, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))
        val frame = FrameLayout(this)
        val kv = KeyboardView(this).apply { listener = this@RimBoardService }
        val ev = EmojiView(this).apply {
            listener = this@RimBoardService
            visibility = View.GONE
        }
        frame.addView(kv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        frame.addView(ev, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        root.addView(frame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        rootView = root
        strip = s
        keyboardView = kv
        emojiView = ev
        return root
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        composing.setLength(0)
        prevWordForBigram = ""
        revert = null
        keyboardView?.shiftState = KeyboardView.ShiftState.NONE
        configureAll(info)
    }

    private fun configureAll(info: EditorInfo) {
        readPrefsAndFieldFlags(info)
        kind = initialKindFor(info)
        applyLayout()
        updateShiftState()
        updateStrip()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        composing.setLength(0)
        userData.saveIfDirty()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (rootView != null) {
            setInputView(onCreateInputView())
            currentInputEditorInfo?.let { configureAll(it) }
        }
    }

    // ---------------------------------------------------------------- config

    private fun readPrefsAndFieldFlags(info: EditorInfo) {
        if (Prefs.pendingClear(this)) {
            userData.clearAll()
            Prefs.setPendingClear(this, false)
        }

        langs = Prefs.languages(this)
        val saved = Prefs.currentLang(this)
        val idx = langs.indexOf(saved)
        langIndex = if (idx >= 0) idx else 0

        val inputType = info.inputType
        val cls = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        isTextClass = cls == InputType.TYPE_CLASS_TEXT
        isPassword = (isTextClass && (
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (cls == InputType.TYPE_CLASS_NUMBER &&
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        isEmailOrUri = isTextClass && (
            variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_URI)
        fieldNoSuggestions = (inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
        fieldNoLearning =
            (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0

        suggestionsActive = Prefs.suggestions(this) && isTextClass && !isPassword &&
            !fieldNoSuggestions && !isEmailOrUri && !isIncognito()
        autocorrectActive = Prefs.autocorrect(this) && isTextClass && !isPassword &&
            !fieldNoSuggestions && !isEmailOrUri

        kbTheme = Themes.resolve(this, Prefs.theme(this))
        val t = kbTheme ?: return
        keyboardView?.let { kv ->
            kv.theme = t
            kv.previewEnabled = Prefs.popupPreview(this)
            kv.spaceCursorEnabled = Prefs.spaceCursor(this)
            kv.keyHeightFactor = Prefs.heightFactor(this)
            kv.showDigitHints = !Prefs.numberRow(this)
            kv.incognito = isIncognito()
        }
        strip?.applyTheme(t)
        emojiView?.applyTheme(t)
        rootView?.setBackgroundColor(t.background)
    }

    private fun isIncognito(): Boolean =
        Prefs.incognitoAlways(this) || Prefs.incognitoSession(this) ||
            isPassword || fieldNoLearning

    private fun initialKindFor(info: EditorInfo): LayoutKind {
        return when (info.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_DATETIME -> LayoutKind.NUMPAD
            else -> LayoutKind.MAIN
        }
    }

    private fun currentLangCode(): String = langs.getOrElse(langIndex) { "en" }

    private fun localeFor(code: String): Locale =
        if (code == "tr") Locale.forLanguageTag("tr") else Locale.ENGLISH

    private fun locale(): Locale = localeFor(currentLangCode())

    private fun applyLayout() {
        val kv = keyboardView ?: return
        hideEmoji()
        val numberRow = Prefs.numberRow(this)
        val showGlobe = langs.size > 1
        val lay: KeyboardLayout = when (kind) {
            LayoutKind.MAIN ->
                if (currentLangCode() == "tr") Layouts.qwertyTr(numberRow, showGlobe)
                else Layouts.qwertyEn(numberRow, showGlobe)
            LayoutKind.SYMBOLS -> Layouts.symbols(locale())
            LayoutKind.SYMBOLS2 -> Layouts.symbols2(locale())
            LayoutKind.NUMPAD -> Layouts.numpad(locale())
        }
        kv.layout = lay
        kv.spaceLabel = spaceLabelText()
        kv.enterLabel = enterLabelText()
        kv.incognito = isIncognito()
    }

    private fun spaceLabelText(): String {
        if (langs.size <= 1) return ""
        val loc = locale()
        return loc.getDisplayLanguage(loc)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(loc) else it.toString() }
    }

    private fun enterLabelText(): String {
        val info = currentInputEditorInfo ?: return "\u21B5"
        if ((info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return "\u21B5"
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> "\u2192"
            EditorInfo.IME_ACTION_NEXT -> "\u2192"
            EditorInfo.IME_ACTION_PREVIOUS -> "\u2190"
            EditorInfo.IME_ACTION_DONE -> "\u2713"
            EditorInfo.IME_ACTION_SEARCH -> "\uD83D\uDD0D"
            EditorInfo.IME_ACTION_SEND -> "\u27A4"
            else -> "\u21B5"
        }
    }

    // ---------------------------------------------------------------- keyboard callbacks

    override fun onKeyPressed(key: Key) {
        when (key.code) {
            Codes.SHIFT -> handleShift()
            Codes.BACKSPACE -> handleBackspace()
            Codes.MODE_SYM -> { kind = LayoutKind.SYMBOLS; applyLayout(); updateStrip() }
            Codes.MODE_SYM2 -> { kind = LayoutKind.SYMBOLS2; applyLayout(); updateStrip() }
            Codes.MODE_ABC -> {
                kind = LayoutKind.MAIN; applyLayout(); updateShiftState(); updateStrip()
            }
            Codes.ENTER -> handleEnter()
            Codes.LANG -> cycleLanguage()
            Codes.EMOJI -> showEmoji()
            Codes.SETTINGS -> openSettings()
            Codes.INCOGNITO -> toggleIncognito()
            Codes.SPACE -> handleSpace()
            else -> if (key.code > 0) typeText(key.label)
        }
    }

    override fun onKeyRepeated(key: Key) {
        if (key.code == Codes.BACKSPACE) {
            handleBackspace()
            if (Prefs.sound(this)) playSound(Codes.BACKSPACE)
        }
    }

    override fun onPopupKeySelected(key: Key) {
        when (key.code) {
            Codes.LANG -> cycleLanguage()
            Codes.SETTINGS -> openSettings()
            Codes.INCOGNITO -> toggleIncognito()
            Codes.EMOJI -> showEmoji()
            Codes.IME_PICKER -> imePicker()
            else -> if (key.code > 0) typeText(key.label)
        }
    }

    override fun onCursorMove(steps: Int) {
        if (steps == 0) return
        finishComposingSilently()
        val code = if (steps > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        repeat(abs(steps)) { sendDownUpKeyEvents(code) }
    }

    override fun onKeyDownFeedback(key: Key) {
        if (Prefs.haptic(this)) {
            keyboardView?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        if (Prefs.sound(this)) playSound(key.code)
    }

    private fun playSound(code: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val fx = when (code) {
            Codes.BACKSPACE -> AudioManager.FX_KEYPRESS_DELETE
            Codes.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
            Codes.ENTER -> AudioManager.FX_KEYPRESS_RETURN
            else -> AudioManager.FX_KEYPRESS_STANDARD
        }
        am.playSoundEffect(fx, 1.0f)
    }

    // ---------------------------------------------------------------- typing

    private fun composeWords(): Boolean = suggestionsActive || autocorrectActive

    private fun isSeparator(c: Char): Boolean = c == ' ' || c in ".,;:!?)]}\u2026"

    private fun typeText(raw: String) {
        val text = applyShift(raw)
        revert = null
        lastShiftTapTime = 0 // a typed character breaks a double-tap-shift sequence
        val c = text.firstOrNull() ?: return
        val isWordChar = c.isLetter() || (c == '\'' && composing.isNotEmpty())
        if (composeWords() && isWordChar && text.length == 1) {
            composing.append(text)
            currentInputConnection?.setComposingText(composing, 1)
            afterEdit()
        } else if (text.length == 1 && isSeparator(c)) {
            handleSeparator(text)
        } else {
            commitTextRaw(text)
        }
        consumeAutoShift()
    }

    private fun applyShift(label: String): String {
        val kv = keyboardView ?: return label
        return if (label.length == 1 && label[0].isLetter() &&
            kv.shiftState != KeyboardView.ShiftState.NONE
        ) label.uppercase(locale()) else label
    }

    private fun consumeAutoShift() {
        val kv = keyboardView ?: return
        if (kv.shiftState == KeyboardView.ShiftState.MANUAL ||
            kv.shiftState == KeyboardView.ShiftState.AUTO
        ) kv.shiftState = KeyboardView.ShiftState.NONE
    }

    private fun commitTextRaw(text: String) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        if (composing.isNotEmpty()) commitComposedWord(ic, allowAutocorrect = false, separator = "")
        ic.commitText(text, 1)
        ic.endBatchEdit()
        prevWordForBigram = ""
        revert = null
        afterEdit()
    }

    private fun handleSeparator(sep: String) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        if (composing.isNotEmpty()) {
            commitComposedWord(ic, allowAutocorrect = autocorrectActive, separator = sep)
        } else {
            ic.commitText(sep, 1)
            revert = null
        }
        ic.endBatchEdit()
        if (sep != " ") prevWordForBigram = ""
        afterEdit()
    }

    private fun commitComposedWord(ic: InputConnection, allowAutocorrect: Boolean, separator: String) {
        val typed = composing.toString()
        var finalWord = typed
        if (allowAutocorrect) {
            engine.correctionFor(typed, currentLangCode(), locale())?.let { finalWord = it }
        }
        ic.commitText(finalWord + separator, 1)
        revert = if (finalWord != typed) Revert(typed, finalWord, separator) else null

        val loc = locale()
        val wordish = typed.all { it.isLetter() || it == '\'' }
        val canLearn = Prefs.learnWords(this) && !isIncognito() && !isPassword && !isEmailOrUri
        if (canLearn && finalWord == typed && wordish && typed.length >= 2) {
            userData.learnWord(typed.lowercase(loc))
        }
        val fw = finalWord.lowercase(loc)
        if (canLearn && Prefs.predictions(this) && wordish && prevWordForBigram.isNotEmpty()) {
            userData.recordBigram(prevWordForBigram, fw)
        }
        prevWordForBigram = if (wordish) fw else ""
        composing.setLength(0)
    }

    private fun handleSpace() {
        val ic = currentInputConnection ?: return
        val now = System.currentTimeMillis()
        if (composing.isEmpty() && Prefs.doubleSpace(this) && now - lastSpaceTime < 500) {
            val before = ic.getTextBeforeCursor(3, 0)
            if (before != null && before.length >= 2 &&
                before[before.length - 1] == ' ' &&
                before[before.length - 2].isLetterOrDigit()
            ) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                ic.endBatchEdit()
                lastSpaceTime = 0
                prevWordForBigram = ""
                revert = null
                afterEdit()
                return
            }
        }
        lastSpaceTime = now
        handleSeparator(" ")
    }

    private fun handleBackspace() {
        val ic = currentInputConnection ?: return
        revert = null
        if (composing.isNotEmpty()) {
            composing.deleteCharAt(composing.length - 1)
            if (composing.isEmpty()) {
                ic.commitText("", 1)
            } else {
                ic.setComposingText(composing, 1)
            }
            afterEdit()
            return
        }
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            val before = ic.getTextBeforeCursor(2, 0)
            if (before != null && before.length >= 2 &&
                Character.isSurrogatePair(before[before.length - 2], before[before.length - 1])
            ) {
                ic.deleteSurroundingText(2, 0)
            } else {
                ic.deleteSurroundingText(1, 0)
            }
        }
        afterEdit()
    }

    private fun handleShift() {
        val kv = keyboardView ?: return
        val now = System.currentTimeMillis()
        if (now - lastShiftTapTime < 300) {
            kv.shiftState = KeyboardView.ShiftState.CAPSLOCK
        } else {
            kv.shiftState = when (kv.shiftState) {
                KeyboardView.ShiftState.NONE -> KeyboardView.ShiftState.MANUAL
                KeyboardView.ShiftState.AUTO -> KeyboardView.ShiftState.NONE
                KeyboardView.ShiftState.MANUAL -> KeyboardView.ShiftState.NONE
                KeyboardView.ShiftState.CAPSLOCK -> KeyboardView.ShiftState.NONE
            }
        }
        lastShiftTapTime = now
    }

    private fun handleEnter() {
        val ic = currentInputConnection ?: return
        if (composing.isNotEmpty()) {
            ic.beginBatchEdit()
            commitComposedWord(ic, allowAutocorrect = autocorrectActive, separator = "")
            ic.endBatchEdit()
        }
        revert = null
        val info = currentInputEditorInfo
        val noAction = info == null ||
            (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        if (!noAction && action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            ic.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
        prevWordForBigram = ""
        afterEdit()
    }

    private fun finishComposingSilently() {
        if (composing.isNotEmpty()) {
            currentInputConnection?.finishComposingText()
            composing.setLength(0)
            updateStrip()
        }
    }

    private fun afterEdit() {
        updateShiftState()
        updateStrip()
    }

    private fun updateShiftState() {
        val kv = keyboardView ?: return
        if (kv.shiftState == KeyboardView.ShiftState.MANUAL ||
            kv.shiftState == KeyboardView.ShiftState.CAPSLOCK
        ) return
        if (!Prefs.autocaps(this) || !isTextClass) {
            kv.shiftState = KeyboardView.ShiftState.NONE
            return
        }
        val info = currentInputEditorInfo ?: return
        val ic = currentInputConnection ?: return
        val caps = ic.getCursorCapsMode(info.inputType)
        kv.shiftState = if (caps != 0) KeyboardView.ShiftState.AUTO
        else KeyboardView.ShiftState.NONE
    }

    // ---------------------------------------------------------------- suggestions

    private fun updateStrip() {
        val s = strip ?: return
        if (isIncognito()) {
            if (composing.isEmpty()) s.showIncognito(getString(R.string.incognito_label))
            else s.showEmpty()
            return
        }
        if (!suggestionsActive) {
            maybeClipboardOrEmpty(s)
            return
        }
        if (composing.isEmpty()) {
            val rv = revert
            if (rv != null) {
                s.showSuggestions(listOf("\u21A9 " + rv.original, "", ""), -1)
                return
            }
            var preds = if (Prefs.predictions(this) && prevWordForBigram.isNotEmpty()) {
                engine.predictions(prevWordForBigram, locale(), 3)
            } else emptyList()
            if (preds.isNotEmpty() &&
                keyboardView?.shiftState == KeyboardView.ShiftState.AUTO
            ) {
                val loc = locale()
                preds = preds.map { p ->
                    p.replaceFirstChar {
                        if (it.isLowerCase()) it.titlecase(loc) else it.toString()
                    }
                }
            }
            if (preds.isNotEmpty()) {
                s.showSuggestions((preds + listOf("", "", "")).take(3), -1)
            } else {
                maybeClipboardOrEmpty(s)
            }
            return
        }
        val res = engine.suggestionsFor(
            composing.toString(), currentLangCode(), locale(),
            allowAutocorrect = autocorrectActive, personalized = true
        )
        s.showSuggestions(res.items, res.autocorrectIndex)
    }

    private fun maybeClipboardOrEmpty(s: SuggestionStripView) {
        if (clipChipEligible()) {
            s.showClipboard("\uD83D\uDCCB " + getString(android.R.string.paste))
        } else {
            s.showEmpty()
        }
    }

    private fun clipChipEligible(): Boolean {
        if (!Prefs.clipboardSuggest(this)) return false
        val ic = currentInputConnection ?: return false
        val before = ic.getTextBeforeCursor(1, 0)
        val after = ic.getTextAfterCursor(1, 0)
        if (!before.isNullOrEmpty() || !after.isNullOrEmpty()) return false
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (!cm.hasPrimaryClip()) return false
        val desc = cm.primaryClipDescription ?: return false
        return desc.hasMimeType("text/plain") || desc.hasMimeType("text/html") ||
            desc.hasMimeType("text/*")
    }

    override fun onSuggestionPicked(index: Int, word: String) {
        if (revert != null && index == 0) {
            performRevert()
            return
        }
        if (word.isEmpty() || word.startsWith("\u21A9")) return
        val ic = currentInputConnection ?: return
        val loc = locale()
        ic.beginBatchEdit()
        ic.commitText("$word ", 1) // replaces the composing region if present
        ic.endBatchEdit()
        composing.setLength(0)
        val wordish = word.all { it.isLetter() || it == '\'' }
        val canLearn = Prefs.learnWords(this) && !isIncognito() && !isPassword && !isEmailOrUri
        if (canLearn && wordish && word.length >= 2) {
            userData.learnWord(word.lowercase(loc))
        }
        if (canLearn && Prefs.predictions(this) && wordish && prevWordForBigram.isNotEmpty()) {
            userData.recordBigram(prevWordForBigram, word.lowercase(loc))
        }
        prevWordForBigram = if (wordish) word.lowercase(loc) else ""
        revert = null
        afterEdit()
    }

    private fun performRevert() {
        val rv = revert ?: return
        val ic = currentInputConnection ?: return
        val expect = rv.committed + rv.separator
        val before = ic.getTextBeforeCursor(expect.length, 0)?.toString()
        if (before != expect) { // text changed since the correction; bail out safely
            revert = null
            updateStrip()
            return
        }
        ic.beginBatchEdit()
        ic.deleteSurroundingText(rv.committed.length + rv.separator.length, 0)
        ic.commitText(rv.original + rv.separator, 1)
        ic.endBatchEdit()
        if (Prefs.learnWords(this) && !isIncognito()) {
            userData.markKnown(rv.original.lowercase(locale()))
        }
        prevWordForBigram = rv.original.lowercase(locale())
        revert = null
        afterEdit()
    }

    override fun onClipboardPasteRequested() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
        if (text.isEmpty()) return
        finishComposingSilently()
        currentInputConnection?.commitText(text, 1)
        afterEdit()
    }

    // ---------------------------------------------------------------- selection tracking

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd
        )
        if (composing.isNotEmpty()) {
            val intact = candidatesStart >= 0 &&
                newSelStart == candidatesEnd && newSelEnd == candidatesEnd
            if (!intact) {
                composing.setLength(0)
                currentInputConnection?.finishComposingText()
                updateStrip()
            }
        } else {
            updateShiftState()
            val rv = revert
            if (rv != null) {
                val expect = rv.committed + rv.separator
                val before = currentInputConnection
                    ?.getTextBeforeCursor(expect.length, 0)?.toString()
                if (before != expect) {
                    revert = null
                    updateStrip()
                }
            } else {
                updateStrip()
            }
        }
    }

    // ---------------------------------------------------------------- languages / modes

    private fun cycleLanguage() {
        if (langs.size <= 1) {
            imePicker()
            return
        }
        finishComposingSilently()
        langIndex = (langIndex + 1) % langs.size
        Prefs.setCurrentLang(this, currentLangCode())
        kind = LayoutKind.MAIN
        applyLayout()
        updateShiftState()
        updateStrip()
    }

    private fun imePicker() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showInputMethodPicker()
    }

    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        val tag = newSubtype.languageTag
        @Suppress("DEPRECATION")
        val locStr = if (tag.isNotEmpty()) tag else newSubtype.locale
        val code = if (locStr.startsWith("tr")) "tr" else "en"
        val idx = langs.indexOf(code)
        if (idx >= 0 && idx != langIndex) {
            langIndex = idx
            Prefs.setCurrentLang(this, code)
            if (keyboardView != null && kind == LayoutKind.MAIN) applyLayout()
        }
    }

    private fun toggleIncognito() {
        Prefs.setIncognitoSession(this, !Prefs.incognitoSession(this))
        finishComposingSilently()
        currentInputEditorInfo?.let { readPrefsAndFieldFlags(it) }
        applyLayout()
        updateStrip()
    }

    private fun openSettings() {
        val i = Intent(this, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
        requestHideSelf(0)
    }

    // ---------------------------------------------------------------- emoji

    private fun showEmoji() {
        val kv = keyboardView ?: return
        val ev = emojiView ?: return
        finishComposingSilently()
        val lp = ev.layoutParams as FrameLayout.LayoutParams
        lp.height = kv.measureKeyboardHeight()
        ev.layoutParams = lp
        ev.setRecents(if (isIncognito()) emptyList() else Prefs.emojiRecents(this))
        kv.visibility = View.GONE
        ev.visibility = View.VISIBLE
    }

    private fun hideEmoji() {
        keyboardView?.visibility = View.VISIBLE
        emojiView?.visibility = View.GONE
    }

    override fun onEmoji(emoji: String) {
        currentInputConnection?.commitText(emoji, 1)
        if (!isIncognito()) {
            val recents = (listOf(emoji) + Prefs.emojiRecents(this).filter { it != emoji }).take(24)
            Prefs.setEmojiRecents(this, recents)
            emojiView?.setRecents(recents)
        }
        if (Prefs.haptic(this)) {
            emojiView?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        if (Prefs.sound(this)) playSound(0)
    }

    override fun onAbc() {
        kind = LayoutKind.MAIN
        applyLayout()
        updateShiftState()
        updateStrip()
    }

    override fun onBackspace() {
        handleBackspace()
        if (Prefs.haptic(this)) {
            emojiView?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        if (Prefs.sound(this)) playSound(Codes.BACKSPACE)
    }

    // ---------------------------------------------------------------- misc

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
