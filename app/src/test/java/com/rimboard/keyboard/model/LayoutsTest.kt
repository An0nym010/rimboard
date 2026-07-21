package com.rimboard.keyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Structural guards over the 22 hand-written layouts.
 *
 * These are edited by hand whenever a language is added or a row is tweaked,
 * and a slip is not subtle at runtime — a layout without a backspace is a
 * keyboard you cannot correct a typo on. Nothing checked any of it.
 */
class LayoutsTest {

    private fun mainLayouts(numberRow: Boolean = false, globe: Boolean = false) =
        Languages.codes.map { it to Languages.byCode(it).layout(numberRow, globe) }

    private fun KeyboardLayout.codes() = rows.flatMap { it.keys }.map { it.code }

    private fun KeyboardLayout.letters() = rows.flatMap { it.keys }
        .filter { it.type == KeyType.CHARACTER && it.label.length == 1 && it.label[0].isLetter() }
        .map { it.label[0] }

    @Test
    fun `every language layout has the keys you cannot type without`() {
        val missing = ArrayList<String>()
        for ((code, lay) in mainLayouts()) {
            val codes = lay.codes()
            for ((name, needed) in listOf(
                "space" to Codes.SPACE,
                "enter" to Codes.ENTER,
                "backspace" to Codes.BACKSPACE,
                "shift" to Codes.SHIFT,
                "symbols" to Codes.MODE_SYM
            )) {
                if (needed !in codes) missing.add("$code: no $name")
            }
        }
        assertTrue(missing.joinToString("\n"), missing.isEmpty())
    }

    @Test
    fun `no language repeats a letter on its own layout`() {
        val problems = ArrayList<String>()
        for ((code, lay) in mainLayouts()) {
            val dupes = lay.letters().groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            if (dupes.isNotEmpty()) problems.add("$code: repeats $dupes")
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `every language offers a usable number of letters`() {
        val thin = mainLayouts().filter { (_, lay) -> lay.letters().size < 20 }
            .map { (code, lay) -> "$code has ${lay.letters().size}" }
        assertTrue("implausibly few letters: $thin", thin.isEmpty())
    }

    @Test
    fun `the number row is added only when asked for`() {
        for ((code, plain) in mainLayouts(numberRow = false)) {
            val withRow = Languages.byCode(code).layout(true, false)
            assertTrue(
                "$code: number row did not add a row",
                withRow.rows.size == plain.rows.size + 1
            )
            val digits = withRow.rows.first().keys.map { it.label }
            assertEquals("$code: first row is not 1-0", "1234567890", digits.joinToString(""))
        }
    }

    @Test
    fun `the globe key appears only when more than one language is enabled`() {
        for (code in Languages.codes) {
            val without = Languages.byCode(code).layout(false, false).codes()
            val with = Languages.byCode(code).layout(false, true).codes()
            assertTrue("$code: globe present when not requested", Codes.LANG !in without)
            assertTrue("$code: globe missing when requested", Codes.LANG in with)
        }
    }

    @Test
    fun `layout locale matches the language it belongs to`() {
        for (code in Languages.codes) {
            val lang = Languages.byCode(code)
            assertEquals(code, lang.locale.language, lang.layout(false, false).locale.language)
        }
    }

    @Test
    fun `the symbol and numpad planes carry their own escapes`() {
        // Every non-letter plane needs a way back, or the user is stranded.
        for (lay in listOf(
            Layouts.symbols(Locale.ENGLISH),
            Layouts.symbols2(Locale.ENGLISH),
            Layouts.numpad(Locale.ENGLISH)
        )) {
            val codes = lay.codes()
            assertTrue("no way back to letters", Codes.MODE_ABC in codes)
            assertTrue("no backspace", Codes.BACKSPACE in codes)
            assertTrue("no enter", Codes.ENTER in codes)
        }
    }

    @Test
    fun `popup alternatives are never declared empty`() {
        // An empty popup list opens a panel with nothing in it.
        val problems = ArrayList<String>()
        for ((code, lay) in mainLayouts(globe = true)) {
            for (k in rowsKeys(lay)) {
                if (k.popup.isNotEmpty() && k.popup.any { it.label.isEmpty() }) {
                    problems.add("$code: '${k.label}' has a blank alternative")
                }
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    private fun rowsKeys(lay: KeyboardLayout) = lay.rows.flatMap { it.keys }
}
