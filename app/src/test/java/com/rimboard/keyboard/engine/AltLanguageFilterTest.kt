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
 * A word is not a better spelling for being in the other dictionary.
 *
 * [SuggestionEngine.suggestionsFor] fills the strip from the effective
 * language's list and then, three paragraphs down, from the user's other
 * enabled one. The first loop refuses three kinds of word; the second refused
 * one of them.
 *
 *     userData.isBlocked          primary yes    second yes
 *     isElongation                primary yes    second **no**
 *     Contractions.isAutoBareForm primary yes    second **no**
 *
 * Both of the missing two are rules about spellings the corpus contains and
 * nobody wants offered, and both were argued once beside the loop that has
 * them. `"hello" must not offer "hellooo" and "helloooo" as completions; they
 * are in the corpus but they are not spellings anyone wants offered.` And the
 * apostrophe-less contractions `sit in the dictionary with huge frequencies;
 * without this they would be offered as completions over the real spelling.`
 * Neither sentence is about which of the two enabled languages the word came
 * out of.
 *
 * ## Measured
 *
 * Both enumerated rather than sampled, and both are the *same word, same
 * prefix, offered when its language is second and refused when it is first*:
 *
 *     apostrophe-less contractions   22 of the 44 auto forms -> 0
 *     stretched spellings            38 of 407 checked       -> 0
 *
 * The contractions are the worse half, because the artefact that put them in
 * the list gave them enormous counts: `that` offered `thats`, `didn` offered
 * `didnt`, `isn` offered `isnt`, and the apostrophe form was nowhere, since
 * the contraction chip is keyed on the *effective* language and this user's
 * effective language is not English.
 *
 * ## The half that is easy to get wrong
 *
 * Both questions have to be asked of the **other** language.
 * [SuggestionEngine.isElongation] decides between two spellings by comparing
 * their counts, and the counts live in that list's own corpus; the contraction
 * table is per-language and English's is the only one there is. Asking the
 * primary language would answer no every time and look exactly like a fix.
 * The Turkish case below is what catches that: it passes only if the English
 * table is consulted for the English word.
 */
class AltLanguageFilterTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("altfilter", "").let { it.delete(); it.mkdirs(); it }
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
            for (kind in listOf("dictionaries", "predictions", "suffixes", "prefixes")) {
                File(assets(), "$kind/$l.txt").takeIf { it.isFile }?.let {
                    files["$kind/$l.txt"] = it.readText()
                }
            }
        }
        return SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
    }

    private fun strip(
        e: SuggestionEngine, typed: String, lang: String, alt: String?
    ): List<String> = e.suggestionsFor(
        typed, lang, Locale.forLanguageTag(lang),
        allowAutocorrect = false, personalized = false,
        altLang = alt, altLocale = alt?.let { Locale.forLanguageTag(it) }
    ).items.map { it.lowercase(Locale.ROOT) }

    /**
     * Every English contraction whose bare form the corpus holds, asked for
     * from a Turkish keyboard with English second.
     *
     * The bare spellings carry huge counts — that is the artefact
     * [Contractions] exists for — so they do not sit harmlessly at the bottom
     * of a candidate list. **Twenty-two of the forty-four reached the strip**,
     * and the apostrophe form was nowhere to be had, because the contraction
     * chip is keyed on the effective language and English is not it.
     */
    @Test
    fun `an apostrophe-less contraction is not offered by the second language`() {
        val e = engine("tr", "en")
        val casualties = StringBuilder()
        var offered = 0
        for (w in Contractions.autoForms("en").sorted()) {
            for (k in 2 until w.length) {
                if (strip(e, w.substring(0, k), "tr", "en").contains(w)) {
                    offered++
                    if (casualties.length < 500) casualties.append("\n  \"${w.substring(0, k)}\" -> $w")
                    break
                }
            }
        }
        assertEquals(
            "a spelling the strip refuses in English was offered because English " +
                "is the *second* language:$casualties",
            0, offered
        )
    }

    /**
     * And the word is still reachable when it is genuinely what was typed.
     *
     * The primary loop's own exception — `w != lower` — because suppressing a
     * completion of the very word in the field would leave the strip arguing
     * with what is already on screen. The second language gets the same
     * exception because it is the same rule.
     */
    @Test
    fun `the second language still completes ordinary words`() {
        val e = engine("tr", "en")
        val missing = listOf("hello" to "hello", "wonder" to "wonderful", "thin" to "think")
            .filterNot { (typed, want) -> strip(e, typed, "tr", "en").contains(want) }
        assertEquals(
            "the second language stopped offering ordinary English words.",
            emptyList<Pair<String, String>>(), missing
        )
    }

    /**
     * Elongations, enumerated over every language the app ships.
     *
     * Each language's own list is scanned for entries the engine itself calls
     * an elongation, and the commonest of them are asked for from a keyboard
     * where that language is second. The control is the same word asked of a
     * keyboard where it is first, which has always refused.
     */
    @Test
    fun `an elongation is not offered by the second language`() {
        val casualties = StringBuilder()
        var offered = 0
        var checked = 0
        val langs = File(assets(), "dictionaries").list().orEmpty()
            .map { it.removeSuffix(".txt") }.sorted()
        for (alt in langs) {
            val primary = if (alt == "en") "tr" else "en"
            val e = engine(primary, alt)
            val altLoc = Locale.forLanguageTag(alt)
            val d = e.dictionary(alt, altLoc)
            val cands = File(assets(), "dictionaries/$alt.txt").readLines()
                .mapNotNull { line ->
                    val p = line.split(' ')
                    if (p.size != 2) null else p[0] to (p[1].toIntOrNull() ?: 0)
                }
                // Cheap pre-filter: only a run of three identical letters can
                // be one, and asking the engine is the expensive half.
                .filter { (w, _) ->
                    w.length >= 4 && (2 until w.length).any { w[it] == w[it - 1] && w[it] == w[it - 2] }
                }
                .filter { (w, _) -> e.isElongation(w, d) }
                .sortedByDescending { it.second }
                .take(PER_LANGUAGE)
            val primaryDict = e.dictionary(primary, Locale.forLanguageTag(primary))
            for ((w, _) in cands) {
                val base = e.elongationBase(w, d) ?: continue
                // Only words the *second* list is the sole source of. English
                // holds "grrr" at 710 against "grr" at 674, so by its own
                // corpus the long spelling is the commoner one and its loop
                // offers it on purpose -- a chip that arrives whichever
                // language is second, and so says nothing about this seam.
                if (primaryDict.contains(w)) continue
                val typed = if (base.length < w.length) base else w.dropLast(1)
                checked++
                if (strip(e, typed, primary, alt).contains(w)) {
                    offered++
                    if (casualties.length < 500) {
                        casualties.append("\n  $primary+$alt \"$typed\" -> $w")
                    }
                }
            }
        }
        println("elongation entries asked for as a second language: $checked")
        assertTrue("the enumeration found nothing to check: $checked", checked >= 200)
        assertEquals(
            "a stretched spelling was offered because its language is the second " +
                "one:$casualties",
            0, offered
        )
    }

    private companion object {
        /** Commonest elongations per language. They thin out fast below this. */
        const val PER_LANGUAGE = 40
    }
}
