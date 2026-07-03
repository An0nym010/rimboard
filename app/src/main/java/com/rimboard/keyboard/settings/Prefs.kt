package com.rimboard.keyboard.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

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
    const val KEY_LEARN = "learn_words"
    const val KEY_CLIPBOARD = "clipboard_suggest"
    const val KEY_LANGUAGES = "languages"
    const val KEY_INCOGNITO_ALWAYS = "incognito_always"
    const val KEY_INCOGNITO_SESSION = "incognito_session"
    const val KEY_CURRENT_LANG = "current_lang"
    const val KEY_EMOJI_RECENTS = "emoji_recents"
    const val KEY_PENDING_CLEAR = "pending_clear"

    fun get(context: Context): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context)

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
    fun learnWords(c: Context) = get(c).getBoolean(KEY_LEARN, true)
    fun clipboardSuggest(c: Context) = get(c).getBoolean(KEY_CLIPBOARD, true)

    fun languages(c: Context): List<String> {
        val set = get(c).getStringSet(KEY_LANGUAGES, null) ?: setOf("en", "tr")
        val ordered = listOf("en", "tr").filter { set.contains(it) }
        return if (ordered.isEmpty()) listOf("en") else ordered
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
}
