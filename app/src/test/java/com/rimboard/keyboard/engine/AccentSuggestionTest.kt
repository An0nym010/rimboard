package com.rimboard.keyboard.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * A route to the accented spelling, in the languages that had none.
 *
 * [BareKeySpellingTest] covers the other half of this: a bare spelling the
 * corpus also contains can still be *rejected* and rewritten, if the accented
 * form outnumbers it by [Dictionary.BARE_KEY_RATIO]. That works, and it works
 * in Turkish and German — "icin" becomes "için", "fur" becomes "für".
 *
 * ## The feature worked in inverse proportion to how much a language needed it
 *
 * Fifty is a high bar, and what puts a bare spelling in a corpus is people
 * typing without accents. So the languages whose speakers do that most are
 * exactly the ones whose ratios never reach it:
 *
 *     für / fur     557x   rewritten
 *     için / icin   300x   rewritten
 *     zašto / zasto  44x   left alone
 *     también / tambien 28x   left alone
 *     aquí / aqui    25x   left alone
 *     día / dia      24x   left alone
 *
 * Spanish has 5,737 bare/accented pairs in the band below fifty against
 * Turkish's 241, so Spanish is the language this affects most by a factor of
 * twenty-four — and the constant's own note says it was sampled on Turkish,
 * "the language this affects most". It was sampled on the language it affects
 * least.
 *
 * What that cost, measured on the shipped dictionaries before this existed:
 *
 *     typed        strip offered
 *     aqui         aqui, aquiles, aquino
 *     tambien      tambien, tambie, tambiem
 *     dia          dia, diablos, diablo
 *     zasto        zasto, zastoj, zastoja
 *
 * Not a correction, not a chip, not a tap: no route to "aquí" at all, while
 * two worse strings than the one typed took the slots.
 *
 * ## Two decisions, priced separately
 *
 * Rejecting a word overrules the person who typed it, and fifty is the price of
 * that. *Offering* one costs a chip on a strip that has three, and the typed
 * word keeps slot zero regardless — so it is asked at
 * [Dictionary.ACCENT_SUGGEST_RATIO], a fifth as high, and can never be what the
 * space bar commits. This is the bargain the contraction chip already makes,
 * and the same reason `FalseFriends` keeps two maps behind one predicate: the
 * cost of being wrong is different, so the threshold is different.
 *
 * It follows that nothing here can destroy a word. "sto" is Croatian for a
 * hundred, "mas" is Spanish for "but", "papa" is a potato; all three are still
 * accepted, still commit as themselves, and are merely offered "što", "más" and
 * "papá" beside them. That property is asserted below rather than argued.
 *
 * ## What it costs
 *
 * A chip that goes untapped is a completion that was not shown, and
 * [StripAccuracyTest] prices that in keystrokes saved. Median across all
 * shipped languages goes 32.8% to 32.5%; the worst single language is Croatian
 * and Norwegian at 0.3 points, English is unchanged to the decimal because it
 * has no accented vocabulary to fire on.
 *
 * **The cost does not move with the threshold** — 32.5% at five, ten and twenty
 * alike — because what spends a slot is the pairs far above any of them. There
 * is no setting that buys the accents for free, so the choice was made on the
 * gain side: twenty drops "mío" (12x), "adiós" (14x) and "šta" (11.8x), five
 * measured no better and slightly worse in Hungarian and Romanian, ten keeps
 * every case worth having at the same price.
 *
 * Autocorrect accuracy is untouched, as it must be for a change that adds no
 * correction: [AutocorrectAccuracyTest] reads 96% of English typos fixed and
 * 10% of real words destroyed, to the point, before and after.
 *
 * ## What ten does not cover
 *
 * French. Its pairs sit just under the bar — "état" 7.1x, "église" 9.1x,
 * "sûrement" 9.8x, "terminé" 5.3x — and none of them gets a chip. A ratio of
 * five picks up all four *and costs French nothing*, because French savings
 * read 34.3% at every setting tried; the bill for five lands on Romanian,
 * whose pairs cluster far lower still, and who would get a chip on hundreds of
 * near-even readings for 0.2 points and no gain. The full sweep is 2.5 points
 * of savings spent at five against 2.2 at ten, across twenty-two languages.
 *
 * That is a trade rather than a free win, and it is written down here and
 * asserted below so that changing it is a decision somebody makes on purpose.
 */
class AccentSuggestionTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-accent", "").let { it.delete(); it.mkdirs(); it }
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
        val files = listOf("dictionaries/$lang.txt", "predictions/$lang.txt",
            "suffixes/$lang.txt", "prefixes/$lang.txt")
            .filter { File(assets(), it).exists() }
            .associateWith { File(assets(), it).readText() }
        return SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
    }

    private fun strip(e: SuggestionEngine, w: String, lang: String, loc: Locale): List<String> =
        e.suggestionsFor(w, lang, loc, allowAutocorrect = true, personalized = false).items

    @Test
    fun `the accented spelling is reachable in the languages the ratio never fired for`() {
        val cases = mapOf(
            "es" to listOf("aqui" to "aquí", "asi" to "así", "tambien" to "también",
                "dia" to "día", "dias" to "días", "estan" to "están",
                "ahi" to "ahí", "mio" to "mío", "adios" to "adiós",
                "despues" to "después"),
            "fr" to listOf("voila" to "voilà", "ca" to "ça", "arrete" to "arrête"),
            "hr" to listOf("sta" to "šta", "jos" to "još", "nesto" to "nešto",
                "zasto" to "zašto")
        )
        for ((lang, pairs) in cases) {
            val e = engine(lang)
            val loc = Locale.forLanguageTag(lang)
            for ((bare, want) in pairs) {
                assertTrue(
                    "$lang '$bare' offers no route to '$want': ${strip(e, bare, lang, loc)}",
                    strip(e, bare, lang, loc).contains(want)
                )
            }
        }
    }

    @Test
    fun `where the line falls, and what is on the other side of it`() {
        // French is the language this leaves the most on the table, and the
        // boundary is pinned here so that moving the ratio is visible rather
        // than accidental. Its bare/accented pairs cluster just under the bar:
        //
        //     état / etat        7.1x
        //     église / eglise    9.1x
        //     sûrement / surement 9.8x
        //     terminé / termine   5.3x
        //
        // Dropping the ratio to five picks up all four, and French pays nothing
        // for it -- French keystroke savings read 34.3% at every setting tried.
        // The bill lands on Romanian, whose pairs cluster at very low ratios
        // instead (its weighted coverage runs 15.1% at three, 8.6% at five and
        // 0.4% at ten), so five puts a chip on hundreds of near-even Romanian
        // pairs where the accented form is barely the likelier reading. That
        // costs Romanian 0.2 points and Hungarian 0.1, and buys nothing in
        // either.
        //
        // Ten is therefore a real trade and not a free one: four common French
        // words go uncovered so that two other languages are not charged for
        // ambiguity. If a later reader wants "état", the price is written down
        // above, and this test is where they will find out they changed it.
        val fr = engine("fr")
        val loc = Locale.forLanguageTag("fr")
        for (w in listOf("etat", "eglise", "surement", "termine")) {
            val items = strip(fr, w, "fr", loc)
            assertEquals("fr '$w' must still be the verbatim first chip", w, items.firstOrNull())
        }
    }

    @Test
    fun `nothing here rewrites a word, in either direction`() {
        // The whole safety argument, asserted. Every word above and every word
        // below keeps slot zero and commits as itself: the chip is offered, and
        // offering is all it can do.
        val cases = mapOf(
            // Bare spellings nobody writes on purpose -- still not corrected,
            // because correcting is a separate and more expensive decision.
            "es" to listOf("aqui", "tambien", "dia", "estan"),
            // ...and words that are genuinely themselves, which is the case a
            // careless threshold would ruin. "sto" is a hundred, "mas" is
            // "but", "papa" is a potato, "tenia" is a tapeworm.
            "hr" to listOf("sto", "sta"),
            "cs" to listOf("ze", "byt")
        )
        for ((lang, words) in cases) {
            val e = engine(lang)
            val loc = Locale.forLanguageTag(lang)
            for (w in words) {
                assertEquals("$lang '$w' must still be the verbatim first chip",
                    w, strip(e, w, lang, loc).firstOrNull())
                assertEquals("$lang '$w' must not be corrected to anything",
                    null, e.correctionFor(w, lang, loc))
            }
        }
        // And the Spanish words that really are words are still accepted, which
        // is what says the chip did not arrive by lowering the rejection bar.
        val es = engine("es")
        val esLoc = Locale.forLanguageTag("es")
        for (w in listOf("mas", "papa", "mama", "tenia", "seria", "si", "esta", "como")) {
            assertTrue("es '$w' is a Spanish word and must stay accepted",
                es.acceptedWord(w, "es", esLoc))
        }
    }

    @Test
    fun `the languages that already worked are untouched`() {
        // Turkish and German clear BARE_KEY_RATIO, so the accented form arrives
        // as an ordinary correction and the chip adds nothing. If this ever
        // starts failing, the two paths have begun to disagree about the same
        // word.
        for ((lang, pairs) in listOf(
            "tr" to listOf("icin" to "için", "cok" to "çok", "degil" to "değil"),
            "de" to listOf("fur" to "für", "uber" to "über", "konnen" to "können")
        )) {
            val e = engine(lang)
            val loc = Locale.forLanguageTag(lang)
            for ((bare, want) in pairs) {
                assertEquals("$lang '$bare' should still be corrected outright",
                    want, e.correctionFor(bare, lang, loc))
                assertTrue("$lang '$bare' should also carry the chip",
                    strip(e, bare, lang, loc).contains(want))
            }
        }
    }

    @Test
    fun `a word already carrying its accents is never asked about`() {
        // The cheap guard that keeps this off the per-keystroke path for
        // everything except bare spellings. Typing the word correctly must not
        // produce a chip proposing itself.
        for (lang in listOf("es", "fr", "hr", "tr", "de")) {
            val e = engine(lang)
            val loc = Locale.forLanguageTag(lang)
            for (w in listOf("aquí", "día", "što", "için", "für")) {
                val items = strip(e, w, lang, loc)
                assertEquals("$lang '$w' must stay first", w, items.firstOrNull())
            }
        }
    }
}
