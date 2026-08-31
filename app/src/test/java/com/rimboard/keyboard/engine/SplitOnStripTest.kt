package com.rimboard.keyboard.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * The split was computed for "alot" and thrown away.
 *
 * `splitFor("alot")` returns "a lot". The strip showed `[alot, alots, a lot]`
 * only after this fix; before it, `[alot, alots, alotta]`. The split was given
 * a free slot rather than a reserved one, on an argument written into the code:
 *
 * > A word that really is two words run together has no continuations to lose
 * > to: nothing in the dictionary follows "alot", so the split still gets its
 * > chip in exactly the case it exists for.
 *
 * The dictionary holds "alots" and "alotta". The premise was false for the one
 * word the feature is named for, and the comment had never been checked against
 * the list it was describing.
 *
 * ## How wide, measured
 *
 * Every adjacent word pair in the 22 prose fixtures, joined, where the rule
 * finds the right split: **24,981 of them, and 9,952 never reached the strip.**
 *
 * It failed hardest where it was most warranted. A run-together the corpus
 * actually holds is a word, so it has completions, so it lost its slot:
 *
 *     attested run-togethers reaching the strip
 *     en  203/514 -> 466/514      es   19/107 -> 92/107
 *     ro   10/73  ->  65/73       cs    9/43  -> 41/43
 *     sv  122/256 -> 246/256      sk   12/63  -> 60/63
 *
 * ## Attested is the line, and it is not arbitrary
 *
 * It is what gives [Dictionary.splitInto] its evidence. `SPLIT_DOMINANCE` asks
 * that the typed word be 150 times rarer than both halves, and that test is
 * *skipped* when the word has no count at all -- which is exactly how "airpo"
 * reaches "air po". Where the guard had a say, the split is a finding; where it
 * did not, it goes on taking only a chip nobody else wanted.
 *
 * ## What it cost
 *
 * 0.1 points of English keystroke savings (34.4% to 34.3% blind, 43.9% to
 * 43.8% with context), nothing measurable in Turkish, and nothing at all in
 * autocorrect accuracy. See `StripAccuracyTest`.
 */
class SplitOnStripTest {

    private lateinit var dir: File
    private lateinit var userData: UserData
    private val en = Locale.ENGLISH

    @Before
    fun setUp() {
        dir = File.createTempFile("splitstrip", "").let { it.delete(); it.mkdirs(); it }
        userData = UserData.inDir(dir)
    }

    @After
    fun tearDown() {
        userData.shutdown()
        dir.deleteRecursively()
    }

    private fun assets(): File =
        listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }

    private fun path(rel: String): File =
        listOf(File(rel), File("app/$rel")).first { it.exists() }

    private fun engine(lang: String): SuggestionEngine {
        val files = listOf("dictionaries/$lang.txt", "predictions/$lang.txt")
            .filter { assets().resolve(it).isFile }
            .associateWith { assets().resolve(it).readText() }
        return SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
    }

    private fun langs(): List<String> =
        assets().resolve("dictionaries").listFiles().orEmpty()
            .map { it.name.removeSuffix(".txt") }.sorted()

    private fun strip(w: String, lang: String, loc: Locale, e: SuggestionEngine) =
        e.suggestionsFor(w, lang, loc, allowAutocorrect = true, personalized = false)
            .items.map { it.lowercase(loc) }

    @Test
    fun `the word this feature is named for gets its chip`() {
        val e = engine("en")
        val missing = listOf(
            "alot" to "a lot",
            "infact" to "in fact",
            "aswell" to "as well",
            "thankyou" to "thank you",
            "everytime" to "every time",
            "ofcourse" to "of course"
        ).filterNot { (typed, split) -> strip(typed, "en", en, e).contains(split) }
        assertEquals(
            "a run-together the corpus itself holds was computed a split and " +
                "then denied a chip for it.",
            emptyList<Pair<String, String>>(), missing
        )
    }

    /**
     * And a word still being typed keeps its completions.
     *
     * This is what the free-slot rule was for and it is still right: mid-word,
     * a prefix splits into two short real words alarmingly often, and offering
     * that instead of the word being typed is noise. None of these has a count
     * in the corpus, so `SPLIT_DOMINANCE` never weighed in on them.
     */
    @Test
    fun `an unfinished word is not split out of its own completions`() {
        val e = engine("en")
        val d = e.dictionary("en", en)
        val stolen = StringBuilder()
        for ((prefix, wanted) in listOf(
            "airpo" to "airport", "abro" to "abroad", "downlo" to "download"
        )) {
            assertEquals("$prefix is attested now; pick another prefix", 0, d.frequency(prefix))
            val items = strip(prefix, "en", en, e)
            if (!items.contains(wanted)) stolen.append(" $prefix lost $wanted: $items")
            val split = e.splitFor(prefix, "en", en)?.lowercase(en)
            if (split != null && items.contains(split)) {
                stolen.append(" $prefix claimed a slot for \"$split\"")
            }
        }
        assertEquals(
            "a word still being typed lost a completion to a split of itself.$stolen",
            "", stolen.toString()
        )
    }

    /**
     * The whole population, enumerated, in every language.
     *
     * Measured 2026-08-31: 2,574 attested run-togethers across the 22 fixtures,
     * 2,359 of them offered the split -- 91.6%. It was 1,047, or 40.7%.
     */
    @Test
    fun `an attested run-together reaches the strip in every language`() {
        var attested = 0
        var shown = 0
        val report = StringBuilder()
        for (lang in langs()) {
            val loc = Locale.forLanguageTag(lang)
            val e = engine(lang)
            val d = e.dictionary(lang, loc)
            val words = Regex("""\p{L}+""")
                .findAll(path("src/test/fixtures/prose_$lang.txt").readText())
                .map { it.value.lowercase(loc) }.toList()
            var a1 = 0
            var s1 = 0
            for (i in 0 until words.size - 1) {
                val a = words[i]
                val b = words[i + 1]
                if (a.length + b.length < 4) continue
                val joined = a + b
                if (d.frequency(joined) <= 0) continue          // not attested
                val sp = e.splitFor(joined, lang, loc)?.lowercase(loc) ?: continue
                if (sp != "$a $b") continue
                a1++
                if (strip(joined, lang, loc, e).contains(sp)) s1++
            }
            attested += a1
            shown += s1
            if (a1 >= 20) report.append("$lang $s1/$a1  ")
        }
        val pct = 100.0 * shown / attested
        println("attested run-togethers offered the split: %d/%d (%.1f%%)  %s".format(shown, attested, pct, report))
        assertTrue("nothing to measure", attested >= 1500)
        // 80% is well under the measured 91.6% and far above the 40.7% this
        // scored when the split waited for a slot nobody was leaving free.
        assertTrue(
            "the split is being computed and thrown away again: %.1f%% reach the strip".format(pct),
            pct >= 80.0
        )
    }
}
