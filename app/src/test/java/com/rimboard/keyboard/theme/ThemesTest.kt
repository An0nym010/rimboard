package com.rimboard.keyboard.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The photo-background theme variant is what keeps lettering readable on top
 * of an arbitrary picture, so its polarity rule is worth pinning: it must
 * follow the *effective* surface — the image's luminance after the dim
 * overlay has done its work — not the raw image.
 */
class ThemesTest {

    private val base = KeyboardTheme(
        background = 0xFF101010.toInt(), keyBg = 0xFF202020.toInt(),
        keyBgFunc = 0xFF181818.toInt(), keyBgPressed = 0xFF303030.toInt(),
        keyText = 0xFFEEEEEE.toInt(), keyHint = 0xFF888888.toInt(),
        accent = 0xFF3E7BFA.toInt(), onAccent = 0xFFFFFFFF.toInt(),
        stripText = 0xFFDDDDDD.toInt(), previewBg = 0xFF262626.toInt(),
        isDark = true
    )

    @Test
    fun `dark photo gets light lettering and a bright photo the reverse`() {
        val onDark = Themes.overPhoto(base, luma = 40, dimAlpha = 0)
        assertTrue(onDark.isDark)
        assertEquals(0xFFFFFFFF.toInt(), onDark.keyText)
        // The strip sits on the same photo, so its text must follow the keys'.
        assertEquals(onDark.keyText, onDark.stripText)

        val onBright = Themes.overPhoto(base, luma = 220, dimAlpha = 0)
        assertFalse(onBright.isDark)
        // Dark lettering: fully opaque, channels near black.
        assertEquals(0xFF, onBright.keyText ushr 24)
        assertTrue((onBright.keyText and 0xFF) < 0x40)
    }

    @Test
    fun `a bright photo under heavy dim is a dark surface by draw time`() {
        // Luminance 220, but an 80% dim: what the letters actually sit on is
        // dark, so they must be light — polarity from the raw image would put
        // black text on a nearly black picture.
        val t = Themes.overPhoto(base, luma = 220, dimAlpha = 204)
        assertTrue(t.isDark)
        assertEquals(0xFFFFFFFF.toInt(), t.keyText)
    }

    @Test
    fun `a panel over a photo ghosts it without becoming unreadable`() {
        val p = Themes.panelOverPhoto(base)
        val alpha = p.background ushr 24
        // See-through enough that the photo carries on behind the panel...
        assertTrue("panel must not be opaque", alpha < 0xFF)
        // ...but dense content sits on it, so not so far that an emoji grid or
        // a list of clipboard text has to compete with the picture.
        assertTrue("panel must stay mostly opaque", alpha >= 0xC0)
        // The surface stays the base colour, which is what lets every other
        // colour in the theme carry over untouched.
        assertEquals(base.background and 0x00FFFFFF, p.background and 0x00FFFFFF)
        assertEquals(base.keyText, p.keyText)
        assertEquals(base.stripText, p.stripText)
        assertEquals(base.accent, p.accent)
        assertEquals(base.keyBg, p.keyBg)
    }

    @Test
    fun `caps are translucent scrims and solid surfaces keep the base theme`() {
        val t = Themes.overPhoto(base, luma = 40, dimAlpha = 0)
        assertTrue("cap must be see-through", (t.keyBg ushr 24) < 0x80)
        assertTrue((t.keyBgFunc ushr 24) < 0x80)
        assertTrue((t.keyBgPressed ushr 24) < 0xFF)
        // Enter/caps-lock stay solid accent, and popups sit above the photo so
        // they keep an opaque, readable surface.
        assertEquals(base.accent, t.accent)
        assertEquals(base.onAccent, t.onAccent)
        assertEquals(base.previewBg, t.previewBg)
        assertEquals(base.background, t.background)
    }

    // ---- per-app tinting

    @Test
    fun `the same app always gets the same hue and different apps differ`() {
        assertEquals(Themes.hueFor("com.whatsapp"), Themes.hueFor("com.whatsapp"))
        assertNotEquals(Themes.hueFor("com.whatsapp"), Themes.hueFor("org.telegram.messenger"))
    }

    @Test
    fun `names differing only at the end are not herded together`() {
        // Package families differ near the end of the name, and the hue is the
        // hash's low bits, so this is where a weak hash shows. It has to be a
        // *statistical* claim: no hash can promise two given hues are far
        // apart — with four values drawn from 360 a near-miss is ordinary
        // chance, and asserting on one family would only pin that chance.
        // What separates a good hash from a bad one is the average.
        //
        // Measured: 1° for String.hashCode (consecutive hues, every time),
        // 2.4° for bare FNV-1a, 21.8° once the murmur3 finalizer folds the
        // high bits down — the same as 21.7° for genuinely random hues. The
        // threshold sits well above both failing designs and well below the
        // random ceiling, so it catches a regression to either.
        var total = 0
        val families = 2000
        for (i in 0 until families) {
            val hues = listOf('1', '2', '3', '4').map { Themes.hueFor("com.vendor$i.app$it") }
            var closest = 360
            for (a in hues.indices) {
                for (b in a + 1 until hues.size) {
                    val d = kotlin.math.abs(hues[a] - hues[b])
                    closest = minOf(closest, d, 360 - d)
                }
            }
            total += closest
        }
        val mean = total.toDouble() / families
        assertTrue("sibling packages average only $mean apart in hue", mean > 10.0)
    }

    @Test
    fun `a hue is always a legal one`() {
        // A negative or out-of-range hue would fall through hsl()'s `when` to
        // the else branch and silently produce the wrong colour, so this pins
        // the range over inputs that overflow the multiply.
        for (p in listOf("", "a", "com.a", "x".repeat(400), "🙂", "com.ünïcodé.app")) {
            val h = Themes.hueFor(p)
            assertTrue("hue out of range for '$p': $h", h in 0..359)
        }
    }

    @Test
    fun `tinting moves the accent far and the surfaces barely`() {
        val t = Themes.forApp(base, "com.whatsapp")
        assertNotEquals("the accent is the point", base.accent, t.accent)
        // The background is most of the screen; saturating it reads as a
        // fault, not a theme. Allow a few steps per channel, no more.
        for (sh in listOf(16, 8, 0)) {
            val d = kotlin.math.abs((base.background shr sh and 0xFF) - (t.background shr sh and 0xFF))
            assertTrue("background moved $d on channel $sh", d <= 24)
        }
        // Contrast against the caps is the base theme's design and must not be
        // renegotiated by whatever hue came out.
        assertEquals(base.keyText, t.keyText)
        assertEquals(base.keyHint, t.keyHint)
        assertEquals(base.stripText, t.stripText)
        assertEquals(base.isDark, t.isDark)
    }

    @Test
    fun `accent lettering stays readable on every hue`() {
        // onAccent is picked from the accent's luminance, so the guarantee has
        // to hold for all 360 of them, in both polarities.
        for (light in listOf(base, base.copy(isDark = false))) {
            for (p in 0 until 360) {
                val t = Themes.forApp(light, "pkg$p")
                val la = lum(t.accent)
                val lo = lum(t.onAccent)
                assertTrue(
                    "accent ${Integer.toHexString(t.accent)} vs text " +
                        "${Integer.toHexString(t.onAccent)}",
                    kotlin.math.abs(la - lo) > 0.35
                )
                assertEquals("accent must be opaque", 0xFF, t.accent ushr 24)
            }
        }
    }

    @Test
    fun `no package means no change at all`() {
        // onStartInputView can hand over a null package name; the keyboard
        // must then look exactly as the user configured it.
        assertEquals(base, Themes.forApp(base, null))
        assertEquals(base, Themes.forApp(base, ""))
    }

    @Test
    fun `themes whose colours were chosen on purpose are never tinted`() {
        // High contrast is an accessibility setting, the custom slots are
        // hand-picked, and dynamic already tracks the wallpaper. Overriding
        // any of them would defeat the reason it was selected.
        for (p in listOf("contrast", "custom", "custom2", "custom3", "dynamic")) {
            assertFalse("$p must not be tinted", Themes.tintable(p))
        }
        for (p in listOf("system", "light", "dark", "amoled", "ocean", "rose")) {
            assertTrue("$p should be tinted", Themes.tintable(p))
        }
    }

    private fun lum(c: Int): Double =
        0.299 * (c shr 16 and 0xFF) / 255.0 +
            0.587 * (c shr 8 and 0xFF) / 255.0 +
            0.114 * (c and 0xFF) / 255.0
}
