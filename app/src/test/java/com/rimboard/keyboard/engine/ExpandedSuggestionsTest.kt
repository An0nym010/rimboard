package com.rimboard.keyboard.engine

import com.rimboard.keyboard.model.ChipRows
import com.rimboard.keyboard.model.StripLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * What the strip's own list looks like when it is not cut to five.
 *
 * The expanded panel is `suggestionsFor` with a bigger `slots` and nothing
 * else -- not a second ranking, not a deeper fetch, not a different set of
 * rules. That is the property worth holding, because a "show me more" gesture
 * that reorders or replaces what it was already showing is worse than no
 * gesture at all.
 *
 * ## What the panel can actually show, which is less than the grid holds
 *
 * [ChipRows.CAPACITY] is 24 and the panel almost never gets 24.
 * [SuggestionEngine.COMPLETION_FETCH] is 12 and is a swept constant, so the
 * dictionary contributes at most twelve prefix matches, with the learned list
 * and the second language on top. Asking for more than exists costs nothing;
 * raising the fetch to fill the grid would cost something real, because the
 * fuzzy typo branch fires on the *size* of the merged candidate list and a
 * deeper fetch would silence it.
 *
 * So the honest claim is **five chips becoming twelve or thirteen**, measured
 * 2026-09-08 and printed by the second test below -- identically across
 * English, German and Turkish, because the ceiling is the fetch rather than
 * the language. That is two and a half times the strip rather than the five
 * the grid could hold, and it lands exactly where the keystroke table in
 * `open-items` stops: three chips 41.8%, six 53.4%, twelve 60.0% for English.
 * The panel reaches the end of the measured range and no further.
 */
class ExpandedSuggestionsTest {

    private lateinit var dir: File
    private lateinit var userData: UserData
    private val en = Locale.ENGLISH

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-expanded", "").let { it.delete(); it.mkdirs(); it }
        userData = UserData.inDir(dir)
    }

    @After
    fun tearDown() {
        userData.shutdown()
        dir.deleteRecursively()
    }

    private fun assets(): File =
        listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }

    private fun engine(lang: String): SuggestionEngine {
        val files = HashMap<String, String>()
        for (kind in listOf("dictionaries", "predictions", "suffixes", "prefixes")) {
            File(assets(), "$kind/$lang.txt").takeIf { it.isFile }?.let {
                files["$kind/$lang.txt"] = it.readText()
            }
        }
        val e = SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
        e.dictionary(lang, Locale.forLanguageTag(lang))
        return e
    }

    private fun ask(e: SuggestionEngine, lang: String, typed: String, slots: Int): List<String> =
        e.suggestionsFor(
            typed, lang, Locale.forLanguageTag(lang),
            allowAutocorrect = true, personalized = false, slots = slots
        ).items

    /** Prefixes with plenty of continuations, so there is something to expand. */
    private val probes = listOf("th", "co", "in", "re", "st", "ca", "pr", "ma")

    @Test
    fun `the strip's words are all in the panel, and the best one still leads`() {
        val e = engine("en")
        for (p in probes) {
            val strip = ask(e, "en", p, StripLayout.SLOTS)
            val panel = ask(e, "en", p, ChipRows.CAPACITY)
            assertEquals(
                "the verbatim word is slot 0 on both, or the panel is showing " +
                    "something other than what was typed",
                p, panel.firstOrNull()
            )
            assertEquals(
                "the panel's best suggestion differs from the strip's, so " +
                    "expanding reorders what it was already showing",
                strip.getOrNull(1), panel.getOrNull(1)
            )
            val missing = strip - panel.toSet()
            assertTrue(
                "expanding '$p' dropped words the strip was showing: $missing",
                missing.isEmpty()
            )
        }
    }

    @Test
    fun `expanding actually adds words, and here is how many`() {
        val report = StringBuilder("prefix  strip  panel\n")
        var gained = 0
        for (lang in listOf("en", "de", "tr")) {
            val e = engine(lang)
            for (p in probes) {
                val strip = ask(e, lang, p, StripLayout.SLOTS).size
                val panel = ask(e, lang, p, ChipRows.CAPACITY).size
                report.append("%s %-6s %5d %6d\n".format(lang, p, strip, panel))
                if (panel > strip) gained++
            }
        }
        println(report)
        assertTrue(
            "expanding added nothing anywhere, so the gesture opens a panel " +
                "showing exactly what the strip already showed",
            gained >= probes.size
        )
    }

    @Test
    fun `the default is the strip, so nothing else in the app moved`() {
        // `slots` has a default and every existing caller relies on it. A
        // change to that default would silently rewrite the strip everywhere.
        val e = engine("en")
        assertEquals(ask(e, "en", "th", StripLayout.SLOTS), ask(e, "en", "th", StripLayout.SLOTS))
        assertTrue(
            "the strip is no longer capped at StripLayout.SLOTS",
            e.suggestionsFor("th", "en", en, allowAutocorrect = true, personalized = false)
                .items.size <= StripLayout.SLOTS
        )
    }
}
