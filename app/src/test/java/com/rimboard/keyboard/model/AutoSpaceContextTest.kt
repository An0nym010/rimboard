package com.rimboard.keyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "Auto-space after punctuation" inserted a space into addresses.
 *
 * The setting saves the space keystroke after a sentence, and it decided on
 * the presence of a full stop alone. On the device, with the setting on:
 *
 * ```
 * typed "example.com"  ->  "Example. com"
 * typed "e.g."         ->  "E. g."
 * ```
 *
 * An address cut in half and the commonest abbreviation in written English
 * pulled apart. Nothing announces either one -- no chip, no underline, no
 * bold -- so it is corrected by the writer noticing, or not at all.
 *
 * The keyboard already holds the opinion that fixes this. [ProseContext] was
 * written so autocorrect would stop rewriting words inside URLs, on the
 * grounds that "a URL is not language", and its answer was one function away
 * from the code inserting the space. The two halves of the app now read the
 * same definition.
 *
 * The two tests below are the two directions: the shapes that must be left
 * alone, and the sentence endings that must still be helped. The third is the
 * price, counted rather than asserted.
 */
class AutoSpaceContextTest {

    /** The service's own set: punctuation that leans on the word before it. */
    private val marks = ".,!?;:"

    private fun takesSpace(before: String) = ProseContext.punctuationTakesSpace(before, marks)

    @Test
    fun `a space is not poured into an address`() {
        val broken = listOf(
            "user@example.",        // an email, mid-domain
            "write to user@gogle.", // the same with a sentence in front of it
            "www.example.",         // the second dot of a bare domain
            "docs.gogle.com/a.",    // a path
            "https://gogle.com.",   // a scheme
            "page.html?",           // a query string opening
            "v2.",                  // a version, by its digit
            "192.168.1.",           // an address made of digits
            "snake_case.",          // an identifier by its underscore
            "e.",                   // "e.g." -- one letter and a full stop
            "i.",
            "U.S.",                 // and the third stop of an initialism
            "www.",                 // one distinct letter, the same rule
            "see www.",             // with a sentence in front of it
            // Auto-capitalisation reaches the word before this does, so at
            // the start of a field it is "Www" -- two distinct characters,
            // and it got its space on the device until this was folded.
            "Www.", "WWW."
        )
        val leaks = broken.filter { takesSpace(it) }
        assertEquals(
            "a space was inserted into something that is not a sentence: $leaks",
            emptyList<String>(), leaks
        )
    }

    @Test
    fun `and the feature still does its job on prose`() {
        val helped = listOf(
            "Hi.", "hello there.", "Really!", "why not?", "one, ",
            "wait...", "so;", "listen:",
            // French writes a narrow no-break space before ? ! ; and :, which
            // leaves the mark alone in its own run. Five of these are in the
            // corpus and an earlier draft of the rule refused all five.
            "Suis-je un robot ?", " ;",
            // And the same shape with a full stop, which is the one that
            // reaches the second rule with nothing to index into.
            ".", "hello . ",
            // The bare first label is the known hole, named in
            // [ProseContext.punctuationTakesSpace] rather than papered over:
            // at this moment "example." and "sentence." are the same string.
            "example."
        ).map { it.trimEnd(' ') }
        val missed = helped.filterNot { takesSpace(it) }
        assertEquals(
            "the setting stopped saving the keystroke it exists to save: $missed",
            emptyList<String>(), missed
        )
    }

    @Test
    fun `punctuation with no space after it is not this function's business`() {
        // Armed by the *mark*; a letter or a space at the cursor means the
        // caller should never have asked.
        assertFalse(takesSpace("hello"))
        assertFalse(takesSpace("hello. "))
        assertFalse(takesSpace(""))
        assertTrue(takesSpace("hello."))
    }

    private fun path(rel: String): File =
        listOf(File(rel), File("app/$rel")).first { it.exists() }

    /**
     * The rule above is decoration unless the keyboard asks it.
     *
     * `RimBoardService` is an `InputMethodService` and cannot be instantiated
     * here, so the wiring is checked by reading it: the one place that decides
     * whether to insert the space must go through
     * [ProseContext.punctuationTakesSpace], and it must read back far enough
     * to see the token rather than the single character it used to fetch.
     * A one-character read is the whole bug -- it can only ever answer "yes,
     * there is a full stop behind me".
     */
    @Test
    fun `the service asks this before it inserts anything`() {
        val service = path("src/main/java/com/rimboard/keyboard/RimBoardService.kt").readText()
        val decision = Regex(
            """private fun punctuationTakesSpace\(\)[\s\S]{0,400}?(?=\n    (private|fun|/\*\*))"""
        ).find(service)
        assertTrue("punctuationTakesSpace() is gone; find what replaced it", decision != null)
        val body = decision!!.value
        assertTrue(
            "the auto-space decision no longer consults ProseContext: $body",
            // Qualified, because the service's own function now carries the
            // same name -- the unqualified string would match the signature
            // the regex just captured and prove nothing.
            body.contains("ProseContext.punctuationTakesSpace")
        )
        assertTrue(
            "it reads a fixed number of characters instead of ProseContext.LOOKBACK, " +
                "which is how it came to answer for \"example.\" what it meant for \"Hi.\": $body",
            body.contains("ProseContext.LOOKBACK")
        )
    }

    /**
     * What the rule costs, over every prose fixture in the project.
     *
     * The population is each place a mark is followed by a space and a letter
     * -- the keystroke this feature exists to save. A suppression there costs
     * the writer a space they were already typing. An insertion in the wrong
     * place costs them a broken address they have to spot first, so the two
     * are not the same price and this errs towards leaving text alone.
     *
     * Measured 2026-08-31: 3 of 1165, 0.26% -- Croatian "20." (an ordinal
     * date, caught by the digit) and Polish "P. Smith" and "P. Brown" (the
     * honorific, caught by the single letter). All three are printed by this
     * test, because a rule that starts eating sentences will do it here first.
     */
    @Test
    fun `it takes almost nothing away from ordinary prose`() {
        val files = (path("src/test/fixtures").listFiles().orEmpty().toList() +
            path("src/test/fixtures/heldout").listFiles().orEmpty().toList())
            .filter { it.name.startsWith("prose_") }
        assertTrue("no prose fixtures found", files.size >= 22)

        var population = 0
        val casualties = ArrayList<String>()
        for (f in files) {
            val t = f.readText()
            for (i in t.indices) {
                if (t[i] !in marks) continue
                if (i + 2 >= t.length || t[i + 1] != ' ' || !t[i + 2].isLetter()) continue
                population++
                if (!ProseContext.punctuationTakesSpace(t.substring(0, i + 1), marks)) {
                    var lo = i
                    while (lo > 0 && !t[lo - 1].isWhitespace()) lo--
                    casualties.add("${f.name.removePrefix("prose_").removeSuffix(".txt")}:" +
                        t.substring(lo, minOf(t.length, i + 8)).replace("\n", " "))
                }
            }
        }
        val rate = 100.0 * casualties.size / population
        println("auto-space suppressed %d of %d (%.2f%%): %s"
            .format(casualties.size, population, rate, casualties))

        assertTrue("no population to measure against", population >= 1000)
        // 1% is four times the measured 0.26%. It is a ceiling on the rule
        // growing teeth, not a target: eight more sentences would trip it.
        assertTrue(
            "the rule now refuses %.2f%% of ordinary sentence punctuation: %s"
                .format(rate, casualties),
            rate < 1.0
        )
    }
}
