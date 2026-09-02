package com.rimboard.keyboard.engine

import com.rimboard.keyboard.model.GlidePath
import com.rimboard.keyboard.model.KeyProximity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Three things learn from a commit, and only one of them was told when the
 * commit was wrong.
 *
 * [com.rimboard.keyboard.model.GlideCorrectionEvidenceTest] made this argument
 * already, at this exact call site, and it is worth quoting because it is the
 * whole of this one too: *"the glide's own commit had already noted the word
 * the user went on to reject, and nothing ever corrected it: the evidence on
 * record was the wrong word, and the tap that said so was thrown away."*
 *
 * That was fixed for [com.rimboard.keyboard.model.LanguageBoost], the machine
 * that decides which of two languages holds the primary slot. A commit feeds
 * two other machines, and neither of them was carried along:
 *
 *     LanguageBoost      noteCommittedWord     told (fixed earlier)
 *     next-word n-grams  recordNgram           **not told**
 *     learned words      learnWord             **not told**
 *
 * So the same sentence applied, unchanged, to two more machines. And for the
 * n-grams it is worse than an omission: the swipe files its guess as the next
 * word after this context *before* the user can object, so the correction left
 * the model holding a count for the rejected word and none at all for the
 * chosen one. The next time that context came round, the keyboard predicted
 * the mistake **more** confidently than it had before being corrected.
 *
 * ## The same fault on the other correction gesture
 *
 * Backspace immediately after an autocorrect is the other way a user says the
 * keyboard was wrong, and it had the same hole. `performRevert` called
 * [UserData.markKnown] on the original — so the word stops being corrected —
 * and said nothing to the n-grams, which had already been given the word being
 * taken away.
 *
 * ## Why the context has to be carried rather than re-read
 *
 * `prevWordForBigram`'s setter shifts `prevWord2` on every commit, so by the
 * time a correction happens the word *before* the one being corrected is gone.
 * Filing the correction under what is left would file it under half the
 * context the mistake was filed under, which is not a correction of it. Both
 * paths therefore carry what they filed and where: `Revert.ngramContext` and
 * `glideNgram`.
 */
class CorrectionEvidenceTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("evidence", "").let { it.delete(); it.mkdirs(); it }
        userData = UserData.inDir(dir)
    }

    @After
    fun tearDown() {
        userData.shutdown()
        dir.deleteRecursively()
    }

    private fun assets(): File =
        listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }

    /** No assets at all, so only what the user has done can answer. */
    private fun bareEngine(): SuggestionEngine =
        SuggestionEngine.forTesting(userData) { null }

    private fun engineWithEnglish(): SuggestionEngine {
        val files = HashMap<String, String>()
        files["dictionaries/en.txt"] = File(assets(), "dictionaries/en.txt").readText()
        return SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
    }

    private fun predicted(prev2: String, prev1: String): List<String> =
        bareEngine().predictions(prev2, prev1, "en", Locale.ENGLISH, 5, personalized = true)
            .map { it.lowercase(Locale.ROOT) }

    /**
     * What the shipped sequence did: file the guess, then throw the correction
     * away.
     */
    @Test
    fun `an uncorrected record leaves the rejected word as the only evidence`() {
        userData.recordNgram("the", "quick", "brownish")
        assertEquals(
            "with only the swipe's own record, the rejected word is what the " +
                "next identical context predicts",
            listOf("brownish"), predicted("the", "quick")
        )
    }

    /** And what the correction now does to it. */
    @Test
    fun `a correction replaces the evidence it corrects`() {
        userData.recordNgram("the", "quick", "brownish")
        // Exactly the pair of calls replaceLastGlideWith and performRevert make.
        userData.forgetNgram("the", "quick", "brownish")
        userData.recordNgram("the", "quick", "brown")
        assertEquals(
            "the corrected word is what the same context now predicts",
            listOf("brown"), predicted("the", "quick")
        )
    }

    /**
     * Both halves matter, and the forget is the half that is easy to leave out.
     *
     * Recording the correction without taking back the mistake leaves the two
     * words on one count each — a tie, which misrepresents a user who was
     * shown one and chose the other.
     */
    @Test
    fun `recording without forgetting only makes it a tie`() {
        userData.recordNgram("the", "quick", "brownish")
        userData.recordNgram("the", "quick", "brown")
        assertEquals(
            "both words should still be on record here; this test exists to " +
                "show why the forget is needed",
            setOf("brownish", "brown"), predicted("the", "quick").toSet()
        )
    }

    /** The trigram and the bigram both come back, because both were written. */
    @Test
    fun `forgetting takes back the bigram as well as the trigram`() {
        userData.recordNgram("the", "quick", "brownish")
        userData.forgetNgram("the", "quick", "brownish")
        assertEquals(
            "the bigram survived the forget, so the word comes back the moment " +
                "the two-word context does not match",
            emptyList<String>(), predicted("", "quick")
        )
    }

    /** A count that was earned more than once is reduced, not erased. */
    @Test
    fun `a word recorded twice keeps a count after one correction`() {
        repeat(2) { userData.recordNgram("the", "quick", "brownish") }
        userData.forgetNgram("the", "quick", "brownish")
        assertEquals(
            "one correction erased evidence the user had given twice",
            listOf("brownish"), predicted("the", "quick")
        )
    }

    /** And a pair that was never recorded is not an error. */
    @Test
    fun `forgetting something never recorded does nothing`() {
        userData.recordNgram("the", "quick", "brown")
        userData.forgetNgram("the", "quick", "never-seen")
        userData.forgetNgram("no", "context", "here")
        assertEquals(listOf("brown"), predicted("the", "quick"))
    }

    /**
     * The third machine: the corrected word joins the learned list, so the same
     * shape decodes to it next time instead of needing the same correction.
     *
     * [UserData.glideCandidates] walks the learned list, so this is the whole
     * mechanism — a swipe that had to be corrected once should not have to be
     * corrected again.
     */
    @Test
    fun `the corrected word is offered for the same swipe afterwards`() {
        val e = engineWithEnglish()
        val prox = KeyProximity.forLang("en")
        // A shape the dictionary has no word for, which is the case the
        // learned list exists to answer: the user swipes it, gets something
        // else, and corrects it.
        val word = "brownisch"
        assertTrue(
            "\"$word\" is in the shipped list now; this needs a word that is not",
            !e.dictionary("en", Locale.ENGLISH).contains(word)
        )
        val pts = ArrayList<Float>()
        var px: Float? = null
        var py: Float? = null
        for (c in word) {
            val x = prox.gridX(c) ?: error("no key for $c")
            val y = prox.gridY(c) ?: error("no key for $c")
            val lx = px
            val ly = py
            if (lx != null && ly != null) {
                for (k in 1..6) {
                    val t = k / 6f
                    pts.add(lx + (x - lx) * t)
                    pts.add(ly + (y - ly) * t)
                }
            } else {
                pts.add(x)
                pts.add(y)
            }
            px = x
            py = y
        }
        val path = GlidePath.of(pts.toFloatArray(), prox) ?: error("no path")
        val before = e.glideFor(path, "en", Locale.ENGLISH, personalized = true)
        assertTrue(
            "the word is already offered, so this measures nothing",
            !before.contains(word)
        )
        // The one call replaceLastGlideWith now makes on the correction.
        userData.learnWord(word)
        val after = engineWithEnglish()
            .glideFor(path, "en", Locale.ENGLISH, personalized = true)
        assertEquals(
            "correcting a swipe did not teach the word, so the same shape needs " +
                "the same correction every time; it offered $after",
            word, after.firstOrNull()
        )
    }

    // ---- and that the service actually makes those calls -------------------

    private fun src(): File =
        listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }

    /**
     * The parentheses are load-bearing: a scan for the bare name passes on the
     * comment above the call, which is how a guard of this shape has already
     * been fooled once in this project.
     */
    private fun bodyOf(name: String): String {
        val svc = src().resolve("com/rimboard/keyboard/RimBoardService.kt").readText()
        val start = svc.indexOf("private fun $name(")
        assertTrue("$name is gone; this scan needs rewriting", start >= 0)
        val next = Regex("\n    private fun ").find(svc, start + 10)?.range?.first ?: svc.length
        return svc.substring(start, next)
    }

    @Test
    fun `replacing a glided word corrects the model as well as the screen`() {
        val body = bodyOf("replaceLastGlideWith")
        for (call in listOf("userData.forgetNgram(", "userData.recordNgram(", "userData.learnWord(")) {
            assertTrue(
                "replaceLastGlideWith no longer calls $call, so the rejected " +
                    "word stays on the record and the chosen one never gets there",
                body.contains(call)
            )
        }
    }

    @Test
    fun `reverting an autocorrect corrects the model as well as the screen`() {
        val body = bodyOf("performRevert")
        for (call in listOf("userData.forgetNgram(", "userData.recordNgram(")) {
            assertTrue(
                "performRevert no longer calls $call, so backspace moves the " +
                    "text and leaves the evidence pointing at the correction",
                body.contains(call)
            )
        }
    }
}
