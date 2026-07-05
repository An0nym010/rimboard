package com.rimboard.keyboard

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.LinearLayout
import com.rimboard.keyboard.engine.SuggestionEngine
import com.rimboard.keyboard.engine.UserData
import com.rimboard.keyboard.model.Codes
import com.rimboard.keyboard.model.Key
import com.rimboard.keyboard.model.KeyboardLayout
import com.rimboard.keyboard.model.Languages
import com.rimboard.keyboard.model.LayoutKind
import com.rimboard.keyboard.model.Layouts
import com.rimboard.keyboard.settings.L10n
import com.rimboard.keyboard.settings.Prefs
import com.rimboard.keyboard.settings.Shortcuts
import com.rimboard.keyboard.settings.Stats
import com.rimboard.keyboard.settings.SettingsActivity
import com.rimboard.keyboard.theme.KeyboardTheme
import com.rimboard.keyboard.theme.Themes
import com.rimboard.keyboard.ui.ClipboardView
import com.rimboard.keyboard.ui.EditPanelView
import com.rimboard.keyboard.ui.EmojiView
import com.rimboard.keyboard.ui.IconView
import com.rimboard.keyboard.ui.Icons
import com.rimboard.keyboard.ui.KeyboardView
import com.rimboard.keyboard.ui.SuggestionStripView
import java.io.File
import java.util.Locale
import kotlin.math.abs
import org.json.JSONArray

class RimBoardService : InputMethodService(),
    KeyboardView.Listener, SuggestionStripView.Listener, EmojiView.Listener,
    ClipboardView.Listener, EditPanelView.Listener {

    private lateinit var userData: UserData
    private lateinit var engine: SuggestionEngine

    private var rootView: LinearLayout? = null
    private var strip: SuggestionStripView? = null
    private var keyboardView: KeyboardView? = null
    private var emojiView: EmojiView? = null
    private var clipboardView: ClipboardView? = null

    /** Clipboard history lives only in memory; it is never written to disk. */
    private class ClipEntry(val text: String, val at: Long)

    private val clipHistory = ArrayDeque<ClipEntry>()
    private var clipChangedListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var editPanelView: EditPanelView? = null
    private var floatingBlock: View? = null
    private var editSelectMode = false

    /** Pinned clips persist in device-protected storage; the user opts in per item. */
    private val pinnedClips = ArrayList<String>()

    /** Words removed by the backspace swipe, restorable by sliding right. */
    private val wordUndo = ArrayDeque<String>()

    private var appliedUiLang: String? = null

    // Language auto-detection: if the user keeps typing words that only the
    // other enabled language knows, suggestions quietly swap priority.
    private var altBoost = false
    private var altBoostStreak = 0
    private var primStreak = 0

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
    private var backspaceRepeats = 0
    private var autoSpace = false
    private var glideWords: List<String> = emptyList()

    private class Revert(val original: String, val committed: String, val separator: String)

    private var revert: Revert? = null

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        userData = UserData(this)
        userData.loadAsync()
        engine = SuggestionEngine(this, userData)
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipL = ClipboardManager.OnPrimaryClipChangedListener { captureClip() }
        clipChangedListener = clipL
        cm.addPrimaryClipChangedListener(clipL)
        loadPinned()
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
        clipChangedListener?.let {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .removePrimaryClipChangedListener(it)
        }
        userData.saveIfDirty()
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        val ctx = L10n.wrap(this)
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val s = SuggestionStripView(ctx).apply { listener = this@RimBoardService }
        root.addView(s, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))
        val frame = FrameLayout(ctx)
        val kv = KeyboardView(ctx).apply { listener = this@RimBoardService }
        val ev = EmojiView(ctx).apply {
            listener = this@RimBoardService
            visibility = View.GONE
        }
        frame.addView(kv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        frame.addView(ev, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        val cv = ClipboardView(ctx).apply {
            listener = this@RimBoardService
            visibility = View.GONE
        }
        frame.addView(cv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        val ep = EditPanelView(ctx).apply {
            listener = this@RimBoardService
            visibility = View.GONE
        }
        frame.addView(ep, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        root.addView(frame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        rootView = root
        strip = s
        keyboardView = kv
        emojiView = ev
        clipboardView = cv
        editPanelView = ep
        floatingBlock = null
        if (!Prefs.floating(this)) return root

        // ---- floating mode: draggable block inside a pass-through container
        val dm = resources.displayMetrics
        val blockW = (dm.widthPixels * 0.86f).toInt()
        val handleH = (26 * dm.density).toInt()
        val lift = (220 * dm.density).toInt()

        val handle = TextView(ctx).apply {
            text = "\u2630"
            gravity = android.view.Gravity.CENTER
            textSize = 13f
        }
        val block = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(handle, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, handleH))
            addView(root, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        floatingBlock = block
        block.setBackgroundColor(0x33000000)

        val container = FrameLayout(ctx)
        block.measure(
            View.MeasureSpec.makeMeasureSpec(blockW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        container.minimumHeight = lift + block.measuredHeight
        val maxX = (dm.widthPixels - blockW).coerceAtLeast(0)
        val lp = FrameLayout.LayoutParams(blockW, FrameLayout.LayoutParams.WRAP_CONTENT)
        val fx = Prefs.floatX(this)
        lp.leftMargin = (if (fx == Int.MAX_VALUE) maxX / 2 else fx).coerceIn(0, maxX)
        lp.topMargin = Prefs.floatY(this).coerceIn(0, lift)
        container.addView(block, lp)

        var downRawX = 0f
        var downRawY = 0f
        var startL = 0
        var startT = 0
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX; downRawY = e.rawY
                    startL = lp.leftMargin; startT = lp.topMargin
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    lp.leftMargin = (startL + (e.rawX - downRawX).toInt()).coerceIn(0, maxX)
                    lp.topMargin = (startT + (e.rawY - downRawY).toInt()).coerceIn(0, lift)
                    block.layoutParams = lp
                    true
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    Prefs.setFloatPos(this, lp.leftMargin, lp.topMargin)
                    true
                }
                else -> false
            }
        }
        return container
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        composing.setLength(0)
        prevWordForBigram = ""
        revert = null
        autoSpace = false
        glideWords = emptyList()
        backspaceRepeats = 0
        val ui = Prefs.uiLanguage(this)
        if (appliedUiLang != null && appliedUiLang != ui) {
            setInputView(onCreateInputView())
        }
        appliedUiLang = ui
        altBoost = false
        altBoostStreak = 0
        primStreak = 0
        wordUndo.clear()
        keyboardView?.shiftState = KeyboardView.ShiftState.NONE
        captureClip()
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
        Stats.flush(this)
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
        if (Prefs.pendingReload(this)) {
            userData.reload()
            pinnedClips.clear()
            loadPinned()
            Prefs.setPendingReload(this, false)
        }

        langs = Prefs.languages(this)
        val saved = Prefs.currentLang(this)
        val idx = langs.indexOf(saved)
        val sysIdx = langs.indexOf(java.util.Locale.getDefault().language)
        langIndex = when {
            idx >= 0 -> idx
            sysIdx >= 0 -> sysIdx
            else -> 0
        }

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
            kv.glideEnabled = Prefs.glide(this)
            when (Prefs.repeatSpeed(this)) {
                "slow" -> { kv.repeatInitialMs = 420L; kv.repeatIntervalMs = 70L }
                "fast" -> { kv.repeatInitialMs = 200L; kv.repeatIntervalMs = 32L }
                else -> { kv.repeatInitialMs = 300L; kv.repeatIntervalMs = 50L }
            }
            kv.hapticFeedback = Prefs.haptic(this)
            kv.oneHanded = (if (Prefs.floating(this)) 0 else Prefs.oneHanded(this))
            kv.keyHeightFactor = Prefs.heightFactor(this)
            kv.showDigitHints = !Prefs.numberRow(this)
            kv.incognito = isIncognito()
        }
        strip?.applyTheme(t)
        emojiView?.applyTheme(t)
        clipboardView?.applyTheme(t)
        editPanelView?.applyTheme(t)
        rootView?.setBackgroundColor(t.background)
        window?.window?.let { w ->
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            w.navigationBarColor = t.background
            if (Build.VERSION.SDK_INT >= 29) w.isNavigationBarContrastEnforced = false
            w.decorView.systemUiVisibility = if (t.isDark)
                w.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            else
                w.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
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

    private fun localeFor(code: String): Locale = Languages.byCode(code).locale

    private fun locale(): Locale = localeFor(currentLangCode())

    private fun altLangCode(): String? = langs.firstOrNull { it != currentLangCode() }

    private fun altLocale(): Locale? = altLangCode()?.let { localeFor(it) }

    private fun effLang(): String =
        if (altBoost) altLangCode() ?: currentLangCode() else currentLangCode()

    private fun effLocale(): Locale = localeFor(effLang())

    private fun effAlt(): String? = if (altBoost) currentLangCode() else altLangCode()

    private fun effAltLocale(): Locale? = effAlt()?.let { localeFor(it) }

    private fun noteCommittedWord(word: String) {
        Stats.word(this)
        val alt = altLangCode() ?: return
        val inPrim = engine.knownIn(word.lowercase(locale()), currentLangCode(), locale())
        val inAlt = engine.knownIn(word.lowercase(localeFor(alt)), alt, localeFor(alt))
        when {
            inAlt && !inPrim -> {
                altBoostStreak++
                primStreak = 0
                if (altBoostStreak >= 3) altBoost = true
            }
            inPrim -> {
                primStreak++
                altBoostStreak = 0
                if (primStreak >= 2) altBoost = false
            }
        }
    }

    private fun applyLayout() {
        val kv = keyboardView ?: return
        hideEmoji()
        val numberRow = Prefs.numberRow(this)
        val showGlobe = langs.size > 1
        val lay: KeyboardLayout = when (kind) {
            LayoutKind.MAIN ->
                Languages.byCode(currentLangCode()).layout(numberRow, showGlobe)
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
        wordUndo.clear()
        Stats.key(this)
        backspaceRepeats = 0
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
            Codes.ONE_HANDED -> toggleOneHanded()
            Codes.CLIPBOARD -> showClipPanel()
            Codes.EDIT_PANEL -> showEditPanel()
            Codes.FLOATING -> toggleFloating()
            Codes.SPACE -> handleSpace()
            else -> if (key.code > 0) typeText(key.label)
        }
    }

    override fun onKeyRepeated(key: Key) {
        if (key.code == Codes.BACKSPACE) {
            backspaceRepeats++
            if (backspaceRepeats >= 12) {
                // long hold: switch to word-by-word deletion, throttled
                if (backspaceRepeats % 3 == 0) deleteWordBeforeCursor()
            } else {
                handleBackspace()
            }
            if (Prefs.sound(this)) playSound(Codes.BACKSPACE)
        }
    }

    private fun deleteWordBeforeCursor() {
        val ic = currentInputConnection ?: return
        revert = null
        autoSpace = false
        glideWords = emptyList()
        if (composing.isNotEmpty()) {
            composing.setLength(0)
            ic.commitText("", 1)
            afterEdit()
            return
        }
        val before = ic.getTextBeforeCursor(32, 0)
        if (before.isNullOrEmpty()) return
        var i = before.length
        while (i > 0 && before[i - 1].isWhitespace()) i--
        while (i > 0 && !before[i - 1].isWhitespace()) i--
        ic.deleteSurroundingText(before.length - i, 0)
        afterEdit()
    }

    override fun onPopupKeySelected(key: Key) {
        when (key.code) {
            Codes.LANG -> cycleLanguage()
            Codes.SETTINGS -> openSettings()
            Codes.INCOGNITO -> toggleIncognito()
            Codes.EMOJI -> showEmoji()
            Codes.ONE_HANDED -> toggleOneHanded()
            Codes.CLIPBOARD -> showClipPanel()
            Codes.EDIT_PANEL -> showEditPanel()
            Codes.FLOATING -> toggleFloating()
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

    override fun onGlideComplete(sequence: String) {
        if (!Prefs.glide(this) || !isTextClass) return
        val loc = locale()
        val cands = engine.glideFor(
            sequence, currentLangCode(), loc,
            personalized = !isIncognito() && Prefs.learnWords(this)
        )
        if (cands.isEmpty()) {
            // tiny flick that matched nothing: fall back to the starting key
            if (sequence.length <= 2) typeText(sequence.substring(0, 1))
            return
        }
        val kv = keyboardView
        val capsLock = kv?.shiftState == KeyboardView.ShiftState.CAPSLOCK
        val cap = kv != null && kv.shiftState != KeyboardView.ShiftState.NONE
        val words = cands.map { w ->
            when {
                capsLock -> w.uppercase(loc)
                cap -> w.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(loc) else it.toString()
                }
                else -> w
            }
        }
        val best = words.first()
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        if (composing.isNotEmpty()) {
            commitComposedWord(ic, allowAutocorrect = autocorrectActive, separator = " ")
        }
        val before = ic.getTextBeforeCursor(1, 0)
        val lead = if (!before.isNullOrEmpty() && before[0].isLetterOrDigit()) " " else ""
        ic.commitText("$lead$best ", 1)
        ic.endBatchEdit()
        val canLearn = Prefs.learnWords(this) && !isIncognito() && !isPassword && !isEmailOrUri
        if (canLearn && Prefs.predictions(this) && prevWordForBigram.isNotEmpty()) {
            userData.recordBigram(prevWordForBigram, best.lowercase(loc))
        }
        prevWordForBigram = best.lowercase(loc)
        revert = null
        noteCommittedWord(best)
        autoSpace = true
        glideWords = words
        consumeAutoShift()
        afterEdit()
    }

    override fun onKeyDownFeedback(key: Key) {
        if (Prefs.haptic(this)) {
            keyboardView?.let { Haptics.tap(it) }
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
        autoSpace = false
        glideWords = emptyList()
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
        autoSpace = false
        glideWords = emptyList()
        afterEdit()
    }

    private fun handleSeparator(sep: String) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        if (composing.isNotEmpty()) {
            commitComposedWord(ic, allowAutocorrect = autocorrectActive, separator = sep)
        } else {
            val swap = autoSpace && sep.length == 1 && sep[0] in ".,;:!?" &&
                ic.getTextBeforeCursor(1, 0)?.toString() == " "
            if (swap) {
                // "word " + "." becomes "word. " (GBoard-style punctuation swap)
                ic.deleteSurroundingText(1, 0)
                ic.commitText("$sep ", 1)
            } else {
                ic.commitText(sep, 1)
            }
            revert = null
        }
        ic.endBatchEdit()
        autoSpace = false
        glideWords = emptyList()
        if (sep != " ") prevWordForBigram = ""
        afterEdit()
    }

    private fun commitComposedWord(ic: InputConnection, allowAutocorrect: Boolean, separator: String) {
        val typed = composing.toString()
        var finalWord = typed
        if (allowAutocorrect) {
            val shortcutExp = Shortcuts.expansionFor(this, typed)
        if (shortcutExp != null) {
            finalWord = shortcutExp
        } else {
            engine.correctionFor(typed, effLang(), effLocale(), effAlt(), effAltLocale())?.let {
                finalWord = it
                Stats.autocorrect(this)
            }
        }
            if (finalWord == typed && typed == "i" && currentLangCode() == "en") {
                finalWord = "I" // standalone English pronoun
            }
        }
        ic.commitText(finalWord + separator, 1)
        revert = if (finalWord != typed) Revert(typed, finalWord, separator) else null

        val loc = locale()
        noteCommittedWord(typed)
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
                autoSpace = false
                glideWords = emptyList()
                afterEdit()
                return
            }
        }
        lastSpaceTime = now
        handleSeparator(" ")
    }

    private fun handleBackspace() {
        Stats.backspace(this)
        val ic = currentInputConnection ?: return
        if (revert != null && composing.isEmpty()) {
            // backspace right after an autocorrect restores the original word
            performRevert()
            return
        }
        revert = null
        autoSpace = false
        glideWords = emptyList()
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
        autoSpace = false
        glideWords = emptyList()
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
            if (glideWords.isNotEmpty()) {
                s.showSuggestions(glideWords.take(3), 0)
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
            composing.toString(), effLang(), effLocale(),
            allowAutocorrect = autocorrectActive, personalized = true
        ,
            altLang = effAlt(),
            altLocale = effAltLocale()
        )
        val shortcutExp = Shortcuts.expansionFor(this, composing.toString())
        val emojiSug = if (composing.length >= 2)
            engine.emojiFor(composing.toString().lowercase(effLocale()), effLang()) else null
        var shownWords = res.items
        var shownHi = res.autocorrectIndex
        if (emojiSug != null && shownWords.isNotEmpty() && !shownWords.contains(emojiSug)) {
            shownWords = if (shownWords.size < 3) shownWords + emojiSug
            else shownWords.take(2) + emojiSug
        }
        if (shortcutExp != null) {
            shownWords = listOf(shortcutExp) + shownWords.take(2)
            shownHi = 0
        }
        s.showSuggestions(shownWords, shownHi)
    }

    private fun maybeClipboardOrEmpty(s: SuggestionStripView) {
        if (clipChipEligible()) {
            s.showClipboard(L10n.wrap(this).getString(android.R.string.paste))
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
        if (glideWords.isNotEmpty() && composing.isEmpty()) {
            replaceLastGlideWith(word)
            return
        }
        val ic = currentInputConnection ?: return
        val loc = locale()
        ic.beginBatchEdit()
        ic.commitText("$word ", 1) // replaces the composing region if present
        ic.endBatchEdit()
        composing.setLength(0)
        autoSpace = true
        noteCommittedWord(word)
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

    private fun replaceLastGlideWith(word: String) {
        val old = glideWords.firstOrNull() ?: return
        if (word == old) return
        val ic = currentInputConnection ?: return
        val expect = "$old "
        if (ic.getTextBeforeCursor(expect.length, 0)?.toString() != expect) {
            glideWords = emptyList()
            updateStrip()
            return
        }
        ic.beginBatchEdit()
        ic.deleteSurroundingText(expect.length, 0)
        ic.commitText("$word ", 1)
        ic.endBatchEdit()
        prevWordForBigram =
            if (word.all { it.isLetter() || it == '\'' }) word.lowercase(locale()) else ""
        glideWords = listOf(word) + glideWords.filter { it != word }
        autoSpace = true
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
            var stale = false
            val rv = revert
            if (rv != null) {
                val expect = rv.committed + rv.separator
                val before = currentInputConnection
                    ?.getTextBeforeCursor(expect.length, 0)?.toString()
                if (before != expect) {
                    revert = null
                    stale = true
                }
            }
            val gw = glideWords.firstOrNull()
            if (gw != null) {
                val expect = "$gw "
                val before = currentInputConnection
                    ?.getTextBeforeCursor(expect.length, 0)?.toString()
                if (before != expect) {
                    glideWords = emptyList()
                    stale = true
                }
            }
            if (stale || (revert == null && glideWords.isEmpty())) updateStrip()
        }
    }

    // ---------------------------------------------------------------- languages / modes

    private fun cycleLanguage() {
        altBoost = false
        altBoostStreak = 0
        primStreak = 0
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
        val code = locStr.take(2).lowercase(Locale.ENGLISH)
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

    private fun toggleFloating() {
        Prefs.setFloating(this, !Prefs.floating(this))
        setInputView(onCreateInputView())
    }

    override fun onComputeInsets(outInsets: InputMethodService.Insets) {
        super.onComputeInsets(outInsets)
        val block = floatingBlock ?: return
        val total = (block.parent as? View)?.height ?: return
        outInsets.contentTopInsets = total
        outInsets.visibleTopInsets = total
        outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(block.left, block.top, block.right, block.bottom)
    }

    private fun toggleOneHanded() {
        if (Prefs.floating(this)) return
        val cur = (if (Prefs.floating(this)) 0 else Prefs.oneHanded(this))
        val next = if (cur == 0) Prefs.oneHandedLast(this) else 0
        if (cur != 0) Prefs.setOneHandedLast(this, cur)
        Prefs.setOneHanded(this, next)
        keyboardView?.oneHanded = next
    }

    override fun onOneHandedChanged(mode: Int) {
        Prefs.setOneHanded(this, mode)
        if (mode != 0) Prefs.setOneHandedLast(this, mode)
    }

    private fun openSettings() {
        val i = Intent(this, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
        requestHideSelf(0)
    }

    // ---------------------------------------------------------------- emoji

    private fun animatePanelIn(v: View) {
        v.animate().cancel()
        v.alpha = 0f
        v.translationY = 10 * resources.displayMetrics.density
        v.animate().alpha(1f).translationY(0f).setDuration(150)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    private fun showEmoji() {
        val kv = keyboardView ?: return
        val ev = emojiView ?: return
        finishComposingSilently()
        val lp = ev.layoutParams as FrameLayout.LayoutParams
        lp.height = kv.measureKeyboardHeight()
        ev.layoutParams = lp
        ev.setRecents(if (isIncognito()) emptyList() else Prefs.emojiRecents(this))
        clipboardView?.visibility = View.GONE
        editPanelView?.visibility = View.GONE
        kv.visibility = View.GONE
        ev.visibility = View.VISIBLE
        animatePanelIn(ev)
    }

    private fun hideEmoji() {
        keyboardView?.visibility = View.VISIBLE
        emojiView?.visibility = View.GONE
        clipboardView?.visibility = View.GONE
        editPanelView?.visibility = View.GONE
    }

    // ------------------------------------------------------------ clipboard

    private fun showClipPanel() {
        val kv = keyboardView ?: return
        val cv = clipboardView ?: return
        finishComposingSilently()
        val lp = cv.layoutParams as FrameLayout.LayoutParams
        lp.height = kv.measureKeyboardHeight()
        cv.layoutParams = lp
        updateClipView()
        emojiView?.visibility = View.GONE
        editPanelView?.visibility = View.GONE
        kv.visibility = View.GONE
        cv.visibility = View.VISIBLE
        animatePanelIn(cv)
    }

    private fun updateClipView() {
        pruneClips()
        clipboardView?.setClips(pinnedClips.toList(), clipHistory.map { it.text })
    }

    private fun showEditPanel() {
        val kv = keyboardView ?: return
        val ep = editPanelView ?: return
        finishComposingSilently()
        val lp = ep.layoutParams as FrameLayout.LayoutParams
        lp.height = kv.measureKeyboardHeight()
        ep.layoutParams = lp
        editSelectMode = false
        ep.setSelectOn(false)
        emojiView?.visibility = View.GONE
        clipboardView?.visibility = View.GONE
        kv.visibility = View.GONE
        ep.visibility = View.VISIBLE
        animatePanelIn(ep)
    }

    private fun pinnedFile() = File(UserData.dataDir(this), "pinned_clips.json")

    private fun loadPinned() {
        try {
            val arr = JSONArray(pinnedFile().readText())
            for (i in 0 until arr.length()) pinnedClips.add(arr.getString(i))
        } catch (_: Exception) {
        }
    }

    private fun pruneClips() {
        val mins = Prefs.clipTimeoutMin(this)
        if (mins <= 0) return
        val cutoff = System.currentTimeMillis() - mins * 60_000L
        clipHistory.removeAll { it.at < cutoff }
    }

    private fun savePinned() {
        try {
            pinnedFile().writeText(JSONArray(pinnedClips).toString())
        } catch (_: Exception) {
        }
    }

    private fun captureClip() {
        try {
            if (!Prefs.clipboardSuggest(this) || isIncognito()) return
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip ?: return
            if (clip.itemCount == 0) return
            val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
            if (text.isBlank()) return
            val trimmed = if (text.length > 10000) text.substring(0, 10000) else text
            if (pinnedClips.contains(trimmed)) return
            pruneClips()
            clipHistory.removeAll { it.text == trimmed }
            clipHistory.addFirst(ClipEntry(trimmed, System.currentTimeMillis()))
            while (clipHistory.size > 10) clipHistory.removeLast()
            updateClipView()
        } catch (_: Exception) {
        }
    }

    override fun onClipboardPanelRequested() {
        showClipPanel()
    }

    override fun onClipPicked(text: String) {
        finishComposingSilently()
        currentInputConnection?.commitText(text, 1)
        hideEmoji()
        afterEdit()
    }

    override fun onClipsCleared() {
        clipHistory.clear()
        updateClipView()
    }

    override fun onClipPinToggle(text: String, pinned: Boolean) {
        clipHistory.removeAll { it.text == text }
        pinnedClips.remove(text)
        if (pinned) {
            pinnedClips.add(0, text)
        } else {
            clipHistory.addFirst(ClipEntry(text, System.currentTimeMillis()))
            while (clipHistory.size > 10) clipHistory.removeLast()
        }
        savePinned()
        updateClipView()
    }

    // ------------------------------------------------------------ edit panel

    override fun onEditAction(action: EditPanelView.Action) {
        val ic = currentInputConnection ?: return
        when (action) {
            EditPanelView.Action.SELECT -> {
                editSelectMode = !editSelectMode
                editPanelView?.setSelectOn(editSelectMode)
            }
            EditPanelView.Action.SELECT_ALL ->
                ic.performContextMenuAction(android.R.id.selectAll)
            EditPanelView.Action.COPY -> {
                ic.performContextMenuAction(android.R.id.copy)
                endSelect()
            }
            EditPanelView.Action.CUT -> {
                ic.performContextMenuAction(android.R.id.cut)
                endSelect()
                afterEdit()
            }
            EditPanelView.Action.PASTE -> {
                ic.performContextMenuAction(android.R.id.paste)
                endSelect()
                afterEdit()
            }
            EditPanelView.Action.TRANSLATE -> launchTranslate(ic)
            EditPanelView.Action.UNDO -> {
                sendCtrl(ic, KeyEvent.KEYCODE_Z, shift = false)
                afterEdit()
            }
            EditPanelView.Action.REDO -> {
                sendCtrl(ic, KeyEvent.KEYCODE_Z, shift = true)
                afterEdit()
            }
            else -> {
                val code = when (action) {
                    EditPanelView.Action.UP -> KeyEvent.KEYCODE_DPAD_UP
                    EditPanelView.Action.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
                    EditPanelView.Action.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
                    EditPanelView.Action.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
                    EditPanelView.Action.HOME -> KeyEvent.KEYCODE_MOVE_HOME
                    else -> KeyEvent.KEYCODE_MOVE_END
                }
                if (editSelectMode) sendShifted(ic, code) else sendDownUpKeyEvents(code)
            }
        }
    }

    override fun onSuggestionLongPressed(word: String, anchor: View) {
        val ctx = anchor.context
        val d = resources.displayMetrics.density
        val row = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(0xEE222222.toInt())
            setPadding((12 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
        }
        row.addView(IconView(ctx, Icons.TRASH).apply { color = 0xFFFFFFFF.toInt() },
            android.view.ViewGroup.LayoutParams((22 * d).toInt(), (26 * d).toInt()))
        val tv = TextView(ctx).apply {
            text = " " + ctx.getString(R.string.suggestion_remove, word)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
        }
        row.addView(tv)
        val pw = PopupWindow(
            row,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        pw.isOutsideTouchable = true
        tv.setOnClickListener {
            userData.blockWord(word.lowercase(effLocale()))
            pw.dismiss()
            updateStrip()
        }
        pw.showAsDropDown(anchor, 0, -(anchor.height * 5) / 2)
    }

    override fun onQuickAction(code: Int) {
        when (code) {
            Codes.CLIPBOARD -> showClipPanel()
            Codes.EDIT_PANEL -> showEditPanel()
            Codes.EMOJI -> showEmoji()
            Codes.INCOGNITO -> toggleIncognito()
            Codes.SETTINGS -> openSettings()
        }
    }

    /** Hands text to any installed translator via the system process-text
     *  action. RimBoard itself sends nothing anywhere. */
    private fun launchTranslate(ic: InputConnection) {
        val selected = ic.getSelectedText(0)?.toString()
        val text = if (!selected.isNullOrBlank()) {
            selected
        } else {
            val et = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
            et?.text?.toString() ?: ""
        }
        if (text.isBlank()) return
        try {
            val send = Intent(Intent.ACTION_PROCESS_TEXT).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_PROCESS_TEXT, text.take(1000))
                putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
            }
            startActivity(Intent.createChooser(send, null)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }

    private fun endSelect() {
        editSelectMode = false
        editPanelView?.setSelectOn(false)
    }

    private fun sendCtrl(ic: InputConnection, keyCode: Int, shift: Boolean) {
        val meta = KeyEvent.META_CTRL_ON or (if (shift) KeyEvent.META_SHIFT_ON else 0)
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
    }

    private fun sendShifted(ic: InputConnection, keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, KeyEvent.META_SHIFT_ON))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, KeyEvent.META_SHIFT_ON))
    }

    override fun onBackspaceWord() {
        finishComposingSilently()
        revert = null
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(64, 0)?.toString() ?: return
        if (before.isEmpty()) return
        var i = before.length
        while (i > 0 && before[i - 1].isWhitespace()) i--
        while (i > 0 && !before[i - 1].isWhitespace()) i--
        val chunk = before.substring(i)
        if (chunk.isEmpty()) return
        ic.deleteSurroundingText(chunk.length, 0)
        wordUndo.addLast(chunk)
        while (wordUndo.size > 50) wordUndo.removeFirst()
        afterEdit()
    }

    override fun onBackspaceWordRestore() {
        if (wordUndo.isEmpty()) return
        val chunk = wordUndo.removeLast()
        currentInputConnection?.commitText(chunk, 1)
        afterEdit()
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
