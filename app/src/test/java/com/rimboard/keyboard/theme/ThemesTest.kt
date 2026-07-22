package com.rimboard.keyboard.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
