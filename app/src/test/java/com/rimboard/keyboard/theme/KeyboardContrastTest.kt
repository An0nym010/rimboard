package com.rimboard.keyboard.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The contrast of the colours a user reads on every keystroke.
 *
 * [AppThemeContrastTest] holds the *app* theme's pairs to 4.5:1 and explains
 * why the keyboard's own palette needed no such check: "the keyboard's own
 * palette has carried an `accent`/`onAccent` pair since it was written and
 * [KeyboardTheme] treats them as one thing". That is an argument about the pair
 * being *declared together*, which stopped the bug that test was written for —
 * half a pair overridden in XML while the other half came from Material's
 * baseline. It is not an argument about the ratio between them, and nothing
 * measured that.
 *
 * Measured now, over the eighteen themes the settings screen offers. The one
 * that matters most is fine everywhere: a key's letter on its key runs from
 * 10.86:1 to 17.07:1, and no theme comes close to trouble. Two pairs do not:
 *
 *     onAccent on accent      mint 3.20   sand 3.67   rose 3.86
 *                             light 3.88  peach 4.01
 *     keyHint on keyBg        dark 3.97   mint 4.03   sky 4.34
 *
 * Both carry body text rather than only large glyphs, which is what decides
 * whether 4.5:1 or 3:1 is the bar. `onAccent` letters the edit panel's button
 * labels (`EditPanelView`), the selected item in a long-press popup, and a
 * pinned tool in the toolbar. `keyHint` letters the emoji search field's hint
 * and clear button, the emoji category tabs, and the clipboard's empty-state
 * label. At those sizes WCAG AA asks 4.5:1 and eight pairs are under it.
 *
 * ## What this asserts, and what it leaves alone
 *
 * Restyling somebody's themes is a visual decision and not one to take from a
 * spreadsheet, so the eight are pinned at the value they have rather than
 * changed. What the test refuses is a *new* theme below 4.5:1, and any of the
 * eight getting worse. The floor for everything else is the same 4.5:1 the app
 * theme answers to, because it is the same standard about the same thing.
 *
 * Read as text rather than by resolving a theme, for the reason the sibling
 * test gives about itself: `Themes.resolve` wants a `Context`, the fixed
 * palettes are private, and the failure this guards is a literal in a Kotlin
 * file.
 */
class KeyboardContrastTest {

    private fun source(): String =
        listOf(
            File("src/main/java/com/rimboard/keyboard/theme/Theme.kt"),
            File("app/src/main/java/com/rimboard/keyboard/theme/Theme.kt")
        ).first { it.isFile }.readText()

    private fun luminance(c: Int): Double {
        fun channel(v: Int): Double {
            val f = v / 255.0
            return if (f <= 0.03928) f / 12.92 else Math.pow((f + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(c shr 16 and 0xFF) +
            0.7152 * channel(c shr 8 and 0xFF) +
            0.0722 * channel(c and 0xFF)
    }

    private fun ratio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** Every `private fun name() = KeyboardTheme(...)` and its colour fields. */
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

    /** The pairs a user reads, foreground to background. */
    private val pairs = listOf(
        "keyText" to "keyBg",
        "keyText" to "keyBgFunc",
        "keyHint" to "keyBg",
        "stripText" to "background",
        "onAccent" to "accent"
    )

    /**
     * Pairs that ship under the bar, at the value they ship at.
     *
     * Pinned rather than fixed: each is a colour somebody chose, and the cure
     * is a design decision. They may not get worse, and nothing may join them.
     */
    private val known = mapOf(
        "mint onAccent/accent" to 3.20,
        "sand onAccent/accent" to 3.67,
        "rose onAccent/accent" to 3.86,
        "light onAccent/accent" to 3.88,
        "peach onAccent/accent" to 4.01,
        "dark keyHint/keyBg" to 3.97,
        "mint keyHint/keyBg" to 4.03,
        "sky keyHint/keyBg" to 4.34
    )

    @Test
    fun `every theme's lettering clears the bar the app theme answers to`() {
        val all = themes()
        assertTrue("no themes parsed out of Theme.kt, so this measures nothing", all.size >= 15)
        val report = StringBuilder()
        val failed = ArrayList<String>()
        for ((name, f) in all) {
            for ((fg, bg) in pairs) {
                val a = f[fg] ?: continue
                val b = f[bg] ?: continue
                val r = ratio(a, b)
                val key = "$name $fg/$bg"
                val floor = known[key] ?: 4.5
                if (r < floor - 0.005) failed.add("%s %.2f (allowed %.2f)".format(key, r, floor))
                if (r < 4.5) report.append("    %-28s %.2f%n".format(key, r))
            }
        }
        println("keyboard theme pairs under 4.5:1\n$report")
        assertTrue(
            "a keyboard theme colour pair is below the contrast it shipped with, or a " +
                "new one is below 4.5:1. These letter body text -- emoji search hints, " +
                "edit-panel buttons, clipboard labels -- so 4.5 is the bar: $failed",
            failed.isEmpty()
        )
    }

    @Test
    fun `the pair that matters most is comfortable everywhere`() {
        // A key's letter on its own key. Nothing else on the keyboard is read
        // as often, and it is the one pair with room to spare in every theme --
        // so this is a floor well above AA, which would catch a new theme that
        // was merely acceptable rather than clear.
        var worst = Double.MAX_VALUE
        var where = ""
        for ((name, f) in themes()) {
            val t = f["keyText"] ?: continue
            val k = f["keyBg"] ?: continue
            val r = ratio(t, k)
            if (r < worst) { worst = r; where = name }
        }
        println("worst key lettering: %s at %.2f:1".format(where, worst))
        assertTrue(
            "key lettering fell to %.2f:1 in %s; every theme was above 10:1"
                .format(worst, where),
            worst >= 8.0
        )
    }
}
