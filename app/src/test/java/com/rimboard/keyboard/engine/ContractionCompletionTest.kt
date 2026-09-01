package com.rimboard.keyboard.engine

import com.rimboard.keyboard.model.Contractions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Typing `I'` offered nothing, and every other pronoun completed.
 *
 *     he'    ->  he', he's, he'll
 *     you'   ->  you', you're, you've
 *     they'  ->  they', they, they're
 *     don'   ->  don', don't
 *     i'     ->  i'
 *
 * `I` is the commonest noun-phrase in English — 27,086,011 in the shipped list,
 * second only to `you` — and `'m` is the 26th most frequent entry of any kind
 * at 4,386,306. So the one word whose contractions are used most was the one
 * word that had none of them offered.
 *
 * ## Two bugs stacked, and repairing either alone changes nothing
 *
 * Both come from the same place: `I'm`, `I've` and `I'll` are the only entries
 * in [Contractions] whose canonical spelling carries a capital, because in
 * English that capital is part of the word rather than a position in a
 * sentence.
 *
 *  - [Contractions.completionsFor] asked `canonical.startsWith(prefixLower)`.
 *    `"I'm".startsWith("i'")` is false. The function was correct for an
 *    upper-case prefix and its only caller has always lower-cased.
 *  - The caller then looks up the stem's frequency to score the candidate, and
 *    `Dictionary.frequency` takes a lower-case word. The stem of `I'm` is `I`,
 *    which reads zero, and the guard is `f > 0`.
 *
 * Measured as letters saved over the twenty-two commonest English
 * contractions, out of 113 letters: **16 before, 16 with only the case fixed,
 * 16 with only the stem fixed, 20 with both.** `i'm` falls from three
 * keystrokes to two, `i've` from four to two, `i'll` from four to three.
 *
 * ## The case the chip carries
 *
 * The completion is offered as `I'm` whether `i'` or `I'` was typed, and that
 * is deliberate. [WordCase.match] only ever *raises* a first letter, so a
 * canonical that already begins with a capital comes through untouched — which
 * is the same treatment the restoration direction has always given `im ->
 * I'm`, and the same reason [WordCase.pronoun] exists.
 */
class ContractionCompletionTest {

    private lateinit var dir: File
    private lateinit var userData: UserData
    private val en = Locale.ENGLISH

    @Before
    fun setUp() {
        dir = File.createTempFile("contrcomp", "").let { it.delete(); it.mkdirs(); it }
        userData = UserData.inDir(dir)
    }

    @After
    fun tearDown() {
        userData.shutdown()
        dir.deleteRecursively()
    }

    private fun assets(): File =
        listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }

    private fun engine(lang: String): SuggestionEngine {
        val files = listOf("dictionaries/$lang.txt", "predictions/$lang.txt")
            .filter { File(assets(), it).isFile }
            .associateWith { File(assets(), it).readText() }
        return SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
    }

    /** The first half: the table answers a lower-cased prefix. */
    @Test
    fun `the table is asked in the case its caller uses`() {
        val lower = Contractions.completionsFor("en", "i'")
        assertTrue(
            "\"i'\" reaches none of the I-contractions, so nothing downstream can " +
                "offer them; got $lower",
            lower.containsAll(listOf("I'm", "I've", "I'll"))
        )
        assertEquals(
            "and the two cases must agree, or the answer depends on the shift key",
            lower.toSet(), Contractions.completionsFor("en", "I'").toSet()
        )
    }

    /** The second half: the stem is looked up in the case the dictionary holds. */
    @Test
    fun `the stem frequency is found`() {
        val d = engine("en").dictionary("en", en)
        assertTrue(
            "the English list has no lower-case \"i\", so this test is measuring " +
                "the wrong thing",
            d.frequency("i") > 1_000_000
        )
        assertEquals(
            "the dictionary is lower-case; anything looking a stem up verbatim " +
                "off a canonical like \"I'm\" reads zero",
            0, d.frequency("I")
        )
    }

    /** Both halves together, which is the only way the strip changes. */
    @Test
    fun `typing an apostrophe after I offers its contractions`() {
        val e = engine("en")
        for (typed in listOf("i'", "I'")) {
            val items = e.suggestionsFor(
                typed, "en", en, allowAutocorrect = true, personalized = false
            ).items
            assertEquals("slot 0 must be what was typed", typed, items.first())
            assertTrue(
                "typing \"$typed\" offered $items, with no contraction of the " +
                    "commonest word in the language in it",
                items.any { it == "I'm" }
            )
            // Capitalised, whichever case the pronoun was typed in: the
            // canonical carries the capital and matchCase never lowers one.
            assertTrue(
                "the chip must read \"I'm\", not \"i'm\": $items",
                items.none { it == "i'm" }
            )
        }
    }

    /**
     * Every pronoun, so this is not the one word somebody noticed.
     *
     * The generalisation of the bug rather than the instance: a canonical whose
     * spelling is not reachable from its own lower-cased prefix is unreachable,
     * whatever the reason.
     */
    @Test
    fun `every contraction is reachable from its own prefix`() {
        val unreachable = StringBuilder()
        for (canonical in Contractions.allCanonical("en")) {
            val cut = canonical.indexOf('\'')
            if (cut <= 0) continue
            val prefix = canonical.substring(0, cut + 1).lowercase(en)
            if (!Contractions.completionsFor("en", prefix).contains(canonical)) {
                unreachable.append("\n  \"$prefix\" does not reach \"$canonical\"")
            }
        }
        assertEquals(
            "a contraction cannot be completed from the prefix somebody would " +
                "have to type to get it:$unreachable",
            "", unreachable.toString()
        )
    }

    /** And the pronouns that always worked still do. */
    @Test
    fun `the other pronouns are unchanged`() {
        val e = engine("en")
        val missing = listOf(
            "he'" to "he's", "you'" to "you're", "they'" to "they're",
            "don'" to "don't", "that'" to "that's", "what'" to "what's",
            "can'" to "can't", "there'" to "there's"
        ).filterNot { (typed, wanted) ->
            e.suggestionsFor(typed, "en", en, allowAutocorrect = true, personalized = false)
                .items.any { it.equals(wanted, ignoreCase = true) }
        }
        assertEquals(
            "a contraction that always completed stopped.",
            emptyList<Pair<String, String>>(), missing
        )
    }
}
