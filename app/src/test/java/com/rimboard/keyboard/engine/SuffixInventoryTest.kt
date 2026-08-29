package com.rimboard.keyboard.engine

import com.rimboard.keyboard.model.Morphology
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Endings counted from a language's own word list, and whether each one pays.
 *
 * Turkish has had morphology from the start: strip a recognised suffix, and if
 * what is left is a known stem the word is a word. It is worth a great deal —
 * a word the keyboard can vouch for is never offered a correction, so it cannot
 * be silently rewritten, and [OutOfVocabularyTest] measures what that is worth
 * in the only currency that matters. Every other inflecting language got
 * nothing, because `Morphology.isAgglutinative` is `lang == "tr"`.
 *
 * The file that holds the Turkish list says why: an inventory is "not something
 * to guess at — a wrong list would suppress real corrections — so they are left
 * until someone who knows them can add one."
 *
 * ## Counting is not guessing
 *
 * `tools/derive_suffixes.py` takes every word, splits it wherever the front half
 * is itself a frequent word, and counts what the back half was. The endings a
 * language really uses come out on top:
 *
 *     hu   -nak -nek -ban -ben -ról -ről -hoz -ért -ból -unk -ünk
 *     fi   -mme -kin -lle -sta -nne -tte -ille -lla -ksi -nsa -ssa
 *     pl   -ami -ach -owi -łem -bym -łam -łeś -liśmy
 *     ro   -ului -lor -ilor -urile -elor
 *
 * The check that the method works is Turkish, where the answer was already
 * known: run against it, the counting reproduces the hand-written list —
 * `-lar`, `-ler`, `-dan`, `-den`, `-ları`, `-nın` all near the front.
 *
 * ## What each language is held to
 *
 * An inventory buys coverage and pays in false accepts, so a language ships one
 * only where the trade earns it — and the test of that is the outcome rather
 * than a proxy for it: **at least one point off the rate at which correct words
 * outside the list are rewritten, for at most 1.5% of damaged words wrongly
 * waved through.** Both halves are measured on the same run, because a gain
 * measured without its cost has misled this project four times.
 *
 * The ceiling is the trade already in the product rather than a figure chosen
 * here. The hand-written Turkish inventory has shipped from the beginning at
 * 3.8%, so anything well inside that is something this keyboard has already
 * decided once, with numbers.
 *
 * Points of destruction prevented, with the inventory and without:
 *
 *     hu 5.8   ro 4.0   fi 3.7   de 3.2   sv 2.5   da 1.8   en 1.5
 *     es 5.5   cs 3.8   fr 3.5   nl 2.8   hr 3.8   no 1.5   sk 0.2
 *     it 4.2   pl 3.8   pt 3.3   id 2.2   ru 1.5
 *
 * Croatian reads 3.8 there and used to read 2.2; see the note on capped
 * two-letter endings below.
 *
 * **Slovak is why the criterion is the outcome.** It derives a respectable
 * inventory and accepts a fair number of held-out words with it, and it
 * prevents 0.2 points of destruction, because the words it accepts were not the
 * ones being rewritten. On the old proxy — "how much does the walk accept" — it
 * looked like a small success. On the thing actually being bought it does
 * nothing, and it does not ship.
 *
 * ## How long an ending is, is a fact about the language
 *
 * Slavic and Germanic inflection is short and Romance and Uralic is not, so the
 * minimum length is per language rather than global. Measured both ways for
 * every one of them, at the same ceiling:
 *
 *            two chars     three chars              two chars    three chars
 *     cs    12.7 / 0.6     3.0 / 0.2        hu     24.8 / 3.9   13.5 / 0.4
 *     nl     7.5 / 0.8     3.8 / 0.3        es     18.3 / 4.5   11.3 / 1.4
 *     de     7.0 / 0.9     2.2 / 0.5        ro     18.3 / 3.6   10.3 / 0.7
 *     id     6.0 / 0.8     2.8 / 0.7        fi     18.2 / 2.3   10.0 / 0.9
 *     sv     5.8 / 1.2     2.7 / 0.0        pl     17.8 / 1.8    9.5 / 0.4
 *
 * The left column gains three to four times as much at two characters and pays
 * almost nothing; the right column would pay several times the cost for its
 * extra gain and keeps three. Czech is the one this brought in from nowhere: it
 * had the joint-worst destruction of any language and no inventory at all,
 * because its case endings are two letters long.
 *
 * Greek and Ukrainian derive nothing whatever. Their endings are one character,
 * and one is where the derivation stops believing anything without the vowel
 * harmony that only Turkish has.
 *
 * ## Croatian, which fits in neither column
 *
 * It was in neither, because it was never swept -- and it had the worst
 * destruction of any shipped language, 38.3% of correct words outside the list
 * rewritten. Swept late, it turned out to be a language the all-or-nothing
 * setting cannot serve. At three characters its inventory prevents 2.2 points.
 * At two it prevents 6.3, the largest gain measured anywhere, for 3.2% wrongly
 * accepted -- more than twice the ceiling.
 *
 * The cost is not spread evenly across those endings. Croatian's two-letter
 * endings are its case and verb inflections, and they fall off steeply: `-om`
 * appears in 2,244 words and the seventy-fourth ending in barely more than the
 * floor. So the tool now takes a per-language cap on how many of them a
 * language may admit on top of its longer ones, and Croatian takes sixteen:
 *
 *     added   prevents   wrongly accepted
 *         0     2.2         0.4%      (what shipped)
 *         8     3.0         1.1%
 *        16     3.8         1.2%      <- here
 *        20     4.0         1.4%
 *        26     4.7         2.1%      over the ceiling
 *
 * 1.6 points better than shipping, at a cost equal to the highest any
 * inventory already carries (Swedish at 1.2%) and with room left under the
 * ceiling for a dictionary rebuild to move things. Twenty buys 0.2 more points
 * for 0.2 more cost and leaves almost no margin, which is not a trade worth
 * making on the edge of a limit.
 *
 * Hungarian at 3.9% and Finnish at 2.3% were rejected at two characters for
 * the same reason Croatian was, and the same knob is now available to them.
 * Neither is measured; this sweep is how.
 *
 * ## English, and a judgement the measurement overruled
 *
 * English was held out of this for a while on taste rather than arithmetic. Its
 * list comes back with -man, -son, -ton, -ley and -ville beside -ing and -ness,
 * because English builds names out of whole words — Johnson, Hamilton,
 * Nashville — and counting cannot tell a name formative from a suffix. "that"
 * plus "-ville" being vouched for looked like a wrong rule rather than a
 * lenient one.
 *
 * It was the wrong call, and three things say so. Those endings do productive
 * work: -man alone rescues twenty held-out words and they are `policeman` and
 * `spokesman`, not `thatman`. English prevents 1.5 points of destruction for
 * 0.8% wrongly accepted, a *lower* false-accept rate than fi, de, da, sv or es,
 * all of which ship. And English is the one language with a repair benchmark of
 * its own — [AutocorrectAccuracyTest] reports 96% of typos fixed with the
 * inventory and without it, to the word.
 *
 * The harm that was feared is precisely what the false-accept figure prices,
 * and it is priced lower here than in half the languages already shipping. A
 * judgement that survives only by not being measured is not a judgement.
 *
 * Turkish keeps its hand-written list. The counted one is measurably worse for
 * it — 31% of held-out words for 0.5% against 46% for 3.8% — because harmony
 * lets the hand-written inventory carry one- and two-letter endings that
 * nothing counted here could trust.
 */
class SuffixInventoryTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-suffix", "").let { it.delete(); it.mkdirs(); it }
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
        File(assets(), "suffixes").list().orEmpty().map { it.removeSuffix(".txt") }.sorted()

    private fun inventory(lang: String): List<String> =
        File(assets(), "suffixes/$lang.txt").readLines()
            .filter { it.isNotBlank() }.sortedByDescending { it.length }

    /**
     * How often the corrector rewrites a correct word outside [lang]'s list,
     * with the inventory in play and without it.
     *
     * This is the outcome the inventories exist for, and it is what decides
     * whether one ships. It replaced a proxy -- how many held-out words the
     * walk accepts -- which is a fine thing to know and not the thing being
     * bought. Slovak was the language that showed the difference: it accepts a
     * respectable number and prevents 0.2 points of destruction, because the
     * words it accepts were not the ones being rewritten.
     */
    private fun destruction(lang: String, withInventory: Boolean): Double {
        val all = File(assets(), "dictionaries/$lang.txt").readLines().filter { it.isNotBlank() }
        val files = HashMap<String, String>()
        files["dictionaries/$lang.txt"] = all.take(KEEP).joinToString(NEWLINE)
        files["predictions/$lang.txt"] = File(assets(), "predictions/$lang.txt").readText()
        if (withInventory) {
            File(assets(), "suffixes/$lang.txt").takeIf { it.exists() }?.let {
                files["suffixes/$lang.txt"] = it.readText()
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

    /** Gain and cost for [lang], both from the same truncated list. */
    private fun trade(lang: String, depth: Int = 2): Pair<Double, Double> {
        val all = File(assets(), "dictionaries/$lang.txt").readLines().filter { it.isNotBlank() }
        val kept = all.take(KEEP)
        val freq = HashMap<String, Int>(kept.size * 2)
        for (l in kept) {
            val p = l.split(' ')
            if (p.size > 1) freq[p[0]] = p[1].toIntOrNull() ?: 0
        }
        val known: (String) -> Boolean = { (freq[it] ?: 0) >= Dictionary.STEM_MIN_FREQ }
        val sufs = inventory(lang)
        val real = all.mapNotNull { it.split(' ').firstOrNull() }.toHashSet()

        val cut = all.drop(KEEP).mapNotNull { it.split(' ').firstOrNull() }
            .filter { w -> w.length in 6..16 && w.all { it.isLetter() } }
        val sample = cut.filterIndexed { i, _ -> i % maxOf(1, cut.size / 600) == 0 }.take(600)
        val gained = sample.count { !known(it) && Morphology.stemIsKnown(it, sufs, depth, known) }

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
            if (Morphology.stemIsKnown(bad, sufs, depth, known)) wrong++
        }
        return gained * 100.0 / sample.size to wrong * 100.0 / maxOf(asked, 1)
    }

    /**
     * Every shipped ending clears the floor its language was measured at.
     *
     * The floors are in `tools/derive_suffixes.py` -- three by default, two for
     * the nine Slavic and Germanic languages whose case endings are that short,
     * from the sweep in the table above. Nothing held the assets to them. The
     * inventories are generated, but a generated file is still a file somebody
     * can edit, and the failure would be silent in the worst way: a one-letter
     * ending puts that language in the 10.2% row, where the walk vouches for a
     * mistyped word ten times more often and autocorrect stops touching it.
     *
     * Read out of the tool rather than restated here, so the two cannot drift
     * apart. The tool is a declared input of this task, so editing it re-runs
     * this rather than leaving it to pass against a cached result.
     */
    @Test
    fun `no shipped ending is shorter than its language's measured floor`() {
        val tool = listOf(File("../tools"), File("tools")).first { it.isDirectory }
            .resolve("derive_suffixes.py").readText()

        val global = tool.lineSequence()
            .first { it.startsWith("MIN_SUFFIX = ") }
            .removePrefix("MIN_SUFFIX = ").trim().toInt()

        val perLang = HashMap<String, Int>()
        val decl = tool.substringAfter("MIN_SUFFIX_BY_LANG = {").substringBefore("}")
        for (item in decl.split(",")) {
            val bits = item.split(":")
            if (bits.size != 2) continue
            val lang = bits[0].trim().trim('"')
            val n = bits[1].trim().toIntOrNull() ?: continue
            if (lang.isNotEmpty()) perLang[lang] = n
        }
        assertTrue("the per-language floors did not parse out of the tool", perLang.isNotEmpty())

        val dir = File(assets(), "suffixes")
        val files = dir.list().orEmpty().filter { it.endsWith(".txt") }.sorted()
        assertTrue("no inventories found at all", files.isNotEmpty())
        // A language may also admit a capped number of two-letter endings on
        // top of its longer ones -- see SHORT_CAP in the tool, and Croatian,
        // which is the reason it exists. Read from the same file so the floor
        // this enforces is the floor the tool applied.
        val capped = HashSet<String>()
        val capDecl = tool.substringAfter("SHORT_CAP = {").substringBefore("}")
        for (item in capDecl.split(",")) {
            val lang = item.split(":").firstOrNull()?.trim()?.trim('"') ?: continue
            if (lang.isNotEmpty()) capped.add(lang)
        }

        for (name in files) {
            val lang = name.removeSuffix(".txt")
            val floor = if (lang in capped) 2 else perLang[lang] ?: global
            val short = dir.resolve(name).readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .filter { it.length < floor }
            assertTrue(
                "$lang ships ${short.size} ending(s) below its floor of $floor: " +
                    short.take(8) + " -- a shorter ending than the sweep measured " +
                    "means that language is running at a false-accept rate nobody chose",
                short.isEmpty()
            )
        }
    }

    @Test
    fun `every shipped inventory earns its place`() {
        val lines = StringBuilder()
        val failed = ArrayList<String>()
        for (lang in shipped()) {
            val saved = destruction(lang, false) - destruction(lang, true)
            val (_, cost) = trade(lang)
            lines.append(
                "%-4s prevents %4.1f points of destruction for %4.1f%% wrongly accepted%n"
                    .format(lang, saved, cost)
            )
            if (saved < MIN_POINTS_SAVED || cost > MAX_FALSE) failed.add(lang)
        }
        println(lines)
        assertTrue("no inventory ships", shipped().isNotEmpty())
        assertTrue(
            "these inventories no longer pay for themselves -- at least " +
                "$MIN_POINTS_SAVED points of destruction prevented for at most " +
                "$MAX_FALSE% wrongly accepted: " + failed + " || " + lines,
            failed.isEmpty()
        )
    }

    @Test
    fun `the inventories hold real morphology, not noise`() {
        // A spot check a speaker of each could read. If the derivation ever
        // starts producing fragments instead of endings, this is where it says
        // so -- these are ordinary case and possessive endings.
        val expected = mapOf(
            "hu" to listOf("nak", "nek", "ban", "ben"),
            "fi" to listOf("lle", "ssa", "sta", "mme"),
            "pl" to listOf("ami", "ach", "owi"),
            "ro" to listOf("lor", "ului"),
            "it" to listOf("ndo", "rlo")
        )
        for ((lang, want) in expected) {
            if (lang !in shipped()) continue
            val have = inventory(lang).toSet()
            val missing = want.filter { it !in have }
            assertTrue("$lang no longer derives $missing", missing.isEmpty())
        }
    }

    @Test
    fun `an ending must actually come off`() {
        // The walk vouches for a word *built* from a stem and endings, not for
        // any word the list happens to hold. Answering the second question is
        // how "alot" -- which a subtitle corpus contains -- stopped being
        // offered as "a lot", because a word the language's own rules build is
        // never given a space.
        val known: (String) -> Boolean = { it == "alot" || it == "walk" }
        assertTrue(
            "a word is being called well-formed without any ending stripped",
            !Morphology.stemIsKnown("alot", listOf("ing", "ness"), known = known)
        )
        assertTrue(
            "a real stem plus a real ending is not being recognised",
            Morphology.stemIsKnown("walking", listOf("ing", "ness"), known = known)
        )
    }

    @Test
    fun `a language without an inventory is left exactly as it was`() {
        val known: (String) -> Boolean = { true }
        assertTrue(
            "an empty inventory must vouch for nothing at all",
            !Morphology.stemIsKnown("anything", emptyList(), known = known)
        )
        // And the ones deliberately left out stay out, so that the set is a
        // decision rather than a leftover.
        for (lang in listOf("sk", "el", "uk", "tr")) {
            assertTrue(
                "$lang ships an inventory now; it was left out on measurement, " +
                    "so the table in this file wants revisiting",
                lang !in shipped()
            )
        }
    }


    /**
     * How far endings may stack, swept.
     *
     * Turkish stacks -- that is what agglutinative means, and its walk allows
     * six. The languages with a counted inventory are not agglutinative, and
     * the guess was that letting their endings pile up would buy little while
     * multiplying the ways a mistyped word could be taken apart. Half right:
     * it buys little, and it costs little either. Between one and six, gain
     * moves at most 0.7 points and cost 0.2.
     *
     * So `DERIVED_MAX_DEPTH` is two, which bounds the work without changing an
     * answer, and this is here so the question is not asked a third time.
     */
    @Test
    fun `how far endings may stack, swept`() {
        println("how far endings may stack, against gain and cost")
        println("lang  depth1        depth2        depth3        depth6")
        for (lang in listOf("hu", "fi", "pl", "ro", "it", "fr", "pt", "es", "cs", "ru", "de", "en")) {
            if (!File(assets(), "suffixes/$lang.txt").exists()) continue
            val row = StringBuilder("%-4s".format(lang))
            for (d in listOf(1, 2, 3, 6)) {
                val (g, c) = trade(lang, d)
                row.append("  %5.1f/%4.1f".format(g, c))
            }
            println(row)
        }
    }

    private companion object {
        const val KEEP = 60000
        val NEWLINE = String(charArrayOf(10.toChar()))
        const val MIN_POINTS_SAVED = 1.0
        const val MAX_FALSE = 1.5
    }
}
