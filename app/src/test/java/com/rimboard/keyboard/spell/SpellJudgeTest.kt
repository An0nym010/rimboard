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

    private fun recommended(v: Verdict) =
        (v.attrs and SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS) != 0

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
    fun `the first word of a sentence is ranked against how sentences start`() {
        // "f" and "h" sit either side of "g" on the same row, so both
        // substitutions cost the same and the channel model has no opinion
        // between them — which is exactly the tie context is allowed to
        // break. The opener row is keyed under U+0001, the sentinel the engine
        // uses for the start of a sentence.
        val assets = mapOf(
            dict("fate" to 5000, "hate" to 5000),
            "predictions/en.txt" to "\u0001\thate happy hello"
        )
        val j = judge(assets)
        assertEquals(
            "mid-sentence there is no context and the tie stands",
            listOf("fate", "hate"), verdict(j, "gate", initial = false).words
        )
        assertEquals(
            "opening a sentence, the openers decide it",
            listOf("hate", "fate"), verdict(j, "gate", initial = true).words
        )
    }

    @Test
    fun `an unknown position asks for no opener context`() {
        // The word-at-a-time API cannot know where it sits, and "might be the
        // first word" is not evidence that it is. Same assets, null position:
        // the tie must stand rather than be broken by a guess.
        val assets = mapOf(
            dict("fate" to 5000, "hate" to 5000),
            "predictions/en.txt" to "\u0001\thate happy hello"
        )
        assertEquals(
            listOf("fate", "hate"), verdict(judge(assets), "gate", initial = null).words
        )
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

    // ---- the other language the user writes in ----

    private fun bilingual(assets: Map<String, String>): SpellJudge {
        val engine = SuggestionEngine.forTesting(userData) { path -> assets[path]?.byteInputStream() }
        return SpellJudge(engine, "tr", Locale.forLanguageTag("tr"), "en", en)
    }

    private val twoLanguages = mapOf(
        "dictionaries/tr.txt" to "araba 9000\nevet 8000",
        "dictionaries/en.txt" to "hello 9000\nworld 8000"
    )

    @Test
    fun `a word from the other enabled language is not underlined`() {
        val j = bilingual(twoLanguages)
        assertTrue("turkish is known", inDictionary(verdict(j, "araba")))
        assertTrue("and so is english", inDictionary(verdict(j, "hello")))
    }

    @Test
    fun `a typo in the other enabled language is offered its fix`() {
        // It was underlined and offered nothing. Candidates only ever came
        // from the field's own dictionary, and the alternate was consulted
        // solely to decide not to correct — so the bilingual writer got the
        // underline and no way to act on it.
        val v = verdict(bilingual(twoLanguages), "helol")
        assertTrue("should still be a typo", typo(v))
        assertTrue("and should offer hello, got ${v.words}", "hello" in v.words)
    }

    @Test
    fun `the other language is only asked when the first has nothing`() {
        // What keeps the second scan free in the ordinary case, and stops it
        // ever displacing a fix in the language actually being written.
        val v = verdict(bilingual(twoLanguages), "arabz")
        assertEquals("only the turkish fix", listOf("araba"), v.words)
    }

    @Test
    fun `with no second language nothing changes`() {
        val j = judge(mapOf(dict("hello" to 9000)))
        assertTrue("hello is the fix", "hello" in verdict(j, "helol").words)
    }

    // ---- what comes back, and what does not ----

    @Test
    fun `the fix arrives in the case the typo was typed in`() {
        // The popup writes its answer straight into the field, so a lowercase
        // fix for a capitalised word would put a lowercase word at the front
        // of the sentence.
        val j = judge(mapOf(dict("hello" to 9000)))
        assertEquals(listOf("Hello"), verdict(j, "Helol").words)
        assertEquals(listOf("hello"), verdict(j, "helol").words)
    }

    @Test
    fun `an offensive word is not offered as a fix, unless the filter is off`() {
        // The setting reached the keyboard and not this service until it was
        // wired up, and that was a change with no test attached because the
        // missing piece was an assignment. This is the effect it was missing.
        val assets = mapOf(dict("shine" to 8000, "shite" to 3000),
            "offensive/en.txt" to "shite")
        val engine = SuggestionEngine.forTesting(userData) { path ->
            assets[path]?.byteInputStream()
        }
        val j = SpellJudge(engine, "en", en)

        assertEquals(
            "filtered while the setting is on",
            listOf("shine"), j.verdictFor("shime", "", "", "", 5, true, budget()).words
        )

        engine.blockOffensive = false
        assertTrue(
            "and offered once it is off",
            "shite" in j.verdictFor("shime", "", "", "", 5, true, budget()).words
        )
    }

    @Test
    fun `an adjacent-key repair is recommended`() {
        // The case the flag exists for: k sits next to l, so "hello" is not a
        // guess about what was meant, it is where the thumb landed.
        val j = judge(mapOf(dict("hello" to 9000)))
        val v = verdict(j, "helko")
        assertTrue("underlined", typo(v))
        assertEquals("hello", v.words.first())
        assertTrue("and worth recommending", recommended(v))
    }

    @Test
    fun `a distant repair is offered but never recommended`() {
        // Two deletions out of a six-letter word. The platform documents the
        // recommended flag as the text service saying these are *the*
        // suggestions, and an editor may act on it without asking, so it has to
        // mean something more than "the search returned a row". Before the
        // confidence gate it was set whenever the list came back non-empty,
        // which is how a keyboard ends up replacing somebody's name.
        val j = judge(mapOf(dict("bury" to 9000)))
        val v = verdict(j, "buraya")
        assertTrue("still underlined", typo(v))
        assertTrue("still offered, since choosing it is the user's call",
            v.words.contains("bury"))
        assertFalse("but not recommended", recommended(v))
    }

    @Test
    fun `a contraction is a word, not a misspelling of one`() {
        // The most visible fault this service has had. The keyboard treats an
        // apostrophe as part of a word -- it has to, or "don't" would compose
        // as two -- and the shipped lists come from subtitles, whose tokeniser
        // split at the apostrophe. So the joined form was in no list, and the
        // spell checker underlined "don't", "it's", "can't" and "we'll" in
        // every app on the phone, offering donut, its, cant and well to
        // replace them. French elides in nearly every sentence.
        //
        // The halves are what the list holds, so the halves are what is
        // checked. Both must clear the same floor a compound part does.
        val j = judge(mapOf(dict("don" to 4158644, "'t" to 9628970)))
        val v = verdict(j, "don't")
        assertTrue(
            "a contraction was underlined; suggestions were " + v.words,
            (v.attrs and SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY) != 0
        )
        assertTrue("a known word was given corrections", v.words.isEmpty())
    }

    @Test
    fun `an apostrophe does not make any two words a word`() {
        val j = judge(mapOf(dict("don" to 4158644, "'t" to 9628970)))
        val v = verdict(j, "don'qwerty")
        assertTrue(
            "a word with an unknown half was called known",
            (v.attrs and SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY) == 0
        )
    }
}
