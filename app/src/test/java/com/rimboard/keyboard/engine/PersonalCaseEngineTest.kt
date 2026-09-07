package com.rimboard.keyboard.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * The two places a learned capital has to arrive, against the real English
 * assets and a real [UserData].
 *
 * `PersonalCaseTest` pins the rule and `PersonalCaseStoreTest` pins the
 * counting; neither can say whether the word ever reaches a chip to be cased.
 * The join itself -- the strip calling `personalCase` on what the engine
 * ranked -- lives in `RimBoardService` and is asserted by
 * `PersonalCaseWiringTest`, because a unit test cannot drive an
 * `InputMethodService`. This asserts the two halves that meet there: that the
 * word is offered, and that the store has the capital for it.
 *
 * Every test here runs with `personalized = true`, which is worth saying out
 * loud: the standing gap in this suite is that almost everything passes
 * `false`, so the learned-data path the product actually runs is the one least
 * exercised. Two shipped features have now been dead on a phone for exactly
 * that reason.
 */
class PersonalCaseEngineTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-caseeng", "").let { it.delete(); it.mkdirs(); it }
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
        for (kind in listOf("dictionaries", "predictions", "suffixes", "prefixes")) {
            File(assets(), "$kind/en.txt").takeIf { it.isFile }?.let {
                files["$kind/en.txt"] = it.readText()
            }
        }
        val e = SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
        e.dictionary("en", Locale.ENGLISH)
        return e
    }

    /** One commit, exactly as `commitComposedWord` makes it. */
    private fun type(word: String, times: Int) {
        repeat(times) {
            userData.learnWord(word.lowercase(Locale.ENGLISH))
            userData.noteCase(word, Locale.ENGLISH)
        }
    }

    private fun strip(e: SuggestionEngine, composing: String): List<String> =
        e.suggestionsFor(
            composing, "en", Locale.ENGLISH,
            allowAutocorrect = false, personalized = true
        ).items

    @Test
    fun `a name the keyboard has learned is completed with its capital`() {
        val e = engine()
        // Three, because that is the bar `suggestionsFor` holds a learned word
        // to before it will offer it at all -- the capitals are useless below
        // it, since there is no chip to put them on.
        type("Anthropic", 3)
        userData.awaitIdle()

        val items = strip(e, "anthr")
        assertTrue(
            "the learned word never reached the strip, so nothing here is " +
                "about capitals: $items",
            items.any { it.equals("anthropic", ignoreCase = true) }
        )
        assertEquals("Anthropic", e.personalCase("anthropic"))
        assertTrue(
            "the strip has the word and the store has its capital, and the " +
                "two did not meet: $items",
            items.map { e.personalCase(it) }.contains("Anthropic")
        )
    }

    @Test
    fun `an ordinary dictionary word gets the same treatment`() {
        // The case that is easy to miss: this is not only about words the
        // shipped list has never heard of. "rose" is in the English
        // dictionary at a good frequency, and somebody writing to Rose wants
        // the name.
        val e = engine()
        assertEquals("nothing learned yet", "rose", e.personalCase("rose"))
        type("Rose", 3)
        userData.awaitIdle()
        assertEquals("Rose", e.personalCase("rose"))
        assertTrue(strip(e, "ros").any { it.equals("rose", ignoreCase = true) })
    }

    @Test
    fun `the next-word chip carries the capital a learned n-gram cannot`() {
        val e = engine()
        type("Anthropic", 3)
        repeat(3) { userData.recordNgram("", "joined", "anthropic") }
        userData.awaitIdle()

        val preds = e.predictions(
            "", "joined", "en", Locale.ENGLISH, 5, personalized = true, mayLoad = true
        )
        assertTrue("the prediction never arrived: $preds", preds.contains("anthropic"))
        assertTrue(
            "a chip is offered with no typed letters to take a case from, so " +
                "this is the only thing that knows the word has a capital: $preds",
            preds.map { e.personalCase(it) }.contains("Anthropic")
        )
    }

    @Test
    fun `a word nobody has capitalised is returned untouched`() {
        val e = engine()
        type("hello", 4)
        userData.awaitIdle()
        assertEquals("hello", e.personalCase("hello"))
        assertEquals("thanks", e.personalCase("thanks"))
    }
}
