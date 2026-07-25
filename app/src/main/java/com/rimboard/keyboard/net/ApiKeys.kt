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
    private const val KEY_TENOR = "tenor_api_key"

    private fun prefs(c: Context): SharedPreferences =
        // Explicitly NOT createDeviceProtectedStorageContext(); see the note above.
        c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun anthropic(c: Context): String? = get(c, KEY_ANTHROPIC)

    fun setAnthropic(c: Context, key: String?) = set(c, KEY_ANTHROPIC, key)

    fun tenor(c: Context): String? = get(c, KEY_TENOR)

    fun setTenor(c: Context, key: String?) = set(c, KEY_TENOR, key)

    private fun get(c: Context, name: String): String? =
        prefs(c).getString(name, null)?.trim()?.takeIf { it.isNotEmpty() }

    private fun set(c: Context, name: String, key: String?) {
        val v = key?.trim().orEmpty()
        prefs(c).edit().apply {
            if (v.isEmpty()) remove(name) else putString(name, v)
        }.apply()
    }

    /**
     * A redacted form for the settings screen, so it can show that a key is
     * present without putting the key itself on a screen someone may be
     * mirroring, screenshotting, or presenting.
     */
    fun masked(key: String?): String? {
        if (key.isNullOrEmpty()) return null
        return if (key.length <= 8) "•".repeat(key.length)
        else key.take(4) + "…" + key.takeLast(4)
    }
}
