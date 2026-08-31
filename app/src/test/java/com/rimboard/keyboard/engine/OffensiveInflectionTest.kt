package com.rimboard.keyboard.engine

import com.rimboard.keyboard.model.Languages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
 * `assets/predictions/` is the evidence for (d), and it is good evidence
 * because it was built for something else entirely: everyday sentences with
 * corpus artifacts already filtered out. A word that model has an opinion
 * about is one ordinary people write in ordinary messages. It costs 96 of the
 * 293 additions, and that is the right side to err on -- failing to block is
 * the state this found, while blocking someone's ordinary vocabulary is a new
 * fault, and one they cannot diagnose.
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
