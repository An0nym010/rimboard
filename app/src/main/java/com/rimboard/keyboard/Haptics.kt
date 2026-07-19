package com.rimboard.keyboard

import android.content.Context
import android.os.Build
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

    private fun fire(view: View, predefined: Int, ms: Long, amp: Int, fallback: Int) {
        val v = vibrator(view.context)
        if (v == null || !v.hasVibrator()) {
            view.performHapticFeedback(fallback)
            return
        }
        try {
            v.vibrate(effect(v, predefined, ms, amp))
        } catch (_: Exception) {
            view.performHapticFeedback(fallback)
        }
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
