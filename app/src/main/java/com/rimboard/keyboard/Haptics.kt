package com.rimboard.keyboard

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Key-press haptics that keep working even when the system-wide
 * "touch feedback" toggle is off (common on MIUI/HyperOS), by driving the
 * vibrator service directly with a fallback to view haptics.
 *
 * Note on predefined effects: [VibrationEffect.EFFECT_CLICK] and friends feel
 * crisper, but many OEM devices (notably Xiaomi/HyperOS, which RimBoard
 * targets) do not implement them and stay completely silent when asked to play
 * one. So we only use a predefined effect when the device reports it as
 * supported (API 30+), and otherwise fall back to a plain one-shot vibration,
 * which every device with a motor honours.
 */
object Haptics {

    private fun vibrator(c: Context): Vibrator? = try {
        if (Build.VERSION.SDK_INT >= 31) {
            (c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            c.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (_: Exception) {
        null
    }

    /** Reliable effect for [predefined]: the predefined effect only if the
     *  device confirms support, otherwise a one-shot of [ms] at [amp] (1..255). */
    private fun effect(v: Vibrator, predefined: Int, ms: Long, amp: Int): VibrationEffect {
        if (Build.VERSION.SDK_INT >= 30 &&
            v.areAllEffectsSupported(predefined) == Vibrator.VIBRATION_EFFECT_SUPPORT_YES
        ) {
            return VibrationEffect.createPredefined(predefined)
        }
        val amplitude = if (v.hasAmplitudeControl()) amp else VibrationEffect.DEFAULT_AMPLITUDE
        return VibrationEffect.createOneShot(ms, amplitude)
    }

    /**
     * Whether the system's own touch-feedback switch is on.
     *
     * Readable without any permission, and it is the switch that decides
     * whether a `USAGE_TOUCH` vibration is played or silently dropped. Defaults
     * to on if the setting is missing, because a device that does not have it
     * is not a device that has turned it off.
     */
    fun systemTouchFeedbackOn(c: Context): Boolean = try {
        android.provider.Settings.System.getInt(
            c.contentResolver,
            android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED,
            1
        ) != 0
    } catch (_: Exception) {
        true
    }

    /**
     * Plays [e], declaring what kind of vibration it is.
     *
     * This is the part that decides whether a keyboard vibrates at all on a lot
     * of phones, and the reasoning here was previously backwards. `USAGE_TOUCH`
     * is the honest description of a key press — and it is *precisely* the
     * category the system's touch-feedback switch governs. Declaring it while
     * that switch is off asks the platform to play a touch vibration on a
     * device that has been told not to play touch vibrations. The motor is
     * fine, the permission is held, the call returns without error, and nothing
     * happens. That is the state a user with the switch off was left in, by the
     * change that was supposed to fix exactly this.
     *
     * So the usage is chosen from the switch. With it on, `USAGE_TOUCH` is
     * correct and is what the platform wants to hear. With it off, the user has
     * still deliberately turned *this keyboard's* vibration on, and the request
     * goes out under `USAGE_HARDWARE_FEEDBACK` — feedback for a key acting as a
     * physical one, which is a fair description of a keyboard and is not the
     * category that switch governs.
     *
     * That second path is best-effort and deliberately not promised anywhere in
     * the UI: whether it survives is the platform's decision and OEM builds
     * differ. What the settings screen does instead is say plainly that the
     * system switch is off, so a silent keyboard is explained rather than
     * looking broken. Below API 33 the audio-attributes overload is the only
     * way to say any of this.
     */
    private fun play(v: Vibrator, e: VibrationEffect, c: Context) {
        // `VibrationAttributes` exists from API 30 but `createForUsage` only
        // from 33, so the audio-attributes route covers 26..32 as well.
        if (Build.VERSION.SDK_INT >= 33) {
            val usage =
                if (systemTouchFeedbackOn(c)) VibrationAttributes.USAGE_TOUCH
                else VibrationAttributes.USAGE_HARDWARE_FEEDBACK
            v.vibrate(e, VibrationAttributes.createForUsage(usage))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(
                e,
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }
    }

    private fun fire(view: View, predefined: Int, ms: Long, amp: Int, fallback: Int) {
        val v = vibrator(view.context)
        if (v == null || !v.hasVibrator()) {
            viewFallback(view, fallback)
            return
        }
        try {
            play(v, effect(v, predefined, ms, amp), view.context)
        } catch (_: Exception) {
            viewFallback(view, fallback)
        }
    }

    /**
     * Fires one key-press vibration on demand, for the settings screen.
     *
     * Whether a vibration survives the platform's filtering cannot be observed
     * from inside the app — a suppressed vibration throws nothing and returns
     * normally, which is why this fault was diagnosed twice from theory and
     * fixed wrongly the first time. The only reliable instrument is the user's
     * hand, so the settings screen offers one.
     */
    fun test(view: View) = tap(view)

    /**
     * The last resort, asked to ignore the global setting where that is still
     * possible. Without the flag this is silent on exactly the devices the
     * fallback exists for — the ones that turned touch feedback off.
     */
    @Suppress("DEPRECATION")
    private fun viewFallback(view: View, fallback: Int) {
        view.performHapticFeedback(
            fallback,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
    }

    fun tap(view: View) {
        when (com.rimboard.keyboard.settings.Prefs.hapticStrength(view.context)) {
            "light" -> fire(view, VibrationEffect.EFFECT_TICK, 10L, 60, HapticFeedbackConstants.KEYBOARD_TAP)
            "strong" -> fire(view, VibrationEffect.EFFECT_HEAVY_CLICK, 28L, 255, HapticFeedbackConstants.KEYBOARD_TAP)
            else -> fire(view, VibrationEffect.EFFECT_CLICK, 18L, 130, HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    fun longPress(view: View) {
        fire(view, VibrationEffect.EFFECT_HEAVY_CLICK, 28L, 200, HapticFeedbackConstants.LONG_PRESS)
    }
}
