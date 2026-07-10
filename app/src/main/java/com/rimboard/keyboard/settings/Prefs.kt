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
    const val KEY_SUGGESTIONS = "suggestions"
    const val KEY_PREDICTIONS = "predictions"
    const val KEY_DOUBLE_SPACE = "double_space"
    const val KEY_SPACE_CURSOR = "space_cursor"
    const val KEY_GLIDE = "glide_typing"
    const val KEY_LEARN = "learn_words"
    const val KEY_CLIPBOARD = "clipboard_suggest"
    const val KEY_LANGUAGES = "languages"
    const val KEY_INCOGNITO_ALWAYS = "incognito_always"
    const val KEY_INCOGNITO_SESSION = "incognito_session"
    const val KEY_CURRENT_LANG = "current_lang"
    const val KEY_EMOJI_RECENTS = "emoji_recents"
    const val KEY_PENDING_CLEAR = "pending_clear"
    const val KEY_PENDING_RELOAD = "pending_reload"
    const val KEY_ONE_HANDED = "one_handed"
    const val KEY_ONE_HANDED_LAST = "one_handed_last"
    const val KEY_UI_LANG = "interface_language"
    const val KEY_CLIP_TIMEOUT = "clip_timeout"
    const val KEY_FLOATING = "floating_keyboard"
    const val KEY_REPEAT_SPEED = "key_repeat_speed"
    const val KEY_NR_PASS = "number_row_passwords"
    const val KEY_AUTOSPACE = "auto_space_punct"
    const val KEY_LONGPRESS = "long_press_delay"
    const val KEY_LABEL_SIZE = "label_size"
    const val KEY_SOUND_VOL = "sound_volume"
    const val KEY_HAPTIC_STR = "haptic_strength"
    const val KEY_EMOJI_ROW = "emoji_row"
    const val KEY_GLIDE_TRAIL = "glide_trail"
    const val KEY_BG_DIM = "bg_dim"
    const val KEY_CC_BG = "cc_bg"
    const val KEY_CC_KEY = "cc_key"
    const val KEY_CC_TEXT = "cc_text"
    const val KEY_CC_ACCENT = "cc_accent"
    const val KEY_FLOAT_X = "float_x"
    const val KEY_FLOAT_Y = "float_y"

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
    fun suggestions(c: Context) = get(c).getBoolean(KEY_SUGGESTIONS, true)
    fun predictions(c: Context) = get(c).getBoolean(KEY_PREDICTIONS, true)
    fun doubleSpace(c: Context) = get(c).getBoolean(KEY_DOUBLE_SPACE, true)
    fun spaceCursor(c: Context) = get(c).getBoolean(KEY_SPACE_CURSOR, true)
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
    fun longPressDelay(c: Context): String = get(c).getString(KEY_LONGPRESS, "normal") ?: "normal"
    fun labelSize(c: Context): String = get(c).getString(KEY_LABEL_SIZE, "normal") ?: "normal"
    fun soundVolume(c: Context): String = get(c).getString(KEY_SOUND_VOL, "normal") ?: "normal"
    fun hapticStrength(c: Context): String = get(c).getString(KEY_HAPTIC_STR, "medium") ?: "medium"
    fun emojiRow(c: Context) = get(c).getBoolean(KEY_EMOJI_ROW, true)
    fun glideTrail(c: Context) = get(c).getBoolean(KEY_GLIDE_TRAIL, true)
    fun bgDim(c: Context): String = get(c).getString(KEY_BG_DIM, "medium") ?: "medium"

    fun repeatSpeed(c: Context): String =
        get(c).getString(KEY_REPEAT_SPEED, "normal") ?: "normal"

    fun customColor(c: Context, key: String, def: Int): Int = get(c).getInt(key, def)

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

    fun clipTimeoutMin(c: Context): Int =
        (get(c).getString(KEY_CLIP_TIMEOUT, "0") ?: "0").toIntOrNull() ?: 0

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

    fun incognitoAlways(c: Context) = get(c).getBoolean(KEY_INCOGNITO_ALWAYS, false)
    fun incognitoSession(c: Context) = get(c).getBoolean(KEY_INCOGNITO_SESSION, false)
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
