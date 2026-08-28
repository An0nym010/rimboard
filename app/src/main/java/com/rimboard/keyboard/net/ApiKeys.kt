package com.rimboard.keyboard.net

import android.content.Context
import android.content.SharedPreferences

/**
 * The user's own API keys for the online features.
 *
 * Bring-your-own-key is the only honest design available here. RimBoard has no
 * server, and an open-source APK has nowhere to hide a shared key: anything
 * compiled in ships to everyone who installs it, and the first person to run
 * `strings` on the APK owns it. So the key is the user's, the billing
 * relationship is the user's, and RimBoard is only ever the thing that types.
 *
 * **These deliberately do not live in [com.rimboard.keyboard.settings.Prefs].**
 * Every other preference is in device-protected storage so the keyboard works
 * on the lock screen before the first unlock — which also means those files are
 * readable at rest before the user has ever authenticated. That trade is right
 * for a layout choice and wrong for a credential. These sit in ordinary
 * credential-protected storage instead, encrypted until first unlock, because
 * nothing here is needed on a lock screen: the network features are never
 * reachable from one.
 *
 * That is file-level encryption from the OS, not a secret vault. Anything with
 * root, and a full device backup taken while unlocked, can still read it — the
 * key is exactly as safe as the device's lock screen. Said plainly in the UI
 * rather than implied away.
 */
object ApiKeys {

    private const val FILE = "rimboard_keys"
    private const val KEY_ANTHROPIC = "anthropic_api_key"
    private const val KEY_KLIPY = "klipy_api_key"
    private const val KEY_LIBRE = "libretranslate_key"

    private fun prefs(c: Context): SharedPreferences? {
        // The flip side of keeping these out of device-protected storage: this
        // file does not exist yet on a phone that has not been unlocked since
        // boot, and asking for it there throws rather than returning empty.
        //
        // RimBoard's service is directBootAware, so it genuinely runs in that
        // window — the keyboard is up on the lock screen. Without this check,
        // tapping the translate or GIF tool before the first unlock took the
        // keyboard down, which is the worst failure this app has.
        if (!unlocked(c)) return null
        return try {
            // Explicitly NOT createDeviceProtectedStorageContext(); see above.
            c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        } catch (_: IllegalStateException) {
            // Belt and braces: the unlock state can change between the check
            // and the call, and the race lands exactly here.
            null
        }
    }

    /**
     * Whether credential-protected storage is readable yet.
     *
     * Public because "locked" and "no key set" are different situations that
     * happen to look identical from [anthropic] — a caller that wants to tell
     * the user which one applies needs to be able to ask.
     */
    fun unlocked(c: Context): Boolean = try {
        val um = c.getSystemService(Context.USER_SERVICE) as android.os.UserManager
        um.isUserUnlocked
    } catch (_: Exception) {
        false
    }

    fun anthropic(c: Context): String? = get(c, KEY_ANTHROPIC)

    fun setAnthropic(c: Context, key: String?) = set(c, KEY_ANTHROPIC, key)

    fun klipy(c: Context): String? = get(c, KEY_KLIPY)

    fun setKlipy(c: Context, key: String?) = set(c, KEY_KLIPY, key)

    /**
     * Optional LibreTranslate key. Not needed at all against a self-hosted
     * instance — which is the reason that source exists — and not needed for
     * the default keyless translator either. Only the public LibreTranslate
     * instance asks for one.
     */
    fun libre(c: Context): String? = get(c, KEY_LIBRE)

    fun setLibre(c: Context, key: String?) = set(c, KEY_LIBRE, key)

    private fun get(c: Context, name: String): String? =
        prefs(c)?.getString(name, null)?.trim()?.takeIf { it.isNotEmpty() }

    private fun set(c: Context, name: String, key: String?) {
        val v = key?.trim().orEmpty()
        prefs(c)?.edit()?.apply {
            if (v.isEmpty()) remove(name) else putString(name, v)
        }?.apply()
    }

    /**
     * A redacted form for the settings screen, so it can show that a key is
     * present without putting the key itself on a screen someone may be
     * mirroring, screenshotting, or presenting.
     *
     * Ends are shown because that is what makes a key identifiable -- the
     * provider's prefix, and the last few characters the provider's own
     * dashboard lists it by -- and the middle is the part worth hiding. That
     * holds for the keys these features actually take: an Anthropic key is
     * over a hundred characters, a LibreTranslate one is a UUID, so eight
     * characters is a few per cent of it.
     *
     * It stops holding for a short key, and the old boundary let it. Anything
     * over eight characters got the ends treatment, so a nine-character key
     * was displayed with eight of its nine characters present and one bullet's
     * worth of secrecy -- a redaction that redacted nothing, in the one case
     * where every character counts. The bar is now the length at which the
     * hidden middle is the majority of the key.
     *
     * The bullets are a fixed width rather than the key's, so the screen does
     * not publish how long the secret is either.
     */
    fun masked(key: String?): String? {
        if (key.isNullOrEmpty()) return null
        return if (key.length < MIN_LENGTH_TO_REVEAL) BULLETS
        else key.take(4) + "…" + key.takeLast(4)
    }

    /**
     * Below this, nothing is shown but bullets: eight revealed characters have
     * to be the smaller half of the key for showing them to be a redaction at
     * all, and twelve hidden is the first length where they clearly are.
     */
    private const val MIN_LENGTH_TO_REVEAL = 20

    private const val BULLETS = "••••••••••"
}
