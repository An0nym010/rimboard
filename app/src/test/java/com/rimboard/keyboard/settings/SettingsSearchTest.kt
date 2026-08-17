package com.rimboard.keyboard.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {

    private fun e(title: String, summary: String = "", screen: String = "Look and feel") =
        SettingsSearch.Entry(
            key = title.lowercase().replace(' ', '_'),
            title = title,
            summary = summary,
            screenTitle = screen,
            screenXml = 1
        )

    private val entries = listOf(
        e("Vibrate on keypress", "Vibrate when a key is pressed"),
        e("Vibration strength", "How strong the vibration is"),
        e("Sound on keypress", "Play a click, which some people prefer to vibration"),
        e("Theme", "Colours of the keyboard"),
        e("Tint strength", "How much of the app's colour the keyboard takes"),
        e("Otomatik düzeltme", "Yazım hatalarını düzelt")
    )

    @Test
    fun `a title match beats a summary match`() {
        // Someone typing "vib" wants the row *called* that, not the one whose
        // description happens to mention it.
        val out = SettingsSearch.search(entries, "vib")
        assertTrue("nothing found", out.isNotEmpty())
        assertTrue(
            "a summary match outranked a title match: ${out.map { it.title }}",
            out.first().title.startsWith("Vibrat")
        )
        assertTrue(
            "the sound row should still be found, but last",
            out.last().title == "Sound on keypress"
        )
    }

    @Test
    fun `a prefix beats a match in the middle`() {
        val out = SettingsSearch.search(entries, "strength")
        // "Vibration strength" and "Tint strength" both contain it; neither
        // starts with it, so the shorter title wins the tie.
        assertEquals("Tint strength", out.first().title)
    }

    @Test
    fun `accents are ignored in both directions`() {
        // The point on a phone: nobody long-presses to type ü to find a setting.
        assertEquals(
            "Otomatik düzeltme",
            SettingsSearch.search(entries, "duzeltme").firstOrNull()?.title
        )
        assertEquals(
            "Otomatik düzeltme",
            SettingsSearch.search(entries, "düzeltme").firstOrNull()?.title
        )
    }

    @Test
    fun `case does not matter`() {
        assertEquals(
            SettingsSearch.search(entries, "theme").map { it.title },
            SettingsSearch.search(entries, "THEME").map { it.title }
        )
    }

    @Test
    fun `an empty or blank query matches nothing rather than everything`() {
        // Showing all fifty settings the moment the field is focused is not a
        // search result, and it hides the screen the user was already on.
        assertTrue(SettingsSearch.search(entries, "").isEmpty())
        assertTrue(SettingsSearch.search(entries, "   ").isEmpty())
    }

    @Test
    fun `a query matching nothing returns nothing`() {
        assertTrue(SettingsSearch.search(entries, "zzzzz").isEmpty())
    }

    @Test
    fun `results are capped`() {
        val many = (1..50).map { e("Setting $it", "contains the word setting") }
        assertTrue(SettingsSearch.search(many, "setting", limit = 12).size <= 12)
    }

    @Test
    fun `folding is stable for the same input`() {
        assertEquals(SettingsSearch.fold("Düzeltme"), SettingsSearch.fold("duzeltme"))
        assertEquals("istanbul", SettingsSearch.fold("İstanbul"))
    }
}
