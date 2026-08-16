package com.rimboard.keyboard.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Next-word prediction: how the shipped model and what the user has typed are
 * weighed against each other, and what happens at the start of a message.
 *
 * The old behaviour was a hard cascade — anything the user had ever typed came
 * first, and the curated model only filled the leftovers. That gives a single
 * accidental word pair the top slot for that context indefinitely, which is a
 * bad trade for a model whose whole purpose is to be right on the first guess.
 */
class PredictionTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    private fun engine(assets: Map<String, String>): SuggestionEngine =
        SuggestionEngine.forTesting(userData) { path -> assets[path]?.byteInputStream() }

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-pred", "").let { it.delete(); it.mkdirs(); it }
        userData = UserData.inDir(dir)
    }

    @After
    fun tearDown() {
        userData.shutdown()
        dir.deleteRecursively()
    }

    private val en = Locale.ENGLISH

    /** "see" is followed by "you" then "the" in the shipped-style model. */
    private val model = mapOf(
        "predictions/en.txt" to "see\tyou the what\n\ti the thanks\n"
    )

    @Test
    fun `the curated model answers before anything has been learned`() {
        val e = engine(model)
        assertEquals(listOf("you", "the", "what"), e.predictions("", "see", "en", en, 3))
    }

    @Test
    fun `a single accidental pair does not displace the curated first guess`() {
        // Typed once and never again. Under the old cascade this took the top
        // slot outright and kept it.
        userData.recordBigram("see", "zebra")
        val out = engine(model).predictions("", "see", "en", en, 3)
        assertEquals("you", out.first())
        assertTrue("the one-off should still be offered, just not first: $out", "zebra" in out)
    }

    @Test
    fun `repetition does win`() {
        // Three sightings is a habit rather than an accident, and beats the
        // curated first guess.
        repeat(4) { userData.recordBigram("see", "zebra") }
        assertEquals("zebra", engine(model).predictions("", "see", "en", en, 3).first())
    }

    @Test
    fun `an exact two-word context outranks a curated one-word guess at once`() {
        // "see you" -> "soon" is far more specific evidence than "see" -> "you".
        userData.recordNgram("i", "see", "soon")
        val out = engine(model).predictions("i", "see", "en", en, 3)
        assertEquals("soon", out.first())
    }

    @Test
    fun `the curated list still fills the slots the user has no opinion on`() {
        // The cascade could not do this: strong user evidence for one word used
        // to push the whole model out, leaving thin suggestions.
        repeat(4) { userData.recordBigram("see", "zebra") }
        val out = engine(model).predictions("", "see", "en", en, 3)
        assertEquals("zebra", out.first())
        assertTrue("expected the model to fill the rest: $out", "you" in out)
    }

    // ---- sentence start ----

    @Test
    fun `the start of a message is a context, not the absence of one`() {
        // Previously this returned nothing at all and the strip sat empty.
        val out = engine(model).predictions("", "", "en", en, 3)
        assertEquals(listOf("i", "the", "thanks"), out)
    }

    @Test
    fun `openers are learned from what the user actually starts with`() {
        repeat(4) { userData.recordNgram("", "", "hey") }
        assertEquals("hey", engine(model).predictions("", "", "en", en, 3).first())
    }

    @Test
    fun `a blocked word is never predicted, however often it was typed`() {
        repeat(9) { userData.recordBigram("see", "zebra") }
        userData.blockWord("zebra")
        assertFalse("zebra" in engine(model).predictions("", "see", "en", en, 3))
    }

    // ---- decay ----

    @Test
    fun `counts are halved once the model grows past its cap, and ones are forgotten`() {
        // A repeated habit survives decay; a single accidental pairing does not.
        repeat(20) { userData.recordBigram("keep", "this") }
        userData.recordBigram("drop", "that")
        // Push past the context cap so a decay pass runs.
        repeat(6_001) { i -> userData.recordBigram("ctx$i", "w") }

        assertTrue(
            "a repeated pair must survive decay",
            "this" in userData.predictNext("", "keep", 3)
        )
        assertTrue(
            "a pair seen once must be forgotten",
            userData.predictNext("", "drop", 3).isEmpty()
        )
    }

    @Test
    fun `the decay threshold is reachable given the hard caps`() {
        // The bug this pins: decay triggered at 20,000 contexts while the prune
        // on every save cut the tables back to 10,000, so the halving could
        // never run outside a test that never saves. A threshold above the cap
        // is dead code that looks alive.
        assertTrue(
            "decay must trigger below the caps that prune enforces",
            UserData.decayThreshold() < UserData.hardCapTotal()
        )
    }

    @Test
    fun `pruning drops the least-used contexts, not arbitrary ones`() {
        // Eviction used to be `keys.take(excess)` — whatever the hash map
        // iterated first, which is unrelated to how useful a context is.
        //
        // Deliberately sized so the old behaviour fails reliably rather than
        // occasionally: 100 heavily-used contexts and 4100 one-off ones, with
        // 200 to evict. Every eviction should be a one-off, so all 100 must
        // survive. Picking 200 arbitrarily would take about five of them, and
        // would leave all 100 standing under 1% of the time.
        repeat(100) { i -> repeat(50) { userData.recordBigram("strong$i", "kept") } }
        repeat(4100) { i -> userData.recordBigram("junk$i", "x") }
        userData.flushBlocking()

        val lost = (0 until 100).filter { i ->
            "kept" !in userData.predictNext("", "strong$i", 3)
        }
        assertTrue("heavily-used contexts were evicted: $lost", lost.isEmpty())
    }

    @Test
    fun `the curated spelling of a prediction survives the user's own history`() {
        // German capitalises its nouns, and a prediction is committed exactly
        // as the model spells it — there is no typed prefix to copy a capital
        // from, the way a completion has. Learned n-grams are always stored
        // lower case, so if the two were scored as different words the strip
        // would offer "Dank" and "dank" side by side, competing for one slot.
        val de = Locale.GERMAN
        val e = engine(mapOf("predictions/de.txt" to "vielen\tDank"))
        repeat(5) { userData.recordBigram("vielen", "dank") }

        val out = e.predictions("", "vielen", "de", de, 3)
        assertEquals("the two spellings must collapse to one entry", 1, out.size)
        assertEquals("Dank", out.first())
    }

    @Test
    fun `a capital cannot smuggle a word past the blocked list`() {
        // Blocked words are stored folded, so the check has to be made on the
        // folded key rather than on whatever case the model happens to use.
        val de = Locale.GERMAN
        val e = engine(mapOf("predictions/de.txt" to "keine\tZeit Ahnung"))
        userData.blockWord("zeit")

        assertEquals(listOf("Ahnung"), e.predictions("", "keine", "de", de, 3))
    }
}
