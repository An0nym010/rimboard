package com.rimboard.keyboard.theme

import android.content.Context
import android.graphics.Typeface
import com.rimboard.keyboard.engine.UserData
import com.rimboard.keyboard.model.Backdrop
import com.rimboard.keyboard.settings.Prefs
import com.rimboard.keyboard.ui.KeyboardView
import com.rimboard.keyboard.ui.PhotoBackdrop
import java.io.File

/**
 * Everything the Look and feel screen decides, in one place.
 *
 * The keyboard's appearance is not one preference: it is a theme, tinted by the
 * host app, possibly re-derived over a background photo, with a live backdrop
 * that outranks nothing and is outranked by the photo — and then eight more
 * settings applied to the view itself. `RimBoardService` worked all of that out
 * inline, which was fine while it was the only thing that needed the answer.
 *
 * `KeyboardPreviewView` needs the same answer now. A preview that computes its
 * own version of "what the keyboard looks like" is a second implementation of
 * the product's most visible behaviour, and it would be wrong in exactly the
 * cases that matter — a per-app tint it forgot, a photo it did not adapt to —
 * while looking perfectly plausible. That is the seam shape this branch has
 * spent three commits closing, so the preview does not open a new one.
 *
 * The order here is load-bearing and is the service's own: **the app tint is
 * applied before the photo variant is derived from it.** Over a photo the caps
 * become scrims and only the accent survives, so tinting afterwards is the one
 * case where per-app colour would do nothing at all.
 */
object KeyboardLook {

    /**
     * @param base what panels and popups use — opaque, never photo-adapted.
     * @param drawn what the keys and the strip use; [base] unless a photo is set.
     * @param liveMode the live background's own setting, for `PhotoBackdrop`.
     * @param clearSurfaces whether the keys must not paint over the backdrop.
     * @param dimAlpha how far the photo is dimmed.
     */
    data class Look(
        val base: KeyboardTheme,
        val drawn: KeyboardTheme,
        val liveMode: String,
        val liveVisible: Boolean,
        val clearSurfaces: Boolean,
        val dimAlpha: Int
    )

    /**
     * The look the keyboard would have in [pkg].
     *
     * [pkg] is the host application. Pass the settings app's own package for a
     * preview and it answers honestly: that *is* what the keyboard looks like
     * in that app, per-app tint included.
     *
     * Reads the palette cache only, never computing it — working an app's
     * colours out means parsing its resource table and rasterising its icon,
     * and this runs on the main thread while the keyboard is appearing.
     */
    fun of(context: Context, pkg: String?): Look {
        val themePref = Prefs.theme(context)
        val curatedOnly = Prefs.curatedColorsOnly(context)
        // Part of the cache key, not just of the answer: an app's declared
        // theme resolves through this process's configuration.
        val night = Themes.isNightMode(context)
        val appIsLight =
            if (Prefs.matchAppMode(context) && pkg != null)
                AppPalette.cachedIsLight(pkg, curatedOnly, night)
            else null
        val base = Themes.resolve(context, themePref, appIsLight).let { b ->
            if (pkg != null && Prefs.themePerApp(context) && Themes.tintable(themePref))
                Themes.forApp(
                    b, pkg,
                    AppPalette.cachedHue(pkg, curatedOnly, night),
                    Prefs.tintStrength(context)
                )
            else b
        }
        val dimAlpha = Prefs.bgDimAlpha(context)
        val hasPhoto = File(UserData.dataDir(context), "bg_image.jpg").exists()
        val drawn =
            if (hasPhoto) Themes.overPhoto(base, Prefs.bgLuma(context), dimAlpha) else base
        val liveMode = Prefs.liveBackground(context)
        return Look(
            base = base,
            drawn = drawn,
            liveMode = liveMode,
            liveVisible = Backdrop.liveVisible(hasPhoto, liveMode),
            clearSurfaces = Backdrop.surfacesTransparent(hasPhoto, liveMode),
            dimAlpha = dimAlpha
        )
    }

    /**
     * Works the app's real colours out, then calls [onReady] once they land.
     *
     * [of] reads the cache and never fills it, which is right on the keyboard's
     * hot path and wrong for a preview: the settings process has its own cache,
     * cold, so a per-app tint would fall back to the hue derived from the
     * package *name* and the preview would show a keyboard in a colour the real
     * one never takes. That is the exact failure a preview exists to prevent,
     * so it warms the cache the same way the keyboard does and redraws when the
     * answer arrives.
     *
     * [onReady] is called from a background thread.
     */
    fun warm(context: Context, pkg: String?, onReady: () -> Unit) {
        AppPalette.prefetch(
            context, pkg,
            Prefs.curatedColorsOnly(context),
            Themes.isNightMode(context),
            onReady
        )
    }

    /**
     * The Look and feel settings that live on the view.
     *
     * Only the ones that are a function of preferences. Everything the service
     * sets from the *field* — password, incognito, TLD popups, whether a swipe
     * may be decoded — stays with the service, because a preview has no field
     * and guessing one would make it lie.
     */
    fun applyTo(kv: KeyboardView, context: Context, look: Look, landscape: Boolean) {
        kv.backdropDrawn = look.clearSurfaces
        kv.theme = look.drawn
        kv.keyBorders = Prefs.keyBorders(context)
        kv.narrowGaps = Prefs.narrowGaps(context)
        kv.sidePadPct = Prefs.sidePadPct(context)
        kv.bottomPadPct = Prefs.bottomPadPct(context)
        kv.labelScale = Prefs.labelScalePct(context) / 100f
        kv.customTypeface = customFont(context)
        kv.keyHeightFactor = Prefs.heightFactor(context)
        kv.showDigitHints = !Prefs.numberRow(context)
        kv.splitFraction = when (Prefs.splitMode(context)) {
            "on" -> 0.12f
            "landscape" -> if (landscape) 0.12f else 0f
            else -> 0f
        }
    }

    /**
     * The backdrop's four properties, which are not the keyboard's.
     *
     * **The background is always the opaque base, never transparent.** The
     * preview got this wrong by reasoning about it instead of copying it: it
     * used `clearSurfaces` here, which is the rule for whether the *keys* may
     * paint over the backdrop, and blanked the root whenever a live background
     * was on. The sky then drew over nothing, and on a light theme the key
     * lettering landed on whatever page was behind — dark text on a dark
     * settings screen, all but invisible. It looked right for as long as the
     * theme happened to be dark, which is the whole reason this is here rather
     * than written out twice.
     *
     * `starColor` is [Look.base]'s key colour rather than [Look.drawn]'s: over
     * a photo the drawn theme's lettering is a scrim colour chosen for the
     * image, and the sky is not drawn over a photo at all.
     */
    fun applyBackdropTo(backdrop: PhotoBackdrop, look: Look) {
        backdrop.setBackgroundColor(look.base.background)
        backdrop.dimAlpha = look.dimAlpha
        backdrop.starColor = look.base.keyText
        backdrop.liveMode = look.liveMode
    }

    private var cachedFont: Typeface? = null
    private var cachedFontStamp: Long = 0L

    /** The user's own font file, loaded once and reloaded when it changes. */
    fun customFont(context: Context): Typeface? {
        val f = File(UserData.dataDir(context), "custom_font.ttf")
        if (!f.exists()) {
            cachedFont = null
            return null
        }
        val stamp = f.lastModified()
        if (cachedFont == null || cachedFontStamp != stamp) {
            cachedFont = try {
                Typeface.createFromFile(f)
            } catch (_: Exception) {
                null
            }
            cachedFontStamp = stamp
        }
        return cachedFont
    }
}
