package com.rimboard.keyboard.spell

import com.rimboard.keyboard.engine.SuggestionEngine
import com.rimboard.keyboard.engine.UserData
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * The half of the app the incognito switch did not reach.
 *
 * "Never learn or suggest from history" is what the setting says, and the
 * keyboard keeps it: `suggestionsFor`, `glideFor`, `predictions` and
 * `correctionCandidates` are all passed `personalized = !isIncognito()`.
 *
 * The spell checker is a second service with its own engine instance and its
 * own way into the same four functions, and it passed none of them the flag.
 * So with incognito on, a typo of a name this keyboard had learned was still
 * offered that name from the underline menu, and the whole candidate list was
 * still re-ranked by what the user's own n-grams predicted. Both are history,
 * surfaced under a promise not to.
 *
 * This is the third time this service has been found governing half a setting.
 * `RimSpellService` carries the note from the last two: "the keyboard offers
 * the word while the spell checker goes on refusing to" for offensive words,
 * and "the setting has to reach here too or it would govern half the app" for
 * cautious autocorrect. A second engine instance is the shape of the fault --
 * a rule set on one is not a rule set on the other.
 *
 * What incognito must *not* take away is the other direction. A word the user
 * taught the keyboard is still not underlined, because `acceptedWord` and the
 * `isKnown` short-circuit sit outside the flag on purpose: incognito withholds
 * what would be suggested, not what is already on the screen.
 */
class SpellIncognitoTest {

    private lateinit var dir: File
    private lateinit var userData: UserData
    private val en: Locale = Locale.ENGLISH

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-spellincog", "").let { it.delete(); it.mkdirs(); it }
        userData = UserData.inDir(dir)
    }

    @After
    fun tearDown() {
        userData.shutdown()
        dir.deleteRecursively()
    }

    private fun assets(): File =
        listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }

    private fun judge(personalized: Boolean): SpellJudge {
        val files = HashMap<String, String>()
        for (n in listOf("dictionaries/en.txt", "predictions/en.txt")) {
            files[n] = File(assets(), n).readText()
        }
        val engine = SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
        return SpellJudge(engine, "en", en, null, null, personalized)
    }

    private fun suggestions(personalized: Boolean): List<String> =
        judge(personalized).verdictFor(
            TYPO, "", "", "", 5, true, Budget(SpellJudge.CORRECTION_BUDGET)
        ).words

    /** A word the user has taught the keyboard, the way typing teaches it. */
    private fun learn(times: Int = 4) {
        repeat(times) { userData.learnWord(WORD) }
    }

    @Test
    fun `the premise holds`() {
        // Everything here rests on the shipped corpus not holding this word, so
        // that an offer of it can only have come from what the user typed. The
        // engine's own incognito test learned this lesson on "zelensky", which
        // the corpus does contain.
        val d = judge(true).let { _ ->
            SuggestionEngine.forTesting(userData) { p ->
                File(assets(), p).takeIf { it.exists() }?.inputStream()
            }.dictionary("en", en)
        }
        assertTrue("$WORD is in the dictionary, so an offer of it proves nothing", !d.contains(WORD))
    }

    @Test
    fun `a learned word is not offered in incognito`() {
        learn()
        // The premise: outside incognito this really is the fix, so the
        // assertion below is about the flag and not about the corrector.
        assertTrue(
            "the learned word is not being offered at all, so this proves " +
                "nothing: " + suggestions(personalized = true),
            suggestions(personalized = true).contains(WORD)
        )
        assertTrue(
            "the spell checker offered a word from the user's history in " +
                "incognito: " + suggestions(personalized = false),
            !suggestions(personalized = false).contains(WORD)
        )
    }

    @Test
    fun `the dictionary still corrects in incognito`() {
        // The other half. Incognito withholds history, not the spell checker.
        assertTrue(
            "incognito stopped the spell checker correcting altogether",
            judge(personalized = false).verdictFor(
                "problrm", "", "", "", 5, true, Budget(SpellJudge.CORRECTION_BUDGET)
            ).words.contains("problem")
        )
    }

    @Test
    fun `a word the user taught it is still not underlined in incognito`() {
        // The direction incognito must not take away. Withholding a suggestion
        // is one thing; underlining someone's own vocabulary back at them as a
        // mistake is a different and worse one.
        learn()
        val v = judge(personalized = false).verdictFor(
            WORD, "", "", "", 5, true, Budget(SpellJudge.CORRECTION_BUDGET)
        )
        assertTrue(
            "incognito made the spell checker flag a word the user taught it",
            (v.attrs and android.view.textservice.SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO) == 0
        )
    }

    /** Unit tests run from the module directory; tolerate the project root too. */
    private fun src(): File =
        listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }

    /**
     * The flag is worthless unless the service sets it from the preference.
     *
     * `SpellJudge` defaults it to true, which is right for the engine's other
     * callers and is exactly how this went unnoticed: everything compiled,
     * every test passed, and the one caller that had to say otherwise said
     * nothing.
     */
    @Test
    fun `the spell service reads the incognito preference`() {
        val svc = src().resolve("com/rimboard/keyboard/spell/RimSpellService.kt").readText()
        assertTrue(
            "RimSpellService never consults incognito, so the switch governs " +
                "the keyboard and not the spell checker",
            svc.contains("Prefs.incognitoOn(")
        )
    }

    private companion object {
        /** A word the shipped dictionary does not hold. See `the premise holds`. */
        const val WORD = "idempotent"

        /** One adjacent-key slip away from it. */
        const val TYPO = "idempotemt"
    }
}
