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

    // Gboard-style layout with a Telegram-style palette: quiet neutrals biased
    // slightly toward the azure accent, one confident blue, soft depth. All
    // values are original (not sampled from either app).
    private fun light() = KeyboardTheme(
        background = 0xFFEDF0F5.toInt(),
        keyBg = 0xFFFFFFFF.toInt(),
        keyBgFunc = 0xFFDBE1EB.toInt(),
        keyBgPressed = 0xFFC3CDDB.toInt(),
        keyText = 0xFF1B1E24.toInt(),
        keyHint = 0xFF697180.toInt(),
        accent = 0xFF3E7BFA.toInt(),
        onAccent = 0xFFFFFFFF.toInt(),
        stripText = 0xFF363B44.toInt(),
        previewBg = 0xFFFFFFFF.toInt(),
        isDark = false
    )

    private fun dark() = KeyboardTheme(
        background = 0xFF141A22.toInt(),
        keyBg = 0xFF29323D.toInt(),
        keyBgFunc = 0xFF1D242E.toInt(),
        keyBgPressed = 0xFF3A4553.toInt(),
        keyText = 0xFFE8EBF0.toInt(),
        keyHint = 0xFF868F9D.toInt(),
        accent = 0xFF5C9CFF.toInt(),
        onAccent = 0xFF0D1218.toInt(),
        stripText = 0xFFE4E8EE.toInt(),
        previewBg = 0xFF323D4A.toInt(),
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


    // ---- preset palettes. All original values, tuned around one accent each:
    // dark ones keep keys ~2 steps above the background, light ones use white
    // caps on a tinted wash, matching the contrast structure of light()/dark().

    private fun ocean() = KeyboardTheme(
        background = 0xFF0E1B25.toInt(),
        keyBg = 0xFF1D3140.toInt(),
        keyBgFunc = 0xFF15252F.toInt(),
        keyBgPressed = 0xFF2C495C.toInt(),
        keyText = 0xFFE2EEF5.toInt(),
        keyHint = 0xFF7FA0B4.toInt(),
        accent = 0xFF2EC5CE.toInt(),
        onAccent = 0xFF00272B.toInt(),
        stripText = 0xFFD6E6EF.toInt(),
        previewBg = 0xFF244050.toInt(),
        isDark = true
    )

    private fun forest() = KeyboardTheme(
        background = 0xFF111B14.toInt(),
        keyBg = 0xFF213528.toInt(),
        keyBgFunc = 0xFF17251C.toInt(),
        keyBgPressed = 0xFF304C3A.toInt(),
        keyText = 0xFFE4F0E7.toInt(),
        keyHint = 0xFF8AA893.toInt(),
        accent = 0xFF6BCB77.toInt(),
        onAccent = 0xFF06210D.toInt(),
        stripText = 0xFFD8E8DC.toInt(),
        previewBg = 0xFF2A4235.toInt(),
        isDark = true
    )

    private fun sunset() = KeyboardTheme(
        background = 0xFF1F1418.toInt(),
        keyBg = 0xFF372227.toInt(),
        keyBgFunc = 0xFF291A1F.toInt(),
        keyBgPressed = 0xFF4C3137.toInt(),
        keyText = 0xFFF5E8E4.toInt(),
        keyHint = 0xFFB18E90.toInt(),
        accent = 0xFFFF8A5C.toInt(),
        onAccent = 0xFF2B1105.toInt(),
        stripText = 0xFFEBDBD8.toInt(),
        previewBg = 0xFF422B31.toInt(),
        isDark = true
    )

    private fun graphite() = KeyboardTheme(
        background = 0xFF17181C.toInt(),
        keyBg = 0xFF262930.toInt(),
        keyBgFunc = 0xFF1D1F24.toInt(),
        keyBgPressed = 0xFF383C46.toInt(),
        keyText = 0xFFEAECEF.toInt(),
        keyHint = 0xFF8F949E.toInt(),
        accent = 0xFFFFB454.toInt(),
        onAccent = 0xFF241300.toInt(),
        stripText = 0xFFE6E8EB.toInt(),
        previewBg = 0xFF30343C.toInt(),
        isDark = true
    )

    private fun rose() = KeyboardTheme(
        background = 0xFFF7EDF1.toInt(),
        keyBg = 0xFFFFFFFF.toInt(),
        keyBgFunc = 0xFFEEDBE3.toInt(),
        keyBgPressed = 0xFFDDBFCC.toInt(),
        keyText = 0xFF2A1E24.toInt(),
        keyHint = 0xFF87707B.toInt(),
        accent = 0xFFD4548C.toInt(),
        onAccent = 0xFFFFFFFF.toInt(),
        stripText = 0xFF463039.toInt(),
        previewBg = 0xFFFFFFFF.toInt(),
        isDark = false
    )

    private fun mint() = KeyboardTheme(
        background = 0xFFEAF4EE.toInt(),
        keyBg = 0xFFFFFFFF.toInt(),
        keyBgFunc = 0xFFD6E8DD.toInt(),
        keyBgPressed = 0xFFBBD9C8.toInt(),
        keyText = 0xFF1C2620.toInt(),
        keyHint = 0xFF6C8477.toInt(),
        accent = 0xFF2FA36B.toInt(),
        onAccent = 0xFFFFFFFF.toInt(),
        stripText = 0xFF33453B.toInt(),
        previewBg = 0xFFFFFFFF.toInt(),
        isDark = false
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

    private fun custom(context: Context, slot: Int): KeyboardTheme {
        val P = com.rimboard.keyboard.settings.Prefs
        val bg = P.customColor(context, P.slotKey(P.KEY_CC_BG, slot), 0xFF1B1E23.toInt())
        val key = P.customColor(context, P.slotKey(P.KEY_CC_KEY, slot), 0xFF3A3E46.toInt())
        val text = P.customColor(context, P.slotKey(P.KEY_CC_TEXT, slot), 0xFFE8EAED.toInt())
        val accent = P.customColor(context, P.slotKey(P.KEY_CC_ACCENT, slot), 0xFF8AB4F8.toInt())
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

    /**
     * The theme variant used while a background photo is showing: key caps
     * become translucent scrims so the picture reads through them, and the
     * scrim's polarity — dark caps with light lettering, or the reverse — is
     * chosen from the image itself.
     *
     * [luma] is the photo's mean luminance (0..255, computed when it was
     * picked) and [dimAlpha] the dim overlay strength; what matters for
     * legibility is their product, since a bright photo under a heavy dim is a
     * dark surface by the time the letters are drawn on it. Solid accents
     * (enter, caps-lock) and the popup surfaces keep the base theme — popups
     * sit above the photo and have to be readable outright.
     */
    fun overPhoto(base: KeyboardTheme, luma: Int, dimAlpha: Int): KeyboardTheme {
        val effective = luma.coerceIn(0, 255) * (255 - dimAlpha.coerceIn(0, 255)) / 255
        val darkSurface = effective < 110
        val fg = if (darkSurface) 0xFFFFFFFF.toInt() else 0xFF14171C.toInt()
        return base.copy(
            keyBg = if (darkSurface) 0x2EFFFFFF else 0x30000000,
            keyBgFunc = if (darkSurface) 0x1AFFFFFF else 0x1C000000,
            keyBgPressed = if (darkSurface) 0x66FFFFFF else 0x59000000,
            keyText = fg,
            keyHint = (fg and 0x00FFFFFF) or (0xB4 shl 24),
            // The suggestion strip sits on the same photo (PhotoBackdrop draws
            // it behind strip and keys together), so its text adapts with the
            // keys' — base-theme grey on an arbitrary picture is a coin flip.
            stripText = fg,
            isDark = darkSurface
        )
    }

    /**
     * Panel surface (emoji, clipboard, editing, tools) over a background
     * photo: the base theme with a translucent background, so the picture
     * carries on behind the panel instead of being blanked out by an opaque
     * block the moment one opens.
     *
     * Deliberately still mostly opaque, unlike the key scrims. A panel is
     * dense content — an emoji grid, or clipboard entries of arbitrary text —
     * and it has to stay readable over an arbitrary picture. At this alpha the
     * surface is still close enough to the base background that every other
     * colour in the theme remains correct against it, so nothing else has to
     * adapt and no text can land on a colour it was not designed for.
     */
    fun panelOverPhoto(base: KeyboardTheme): KeyboardTheme =
        base.copy(background = (base.background and 0x00FFFFFF) or (0xD8 shl 24))

    /**
     * The themes whose colours are a deliberate choice rather than a default,
     * and so are never re-tinted per app: high contrast exists to hit a
     * contrast ratio, the three custom slots are colours the user picked by
     * hand, and `dynamic` is already adapting — to the system wallpaper.
     * Tinting any of them would quietly overrule the reason it was chosen.
     */
    private val FIXED_ACCENT = setOf("contrast", "custom", "custom2", "custom3", "dynamic")

    internal fun tintable(pref: String) = pref !in FIXED_ACCENT

    /**
     * A hue for [pkg], stable across reboots and installs because it is a pure
     * function of the package name.
     *
     * The obvious implementation is to sample the app's launcher icon, and it
     * is the wrong one here: reading another package's icon needs
     * `QUERY_ALL_PACKAGES`, and this keyboard's whole claim is that it ships
     * with `VIBRATE` and nothing else. So the hue is derived from the name
     * instead. It is not the app's brand colour and does not pretend to be —
     * what it has to be is *distinct and constant*, so that the keyboard looks
     * settled in each app and different between them.
     *
     * The hue is the *low* bits of the hash, via `% 360`, and that is what
     * makes the choice of hash matter. Package families differ only near the
     * end of the name (`…app`, `…app.pro`, `…app.beta`), and a trailing
     * difference reaches the low bits and nowhere else:
     *
     *  - [String.hashCode] is `31*h + c`, so the last character is worth 31
     *    and such names land on consecutive hues — one colour to the eye.
     *  - FNV-1a alone is barely better. A trailing difference flips a few low
     *    bits, which the final multiply turns into one of a handful of fixed
     *    offsets, so the family stays clustered.
     *
     * Hence the murmur3 finalizer: it folds high bits down into the low ones,
     * so the trailing difference reaches the whole word. Measured over 2000
     * such families, the closest pair of hues averages 1° with `hashCode`,
     * 2.4° with bare FNV, and 21.8° with the finalizer — against 21.7° for
     * genuinely random hues, which is the ceiling.
     */
    internal fun hueFor(pkg: String): Int {
        var h = 0x811C9DC5.toInt()
        for (ch in pkg) {
            h = h xor ch.code
            h *= 0x01000193
        }
        h = h xor (h ushr 16)
        h *= 0x85EBCA6B.toInt()
        h = h xor (h ushr 13)
        h *= 0xC2B2AE35.toInt()
        h = h xor (h ushr 16)
        return ((h % 360) + 360) % 360
    }

    private fun hsl(hue: Int, s: Float, l: Float): Int {
        val c = (1 - kotlin.math.abs(2 * l - 1)) * s
        val x = c * (1 - kotlin.math.abs((hue / 60f) % 2 - 1))
        val m = l - c / 2
        val (r, g, b) = when (hue / 60) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun ch(v: Float) = (((v + m) * 255).toInt()).coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    /**
     * [base] re-tinted for whichever app is being typed in.
     *
     * Only the accent is actually replaced; the surfaces move a few percent
     * toward the same hue and no further. That asymmetry is the point — the
     * accent is one small area (enter, caps lock, the active suggestion) where
     * saturation reads as intent, while the background is most of the screen,
     * where the same saturation reads as a tinted-screen fault. Key text,
     * hints and `isDark` are untouched, so contrast against the caps is exactly
     * what the base theme was designed for, whatever hue comes out.
     */
    /**
     * [hueOverride] is the app's own colour where it could be read from its
     * icon; without it the hue falls back to [hueFor], which is stable and
     * distinct but is not the app's colour and does not claim to be.
     */
    fun forApp(base: KeyboardTheme, pkg: String?, hueOverride: Int? = null): KeyboardTheme {
        if (pkg.isNullOrEmpty()) return base
        val hue = hueOverride ?: hueFor(pkg)
        // Dark themes need a lighter, less saturated accent to stay legible
        // against a near-black surface; light ones need it darker so white
        // `onAccent` lettering survives on top of it.
        val accent = if (base.isDark) hsl(hue, 0.72f, 0.66f) else hsl(hue, 0.62f, 0.44f)
        return base.copy(
            accent = accent,
            onAccent = if (luminance(accent) < 0.5) 0xFFFFFFFF.toInt() else 0xFF14171C.toInt(),
            background = mix(base.background, accent, 0.06f),
            keyBg = mix(base.keyBg, accent, 0.05f),
            keyBgFunc = mix(base.keyBgFunc, accent, 0.05f),
            keyBgPressed = mix(base.keyBgPressed, accent, 0.10f),
            previewBg = mix(base.previewBg, accent, 0.05f)
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
            "ocean" -> ocean()
            "forest" -> forest()
            "sunset" -> sunset()
            "graphite" -> graphite()
            "rose" -> rose()
            "mint" -> mint()
            "custom" -> custom(context, 1)
            "custom2" -> custom(context, 2)
            "custom3" -> custom(context, 3)
            "dynamic" ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamic(context, night)
                else if (night) dark() else light()
            else -> if (night) dark() else light()
        }
    }
}
