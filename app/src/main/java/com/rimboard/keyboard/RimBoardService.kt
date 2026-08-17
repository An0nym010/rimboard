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
import com.rimboard.keyboard.model.TapTiming
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
import com.rimboard.keyboard.ui.Thumbs
import java.io.File
import java.util.Locale
import kotlin.math.abs
import org.json.JSONArray

class RimBoardService : InputMethodService(),
    KeyboardView.Listener, SuggestionStripView.Listener, EmojiView.Listener,
    ClipboardView.Listener, EditPanelView.Listener,
    com.rimboard.keyboard.ui.ToolbarPanelView.Listener,
    com.rimboard.keyboard.ui.GifView.Listener,
    com.rimboard.keyboard.ui.TranslateView.Listener {

    private lateinit var userData: UserData
    private lateinit var engine: SuggestionEngine

    private var rootView: com.rimboard.keyboard.ui.PhotoBackdrop? = null
    private var strip: SuggestionStripView? = null
    private var keyboardView: KeyboardView? = null
    private var emojiView: EmojiView? = null
    private var clipboardView: ClipboardView? = null
    private var gifView: com.rimboard.keyboard.ui.GifView? = null
    private var translateView: com.rimboard.keyboard.ui.TranslateView? = null


    /**
     * The translation currently sitting in the field, so the next one replaces
     * it instead of piling up after it. Null once nothing has been inserted.
     */
    private var translateInserted: String? = null

    /**
     * The source text of the last translation actually sent.
     *
     * Typing a word, deleting it and retyping the same thing produced two
     * identical billed requests. The result has not changed, so the request
     * does not need making.
     */
    private var translateLastSource: String? = null

    /** When that request went out, for the floor between calls. */
    private var translateLastAt = 0L

    /** Requests this time the bar has been open, shown in the bar. */
    private var translateCount = 0

    /**
     * Floor between translation requests. The bar's own debounce is the main
     * control; this is the backstop against a typing rhythm that happens to
     * land on it repeatedly.
     */
    private val minTranslateGapMs = 1500L

    /**
     * How far past the end of the last translation to look for it when
     * replacing. Enough for a handful of emoji or a short pasted run typed
     * after it; beyond that the run is treated as lost and the new translation
     * simply goes in at the cursor.
     */
    private val FOREIGN_TAIL_MAX = 32

    /** Holds a picker shown *above* the keyboard rather than instead of it. */
    private var searchHost: FrameLayout? = null

    /** Holds the translate bar, above the suggestion strip. See where it is built. */
    private var barHost: FrameLayout? = null

    /** Which picker, if any, is currently eating keystrokes for its search box. */
    private enum class SearchRoute { NONE, GIF, EMOJI, TRANSLATE }

    private var searchRoute = SearchRoute.NONE

    /**
     * The text that seeded the current GIF search, so picking a result can
     * remove it from the field. Null when the search came from a chip, which
     * put nothing in the field to clean up.
     */
    private var gifQueryFromField: String? = null

    /**
     * How many characters that seed occupies in the field. Tracked separately
     * because the query is normalised and the field is not.
     */
    private var gifQueryFieldLength: Int = 0

    /** Clipboard history lives only in memory; it is never written to disk. */
    private class ClipEntry(val text: String, val at: Long)

    private val clipHistory = ArrayDeque<ClipEntry>()
    private var clipChangedListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var editPanelView: EditPanelView? = null
    private var toolbarPanel: com.rimboard.keyboard.ui.ToolbarPanelView? = null

    /** The panel plus its close bar. Shown and hidden as one; see onCreateInputView. */
    private var toolbarPanelHost: LinearLayout? = null
    private var toolbarCloseBtn: TextView? = null
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

    /**
     * Whether the cursor is genuinely at the start of a sentence.
     *
     * [prevWordForBigram] being empty means two different things — "a sentence
     * just ended" and "the last thing committed was not a word I can predict
     * from" — and they need opposite handling now that an empty context is a
     * real prediction key rather than a dead end. Without this, committing an
     * emoji or a two-word suggestion mid-sentence made the strip offer message
     * openers ("I", "the", "thanks") in the middle of a line, and taught the
     * opener model words that never started anything.
     */
    private var atSentenceStart = true

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

    /**
     * [learnable] is false for whole-phrase reverts such as a translation.
     * Reverting a corrected *word* teaches the dictionary that word and moves
     * the bigram context onto it; doing either with an entire sentence would
     * file the sentence away as a word and poison the next-word context.
     */
    private class Revert(
        val original: String,
        val committed: String,
        val separator: String,
        val learnable: Boolean = true
    )

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
        thumbPool.shutdownNow()
        userData.flushBlocking()
        userData.shutdown()
        // onFinishInputView flushes these too, but it is not guaranteed to run
        // before the service goes away.
        Stats.flush(this)
        super.onDestroy()
    }

    /**
     * The platform asking for memory back before it starts killing processes.
     *
     * A keyboard is unusually exposed here: it is a background process most of
     * the time, so it is near the front of the kill list, and being killed
     * mid-sentence is the most visible failure this app has. Loaded
     * dictionaries are by far the largest thing it holds and the cheapest to
     * rebuild — one asset parse, on the warm thread, the next time that
     * language is typed.
     *
     * Only the languages currently selected are kept, and only from
     * [TRIM_MEMORY_RUNNING_LOW] up: below that the platform is asking for
     * spare change, and dropping a dictionary the user is about to type in
     * would trade a stall for nothing.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level < TRIM_MEMORY_RUNNING_LOW) return
        val keep = if (level >= TRIM_MEMORY_COMPLETE) emptySet()
        else setOfNotNull(effLang(), effAlt())
        SuggestionEngine.trimDictionaries(keep)
        // Second only to the dictionaries in size, and cheaper still to give
        // up: a thumbnail is one download away, and if the panel is open the
        // placeholders return rather than the grid emptying.
        gifView?.releaseThumbnails()
        com.rimboard.keyboard.theme.AppPalette.clearCache()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        // A rotation or a floating-mode toggle rebuilds the input view, and any
        // popup still up is anchored to the one being replaced.
        dismissPopups()
        val ctx = L10n.wrap(this)
        // The column itself draws the background photo, behind the strip and
        // the keys alike; a plain LinearLayout here left the strip a flat bar
        // cutting the picture off at the top of the keyboard.
        val root = com.rimboard.keyboard.ui.PhotoBackdrop(ctx)
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
        }
        // Wrapped rather than modified. The panel is a single canvas-drawn view
        // with its own accessibility node tree, so a control drawn inside it
        // would have to be hand-registered there to exist for a screen reader.
        // A plain TextView in a wrapper is a real view: focusable, announced,
        // and themed like every other control.
        val tpClose = TextView(ctx).apply {
            text = getString(R.string.panel_close)
            gravity = android.view.Gravity.CENTER
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(10), 0, dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { hideToolbarPanel() }
        }
        val tpWrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        tpWrap.addView(tp, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        tpWrap.addView(tpClose, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        frame.addView(tpWrap, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        val gv = com.rimboard.keyboard.ui.GifView(ctx).apply {
            listener = this@RimBoardService
            visibility = View.GONE
        }
        val tv = com.rimboard.keyboard.ui.TranslateView(ctx).apply {
            listener = this@RimBoardService
            visibility = View.GONE
        }
        // Sits between the strip and the keyboard, so a picker can occupy the
        // top while the real keyboard stays where it always is. The panels in
        // `frame` replace the keyboard; anything in here sits above it, which
        // is what lets GIF and emoji search be typed on the actual keys
        // instead of a second miniature keyboard drawn inside the panel.
        val sh = FrameLayout(ctx).apply { visibility = View.GONE }
        sh.addView(ev, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        sh.addView(gv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(sh, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0))
        searchHost = sh

        // The translate bar goes *above* the suggestion strip rather than into
        // `sh` with the pickers. Below it, the bar pushed the strip up and away
        // from the keys and took its place at the edge — so opening 🌍 looked
        // like the suggestion bar had been replaced. Suggestions still work
        // while translating, and the strip belongs next to the keyboard.
        val bh = FrameLayout(ctx).apply { visibility = View.GONE }
        bh.addView(tv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(bh, 0, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0))
        barHost = bh

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
        toolbarPanelHost = tpWrap
        toolbarCloseBtn = tpClose
        gifView = gv
        translateView = tv
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
        atSentenceStart = true
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
        // Panel visibility survives the input view being hidden and shown
        // again, so without this the keyboard could return still covered by
        // whatever panel was open when it went away — including the tools
        // panel, which has no exit of its own.
        closeAnyPanel()
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

        // Not gated on incognito any more: what incognito withholds is the
        // learned data behind a suggestion, not the suggestion itself. The
        // callers pass `personalized = !isIncognito()` so nothing from history
        // reaches the strip.
        suggestionsActive = Prefs.suggestions(this) && isTextClass && !isPassword &&
            !fieldNoSuggestions && !isEmailOrUri
        autocorrectActive = Prefs.autocorrect(this) && isTextClass && !isPassword &&
            !fieldNoSuggestions && !isEmailOrUri

        val themePref = Prefs.theme(this)
        // Tinted before the photo variant is derived from it, not after: over a
        // photo the caps become scrims and only the accent survives from the
        // base theme, so tinting afterwards would be the one case where this
        // feature did nothing.
        kbTheme = Themes.resolve(this, themePref).let { base ->
            if (Prefs.themePerApp(this) && Themes.tintable(themePref))
                Themes.forApp(
                    base, info.packageName,
                    // The app's real colour when its icon can be read, and the
                    // package-name hue when it cannot. Which of those happens
                    // is decided by package visibility, not by anything here.
                    com.rimboard.keyboard.theme.AppPalette.hueOf(
                        this, info.packageName, Prefs.curatedColorsOnly(this)
                    ),
                    Prefs.tintStrength(this)
                )
            else base
        }
        val t = kbTheme ?: return
        val bgDimAlpha = Prefs.bgDimAlpha(this)
        // With a photo, the keys switch to translucent scrims whose polarity
        // follows the image (see Themes.overPhoto), and the strip — which sits
        // on the same photo now — takes the adapted colours over a transparent
        // background. The panels cover the photo with their own opaque surface,
        // so they keep the base theme.
        val hasBgImage =
            File(UserData.dataDir(this), "bg_image.jpg").exists()
        val photoTheme =
            if (hasBgImage) Themes.overPhoto(t, Prefs.bgLuma(this), bgDimAlpha) else null
        keyboardView?.let { kv ->
            kv.theme = photoTheme ?: t
            kv.previewEnabled = Prefs.popupPreview(this)
            kv.glideEnabled = Prefs.glide(this)
            when (Prefs.repeatSpeed(this)) {
                "slow" -> { kv.repeatInitialMs = 420L; kv.repeatIntervalMs = 70L }
                "fast" -> { kv.repeatInitialMs = 200L; kv.repeatIntervalMs = 32L }
                else -> { kv.repeatInitialMs = 300L; kv.repeatIntervalMs = 50L }
            }
            kv.showTrail = Prefs.glideTrail(this)
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
        strip?.applyTheme(photoTheme?.copy(background = 0x00000000) ?: t)
        // Panels sit on the same backdrop, so with a photo set they take a
        // translucent surface and the picture carries on behind them instead
        // of stopping dead the moment one opens. Everything else in the theme
        // is unchanged — see Themes.panelOverPhoto for why that is safe.
        val panelTheme = if (photoTheme != null) Themes.panelOverPhoto(t) else t
        emojiView?.applyTheme(panelTheme)
        clipboardView?.applyTheme(panelTheme)
        editPanelView?.applyTheme(panelTheme)
        toolbarPanel?.applyTheme(panelTheme)
        toolbarPanelHost?.setBackgroundColor(panelTheme.background)
        toolbarCloseBtn?.setTextColor(panelTheme.accent)
        gifView?.applyTheme(panelTheme)
        translateView?.applyTheme(panelTheme)
        rootView?.setBackgroundColor(t.background)
        rootView?.dimAlpha = bgDimAlpha
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
        if (consumedBySearch(key, Source.TAP)) return
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
        if (consumedBySearch(key, Source.REPEAT)) return
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
        if (consumedBySearch(key, Source.POPUP)) return
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
        // The strip is refreshed from onUpdateSelection rather than here: the
        // key events above move the cursor asynchronously, so reading the text
        // around it now would describe the position we just left.
    }

    /**
     * Re-derives the next-word context from the text actually before the cursor.
     *
     * Sliding along the spacebar finishes any composing word, which left the
     * strip with nothing to show and a bigram context pointing at wherever the
     * cursor used to be — so the suggestions went blank and stayed blank. The
     * words either side of the new position are what predictions should be
     * based on, and they are cheap to read back.
     */
    private fun refreshContextFromCursor() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(96, 0)?.toString() ?: return
        // Trailing whitespace means the cursor sits between words, which is
        // where a next-word prediction makes sense. Mid-word it does not, and
        // the composing branch of updateStrip handles that case anyway.
        val loc = locale()
        val words = Regex("""[\p{L}\p{N}']+""").findAll(before).map { it.value }.toList()
        prevWordForBigram = words.lastOrNull()?.lowercase(loc).orEmpty()
        prevWord2 = words.getOrNull(words.size - 2)?.lowercase(loc).orEmpty()
        // Derived from the same text as the two words above, rather than left
        // behind from wherever the cursor used to be. An empty context means
        // opposite things either side of this call — "nothing to go on" or "the
        // start of a sentence" — so leaving it stale made the same cursor
        // position offer different suggestions depending on history: tapping to
        // the front of a field that already held a sentence produced no openers,
        // where the identical position in a fresh field produces them.
        // Only spaces and tabs are trimmed: a trailing newline is itself a
        // sentence break (handleEnter says so), and trimEnd() would eat the very
        // character being tested for.
        val tail = before.trimEnd(' ', '\t')
        atSentenceStart = tail.isEmpty() || tail.last() in ".!?\n"
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
        // Glided into a picker's search box: the word goes to the query, and
        // none of the learning, autospace or bigram bookkeeping below applies
        // because nothing was committed to a text field.
        if (searchRoute != SearchRoute.NONE) {
            best.forEach { routeCharToSearch(it) }
            return
        }
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
        if (canLearn && Prefs.predictions(this) && (prevWordForBigram.isNotEmpty() || atSentenceStart)) {
            userData.recordNgram(prevWord2, prevWordForBigram, best.lowercase(loc))
        }
        prevWordForBigram = best.lowercase(loc)
        atSentenceStart = false
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
        atSentenceStart = false
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
        if (sep != " ") {
            prevWordForBigram = ""
            // A comma or a colon ends a word, not a sentence, so it must not
            // start offering message openers.
            if (sep.any { it in ".!?\n" }) atSentenceStart = true
        }
        afterEdit()
    }

    private fun commitComposedWord(ic: InputConnection, allowAutocorrect: Boolean, separator: String) {
        val typed = composing.toString()
        var finalWord = typed
        if (allowAutocorrect) {
            val shortcutExp = Shortcuts.expansionFor(this, typed, effLocale())
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
        if (canLearn && Prefs.predictions(this) && wordish && (prevWordForBigram.isNotEmpty() || atSentenceStart)) {
            userData.recordNgram(prevWord2, prevWordForBigram, fw)
        }
        prevWordForBigram = if (wordish) fw else ""
        atSentenceStart = false
        composing.setLength(0)
    }

    private fun handleSpace() {
        val ic = currentInputConnection ?: return
        // uptimeMillis, not currentTimeMillis: this measures the gap between two
        // taps, and wall-clock time is not monotonic — an NTP correction or the
        // user changing the clock can move it backwards, which makes the
        // subtraction negative, which reads as "well under 500ms". A lone space
        // after a letter would then be silently rewritten to ". ". The rest of
        // this file already times intervals on uptimeMillis.
        val now = SystemClock.uptimeMillis()
        val hadRecentSpace = TapTiming.isDoubleTap(now, lastSpaceTime, 500)
        if (composing.isEmpty() && Prefs.doubleSpace(this) && hadRecentSpace) {
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
                atSentenceStart = true
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
        // Same reason as [handleSpace]: a backwards clock step turns any two
        // shift taps into a double-tap and silently engages caps lock.
        val now = SystemClock.uptimeMillis()
        // The 0 here is also set by typing a character, which is what stops a
        // shift from before a word pairing with one after it.
        if (TapTiming.isDoubleTap(now, lastShiftTapTime, 300)) {
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
        atSentenceStart = true
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
        // Incognito used to end here, replacing the strip with a label — so
        // the keyboard stopped helping at all the moment it was switched on,
        // which is a much bigger price than the setting asks for. What
        // incognito promises is that nothing is learned and nothing is
        // suggested *from history*; the dictionary and the bundled model are
        // neither. They still answer, the learned data stays out (see
        // `personalized` below), and the mark rides along so the state is never
        // in doubt.
        s.incognitoMark = isIncognito()
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
            // Nothing in the field at all: no word to suggest from and none to
            // suggest after, so the strip carries nothing. Openers used to
            // appear here, which meant an untouched field already had three
            // words in it before anything had been typed.
            if (fieldIsEmpty()) {
                maybeClipboardOrEmpty(s)
                return
            }
            // An empty context is only a prediction key at a real sentence
            // start; mid-sentence it means "nothing to go on", and offering
            // openers there would be worse than offering nothing.
            var preds = if (Prefs.predictions(this) &&
                (prevWordForBigram.isNotEmpty() || atSentenceStart)
            ) {
                engine.predictions(
                    prevWord2, prevWordForBigram, currentLangCode(), locale(), 3,
                    personalized = !isIncognito()
                )
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
            allowAutocorrect = autocorrectActive,
            // The learned vocabulary is history; in incognito the dictionary
            // answers on its own.
            personalized = !isIncognito(),
            altLang = effAlt(),
            altLocale = effAltLocale(),
            // The word before the one being typed, so completions and
            // corrections can be ranked in context rather than by raw
            // frequency alone.
            prevWord2 = prevWord2,
            prevWord = prevWordForBigram
        )
        val shortcutExp = Shortcuts.expansionFor(this, composing.toString(), effLocale())
        var shownWords = res.items
        var shownHi = res.autocorrectIndex
        if (shortcutExp != null) {
            shownWords = listOf(shortcutExp) + shownWords.take(2)
            shownHi = 0
        } else {
            val arranged = arrangeUnknownWord(res)
            shownWords = arranged.first
            shownHi = arranged.second
        }
        // The emoji has a chip of its own beside the words, so offering one
        // never costs a suggestion. Blocked emoji stay blocked: long-pressing
        // a chip offers to remove it, and without this the emoji was the one
        // suggestion that came straight back.
        val emojiSug = if (composing.length >= 2)
            engine.emojiFor(composing.toString().lowercase(effLocale()), effLang())
                ?.takeIf { !userData.isBlocked(it) }
        else null
        s.showSuggestions(shownWords, shownHi, emojiSug)
    }

    /**
     * The word the strip is currently showing in quotes, and what it really is.
     *
     * The quotes are decoration — the chip reads `"hellooo"` and must commit
     * `hellooo`. Kept as a pair rather than parsed back off the chip, because
     * stripping quotation marks from a picked word would also strip them from
     * someone who genuinely typed a quoted word.
     */
    private var quotedChip: Pair<String, String>? = null

    /** Wraps a word for display as "not a word I know". */
    private fun quoted(word: String) = "“$word”"

    /**
     * Puts an unrecognised word in the middle of the strip, in quotes, instead
     * of at the front bare.
     *
     * Slot 0 was always the verbatim word, which meant an unknown word looked
     * exactly like a known one and sat where the best suggestion should be. Now
     * it is marked as unrecognised and moved off the front, so the two
     * suggestions either side of it are the ones being offered — and the word
     * itself is still there to tap, which is what stops the keyboard from
     * arguing with someone typing a name.
     *
     * With nothing to suggest at all — no completion, no correction, no
     * near-miss — the word is alone on the strip. That is the honest display
     * for something like "mndsnfms": there is no candidate to rank against it,
     * and filling the other two slots would mean inventing something. Note this
     * is decided by *having no candidates*, not by judging the word random;
     * the keyboard has no business declaring what is and is not a word.
     */
    private fun arrangeUnknownWord(res: com.rimboard.keyboard.engine.SuggestionsResult):
        Pair<List<String>, Int> {
        val verbatim = res.items.firstOrNull()
        val known = verbatim != null &&
            engine.acceptedWord(verbatim, effLang(), effLocale(), effAlt(), effAltLocale())
        val out = com.rimboard.keyboard.model.StripLayout.arrange(
            res.items, res.autocorrectIndex, known, ::quoted
        )
        quotedChip = out.quotedWord?.let { quoted(it) to it }
        return out.words to out.highlight
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

    /** Whether the field holds no text at all, either side of the cursor. */
    private fun fieldIsEmpty(): Boolean {
        val ic = currentInputConnection ?: return false
        val before = ic.getTextBeforeCursor(1, 0)
        val after = ic.getTextAfterCursor(1, 0)
        return before.isNullOrEmpty() && after.isNullOrEmpty()
    }

    private fun maybeClipboardOrEmpty(s: SuggestionStripView) {
        if (maybeDomainChips(s)) return
        if (clipChipEligible()) {
            s.showClipboard(L10n.wrap(this).getString(android.R.string.paste))
        } else {
            feedIdle(s)
            // With nothing to suggest there is room to say what incognito is
            // doing, rather than only marking it. When there *are* suggestions
            // the icon rides alongside them instead — see [showSuggestions].
            if (isIncognito()) s.showIncognito(getString(R.string.incognito_label))
            else s.showEmpty()
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
        // The quotes on an unrecognised word are a label, not part of it.
        quotedChip?.let { (chip, raw) -> if (word == chip) return onSuggestionPicked(index, raw) }
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
        if (canLearn && Prefs.predictions(this) && wordish && (prevWordForBigram.isNotEmpty() || atSentenceStart)) {
            userData.recordNgram(prevWord2, prevWordForBigram, word.lowercase(loc))
        }
        prevWordForBigram = if (wordish) word.lowercase(loc) else ""
        atSentenceStart = false
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
        if (rv.learnable && Prefs.learnWords(this) && !isIncognito()) {
            userData.markKnown(rv.original.lowercase(locale()))
        }
        if (rv.learnable) {
            // Reverting swaps the last word in place; keep the trigram context.
            val keep2 = prevWord2
            prevWordForBigram = rv.original.lowercase(locale())
            prevWord2 = keep2
        }
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
            // A cursor move lands here with no composing text, and the
            // next-word context still describes wherever the cursor *was*.
            // Re-reading it from the new position is what keeps the strip
            // useful after sliding along the spacebar instead of blanking it.
            refreshContextFromCursor()
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
    }

    private fun restoreMainView() {
        // closeSearchHost covers the emoji and GIF pickers, which live above
        // the keyboard rather than over it and so are not hidden by clearing
        // the panels in `frame`.
        closeSearchHost()
        clipboardView?.visibility = View.GONE
        editPanelView?.visibility = View.GONE
        toolbarPanelHost?.visibility = View.GONE
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
    /** Every panel that can cover the keyboard. One list, so none gets missed. */
    private fun panels() =
        arrayOf(clipboardView, editPanelView, toolbarPanelHost)

    /** The pickers are not in [panels]: they sit above the keyboard, not over it. */
    private fun anyPanelOpen() =
        panels().any { it?.visibility == View.VISIBLE } ||
            searchHost?.visibility == View.VISIBLE ||
            barHost?.visibility == View.VISIBLE

    /**
     * Puts the keyboard back, whatever panel was covering it.
     *
     * The tools panel had no way out at all: every other panel carries an ABC
     * button, but that one is a custom-drawn grid whose only listener is "a
     * tool was tapped", so the sole way to leave it was to run something —
     * which is not a way to leave a screen, it is a way to be forced into an
     * action you did not want.
     */
    private fun closeAnyPanel() {
        closeSearchHost()
        panels().forEach { it?.visibility = View.GONE }
        gifQueryFromField = null
        gifQueryFieldLength = 0
        showKeyboardBack()
        updateStrip()
    }

    /**
     * Back closes the open panel before it closes the keyboard.
     *
     * Nothing handled back here before, so it fell through to the default
     * "hide the whole IME" — which looked like it worked, but left the panel's
     * visibility set. Tapping the field again brought the keyboard back still
     * showing the panel, and for the tools panel that was a dead end the user
     * could not get out of. [onStartInputView] now clears that state too, so
     * the stale-panel half of the bug cannot come back through another route.
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        // One level at a time: the language list is a layer inside the
        // translate bar, so Back should dismiss that before dismissing the bar
        // and losing what was typed into it.
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK &&
            translateView?.isPickingLanguage() == true
        ) {
            translateView?.closeLanguageList()
            return true
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK && anyPanelOpen()) {
            closeAnyPanel()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

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
        // Panels live in `frame` and the pickers and translate bar live outside
        // it, so hiding one family says nothing about the other. Without this,
        // opening the
        // clipboard over an open GIF picker left both on screen and left
        // keystrokes routing into the query underneath.
        closeSearchHost()
        val lp = panel.layoutParams as FrameLayout.LayoutParams
        lp.height = kv.measureKeyboardHeight()
        panel.layoutParams = lp
        for (other in arrayOf(clipboardView, editPanelView, toolbarPanelHost)) {
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
        // Browsing needs no keys, so the panel takes the keyboard's whole
        // height and the keys hide beneath it — the same arrangement as before.
        // Searching then shrinks it and brings them back; see onEmojiSearchMode.
        openSearchHost(ev, SearchRoute.NONE, withKeyboard = false)
    }

    /**
     * Emoji search opened or closed.
     *
     * The panel has no keys of its own now, so entering search has to make room
     * for the real ones and start sending them here instead of to the field.
     */
    override fun onEmojiSearchMode(active: Boolean) {
        val ev = emojiView ?: return
        if (active) openSearchHost(ev, SearchRoute.EMOJI, withKeyboard = true)
        else openSearchHost(ev, SearchRoute.NONE, withKeyboard = false)
    }

    private fun hideEmoji() {
        showKeyboardBack()
        closeSearchHost()
        clipboardView?.visibility = View.GONE
        editPanelView?.visibility = View.GONE
        toolbarPanelHost?.visibility = View.GONE
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

    override fun onEmojiSuggestionPicked(emoji: String) {
        val ic = currentInputConnection ?: return
        // Replaces the word being typed, the way picking a suggestion does —
        // the emoji is an alternative to that word, not an addition to it.
        ic.beginBatchEdit()
        composing.setLength(0)
        ic.commitText(emoji, 1)
        ic.endBatchEdit()
        prevWordForBigram = ""
        atSentenceStart = false
        revert = null
        autoSpace = false
        glideWords = emptyList()
        if (!isIncognito()) {
            val recents = (listOf(emoji) + Prefs.emojiRecents(this).filter { it != emoji }).take(24)
            Prefs.setEmojiRecents(this, recents)
        }
        if (Prefs.haptic(this)) keyboardView?.let { Haptics.tap(it) }
        afterEdit()
    }

    override fun onSuggestionLongPressed(word: String, anchor: View) {
        // The calculator chip and revert chip aren't dictionary words; long-press
        // (block-word) doesn't apply to them.
        if (word.startsWith("= ") || word.startsWith("↩")) return
        // Nor is the quoted verbatim word: it is not a suggestion the keyboard
        // made, so there is nothing to tell it to stop suggesting.
        if (quotedChip?.first == word) return
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
            Codes.GIF -> showGifPanel()
            Codes.PROOFREAD -> currentInputConnection?.let { proofreadInPlace(it) }
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
        tp.setUnavailable(unavailableTools())
        revealPanel(toolbarPanelHost ?: return)
    }

    private fun hideToolbarPanel() {
        showKeyboardBack()
        toolbarPanelHost?.visibility = View.GONE
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

    /**
     * The 🌍 tool. Translates in place when this build can and the user has
     * opted in, and otherwise does what it has always done: hand the text to
     * another app.
     *
     * One tool, two implementations, chosen by what is actually available —
     * rather than a second icon that is dead on the offline build. The offline
     * build, and the online build with network off or no API key, keep exactly
     * the previous behaviour.
     */
    private fun launchTranslate(ic: InputConnection) {
        val block = com.rimboard.keyboard.net.Net.blockedBy(this, sendsTypedText = true)
        // In-place translation needs a network, not a key: the keyless engine
        // covers anyone without an Anthropic key. So the bar opens whenever the
        // request would be allowed, and the engine is chosen at request time.
        if (block == null) {
            showTranslatePanel(ic)
            return
        }

        // The offline build can never translate in place, so handing the text
        // to another app is what 🌍 *means* there and it happens silently.
        if (block == com.rimboard.keyboard.net.Net.Block.NO_PERMISSION) {
            if (!launchExternalTranslate(ic)) toast(getString(R.string.tr_no_app))
            return
        }

        // Network off or incognito — fixable, and named rather than silently
        // routed into an app chooser wearing the same button.
        toastLong(getString(netBlockMessage(block)))
    }

    /**
     * Which online tools cannot run right now, and the short reason for each.
     *
     * Computed at the moment the panel opens rather than cached: incognito and
     * the network switch both change under it, and a stale "ready" is worse
     * than no marking at all. Short phrasings, because these are drawn under a
     * 62dp icon rather than shown as a message.
     */
    private fun unavailableTools(): Map<String, String> {
        val out = HashMap<String, String>()
        val block = com.rimboard.keyboard.net.Net.blockedBy(this, sendsTypedText = true)
        val locked = !com.rimboard.keyboard.net.ApiKeys.unlocked(this)
        val shared = when {
            block == com.rimboard.keyboard.net.Net.Block.NO_PERMISSION ->
                getString(R.string.tool_off_build)
            block == com.rimboard.keyboard.net.Net.Block.INCOGNITO ->
                getString(R.string.tool_off_incognito)
            block != null -> getString(R.string.tool_off_network)
            locked -> getString(R.string.tool_off_locked)
            else -> null
        }
        // A missing key is per-feature: the two use different services, which
        // is exactly the confusion that had someone set one and expect both.
        val gifReason = shared
            ?: getString(R.string.tool_off_key).takeIf {
                com.rimboard.keyboard.net.ApiKeys.klipy(this) == null
            }
        val aiReason = shared
            ?: getString(R.string.tool_off_key).takeIf {
                com.rimboard.keyboard.net.ApiKeys.anthropic(this) == null
            }
        gifReason?.let { out["gif"] = it }
        // Proofread is Anthropic-only, so a missing Anthropic key disables it.
        aiReason?.let { out["proofread"] = it }
        // Translate works keyless now, so it needs only a network — never a
        // key. On the offline build it still hands off to another app, so it is
        // only truly unavailable when the block is something other than that.
        if (shared != null && block != com.rimboard.keyboard.net.Net.Block.NO_PERMISSION) {
            out["translate"] = shared
        }
        return out
    }

    /**
     * Why a network feature is unavailable, as one message.
     *
     * The GIF picker, proofread and translate all refuse for the same four
     * reasons and each had its own copy of this mapping. Three copies of a
     * four-way branch is three chances for one of them to fall behind.
     */
    private fun netBlockMessage(block: com.rimboard.keyboard.net.Net.Block): Int = when (block) {
        com.rimboard.keyboard.net.Net.Block.NO_PERMISSION -> R.string.gif_offline_build
        com.rimboard.keyboard.net.Net.Block.INCOGNITO -> R.string.ai_incognito
        else -> R.string.gif_network_off
    }

    /**
     * Replaces the selection with its translation, into whichever language the
     * keyboard is currently set to — so the target is the language you are
     * typing in, which is nearly always the one you want and needs no picker.
     *
     * Opens the translate panel above the keyboard.
     *
     * A selection seeds it, but nothing requires one: the old flow could only
     * translate text that already existed, so composing a message in your own
     * language and sending it in another — the thing people actually want a
     * keyboard translator for — was impossible. Seeding also means the result
     * is previewed rather than overwriting the selection sight-unseen.
     */
    /** The translation service in force; see `Translate.effective`. */
    private fun translateSrc(): com.rimboard.keyboard.net.Translate.Src =
        com.rimboard.keyboard.net.Translate.effective(this)

    private fun translateWithAnthropic(): Boolean =
        translateSrc() == com.rimboard.keyboard.net.Translate.Src.ANTHROPIC

    private fun showTranslatePanel(ic: InputConnection) {
        val tv = translateView ?: return
        finishComposingSilently()
        val selected = ic.getSelectedText(0)?.toString()?.takeIf { it.isNotBlank() }
        val TT = com.rimboard.keyboard.model.TranslateTargets
        // Every reset happens *before* the bar is started, because starting it
        // with a selection sends a request there and then. Clearing afterwards
        // wiped the bookkeeping for a request already on the wire: the counter
        // went back to zero with one call outstanding, the deduplication forgot
        // what had just been asked so an unchanged source could be billed
        // twice, and a generation moved on here would strand the very reply the
        // seed was waiting for.
        translateInserted = null
        translateLastSource = null
        translateLastAt = 0L
        translateCount = 0
        // A reply still in flight from a previous session of the bar belongs to
        // the text that was being typed then, not to what is about to be.
        translateGeneration++
        tv.setRequestCount(0)
        if (translateWithAnthropic()) {
            tv.start(selected, TT.currentLabel(this, effLocale()))
        } else {
            // The keyless services cannot be asked for "whatever my keyboard
            // language is", so an "auto" target resolves to a concrete other
            // language here — shown on the chip, so the pair is honest.
            tv.start(selected, TT.labelFor(TT.keylessTarget(this, currentLangCode()), effLocale()))
        }
        tv.setLanguages(
            com.rimboard.keyboard.model.TranslateTargets.list(this, effLocale())
                .map { it.code to it.label }
        ) { code -> onTranslateTargetPicked(code) }
        openSearchHost(tv, SearchRoute.TRANSLATE, withKeyboard = true, compact = true)
    }

    override fun onTranslateRequest(text: String) {
        val tv = translateView ?: return

        // Auto-insert means this fires on a timer rather than on a tap, so
        // nothing but these guards stands between a long message and a long
        // bill. The user is paying per call with their own key.
        if (text == translateLastSource) {
            // Same words as last time — the answer would be the same too.
            return
        }
        val since = android.os.SystemClock.uptimeMillis() - translateLastAt
        if (translateLastAt != 0L && since < minTranslateGapMs) {
            // Too soon after the last one. Reschedule rather than drop, so a
            // fast typist gets the translation late instead of never.
            tv.retryAfter(minTranslateGapMs - since)
            return
        }
        translateLastSource = text
        translateLastAt = android.os.SystemClock.uptimeMillis()
        translateCount++
        tv.setRequestCount(translateCount)
        tv.setStatus(getString(R.string.ai_translating))
        val TT = com.rimboard.keyboard.model.TranslateTargets
        val src = translateSrc()
        val source = currentLangCode()
        val generation = ++translateGeneration
        Thread {
            val result = if (src == com.rimboard.keyboard.net.Translate.Src.ANTHROPIC) {
                com.rimboard.keyboard.net.AiText.run(
                    this, com.rimboard.keyboard.net.AiText.Task.TRANSLATE, text,
                    TT.promptName(this, source)
                )
            } else {
                com.rimboard.keyboard.net.Translate.run(
                    this, src, text, TT.keylessTarget(this, source)
                )
            }
            main {
                // A reply to a question no longer being asked. Two ways that
                // happens, and this insert is the one place it would be felt:
                // closing the bar does not abort a request already on the wire,
                // so the answer used to arrive afterwards and type itself into
                // the message; and a slow reply outliving the next one would
                // overwrite the newer translation with the older, because
                // whichever landed last won. The GIF thumbnails already work
                // this way — same hazard, same counter.
                if (generation != translateGeneration) return@main
                result.fold(
                    onSuccess = { tv.setResult(it) },
                    onFailure = {
                        // The dedupe above is keyed on the text of the last
                        // request, and it was set before the request rather
                        // than after a reply — so a request that failed still
                        // counted as "already asked". A translation lost to a
                        // dropped connection could then never be retried: the
                        // timer fired again with the same words and was turned
                        // away by its own record of the attempt, and the only
                        // way out was to edit the message. Forgetting it here
                        // makes the next attempt go through; the 1.5s floor and
                        // the bar's debounce still bound how often that is.
                        translateLastSource = null
                        tv.setStatus(getString(R.string.ai_failed, netError(it)))
                    }
                )
            }
        }.start()
    }

    /**
     * Rises with every translation request, and again whenever the bar is
     * opened or closed. Anything that comes back carrying an older number is
     * answering a question that has since been replaced or withdrawn.
     */
    private var translateGeneration = 0

    /**
     * Puts a translation in the field, replacing the one already there.
     *
     * This is what makes the text appear on its own as you type, the way
     * Gboard's does — the bar shows what you typed and the field shows the
     * translation. Each new result deletes the previous one first, so pausing
     * twice does not leave two translations end to end.
     *
     * The delete is conditional on the text still being there: the user may
     * have tapped elsewhere or the app may have reformatted, and blindly
     * deleting that many characters would eat something else.
     */
    override fun onTranslateApply(text: String) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        val prev = translateInserted
        var tail = ""
        if (prev != null) {
            // The previous translation is not always the last thing in the
            // field. Anything committed while the bar was open — an emoji off
            // the strip, a pasted clip — lands after it, and checking only the
            // characters immediately before the cursor then failed to match, so
            // nothing was removed and the whole translation was written a
            // second time. That is the duplicate: translation, emoji,
            // translation again.
            //
            // So the run is looked for in a window rather than only at the end,
            // and whatever was typed after it is carried across to sit after
            // the new translation, which is where the user put it.
            val window = ic.getTextBeforeCursor(prev.length + FOREIGN_TAIL_MAX, 0)?.toString()
            val at = window?.lastIndexOf(prev) ?: -1
            if (window != null && at >= 0) {
                tail = window.substring(at + prev.length)
                ic.deleteSurroundingText(window.length - at, 0)
            }
        }
        // commitText replaces a selection when there is one, which is what
        // seeds the first insert when the bar was opened on selected text.
        ic.commitText(text + tail, 1)
        ic.endBatchEdit()
        translateInserted = text
        afterEdit()
    }

    override fun onTranslateTargetPicked(code: String) {
        val TT = com.rimboard.keyboard.model.TranslateTargets
        TT.store(this, code)
        // Show the language that will actually be used. On the keyless engine
        // "auto" resolves to a concrete other-language, so the chip reflects
        // that rather than the word "auto".
        val label = if (translateWithAnthropic()) TT.currentLabel(this, effLocale())
        else TT.labelFor(TT.keylessTarget(this, currentLangCode()), effLocale())
        translateView?.setTargetLabel(label)
        // The target changed, so the same source now has a different correct
        // answer — the dedupe must not suppress the re-request.
        translateLastSource = null
        translateView?.flush()
    }

    /** The language list needs more room than the bar; give it the picker height. */
    override fun onTranslateExpand(expanded: Boolean) {
        val tv = translateView ?: return
        openSearchHost(
            tv, SearchRoute.TRANSLATE, withKeyboard = true,
            compact = !expanded
        )
    }

    override fun onTranslateClose() {
        closeSearchHost()
    }

    /**
     * Fixes spelling, grammar and punctuation in the selection, leaving the
     * wording alone.
     *
     * Falls back to nothing rather than to an external app the way 🌍 does:
     * there is no system action for "proofread", so on a build that cannot
     * reach the network this simply reports why.
     */
    private fun proofreadInPlace(ic: InputConnection) {
        com.rimboard.keyboard.net.Net.blockedBy(this, sendsTypedText = true)?.let {
            toast(getString(netBlockMessage(it)))
            return
        }
        if (!com.rimboard.keyboard.net.ApiKeys.unlocked(this)) {
            toast(getString(R.string.net_locked))
            return
        }
        if (com.rimboard.keyboard.net.ApiKeys.anthropic(this) == null) {
            toast(getString(R.string.ai_no_key))
            return
        }
        aiTransform(ic, com.rimboard.keyboard.net.AiText.Task.PROOFREAD, null,
            R.string.ai_proofreading)
    }

    /**
     * Runs an [AiText] task over the selection and replaces it.
     *
     * Shared by both AI actions so the guards that matter — requires a
     * selection, off the main thread, and re-check the field before committing
     * — exist once. A second copy is how one of them ends up missing the
     * staleness check and pastes into the wrong app.
     */
    private fun aiTransform(
        ic: InputConnection,
        task: com.rimboard.keyboard.net.AiText.Task,
        target: String?,
        busyRes: Int
    ) {
        val selected = ic.getSelectedText(0)?.toString()
        if (selected.isNullOrBlank()) {
            toast(getString(R.string.ai_needs_selection))
            return
        }
        toast(getString(busyRes))
        // Off the main thread: this is a network round trip, and the IME's main
        // thread is the one drawing the keyboard the user is still typing on.
        Thread {
            val result = com.rimboard.keyboard.net.AiText.run(this, task, selected, target)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                result.fold(
                    onSuccess = { text ->
                        // Re-fetched rather than captured: the request took a
                        // second or two, and the field the reply belongs to may
                        // not be the focused one any more. Committing into
                        // whatever is focused now would paste a translation
                        // into an unrelated app.
                        val live = currentInputConnection
                        if (live == null || live.getSelectedText(0)?.toString() != selected) {
                            toast(getString(R.string.ai_moved_on))
                        } else {
                            live.commitText(text, 1)
                            // Replacing a whole selection with model output is
                            // the most destructive thing this keyboard does,
                            // and it was irreversible. The strip already has a
                            // revert chip for autocorrect; this reuses it, so
                            // a translation that came back wrong costs one tap
                            // rather than the text.
                            revert = Revert(selected, text, "", learnable = false)
                            updateStrip()
                        }
                    },
                    onFailure = { e ->
                        toast(getString(R.string.ai_failed, netError(e)))
                    }
                )
            }
        }.start()
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** For messages that are instructions rather than status. */
    private fun toastLong(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
    }

    /**
     * Turns a network failure into something worth reading.
     *
     * A raw `SocketTimeoutException` tells the user nothing they can act on,
     * and the single most common cause is simply having no signal — which is
     * not a fault in the keyboard, the API key, or the service.
     */
    /**
     * What to show the user when a request fails.
     *
     * A status line is not a stack trace. The provider's own error body used to
     * arrive here as the message and go straight onto a two-line strip, so a
     * failing service produced a clipped fragment of someone else's JSON and
     * the user was left to work out whether they had done something wrong. What
     * they need to know is only ever one of three things: their phone is
     * offline, the service is broken and it is worth trying again, or the
     * request itself will not be accepted however many times it is repeated.
     * The detail stays on the exception for the log.
     */
    private fun netError(e: Throwable?): String {
        if (com.rimboard.keyboard.net.Net.deviceOnline(this) == false) {
            return getString(R.string.net_device_offline)
        }
        val http = e as? com.rimboard.keyboard.net.HttpStatusException
        if (http != null) {
            android.util.Log.w("RimBoard", "request failed: ${http.message}")
            return when {
                http.isRateLimited -> getString(R.string.net_error_rate_limited)
                http.isServerFault -> getString(R.string.net_error_server, http.code)
                else -> getString(R.string.net_error_request, http.code)
            }
        }
        return e?.message ?: getString(R.string.net_unknown_error)
    }

    // ---- GIF search -------------------------------------------------------

    /**
     * Opens the GIF panel, refusing with a specific reason rather than a
     * generic failure.
     *
     * Four different things can make this unavailable and they have four
     * different fixes — wrong build, network off, incognito, no key. A single
     * "GIFs unavailable" would leave the user with no idea which.
     */
    private fun showGifPanel() {
        val gv = gifView ?: return
        com.rimboard.keyboard.net.Net.blockedBy(this, sendsTypedText = true)?.let { block ->
            toast(getString(
                if (block == com.rimboard.keyboard.net.Net.Block.INCOGNITO)
                    R.string.gif_incognito else netBlockMessage(block)
            ))
            return
        }
        if (!com.rimboard.keyboard.net.ApiKeys.unlocked(this)) {
            toast(getString(R.string.net_locked))
            return
        }
        if (com.rimboard.keyboard.net.ApiKeys.klipy(this) == null) {
            toast(getString(R.string.gif_no_key))
            return
        }
        // Deliberately no check that the field accepts images. Only apps that
        // opt into rich content declare it, so refusing to open here meant the
        // picker was unavailable in most places — including this app's own
        // setup screen. GifInsert falls back to the clipboard instead.
        finishComposingSilently()
        openSearchHost(gv, SearchRoute.GIF, withKeyboard = true)
        // Seed from whatever the user already typed, as though it had been
        // typed on the panel's own keypad — so it can be edited rather than
        // only accepted or cleared.
        val seed = textBeforeCursorSeed()
        gifQueryFromField = seed?.query
        gifQueryFieldLength = seed?.rawLength ?: 0
        gv.startWith(seed?.query)
        seed?.let { runGifSearch(it.query) }
    }

    /**
     * The last few words before the cursor, as a search seed.
     *
     * Returns the query *and* how many characters it actually occupies in the
     * field, which are not the same number: the query is trimmed and has its
     * whitespace collapsed for searching, so using its length to delete from
     * the field leaves the difference behind. "hey  there  cat" typed with
     * double spaces normalises to 13 characters over a 15-character span.
     */
    private fun textBeforeCursorSeed(): FieldSeed? =
        seedFromTextBeforeCursor(currentInputConnection?.getTextBeforeCursor(60, 0)?.toString())

    override fun onGifSearch(query: String) {
        // The user edited the query on the panel's keypad, so it no longer
        // matches what is in the field — picking a result must not delete
        // text the search is no longer based on.
        if (query != gifQueryFromField) {
            gifQueryFromField = null
            gifQueryFieldLength = 0
        }
        runGifSearch(query)
    }

    private fun runGifSearch(query: String) {
        val gv = gifView ?: return
        gv.setStatus(getString(R.string.gif_searching))
        // Supersedes any thumbnails still in flight for the previous query.
        val generation = ++thumbGeneration
        thumbPool.queue.clear()
        Thread {
            val result = com.rimboard.keyboard.net.Klipy.search(this, query)
            main {
                // The generation guarded the thumbnails but not the result set
                // they belong to, so a slow search returning after a later one
                // replaced the newer grid with its own — and then every one of
                // its thumbnails was dropped by the check in loadThumb, because
                // by then the generation really had moved on. The visible
                // outcome was the wrong results, permanently blank.
                if (generation != thumbGeneration) return@main
                result.fold(
                    onSuccess = { gifs ->
                        gv.setStatus(if (gifs.isEmpty()) getString(R.string.gif_none) else null)
                        gv.setResults(gifs)
                        gifs.forEach { loadThumb(it, generation) }
                    },
                    onFailure = { gv.setStatus(getString(R.string.gif_failed, netError(it))) }
                )
            }
        }.start()
    }

    /**
     * One thread per thumbnail, deliberately fire-and-forget: a stale result
     * for a search the user has moved on from is dropped by [GifView] because
     * its id is no longer in the list, so there is nothing to cancel.
     */
    /**
     * Downloads thumbnails a few at a time.
     *
     * This used to be one thread per tile, so a search fired twenty-four
     * concurrent downloads and twenty-four decodes at once — on a mid-range
     * phone that is visible jank in the keyboard the user is still typing on,
     * and it is more sockets than the connection can usefully serve anyway.
     * Four at a time keeps the grid filling steadily without the stampede.
     */
    private val thumbPool: java.util.concurrent.ThreadPoolExecutor =
        java.util.concurrent.ThreadPoolExecutor(
            0, 4, 20L, java.util.concurrent.TimeUnit.SECONDS,
            java.util.concurrent.LinkedBlockingQueue()
        ).apply {
            // Nothing is queued while the panel is closed, so letting the
            // threads die back keeps an idle keyboard at zero of them.
            allowCoreThreadTimeOut(true)
        }

    /**
     * Rises with each search. A download that finishes after the user has
     * typed on and triggered a new search belongs to a grid that no longer
     * exists, so it is dropped rather than decoded and posted.
     */
    private var thumbGeneration = 0

    /**
     * The size a grid tile is actually drawn at, in pixels: two columns across
     * the display, and the fixed 92dp the tile is tall. An upper bound rather
     * than a measurement — the view may not be laid out when a download lands,
     * and erring large only costs one halving of the sampling.
     */
    private fun thumbTargetW(): Int = resources.displayMetrics.widthPixels / 2

    private fun thumbTargetH(): Int = (92 * resources.displayMetrics.density).toInt()

    private fun loadThumb(gif: com.rimboard.keyboard.net.Klipy.Gif, generation: Int) {
        thumbPool.execute {
            if (generation != thumbGeneration) return@execute
            val bytes = com.rimboard.keyboard.net.Net.fetchBytes(
                this, gif.previewUrl, reason = "GIF thumbnail", sendsTypedText = false
            ).getOrNull() ?: return@execute
            // Checked again after the download: it is the slow part, and
            // decoding a bitmap for a discarded search is the cost worth
            // avoiding.
            if (generation != thumbGeneration) return@execute
            // Decodes the first frame — a still is all a grid tile needs, and
            // animated decoding is API 28+ while this app supports 26.
            //
            // Sized to the tile rather than to whatever the provider sent. The
            // grid is two columns of 92dp tiles; the previews behind them are
            // routinely several times that in each direction, and every one was
            // being decoded at full resolution into ARGB_8888 and then held for
            // as long as the results were on screen. Nothing bounded that but
            // the provider's own choice of preview size.
            val bounds = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val opts = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = Thumbs.sampleSizeFor(
                    bounds.outWidth, bounds.outHeight, thumbTargetW(), thumbTargetH()
                )
            }
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                ?: return@execute
            main { if (generation == thumbGeneration) gifView?.setThumbnail(gif.id, bmp) }
        }
    }

    override fun onGifPicked(gif: com.rimboard.keyboard.net.Klipy.Gif) {
        val gv = gifView ?: return
        gv.setStatus(getString(R.string.gif_inserting))
        // Downloading a GIF is the longest wait in this keyboard — megabytes,
        // not a JSON reply — and it is also the only one of the three async
        // paths here that had no staleness guard. The thumbnails check
        // [thumbGeneration] and the AI reply re-checks the selection it was
        // asked about; this one re-fetched the input connection and stopped
        // there, which is not the same test. A live connection only says
        // *something* is focused, not that it is the field the user picked the
        // GIF for. Switching apps while it downloaded therefore dropped the GIF
        // into whatever was focused when it landed.
        //
        // [closeSearchHost] already moves this generation on for exactly this
        // reason, and it runs both when the panel is closed and, via
        // closeAnyPanel, when the editor changes — so it is the signal that was
        // there all along and only this path failed to read.
        val generation = thumbGeneration
        Thread {
            val bytes = com.rimboard.keyboard.net.Klipy.download(this, gif)
            main {
                if (generation != thumbGeneration) return@main
                val data = bytes.getOrNull()
                val ic = currentInputConnection
                val editor = currentInputEditorInfo
                if (data == null || ic == null || editor == null) {
                    gv.setStatus(getString(R.string.gif_failed, netError(bytes.exceptionOrNull())))
                    return@main
                }
                // Remove the words that seeded the search — they were the query,
                // not part of the message. Only when the field supplied them.
                if (gifQueryFromField != null && gifQueryFieldLength > 0) {
                    ic.deleteSurroundingText(gifQueryFieldLength, 0)
                }
                when (com.rimboard.keyboard.net.GifInsert.commit(
                    this, ic, editor, data, gif.description
                )) {
                    com.rimboard.keyboard.net.GifInsert.Result.INSERTED -> {
                        gv.setStatus(null)
                        onGifAbc()
                    }
                    // The app would not take it directly, so it is on the
                    // clipboard. Close the panel and say so: leaving it open
                    // over the field they now need to paste into would hide
                    // the thing the message is telling them to do.
                    com.rimboard.keyboard.net.GifInsert.Result.COPIED -> {
                        gv.setStatus(null)
                        onGifAbc()
                        toast(getString(R.string.gif_copied))
                    }
                    com.rimboard.keyboard.net.GifInsert.Result.FAILED ->
                        gv.setStatus(getString(R.string.gif_insert_failed))
                }
            }
        }.start()
    }

    /**
     * Shows [panel] above the keyboard rather than in place of it.
     *
     * Height is a share of the keyboard's rather than a constant: the picker
     * and the keyboard are stacked now, so a fixed panel height would push the
     * whole input view past what the system will give it on a short screen,
     * and the keys would be the part that got cut.
     */
    /**
     * Sends a keystroke to the open picker's search box.
     *
     * Returns false for keys the picker has no use for — layout switches,
     * settings, the language cycle — which then behave normally. Enter closes
     * the picker rather than sending a newline into the field behind it, since
     * "done searching" is the only thing it could reasonably mean here.
     */
    /**
     * Where a keystroke came from. Only [REPEAT] behaves differently — a held
     * key that the picker has no use for is swallowed rather than passed on,
     * because a repeat firing into the field behind an open picker is the
     * accident this whole seam exists to prevent.
     */
    private enum class Source { TAP, REPEAT, POPUP }

    /**
     * The single place a keystroke is offered to an open picker's search box.
     *
     * The keyboard produces input at four entry points — tap, repeat,
     * long-press popup and glide — and each one previously needed this check
     * bolted on by hand. Three of them were missed, which is how a *held*
     * backspace came to delete the user's actual message while a tapped one
     * edited the search query. Every one of them now asks here instead, so
     * adding a fifth entry point cannot quietly reintroduce it.
     *
     * Returns true when the keystroke has been dealt with and the caller must
     * do nothing further.
     */
    private fun consumedBySearch(key: Key, source: Source): Boolean {
        if (searchRoute == SearchRoute.NONE) return false
        if (routeKeyToSearch(key)) return true
        // Layout switches, shift and settings still work on the keyboard
        // itself, so a tap or popup falls through to normal handling.
        return source == Source.REPEAT
    }

    /** Appends one character to whichever search box is open. */
    private fun routeCharToSearch(c: Char) {
        when (searchRoute) {
            SearchRoute.GIF -> gifView?.appendQuery(c)
            SearchRoute.EMOJI -> emojiView?.appendQuery(c)
            SearchRoute.TRANSLATE -> translateView?.appendQuery(c)
            SearchRoute.NONE -> {}
        }
    }

    private fun routeKeyToSearch(key: Key): Boolean {
        val append: (Char) -> Unit
        val back: () -> Unit
        when (searchRoute) {
            SearchRoute.GIF -> {
                val gv = gifView ?: return false
                append = gv::appendQuery; back = gv::backspaceQuery
            }
            SearchRoute.EMOJI -> {
                val ev = emojiView ?: return false
                append = ev::appendQuery; back = ev::handleBackspace
            }
            SearchRoute.TRANSLATE -> {
                val tv = translateView ?: return false
                append = tv::appendQuery; back = tv::backspaceQuery
            }
            SearchRoute.NONE -> return false
        }
        return when (key.code) {
            Codes.BACKSPACE -> { back(); true }
            Codes.SPACE -> { append(' '); true }
            // Enter commits the translation rather than discarding it. On the
            // other pickers there is nothing to commit, so it just closes.
            Codes.ENTER -> {
                if (searchRoute == SearchRoute.TRANSLATE) translateView?.flush()
                else closeSearchHost()
                true
            }
            // Shift and the symbol pages still work on the keyboard itself, so
            // they fall through rather than being swallowed.
            else -> if (key.code > 0) {
                // Through the same case transform the message field gets. The
                // key label is always lower case, so without this the shift and
                // caps-lock keys visibly changed the keyboard and then had no
                // effect whatsoever on what was typed into a search or translate
                // box — you could not type a capital in either.
                applyShift(key.label).forEach { append(it) }
                consumeAutoShift()
                true
            } else false
        }
    }

    private fun openSearchHost(
        panel: View,
        route: SearchRoute,
        withKeyboard: Boolean,
        compact: Boolean = false
    ) {
        // The translate bar lives above the strip and the pickers below it, so
        // which container is opened follows the panel rather than the route.
        val host = (if (panel === translateView) barHost else searchHost) ?: return
        val other = if (host === barHost) searchHost else barHost
        val kbH = keyboardView?.measureKeyboardHeight() ?: return
        val lp = host.layoutParams
        // Browsing gets the whole keyboard's height because the keys are
        // hidden underneath it; searching gets a share, because they are not.
        // Sized against what is left after the picker's own header, footer and
        // attribution rather than against the keyboard alone: at 0.72 the grid
        // was down to less than a single row of tiles once the close bar was
        // added, so the picker was mostly chrome.
        lp.height = when {
            // The translate bar shows a language pair and one line of source;
            // the translation itself goes into the message field, so it needs
            // nothing like a picker's height.
            compact -> dp(84)
            withKeyboard -> (kbH * 0.95f).toInt().coerceIn(dp(210), dp(320))
            else -> kbH
        }
        host.layoutParams = lp
        // The reverse of the guard in revealPanel: a picker replaces any panel.
        for (other in arrayOf(clipboardView, editPanelView, toolbarPanelHost)) {
            other?.visibility = View.GONE
        }
        for (i in 0 until host.childCount) {
            host.getChildAt(i).visibility =
                if (host.getChildAt(i) === panel) View.VISIBLE else View.GONE
        }
        // Switching straight from one picker to the other must not leave the
        // first one's pending search or its field-seed bookkeeping behind.
        gifView?.cancelPending()
        if (panel !== gifView) {
            gifQueryFromField = null
            gifQueryFieldLength = 0
        }
        host.visibility = View.VISIBLE
        // Opening the bar must collapse the picker container and vice versa, or
        // the one left behind keeps its height and leaves a gap.
        other?.let { collapse(it) }
        keyboardView?.visibility = if (withKeyboard) View.VISIBLE else View.GONE
        searchRoute = route
        animatePanelIn(host)
    }

    private fun collapse(host: FrameLayout) {
        host.visibility = View.GONE
        host.layoutParams = host.layoutParams.apply { height = 0 }
    }

    private fun closeSearchHost() {
        searchRoute = SearchRoute.NONE
        gifView?.cancelPending()
        translateView?.cancelPending()
        // cancelPending only drops the pending debounce; a request already on
        // the wire keeps going and cannot be recalled. Moving the generation on
        // is what stops its answer being typed into the field after the user
        // has closed the bar.
        translateGeneration++
        thumbGeneration++
        // The panel is closed, so its bitmaps are not being shown to anyone.
        // Holding them until it is next opened meant holding them for the life
        // of the process, since that is when the list was previously cleared.
        gifView?.releaseThumbnails()
        translateInserted = null
        translateLastSource = null
        translateLastAt = 0L
        translateCount = 0
        searchHost?.let { collapse(it) }
        barHost?.let { collapse(it) }
        keyboardView?.visibility = View.VISIBLE
        updateStrip()
    }

    override fun onGifAbc() {
        closeSearchHost()
        gifQueryFromField = null
        gifQueryFieldLength = 0
    }

    private fun main(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }

    /** Hands text to any installed translator via the system process-text
     *  action. RimBoard itself sends nothing anywhere. */
    /**
     * Hands the text to any installed translator. Returns whether that
     * actually happened, so the caller can explain the silence otherwise.
     *
     * Every exit here used to be silent — blank text, no installed handler, a
     * refused activity start — which is how the tool came to do nothing at all
     * with no indication why.
     */
    private fun launchExternalTranslate(ic: InputConnection): Boolean {
        val selected = ic.getSelectedText(0)?.toString()
        val text = if (!selected.isNullOrBlank()) {
            selected
        } else {
            val et = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
            et?.text?.toString() ?: ""
        }
        if (text.isBlank()) {
            toast(getString(R.string.ai_needs_selection))
            return true
        }
        val send = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, text.take(1000))
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        }
        // Needs the <queries> entry in the manifest to see anything at all on
        // API 30+, where package visibility is filtered by default. Without it
        // this returns empty even on a phone with Google Translate installed.
        if (packageManager.queryIntentActivities(send, 0).isEmpty()) return false
        return try {
            startActivity(Intent.createChooser(send, null)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) {
            false
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

/**
 * A GIF search seed taken from the field: the query to search for, and how many
 * characters it occupies in the field.
 *
 * The two are different numbers, which is the whole reason this exists. The
 * query is trimmed and has its whitespace collapsed so it reads as a search
 * term; the field holds whatever the user actually typed. Deleting
 * `query.length` characters after picking a GIF therefore left the difference
 * behind — "hey  there  cat" typed with double spaces normalises to 13
 * characters over a 15-character span, so two letters survived the deletion.
 */
internal class FieldSeed(val query: String, val rawLength: Int)

/**
 * Top-level and internal rather than a method on the service, purely so it can
 * be tested: the walk below is fiddly, its failure mode is silent, and it is
 * the kind of thing that regresses the next time someone changes how many
 * words the seed takes.
 */
internal fun seedFromTextBeforeCursor(raw: String?, maxWords: Int = 3): FieldSeed? {
    if (raw.isNullOrBlank()) return null
    var i = raw.length
    var words = 0
    while (i > 0 && words < maxWords) {
        // Trailing and inter-word whitespace belongs to the slice, so that a
        // seed ending in a space deletes that space too.
        while (i > 0 && raw[i - 1].isWhitespace()) i--
        if (i == 0) break
        val wordEnd = i
        while (i > 0 && !raw[i - 1].isWhitespace()) i--
        if (wordEnd > i) words++
    }
    val slice = raw.substring(i)
    val query = slice.trim().replace(Regex("\\s+"), " ")
    if (query.isEmpty()) return null
    return FieldSeed(query, slice.length)
}
