package com.rimboard.keyboard.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides who paints the background.
 *
 * Every case here was true of the shipped app except one, and that one is the
 * bug: with a live background chosen and no photo, the backdrop drew a sky and
 * the keyboard painted over it, so the setting appeared to do nothing at all.
 * The keyboard covers nearly the whole backdrop, so "nothing at all" is exactly
 * what a user sees.
 *
 * There is no unit test that can see a `Canvas`, which is why this was found by
 * screenshotting a phone rather than by the 1,181 tests that were passing. What
 * *can* be pinned is the rule the three drawing surfaces have to agree about,
 * and that is this file.
 */
class BackdropTest {

    @Test
    fun `a live background with no photo is drawn, and nothing may cover it`() {
        // The case that was broken.
        assertTrue(Backdrop.liveVisible(hasPhoto = false, liveMode = "stars"))
        assertTrue(Backdrop.surfacesTransparent(hasPhoto = false, liveMode = "stars"))
        assertTrue(Backdrop.liveVisible(hasPhoto = false, liveMode = "particles"))
        assertTrue(Backdrop.surfacesTransparent(hasPhoto = false, liveMode = "particles"))
    }

    @Test
    fun `a photo outranks the sky and still clears the surfaces`() {
        // Two moving backgrounds behind one set of keys is one too many, and
        // the photo is the more deliberate choice. PhotoBackdrop has always
        // taken this view; the point here is that the keys must still get out
        // of the way, which is the half that already worked.
        assertFalse(Backdrop.liveVisible(hasPhoto = true, liveMode = "stars"))
        assertTrue(Backdrop.surfacesTransparent(hasPhoto = true, liveMode = "stars"))
    }

    @Test
    fun `a photo alone clears the surfaces`() {
        assertFalse(Backdrop.liveVisible(hasPhoto = true, liveMode = Backdrop.NONE))
        assertTrue(Backdrop.surfacesTransparent(hasPhoto = true, liveMode = Backdrop.NONE))
    }

    @Test
    fun `with neither, the keyboard paints its own background as it always has`() {
        // The ordinary case, and the one that must not regress: without this
        // the keys would sit on whatever the backdrop's flat colour happens to
        // be and lose the raised-key gradient for no reason at all.
        assertFalse(Backdrop.liveVisible(hasPhoto = false, liveMode = Backdrop.NONE))
        assertFalse(Backdrop.surfacesTransparent(hasPhoto = false, liveMode = Backdrop.NONE))
    }

    @Test
    fun `the off value is the one the preference actually stores`() {
        // `live_bg_values` in arrays.xml is none/stars/particles, and the
        // preference default is "none". A rename on one side of that and this
        // rule silently reads every keyboard as having a live background.
        assertFalse(Backdrop.liveVisible(hasPhoto = false, liveMode = "none"))
        assertTrue(Backdrop.NONE == "none")
    }
}
