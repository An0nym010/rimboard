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
 * The third door into the user's history, and the one with no flag on it.
 *
 * The spell checker re-ranks its corrections by the word *after* the typo:
 * "does this candidate usually come before that?" `SuggestionEngine.continues`
 * answers it, and the first thing it does is ask `userData.follows`, the
 * user's own learned bigrams.
 *
 * Unlike every other reader of that store, it took no `personalized` argument
 * at all -- so there was nothing for the spell checker to pass, and nothing to
 * forget. Incognito withheld the user's words from the candidate list and then
 * ordered what was left by the pairs they had typed before.
 *
 * `follows` is a membership test, not a count: **one** typed pair is enough to
 * move a candidate. That is the right bar for evidence and a very low one for
 * a leak.
 *
 * As with the keyboard's own version of this, no content escapes -- every
 * candidate is a word the shipped dictionary holds. What escapes is the order,
 * and the first entry of an underline menu is the one that gets tapped.
 */
class SpellRightContextTest {

    private lateinit var dir: File
    private lateinit var userData: UserData
    private val en: Locale = Locale.ENGLISH

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-rightctx", "").let { it.delete(); it.mkdirs(); it }
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

    private fun words(personalized: Boolean, typo: String, next: String): List<String> =
        judge(personalized).verdictFor(
            typo, "", "", next, 5, true, Budget(SpellJudge.CORRECTION_BUDGET)
        ).words

    @Test
    fun `a learned pair does not reorder the underline menu in incognito`() {
        val typo = "pkay"
        val next = "again"

        val baseline = words(personalized = false, typo = typo, next = next)
        assertTrue(
            "the typo has fewer than two corrections, so nothing can be " +
                "reordered: $baseline",
            baseline.size >= 2
        )

        // One pair, typed once. That is all `follows` asks for.
        val runnerUp = baseline[1]
        userData.recordBigram(runnerUp, next)

        val outed = words(personalized = true, typo = typo, next = next)
        assertTrue(
            "the learned pair moved nothing, so the assertion below would " +
                "prove nothing. baseline=$baseline outed=$outed",
            outed != baseline
        )

        val incognito = words(personalized = false, typo = typo, next = next)
        assertTrue(
            "incognito ordered the corrections by a pair this user had typed " +
                "before. Without history: $baseline. In incognito: $incognito.",
            incognito == baseline
        )
    }
}
