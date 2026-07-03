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
            "light" -> light()
            "dark" -> dark()
            "amoled" -> amoled()
            "dynamic" ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamic(context, night)
                else if (night) dark() else light()
            else -> if (night) dark() else light()
        }
    }
}
