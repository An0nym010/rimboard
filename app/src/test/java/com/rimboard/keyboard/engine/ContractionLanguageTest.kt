package com.rimboard.keyboard.engine

import com.rimboard.keyboard.model.Contractions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * A bare English contraction is sometimes an ordinary word in the user's other
 * language.
 *
 * `Contractions` overrides a corpus that stripped apostrophes, so "dont" and
 * "im" are treated as missing apostrophes rather than words. That is right for
 * English and wrong for anybody typing beside it: **"im" is German for "in
 * the"** and **"dont" is the French relative pronoun**, both among the
 * commonest words in those languages. The rule ran on the effective language
 * alone, so with the boost sitting on English -- which is where it sits for
 * anybody dropping a German phrase into English writing -- the space bar turned
 * them into "I'm" and "don't".
 *
 * The demotion is auto to suggest. The chip stays; only the automatic commit
 * stops.
 */
class ContractionLanguageTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-contr", "").let { it.delete(); it.mkdirs(); it }
        userData = UserData.inDir(dir)
    }

    @After
    fun tearDown() {
        userData.shutdown()
        dir.deleteRecursively()
    }

    private fun assets(): File =
        listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }

    private fun engine(vararg langs: String): SuggestionEngine {
        val files = HashMap<String, String>()
        for (l in langs) {
            files["dictionaries/$l.txt"] = File(assets(), "dictionaries/$l.txt").readText()
        }
        return SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
    }

    private fun loc(l: String) = Locale.forLanguageTag(l)

    private fun autoFor(word: String, alt: String?): Boolean? {
        val e = if (alt == null) engine("en") else engine("en", alt)
        return e.contractionFor(
            word, "en", Locale.ENGLISH, alt, alt?.let { loc(it) }
        )?.second
    }

    @Test
    fun `alone, an unambiguous contraction still commits itself`() {
        assertEquals(true, autoFor("dont", null))
        assertEquals(true, autoFor("im", null))
    }

    @Test
    fun `German im is not turned into I'm`() {
        assertEquals(false, autoFor("im", "de"))
    }

    @Test
    fun `French dont is not turned into don't`() {
        assertEquals(false, autoFor("dont", "fr"))
    }

    @Test
    fun `the chip is still offered, only the commit stops`() {
        // The demotion must not take the contraction away: somebody typing
        // English on a German keyboard still means "I'm" and can tap for it.
        val e = engine("en", "de")
        val out = e.contractionFor("im", "en", Locale.ENGLISH, "de", loc("de"))
        assertNotNull("the contraction disappeared entirely", out)
        assertEquals("I'm", out!!.first)
        assertFalse("it still commits itself", out.second)
    }

    @Test
    fun `English in the other list does not break the feature it exists for`() {
        // These corpora are subtitle text with English in them, so Turkish
        // holds "dont" at 39 occurrences and Spanish at 41. Refusing on
        // presence would break the case the whole object was written for.
        assertEquals("Turkish corpus noise disabled the fix", true, autoFor("dont", "tr"))
        assertEquals("Spanish corpus noise disabled the fix", true, autoFor("dont", "es"))
        assertEquals("Danish corpus noise disabled the fix", true, autoFor("dont", "da"))
    }

    @Test
    fun `every auto form against every other shipped language`() {
        // The population, not a sample: 44 forms against 21 dictionaries. The
        // expected set is the measurement, so a dictionary rebuild that moves
        // a word across the line has to be looked at rather than absorbed.
        val langs = assets().resolve("dictionaries").list().orEmpty()
            .filter { it.endsWith(".txt") }
            .map { it.removeSuffix(".txt") }
            .filter { it != "en" }
            .sorted()
        val forms = Contractions.autoForms("en").sorted()
        val demoted = sortedSetOf<String>()
        for (lang in langs) {
            val e = engine("en", lang)
            for (w in forms) {
                val r = e.contractionFor(w, "en", Locale.ENGLISH, lang, loc(lang))
                if (r != null && !r.second) demoted.add("$lang $w")
            }
        }
        val expected = sortedSetOf(
            // The bug: ordinary words in their own language.
            "de im", "hr im", "sk im", "pl im", "fr dont", "tr im",
            // Marginal: both sides are corpus noise and the other side is a
            // hair higher. Costs a chip that must be tapped.
            "id im", "da yall", "ro hadnt"
        )
        assertEquals(
            "the set of demoted pairs moved; each new one is a contraction that " +
                "stopped committing itself, and each missing one is a word that " +
                "started being overwritten in somebody's language",
            expected, demoted
        )
    }

    @Test
    fun `no bare form is in both confidence lists`() {
        // "youd" was in both: the auto list said its bare form is never an
        // English word, which is the criterion for auto, and the suggest list
        // said it was ambiguous. Only one of those can be true, and the suggest
        // copy was dead code behind the auto lookup.
        val both = Contractions.autoForms("en") intersect Contractions.suggestForms("en")
        assertTrue("in both lists, so the two disagree about it: $both", both.isEmpty())
    }
}
