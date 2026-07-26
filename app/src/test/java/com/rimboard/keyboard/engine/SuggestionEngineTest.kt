package com.rimboard.keyboard.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * The context-aware ranking, tested end to end through the engine.
 *
 * This is the logic the previous change shipped without a test, because the
 * engine took a Context to read its assets and could not run on a plain JVM.
 * With the asset seam it can: a handful of in-memory words stands in for the
 * shipping dictionary, and every ranking claim here is verifiable by hand.
 */
class SuggestionEngineTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    /** Builds an engine over the given in-memory assets. */
    private fun engine(assets: Map<String, String>): SuggestionEngine =
        SuggestionEngine.forTesting(userData) { path ->
            assets[path]?.byteInputStream()
        }

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-test", "").let {
            it.delete(); it.mkdirs(); it
        }
        userData = UserData.inDir(dir)
    }

    @After
    fun tearDown() {
        userData.shutdown()
        dir.deleteRecursively()
    }

    private val en = Locale.ENGLISH

    // ---- completion ranking ----

    @Test
    fun `with no preceding word completions fall back to raw frequency`() {
        // "and" is commoner than "am", so with nothing to go on it leads.
        val assets = mapOf(
            "dictionaries/en.txt" to "and 9000\nam 3000\nan 2000"
        )
        val out = engine(assets).suggestionsFor(
            "a", "en", en, allowAutocorrect = false, personalized = false
        ).items
        assertEquals("a", out.first())              // slot 0 is always verbatim
        assertEquals("and", out.drop(1).first())    // then by frequency
    }

    @Test
    fun `the preceding word lifts a contextual completion over a commoner one`() {
        // Same words, but now "I" precedes them and the bundled model says "I"
        // is followed by "am". "am" should overtake the commoner "and".
        val assets = mapOf(
            "dictionaries/en.txt" to "and 9000\nam 3000\nan 2000",
            "predictions/en.txt" to "i\tam are was"
        )
        val out = engine(assets).suggestionsFor(
            "a", "en", en, allowAutocorrect = false, personalized = false,
            prevWord = "i"
        ).items
        assertEquals("a", out.first())
        assertEquals(
            "context should lift 'am' above the commoner 'and'",
            "am", out.drop(1).first()
        )
    }

    @Test
    fun `context only reorders real completions, never injects an unrelated word`() {
        // "am" is predicted after "I", but the user is typing "th" — "am" is
        // not a completion of that and must not appear.
        val assets = mapOf(
            "dictionaries/en.txt" to "the 9000\nthis 4000\nam 3000",
            "predictions/en.txt" to "i\tam are"
        )
        val out = engine(assets).suggestionsFor(
            "th", "en", en, allowAutocorrect = false, personalized = false,
            prevWord = "i"
        ).items
        assertTrue("'am' must not be injected", out.none { it.equals("am", true) })
        assertTrue(out.any { it.equals("the", true) })
    }

    // ---- correction ranking ----

    @Test
    fun `context breaks a correction tie the dictionary cannot`() {
        // "wan" is one edit from both "was" and "war". Frequencies are close
        // enough that context decides: after "the", the model predicts "war".
        val assets = mapOf(
            "dictionaries/en.txt" to "was 5000\nwar 4000",
            "predictions/en.txt" to "the\twar world way"
        )
        val eng = engine(assets)
        val withContext = eng.correctionCandidates(
            "wan", "en", en, limit = 1,
            contextRank = mapOf("war" to 0, "world" to 1, "way" to 2)
        )
        assertEquals(listOf("war"), withContext)
        // Without the context the frequency ordering stands.
        val without = eng.correctionCandidates("wan", "en", en, limit = 1)
        assertEquals(listOf("was"), without)
    }

    @Test
    fun `a strong adjacent-key fix is not overturned by weak context`() {
        // "helko" is an adjacent-key slip for "hello" (l/k neighbours). Even
        // if context nudges "hells", the spatial evidence must win — the bonus
        // is a tie-break, not an override.
        val assets = mapOf("dictionaries/en.txt" to "hello 9000\nhells 200")
        val out = engine(assets).correctionCandidates(
            "helko", "en", en, limit = 1,
            contextRank = mapOf("hells" to 0)
        )
        assertEquals(listOf("hello"), out)
    }

    // ---- the learned-data path, now reachable without a device ----

    @Test
    fun `a learned word is suggested once past the use threshold`() {
        val assets = mapOf("dictionaries/en.txt" to "apple 9000")
        val eng = engine(assets)
        repeat(3) { userData.learnWord("appleseed") }  // three uses
        val out = eng.suggestionsFor(
            "app", "en", en, allowAutocorrect = false, personalized = true
        ).items
        assertTrue("learned word should appear", out.any { it.equals("appleseed", true) })
    }

    @Test
    fun `a word learned only once stays below the suggestion threshold`() {
        val assets = mapOf("dictionaries/en.txt" to "apple 9000")
        val eng = engine(assets)
        userData.learnWord("appleseed")  // one use only
        val out = eng.suggestionsFor(
            "app", "en", en, allowAutocorrect = false, personalized = true
        ).items
        assertTrue("one use is not enough", out.none { it.equals("appleseed", true) })
    }

    @Test
    fun `learned next-word context predicts before any bundled model exists`() {
        // No predictions asset at all; the engine must still predict from what
        // the user has been observed typing.
        val eng = engine(mapOf("dictionaries/en.txt" to "you 9000"))
        repeat(4) { userData.recordNgram("", "see", "you") }
        val preds = eng.predictions("", "see", "en", en, 3)
        assertEquals("you", preds.first())
    }

    @Test
    fun `predictions with no evidence anywhere are empty rather than wrong`() {
        val eng = engine(mapOf("dictionaries/en.txt" to "you 9000"))
        assertTrue(eng.predictions("", "nonesuch", "en", en, 3).isEmpty())
    }

    // ---- contractions: overriding the apostrophe-stripped corpus ----

    /** The corpus reality: the bare form is in the dictionary, the real one is not. */
    private val enWithBareForms =
        "dont 9523\ncant 3936\nwont 1200\nwere 8000\nits 15000\ndone 5000"

    @Test
    fun `an unambiguous contraction auto-commits over the corpus bare form`() {
        // "dont" is in the dictionary with a huge frequency, so the ordinary
        // path treats it as a correctly-spelled word and never touches it.
        val eng = engine(mapOf("dictionaries/en.txt" to enWithBareForms))
        assertEquals("don't", eng.correctionFor("dont", "en", en))
    }

    @Test
    fun `contraction casing follows what was typed`() {
        val eng = engine(mapOf("dictionaries/en.txt" to "im 500"))
        assertEquals("I'm", eng.correctionFor("im", "en", en))
        assertEquals("I'm", eng.correctionFor("Im", "en", en))
    }

    @Test
    fun `an ambiguous contraction is offered but never auto-committed`() {
        // "cant" and "wont" are real words too, so the apostrophe form is a
        // tap-only suggestion — committing it on space would fight anyone who
        // meant the noun or "accustomed".
        val eng = engine(mapOf("dictionaries/en.txt" to enWithBareForms))
        assertEquals("cant", eng.correctionFor("cant", "en", en) ?: "cant")
        val strip = eng.suggestionsFor(
            "cant", "en", en, allowAutocorrect = true, personalized = false
        )
        assertTrue("can't should be offered", strip.items.any { it == "can't" })
        assertEquals("but never as the autocorrect", -1, strip.autocorrectIndex)
    }

    @Test
    fun `a common word that is also a bare contraction is left alone`() {
        // "its", "were" and "well" are usually correct as typed; the keyboard
        // must not turn "its" into "it's". They are in neither contraction list.
        val eng = engine(mapOf("dictionaries/en.txt" to enWithBareForms))
        assertEquals(null, eng.correctionFor("its", "en", en))
        assertEquals(null, eng.correctionFor("were", "en", en))
    }

    @Test
    fun `the apostrophe-less form is not offered as a completion`() {
        // Typing "don" must not suggest "dont" from the corpus; the contraction
        // path owns that word now.
        val eng = engine(mapOf("dictionaries/en.txt" to enWithBareForms))
        val out = eng.suggestionsFor(
            "don", "en", en, allowAutocorrect = false, personalized = false
        ).items
        assertTrue("'dont' must not be a completion", out.none { it == "dont" })
        assertTrue("'done' still may be", out.any { it == "done" })
    }

    @Test
    fun `the contraction rides the front chip when the word is fully typed`() {
        val eng = engine(mapOf("dictionaries/en.txt" to enWithBareForms))
        val out = eng.suggestionsFor(
            "youre", "en", en, allowAutocorrect = true, personalized = false
        )
        assertEquals("youre", out.items.first())          // verbatim
        assertTrue(out.items.any { it == "you're" })
        assertEquals("you're", out.items[out.autocorrectIndex])
    }

    @Test
    fun `sanity - the two completion orderings genuinely differ`() {
        // Guards the first two tests against both silently returning the same
        // thing through some shared bug.
        val assets = mapOf(
            "dictionaries/en.txt" to "and 9000\nam 3000",
            "predictions/en.txt" to "i\tam"
        )
        val eng = engine(assets)
        val neutral = eng.suggestionsFor(
            "a", "en", en, allowAutocorrect = false, personalized = false
        ).items.drop(1).first()
        val contextual = eng.suggestionsFor(
            "a", "en", en, allowAutocorrect = false, personalized = false, prevWord = "i"
        ).items.drop(1).first()
        assertNotEquals(neutral, contextual)
    }
}
