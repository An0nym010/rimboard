package com.rimboard.keyboard.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app theme's colour *pairs*, and the rule that they are pairs.
 *
 * `Theme.RimBoard` overrode `colorPrimary` to the RimBoard blue and inherited
 * everything else from Material 3. That left the **label** on every filled
 * button at M3's baseline, which in the dark theme is a dark purple — so the
 * three buttons on the setup screen, the first thing a new install shows, were
 * #381E72 on #1A73E8. Measured off a device screenshot: **2.92:1**, against the
 * 4.5:1 WCAG AA asks for text that size.
 *
 * It survived because nothing here runs a theme. The keyboard's own palette has
 * carried an `accent`/`onAccent` pair since it was written and
 * [com.rimboard.keyboard.theme.KeyboardTheme] treats them as one thing; the
 * *app* theme is XML, where half a pair can be overridden and the other half
 * silently comes from somewhere else.
 *
 * So this reads the resource file rather than a Kotlin object. It is a
 * text-level check and deliberately so: the failure was a missing line in an
 * XML file, and that is exactly what it looks for.
 */
class AppThemeContrastTest {

    /** Foreground/background attribute pairs Material resolves together. */
    private val pairs = listOf(
        "colorPrimary" to "colorOnPrimary",
        "colorSecondary" to "colorOnSecondary",
        "colorTertiary" to "colorOnTertiary",
        "colorError" to "colorOnError",
        "colorSurface" to "colorOnSurface",
        "colorPrimaryContainer" to "colorOnPrimaryContainer"
    )

    private fun themesXml(): String =
        listOf(
            File("src/main/res/values/themes.xml"),
            File("app/src/main/res/values/themes.xml")
        ).first { it.isFile }.readText()

    /** Every `<item name="x">#RRGGBB</item>` in the file. */
    private fun items(xml: String): Map<String, String> =
        Regex("""<item\s+name="([^"]+)"\s*>\s*(#[0-9A-Fa-f]{6,8})\s*</item>""")
            .findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2] }

    private fun channels(hex: String): Triple<Int, Int, Int> {
        val h = hex.removePrefix("#").let { if (it.length == 8) it.substring(2) else it }
        return Triple(
            h.substring(0, 2).toInt(16),
            h.substring(2, 4).toInt(16),
            h.substring(4, 6).toInt(16)
        )
    }

    /** WCAG relative luminance. */
    private fun luminance(hex: String): Double {
        val (r, g, b) = channels(hex)
        fun f(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b)
    }

    private fun contrast(a: String, b: String): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    @Test
    fun `overriding a colour also overrides the one written on top of it`() {
        val declared = items(themesXml())
        val split = pairs.filter { (bg, fg) -> bg in declared && fg !in declared }
        assertTrue(
            "Theme.RimBoard sets ${split.joinToString { it.first }} but not " +
                "${split.joinToString { it.second }}. Material 3 will supply the " +
                "missing half from its own baseline palette, which is purple in " +
                "the dark theme — that is how the setup screen shipped with " +
                "#381E72 lettering on a blue button. Set both or neither.",
            split.isEmpty()
        )
    }

    @Test
    fun `each declared pair is readable`() {
        val declared = items(themesXml())
        val report = StringBuilder()
        var worst = Double.MAX_VALUE
        for ((bg, fg) in pairs) {
            val b = declared[bg] ?: continue
            val f = declared[fg] ?: continue
            val c = contrast(f, b)
            worst = minOf(worst, c)
            report.append("%s %s on %s = %.2f:1\n".format(fg, f, b, c))
        }
        assertTrue("no colour pair is declared, so this test measures nothing", worst < Double.MAX_VALUE)
        println(report)
        // 4.5:1 is the WCAG AA bar for text below ~18pt, which button labels are.
        assertTrue(
            "a theme colour pair is below 4.5:1 and its text will be hard to read.\n$report",
            worst >= 4.5
        )
    }

    @Test
    fun `the check would reject the colours that shipped`() {
        // The instrument, verified against the known-bad case rather than
        // trusted. Without this the arithmetic above could be wrong in the
        // safe direction and the test would pass on anything.
        assertTrue(
            "2.92:1 must read as a failure",
            contrast("#381E72", "#1A73E8") < 4.5
        )
        assertTrue(
            "white on the RimBoard blue must read as a pass",
            contrast("#FFFFFF", "#1A73E8") >= 4.5
        )
    }
}
