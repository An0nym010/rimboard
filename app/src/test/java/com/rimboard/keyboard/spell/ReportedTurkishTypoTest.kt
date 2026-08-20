package com.rimboard.keyboard.spell

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
 * The four words from the first real use of this keyboard on a phone.
 *
 * Somebody typed "merhabaaa naberr nasılsınn iyiyimmm" into a Turkish field and
 * long-pressed each one. Every other test in this suite is built from words
 * this project chose; these four were chosen by a person typing quickly, and
 * they are all the same shape — a key held a fraction too long — because that
 * is what actually happens on a phone.
 *
 * Against the real shipped dictionary rather than a fixture, because three of
 * the four only misbehaved in the presence of the real one: the guard that got
 * "nasılsınn" wrong needed a real stem to peel onto.
 *
 * The valuable one is "nasılsınn". It was not merely ranked wrong, it was
 * **accepted** — the Turkish morphology guard peeled the doubled "n" as a
 * suffix, found the real word "nasılsın" underneath, and declared the typo
 * correct. No underline, no suggestion, nothing to notice. A wrong suggestion
 * announces itself; this said nothing at all.
 */
class ReportedTurkishTypoTest {

    private lateinit var dir: File
    private lateinit var userData: UserData
    private val tr: Locale = Locale.forLanguageTag("tr")

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-reported", "").let { it.delete(); it.mkdirs(); it }
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
        val files = listOf("dictionaries/tr.txt", "predictions/tr.txt")
            .associateWith { File(assets(), it).readText() }
        return SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
    }

    private val typed = listOf("merhabaaa", "naberr", "nasılsınn", "iyiyimmm")
    private val meant = listOf("merhaba", "naber", "nasılsın", "iyiyim")

    @Test
    fun `each word reported from the device is judged a typo`() {
        val eng = engine()
        for (w in typed) {
            assertFalse(
                "'$w' is a held key, not a word, and has to be underlined",
                eng.acceptedWord(w, "tr", tr)
            )
        }
    }

    @Test
    fun `and the word that was meant is the first thing offered`() {
        val eng = engine()
        eng.predictions("", "", "tr", tr, 1)
        val judge = SpellJudge(eng, "tr", tr)
        for ((i, w) in typed.withIndex()) {
            val v = judge.verdictFor(
                w, "", if (i == 0) "" else typed[i - 1], typed.getOrNull(i + 1) ?: "",
                5, i == 0, Budget(SpellJudge.CORRECTION_BUDGET)
            )
            assertEquals(
                "the popup for '$w' should lead with '${meant[i]}', and offered ${v.words}",
                meant[i], v.words.firstOrNull()
            )
        }
    }

    @Test
    fun `a typo is not excused by peeling onto corpus noise`() {
        // A 200k-word list built from subtitles holds a great deal that is not
        // a Turkish root, and the guard used to accept any stem present in it
        // at all. These came apart onto "sr" (68 occurrences), "bs" (39) and
        // "hek" (37) and were pronounced correct -- never underlined, never
        // corrected, and with autocorrect on, never noticed.
        val eng = engine()
        for (w in listOf("srlam", "bsyan", "heken", "oeada")) {
            assertFalse(
                "'$w' peels onto corpus noise and is not a word",
                eng.acceptedWord(w, "tr", tr)
            )
        }
    }

    @Test
    fun `a typo is not excused by a suffix that could not follow that stem`() {
        // Each of these peels cleanly by spelling and is impossible by
        // harmony: after u the four-way vowel is u, so "bunın" cannot be
        // "bu" plus the genitive however much it looks like it.
        val eng = engine()
        for (w in listOf("bunın", "sorın", "olaun", "kimae")) {
            assertFalse(
                "'$w' violates vowel harmony and cannot be Turkish",
                eng.acceptedWord(w, "tr", tr)
            )
        }
        // The forms they were typos of are still accepted.
        for (w in listOf("bunun", "sorun")) {
            assertTrue("'$w' is the real word", eng.acceptedWord(w, "tr", tr))
        }
    }

    @Test
    fun `a real Turkish inflection the dictionary lacks is still left alone`() {
        // The other half of the same rule, and the reason it is narrow. The
        // morphology guard exists because Turkish stacks suffixes without limit
        // and no word list holds every surface form, so refusing to peel a
        // repeated letter must not cost the guard its actual job.
        val eng = engine()
        for (w in listOf("kitaplarımızdan", "kalemlerimizden", "arkadaşlarımızla")) {
            assertTrue(
                "'$w' is ordinary Turkish and absent from the list; it must not " +
                    "be corrected",
                eng.acceptedWord(w, "tr", tr)
            )
        }
    }
}
