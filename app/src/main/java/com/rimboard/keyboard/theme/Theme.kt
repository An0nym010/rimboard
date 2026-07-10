package com.rimboard.keyboard.theme

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.core.content.ContextCompat

data class KeyboardTheme(
    val background: Int,
    val keyBg: Int,
    val keyBgFunc: Int,
    val keyBgPressed: Int,
    val keyText: Int,
    val keyHint: Int,
    val accent: Int,
    val onAccent: Int,
    val stripText: Int,
    val previewBg: Int,
    val isDark: Boolean
)

object Themes {

    private fun light() = KeyboardTheme(
        background = 0xFFEEF1F5.toInt(),
        keyBg = 0xFFFFFFFF.toInt(),
        keyBgFunc = 0xFFDDE2EA.toInt(),
        keyBgPressed = 0xFFC7CEDA.toInt(),
        keyText = 0xFF1F1F1F.toInt(),
        keyHint = 0xFF6B7078.toInt(),
        accent = 0xFF1A73E8.toInt(),
        onAccent = 0xFFFFFFFF.toInt(),
        stripText = 0xFF3C4043.toInt(),
        previewBg = 0xFFFFFFFF.toInt(),
        isDark = false
    )

    private fun dark() = KeyboardTheme(
        background = 0xFF1B1E23.toInt(),
        keyBg = 0xFF3A3E46.toInt(),
        keyBgFunc = 0xFF262A31.toInt(),
        keyBgPressed = 0xFF565B66.toInt(),
        keyText = 0xFFE8EAED.toInt(),
        keyHint = 0xFF9AA0A6.toInt(),
        accent = 0xFF8AB4F8.toInt(),
        onAccent = 0xFF1F1F1F.toInt(),
        stripText = 0xFFE8EAED.toInt(),
        previewBg = 0xFF4A4F58.toInt(),
        isDark = true
    )

    private fun amoled() = KeyboardTheme(
        background = 0xFF000000.toInt(),
        keyBg = 0xFF161A1E.toInt(),
        keyBgFunc = 0xFF0C0E10.toInt(),
        keyBgPressed = 0xFF2A2F35.toInt(),
        keyText = 0xFFE8EAED.toInt(),
        keyHint = 0xFF9AA0A6.toInt(),
        accent = 0xFF8AB4F8.toInt(),
        onAccent = 0xFF1F1F1F.toInt(),
        stripText = 0xFFE8EAED.toInt(),
        previewBg = 0xFF23282E.toInt(),
        isDark = true
    )


    private fun isNightMode(context: Context): Boolean =
        (context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun highContrast() = KeyboardTheme(
        background = 0xFF000000.toInt(),
        keyBg = 0xFF1C1C1C.toInt(),
        keyBgFunc = 0xFF0A0A0A.toInt(),
        keyBgPressed = 0xFF555555.toInt(),
        keyText = 0xFFFFFFFF.toInt(),
        keyHint = 0xFFCCCCCC.toInt(),
        accent = 0xFFFFEB3B.toInt(),
        onAccent = 0xFF000000.toInt(),
        stripText = 0xFFFFFFFF.toInt(),
        previewBg = 0xFF2A2A2A.toInt(),
        isDark = true
    )

    private fun luminance(c: Int): Double {
        val r = (c shr 16 and 0xFF) / 255.0
        val g = (c shr 8 and 0xFF) / 255.0
        val b = (c and 0xFF) / 255.0
        return 0.299 * r + 0.587 * g + 0.114 * b
    }

    private fun mix(a: Int, b: Int, f: Float): Int {
        fun ch(sh: Int) = (((a shr sh and 0xFF) * (1 - f)) + ((b shr sh and 0xFF) * f)).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun custom(context: Context): KeyboardTheme {
        val bg = com.rimboard.keyboard.settings.Prefs.customColor(
            context, com.rimboard.keyboard.settings.Prefs.KEY_CC_BG, 0xFF1B1E23.toInt())
        val key = com.rimboard.keyboard.settings.Prefs.customColor(
            context, com.rimboard.keyboard.settings.Prefs.KEY_CC_KEY, 0xFF3A3E46.toInt())
        val text = com.rimboard.keyboard.settings.Prefs.customColor(
            context, com.rimboard.keyboard.settings.Prefs.KEY_CC_TEXT, 0xFFE8EAED.toInt())
        val accent = com.rimboard.keyboard.settings.Prefs.customColor(
            context, com.rimboard.keyboard.settings.Prefs.KEY_CC_ACCENT, 0xFF8AB4F8.toInt())
        val dark = luminance(bg) < 0.5
        return KeyboardTheme(
            background = bg,
            keyBg = key,
            keyBgFunc = mix(key, bg, 0.55f),
            keyBgPressed = mix(key, if (dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt(), 0.25f),
            keyText = text,
            keyHint = mix(text, bg, 0.4f),
            accent = accent,
            onAccent = if (luminance(accent) < 0.5) 0xFFFFFFFF.toInt() else 0xFF000000.toInt(),
            stripText = text,
            previewBg = mix(key, if (dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt(), 0.12f),
            isDark = dark
        )
    }

    private fun dynamic(context: Context, night: Boolean): KeyboardTheme {
        fun c(id: Int) = ContextCompat.getColor(context, id)
        return if (night) KeyboardTheme(
            background = c(android.R.color.system_neutral1_900),
            keyBg = c(android.R.color.system_neutral1_800),
            keyBgFunc = c(android.R.color.system_neutral2_800),
            keyBgPressed = c(android.R.color.system_neutral1_600),
            keyText = c(android.R.color.system_neutral1_50),
            keyHint = c(android.R.color.system_neutral2_400),
            accent = c(android.R.color.system_accent1_200),
            onAccent = c(android.R.color.system_accent1_800),
            stripText = c(android.R.color.system_neutral1_100),
            previewBg = c(android.R.color.system_neutral1_700),
            isDark = true
        ) else KeyboardTheme(
            background = c(android.R.color.system_neutral1_100),
            keyBg = c(android.R.color.system_neutral1_10),
            keyBgFunc = c(android.R.color.system_accent2_100),
            keyBgPressed = c(android.R.color.system_neutral1_200),
            keyText = c(android.R.color.system_neutral1_900),
            keyHint = c(android.R.color.system_neutral2_500),
            accent = c(android.R.color.system_accent1_600),
            onAccent = c(android.R.color.system_accent1_0),
            stripText = c(android.R.color.system_neutral1_700),
            previewBg = c(android.R.color.system_neutral1_10),
            isDark = false
        )
    }

    fun resolve(context: Context, pref: String): KeyboardTheme {
        val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return when (pref) {
            "system" -> if (isNightMode(context)) dark() else light()
            "light" -> light()
            "dark" -> dark()
            "amoled" -> amoled()
            "contrast" -> highContrast()
            "custom" -> custom(context)
            "dynamic" ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamic(context, night)
                else if (night) dark() else light()
            else -> if (night) dark() else light()
        }
    }
}
