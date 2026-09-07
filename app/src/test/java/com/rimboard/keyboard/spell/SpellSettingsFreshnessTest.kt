package com.rimboard.keyboard.spell

import com.rimboard.keyboard.engine.SuggestionEngine
import com.rimboard.keyboard.engine.UserData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * A spell-checker session is a text field, not a moment — so it has to keep
 * reading the settings.
 *
 * Three preferences change what a verdict is, and all three were read in
 * `RimSession.onCreate` and never again. A `Session` is bound to one text field
 * and lives as long as the user is in it, so "per session" sounds like "often"
 * and means **frozen for exactly the stretch of time in which a user changes
 * these settings**.
 *
 * ## Incognito, which is the one that matters
 *
 * The switch says "Never learn or suggest from history", and the per-session
 * half of it lives on the comma popup, to be thrown mid-field, immediately
 * before typing the private thing. The keyboard obeys at once: it reads
 * `isIncognito()` at every use. This service read it once, when the field was
 * bound — which is *before* the switch was thrown — so it went on offering the
 * user's learned words as corrections, and re-ranking the whole candidate list
 * by the user's own n-grams, until they left the field. By then the private
 * thing has been typed.
 *
 * [SpellIncognitoTest] already proves the flag is a real input to a verdict; it
 * could not see that the flag was never re-read, because it builds the
 * [SpellJudge] itself.
 *
 * ## And a cache key made of stale state cannot detect staleness
 *
 * The verdict cache's `Ask` carries `cautious` with a note saying that a session
 * outliving a trip to the settings screen would otherwise serve the flag it
 * decided under the old setting. It could not do that: the key was built from
 * `engine.cautiousAutocorrect`, which *was* the value decided at bind time. The
 * entry worked only by accident — when a second session's `onCreate` happened to
 * overwrite the shared engine's flag underneath the first. Reading the
 * preference at judge time is what makes the key mean what its comment says.
 *
 * "Block offensive words" was in neither the reading nor the key, and it reaches
 * further than the other two: it removes candidates from the list the verdict
 * *is*. Measured below on the shipped English lists.
 *
 * ## What is proved where
 *
 * That the settings are real inputs is behavioural. That they are read per
 * framework call rather than per session is a fact about wiring inside a
 * `SpellCheckerService.Session`, which does not instantiate in a JVM test, so it
 * is read out of the source in the style of
 * [com.rimboard.keyboard.FocusContextTest] and `InputRoutingTest`.
 */
class SpellSettingsFreshnessTest {

    private lateinit var dir: File
    private lateinit var userData: UserData
    private val en: Locale = Locale.ENGLISH

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-spellfresh", "").let {
            it.delete(); it.mkdirs(); it
        }
        userData = UserData.inDir(dir)
    }

    @After
    fun tearDown() {
        userData.shutdown()
        dir.deleteRecursively()
    }

    private fun assets(): File =
        listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }

    private fun engine(blockOffensive: Boolean): SuggestionEngine {
        val files = HashMap<String, String>()
        for (n in listOf("dictionaries/en.txt", "predictions/en.txt", "offensive/en.txt")) {
            files[n] = File(assets(), n).readText()
        }
        val e = SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
        e.blockOffensive = blockOffensive
        return e
    }

    private fun suggestions(typo: String, blockOffensive: Boolean): List<String> =
        SpellJudge(engine(blockOffensive), "en", en).verdictFor(
            typo, "", "", "", 5, true, Budget(SpellJudge.CORRECTION_BUDGET)
        ).words

    @Test
    fun `blocking offensive words changes what a verdict holds`() {
        // Two real transpositions of a listed word, on the shipped English
        // dictionary and the shipped English offensive list. Without the
        // setting the repair is the only thing offered; with it there is
        // nothing to offer at all, which is the intended behaviour and is why
        // a verdict cached under one answer must not be served under the other.
        for (typo in listOf("btich", "bitcx")) {
            assertEquals(
                "the premise has moved: $typo no longer repairs to the listed " +
                    "word, so this proves nothing about the setting",
                listOf("bitch"),
                suggestions(typo, blockOffensive = false)
            )
            assertTrue(
                "\"Block offensive words\" did not reach the spell checker's " +
                    "suggestions for $typo: " + suggestions(typo, true),
                suggestions(typo, blockOffensive = true).isEmpty()
            )
        }
    }

    // ---------------------------------------------------------------- wiring

    private fun serviceSource(): String {
        for (p in listOf(
            "src/main/java/com/rimboard/keyboard/spell/RimSpellService.kt",
            "app/src/main/java/com/rimboard/keyboard/spell/RimSpellService.kt"
        )) {
            val f = File(p)
            if (f.isFile) return f.readText()
        }
        throw AssertionError("RimSpellService not found")
    }

    /** The session's `onCreate`, which is the one place these used to be read. */
    private fun sessionOnCreateBody(): String {
        val src = serviceSource()
        // The service has an onCreate of its own; the session's is the second.
        val first = src.indexOf("override fun onCreate(")
        assertTrue("no onCreate at all — was the service renamed?", first >= 0)
        val start = src.indexOf("override fun onCreate(", first + 1)
        assertTrue("the session has no onCreate — was it renamed?", start >= 0)
        val end = src.indexOf("override fun onGetSentenceSuggestionsMultiple", start)
        assertTrue("could not find the end of the session's onCreate", end > start)
        return src.substring(start, end)
    }

    private val settingReads = listOf(
        "Prefs.incognitoOn(",
        "Prefs.blockOffensive(",
        "Prefs.cautiousAutocorrect("
    )

    @Test
    fun `the session does not freeze the three settings when the field is bound`() {
        val body = sessionOnCreateBody()
        for (read in settingReads) {
            assertFalse(
                "the session reads $read when the field is bound. A session " +
                    "lives as long as the user is in that field, so this is the " +
                    "value from before they changed the setting — and for " +
                    "incognito that is the whole point of the switch",
                body.contains(read)
            )
        }
    }

    @Test
    fun `all three are read where the answer is worked out`() {
        val src = serviceSource()
        val start = src.indexOf("private fun live()")
        assertTrue("live() is gone — where are the settings read now?", start >= 0)
        val body = src.substring(start, minOf(src.length, start + 400))
        for (read in settingReads) {
            assertTrue("live() no longer reads $read", body.contains(read))
        }
        // Both framework entry points, because the word-at-a-time one is still
        // used by callers that ask for word-level checking and is exactly as
        // able to be stale.
        assertEquals(
            "live() is not called from both entry points, so one of them is " +
                "still answering under whatever the last call left behind",
            2,
            Regex("""live\(\)""").findAll(src).count { m ->
                !src.startsWith("private fun live()", m.range.first - "private fun ".length)
            }
        )
    }

    @Test
    fun `the verdict cache is keyed on all three`() {
        val src = serviceSource()
        val start = src.indexOf("private data class Ask(")
        assertTrue("Ask is gone — was the cache key renamed?", start >= 0)
        val end = src.indexOf("private val verdicts", start)
        assertTrue("could not find the end of Ask", end > start)
        val body = src.substring(start, end)
        for (field in listOf("val cautious:", "val personalized:", "val blockOffensive:")) {
            assertTrue(
                "the verdict cache is not keyed on `$field`, so an answer " +
                    "worked out under one setting is served under the other. " +
                    "Ask's own note says it: when a verdict gains an input, " +
                    "come back here",
                body.contains(field)
            )
        }
    }
}
