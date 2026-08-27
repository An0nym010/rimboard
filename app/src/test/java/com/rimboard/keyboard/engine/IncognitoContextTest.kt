package com.rimboard.keyboard.engine

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Incognito withholds the words, and used to rank them by history anyway.
 *
 * `predictions` takes a `personalized` flag and its own comment says what it
 * is for: false "leaves the learned n-grams out entirely and answers from the
 * bundled model alone... **Nothing about the user reaches the strip on this
 * path.**"
 *
 * `contextRankFor` is the other consumer of that function. It ranks
 * *corrections* by what the preceding word predicts, and it called
 * `predictions` without the flag, so the default took over. All three of its
 * callers -- `correctionFor`, `suggestionsFor`, `glideFor` -- have
 * `personalized` in scope and none of them passed it on.
 *
 * So the list of candidates was correctly stripped of the user's vocabulary in
 * incognito, and then put in an order their own typing had chosen. The word at
 * the top of that order is the one the space bar commits.
 *
 * Nothing about the *content* escapes: every candidate here is a word the
 * shipped dictionary already holds. What escapes is which of them comes first,
 * which is a fact about this user and no one else.
 */
class IncognitoContextTest {

    private lateinit var dir: File
    private lateinit var userData: UserData
    private val en: Locale = Locale.ENGLISH

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-incogctx", "").let { it.delete(); it.mkdirs(); it }
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

    /** What typing "$prev $next" over and over teaches the store. */
    private fun teach(prev: String, next: String, times: Int = 30) {
        repeat(times) { userData.recordNgram("", prev, next) }
    }

    private fun correction(
        e: SuggestionEngine, typed: String, prev: String, personalized: Boolean
    ): String? = e.correctionFor(
        typed, "en", en, prevWord = prev, personalized = personalized
    )

    /**
     * A typo whose correction the user's own n-grams can move.
     *
     * Searched for rather than written down, because it has to be a case where
     * two readings are close enough that evidence decides -- exactly the cases
     * context exists for. Returns the typo, the answer without history, and
     * the answer the learned n-gram pulls it to.
     */
    private fun findMovableCase(e: SuggestionEngine, prev: String): Triple<String, String, String>? {
        val words = File(assets(), "dictionaries/en.txt").readText()
            .lineSequence().take(4000).mapNotNull { it.split(" ").firstOrNull() }
            .filter { it.length in 4..6 && it.all { c -> c.isLetter() } }
            .toList()
        for (w in words) {
            // One adjacent-key slip, the commonest kind of typo there is.
            for (i in w.indices) {
                val near = NEIGHBOURS[w[i]] ?: continue
                val typo = w.substring(0, i) + near + w.substring(i + 1)
                val plain = correction(e, typo, prev, personalized = false) ?: continue
                val others = e.correctionCandidates(typo, "en", en, limit = 4)
                    .filter { it != plain }
                for (other in others.take(2)) {
                    val probe = UserData.inDir(
                        File.createTempFile("probe", "").let { it.delete(); it.mkdirs(); it }
                    )
                    repeat(40) { probe.recordNgram("", prev, other) }
                    val e2 = SuggestionEngine.forTesting(probe) { path ->
                        assetText[path]?.byteInputStream()
                    }
                    e2.predictions("", prev, "en", en, 1)
                    val pulled = e2.correctionFor(
                        typo, "en", en, prevWord = prev, personalized = true
                    )
                    probe.shutdown()
                    if (pulled == other && plain != other) return Triple(typo, plain, other)
                }
            }
        }
        return null
    }

    private val assetText: Map<String, String> by lazy {
        listOf("dictionaries/en.txt", "predictions/en.txt")
            .associateWith { File(assets(), it).readText() }
    }

    private companion object {
        /** One key to the right on a QWERTY row, which is a typo, not a word. */
        val NEIGHBOURS = mapOf(
            'a' to 's', 's' to 'd', 'd' to 'f', 'e' to 'r', 'r' to 't',
            'o' to 'p', 'i' to 'o', 'n' to 'm', 'c' to 'v', 't' to 'y'
        )
    }

    @Test
    fun `a learned continuation does not decide the correction in incognito`() {
        val e = engine()
        val prev = "the"
        val case = findMovableCase(e, prev)
        assertTrue("the corpus produced no case where context moves the answer", case != null)
        val (typo, plain, pulled) = case!!

        // The premise, restated as an assertion: with history, this user's own
        // n-grams decide the answer.
        val probeDir = File.createTempFile("incogctx2", "").let { it.delete(); it.mkdirs(); it }
        val probe = UserData.inDir(probeDir)
        repeat(40) { probe.recordNgram("", prev, pulled) }
        val e2 = SuggestionEngine.forTesting(probe) { path -> assetText[path]?.byteInputStream() }
        e2.predictions("", prev, "en", en, 1)
        assertTrue(
            "the learned n-gram no longer moves \"$typo\", so this proves nothing",
            e2.correctionFor(typo, "en", en, prevWord = prev, personalized = true) == pulled
        )

        val incognito = e2.correctionFor(typo, "en", en, prevWord = prev, personalized = false)
        probe.shutdown()
        probeDir.deleteRecursively()
        assertTrue(
            "incognito corrected \"$typo\" to \"$incognito\" because this user " +
                "has typed \"$prev $pulled\" before; without history it is " +
                "\"$plain\". The word this picks is the one the space bar commits.",
            incognito == plain
        )
    }
}
