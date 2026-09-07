package com.rimboard.keyboard.engine

import com.rimboard.keyboard.model.Languages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * "Never suggest or autocorrect to profanity" has to mean the plural too.
 *
 * [SuggestionEngine.isOffensive] is an exact membership test. It is careful
 * about case and about which locale folds the word, and it has no idea that a
 * plural is the same word -- so the lists holding base forms only meant the
 * keyboard went on offering the inflections. Sixty-one of them in English were
 * sitting in the shipped dictionary, ready to be completed or corrected to,
 * with the setting switched on.
 *
 * Fixing it in the matcher was tried and rejected: the suffix inventories in
 * `assets/suffixes/` are derivational -- they contain "the", "you", "land",
 * "town" -- so peeling with them turns ordinary vocabulary into listed words,
 * including one with a corpus frequency of 232,845. A filter that over-blocks
 * common words is worse than one that under-blocks rare ones.
 *
 * So it is data, and `tools/expand_offensive.py` maintains it. This is the
 * ratchet: a form that is (a) a listed word plus a grammatical ending, (b)
 * attested in the shipped dictionary, (c) no more frequent than the word it
 * derives from, and (d) unknown to the bundled next-word model -- as is the
 * word it derives from -- must itself be listed.
 *
 * (c) and (d) are both about keeping ordinary words out, and they catch
 * different things. A real inflection is rarer than its base, so anything
 * commoner is a different word that merely looks derived. But that cannot see
 * **polysemy**: "cock", "prick" and Turkish "mal" are listed for one sense and
 * are ordinary words in another, so their inflections are rarer than the base
 * and still perfectly ordinary. The first version of this shipped without (d)
 * and blocked "cocked", "pricked", Turkish "mali" (financial) and "mallar"
 * (goods).
 *
 * `assets/predictions/` is the evidence for (d), and it was chosen because it
 * was built for something else entirely: everyday sentences with corpus
 * artifacts already filtered out. A word that model has an opinion about is one
 * ordinary people write in ordinary messages.
 *
 * ## What (d) costs, measured 2026-09-07, and why nothing cheaper works
 *
 * The cost used to be written here as "96 of the 293 additions... the right
 * side to err on". That is the count of things withheld, which is not the same
 * as the count of things a user meets. **86 forms are withheld across the
 * twelve languages, and 61 of them the keyboard will still put on the strip
 * with the setting switched on** -- `shits` after four letters, `retards`
 * after six, `bastards` after four, German `negern` after five, French
 * `enculee` after six -- while 38 are offered as the repair for a typo of
 * themselves. See the test below, which measures both through the engine
 * rather than reasoning about the lists.
 *
 * Every one of the 86 is withheld by the same half of (d): the *base* word is
 * in the model, so the walk skips it and never reaches its forms. The other
 * half -- `form not in common` -- has **never fired once**. It is a dead clause
 * in the sense `fcea63e` found three of.
 *
 * Two cheaper rules were measured and both are worse:
 *
 *  - **Ask about the form instead of the base.** Admits 69 and keeps out 17,
 *    and it sorts them the wrong way round: the 17 it keeps out are
 *    `bastards`, `fucks`, `bitching`, `bitchy`, and the 69 it admits include
 *    `cocky`, `cocking`, `cockers`, `dicker`, `dickers`, `dicky`, `dickies`,
 *    `bastardy`, Danish `svine`/`svins`/`svinen`, French `cones` -- ordinary
 *    words, which is the one fault worse than the one being fixed.
 *  - **A frequency ratio to the base.** Completely interleaved: ordinary
 *    `cocky` at 0.242 sits between `bastards` at 0.284 and `retards` at 0.222,
 *    and at the bottom ordinary `dickers`, `cockers`, `dickies` and `bastardy`
 *    sit at 0.001 among `assholey`, `shites` and `fuckings`. No threshold
 *    exists.
 *
 * **The reason both fail is the same one, and it is worth naming.** Every
 * signal available here is some form of *how much ordinary text contains this
 * word* -- and for profanity that is exactly what a swear word has. The
 * evidence is selected by the very property the two classes share, so it
 * cannot separate "an ordinary word that looks derived from a slur" from "an
 * inflection of a slur people write a lot". That is a question about meaning,
 * one word at a time.
 *
 * So (d) stays, and the 86 stay out, and this is recorded rather than repaired.
 * The English 38 could be sorted by hand; the other 48 are Danish, German,
 * French, Dutch, Spanish, Italian, Polish, Portuguese and Turkish, and sorting
 * those is the same standing blocker as the ten missing ending sets below --
 * it wants a speaker, not another measurement.
 *
 * All twelve the tool covers, and the endings are read out of the tool rather
 * than transcribed here. This checked three of them against its own copy of
 * the table, with a note saying "the tool owns the rest" -- so nine covered
 * languages were never checked, and a copy of the rule under test is the
 * fault this project has found four times elsewhere.
 *
 * Reading the tool also made the other half visible: it covers twelve
 * languages and skips ten, and the ten are the ones whose grammar lives in
 * endings. See the test below, which names them.
 */
class OffensiveInflectionTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-offinfl", "").let {
            it.delete(); it.mkdirs(); it
        }
        userData = UserData.inDir(dir)
    }

    @After
    fun tearDown() {
        userData.shutdown()
        dir.deleteRecursively()
    }

    /**
     * The tool's own table, read rather than copied.
     *
     * This held three languages of its own, transcribed, with a note saying
     * "the tool owns the rest". A test that keeps its own copy of the rule it
     * is checking measures its copy -- found four times in this project -- and
     * here it also meant nine of the twelve covered languages were never
     * checked at all. `tools` is a declared input of the test task, so editing
     * the table re-runs this.
     */
    private fun endings(): Map<String, List<String>> {
        val tool = listOf(File("../tools"), File("tools")).first { it.isDirectory }
            .resolve("expand_offensive.py").readText()
        val body = tool.substringAfter("SUFFIXES = {").substringBefore("\n}")
        val out = LinkedHashMap<String, List<String>>()
        val entry = Regex("\"(\\w\\w)\":\\s*\\[([^]]*)]")
        val item = Regex("\"([^\"]+)\"")
        for (m in entry.findAll(body)) {
            out[m.groupValues[1]] =
                item.findAll(m.groupValues[2]).map { it.groupValues[1] }.toList()
        }
        return out
    }

    private fun assets(): File =
        listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }

    private fun listed(lang: String): Set<String> =
        File(assets(), "offensive/$lang.txt").readLines()
            .map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    /** Words the bundled next-word model has an opinion about; see (d). */
    private fun everyday(lang: String): Set<String> {
        val out = HashSet<String>()
        File(assets(), "predictions/$lang.txt").forEachLine { line ->
            val i = line.indexOf('	')
            if (i > 0) {
                out.addAll(line.substring(0, i).split(" "))
                out.addAll(line.substring(i + 1).split(" "))
            }
        }
        return out
    }

    private fun frequencies(lang: String): Map<String, Int> {
        val out = HashMap<String, Int>()
        File(assets(), "dictionaries/$lang.txt").forEachLine { line ->
            val p = line.split(" ")
            if (p.size >= 2) p[1].toIntOrNull()?.let { out[p[0].lowercase()] = it }
        }
        return out
    }

    /**
     * Which languages the expansion covers, as a decision rather than a
     * leftover.
     *
     * Twelve of the twenty-two have an ending set and ten do not, so for those
     * ten the offensive list carries base forms only and every inflection is
     * offered. That is not a small residue: the ten are where the inflections
     * are, and several of the missing forms are common words. Candidates the
     * tool would have considered, with their corpus counts --
     *
     *     fi helvettia 19,353   hu szart 14,300   ro rahatul 11,553
     *     cs prdeli 5,331       hr sranjem 1,499  el poutanas 1,627
     *     sk prdeli 543         ru sukiny 459     id anjingnya 795
     *     uk mudaka 10
     *
     * -- are partitives, accusatives, instrumentals and possessives of words
     * already on those lists. Finnish, Hungarian, Czech, Slovak, Croatian,
     * Russian, Ukrainian, Greek and Romanian are exactly the languages whose
     * grammar lives in endings, which is the opposite of the order you would
     * choose.
     *
     * **Writing those ten sets wants a speaker and not a guess**, which is why
     * this test names them rather than filling them. Every entry in that table
     * is a claim that a string is a grammatical ending of a language, and the
     * cost of being wrong is somebody's ordinary vocabulary going missing from
     * their own keyboard -- the fault [com.rimboard.keyboard.model.FalseFriends]
     * exists to undo. It is the same standing gap as the curated lists there.
     *
     * Pinned so that adding a language to the tool without re-reading this
     * fails, and so the ten stay visible instead of being a silence.
     */
    @Test
    fun `the languages with no ending set are named, not forgotten`() {
        val covered = endings().keys
        assertTrue(
            "no ending table parsed out of tools/expand_offensive.py, so the " +
                "ratchet below is running over nothing",
            covered.size >= 10
        )
        val uncovered = Languages.codes.filterNot { it in covered }.sorted()
        assertEquals(
            "the set of languages whose offensive list gets no inflections has " +
                "changed. Adding one is good and wants its numbers written into " +
                "the note above; losing one silently is the thing this catches.",
            listOf("cs", "el", "fi", "hr", "hu", "id", "ro", "ru", "sk", "uk"),
            uncovered
        )
        println("inflections expanded for ${covered.size} of ${Languages.codes.size}: " +
            covered.sorted().joinToString(" "))
    }

    @Test
    fun `an attested inflection of a listed word is listed too`() {
        val missing = ArrayList<String>()
        var checked = 0
        for ((lang, sufs) in endings()) {
            val off = listed(lang)
            val freq = frequencies(lang)
            val common = everyday(lang)
            for (w in off) {
                val base = freq[w] ?: continue
                // Ordinary messages use this word, so it carries a sense the
                // list is not about and its inflections belong to that sense.
                if (w in common) continue
                for (s in sufs) {
                    val form = w + s
                    val f = freq[form] ?: continue
                    checked++
                    // Rarer than its base, so it is that word in another form
                    // rather than a different word that looks like one.
                    if (f <= base && form !in common && form !in off) {
                        missing.add("$lang:${form.length}-letter form")
                    }
                }
            }
        }
        // Guards the guard: a scan that matches nothing reports clean, which
        // looks exactly like a scan that found nothing wrong.
        assertTrue(
            "the scan examined $checked candidate forms -- it has stopped " +
                "finding the lists or the dictionaries",
            // Was 100 before condition (d), which skips the bases ordinary
            // messages use and takes their candidates out of the scan with
            // them. 88 today across three languages; this is a floor against
            // the scan silently matching nothing, not a target.
            checked >= 60
        )
        assertTrue(
            "these are inflections of listed words, present in the shipped " +
                "dictionary and rarer than the word they come from, and the " +
                "filter would offer them: " + missing.joinToString(", "),
            missing.isEmpty()
        )
    }

    /**
     * Every form condition (d) withholds: a listed word plus one of the tool's
     * own endings, attested in the shipped dictionary, no more frequent than
     * its base, and skipped because the base is a word the prediction model
     * knows.
     */
    private fun withheld(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for ((lang, sufs) in endings()) {
            val off = listed(lang)
            val freq = frequencies(lang)
            val common = everyday(lang)
            for (w in off) {
                val base = freq[w] ?: continue
                if (w !in common) continue
                for (s in sufs) {
                    val form = w + s
                    if (form in off) continue
                    val f = freq[form] ?: continue
                    if (f <= base) out.add(lang to form)
                }
            }
        }
        return out
    }

    /**
     * What the exemption actually leaves on the strip.
     *
     * The class note above argues that (d) has to stay and that no counting
     * rule replaces it. This is the other half of that honesty: the price is
     * not an abstract count of withheld additions, it is words the keyboard
     * offers to somebody who asked it not to. Asked through the engine, with
     * "Block offensive words" on, exactly as the strip and the space bar ask.
     *
     * Both bounds are ratchets rather than targets, and both sit close to the
     * measurement on purpose: `fcea63e` found eleven floors set so far under
     * what they guarded that they could not fail. The first draft of this one
     * had the same fault -- at a ceiling of 100 it survived four extra English
     * endings being added to the tool, which took the reachable count from 61
     * to 68 and should have been caught. Five clear of 61 and 38, checked by
     * making that change and watching it trip.
     *
     * The floor is the guard on the guard -- a scan that reaches nothing
     * reports clean and looks exactly like a scan that found nothing. If the
     * gap is ever closed this trips too, which is the intended outcome: the
     * note above wants rewriting, not the number relaxing.
     */
    @Test
    fun `the forms condition (d) withholds are still offered, and how many`() {
        val all = withheld()
        assertTrue(
            "nothing is being withheld at all, so either the rule changed or " +
                "this scan has stopped finding the assets",
            all.size in 60..140
        )
        var completed = 0
        var corrected = 0
        val shown = ArrayList<String>()
        for ((lang, forms) in all.groupBy({ it.first }, { it.second })) {
            val locale = Languages.byCode(lang).locale
            val files = HashMap<String, String>()
            for (n in listOf(
                "dictionaries/$lang.txt", "predictions/$lang.txt",
                "offensive/$lang.txt", "offensive/en.txt"
            )) {
                val f = File(assets(), n)
                if (f.isFile) files[n] = f.readText()
            }
            val engine = SuggestionEngine.forTesting(userData) { p ->
                files[p]?.byteInputStream()
            }
            // The setting under test. Its own summary reads "Never suggest or
            // autocorrect to profanity".
            engine.blockOffensive = true
            for (form in forms) {
                var hit = false
                for (k in 2 until form.length) {
                    val res = engine.suggestionsFor(
                        form.substring(0, k), lang, locale,
                        allowAutocorrect = true, personalized = false
                    )
                    if (res.items.any { it.equals(form, ignoreCase = true) }) {
                        completed++
                        hit = true
                        shown.add("$lang $form after $k")
                        break
                    }
                }
                // A typo of the word itself, which is the other way a word
                // reaches somebody who did not ask for it.
                val typo = form.dropLast(1) + (if (form.last() == 'x') 'z' else 'x')
                if (engine.correctionCandidates(typo, lang, locale, limit = 3)
                        .any { it.equals(form, ignoreCase = true) }
                ) {
                    corrected++
                    if (!hit) shown.add("$lang $form as a repair")
                }
            }
        }
        println(
            "condition (d) withholds ${all.size} forms; the strip completes " +
                "$completed of them and corrects to $corrected, with the " +
                "setting on"
        )
        assertTrue(
            "no withheld form is reachable any more, which would be very good " +
                "news and wants the note above rewritten rather than this " +
                "number quietly relaxed",
            completed >= 50
        )
        assertTrue(
            "more of the withheld forms reach the strip than when this was " +
                "measured (61 completed, 38 corrected of 86). Something " +
                "widened the ending sets or the lists: " +
                shown.take(12).joinToString(", "),
            completed <= 66 && corrected <= 43
        )
    }

    @Test
    fun `the lists stay sorted and free of duplicates`() {
        // They are read as a set, so order costs nothing at runtime -- but the
        // file is reviewed by a human, and a word list nobody can scan is a
        // word list nobody checks.
        for (f in File(assets(), "offensive").listFiles().orEmpty().sortedBy { it.name }) {
            val w = f.readLines().map { it.trim() }.filter { it.isNotEmpty() }
            assertTrue("${f.name} is not sorted", w == w.sorted())
            assertTrue("${f.name} has duplicates", w.size == w.toSet().size)
        }
    }
}
