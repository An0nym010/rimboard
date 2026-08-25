package com.rimboard.keyboard.engine

import com.rimboard.keyboard.model.LanguageBoost
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * How well the keyboard knows which of two languages you are writing.
 *
 * Nothing measured this. The mechanism decides which dictionary holds the
 * primary slot on every committed word, it has two thresholds, and both were
 * chosen without a number — so the first thing to establish is what they were
 * worth, and the answer turned out to be "less than they looked, and in the
 * wrong direction".
 *
 * ## The fact the thresholds were chosen without
 *
 * **60% of the words in a mixed English/Turkish passage are in both
 * dictionaries.** These lists come from subtitle corpora and overlap
 * enormously. A word in both counts as evidence for the primary, so "three
 * consecutive words only the second language knows" — the old bar for swapping
 * — is a demanding thing to ask, while "two words the primary knows" is nearly
 * free. The strong signal was held to the higher bar.
 *
 * ## What changed
 *
 *                                  3/2 (was)      1/3 (now)
 *     slot matches the language          72%            88%
 *     pure Turkish: wrong                37%             4%
 *     pure Turkish: flips per 100w      13.2            4.8
 *     pure English: wrong                 0%             0%
 *     engages within a sentence      81/120        120/120
 *     words before it engages           4.6            1.8
 *
 * The median sentence here is six words. The old setting arrived after most of
 * one had been typed, never arrived at all in a third of them, and *flapped
 * more* than the eager setting — which is what a high threshold is usually for.
 *
 * ## And what it is worth, which is little
 *
 * Worth saying plainly so nobody spends real effort here again. Being in the
 * wrong slot cannot destroy a word — a word known to either dictionary is never
 * autocorrected, and destruction measured 0% in every setting including "always
 * boosted". What is left is ranking:
 *
 *     never boost   32.54% of keystrokes saved
 *     3/2           32.72%
 *     1/3           32.78%
 *     an oracle     32.90%
 *
 * A third of a point is the ceiling on the whole mechanism, and glide top-1
 * moves half a point. It is fixed rather than deleted because the fix is a
 * constant, and because being wrong about which language you are writing is a
 * bad thing for a keyboard to be even when the bill is small.
 */
class LanguageBoostAccuracyTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-boost", "").let { it.delete(); it.mkdirs(); it }
        userData = UserData.inDir(dir)
    }

    @After
    fun tearDown() {
        userData.shutdown()
        dir.deleteRecursively()
    }

    private fun assets(): File =
        listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }

    private fun fixtures(): File =
        listOf(File("src/test/fixtures"), File("app/src/test/fixtures")).first { it.isDirectory }

    private fun engine(vararg langs: String): SuggestionEngine {
        val files = HashMap<String, String>()
        for (l in langs) {
            files["dictionaries/$l.txt"] = File(assets(), "dictionaries/$l.txt").readText()
            files["predictions/$l.txt"] = File(assets(), "predictions/$l.txt").readText()
        }
        return SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
    }

    private fun sentences(lang: String, n: Int): List<List<String>> =
        File(fixtures(), "prose_$lang.txt").readLines().filter { it.isNotBlank() }.take(n)
            .map { line ->
                val sb = StringBuilder()
                val out = ArrayList<String>()
                for (c in line) {
                    if (c.isLetter() || c == '\'' || c == '’') sb.append(c)
                    else { if (sb.isNotEmpty()) out.add(sb.toString()); sb.setLength(0) }
                }
                if (sb.isNotEmpty()) out.add(sb.toString())
                out.map { it.trim('\'').lowercase(Locale.forLanguageTag(lang)) }
                    .filter { it.length > 1 }
            }

    private val prim = "en"
    private val alt = "tr"
    private val pLoc = Locale.forLanguageTag("en")
    private val aLoc = Locale.forLanguageTag("tr")

    /** Whether each dictionary knows [w], which is all the machine ever sees. */
    private fun evidenceFor(e: SuggestionEngine, w: String): Pair<Boolean, Boolean> =
        e.knownIn(w.lowercase(pLoc), prim, pLoc) to e.knownIn(w.lowercase(aLoc), alt, aLoc)

    @Test
    fun `the two dictionaries overlap enough to matter`() {
        // The fact the thresholds have to be chosen against. Asserted so that a
        // rebuild of the assets which happened to separate them would make this
        // reasoning visibly stale rather than quietly wrong.
        val e = engine(prim, alt)
        val words = (sentences(prim, 60) + sentences(alt, 60)).flatten()
        val both = words.count { val (p, a) = evidenceFor(e, it); p && a }
        val share = both.toDouble() / words.size
        assertTrue(
            "only ${"%.0f".format(share * 100)}% of words are in both lists; the " +
                "run lengths in LanguageBoost are sized against this overlap",
            share > 0.40
        )
    }

    @Test
    fun `a sentence of the second language engages it, and early`() {
        val e = engine(prim, alt)
        // Each sentence starts from a reset, which is what a fresh field is —
        // and a fresh field is every message in a chat app.
        val passages = sentences(alt, 120).filter { it.size >= 4 }
        var engaged = 0
        var totalAt = 0
        for (p in passages) {
            val b = LanguageBoost()
            var at = -1
            for ((i, w) in p.withIndex()) {
                val (ip, ia) = evidenceFor(e, w)
                b.note(ip, ia)
                if (at < 0 && b.boosted) at = i + 1
            }
            if (at >= 0) { engaged++; totalAt += at }
        }
        val lag = totalAt.toDouble() / engaged
        val report = "engaged in $engaged/${passages.size} sentences after " +
            "${"%.1f".format(lag)} words on average"
        assertTrue(
            "the second language must be recognised in nearly every sentence " +
                "of it; the old thresholds managed 81/120. $report",
            engaged >= passages.size * 95 / 100
        )
        assertTrue(
            "and early enough to matter in a sentence of about six words; the " +
                "old thresholds took 4.6. $report",
            lag <= 2.5
        )
    }

    @Test
    fun `writing one language only never boosts and never flaps`() {
        // The case that must not regress, and the reason an eager threshold is
        // safe: a monolingual English writer never trips it at all.
        val e = engine(prim, alt)
        val b = LanguageBoost()
        var flips = 0
        var boostedWords = 0
        val words = sentences(prim, 80).flatten()
        for (w in words) {
            if (b.boosted) boostedWords++
            val (ip, ia) = evidenceFor(e, w)
            if (b.note(ip, ia)) flips++
        }
        assertTrue(
            "an English-only writer had the slot taken from them $boostedWords " +
                "times in ${words.size} words, over $flips changes",
            boostedWords == 0 && flips == 0
        )
    }

    @Test
    fun `writing the second language holds the slot rather than flapping`() {
        val e = engine(prim, alt)
        val words = sentences(alt, 80).flatten()
        val b = LanguageBoost()
        var flips = 0
        var wrong = 0
        for (w in words) {
            if (!b.boosted) wrong++
            val (ip, ia) = evidenceFor(e, w)
            if (b.note(ip, ia)) flips++
        }
        val report = "wrong for $wrong of ${words.size} words over $flips changes"
        assertTrue(
            "a page of Turkish must mostly be ranked as Turkish; the old " +
                "thresholds were wrong 37% of the time. $report",
            wrong < words.size / 10
        )
        assertTrue(
            "and must not flap while doing it; the old thresholds changed the " +
                "slot 13.2 times per 100 words. $report",
            flips * 100.0 / words.size < 8.0
        )
    }
}
