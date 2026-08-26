package com.rimboard.keyboard.engine

import com.rimboard.keyboard.model.Diacritics
import com.rimboard.keyboard.model.GlidePath
import com.rimboard.keyboard.model.KeyProximity
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * What a swipe is allowed to offer.
 *
 * Every path that puts a word in front of the user filters two things: words the
 * user has blocked by hand, and profanity when "Block offensive words" is on —
 * a setting whose own summary reads "Never suggest or autocorrect to profanity".
 * Completions filter both. Corrections filter both. Predictions filter both. The
 * split, the contraction and the accented-form paths all filter both.
 *
 * Gliding filtered neither, and gliding is the one that does not merely offer:
 * its first candidate is committed on the lift, with no keystroke in between.
 * So the one input method that puts a word into the message without being asked
 * was the one input method that would put *that* word into the message, with
 * the switch on.
 *
 * It is the same shape of fault as the second language never reaching the glide
 * decoder, and it went unnoticed for the same reason: `glideFor` builds its
 * candidate list separately from the tapped strip's, so a rule added to one is
 * not a rule added to the other.
 */
class GlideFilterTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-gf", "").let { it.delete(); it.mkdirs(); it }
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
        for (n in listOf("dictionaries/en.txt", "predictions/en.txt", "offensive/en.txt")) {
            File(assets(), n).takeIf { it.exists() }?.let { files[n] = it.readText() }
        }
        return SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
    }

    /** A clean straight-line swipe through the letters of [word]. */
    private fun swipe(word: String): GlidePath {
        val prox = KeyProximity.forLang("en")
        val stops = StringBuilder()
        for (c in word) if (stops.isEmpty() || stops[stops.length - 1] != c) stops.append(c)
        val pts = ArrayList<Float>()
        for (i in 0 until stops.length - 1) {
            val ax = prox.gridX(stops[i]) ?: prox.gridX(Diacritics.fold(stops[i]))!!
            val ay = prox.gridY(stops[i]) ?: prox.gridY(Diacritics.fold(stops[i]))!!
            val bx = prox.gridX(stops[i + 1]) ?: prox.gridX(Diacritics.fold(stops[i + 1]))!!
            val by = prox.gridY(stops[i + 1]) ?: prox.gridY(Diacritics.fold(stops[i + 1]))!!
            for (s in 0..12) {
                val t = s / 12f
                pts.add(ax + (bx - ax) * t)
                pts.add(ay + (by - ay) * t)
            }
        }
        return GlidePath.of(pts.toFloatArray(), prox)!!
    }

    private fun glide(e: SuggestionEngine, word: String): List<String> =
        e.glideFor(swipe(word), "en", Locale.ENGLISH, personalized = true)

    /** A word from the shipped list that a swipe can actually reach. */
    private fun reachableOffensive(e: SuggestionEngine): String {
        val listed = File(assets(), "offensive/en.txt").readText()
            .split(Regex("\\s+")).filter { it.length in 5..10 && it.all { c -> c.isLetter() } }
        e.blockOffensive = false
        return listed.first { glide(e, it).contains(it) }
    }

    @Test
    fun `a swipe does not offer a word the filter is switched on for`() {
        val e = engine()
        val word = reachableOffensive(e)
        // The premise: without the filter this swipe really does produce it, so
        // the assertion below is about the filter and not about the decoder.
        assertTrue(
            "the corpus could not produce a case to test with",
            glide(e, word).contains(word)
        )
        e.blockOffensive = true
        assertTrue(
            "swiping still offers \"$word\" with \"Block offensive words\" on, " +
                "and the first candidate is what the lift commits: ${glide(e, word)}",
            !glide(e, word).contains(word)
        )
    }

    @Test
    fun `a swipe does not offer a word the user blocked by hand`() {
        val e = engine()
        e.blockOffensive = false
        // Any ordinary word will do; blocking is the user saying "not this one".
        val word = listOf("problem", "morning", "brother", "picture")
            .first { glide(e, it).contains(it) }
        userData.blockWord(word)
        assertTrue(
            "swiping still offers \"$word\" after the user blocked it: " +
                glide(e, word),
            !glide(e, word).contains(word)
        )
    }

    @Test
    fun `an ordinary word is still offered`() {
        // The other half: the filters must not be swallowing everything.
        val e = engine()
        e.blockOffensive = true
        assertTrue(
            "an ordinary swipe stopped working",
            glide(e, "morning").contains("morning")
        )
    }
}
