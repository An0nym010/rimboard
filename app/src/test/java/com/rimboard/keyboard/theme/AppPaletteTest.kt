package com.rimboard.keyboard.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Picking an app's colour out of its icon.
 *
 * The cases that matter are the ordinary shapes of real launcher icons: a
 * brand mark on a white field, a mark on a transparent field, a monochrome
 * mark, and a red one — red sitting on the 0/360 seam where averaging hues
 * naively gives the opposite colour.
 */
class AppPaletteTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int) =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun field(count: Int, color: Int, plus: Pair<Int, Int>? = null): IntArray {
        val out = IntArray(count) { color }
        plus?.let { (n, c) -> for (i in 0 until n) out[i] = c }
        return out
    }

    private fun near(expected: Int, actual: Int?, tolerance: Int = 12) {
        assertTrue("expected a hue near $expected, got $actual", actual != null)
        val d = Math.abs(expected - actual!!)
        assertTrue(
            "hue $actual is not within $tolerance of $expected",
            minOf(d, 360 - d) <= tolerance
        )
    }

    @Test
    fun `a coloured mark on a white field wins over the white`() {
        // White is the majority of the pixels and says nothing about which app
        // this is. It is the saturation *floor* that excludes it, not the
        // weighting — a distinction worth stating, because the first version of
        // this comment credited the wrong mechanism and the test passed either
        // way. See the pale-wash case below for what the weighting really does.
        val white = argb(255, 255, 255, 255)
        val green = argb(255, 37, 211, 102)
        near(142, AppPalette.dominantHue(field(1024, white, 120 to green)))
    }

    @Test
    fun `a small emphatic mark beats a larger pale wash`() {
        // Both clear the saturation floor, so only the weighting separates
        // them: 300 pale pixels against 100 vivid ones is the majority for the
        // wash and the answer for the mark. What identifies an app is the
        // colour it uses emphatically, not the colour it uses most — an icon
        // with a tinted background and a strong glyph is the common shape.
        val pale = argb(255, 190, 215, 235)     // blue, saturation ~0.19… lifted below
        val paleBlue = argb(255, 150, 195, 230) // ~0.35 saturation, hue ~205
        val vivid = argb(255, 255, 120, 0)      // ~1.0 saturation, hue ~28
        val px = IntArray(400) { paleBlue }
        for (i in 0 until 100) px[i] = vivid
        near(28, AppPalette.dominantHue(px))
        // And the majority does win when the two are comparably saturated,
        // which is what stops this from simply always picking the loudest pixel.
        val px2 = IntArray(400) { paleBlue }
        for (i in 0 until 100) px2[i] = pale
        near(205, AppPalette.dominantHue(px2))
    }

    @Test
    fun `a colour split across the seam is not beaten by a tidier rival`() {
        // Red straddles 0/360, so it falls in the first and last buckets and
        // each half competes alone. 150 + 150 red against a single block of
        // 200 blue: counted per bucket the blue wins, which is the wrong
        // answer about an icon that is plainly red.
        val clear = argb(0, 0, 0, 0)
        val red1 = argb(255, 220, 20, 30)   // hue ~357
        val red2 = argb(255, 230, 40, 20)   // hue ~5
        val blue = argb(255, 30, 90, 220)   // hue ~222
        val px = IntArray(1024) { clear }
        for (i in 0 until 150) px[i] = red1
        for (i in 150 until 300) px[i] = red2
        for (i in 300 until 500) px[i] = blue
        val h = AppPalette.dominantHue(px)
        assertTrue("expected a red hue, got $h", h != null && (h > 335 || h < 25))
    }

    @Test
    fun `transparent padding is ignored`() {
        val clear = argb(0, 255, 255, 255)
        val blue = argb(255, 34, 158, 217)
        near(197, AppPalette.dominantHue(field(1024, clear, 200 to blue)))
    }

    @Test
    fun `a red mark does not average to cyan`() {
        // Reds straddle 0 and 360. Averaging the numbers rather than the angles
        // puts the answer at 180 — the opposite colour — which is the whole
        // reason the buckets carry sines and cosines.
        val white = argb(255, 255, 255, 255)
        val red1 = argb(255, 220, 20, 30)   // hue ~357
        val red2 = argb(255, 230, 40, 20)   // hue ~5
        val px = field(1024, white, 200 to red1)
        for (i in 200 until 400) px[i] = red2
        val h = AppPalette.dominantHue(px)
        assertTrue("expected a red hue, got $h", h != null && (h > 340 || h < 20))
    }

    @Test
    fun `a monochrome icon has no dominant hue`() {
        // Better to say so and let the caller fall back to the package-name
        // hue, which at least tells two black-and-white icons apart.
        val black = argb(255, 20, 20, 20)
        val white = argb(255, 245, 245, 245)
        assertNull(AppPalette.dominantHue(field(512, black)))
        assertNull(AppPalette.dominantHue(field(512, white, 100 to black)))
    }

    @Test
    fun `an empty or fully transparent icon has no hue`() {
        assertNull(AppPalette.dominantHue(IntArray(0)))
        assertNull(AppPalette.dominantHue(field(256, argb(0, 0, 0, 0))))
    }

    @Test
    fun `the same pixels always give the same hue`() {
        val px = field(512, argb(255, 255, 255, 255), 60 to argb(255, 240, 138, 30))
        assertEquals(AppPalette.dominantHue(px), AppPalette.dominantHue(px))
    }

    @Test
    fun `the curated list is a list of package names and holds no duplicates`() {
        // It is the whole of the restraint on the default setting, so it is
        // worth knowing it says what it means to. A typo here is invisible:
        // the app simply never matches and quietly keeps the hash colour,
        // which looks exactly like the feature working as designed.
        assertTrue("curated list looks too short", AppPalette.CURATED.size >= 30)
        for (p in AppPalette.CURATED) {
            assertTrue("'$p' is not a package name", p.contains('.') && !p.contains(' '))
            assertTrue("'$p' has stray case or punctuation", p.trim() == p)
        }
    }

    @Test
    fun `the same app in light and in dark is not the same cache entry`() {
        // The bug this exists for: both answers are read out of the app's
        // resolved theme, and another package's resources resolve through this
        // process's configuration — so an app with `-night` resources says
        // "light" while the system is light and "dark" once it is not. Keyed on
        // the package alone, the first answer was cached and kept being served
        // after the system had flipped, and `resolve` turns a stale "the app is
        // light" into "keep the keyboard light". The visible failure is a white
        // keyboard under a black app, holding out against the system's own
        // night setting — which is exactly what the keyboard would have
        // followed had it read nothing at all.
        assertNotEquals(
            AppPalette.cacheKey("com.whatsapp", curatedOnly = true, night = false),
            AppPalette.cacheKey("com.whatsapp", curatedOnly = true, night = true)
        )
    }

    @Test
    fun `each of the three things the answer depends on changes the key`() {
        // Two packages, both settings, both polarities: eight questions, and
        // eight answers that must not be filed on top of one another.
        val keys = mutableListOf<String>()
        for (pkg in listOf("com.whatsapp", "com.discord")) {
            for (curated in listOf(true, false)) {
                for (night in listOf(true, false)) {
                    keys += AppPalette.cacheKey(pkg, curated, night)
                }
            }
        }
        assertEquals("keys collide: $keys", keys.size, keys.toSet().size)
    }

    @Test
    fun `the key stays keyed on the whole package name`() {
        // Cheap to get wrong while adding a field to the front of a key, and
        // it fails the way this whole class of bug fails: silently, as one app
        // wearing another's colour.
        assertTrue(
            AppPalette.cacheKey("com.whatsapp", curatedOnly = true, night = false)
                .endsWith("com.whatsapp")
        )
        assertNotEquals(
            AppPalette.cacheKey("com.whatsapp.w4b", curatedOnly = true, night = false),
            AppPalette.cacheKey("com.whatsapp", curatedOnly = true, night = false)
        )
    }

    @Test
    fun `a hue is always a legal one`() {
        for (r in 0..255 step 51) for (g in 0..255 step 51) for (b in 0..255 step 51) {
            val h = AppPalette.dominantHue(field(64, argb(255, r, g, b))) ?: continue
            assertTrue("hue out of range for rgb($r,$g,$b): $h", h in 0..359)
        }
    }
}
