package com.rimboard.keyboard.settings

import com.rimboard.keyboard.model.Languages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Which of several enabled languages gets to be the second one.
 *
 * The engine blends exactly one other language into the strip — two
 * dictionaries and two prediction models is the memory budget, and the numbers
 * behind it are in [com.rimboard.keyboard.engine.BilingualTest]. With two
 * languages enabled there is nothing to decide. With three there is, and the
 * decision used to be `languages().first { it != current }` — which is the
 * order [Languages.all] is written in, a list authored in this repository with
 * no knowledge of the user.
 *
 * ## What that cost
 *
 * Enabling en, tr and de produced the pairs en+tr, tr+en and de+en. German and
 * Turkish never meet, however much the user writes both, because English is
 * written first in `Languages.all`. Measured over German prose on the Turkish
 * layout, keystrokes saved:
 *
 *     no second language      1.1%
 *     the pair it chose       2.9%     (alt = en)
 *     the pair it wanted     33.4%     (alt = de)
 *
 * and Turkish prose on the German layout, 1.2% / 1.3% / 29.3% the same way. A
 * third language was not merely unhelped, it displaced the one that would have
 * helped.
 *
 * ## The rule now
 *
 * The two languages you switch between are the two you want blended, and
 * switching between them is the evidence. [Prefs.altLangFor] takes the most
 * recently opened other language, falling back to list order when nothing has
 * been opened yet — so this is a strict extension: an empty recency list gives
 * exactly the old answer, and with two languages enabled the two orders cannot
 * disagree at all.
 */
class SecondLanguageChoiceTest {

    private fun alt(enabled: List<String>, recency: List<String>, current: String) =
        Prefs.altLangFor(enabled, recency, current)

    @Test
    fun `with two languages the choice is the same as it ever was`() {
        // The invariance: for the two-language user nothing about this changed,
        // whatever the recency list happens to say.
        for (recency in listOf(
            emptyList(), listOf("en"), listOf("tr"), listOf("tr", "en"), listOf("en", "tr")
        )) {
            assertEquals("en+tr, recency $recency", "tr", alt(listOf("en", "tr"), recency, "en"))
            assertEquals("tr+en, recency $recency", "en", alt(listOf("en", "tr"), recency, "tr"))
        }
    }

    @Test
    fun `nothing used yet falls back to the order it used to use`() {
        val enabled = Languages.codes.filter { it in setOf("en", "tr", "de") }
        assertEquals(listOf("en", "tr", "de"), enabled)
        for (cur in enabled) {
            assertEquals(
                "an empty recency list must reproduce the old answer for $cur",
                enabled.firstOrNull { it != cur },
                alt(enabled, emptyList(), cur)
            )
        }
    }

    @Test
    fun `the pair a trilingual user actually types is the pair they get`() {
        val enabled = listOf("en", "tr", "de")
        // Turkish, then German: the two they have been switching between.
        var recency = Prefs.recencyWith("tr", emptyList())
        recency = Prefs.recencyWith("de", recency)
        assertEquals("de is open, tr was last", "tr", alt(enabled, recency, "de"))
        // Back to Turkish, and the pair holds from the other side.
        recency = Prefs.recencyWith("tr", recency)
        assertEquals("tr is open, de was last", "de", alt(enabled, recency, "tr"))
        // English is only chosen once it is actually used.
        assertTrue("English must not appear until it is used", "en" !in recency.take(2))
        recency = Prefs.recencyWith("en", recency)
        assertEquals("en", alt(enabled, recency, "tr"))
    }

    @Test
    fun `a language that is no longer enabled is not chosen`() {
        // Recency outlives the setting: turning German off must not leave it
        // named as the second language, with a dictionary that may not be there.
        val recency = listOf("de", "tr", "en")
        assertEquals("tr", alt(listOf("en", "tr"), recency, "en"))
    }

    @Test
    fun `an unknown code cannot reach the head of the list`() {
        // A language dropped from the build leaves its code in the stored
        // string. Head of the recency list is exactly where it must not sit.
        assertEquals(listOf("tr", "en"), Prefs.recencyWith("tr", listOf("en", "xx")))
        assertEquals(listOf("en"), Prefs.recencyWith("xx", listOf("en", "xx")))
        assertTrue("xx", alt(listOf("en", "tr"), listOf("xx"), "en") == "tr")
    }

    @Test
    fun `using a language moves it to the front without duplicating it`() {
        var r = Prefs.recencyWith("en", emptyList())
        r = Prefs.recencyWith("tr", r)
        r = Prefs.recencyWith("en", r)
        assertEquals(listOf("en", "tr"), r)
    }

    /**
     * Both readers of "the second language" must ask the same question.
     *
     * The keyboard and the spell checker each need it, and they used to answer
     * it with the same expression written out twice — so a bilingual writer
     * could have their strip blended with one language and their underlines
     * judged against another the moment a third was enabled.
     */
    @Test
    fun `the keyboard and the spell checker choose it the same way`() {
        val src = listOf(File("src/main/java"), File("app/src/main/java"))
            .first { it.isDirectory }
        val offenders = ArrayList<String>()
        for (f in src.walkTopDown().filter { it.extension == "kt" }) {
            if (f.name == "Prefs.kt") continue
            f.readLines().forEachIndexed { i, line ->
                if (Regex("""languages\([^)]*\)\s*\.\s*(firstOrNull|first)\s*\{""")
                        .containsMatchIn(line)
                ) {
                    offenders.add("${f.name}:${i + 1} $line")
                }
            }
        }
        assertTrue(
            "these pick a second language by list order instead of asking " +
                "Prefs.altLangFor, which is how the two answers drifted apart:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }
}
