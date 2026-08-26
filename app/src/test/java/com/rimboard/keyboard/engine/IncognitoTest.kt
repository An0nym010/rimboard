package com.rimboard.keyboard.engine

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * What incognito promises, asked of every path that could break it.
 *
 * The switch says "Never learn or suggest from history", and the service holds
 * up its end of that: every write to [UserData] is behind
 * `Prefs.learnWords && !isIncognito() && !isPassword && !isEmailOrUri`, and
 * every call into the engine passes `personalized = !isIncognito()`.
 *
 * The engine's end was one gate short. `suggestionsFor`, `glideFor` and
 * `predictions` each check `personalized` before reading the user's own
 * vocabulary — and `correctionCandidates`, which feeds both the strip's
 * corrections and the word the space bar commits, never took the flag at all.
 * So a typo of a name this keyboard had learned was still fixed to that name in
 * incognito: not a word the user typed in this session, but one from before it,
 * put on screen by a keyboard that had promised not to.
 *
 * It is worth being exact about the size of it. Nothing was *written* in
 * incognito, and nothing was written differently; the leak was one direction
 * only, and it needed the user to mistype something close to a word they had
 * taught the keyboard at least three times. It is still the difference between
 * a promise and a nearly-kept one.
 */
class IncognitoTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-incog", "").let { it.delete(); it.mkdirs(); it }
        userData = UserData.inDir(dir)
    }

    @After
    fun tearDown() {
        userData.shutdown()
        dir.deleteRecursively()
    }

    private fun assets(): File =
        listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }

    private fun engine(): SuggestionEngine {
        val files = HashMap<String, String>()
        for (n in listOf("dictionaries/en.txt", "predictions/en.txt")) {
            files[n] = File(assets(), n).readText()
        }
        return SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
    }

    /** A word the user has taught the keyboard, the way typing teaches it. */
    private fun learn(word: String, times: Int = 4) {
        repeat(times) { userData.learnWord(word) }
    }

    @Test
    fun `a learned word does not correct a typo in incognito`() {
        val e = engine()
        val en = Locale.ENGLISH
        learn(WORD)
        // The premise: outside incognito this really is the fix, so the
        // assertion below is about the flag and not about the corrector.
        assertTrue(
            "the learned word is not correcting at all, so this proves nothing: " +
                e.correctionFor(TYPO, "en", en, personalized = true),
            e.correctionFor(TYPO, "en", en, personalized = true) == WORD
        )
        assertTrue(
            "incognito still offered a word from the user's history: " +
                e.correctionFor(TYPO, "en", en, personalized = false),
            e.correctionFor(TYPO, "en", en, personalized = false) == null
        )
    }

    @Test
    fun `a learned word does not reach the strip in incognito`() {
        val e = engine()
        val en = Locale.ENGLISH
        learn(WORD)
        val shown = e.suggestionsFor(
            TYPO, "en", en, allowAutocorrect = true, personalized = false
        ).items
        assertTrue(
            "incognito put a word from the user's history on the strip: $shown",
            shown.none { it.equals(WORD, ignoreCase = true) }
        )
    }

    @Test
    fun `the dictionary still corrects in incognito`() {
        // The other half. Incognito withholds *history*, not the keyboard: an
        // ordinary typo of an ordinary word must still be fixed.
        val e = engine()
        assertTrue(
            "incognito stopped correcting altogether",
            e.correctionFor("problrm", "en", Locale.ENGLISH, personalized = false) == "problem"
        )
    }

    @Test
    fun `outside incognito the learned word still corrects`() {
        // And the feature this is narrowing must survive the narrowing.
        val e = engine()
        learn(WORD)
        assertTrue(
            "a learned word no longer corrects a typo of itself",
            e.correctionFor(TYPO, "en", Locale.ENGLISH, personalized = true) == WORD
        )
    }


    @Test
    fun `the premise holds`() {
        // Everything here rests on the corpus not containing this word. It once
        // rested on "zelensky", which the corpus does contain at rank 298,732 --
        // so the leak appeared to be real when the strip was only showing a
        // dictionary entry.
        val d = engine().dictionary("en", Locale.ENGLISH)
        assertTrue(
            "$WORD is in the dictionary now, so a correction to it proves nothing",
            !d.contains(WORD)
        )
    }

    private companion object {
        /**
         * A word the shipped dictionary does not hold, so that a correction to
         * it can only have come from what the user typed before. Checked in
         * `the premise holds` below, because a word that quietly entered the
         * corpus would turn every assertion here into a tautology.
         */
        const val WORD = "idempotent"

        /** One adjacent-key slip away from it. */
        const val TYPO = "idempotemt"
    }
}
