package com.rimboard.keyboard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Typing "france" into the emoji search returned an empty panel.
 *
 * The search index is a hand-written asset — 420 keywords reaching 571 of the
 * 1,564 emoji the palette shows — and the flags were the largest single hole
 * in it: **229 of the 233 flags could not be found by any query at all.**
 *
 * They are the one hole that needs no writing. A country flag is not a picture
 * the way the other emoji are; it is two regional indicator symbols spelling
 * the region's ISO 3166-1 code, so the flag already carries its own name. And
 * the platform has the table that turns a code into a name, in every language
 * it speaks, which is the same CLDR data Android uses everywhere else.
 *
 * So no names are shipped. What this pins is that the derivation still finds
 * the flags, still refuses everything that is not one, and still declines
 * rather than guesses when the platform has no name for a region.
 */
class FlagSearchTest {

    private fun palette(): List<String> {
        val src = listOf(
            File("src/main/java/com/rimboard/keyboard/ui/EmojiData.kt"),
            File("app/src/main/java/com/rimboard/keyboard/ui/EmojiData.kt")
        ).first { it.exists() }.readText()
        return Regex("""c\("[^"]+",\s*\n\s*"([^"]*)"\)""")
            .findAll(src)
            .flatMap { it.groupValues[1].split(" ").asSequence() }
            .filter { it.isNotBlank() }
            .toList()
    }

    @Test
    fun `a flag emoji spells its own region code`() {
        assertEquals("FR", EmojiData.regionOf("🇫🇷"))
        assertEquals("GB", EmojiData.regionOf("🇬🇧"))
        assertEquals("UA", EmojiData.regionOf("🇺🇦"))
        // Not flags: a waving flag, a pride flag, a tag-sequence flag, a face.
        assertNull(EmojiData.regionOf("🏳️"))
        assertNull(EmojiData.regionOf("🏳️‍🌈"))
        assertNull(EmojiData.regionOf("🏴󠁧󠁢󠁥󠁮󠁧󠁿"))
        assertNull(EmojiData.regionOf("😀"))
        assertNull(EmojiData.regionOf(""))
        // One indicator on its own is a letter, not a flag.
        assertNull(EmojiData.regionOf("🇫"))
    }

    @Test
    fun `the flags in the palette are searchable by country name`() {
        val kw = EmojiData.flagKeywords(Locale.ENGLISH)
        println("sample keys: " + listOf("FR","DE","ES","TR","US","GB","NL","KR","CZ")
            .joinToString(" ") { it + "=" + Locale("", it).getDisplayCountry(Locale.ENGLISH) })
        val missing = listOf(
            "france", "germany", "spain", "italy", "poland", "ukraine",
            "japan", "brazil", "india", "netherlands", "sweden"
        ).filterNot { kw.containsKey(it) }
        assertEquals(
            "a country whose flag the palette shows cannot be found by its name.",
            emptyList<String>(), missing
        )
        assertEquals(
            "\"france\" must find exactly the French flag",
            listOf("🇫🇷"), kw["france"]
        )
        // Every word of the name, because the search matches keywords by
        // prefix: "South Korea" is otherwise reachable only by typing "south".
        assertTrue("\"korea\" should find both Koreas", kw["korea"].orEmpty().size >= 2)
        assertTrue("\"states\" should find the United States", kw["states"].orEmpty().contains("🇺🇸"))
        assertTrue(
            "\"united\" should find more than one united thing",
            kw["united"].orEmpty().size >= 2
        )
        // The platform's name is the name, and *which* name that is belongs to
        // the platform too. This asserted the literal string "türkiye",
        // because that is what the JDK on this desk answers for TR -- and the
        // one CI builds with answers "Turkey", so the suite passed locally and
        // failed on every push for fifteen commits. Android is the third
        // opinion and varies by release. What the app promises is that
        // whatever the platform calls a region is what finds its flag, with no
        // hand-written alias in between; so that is what is asked, of the same
        // call the feature makes.
        val tr = java.util.Locale("", "TR").getDisplayCountry(Locale.ENGLISH)
            .lowercase(Locale.ENGLISH)
        assertTrue(
            "the platform calls TR \"$tr\" and that is not what finds its flag",
            kw.containsKey(tr)
        )
    }

    /**
     * The whole palette, so a flag added to it is covered without being listed
     * anywhere. Measured 2026-09-01: 233 flags, 229 of them derivable —
     * the four that are not are the waving, black, chequered and pirate flags
     * plus the tag-sequence and pride ones, which are not regions.
     */
    @Test
    fun `almost every flag in the palette gets a name`() {
        val flags = palette().filter { EmojiData.regionOf(it) != null }
        assertTrue("the palette has no flags in it any more: ${flags.size}", flags.size >= 200)
        val named = EmojiData.flagKeywords(Locale.ENGLISH).values.flatten().toSet()
        val unnamed = flags.filterNot { it in named }.map { EmojiData.regionOf(it) }
        // The platform hands back the code for a region it cannot name, and
        // those are skipped rather than turned into a keyword of "AC".
        assertTrue(
            "the platform stopped naming ${unnamed.size} of ${flags.size} regions, " +
                "which is more than the handful it has never named: $unnamed",
            unnamed.size <= 12
        )
        println("flags in the palette: ${flags.size}, named by the platform: ${flags.size - unnamed.size}, skipped: $unnamed")
    }

    /** And the names come back in the language being searched in. */
    @Test
    fun `the names are the platform's, in the search language`() {
        val de = EmojiData.flagKeywords(Locale.GERMAN)
        assertTrue(
            "German search should find Germany by its German name; got ${de.keys.take(5)}",
            de.containsKey("deutschland")
        )
        // English is merged in behind it, exactly as the asset index does, so a
        // user typing the English name in a German keyboard still finds it.
        assertTrue("the English name should be there too", de.containsKey("germany"))
    }

    /** Nothing here may claim an emoji the palette does not show. */
    @Test
    fun `it only ever names flags the palette actually has`() {
        val shown = palette().toSet()
        val claimed = EmojiData.flagKeywords(Locale.ENGLISH).values.flatten().toSet()
        val stray = claimed.filterNot { it in shown }
        assertEquals("a keyword points at an emoji the panel never shows", emptyList<String>(), stray)
    }
}
