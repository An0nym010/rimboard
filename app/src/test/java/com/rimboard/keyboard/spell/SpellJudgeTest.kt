package com.rimboard.keyboard.spell

import android.view.textservice.SuggestionsInfo
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
 * What the spell checker decides about one word.
 *
 * Every ranking rule this service gained — sentence context, the word after the
 * typo, sentence openers, proper nouns, the correction budget — shipped without
 * a test, because the decision lived in a private method of a class that needs
 * a bound text field to exist. It does not any more, and the engine has had an
 * asset seam all along, so a handful of in-memory words stands in for the
 * shipping dictionary and every claim below is checkable by hand.
 */
class SpellJudgeTest {

    private lateinit var dir: File
    private lateinit var userData: UserData
    private val en: Locale = Locale.ENGLISH

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-judge", "").let { it.delete(); it.mkdirs(); it }
        userData = UserData.inDir(dir)
    }

    @After
    fun tearDown() {
        userData.shutdown()
        dir.deleteRecursively()
    }

    private fun judge(assets: Map<String, String>): SpellJudge {
        val engine = SuggestionEngine.forTesting(userData) { path -> assets[path]?.byteInputStream() }
        // Load the prediction model, which warm() does in the app. The judge
        // deliberately never loads it: that call would parse an asset on a
        // binder thread, so both context rules sit inert until something else
        // has brought it in. A test that wants to watch them work must say so.
        engine.predictions("", "prime", "en", en, 1)
        return SpellJudge(engine, "en", en)
    }

    /** The words in the dictionary, most common first, as the loader expects. */
    private fun dict(vararg pairs: Pair<String, Int>) =
        "dictionaries/en.txt" to pairs.joinToString("\n") { (w, f) -> "$w $f" }

    private fun budget() = Budget(SpellJudge.CORRECTION_BUDGET)

    private fun verdict(
        j: SpellJudge,
        word: String,
        prev: String = "",
        next: String = "",
        initial: Boolean? = true,
        limit: Int = 5,
        budget: Budget = budget()
    ) = j.verdictFor(word, "", prev, next, limit, initial, budget)

    private fun typo(v: Verdict) =
        (v.attrs and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO) != 0

    private fun inDictionary(v: Verdict) =
        (v.attrs and SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY) != 0

    @Test
    fun `a known word is reported as known and offers nothing`() {
        val j = judge(mapOf(dict("store" to 9000, "stone" to 4000)))
        val v = verdict(j, "store")
        assertTrue("should be in the dictionary", inDictionary(v))
        assertFalse("and not a typo", typo(v))
        assertEquals(emptyList<String>(), v.words)
    }

    @Test
    fun `an unknown word is a typo and the fix is offered`() {
        val j = judge(mapOf(dict("store" to 9000, "stone" to 4000)))
        val v = verdict(j, "stroe")
        assertTrue("should look like a typo", typo(v))
        assertTrue(
            "and should say it has suggestions",
            (v.attrs and SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS) != 0
        )
        assertTrue("expected store among ${v.words}", "store" in v.words)
    }

    @Test
    fun `which key is nearer decides it, not which word is commoner`() {
        // Both candidates are one substitution from "hoyse" and equally
        // common, so only the keyboard geometry separates them: on QWERTY "u"
        // is next to "y" and "r" is two keys away. This is the channel model
        // on its own, with nothing else to lean on, and it is the baseline the
        // next test measures the follower against.
        val j = judge(mapOf(dict("house" to 5000, "horse" to 5000)))
        assertEquals("house", verdict(j, "hoyse").words.first())
    }

    @Test
    fun `the word after the typo breaks the tie`() {
        // Measured against what the engine does unaided rather than against my
        // guess at it — twice this test was written on an assumption about
        // the ranking that turned out to be wrong. Baseline is "house"; a
        // follower that only "horse" is known to precede should lift it above.
        val assets = mapOf(
            dict("house" to 5000, "horse" to 5000),
            "predictions/en.txt" to "horse\triding saddle"
        )
        val plain = verdict(judge(assets), "hoyse").words
        val withNext = verdict(judge(assets), "hoyse", next = "riding").words
        assertEquals("baseline: the nearer key wins", "house", plain.first())
        assertEquals("the follower should lift horse over it", "horse", withNext.first())
        assertTrue("and must not drop the other candidate", "house" in withNext)
    }

    @Test
    fun `a candidate more than one edit away is never in the running`() {
        // Why the two tests above use a substitution pair. maxEditDistance is
        // 1 below six letters, so "stone" is not a candidate for "stroe" at
        // all — it is two substitutions away, while "store" is one
        // transposition. Right-context ranking can only reorder what the
        // channel model already admitted; it cannot introduce anything.
        val j = judge(mapOf(dict("stone" to 9000, "store" to 4000)))
        val words = verdict(j, "stroe").words
        assertTrue("expected store, got $words", "store" in words)
        assertFalse("stone is two edits away and must not appear", "stone" in words)
    }

    @Test
    fun `a name in mid-sentence is not judged, and the same word opening one is`() {
        val j = judge(mapOf(dict("same" to 9000, "some" to 8000)))
        val mid = verdict(j, "Sam", initial = false)
        assertFalse("a capital mid-sentence reads as a name", typo(mid))
        assertEquals(emptyList<String>(), mid.words)

        val opening = verdict(j, "Sam", initial = true)
        assertTrue("the same word opening a sentence is still judged", typo(opening))
    }

    @Test
    fun `a word with a digit in it is left alone`() {
        val j = judge(mapOf(dict("covid" to 9000)))
        assertFalse("version numbers and identifiers are not spelling", typo(verdict(j, "covid19")))
    }

    @Test
    fun `past the budget a word is still underlined but nothing is worked out`() {
        val j = judge(mapOf(dict("store" to 9000)))
        val spent = Budget(1)
        val first = verdict(j, "stroe", budget = spent)
        val second = verdict(j, "stroe", budget = spent)

        assertTrue("the first still gets its corrections", "store" in first.words)
        assertTrue("the first is a complete answer", first.complete)

        assertTrue("the second is still reported as a typo", typo(second))
        assertEquals("but nothing is worked out for it", emptyList<String>(), second.words)
        assertFalse("and it must not be cached as an answer", second.complete)
    }

    @Test
    fun `never more suggestions than the popup is documented to hold`() {
        // A framework limit above the cap used to let the contraction and the
        // split past it, for seven entries in a popup documented to hold five.
        val j = judge(mapOf(dict(
            "store" to 9000, "stone" to 8000, "stole" to 7000, "stoke" to 6000,
            "stove" to 5000, "store's" to 4000, "strode" to 3000
        )))
        val v = verdict(j, "stroe", limit = 50)
        assertTrue("expected at most ${SpellJudge.MAX_SUGGESTIONS}, got ${v.words}",
            v.words.size <= SpellJudge.MAX_SUGGESTIONS)
    }

    @Test
    fun `a word too short to judge is left alone`() {
        val j = judge(mapOf(dict("an" to 9000)))
        assertFalse("two letters correct too easily into something else", typo(verdict(j, "na")))
    }
}
