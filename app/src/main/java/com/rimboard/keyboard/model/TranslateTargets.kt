package com.rimboard.keyboard.model

import android.content.Context
import com.rimboard.keyboard.settings.Prefs
import java.util.Locale

/**
 * What 🌍 translates *into*.
 *
 * This was tied to the active keyboard layout, which conflated two unrelated
 * things. Wanting Turkish output does not mean wanting a Turkish keyboard: to
 * translate a message into Turkish you had to enable the Turkish layout and
 * switch to it first, and then switch back to carry on typing. The 22 bundled
 * layouts were also a hard ceiling on the target, even though the model behind
 * the feature handles far more languages than this app ships dictionaries for.
 *
 * So the target is its own setting, and the list comes from the platform's own
 * ISO language table rather than from a list maintained here — which means the
 * names are already translated into whatever the user reads the UI in, and the
 * set does not go stale.
 */
object TranslateTargets {

    /**
     * Follow whichever keyboard language is active. The default, because it is
     * what the feature did before this existed and it is right for anyone who
     * switches layouts to write in another language anyway.
     */
    const val AUTO = "auto"

    /**
     * The English name of the language to translate into, for the prompt.
     *
     * English specifically: the instruction the model receives is in English,
     * and "translate into Deutsch" is a sentence mixing two languages for no
     * reason. The user never sees this string.
     */
    fun promptName(c: Context, keyboardLang: String): String {
        val code = stored(c).takeIf { it != AUTO } ?: keyboardLang
        return Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH)
            .ifBlank { "English" }
    }

    fun stored(c: Context): String =
        Prefs.get(c).getString(Prefs.KEY_TRANSLATE_TARGET, AUTO) ?: AUTO

    fun store(c: Context, code: String) {
        Prefs.get(c).edit().putString(Prefs.KEY_TRANSLATE_TARGET, code).apply()
    }

    /** One entry in the picker. */
    class Target(val code: String, val label: String)

    /**
     * Every language the platform can name, with the user's own keyboard
     * languages first.
     *
     * Those are hoisted because they are overwhelmingly the likely targets —
     * someone who has enabled German and Turkish is far more likely to want
     * one of those than Yoruba — and a scroll through 180 entries to reach the
     * two you actually use is a bad list.
     */
    fun list(c: Context, uiLocale: Locale): List<Target> {
        val enabled = Prefs.languages(c)
        val auto = Target(AUTO, autoLabel(c, uiLocale))
        fun label(code: String) =
            Locale.forLanguageTag(code).getDisplayLanguage(uiLocale)

        val top = enabled.map { Target(it, label(it)) }
            .filter { it.label.isNotBlank() }
        val topCodes = top.map { it.code }.toSet()
        val rest = Locale.getISOLanguages()
            .filter { it !in topCodes }
            .map { Target(it, label(it)) }
            // A code with no display name would render as the raw ISO code,
            // which is not something anyone can pick from.
            .filter { it.label.isNotBlank() && it.label != it.code }
            .sortedBy { it.label.lowercase(uiLocale) }
        return listOf(auto) + top + rest
    }

    /** "Keyboard language (German)" — says what Auto currently resolves to. */
    private fun autoLabel(c: Context, uiLocale: Locale): String {
        val current = Prefs.currentLang(c) ?: Prefs.languages(c).firstOrNull() ?: "en"
        val name = Locale.forLanguageTag(current).getDisplayLanguage(uiLocale)
        return c.getString(com.rimboard.keyboard.R.string.tr_target_auto, name)
    }

    /**
     * The ISO code to translate *into* for the keyless engine, which — unlike
     * the model behind the Anthropic path — cannot detect the source and so
     * must be given a real target different from it.
     *
     * The stored target if it is concrete and not the source; otherwise the
     * first enabled keyboard language that is not the source; otherwise
     * English. So a Turkish keyboard with English also enabled translates
     * Turkish into English by default, which is the common bilingual case.
     */
    fun keylessTarget(c: Context, source: String): String {
        val stored = stored(c)
        if (stored != AUTO && stored != source) return stored
        Prefs.languages(c).firstOrNull { it != source }?.let { return it }
        return if (source != "en") "en" else "es"
    }

    /** The display name of a bare ISO code, in the UI locale. */
    fun labelFor(code: String, uiLocale: Locale): String =
        Locale.forLanguageTag(code).getDisplayLanguage(uiLocale).ifBlank { code }

    /** The label for whatever is currently selected, for a settings summary. */
    fun currentLabel(c: Context, uiLocale: Locale): String {
        val code = stored(c)
        if (code == AUTO) return autoLabel(c, uiLocale)
        return Locale.forLanguageTag(code).getDisplayLanguage(uiLocale).ifBlank { code }
    }
}
