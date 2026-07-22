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
    ClipboardView.Listener, EditPanelView.Listener,
    com.rimboard.keyboard.ui.ToolbarPanelView.Listener {

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
    private var toolbarPanel: com.rimboard.keyboard.ui.ToolbarPanelView? = null
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

    /** Word before [prevWordForBigram] — the trigram context. Maintained by
     *  the setter below: committing a new word shifts, clearing clears both. */
    private var prevWord2 = ""
    private var prevWordForBigram = ""
        set(value) {
            prevWord2 = if (value.isEmpty()) "" else field
            field = value
        }

    private var kind = LayoutKind.MAIN
    private var langs: List<String> = listOf("en", "tr")
    private var langIndex = 0
    private var kbTheme: KeyboardTheme? = null

    // per-editor flags
    private var isPassword = false
    private var pendingPunctSpace = false
    private var currentPkg: String? = null
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
        dismissPopups()
        clipChangedListener?.let {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .removePrimaryClipChangedListener(it)
        }
        userData.flushBlocking()
        userData.shutdown()
        // onFinishInputView flushes these too, but it is not guaranteed to run
        // before the service goes away.
        Stats.flush(this)
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        // A rotation or a floating-mode toggle rebuilds the input view, and any
        // popup still up is anchored to the one being replaced.
        dismissPopups()
        val ctx = L10n.wrap(this)
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val s = SuggestionStripView(ctx).apply { listener = this@RimBoardService }
        root.addView(s, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))
        val frame = FrameLayout(ctx)
        val kv = KeyboardView(ctx).apply {
            listener = this@RimBoardService
            tapArbiter = ::resolveAmbiguousTap
        }
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
        val tp = com.rimboard.keyboard.ui.ToolbarPanelView(ctx).apply {
            listener = this@RimBoardService
            visibility = View.GONE
        }
        frame.addView(tp, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        root.addView(frame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        rootView = root
        // The strip is a new instance, so the feed guards must forget what they
        // sent to the old one or this one is never populated.
        lastTools = null
        pinnedCache = null
        strip = s
        keyboardView = kv
        emojiView = ev
        clipboardView = cv
        editPanelView = ep
        toolbarPanel = tp
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
        // The settings picker writes the same preference from another screen,
        // so drop the cache here rather than trusting it across a focus change.
        pinnedCache = null
        lastTools = null
        strip?.setDrawerOpen(false)
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
        currentPkg = info.packageName
        if (Prefs.langPerApp(this)) {
            Prefs.appLang(this, info.packageName)?.let { saved ->
                val idx = langs.indexOf(saved)
                if (idx >= 0) langIndex = idx
            }
        }
        keyboardView?.shiftState = KeyboardView.ShiftState.NONE
        captureClip()
        configureAll(info)
    }

    private fun configureAll(info: EditorInfo) {
        readPrefsAndFieldFlags(info)
        engine.warm(effLang(), effLocale(), effAlt(), effAltLocale())
        kind = initialKindFor(info)
        applyLayout()
        updateShiftState()
        updateStrip()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // Before anything else: the popup is anchored to the input view that is
        // now going away, and outliving its window token is what leaks it.
        dismissPopups()
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
        val bgDimAlpha = Prefs.bgDimAlpha(this)
        // With a photo behind the keys, the keys themselves switch to
        // translucent scrims whose polarity follows the image (see
        // Themes.overPhoto). Only the keyboard view gets the variant: the
        // strip and panels have no photo behind them, so they keep the theme.
        val hasBgImage =
            File(UserData.dataDir(this), "bg_image.jpg").exists()
        keyboardView?.let { kv ->
            kv.theme =
                if (hasBgImage) Themes.overPhoto(t, Prefs.bgLuma(this), bgDimAlpha) else t
            kv.previewEnabled = Prefs.popupPreview(this)
            kv.glideEnabled = Prefs.glide(this)
            when (Prefs.repeatSpeed(this)) {
                "slow" -> { kv.repeatInitialMs = 420L; kv.repeatIntervalMs = 70L }
                "fast" -> { kv.repeatInitialMs = 200L; kv.repeatIntervalMs = 32L }
                else -> { kv.repeatInitialMs = 300L; kv.repeatIntervalMs = 50L }
            }
            kv.showTrail = Prefs.glideTrail(this)
            kv.bgDimAlpha = bgDimAlpha
            kv.keyBorders = Prefs.keyBorders(this)
            kv.narrowGaps = Prefs.narrowGaps(this)
            kv.sidePadPct = Prefs.sidePadPct(this)
            kv.bottomPadPct = Prefs.bottomPadPct(this)
            kv.labelScale = Prefs.labelScalePct(this) / 100f
            kv.longPressTimeoutMs = Prefs.longPressMs(this).toLong()
            kv.spaceSwipeH = when (Prefs.spaceSwipeH(this)) {
                "language" -> 2
                "none" -> 0
                else -> 1
            }
            kv.spaceSwipeV = if (Prefs.spaceSwipeV(this) == "hide") 1 else 0
            kv.spaceLongPressMode = if (Prefs.spaceLongPress(this) == "none") 0 else 1
            kv.numpadOnSymbolsLongPress = Prefs.numpadLongPress(this)
            kv.tldPopups = isEmailOrUri && Prefs.tldPopupsOn(this)
            kv.customTypeface = customFont()
            kv.splitFraction = when (Prefs.splitMode(this)) {
                "on" -> 0.12f
                "landscape" ->
                    if (resources.configuration.orientation ==
                        android.content.res.Configuration.ORIENTATION_LANDSCAPE) 0.12f else 0f
                else -> 0f
            }
            engine.blockOffensive = Prefs.blockOffensive(this)
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
        toolbarPanel?.applyTheme(t)
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
        val numberRow = Prefs.numberRow(this) || (isPassword && Prefs.numberRowPasswords(this))
        val showGlobe = langs.size > 1
        val lay: KeyboardLayout = when (kind) {
            LayoutKind.MAIN ->
                Languages.byCode(currentLangCode()).layout(numberRow, showGlobe)
            LayoutKind.SYMBOLS -> Layouts.symbols(locale(), Prefs.currencies(this))
            LayoutKind.SYMBOLS2 -> Layouts.symbols2(locale())
            LayoutKind.NUMPAD -> Layouts.numpad(locale())
        }
        // Signature covers everything that changes what is written on the keys,
        // so refocusing a field rebuilds the layout without fading it.
        kv.setLayout(lay, "$kind/${currentLangCode()}/$numberRow/$showGlobe")
        kv.spaceLabel = spaceLabelText()
        kv.enterLabel = enterLabelText()
        kv.incognito = isIncognito()
    }

    private fun spaceLabelText(): String {
        Prefs.spaceText(this).takeIf { it.isNotBlank() }?.let { return it }
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
        // Typing dismisses the drawer. Without this the strip keeps showing
        // tools while words are being composed, so suggestions and autocorrect
        // silently vanish for as long as it is open.
        closeDrawerIfOpen()
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
            Codes.NUMPAD -> toggleNumpad()
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
            userData.recordNgram(prevWord2, prevWordForBigram, best.lowercase(loc))
        }
        prevWordForBigram = best.lowercase(loc)
        revert = null
        noteCommittedWord(best)
        autoSpace = true
        glideWords = words
        consumeAutoShift()
        afterEdit()
    }

    /**
     * Adaptive tap targeting: choose among letter keys whose expanded bounds
     * contain the touch by combining the spatial Gaussian (from KeyboardView)
     * with the language model's P(letter | previous letter) — the technique
     * behind Gboard's tap accuracy. Word-initial taps use the word-start
     * distribution. Disabled in password fields, where people type precisely
     * and unusual sequences (no language prior should second-guess them).
     */
    private fun resolveAmbiguousTap(chars: CharArray, spatialLogP: DoubleArray): Int {
        if (isPassword || !Prefs.smartTap(this)) return -1
        val dict = engine.cachedDictionary(effLang()) ?: return -1
        // Locale-aware lowercase so Turkish 'I' folds to dotless 'ı' (matching the
        // dictionary), not 'i', keeping the language prior meaningful in Turkish.
        val prev = composing.lastOrNull()?.toString()?.lowercase(effLocale())?.firstOrNull()
            ?: com.rimboard.keyboard.engine.Dictionary.WORD_START
        var best = -1
        var bestScore = Double.NEGATIVE_INFINITY
        for (i in chars.indices) {
            val s = spatialLogP[i] + 0.55 * dict.charLogP(prev, chars[i].lowercaseChar())
            if (s > bestScore) {
                bestScore = s
                best = i
            }
        }
        return best
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
        val vol = when (Prefs.soundVolume(this)) {
            "quiet" -> 0.25f
            "loud" -> 1.0f
            else -> 0.6f
        }
        am.playSoundEffect(fx, vol)
    }

    // ---------------------------------------------------------------- typing

    private fun composeWords(): Boolean = suggestionsActive || autocorrectActive

    private fun isSeparator(c: Char): Boolean = c == ' ' || c in ".,;:!?)]}\u2026"

    /**
     * Whether the cursor really does sit straight after sentence punctuation.
     *
     * [pendingPunctSpace] is armed by typing punctuation and cleared by typing
     * something else or backspacing — and by nothing else at all. Not the space
     * key, not enter, not a suggestion, not a pasted clip, not even focusing a
     * different field. So it stayed armed across all of those and the next
     * letter typed got a space in front of it: "Hi." then space then "there"
     * gave "Hi.  there", enter gave a line starting with a space, and switching
     * apps could put a stray space at the front of an empty field.
     *
     * Asking the field is one call, and only on the letter-after-punctuation
     * path — at most once a sentence, never per keystroke. The alternative was
     * clearing the flag in the ten-odd places that commit text by another
     * route, which is the arrangement that produced this in the first place.
     */
    private fun cursorFollowsPunctuation(): Boolean {
        val before = currentInputConnection?.getTextBeforeCursor(1, 0) ?: return false
        return before.length == 1 && before[0] in ".,!?;:"
    }

    private fun typeText(raw: String) {
        if (raw.length == 1) {
            val ch = raw[0]
            if (pendingPunctSpace && ch.isLetter() && cursorFollowsPunctuation()) {
                currentInputConnection?.commitText(" ", 1)
            }
            pendingPunctSpace = Prefs.autoSpacePunct(this) && ch in ".,!?;:"
        } else {
            pendingPunctSpace = false
        }
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
            userData.recordNgram(prevWord2, prevWordForBigram, fw)
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
                leaveSymbolsAfterSpace()
                return
            }
        }
        lastSpaceTime = now
        handleSeparator(" ")
        leaveSymbolsAfterSpace()
    }

    /**
     * "Leave symbols after space": the symbols planes are for the odd character,
     * so a space goes back to letters.
     *
     * This lived in [typeText], which the spacebar never reaches. Every layout
     * builds its spacebar with [Codes.SPACE], and that is 32 — a positive code,
     * matched by the `when` in [onKeyPressed] long before the `else` branch that
     * calls [typeText]. So the preference has shipped defaulting to on and doing
     * nothing at all.
     */
    private fun leaveSymbolsAfterSpace() {
        if (kind != LayoutKind.SYMBOLS && kind != LayoutKind.SYMBOLS2) return
        if (!Prefs.symbolsReturn(this)) return
        kind = LayoutKind.MAIN
        applyLayout()
        updateShiftState()
    }

    private fun handleBackspace() {
        Stats.backspace(this)
        pendingPunctSpace = false
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

    // ------------------------------------------------------------ calculator

    /** "= 408" chip for a trailing arithmetic expression before the cursor.
     *  The arithmetic itself lives in [com.rimboard.keyboard.engine.Calc]. */
    private fun calcChip(): String? {
        if (!Prefs.calcChip(this)) return null
        val before = currentInputConnection?.getTextBeforeCursor(40, 0)?.toString() ?: return null
        return com.rimboard.keyboard.engine.Calc.chipFor(before, 40)
    }

    private fun updateStrip() {
        val s = strip ?: return
        feedTools(s)
        if (isIncognito()) {
            if (composing.isEmpty()) s.showIncognito(getString(R.string.incognito_label))
            else {
                feedIdle(s)
                s.showEmpty()
            }
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
            calcChip()?.let {
                s.showSuggestions(listOf(it, "", ""), -1)
                return
            }
            var preds = if (Prefs.predictions(this) && prevWordForBigram.isNotEmpty()) {
                engine.predictions(prevWord2, prevWordForBigram, currentLangCode(), locale(), 3)
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

    /** Domains offered after "@" in an email field, so a pick is recognised. */
    private var domainChips: List<String> = emptyList()
    private var domainTyped = 0

    private val emailDomains = listOf(
        "gmail.com", "outlook.com", "hotmail.com", "yahoo.com", "icloud.com", "proton.me"
    )

    /** "name@gm" -> gmail.com chip. Email fields have suggestions off, so this
     *  is the one thing the strip offers there. */
    private fun maybeDomainChips(s: SuggestionStripView): Boolean {
        domainChips = emptyList()
        if (!isEmailOrUri) return false
        val ic = currentInputConnection ?: return false
        val before = ic.getTextBeforeCursor(40, 0)?.toString() ?: return false
        val at = before.lastIndexOf('@')
        if (at < 0) return false
        val after = before.substring(at + 1)
        // Domain already picked or being typed past its dot: leave it alone.
        if (after.any { it == ' ' || it == '.' }) return false
        val hits = emailDomains.filter { it.startsWith(after.lowercase()) }.take(3)
        if (hits.isEmpty()) return false
        domainChips = hits
        domainTyped = after.length
        s.showSuggestions((hits + listOf("", "", "")).take(3), -1)
        return true
    }

    private fun maybeClipboardOrEmpty(s: SuggestionStripView) {
        if (maybeDomainChips(s)) return
        if (clipChipEligible()) {
            s.showClipboard(L10n.wrap(this).getString(android.R.string.paste))
        } else {
            feedIdle(s)
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
        if (word in domainChips) {
            val ic0 = currentInputConnection ?: return
            // Replace what was typed after the "@" with the whole domain.
            if (domainTyped > 0) ic0.deleteSurroundingText(domainTyped, 0)
            ic0.commitText(word, 1)
            domainChips = emptyList()
            afterEdit()
            return
        }
        // Calculator chip (only shown, and only actioned, while not composing \u2014
        // so a text-shortcut expansion like "= mc^2" is never mistaken for it).
        if (composing.isEmpty() && word.startsWith("= ")) {
            val result = word.substring(2)
            val ic = currentInputConnection ?: return
            val prevCh = ic.getTextBeforeCursor(1, 0)?.lastOrNull()
            ic.commitText(if (prevCh == '=') result else "=$result", 1)
            afterEdit()
            return
        }
        if (glideWords.isNotEmpty() && composing.isEmpty()) {
            replaceLastGlideWith(word)
            return
        }
        val ic = currentInputConnection ?: return
        val loc = locale()
        ic.beginBatchEdit()
        ic.commitText(if (Prefs.autoSpaceSuggestion(this)) "$word " else word, 1) // replaces the composing region if present
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
            userData.recordNgram(prevWord2, prevWordForBigram, word.lowercase(loc))
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
        ic.commitText(if (Prefs.autoSpaceSuggestion(this)) "$word " else word, 1)
        ic.endBatchEdit()
        // Replacement of the last word: the trigram context (prevWord2) must
        // not shift onto the word being replaced.
        val keep2 = prevWord2
        prevWordForBigram =
            if (word.all { it.isLetter() || it == '\'' }) word.lowercase(locale()) else ""
        prevWord2 = keep2
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
        // Reverting swaps the last word in place; keep the trigram context.
        val keep2 = prevWord2
        prevWordForBigram = rv.original.lowercase(locale())
        prevWord2 = keep2
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
        // The half-typed word survives the switch on purpose. This used to
        // finishComposingSilently() here, which is exactly backwards for the
        // person who types an English word on the Turkish layout, sees Turkish
        // suggestions, and switches language to fix that: their word was
        // committed as-is and the strip went blank. Composing stays live, and
        // the updateStrip() below re-runs suggestions against the language
        // just switched to.
        langIndex = (langIndex + 1) % langs.size
        Prefs.setCurrentLang(this, currentLangCode())
        kind = LayoutKind.MAIN
        applyLayout()
        updateShiftState()
        updateStrip()
        recordAppLang()
        // Gboard-style confirmation: the new language's name rides the spacebar.
        com.rimboard.keyboard.model.Languages.all
            .firstOrNull { it.code == currentLangCode() }
            ?.let { keyboardView?.flashSpaceLabel(it.nativeName) }
    }

    private var cachedFont: android.graphics.Typeface? = null
    private var cachedFontStamp = -1L

    private fun customFont(): android.graphics.Typeface? {
        val f = java.io.File(
            com.rimboard.keyboard.engine.UserData.dataDir(this), "custom_font.ttf")
        if (!f.exists()) {
            cachedFont = null
            return null
        }
        val stamp = f.lastModified()
        if (cachedFont == null || cachedFontStamp != stamp) {
            cachedFont = try {
                android.graphics.Typeface.createFromFile(f)
            } catch (_: Exception) {
                null
            }
            cachedFontStamp = stamp
        }
        return cachedFont
    }

    /** Parsed once and invalidated on change: feedIdle runs per keystroke. */
    private var pinnedCache: List<String>? = null

    private fun pinnedTools(): List<String> =
        pinnedCache ?: Prefs.pinnedTools(this).also { pinnedCache = it }

    /** Only the tool row: fed on every strip update, so pinned tools survive
     *  suggestions. Guarded so the views are rebuilt only when the set changes. */
    private var lastTools: List<String>? = null

    private fun feedTools(s: com.rimboard.keyboard.ui.SuggestionStripView) {
        val tools = pinnedTools()
        if (tools == lastTools) return
        lastTools = tools
        s.setPinnedTools(
            tools.mapNotNull { com.rimboard.keyboard.ui.ToolCatalog.byId(it) }
                .map { it.icon to it.code }
        )
    }

    private fun feedIdle(s: com.rimboard.keyboard.ui.SuggestionStripView) {
        feedTools(s)
        s.setRecentEmoji(
            if (Prefs.emojiRow(this)) Prefs.emojiRecents(this).take(6) else emptyList()
        )
    }

    private fun restoreMainView() {
        emojiView?.visibility = View.GONE
        clipboardView?.visibility = View.GONE
        editPanelView?.visibility = View.GONE
        toolbarPanel?.visibility = View.GONE
        showKeyboardBack()
    }

    private fun toggleNumpad() {
        kind = if (kind == LayoutKind.NUMPAD) LayoutKind.MAIN else LayoutKind.NUMPAD
        applyLayout()
        updateShiftState()
    }

    private fun recordAppLang() {
        val pkg = currentPkg ?: return
        if (Prefs.langPerApp(this)) Prefs.setAppLang(this, pkg, currentLangCode())
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
            // Same as the globe key: a half-typed word gets its suggestions
            // recomputed in the language just switched to, rather than the
            // strip showing the old language's until the next keystroke.
            updateStrip()
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
        // Rebuilding the input view yields fresh, uninitialised views; re-run the
        // full setup (layout, theme, prefs, strip) exactly like a config change,
        // otherwise the keyboard comes up blank after toggling floating mode.
        currentInputEditorInfo?.let { configureAll(it) }
    }

    override fun onComputeInsets(outInsets: InputMethodService.Insets) {
        super.onComputeInsets(outInsets)
        val block = floatingBlock ?: return
        val total = (block.parent as? View)?.height ?: return
        outInsets.contentTopInsets = total
        outInsets.visibleTopInsets = total
        // Before the first layout the block has no bounds; publishing an empty
        // touchable region there would let every tap fall through to the app and
        // make the floating keyboard dead. Fall back to the whole view instead.
        if (block.width <= 0 || block.height <= 0) return
        outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(block.left, block.top, block.right, block.bottom)
    }

    private fun toggleOneHanded() {
        // One-handed and floating are mutually exclusive layouts. Rather than
        // making the toolbar's one-handed button a silent no-op while floating,
        // leave floating first so the tap always does something visible.
        if (Prefs.floating(this)) {
            toggleFloating()
            if (Prefs.floating(this)) return
        }
        val cur = Prefs.oneHanded(this)
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


    /** Brings the keyboard back from a panel, animating only if it was hidden. */
    private fun showKeyboardBack() {
        val kv = keyboardView ?: return
        val wasHidden = kv.visibility != View.VISIBLE
        kv.visibility = View.VISIBLE
        if (wasHidden) animatePanelIn(kv)
    }

    private fun animatePanelIn(v: View) {
        v.animate().cancel()
        v.alpha = 0f
        v.translationY = 10 * resources.displayMetrics.density
        v.animate().alpha(1f).translationY(0f).setDuration(150)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    /**
     * Sizes [panel] to the keyboard and reveals it as the only thing over it.
     *
     * Each of the four openers used to hide the panels it happened to know
     * about, and three of them forgot the toolbar panel — which is the last
     * child added to the frame, so it sits on top of every other one. The strip
     * stays visible and tappable while a panel is up, so opening emoji from the
     * drawer with the toolbar panel showing drew the emoji grid underneath it
     * and left the keyboard looking stuck. Hiding by exclusion cannot go stale
     * the way four hand-maintained lists did.
     */
    private fun revealPanel(panel: View) {
        val kv = keyboardView ?: return
        val lp = panel.layoutParams as FrameLayout.LayoutParams
        lp.height = kv.measureKeyboardHeight()
        panel.layoutParams = lp
        for (other in arrayOf(emojiView, clipboardView, editPanelView, toolbarPanel)) {
            if (other !== panel) other?.visibility = View.GONE
        }
        kv.visibility = View.GONE
        panel.visibility = View.VISIBLE
        animatePanelIn(panel)
    }

    private fun showEmoji() {
        val ev = emojiView ?: return
        finishComposingSilently()
        ev.setSearchLang(currentLangCode())
        ev.setRecents(if (isIncognito()) emptyList() else Prefs.emojiRecents(this))
        revealPanel(ev)
    }

    private fun hideEmoji() {
        showKeyboardBack()
        emojiView?.visibility = View.GONE
        clipboardView?.visibility = View.GONE
        editPanelView?.visibility = View.GONE
        toolbarPanel?.visibility = View.GONE
    }

    // ------------------------------------------------------------ clipboard

    private fun showClipPanel() {
        val cv = clipboardView ?: return
        finishComposingSilently()
        updateClipView()
        revealPanel(cv)
    }

    private fun updateClipView() {
        pruneClips()
        clipboardView?.setClips(pinnedClips.toList(), clipHistory.map { it.text })
    }

    private fun showEditPanel() {
        val ep = editPanelView ?: return
        finishComposingSilently()
        editSelectMode = false
        ep.setSelectOn(false)
        revealPanel(ep)
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
        if (Prefs.clipReturn(this)) restoreMainView()
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
        // The calculator chip and revert chip aren't dictionary words; long-press
        // (block-word) doesn't apply to them.
        if (word.startsWith("= ") || word.startsWith("↩")) return
        val ctx = anchor.context
        val d = resources.displayMetrics.density
        // Follow the keyboard theme instead of a hardcoded dark chip, which
        // looked wrong on the light themes.
        val t = kbTheme
        val popupBg = t?.previewBg ?: 0xEE222222.toInt()
        val popupFg = t?.keyText ?: 0xFFFFFFFF.toInt()
        val bg = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 12 * d
            setColor(popupBg)
        }
        val row = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = bg
            setPadding((12 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
        }
        row.addView(IconView(ctx, Icons.TRASH).apply { color = popupFg },
            android.view.ViewGroup.LayoutParams((22 * d).toInt(), (26 * d).toInt()))
        val tv = TextView(ctx).apply {
            text = " " + ctx.getString(R.string.suggestion_remove, word)
            setTextColor(popupFg)
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
        blockWordPopup?.dismiss()
        blockWordPopup = pw
        pw.setOnDismissListener { if (blockWordPopup === pw) blockWordPopup = null }
        pw.showAsDropDown(anchor, 0, -(anchor.height * 5) / 2)
    }

    /**
     * The block-word popup, held so the keyboard can take it down with itself.
     *
     * It hangs off the input view's window token. Nothing dismissed it when the
     * keyboard went away, so hiding the IME with it open leaked the window and
     * could leave the chip drawn over whatever the user went to next.
     */
    private var blockWordPopup: PopupWindow? = null

    private fun dismissPopups() {
        blockWordPopup?.dismiss()
        blockWordPopup = null
    }

    override fun onQuickEmoji(emoji: String) {
        currentInputConnection?.commitText(emoji, 1)
    }

    override fun onLanguageSwipe(direction: Int) {
        if (direction < 0 && langs.size > 1) {
            // Composing survives, same as cycleLanguage — see the note there.
            altBoost = false
            altBoostStreak = 0
            primStreak = 0
            langIndex = (langIndex - 1 + langs.size) % langs.size
            Prefs.setCurrentLang(this, currentLangCode())
            kind = LayoutKind.MAIN
            applyLayout()
            updateShiftState()
            updateStrip()
            recordAppLang()
        } else {
            cycleLanguage()
        }
    }

    override fun onHideKeyboard() {
        requestHideSelf(0)
    }

    override fun onSpaceLongPress() {
        when (Prefs.spaceLongPress(this)) {
            "ime" -> imePicker()
            "none" -> {}
            else -> cycleLanguage()
        }
    }

    override fun onQuickAction(code: Int) {
        closeDrawerIfOpen()
        when (code) {
            Codes.UNDO -> currentInputConnection?.let {
                sendCtrl(it, KeyEvent.KEYCODE_Z, shift = false)
            }
            Codes.REDO -> currentInputConnection?.let {
                sendCtrl(it, KeyEvent.KEYCODE_Z, shift = true)
            }
            Codes.COPY -> currentInputConnection?.performContextMenuAction(android.R.id.copy)
            Codes.PASTE -> currentInputConnection?.performContextMenuAction(android.R.id.paste)
            Codes.CUT -> currentInputConnection?.performContextMenuAction(android.R.id.cut)
            Codes.SELECT_ALL -> currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
            Codes.HIDE_KB -> requestHideSelf(0)
            Codes.TOOLBAR_PANEL -> showToolbarPanel()
            Codes.NUMPAD -> toggleNumpad()
            Codes.CLIPBOARD -> showClipPanel()
            Codes.EDIT_PANEL -> showEditPanel()
            Codes.EMOJI -> showEmoji()
            Codes.INCOGNITO -> toggleIncognito()
            Codes.SETTINGS -> openSettings()
            Codes.LANG -> cycleLanguage()
            Codes.ONE_HANDED -> toggleOneHanded()
            Codes.FLOATING -> toggleFloating()
            Codes.TRANSLATE -> currentInputConnection?.let { launchTranslate(it) }
            Codes.SHARE -> currentInputConnection?.let { shareText(it) }
            Codes.THEME -> cycleTheme()
            Codes.RESIZE -> cycleHeight()
        }
    }

    override fun onToolbarToggle(expand: Boolean) {
        val s = strip ?: return
        // Fill the row before revealing it: the drawer is shown directly rather
        // than through updateStrip, which is what normally feeds it.
        if (expand) feedTools(s)
        s.setDrawerOpen(expand)
    }

    override fun onDrawerClosed() {
        updateStrip()
    }

    // ------------------------------------------------------------ toolbar panel

    /** Reached from the "All tools" tool rather than the chevron. */
    private fun showToolbarPanel() {
        val tp = toolbarPanel ?: return
        finishComposingSilently()
        tp.setTools(pinnedTools())
        revealPanel(tp)
    }

    private fun hideToolbarPanel() {
        showKeyboardBack()
        toolbarPanel?.visibility = View.GONE
        updateStrip()
    }

    override fun onToolAction(code: Int) {
        hideToolbarPanel()
        // "All tools" is itself a tool in the panel, so running it from inside
        // would close and immediately reopen the panel. Closing is the intent.
        if (code == Codes.TOOLBAR_PANEL) return
        onQuickAction(code)
    }

    /** Running a tool from the drawer closes it, the way a menu closes. */
    private fun closeDrawerIfOpen() {
        val s = strip ?: return
        if (s.isDrawerOpen()) s.setDrawerOpen(false)
    }

    override fun onPinnedChanged(ids: List<String>) {
        Prefs.setPinnedTools(this, ids)
        pinnedCache = ids
        lastTools = null
        updateStrip()
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

    /** Shares the selected text (or the whole field) via the system share sheet.
     *  Nothing leaves the device unless the user picks a share target. */
    private fun shareText(ic: InputConnection) {
        val selected = ic.getSelectedText(0)?.toString()
        val text = if (!selected.isNullOrBlank()) selected
        else ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
            ?.text?.toString() ?: ""
        if (text.isBlank()) return
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text.take(20000))
            }
            startActivity(Intent.createChooser(send, null)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }

    /**
     * Steps to the next theme and re-applies it live.
     *
     * The order comes from the array the settings screen offers, not a copy of
     * it. The copy that used to live here had drifted six palettes behind, so
     * the Theme tool could never reach Ocean through Mint — and if you were on
     * one of them the lookup missed, dropping you back to "system" with no way
     * to cycle in.
     */
    private fun cycleTheme() {
        val values = resources.getStringArray(R.array.theme_values)
        if (values.isEmpty()) return
        val i = values.indexOf(Prefs.theme(this))
        val next = values[(if (i < 0) 0 else i + 1) % values.size]
        Prefs.get(this).edit().putString(Prefs.KEY_THEME, next).apply()
        currentInputEditorInfo?.let { readPrefsAndFieldFlags(it) }
        updateStrip()
    }

    /** Steps keyboard height to the next preset and re-lays out. Same array as
     *  the settings screen, for the same reason as [cycleTheme]. */
    private fun cycleHeight() {
        val values = resources.getStringArray(R.array.height_values)
        if (values.isEmpty()) return
        val cur = Prefs.heightFactor(this)
        val i = values.indexOfFirst { (it.toFloatOrNull() ?: 1f) == cur }
        // A stored height that is not one of the presets (an old backup, say)
        // resolves to the normal one rather than the smallest.
        val next = values[(if (i < 0) values.indexOf("1.0").coerceAtLeast(0) else i + 1) % values.size]
        Prefs.get(this).edit().putString(Prefs.KEY_HEIGHT, next).apply()
        keyboardView?.keyHeightFactor = next.toFloatOrNull() ?: 1f
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
        if (Prefs.emojiReturn(this)) restoreMainView()
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
