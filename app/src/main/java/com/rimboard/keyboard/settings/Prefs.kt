package com.rimboard.keyboard.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.rimboard.keyboard.model.Languages

object Prefs {
    const val KEY_THEME = "theme"
    const val KEY_HEIGHT = "height"
    const val KEY_NUMBER_ROW = "number_row"
    const val KEY_POPUP = "popup_preview"
    const val KEY_SOUND = "sound"
    const val KEY_HAPTIC = "haptic"
    const val KEY_AUTOCAPS = "autocaps"
    const val KEY_AUTOCORRECT = "autocorrect"
    const val KEY_AUTOCORRECT_CAUTIOUS = "autocorrect_cautious"
    const val KEY_INLINE_AUTOFILL = "inline_autofill"
    const val KEY_SUGGESTIONS = "suggestions"
    const val KEY_PREDICTIONS = "predictions"
    const val KEY_DOUBLE_SPACE = "double_space"
    const val KEY_GLIDE = "glide_typing"
    const val KEY_LEARN = "learn_words"
    const val KEY_CLIPBOARD = "clipboard_suggest"
    const val KEY_LANGUAGES = "languages"
    const val KEY_INCOGNITO_ALWAYS = "incognito_always"
    const val KEY_INCOGNITO_SESSION = "incognito_session"
    const val KEY_CURRENT_LANG = "current_lang"
    const val KEY_LANG_RECENCY = "lang_recency"
    const val KEY_EMOJI_RECENTS = "emoji_recents"
    const val KEY_PENDING_CLEAR = "pending_clear"
    const val KEY_PENDING_RELOAD = "pending_reload"
    const val KEY_ONE_HANDED = "one_handed"
    const val KEY_ONE_HANDED_LAST = "one_handed_last"
    const val KEY_UI_LANG = "interface_language"
    /** Legacy three-option list; read only to seed [KEY_CLIP_TIMEOUT_MIN]. */
    const val KEY_CLIP_TIMEOUT = "clip_timeout"
    const val KEY_CLIP_TIMEOUT_MIN = "clip_timeout_min"
    const val KEY_FLOATING = "floating_keyboard"
    const val KEY_REPEAT_SPEED = "key_repeat_speed"
    const val KEY_NR_PASS = "number_row_passwords"
    const val KEY_AUTOSPACE = "auto_space_punct"
    const val KEY_SOUND_VOL = "sound_volume"
    const val KEY_HAPTIC_STR = "haptic_strength"
    const val KEY_GLIDE_TRAIL = "glide_trail"
    const val KEY_GLIDE_DELETE = "glide_delete"
    /** Legacy light/medium/strong choice; read only to seed the slider. */
    const val KEY_BG_DIM = "bg_dim"
    const val KEY_BG_DIM_PCT = "bg_dim_pct"
    const val KEY_BG_LUMA = "bg_luma"
    const val KEY_KEY_BORDERS = "key_borders"
    const val KEY_NARROW_GAPS = "narrow_gaps"
    const val KEY_SPLIT = "split_mode"
    const val KEY_SIDE_PAD = "side_pad_pct"
    const val KEY_BOTTOM_PAD = "bottom_pad_pct"
    const val KEY_LABEL_PCT = "label_scale_pct"
    const val KEY_LP_MS = "long_press_ms"
    const val KEY_SPACE_H = "space_swipe_h"
    const val KEY_SPACE_V = "space_swipe_v"
    const val KEY_SPACE_LONG = "space_long_press"
    const val KEY_NUMPAD_LONG = "numpad_long_press"
    const val KEY_TLD = "tld_popups"
    const val KEY_LANG_PER_APP = "lang_per_app"
    const val KEY_THEME_PER_APP = "theme_per_app"
    const val KEY_APP_COLOR_SOURCE = "app_color_source"
    const val KEY_TINT_STRENGTH = "tint_strength"
    const val KEY_LIVE_BG = "live_background"
    const val KEY_MATCH_APP_MODE = "match_app_mode"
    const val KEY_SYM_RETURN = "symbols_return"
    const val KEY_EMOJI_RETURN = "emoji_return"
    const val KEY_CLIP_RETURN = "clip_return"
    const val KEY_CURRENCIES = "currencies"
    const val KEY_OFFENSIVE = "block_offensive"
    const val KEY_CONTACT_NAMES = "contact_names"
    const val KEY_SYSTEM_DICT = "system_dictionary"
    const val KEY_AS_SUGG = "autospace_suggestion"
    const val KEY_TOOLBAR = "toolbar_keys"
    const val KEY_PINNED_ORDER = "pinned_order"
    const val KEY_CALC = "calc_chip"
    const val KEY_SMART_TAP = "smart_tap"
    const val KEY_SPACE_TEXT = "space_text"
    const val KEY_CC_BG = "cc_bg"
    const val KEY_CC_KEY = "cc_key"
    const val KEY_CC_TEXT = "cc_text"
    const val KEY_CC_ACCENT = "cc_accent"
    const val KEY_FLOAT_X = "float_x"
    const val KEY_FLOAT_Y = "float_y"
    /**
     * "offline" or "online". Read through `Net.mode`, never directly: on the
     * offline build the answer is fixed regardless of what is stored here, and
     * a caller reading the string itself would miss that.
     */
    const val KEY_NET_MODE = "net_mode"
    const val KEY_NET_SENT = "net_sent_count"

    /**
     * ISO code 🌍 translates into, or "auto" to follow the keyboard language.
     * Read through `TranslateTargets`, which resolves "auto" and names it.
     */
    const val KEY_TRANSLATE_TARGET = "translate_target"

    /**
     * Which translation service 🌍 uses, or "auto" to pick the best available.
     * Read through `Translate.effective`, which resolves "auto" and drops back
     * to a usable source when the chosen one has no key.
     */
    const val KEY_TRANSLATE_SOURCE = "translate_source"

    /**
     * Optional self-hosted instance for the chosen service, as a bare hostname.
     *
     * This is the one host the network allowlist cannot be static about, and
     * `Net.hostAllowed` consults it deliberately: the point of self-hosting is
     * that the address is yours and nobody could have listed it in advance.
     * Empty unless the user typed one in.
     */
    const val KEY_TRANSLATE_HOST = "translate_host"

    @Volatile
    private var cached: SharedPreferences? = null

    /**
     * Preferences live in device-protected storage so the keyboard can run
     * on the lock screen right after a reboot (direct boot), before the
     * user's first unlock. Existing prefs are migrated once.
     */
    fun get(context: Context): SharedPreferences {
        cached?.let { return it }
        val dp = context.createDeviceProtectedStorageContext()
        dp.moveSharedPreferencesFrom(context, context.packageName + "_preferences")
        return PreferenceManager.getDefaultSharedPreferences(dp).also { cached = it }
    }

    fun theme(c: Context): String = get(c).getString(KEY_THEME, "system") ?: "system"

    fun heightFactor(c: Context): Float =
        (get(c).getString(KEY_HEIGHT, "1.0") ?: "1.0").toFloatOrNull() ?: 1f

    fun numberRow(c: Context) = get(c).getBoolean(KEY_NUMBER_ROW, false)
    fun popupPreview(c: Context) = get(c).getBoolean(KEY_POPUP, true)
    fun sound(c: Context) = get(c).getBoolean(KEY_SOUND, false)
    fun haptic(c: Context) = get(c).getBoolean(KEY_HAPTIC, true)
    fun autocaps(c: Context) = get(c).getBoolean(KEY_AUTOCAPS, true)
    fun autocorrect(c: Context) = get(c).getBoolean(KEY_AUTOCORRECT, true)

    /**
     * Whether autocorrect holds to the stricter bar. Off by default, because
     * the default is the measured point where the gate costs nothing.
     */
    fun cautiousAutocorrect(c: Context) =
        get(c).getBoolean(KEY_AUTOCORRECT_CAUTIOUS, false)

    /**
     * Whether the strip offers what a password manager wants to fill.
     *
     * On by default. It costs nothing when no autofill service is configured —
     * the system simply never sends a response — and turning it off is for
     * people who would rather the keyboard never carried anything from another
     * app, which is a reasonable thing to want from this keyboard in
     * particular.
     */
    fun inlineAutofill(c: Context) = get(c).getBoolean(KEY_INLINE_AUTOFILL, true)
    fun suggestions(c: Context) = get(c).getBoolean(KEY_SUGGESTIONS, true)
    fun predictions(c: Context) = get(c).getBoolean(KEY_PREDICTIONS, true)
    fun doubleSpace(c: Context) = get(c).getBoolean(KEY_DOUBLE_SPACE, true)
    fun glide(c: Context) = get(c).getBoolean(KEY_GLIDE, true)

    /** 0 = off, 1 = anchored left, 2 = anchored right. */
    fun oneHanded(c: Context) = get(c).getInt(KEY_ONE_HANDED, 0)
    fun setOneHanded(c: Context, v: Int) {
        get(c).edit().putInt(KEY_ONE_HANDED, v).apply()
    }

    fun uiLanguage(c: Context): String =
        get(c).getString(KEY_UI_LANG, "system") ?: "system"

    fun setUiLanguage(c: Context, v: String) {
        get(c).edit().putString(KEY_UI_LANG, v).apply()
    }


    fun floating(c: Context) = get(c).getBoolean(KEY_FLOATING, false)

    fun numberRowPasswords(c: Context) = get(c).getBoolean(KEY_NR_PASS, true)
    fun autoSpacePunct(c: Context) = get(c).getBoolean(KEY_AUTOSPACE, false)
    fun soundVolume(c: Context): String = get(c).getString(KEY_SOUND_VOL, "normal") ?: "normal"
    fun hapticStrength(c: Context): String = get(c).getString(KEY_HAPTIC_STR, "medium") ?: "medium"
    // Off by default: the idle strip stays clean unless the row is asked for.
    // Must match the defaultValue in prefs_general.xml or the switch shows one
    // state while the keyboard obeys the other.
    fun glideTrail(c: Context) = get(c).getBoolean(KEY_GLIDE_TRAIL, true)

    /**
     * Swipe left from backspace to delete whole words.
     *
     * The gesture has shipped since before there was a screen to turn it off
     * on, which is the wrong way round for something that deletes: anyone who
     * triggers it by accident had no way to stop it happening again.
     */
    fun glideDelete(c: Context) = get(c).getBoolean(KEY_GLIDE_DELETE, true)
    /**
     * Dim overlay for the background image, as a draw alpha (0..230).
     *
     * The setting is a 0..100 slider now; the old light/medium/strong choice
     * seeds the slider's starting point for anyone who had picked one, so an
     * upgrade keeps the darkness they chose. Capped below 255 so full slider
     * never paints the photo out entirely.
     */
    fun bgDimAlpha(c: Context): Int = bgDimPct(c) * 230 / 100

    fun bgDimPct(c: Context): Int {
        val p = get(c)
        if (p.contains(KEY_BG_DIM_PCT)) return p.getInt(KEY_BG_DIM_PCT, 48).coerceIn(0, 100)
        return when (p.getString(KEY_BG_DIM, "medium")) {
            "light" -> 26
            "strong" -> 72
            else -> 48
        }
    }

    fun setBgDimPct(c: Context, v: Int) {
        get(c).edit().putInt(KEY_BG_DIM_PCT, v.coerceIn(0, 100)).apply()
    }

    /**
     * Mean luminance (0..255) of the saved background image, computed once
     * when it is picked. What decides whether keys over the photo scrim dark
     * with light lettering or the reverse.
     */
    fun bgLuma(c: Context): Int = get(c).getInt(KEY_BG_LUMA, 96)

    fun setBgLuma(c: Context, v: Int) {
        get(c).edit().putInt(KEY_BG_LUMA, v.coerceIn(0, 255)).apply()
    }

    /** Off means the flat style: bare letter glyphs, caps only on other keys. */
    fun keyBorders(c: Context) = get(c).getBoolean(KEY_KEY_BORDERS, false)
    fun narrowGaps(c: Context) = get(c).getBoolean(KEY_NARROW_GAPS, false)
    fun splitMode(c: Context): String = get(c).getString(KEY_SPLIT, "off") ?: "off"
    fun sidePadPct(c: Context) = get(c).getInt(KEY_SIDE_PAD, 0)
    fun bottomPadPct(c: Context) = get(c).getInt(KEY_BOTTOM_PAD, 0)
    fun labelScalePct(c: Context) = get(c).getInt(KEY_LABEL_PCT, 100)
    fun longPressMs(c: Context) = get(c).getInt(KEY_LP_MS, 300)
    fun spaceSwipeH(c: Context): String = get(c).getString(KEY_SPACE_H, "cursor") ?: "cursor"
    fun spaceSwipeV(c: Context): String = get(c).getString(KEY_SPACE_V, "none") ?: "none"
    fun spaceLongPress(c: Context): String = get(c).getString(KEY_SPACE_LONG, "language") ?: "language"
    fun numpadLongPress(c: Context) = get(c).getBoolean(KEY_NUMPAD_LONG, false)
    fun tldPopupsOn(c: Context) = get(c).getBoolean(KEY_TLD, true)
    fun langPerApp(c: Context) = get(c).getBoolean(KEY_LANG_PER_APP, false)

    /**
     * On by default, unlike [langPerApp]: this one only shifts the accent of
     * whichever theme is already chosen, so the worst case is a colour the
     * user did not ask for, whereas a remembered *language* silently changes
     * what typing produces.
     */
    fun themePerApp(c: Context) = get(c).getBoolean(KEY_THEME_PER_APP, true)

    /**
     * Which apps the keyboard may read a colour and a polarity from: `all`
     * (the default) or `curated`, a fixed list of about forty well-known apps.
     *
     * It started at the narrower one, reasoning that widening what the
     * keyboard looks at is a choice and a default is not a choice the user
     * made. What that missed is that both features it governs are themselves
     * on by default and describe themselves without qualification. "A dark app
     * gets a dark keyboard" was untrue in the forty-first app, and a feature
     * that is silently inert in most places is reported as broken rather than
     * as restrained — which is exactly how it was reported.
     *
     * So the honest pairing is the wide default with the narrow setting kept
     * and the cost written down where it can be checked: the `<queries>` block
     * in the manifest, and the README section that explains it. Reading is the
     * whole of what happens either way, and on the offline build nothing read
     * can leave the phone at all.
     */
    fun appColorSource(c: Context): String =
        get(c).getString(KEY_APP_COLOR_SOURCE, "all") ?: "all"

    fun curatedColorsOnly(c: Context) = appColorSource(c) != "all"

    /**
     * Whether a light or dark keyboard follows the app rather than the system.
     *
     * On by default. Matching the app you are looking at is what someone means
     * by "the keyboard should fit in", and unlike the accent tint it changes
     * nothing a user picked deliberately: it applies only to the two themes
     * that were already following something, and is ignored by every theme
     * chosen outright.
     */
    fun matchAppMode(c: Context) = get(c).getBoolean(KEY_MATCH_APP_MODE, true)

    /**
     * How saturated the tinted surfaces become.
     *
     * Defaults to medium rather than subtle because subtle is where this
     * started, at a strength low enough that the feature was indistinguishable
     * from being switched off — which is how it was reported.
     */
    /**
     * The animated background: `none` or `stars`.
     *
     * `none` by default. It is the only thing in this app that draws while
     * nobody is touching anything, and a keyboard is on screen for hours a
     * day — an always-on animation is a battery cost to opt into, not one to
     * discover.
     */
    fun liveBackground(c: Context): String =
        get(c).getString(KEY_LIVE_BG, "none") ?: "none"

    fun tintStrength(c: Context): Float =
        when (get(c).getString(KEY_TINT_STRENGTH, "medium")) {
            "subtle" -> 0.04f
            "strong" -> 0.16f
            else -> 0.09f
        }
    fun symbolsReturn(c: Context) = get(c).getBoolean(KEY_SYM_RETURN, true)
    fun emojiReturn(c: Context) = get(c).getBoolean(KEY_EMOJI_RETURN, false)
    fun clipReturn(c: Context) = get(c).getBoolean(KEY_CLIP_RETURN, false)
    fun currencies(c: Context): String {
        val v = get(c).getString(KEY_CURRENCIES, "") ?: ""
        return if (v.length >= 2) v.take(6) else "\u0024\u20BA\u20AC\u00A3\u00A5"
    }
    fun blockOffensive(c: Context) = get(c).getBoolean(KEY_OFFENSIVE, true)

    /**
     * Whether names from the address book count as spelled correctly.
     *
     * Off, unlike almost everything else here. A default that reads a
     * permission-gated source is a decision made on the user's behalf about
     * their address book, and the one thing an on-by-default switch cannot do
     * is be a choice. Turning it on is what triggers the permission prompt.
     */
    fun contactNames(c: Context) = get(c).getBoolean(KEY_CONTACT_NAMES, false)

    /** Used when the permission prompt is refused, so the switch cannot lie. */
    fun setContactNames(c: Context, v: Boolean) {
        get(c).edit().putBoolean(KEY_CONTACT_NAMES, v).apply()
    }

    /**
     * Whether the words in Android's own personal dictionary count as spelled
     * correctly. Off by default, for the same reason as [contactNames]: a
     * default that reads a permission-gated source is not a choice.
     */
    fun systemDictionary(c: Context) = get(c).getBoolean(KEY_SYSTEM_DICT, false)

    fun setSystemDictionary(c: Context, v: Boolean) {
        get(c).edit().putBoolean(KEY_SYSTEM_DICT, v).apply()
    }
    fun autoSpaceSuggestion(c: Context) = get(c).getBoolean(KEY_AS_SUGG, true)
    fun toolbarKeys(c: Context): Set<String> =
        get(c).getStringSet(KEY_TOOLBAR, emptySet()) ?: emptySet()

    /**
     * Tools pinned to the idle strip, in the order the user arranged them.
     *
     * Falls back to the old unordered checkbox selection (in catalog order) so
     * anyone upgrading keeps exactly the shortcuts they already had.
     */
    fun pinnedTools(c: Context): List<String> {
        val p = get(c)
        // Presence of the key, not emptiness of its value: unpinning everything
        // is a choice, and must not read as "never configured" and resurrect
        // the old checkbox selection.
        if (p.contains(KEY_PINNED_ORDER)) {
            return (p.getString(KEY_PINNED_ORDER, "") ?: "")
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }
        val legacy = toolbarKeys(c)
        if (legacy.isNotEmpty()) {
            return com.rimboard.keyboard.ui.ToolCatalog.defaultOrder.filter { it in legacy }
        }
        // Never configured: the strip has no fixed settings or clipboard button
        // any more, so an empty drawer would leave a fresh install with no way
        // to reach either, or to reach the panel that configures them.
        return DEFAULT_PINNED
    }

    val DEFAULT_PINNED = listOf("toolbar", "settings", "clipboard", "emoji", "onehanded")

    fun setPinnedTools(c: Context, ids: List<String>) {
        get(c).edit()
            .putString(KEY_PINNED_ORDER, ids.joinToString(","))
            // Kept in step so nothing still reading the old set goes stale.
            .putStringSet(KEY_TOOLBAR, ids.toSet())
            .apply()
    }

    fun calcChip(c: Context) = get(c).getBoolean(KEY_CALC, true)

    fun smartTap(c: Context) = get(c).getBoolean(KEY_SMART_TAP, true)

    fun spaceText(c: Context): String = get(c).getString(KEY_SPACE_TEXT, "") ?: ""

    fun appLang(c: Context, pkg: String?): String? {
        if (pkg == null) return null
        return try {
            org.json.JSONObject(get(c).getString("app_langs", "{}") ?: "{}")
                .optString(pkg).takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    fun setAppLang(c: Context, pkg: String, code: String) {
        try {
            val o = org.json.JSONObject(get(c).getString("app_langs", "{}") ?: "{}")
            o.put(pkg, code)
            get(c).edit().putString("app_langs", o.toString()).apply()
        } catch (_: Exception) {
        }
    }

    fun repeatSpeed(c: Context): String =
        get(c).getString(KEY_REPEAT_SPEED, "normal") ?: "normal"

    fun customColor(c: Context, key: String, def: Int): Int = get(c).getInt(key, def)

    /**
     * The three saveable custom themes.
     *
     * Slot 1 deliberately reuses the original unsuffixed keys, so whoever had
     * already built a custom theme finds it in the first slot rather than
     * losing it to a rename.
     */
    const val CUSTOM_SLOTS = 3

    fun slotKey(base: String, slot: Int): String =
        if (slot <= 1) base else "${base}_$slot"

    /** A slot counts as used once any of its four colours has been set. */
    fun customSlotUsed(c: Context, slot: Int): Boolean {
        val p = get(c)
        return CC_KEYS.any { p.contains(slotKey(it, slot)) }
    }

    fun clearCustomSlot(c: Context, slot: Int) {
        val e = get(c).edit()
        CC_KEYS.forEach { e.remove(slotKey(it, slot)) }
        e.apply()
    }

    val CC_KEYS = listOf(KEY_CC_BG, KEY_CC_KEY, KEY_CC_TEXT, KEY_CC_ACCENT)

    /** Theme id for a slot, matching the values in `theme_values`. */
    fun customThemeId(slot: Int): String = if (slot <= 1) "custom" else "custom$slot"

    fun setCustomColor(c: Context, key: String, v: Int) {
        get(c).edit().putInt(key, v).apply()
    }

    fun setFloating(c: Context, on: Boolean) {
        get(c).edit().putBoolean(KEY_FLOATING, on).apply()
    }

    fun floatX(c: Context) = get(c).getInt(KEY_FLOAT_X, Int.MAX_VALUE)

    fun floatY(c: Context) = get(c).getInt(KEY_FLOAT_Y, Int.MAX_VALUE)

    fun setFloatPos(c: Context, x: Int, y: Int) {
        get(c).edit().putInt(KEY_FLOAT_X, x).putInt(KEY_FLOAT_Y, y).apply()
    }

    /**
     * Minutes before the clipboard history self-clears; 0 means never.
     *
     * Reads the slider first and falls back to the old three-option list,
     * which stored the same number as a *String*. Anyone who had picked 15
     * minutes or an hour keeps that setting instead of being silently reset to
     * never — which on this preference means their clips stop expiring.
     */
    fun clipTimeoutMin(c: Context): Int {
        val p = get(c)
        if (p.contains(KEY_CLIP_TIMEOUT_MIN)) {
            return p.getInt(KEY_CLIP_TIMEOUT_MIN, 0).coerceIn(0, 120)
        }
        return (p.getString(KEY_CLIP_TIMEOUT, "0") ?: "0").toIntOrNull()?.coerceIn(0, 120) ?: 0
    }

    fun oneHandedLast(c: Context) = get(c).getInt(KEY_ONE_HANDED_LAST, 2)
    fun setOneHandedLast(c: Context, v: Int) {
        get(c).edit().putInt(KEY_ONE_HANDED_LAST, v).apply()
    }

    /** "off", "left" or "right". */
    fun learnWords(c: Context) = get(c).getBoolean(KEY_LEARN, true)
    fun clipboardSuggest(c: Context) = get(c).getBoolean(KEY_CLIPBOARD, true)

    fun languages(c: Context): List<String> {
        val set = get(c).getStringSet(KEY_LANGUAGES, null) ?: defaultLanguages()
        val ordered = Languages.codes.filter { set.contains(it) }
        return if (ordered.isEmpty()) listOf("en") else ordered
    }

    /** First run: enable the device language (if supported) alongside English. */
    private fun defaultLanguages(): Set<String> {
        val sys = java.util.Locale.getDefault().language
        return if (sys != "en" && Languages.codes.contains(sys)) setOf(sys, "en") else setOf("en")
    }

    /**
     * Which of [enabled] a field opens in, most specific answer first.
     *
     * [perApp] is the language this app was last left in and beats everything,
     * because that is the entire content of the language-per-app setting.
     * [saved] is the language last used anywhere, [systemLang] the device's own,
     * and the first enabled language is the last resort.
     *
     * The precedence is spelled out here rather than inline because getting it
     * wrong is invisible: the per-app choice used to be applied in
     * `onStartInputView` and then overwritten from [saved] a few microseconds
     * later, in the same pass, before any layout was drawn. The setting wrote
     * its preference faithfully on every switch and never once read it back, so
     * it did nothing at all and said nothing about it.
     */
    fun startupLang(
        enabled: List<String>,
        perApp: String?,
        saved: String?,
        systemLang: String
    ): String {
        if (perApp != null && perApp in enabled) return perApp
        if (saved != null && saved in enabled) return saved
        if (systemLang in enabled) return systemLang
        return enabled.firstOrNull() ?: "en"
    }

    /** Enabled languages, most recently switched to first. */
    fun langRecency(c: Context): List<String> =
        (get(c).getString(KEY_LANG_RECENCY, "") ?: "")
            .split(' ').filter { it.isNotBlank() }

    /**
     * Remember that the user just had [code] open.
     *
     * A no-op when [code] is already the most recent, which is what makes this
     * safe to call from the paths that *restore* a language rather than choose
     * one — every field that opens, in other words. Only an actual change
     * writes.
     */
    fun noteLangUsed(c: Context, code: String) {
        val current = langRecency(c)
        if (current.firstOrNull() == code || code !in Languages.codes) return
        get(c).edit()
            .putString(KEY_LANG_RECENCY, recencyWith(code, current).joinToString(" "))
            .apply()
    }

    /**
     * [noteLangUsed]'s rule without a `Context`, so it can be tested.
     *
     * Unknown codes are dropped rather than kept: a language can be removed
     * from the build, and a stale code sitting at the head of this list would
     * otherwise be chosen as a second language that has no dictionary.
     */
    fun recencyWith(code: String, current: List<String>): List<String> =
        if (code !in Languages.codes) current.filter { it in Languages.codes }
        else listOf(code) + current.filter { it != code && it in Languages.codes }

    /**
     * The second language to pair with [current]: the one the user most
     * recently had open, and only failing that the order [languages] returns.
     *
     * The engine holds one other language at a time — two dictionaries and two
     * prediction models is the memory budget — so with more than two enabled,
     * *which* one is a real decision. It used to be `languages().first { it !=
     * current }`, which is the order [Languages.all] is written in: a fixed
     * list, authored here, with no knowledge of the user.
     *
     * For anyone with three languages that quietly picked the wrong pair.
     * Enabling en, tr and de gives the pairs en+tr, tr+en and de+en, so German
     * and Turkish never meet however much the user writes both — and typing
     * German on the Turkish layout saved 2.9% of keystrokes where pairing them
     * saves 33.4%. The two languages you switch between are the two you want
     * blended, and switching between them is exactly the evidence for it.
     *
     * With two languages enabled this cannot differ from what it replaced:
     * there is only one other language to return, recency or no recency.
     */
    fun altLangFor(c: Context, current: String): String? =
        altLangFor(languages(c), langRecency(c), current)

    /**
     * [altLangFor]'s rule without a `Context`, so it can be tested.
     *
     * [recency] is consulted first and [enabled] is the fallback, which is what
     * makes this a strict improvement rather than a change: before any language
     * has been used the recency list is empty and the answer is exactly the old
     * one, and with two languages enabled the two orders cannot disagree.
     */
    fun altLangFor(enabled: List<String>, recency: List<String>, current: String): String? =
        recency.firstOrNull { it != current && it in enabled }
            ?: enabled.firstOrNull { it != current }

    fun incognitoAlways(c: Context) = get(c).getBoolean(KEY_INCOGNITO_ALWAYS, false)
    fun incognitoSession(c: Context) = get(c).getBoolean(KEY_INCOGNITO_SESSION, false)

    /**
     * Whether incognito is on, by preference alone.
     *
     * The two switches read as one thing to the user -- the settings toggle
     * and the per-session one on the comma popup -- and every caller that
     * asked wrote the `||` out again. Three copies, and the third was the
     * spell checker's, which never got written at all.
     *
     * The keyboard's own `isIncognito()` is this plus what it knows about the
     * focused field, which nothing outside the service can see.
     */
    fun incognitoOn(c: Context) = incognitoAlways(c) || incognitoSession(c)
    fun setIncognitoSession(c: Context, v: Boolean) {
        get(c).edit().putBoolean(KEY_INCOGNITO_SESSION, v).apply()
    }

    fun currentLang(c: Context): String? = get(c).getString(KEY_CURRENT_LANG, null)
    fun setCurrentLang(c: Context, v: String) {
        get(c).edit().putString(KEY_CURRENT_LANG, v).apply()
    }

    fun emojiRecents(c: Context): List<String> =
        (get(c).getString(KEY_EMOJI_RECENTS, "") ?: "").split(" ").filter { it.isNotBlank() }

    fun setEmojiRecents(c: Context, list: List<String>) {
        get(c).edit().putString(KEY_EMOJI_RECENTS, list.joinToString(" ")).apply()
    }

    fun pendingClear(c: Context) = get(c).getBoolean(KEY_PENDING_CLEAR, false)
    fun setPendingClear(c: Context, v: Boolean) {
        get(c).edit().putBoolean(KEY_PENDING_CLEAR, v).apply()
    }

    fun pendingReload(c: Context) = get(c).getBoolean(KEY_PENDING_RELOAD, false)
    fun setPendingReload(c: Context, v: Boolean) {
        get(c).edit().putBoolean(KEY_PENDING_RELOAD, v).apply()
    }
}
