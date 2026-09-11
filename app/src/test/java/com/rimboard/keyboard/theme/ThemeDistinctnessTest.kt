package com.rimboard.keyboard.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.sqrt

/**
 * Two themes with different names should be two themes.
 *
 * Twenty named palettes were picked one at a time, and nothing ever asked
 * whether the twentieth was already in the list. The theme picker asks it now
 * by drawing them — `ThemePickerActivity` exists partly so a person can see
 * this — but a grid nobody opens is not a guard, and the next palette will be
 * added in a text editor.
 *
 * **Pinned, not fixed**, in the same shape as [KeyboardContrastTest]: each
 * close pair is two colours somebody chose, and merging or restyling them is a
 * design decision. They may not get closer, and nothing new may join them.
 *
 * The measure is the mean straight-line RGB distance across the eight roles a
 * person actually sees, which is crude — it is not perceptual, and it weighs a
 * background the same as an accent. It is enough for the question being asked,
 * which is not "how different do these feel" but "did somebody add the same
 * palette twice". The widest pair in the set scores 350, so a pair under 25 is
 * within about 7% of the space the palettes occupy.
 */
class ThemeDistinctnessTest {

    private fun source(): String {
        for (p in listOf(
            "src/main/java/com/rimboard/keyboard/theme/Theme.kt",
            "app/src/main/java/com/rimboard/keyboard/theme/Theme.kt"
        )) File(p).let { if (it.isFile) return it.readText() }
        throw AssertionError("Theme.kt not found from ${File(".").absolutePath}")
    }

    /** The roles a person reads, which is what "looks the same" is about. */
    private val roles = listOf(
        "background", "keyBg", "keyBgFunc", "keyText",
        "keyHint", "accent", "onAccent", "stripText"
    )

    private fun themes(): Map<String, Map<String, Int>> {
        val out = LinkedHashMap<String, Map<String, Int>>()
        val block = Regex(
            """private fun (\w+)\(\) = KeyboardTheme\((.*?)\n    \)""",
            RegexOption.DOT_MATCHES_ALL
        )
        val field = Regex("""(\w+)\s*=\s*0x([0-9A-Fa-f]{8})""")
        for (m in block.findAll(source())) {
            val fields = field.findAll(m.groupValues[2]).associate {
                it.groupValues[1] to (it.groupValues[2].toLong(16).toInt() and 0xFFFFFF)
            }
            if (fields.isNotEmpty()) out[m.groupValues[1]] = fields
        }
        return out
    }

    private fun distance(a: Map<String, Int>, b: Map<String, Int>): Double {
        var total = 0.0
        var n = 0
        for (role in roles) {
            val x = a[role] ?: continue
            val y = b[role] ?: continue
            val dr = ((x shr 16 and 0xFF) - (y shr 16 and 0xFF)).toDouble()
            val dg = ((x shr 8 and 0xFF) - (y shr 8 and 0xFF)).toDouble()
            val db = ((x and 0xFF) - (y and 0xFF)).toDouble()
            total += sqrt(dr * dr + dg * dg + db * db)
            n++
        }
        return if (n == 0) Double.MAX_VALUE else total / n
    }

    /** Pairs that ship close together, at the distance they ship at. */
    private val known = mapOf(
        "crimson/sunset" to 12.7,
        "light/sky" to 14.0,
        "peach/sand" to 14.0,
        "paper/sage" to 15.0,
        "mint/sage" to 15.3,
        "light/lilac" to 19.8,
        "graphite/sunset" to 21.6,
        "peach/rose" to 22.0,
        "lilac/sky" to 22.9,
        "dark/ocean" to 23.6,
        "sage/sand" to 23.7,
        "mint/paper" to 24.8
    )

    private val bar = 25.0

    @Test
    fun `no new pair of themes is a near-duplicate of another`() {
        val t = themes()
        assertTrue("no palettes parsed; the regex has come away from the file",
            t.size >= 15)
        val names = t.keys.sorted()
        val tooClose = mutableListOf<String>()
        for (i in names.indices) for (j in i + 1 until names.size) {
            val key = "${names[i]}/${names[j]}"
            val d = distance(t[names[i]]!!, t[names[j]]!!)
            if (d < bar && key !in known) tooClose += "$key at %.1f".format(d)
        }
        assertTrue(
            "these two palettes are close enough that the picker shows them " +
                "as the same keyboard twice. Either they are one theme, or " +
                "one of them wants pulling apart: $tooClose",
            tooClose.isEmpty()
        )
    }

    @Test
    fun `the pairs that already overlap do not get closer`() {
        val t = themes()
        val worse = mutableListOf<String>()
        for ((key, was) in known) {
            val (a, b) = key.split("/")
            val pa = t[a] ?: continue
            val pb = t[b] ?: continue
            val now = distance(pa, pb)
            if (now < was - 0.2) worse += "$key %.1f -> %.1f".format(was, now)
        }
        assertTrue(
            "a pair that was already hard to tell apart got harder: $worse",
            worse.isEmpty()
        )
    }
}
