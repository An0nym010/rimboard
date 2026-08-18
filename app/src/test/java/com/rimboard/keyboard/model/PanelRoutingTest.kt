package com.rimboard.keyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelRoutingTest {

    @Test
    fun `opening a picker cancels every other picker`() {
        // The bug this exists for: opening the GIF picker cancelled the GIF
        // search and nothing else, so the translate bar's debounce survived and
        // fired into a bar the user had left — a billed request nobody asked
        // for.
        assertEquals(
            setOf(PanelRouting.Picker.EMOJI, PanelRouting.Picker.TRANSLATE),
            PanelRouting.toCancel(PanelRouting.Picker.GIF)
        )
        assertEquals(
            setOf(PanelRouting.Picker.EMOJI, PanelRouting.Picker.GIF),
            PanelRouting.toCancel(PanelRouting.Picker.TRANSLATE)
        )
    }

    @Test
    fun `a picker does not cancel itself`() {
        for (p in listOf(
            PanelRouting.Picker.EMOJI, PanelRouting.Picker.GIF, PanelRouting.Picker.TRANSLATE
        )) {
            assertFalse("$p cancelled its own pending work", p in PanelRouting.toCancel(p))
        }
    }

    @Test
    fun `closing everything cancels all of them`() {
        assertEquals(3, PanelRouting.toCancel(PanelRouting.Picker.NONE).size)
    }

    @Test
    fun `NONE is never something to cancel`() {
        for (p in PanelRouting.Picker.entries) {
            assertFalse(PanelRouting.Picker.NONE in PanelRouting.toCancel(p))
        }
    }

    @Test
    fun `the gif seed survives only while the gif picker is the one opening`() {
        assertFalse(PanelRouting.clearsGifSeed(PanelRouting.Picker.GIF))
        for (p in listOf(
            PanelRouting.Picker.NONE, PanelRouting.Picker.EMOJI, PanelRouting.Picker.TRANSLATE
        )) {
            assertTrue("$p kept a seed belonging to the GIF picker",
                PanelRouting.clearsGifSeed(p))
        }
    }

    @Test
    fun `leaving the translate bar invalidates a reply already on the wire`() {
        // Cancelling the debounce only stops what has not been sent.
        assertFalse(PanelRouting.abandonsTranslate(PanelRouting.Picker.TRANSLATE))
        assertTrue(PanelRouting.abandonsTranslate(PanelRouting.Picker.GIF))
        assertTrue(PanelRouting.abandonsTranslate(PanelRouting.Picker.NONE))
    }

    @Test
    fun `opening a panel hides the others and keeps itself`() {
        assertEquals(
            listOf(PanelRouting.Panel.EDIT, PanelRouting.Panel.TOOLS),
            PanelRouting.panelsToHide(PanelRouting.Panel.CLIPBOARD)
        )
        assertEquals(3, PanelRouting.panelsToHide(null).size)
    }
}
