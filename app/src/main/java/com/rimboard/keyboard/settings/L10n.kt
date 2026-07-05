package com.rimboard.keyboard.settings

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Interface-language override. When the user picks an explicit language,
 * activities and keyboard panels are built with a context configured for
 * that locale; "system" follows the device language.
 */
object L10n {
    fun wrap(base: Context): Context {
        val tag = Prefs.uiLanguage(base)
        if (tag == "system") return base
        val locale = Locale.forLanguageTag(tag)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
