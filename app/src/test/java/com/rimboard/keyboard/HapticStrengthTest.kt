package com.rimboard.keyboard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The three vibration strengths have to be orderable.
 *
 * "Strong" was reported as the weakest of the three, and the cause was not the
 * numbers: support for predefined effects is per-effect, so a device
 * implementing `EFFECT_HEAVY_CLICK` but not `EFFECT_TICK` or `EFFECT_CLICK`
 * received a predefined buzz for Strong and hand-built one-shots for the other
 * two. Those are different scales, and the ordering inverted.
 *
 * The mechanism is a `Vibrator` call and cannot run here, so this guards the
 * two things that can be checked from the source: the fallback numbers rise
 * with the setting, and the decision to use predefined effects is taken for
 * all three at once rather than per level.
 */
class HapticStrengthTest {

    private fun source(): String {
        for (p in listOf("src/main/java", "app/src/main/java")) {
            val f = File(p, "com/rimboard/keyboard/Haptics.kt")
            if (f.isFile) return f.readText()
        }
        throw AssertionError("Haptics.kt not found from ${File(".").absolutePath}")
    }

    private fun level(text: String, name: String): Pair<Long, Int> {
        val m = Regex("""val $name = Triple\([^,]+,\s*(\d+)L,\s*(\d+)\)""").find(text)
            ?: throw AssertionError("no $name level found in Haptics.kt")
        return m.groupValues[1].toLong() to m.groupValues[2].toInt()
    }

    @Test
    fun `duration and amplitude both rise with the setting`() {
        val text = source()
        val (lightMs, lightAmp) = level(text, "LIGHT")
        val (medMs, medAmp) = level(text, "MEDIUM")
        val (strongMs, strongAmp) = level(text, "STRONG")
        // Amplitude alone will not do: plenty of motors have no amplitude
        // control, and on those every level falls back to DEFAULT_AMPLITUDE and
        // feels the same. Duration is what carries the difference there.
        assertTrue("duration must rise: $lightMs $medMs $strongMs",
            lightMs < medMs && medMs < strongMs)
        assertTrue("amplitude must rise: $lightAmp $medAmp $strongAmp",
            lightAmp < medAmp && medAmp < strongAmp)
        assertTrue("amplitude must stay in range", strongAmp <= 255 && lightAmp >= 1)
    }

    @Test
    fun `predefined effects are chosen for all three levels together`() {
        // The actual bug. areAllEffectsSupported must be asked about the whole
        // set, so the three either all come from the device's vocabulary or all
        // come from one-shots — never a mixture, which is not a scale.
        val text = source()
        val call = Regex("""areAllEffectsSupported\(([^)]*)\)""", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.groupValues?.get(1)
            ?: throw AssertionError("no areAllEffectsSupported call found")
        for (e in listOf("EFFECT_TICK", "EFFECT_CLICK", "EFFECT_HEAVY_CLICK")) {
            assertTrue("$e is not part of the support check: $call", call.contains(e))
        }
    }
}
