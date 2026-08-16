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
     * Plays [e] as a *touch* vibration.
     *
     * This is the part that decides whether a keyboard vibrates at all on a lot
     * of phones. `vibrate(effect)` with no attributes is filed under
     * `USAGE_UNKNOWN`, and an unknown-usage vibration from a background service
     * is exactly what the platform — and MIUI/HyperOS more aggressively than
     * most — drops when the system's own touch-feedback switch is off. The
     * motor is fine, the permission is held, the call returns without error,
     * and nothing happens.
     *
     * Declaring `USAGE_TOUCH` says what this actually is: feedback for a key
     * the user just pressed. Below API 30 the same thing is said with the
     * audio-attributes overload, where sonification is the nearest equivalent.
     */
    private fun play(v: Vibrator, e: VibrationEffect) {
        // `VibrationAttributes` exists from API 30 but `createForUsage` only
        // from 33, so the audio-attributes route covers 26..32 as well.
        if (Build.VERSION.SDK_INT >= 33) {
            v.vibrate(e, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
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
            play(v, effect(v, predefined, ms, amp))
        } catch (_: Exception) {
            viewFallback(view, fallback)
        }
    }

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
