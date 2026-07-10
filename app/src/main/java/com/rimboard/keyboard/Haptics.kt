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
 * "touch feedback" toggle is off (common on MIUI/HyperOS), by using the
 * vibrator service directly with a fallback to view haptics.
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

    fun tap(view: View) {
        val strength = com.rimboard.keyboard.settings.Prefs.hapticStrength(view.context)
        val v = vibrator(view.context)
        val predefined = when (strength) {
            "light" -> VibrationEffect.EFFECT_TICK
            "strong" -> VibrationEffect.EFFECT_HEAVY_CLICK
            else -> VibrationEffect.EFFECT_CLICK
        }
        val ms = when (strength) {
            "light" -> 8L
            "strong" -> 24L
            else -> 15L
        }
        when {
            v != null && v.hasVibrator() && Build.VERSION.SDK_INT >= 29 ->
                v.vibrate(VibrationEffect.createPredefined(predefined))
            v != null && v.hasVibrator() ->
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            else -> view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    fun longPress(view: View) {
        val v = vibrator(view.context)
        when {
            v != null && v.hasVibrator() && Build.VERSION.SDK_INT >= 29 ->
                v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
            v != null && v.hasVibrator() ->
                v.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
            else -> view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }
}
