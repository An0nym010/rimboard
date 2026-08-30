package com.rimboard.keyboard.engine

import com.rimboard.keyboard.model.Morphology
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Prefixes counted from a language's own word list, and whether each one pays.
 *
 * [SuffixInventoryTest] counts the endings a language builds words with, and
 * eighteen languages ship one. It only ever reads the end of a word, because
 * the walk was written for Turkish and the note at the top of
 * [com.rimboard.keyboard.model.Morphology] says why: "Turkish inflection is
 * purely suffixing, so the stem is always what is left at the front." True of
 * Turkish. False of most of the languages that inherited the walk.
 *
 * [OutOfVocabularyTest] prints the consequence in its own output, and has done
 * since it was written. `nl verschuldigde` is rewritten to `verschuldigd` and
 * `de angeschlichen` to `geschlichen` — correct words destroyed by having a
 * prefix taken off them, where the same words with an ending stripped would
 * have been vouched for and left alone. Three of the five real English words
 * destroyed on a phone lost a derivational prefix.
 *
 * ## The same method, at the other end of the word
 *
 * `tools/derive_prefixes.py` splits every word wherever the *back* half is
 * itself a frequent word and counts what the front half was. What rises to the
 * top is ordinary grammar:
 *
 *     ru   по- за- не- про- при- вы- пере- на- от- до- под- раз-
 *     hu   meg- fel- vissza- össze- bele- oda- elő-
 *     pl   po- za- wy- prze- nie- od- na- do- przy- roz- pod-
 *     de   ver- aus- auf- ein- vor- über- hin- durch- zurück- ent-
 *
 * ## What each language is held to, and why the cost is read on both halves
 *
 * The same bar the endings answer to: **at least one point off the rate at
 * which correct words outside the list are rewritten, for at most 1.5% of
 * damaged words wrongly waved through.**
 *
 * With one difference that matters. The false-accept figure here is measured
 * for the **whole walk** — a word counts against the language if either its
 * endings or its prefixes will wave it through — because that total is what a
 * user typing a word is actually exposed to. Priced separately a prefix
 * inventory looks nearly free: it adds 0.0 to 0.5 points, because a mistyped
 * letter usually lands in the middle or the end of a word, which leaves the
 * prefix intact and breaks the stem. Priced together, a language whose endings
 * have already spent the budget cannot have one at all. That is the honest
 * answer, and it is why Croatian ships no prefixes despite counting good ones.
 *
 * ## The sweep
 *
 * Gain in points of destruction prevented, and cost for the whole walk, at
 * each floor on how short a prefix may be. Starred is what ships.
 *
 *              floor two          floor three        endings alone
 *     cs      +1.7 / 1.9%        +0.2 / 0.8%             0.6%
 *     da      +1.5 / 1.6%        +0.8 / 1.4%             0.9%
 *     de      +2.3 / 2.1%      * +1.3 / 1.2%             0.9%
 *     el      +0.8 / 0.0%        +0.5 / 0.0%             0.0%
 *     en      +1.5 / 2.5%        +0.7 / 1.7%             0.8%
 *     es      +1.8 / 1.9%        +0.8 / 1.4%             1.4%
 *     fi      +0.0 / 1.2%        +0.0 / 1.2%             1.2%
 *     fr    * +1.5 / 1.0%        +0.3 / 0.3%             0.3%
 *     hr      +2.7 / 3.3%        +0.8 / 2.1%             1.2%
 *     hu      +4.7 / 1.9%      * +1.8 / 1.2%             1.0%
 *     id      +2.7 / 1.4%      * +1.7 / 1.0%             0.8%
 *     it      +1.8 / 2.0%        +0.5 / 1.5%             0.8%
 *     nl      +2.5 / 1.7%      * +2.2 / 1.3%             0.8%
 *     no      +0.0 / 0.7%        +0.0 / 0.5%             0.3%
 *     pl    * +2.3 / 0.9%        +0.8 / 0.9%             0.7%
 *     pt      +1.7 / 1.2%      * +1.5 / 0.7%             0.7%
 *     ro    * +3.3 / 0.9%        +0.3 / 0.7%             0.7%
 *     ru    * +2.7 / 0.8%        +0.8 / 0.5%             0.2%
 *     sk    * +1.7 / 0.8%        +0.5 / 0.4%             0.0%
 *     sv      +1.3 / 1.4%      * +1.2 / 1.2%             1.2%
 *     uk    * +2.7 / 1.2%        +1.0 / 0.8%             0.2%
 *
 * **The floor splits these languages differently than it splits them for
 * endings**, and that is a fact about where each language keeps its grammar
 * rather than a knob turned twice. Endings put Slavic and Germanic at two
 * characters and Romance at three. Prefixes do the reverse: Germanic prefixes
 * are `ver-`, `aus-`, `uit-`, `för-` and want three, while Slavic and Romance
 * keep theirs in two — `po-`, `za-`, `вы-`, `re-`, `ne-`. One global floor
 * would have cost Romanian its 3.3 points or German its 1.2% cost.
 *
 * Three languages were turned down where a better trade was on the table, and
 * the reason each time was margin rather than arithmetic. Indonesian at floor
 * two gains a full point more, +2.7, and lands on 1.4%; Swedish the same at
 * 1.4%; Danish fits nowhere at all. A tenth of a point under the limit is what
 * a dictionary rebuild moves, and this project has twice decided that an
 * inventory with no margin is not one it wants.
 *
 * **Croatian is what the ceiling cost.** It has the worst destruction of any
 * shipped language at 36.7%, it counts perfectly good prefixes — `pre-`,
 * `pro-`, `pri-`, `raz-`, `pod-` — and it gains 2.7 points from them, tied
 * with Russian and Ukrainian for the best figure after Romanian. It ships
 * none, because its endings already run at 1.2% and the pair reaches 3.3%.
 * That is the ceiling doing its job on the language that would most like it
 * not to.
 *
 * **Slovak and Ukrainian had never been measured here at all**, and the reason
 * was not this table. The stem floor a prefix is counted in front of is a flat
 * 500 occurrences, and their corpora are far too small for that to mean what
 * it means elsewhere — Ukrainian had 833 stems to look behind. Scaled to their
 * own corpora (see [Dictionary.stemFloorFor]) they count ordinary Slavic
 * verbal prefixes and both take two characters, like Polish and Russian.
 * Ukrainian's +2.7 ties Russian for the best measured.
 *
 * **Greek was never measured here either, and it is refused.** It was starved
 * by nothing — 22,238 stems at the flat floor — and it counts real Greek
 * morphology: `ξανα-`, `ανα-`, `απο-`, `παρα-`, `δια-`, `προ-`, `κατα-`,
 * `επι-`, `υπο-`, `συν-`. It costs **nothing at all**, 0.0% wrongly accepted,
 * the only inventory ever measured at zero here and a better ratio than
 * Swedish, German or Hungarian. It misses only because the bar is written as a
 * minimum gain rather than a ratio, and moving a bar to admit the thing just
 * measured is a move this project has refused before. Greek is also the one
 * language with no ending inventory — its stems are never words on their own,
 * so the counting method cannot find one — which makes this the only
 * morphology it could have, and the first thing to re-measure if the
 * dictionaries are rebuilt.
 *
 * ## What the counting gets wrong, and why it ships anyway
 *
 * The lists hold entries no grammar would call a prefix. German has `sch-`,
 * which is the first syllable of `schlagen` and not a morpheme. French has
 * `la-` and `ma-`, Romanian `ca-` and `pa-`, Swedish `polis-` and `barn-`.
 *
 * This is the objection English endings raised with `-man`, `-son` and
 * `-ville`, and it gets the answer this project already had to learn once:
 * **the harm being feared is exactly what the false-accept figure prices**,
 * and it is priced, per language, in the table above. A judgement that
 * survives only by not being measured is not a judgement. The asymmetry that
 * makes it safe is in [Morphology]'s own words — the walk "is a guard, never a
 * generator" — so the worst a wrong entry can do is fail to flag a typo, while
 * what it prevents is a correct word being silently overwritten.
 *
 * Some of those entries are not even wrong, only mislabelled. Swedish `polis-`
 * and `huvud-`, German `wasser-` and `haupt-` are compound first elements, and
 * those really do build unbounded numbers of real words.
 */
class PrefixInventoryTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-prefix", "").let { it.delete(); it.mkdirs(); it }
        userData = UserData.inDir(dir)
    }

    @After
    fun tearDown() {
        userData.shutdown()
        dir.deleteRecursively()
    }

    private fun assets(): File =
        listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }

    private fun shipped(): List<String> =
        File(assets(), "prefixes").list().orEmpty().map { it.removeSuffix(".txt") }.sorted()

    private fun inventory(lang: String): List<String> =
        File(assets(), "prefixes/$lang.txt").readLines()
            .filter { it.isNotBlank() }.sortedByDescending { it.length }

    private fun suffixesOf(lang: String): List<String> =
        File(assets(), "suffixes/$lang.txt").takeIf { it.exists() }
            ?.readLines().orEmpty().filter { it.isNotBlank() }.sortedByDescending { it.length }

    /**
     * How often the corrector rewrites a correct word outside [lang]'s list,
     * with the prefix inventory in play and without it. The endings are in
     * play either way, so what this measures is what the prefixes add.
     */
    private fun destruction(lang: String, withPrefixes: Boolean): Double {
        val all = File(assets(), "dictionaries/$lang.txt").readLines().filter { it.isNotBlank() }
        val files = HashMap<String, String>()
        files["dictionaries/$lang.txt"] = all.take(KEEP).joinToString(NEWLINE)
        files["predictions/$lang.txt"] = File(assets(), "predictions/$lang.txt").readText()
        File(assets(), "suffixes/$lang.txt").takeIf { it.exists() }?.let {
            files["suffixes/$lang.txt"] = it.readText()
        }
        if (withPrefixes) {
            File(assets(), "prefixes/$lang.txt").takeIf { it.exists() }?.let {
                files["prefixes/$lang.txt"] = it.readText()
            }
        }
        val e = SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
        val locale = Locale.forLanguageTag(lang)
        val cut = all.drop(KEEP).mapNotNull { it.split(' ').firstOrNull() }
            .filter { w -> w.length in 6..16 && w.all { it.isLetter() } }
        val sample = cut.filterIndexed { i, _ -> i % maxOf(1, cut.size / 600) == 0 }.take(600)
        var d = 0
        for (w in sample) {
            val fix = e.correctionFor(w, lang, locale) ?: continue
            if (fix.lowercase(locale) != w) d++
        }
        return d * 100.0 / sample.size
    }

    /**
     * Damaged words the *whole* walk waves through — endings, or endings
     * behind a prefix.
     *
     * Both halves together on purpose. A prefix inventory priced on its own
     * looks nearly free, and that figure would be true and useless: what
     * decides whether a language may have one is how much of the budget its
     * endings have already spent.
     */
    private fun cost(lang: String, withPrefixes: Boolean): Double {
        val all = File(assets(), "dictionaries/$lang.txt").readLines().filter { it.isNotBlank() }
        val kept = all.take(KEEP)
        val freq = HashMap<String, Int>(kept.size * 2)
        for (l in kept) {
            val p = l.split(' ')
            if (p.size > 1) freq[p[0]] = p[1].toIntOrNull() ?: 0
        }
        // The floor the shipped dictionary computes for this list, not the
        // constant. Two of these are scaled to their own corpus now -- see
        // [Dictionary.stemFloorFor] -- and a benchmark holding the flat number
        // would price an inventory the keyboard does not walk.
        val floor = Dictionary.stemFloorFor(
            lang, all.sumOf { it.substringAfterLast(' ').toLongOrNull() ?: 0L }
        )
        val known: (String) -> Boolean = { (freq[it] ?: 0) >= floor }
        val sufs = suffixesOf(lang)
        val pres = if (withPrefixes) inventory(lang) else emptyList()
        val real = all.mapNotNull { it.split(' ').firstOrNull() }.toHashSet()
        val prox = com.rimboard.keyboard.model.KeyProximity.forLang(lang)
        val words = kept.mapNotNull { it.split(' ').firstOrNull() }
            .filter { w -> w.length in 6..14 && w.all { it.isLetter() } }
        val rnd = kotlin.random.Random(20260826)
        var asked = 0
        var wrong = 0
        for (w in words.filterIndexed { i, _ -> i % maxOf(1, words.size / 600) == 0 }.take(600)) {
            val at = 1 + rnd.nextInt(w.length - 2)
            val nb = prox.neighbours(w[at]).firstOrNull() ?: continue
            val bad = w.substring(0, at) + nb + w.substring(at + 1)
            if (bad in real) continue
            asked++
            if (Morphology.stemIsKnown(bad, sufs, known = known) ||
                Morphology.prefixedStemIsKnown(bad, pres, sufs, known)
            ) wrong++
        }
        return wrong * 100.0 / maxOf(asked, 1)
    }

    @Test
    fun `every shipped prefix inventory earns its place`() {
        val lines = StringBuilder()
        val failed = ArrayList<String>()
        for (lang in shipped()) {
            val saved = destruction(lang, false) - destruction(lang, true)
            val total = cost(lang, true)
            lines.append(
                ("%-4s prevents %4.1f points of destruction; the whole walk then " +
                    "wrongly accepts %4.1f%% (endings alone %4.1f%%)%n")
                    .format(lang, saved, total, cost(lang, false))
            )
            if (saved < MIN_POINTS_SAVED || total > MAX_FALSE) failed.add(lang)
        }
        println(lines)
        assertTrue("no prefix inventory ships", shipped().isNotEmpty())
        assertTrue(
            "these inventories no longer pay for themselves -- at least " +
                "$MIN_POINTS_SAVED points of destruction prevented for at most " +
                "$MAX_FALSE% wrongly accepted across the whole walk: " + failed +
                " || " + lines,
            failed.isEmpty()
        )
    }

    /**
     * Every shipped prefix clears the floor its language was measured at.
     *
     * Read out of the tool rather than restated here, so the two cannot drift
     * apart. The failure this catches would be silent: a two-letter prefix in
     * German puts that language on the 2.1% row of the sweep, over the ceiling,
     * and nothing else in the suite would notice.
     */
    @Test
    fun `no shipped prefix is shorter than its language's measured floor`() {
        val tool = listOf(File("../tools"), File("tools")).first { it.isDirectory }
            .resolve("derive_prefixes.py").readText()
        val global = tool.lineSequence()
            .first { it.startsWith("MIN_PREFIX = ") }
            .removePrefix("MIN_PREFIX = ").trim().toInt()
        val perLang = HashMap<String, Int>()
        val decl = tool.substringAfter("MIN_PREFIX_BY_LANG = {").substringBefore("}")
        for (item in decl.split(",")) {
            val bits = item.split(":")
            if (bits.size != 2) continue
            val lang = bits[0].trim().trim('"')
            val n = bits[1].trim().toIntOrNull() ?: continue
            if (lang.isNotEmpty()) perLang[lang] = n
        }
        assertTrue("the per-language floors did not parse out of the tool", perLang.isNotEmpty())
        assertTrue("no prefix inventories found at all", shipped().isNotEmpty())
        for (lang in shipped()) {
            val floor = perLang[lang] ?: global
            val short = inventory(lang).filter { it.length < floor }
            assertTrue(
                "$lang ships ${short.size} prefix(es) below its floor of $floor: " +
                    short.take(8) + " -- a shorter prefix than the sweep measured " +
                    "means that language is running at a false-accept rate nobody chose",
                short.isEmpty()
            )
        }
    }

    @Test
    fun `the inventories hold real morphology, not noise`() {
        // A spot check a speaker of each could read: ordinary verbal and
        // derivational prefixes. If the derivation ever starts producing first
        // syllables instead of morphemes, this is where it says so.
        val expected = mapOf(
            "ru" to listOf("по", "за", "про", "вы"),
            "hu" to listOf("meg", "fel", "vissza"),
            "pl" to listOf("po", "za", "wy", "prze"),
            "de" to listOf("ver", "aus", "auf", "ein"),
            "nl" to listOf("ver", "uit", "aan", "over"),
            "sk" to listOf("pre", "pri", "roz", "vy"),
            "uk" to listOf("пере", "роз", "під", "від")
        )
        for ((lang, want) in expected) {
            if (lang !in shipped()) continue
            val have = inventory(lang).toSet()
            val missing = want.filter { it !in have }
            assertTrue("$lang no longer derives $missing", missing.isEmpty())
        }
        // Ukrainian's counted list also holds "от", "под", "со" and "ро" -- the
        // first three are Russian prefixes rather than Ukrainian ones
        // (Ukrainian writes "від", "під" and "з"), and the fourth is not a
        // morpheme at all. A subtitle corpus for a language with a large
        // Russian-speaking audience contains Russian, and counting cannot tell
        // the difference.
        //
        // Deliberately not stripped here. The cost of carrying them is already
        // priced -- Ukrainian's whole walk reads 1.2% wrongly accepted, inside
        // the ceiling -- and hand-editing a counted list is the thing this tool
        // exists to avoid. It is the same standing gap as the other curated
        // lists: it wants a speaker, not a guess.
    }

    @Test
    fun `a prefix must actually come off, and only one of them`() {
        val known: (String) -> Boolean = { it == "schuldigde" || it == "verschuldigde" }
        assertTrue(
            "a word is being called well-formed without any prefix stripped",
            !Morphology.prefixedStemIsKnown(
                "verschuldigde", listOf("aan", "uit"), emptyList(), known
            )
        )
        assertTrue(
            "a real prefix in front of a real stem is not being recognised",
            Morphology.prefixedStemIsKnown(
                "verschuldigde", listOf("ver", "uit"), emptyList(), known
            )
        )
        // The shortest acceptable word: a two-letter prefix on a three-letter
        // stem. The first version of the walk bailed below six characters and
        // would have refused this, and no sample in this file is short enough
        // to have noticed -- they are all six letters or longer by
        // construction.
        val shortRoot: (String) -> Boolean = { it == "bil" }
        assertTrue(
            "a two-letter prefix on a three-letter stem must still be a word",
            Morphology.prefixedStemIsKnown("pobil", listOf("po"), emptyList(), shortRoot)
        )
        // One strip and no more. Czech counts "nevy-" and "nepo-" as prefixes
        // in their own right precisely because stacking is not done here.
        val onlyRoot: (String) -> Boolean = { it == "konal" }
        assertTrue(
            "prefixes are stacking; the inventory is meant to hold the pairs",
            !Morphology.prefixedStemIsKnown(
                "nevykonal", listOf("ne", "vy"), emptyList(), onlyRoot
            )
        )
    }

    @Test
    fun `a language without a prefix inventory is left exactly as it was`() {
        assertTrue(
            "an empty inventory must vouch for nothing at all",
            !Morphology.prefixedStemIsKnown("anything", emptyList(), emptyList()) { true }
        )
        // The ones the sweep turned down stay out, so the set is a decision
        // rather than a leftover.
        //
        // Greek is the first to re-check if the dictionaries are ever rebuilt,
        // and it displaced Croatian for the job. Croatian gains more (2.7
        // points) but spends its budget on endings, so the pair reaches 3.3%.
        // Greek reads 0.8 points for **0.0% wrongly accepted** -- the only
        // inventory ever measured at zero cost here -- and misses only because
        // the bar is a minimum gain and not a ratio. It is also the one
        // language with no ending inventory either, so this is the only
        // morphology it could have.
        for (lang in listOf("cs", "da", "el", "en", "es", "fi", "hr", "it", "no", "tr")) {
            assertTrue(
                "$lang ships a prefix inventory now; it was left out on " +
                    "measurement, so the table in this file wants revisiting",
                lang !in shipped()
            )
        }
    }

    private companion object {
        const val KEEP = 60000
        val NEWLINE = String(charArrayOf(10.toChar()))
        const val MIN_POINTS_SAVED = 1.0
        const val MAX_FALSE = 1.5
    }
}
