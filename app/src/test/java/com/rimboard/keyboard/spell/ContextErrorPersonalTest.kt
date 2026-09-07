package com.rimboard.keyboard.spell

import com.rimboard.keyboard.engine.SuggestionEngine
import com.rimboard.keyboard.engine.UserData
import com.rimboard.keyboard.model.ContextError
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * What the user's own typing does to the rule that judges the user's own
 * typing.
 *
 * `ContextError` declines when the n-grams already hold the written word
 * before the follower, and with `personalized = true` those n-grams include
 * the pairs this keyboard has watched the user type. So the act of making the
 * mistake files the evidence that the mistake was intended — the same shape as
 * the fault that made post-correction dead on a phone, in the feature written
 * one commit later.
 *
 * The question is where the line sits, not whether the personal history counts.
 * Somebody who really does write "form data" should stop being asked about it.
 * Somebody who typed "form the" once, by mistake, should not have immunised it
 * for good — and a membership test is exactly that, because one occurrence is
 * indistinguishable from a habit.
 */
class ContextErrorPersonalTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-ctxpersonal", "").let { it.delete(); it.mkdirs(); it }
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
        e.predictions("", "the", "en", Locale.ENGLISH, 1, personalized = false, mayLoad = true)
        return e
    }

    /** The rule as the spell checker asks it: with the user's history in play. */
    private fun ask(e: SuggestionEngine, prev: String, word: String, next: String): String? =
        ContextError.suggest(
            word = word,
            next = next,
            predictions = {
                e.predictions("", prev, "en", Locale.ENGLISH, ContextError.DEPTH,
                    personalized = true, mayLoad = false)
                    .map { it.lowercase(Locale.ENGLISH) }
            },
            continues = { a, b -> e.continues(a, b, "en", Locale.ENGLISH, personalized = true) },
            writtenFits = { a, b ->
                e.continues(
                    a, b, "en", Locale.ENGLISH, personalized = true,
                    minLearned = SuggestionEngine.HABIT
                )
            }
        )

    @Test
    fun `the error is flagged before the keyboard has ever seen it`() {
        val e = engine()
        assertEquals("from", ask(e, "away", "form", "here"))
    }

    @Test
    fun `typing the mistake once does not immunise it`() {
        val e = engine()
        // Exactly what `commitComposedWord` files when somebody types
        // "away form here": the pair (form -> here), once.
        userData.recordNgram("away", "form", "here")
        userData.awaitIdle()
        assertEquals(
            "one slip made the spell checker stop seeing this error. The pair " +
                "was filed by the very act of making it, and a membership test " +
                "cannot tell that from a habit — so the mistake a user makes " +
                "is the one mistake this rule goes blind to.",
            "from",
            ask(e, "away", "form", "here")
        )
    }

    @Test
    fun `writing it twice is a habit, and the rule stands down`() {
        val e = engine()
        // Somebody who really does write this pair. Two is the same bar
        // `UserData.isKnown` holds a learned word to, and for the same reason.
        userData.recordNgram("away", "form", "here")
        userData.recordNgram("away", "form", "here")
        userData.awaitIdle()
        assertNull(ask(e, "away", "form", "here"))
    }
}
