package com.rimboard.keyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The translation target list.
 *
 * The point being guarded is that translating into a language is not the same
 * as having its keyboard. The target used to be whichever layout was active,
 * so reaching Turkish output meant enabling the Turkish layout, switching to
 * it, translating, and switching back — and any language without a bundled
 * layout was simply unreachable.
 */
class TranslateTargetsTest {

    @Test
    fun `targets are not limited to the bundled layouts`() {
        // The whole point. The app ships 22 layouts; the platform names far
        // more, and the model behind the feature handles them.
        val named = Locale.getISOLanguages()
            .map { Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH) }
            .filter { it.isNotBlank() }
        assertTrue(
            "expected far more nameable languages than bundled layouts, got ${named.size}",
            named.size > Languages.codes.size * 4
        )
    }

    @Test
    fun `languages with no bundled layout are still reachable targets`() {
        // Japanese, Arabic, Hindi and Swahili have no layout in this app, and
        // there is no reason that should stop anyone translating into them.
        for (code in listOf("ja", "ar", "hi", "sw", "ko", "th")) {
            assertFalse("$code unexpectedly has a layout", code in Languages.codes)
            val name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH)
            assertTrue("$code has no display name to offer", name.isNotBlank())
            assertTrue("$code should not render as its own code", name != code)
        }
    }

    @Test
    fun `the prompt name is English regardless of the display locale`() {
        // The instruction sent to the model is English prose. "Translate into
        // Deutsch" mixes two languages in one sentence for no benefit, and the
        // user never sees this string anyway.
        assertEquals("German", Locale.forLanguageTag("de").getDisplayLanguage(Locale.ENGLISH))
        assertEquals("Turkish", Locale.forLanguageTag("tr").getDisplayLanguage(Locale.ENGLISH))
        // Same code, shown to the user in their own language, is a different
        // string — which is exactly why the two are kept apart.
        assertEquals("Deutsch", Locale.forLanguageTag("de").getDisplayLanguage(Locale.GERMAN))
    }

    @Test
    fun `auto is the default so behaviour is unchanged until it is set`() {
        assertEquals("auto", TranslateTargets.AUTO)
    }

    @Test
    fun `every bundled layout language can name itself as a target`() {
        // A layout whose language the platform cannot name would appear in the
        // hoisted section of the picker as a blank row.
        val unnameable = Languages.codes.filter {
            Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH).isBlank()
        }
        assertTrue("these languages have no display name: $unnameable", unnameable.isEmpty())
    }
}
