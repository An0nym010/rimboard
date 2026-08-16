package com.rimboard.keyboard.engine

import com.rimboard.keyboard.model.Languages
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The offensive-word filter, in the five languages RimBoard is used in most.
 *
 * The filter runs on suggestions that have already been *cased for display*, so
 * what it actually has to recognise is not the dictionary form but whatever
 * `matchCase` made of it — auto-capitalised at the start of a sentence, or in
 * full capitals under caps lock. Folding that back is only a round trip in
 * languages where case happens to be a one-to-one map, and two of these are not:
 *
 *  - **Turkish** capitalises `i` as `İ`, which a locale-less `lowercase()` turns
 *    into `i` + U+0307 rather than back into `i`;
 *  - **German** capitalises `ß` as `SS`, and nothing turns that back at all.
 *
 * Both let a slur through a filter the user had switched on. English, Spanish
 * and Russian never showed the fault because their case mapping is
 * locale-independent — they are here so that stays true.
 *
 * Every case proves the word is *reachable* before asserting it is gone, so an
 * assertion cannot pass merely because the lookup found nothing.
 */
class OffensiveFilterTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-offensive", "").let {
            it.delete(); it.mkdirs(); it
        }
        userData = UserData.inDir(dir)
    }

    @After
    fun tearDown() {
        userData.shutdown()
        dir.deleteRecursively()
    }

    /**
     * What the strip offers for [typed], given a dictionary and a blocked list
     * holding exactly [word]. Slot 0 is dropped: it is the verbatim typing, and
     * is deliberately never filtered — the point is to stop the keyboard
     * *offering* a word, not to censor one the user typed and can already see.
     */
    private fun offered(
        lang: String, word: String, typed: String, filtering: Boolean
    ): List<String> {
        val engine = SuggestionEngine.forTesting(userData) { path ->
            when (path) {
                "dictionaries/$lang.txt" -> "$word 9000".byteInputStream()
                "offensive/$lang.txt" -> word.byteInputStream()
                else -> null
            }
        }
        engine.blockOffensive = filtering
        return engine.suggestionsFor(
            typed, lang, Languages.byCode(lang).locale,
            allowAutocorrect = false, personalized = false
        ).items.drop(1)
    }

    /** [shown] is [word] as the strip would case it after typing [typed]. */
    private fun check(lang: String, word: String, typed: String, shown: String) {
        assertTrue(
            "$lang: \"$shown\" is never offered even with the filter off, so " +
                "this case proves nothing — fix the fixture, not the filter",
            offered(lang, word, typed, filtering = false).contains(shown)
        )
        assertFalse(
            "$lang: \"$shown\" was offered with the offensive filter on",
            offered(lang, word, typed, filtering = true).contains(shown)
        )
    }

    @Test
    fun `turkish dotted i survives the round trip through upper case`() {
        // Caps lock, and the auto-capital at the start of a sentence.
        check("tr", "ibne", "İBN", "İBNE")
        check("tr", "ibne", "İbn", "İbne")
    }

    @Test
    fun `german sharp s is caught although capitals spell it ss`() {
        check("de", "scheiße", "SCHEI", "SCHEISSE")
        check("de", "scheiße", "Schei", "Scheiße")
    }

    @Test
    fun `english spanish and russian stay filtered when capitalised`() {
        check("en", "shit", "SHI", "SHIT")
        check("es", "cabrón", "CABRÓ", "CABRÓN")
        check("ru", "блядь", "БЛЯД", "БЛЯДЬ")
    }

    @Test
    fun `the word the user typed is still shown verbatim`() {
        // Slot 0 is not the filter's business: it is already in the field.
        val engine = SuggestionEngine.forTesting(userData) { path ->
            when (path) {
                "dictionaries/en.txt" -> "shit 9000".byteInputStream()
                "offensive/en.txt" -> "shit".byteInputStream()
                else -> null
            }
        }
        engine.blockOffensive = true
        val items = engine.suggestionsFor(
            "shit", "en", Languages.byCode("en").locale,
            allowAutocorrect = false, personalized = false
        ).items
        assertEquals("shit", items.firstOrNull())
    }
}
