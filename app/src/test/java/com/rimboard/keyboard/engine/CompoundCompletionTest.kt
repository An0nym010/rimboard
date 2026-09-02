package com.rimboard.keyboard.engine

import com.rimboard.keyboard.model.Compounds
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.random.Random

/**
 * German compounds could be judged but not typed.
 *
 * [Compounds] exists because a 200,000-entry frequency list cannot hold
 * German: compounding is productive there, so the list holds whichever
 * compounds its corpus happened to contain. Its own note measures the gap at
 * **24.4% of the German words the list misses**, and answers it — for one of
 * the two questions anyone asks about a word.
 *
 * [Compounds.splitOf] answers *is this a word*, so a compound is not
 * underlined and autocorrect will not overwrite it. Nothing answered *what is
 * this word going to be*, which is the one the user is waiting on. A compound
 * the list does not hold therefore had no completion at all: every letter
 * typed by hand, with three unrelated words on the strip the whole way.
 *
 * The same defect, and the same fix, as the Turkish generator one language
 * over — a rule that knew a word's shape for the accepting question and was
 * never asked it for the offering one.
 *
 * ## Measured
 *
 * The list cut at 40,000 so the words past the cut stand in for the ones past
 * the end of the shipped 200,000, which is the construction
 * [OutOfVocabularyTest] uses and for the same reason. Of the 108,213 held-out
 * words, **29,591 are two listed words joined**; over a seeded sample of 400
 * of those, typed letter by letter until the word appears on the strip:
 *
 *     letters typed        12.37 -> 10.29
 *     never offered at all  100% -> 14.5%
 *
 * The control is the change itself removed: with the block in
 * [SuggestionEngine.suggestionsFor] disabled this test fails at 12.37 letters
 * and 400 of 400 never offered, which is the shipped keyboard before today.
 *
 * The halves are unaffected by the cut, which is what makes it a fair one:
 * both must clear [Dictionary.stemMinFreq] of 500, and every German word that
 * common is inside the first 40,000 either way.
 *
 * ## The cost, which is the half that decided the design
 *
 * A join is grammatical rather than counted, so it must not outrank a word the
 * corpus has actually seen. Scored on the frequency of its own tail — the way
 * the elision join beside it is — these cost **1.9 points** of German keystroke
 * savings on ordinary prose, 47.75% to 45.85%, by displacing attested
 * completions of words the list does hold. Anchored below the weakest attested
 * completion, the way the generated Turkish inflections are, they cost
 * **nothing**, and the harness says so on every arm German appears in:
 *
 *     StripAccuracyTest    de saved 33.0%, by 3 letters 76%, never 0%   both
 *     OutOfVocabularyTest  de destruction 21.0%                         both
 *     StripLatencyTest     de p99 0.51 -> 0.61 ms, ceiling 2.0
 *
 * identical to the digit on the first two. The tail costs a tenth of a
 * millisecond, which is the extra prefix search per split point, and the
 * cross-language worst stays inside the 0.80-0.96 band that test's own note
 * records as its run-to-run spread.
 *
 * That is the second time anchoring has been the difference between a
 * generator that pays and one that charges every word the corpus knows for the
 * few it does not.
 */
class CompoundCompletionTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("compound", "").let { it.delete(); it.mkdirs(); it }
        userData = UserData.inDir(dir)
    }

    @After
    fun tearDown() {
        userData.shutdown()
        dir.deleteRecursively()
    }

    private fun assets(): File =
        listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }

    private fun engineWith(lang: String, dictText: String): SuggestionEngine {
        val files = HashMap<String, String>()
        files["dictionaries/$lang.txt"] = dictText
        for (kind in listOf("predictions", "suffixes", "prefixes")) {
            File(assets(), "$kind/$lang.txt").takeIf { it.isFile }?.let {
                files["$kind/$lang.txt"] = it.readText()
            }
        }
        return SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
    }

    private fun entries(lang: String): List<Pair<String, Int>> =
        File(assets(), "dictionaries/$lang.txt").readLines().mapNotNull { line ->
            val p = line.split(' ')
            if (p.size == 2) p[0] to (p[1].toIntOrNull() ?: return@mapNotNull null) else null
        }

    /** Letters typed before [word] is one of the three chips. */
    private fun lettersTyped(e: SuggestionEngine, word: String, lang: String, loc: Locale): Int {
        for (k in 1..word.length) {
            val items = e.suggestionsFor(
                word.substring(0, k), lang, loc,
                allowAutocorrect = false, personalized = false
            ).items
            if (items.any { it.equals(word, ignoreCase = true) }) return k
        }
        return word.length
    }

    @Test
    fun `a compound past the end of the list can be typed at all`() {
        val all = entries("de")
        val freq = all.toMap()
        val keep = all.take(KEEP)
        val kept = keep.map { it.first }.toHashSet()
        val floor = Dictionary.stemFloorFor("de", all.sumOf { it.second.toLong() })

        fun splittable(w: String): Boolean {
            for (i in Compounds.MIN_PART..w.length - Compounds.MIN_PART) {
                val head = w.substring(0, i)
                if (head !in kept || (freq[head] ?: 0) < floor) continue
                val tail = w.substring(i)
                if (tail in kept && (freq[tail] ?: 0) >= floor) return true
                if (tail.length > Compounds.MIN_PART && tail[0] == 's') {
                    val rest = tail.substring(1)
                    if (rest in kept && (freq[rest] ?: 0) >= floor) return true
                }
            }
            return false
        }

        val heldOut = all.drop(KEEP).map { it.first }
            .filter { it.length >= Compounds.MIN_PART * 2 && it.all(Char::isLetter) }
        val population = heldOut.filter { splittable(it) }
        assertTrue(
            "the held-out population is too small to measure: ${population.size}",
            population.size > 5_000
        )

        val rnd = Random(seed = 20260826)
        val sample = population.shuffled(rnd).take(SAMPLE)
        val e = engineWith("de", keep.joinToString("\n") { "${it.first} ${it.second}" })
        val de = Locale.GERMAN
        var typed = 0
        var never = 0
        val casualties = StringBuilder()
        for (w in sample) {
            val k = lettersTyped(e, w, "de", de)
            typed += k
            if (k == w.length) {
                never++
                if (casualties.length < 600) casualties.append(" $w")
            }
        }
        val letters = typed.toDouble() / sample.size
        val neverPct = 100.0 * never / sample.size
        println(
            "de compounds past the list: n=%d  letters %.2f  never offered %d (%.1f%%)%s"
                .format(sample.size, letters, never, neverPct, "\n  never:$casualties")
        )
        // Both are tripwires under the measured figures with room for corpus
        // noise, not targets. Before this existed they read 12.37 and 100%.
        assertTrue("a compound past the list costs too many letters: %.2f".format(letters),
            letters <= 10.5)
        assertTrue("too many compounds cannot be reached at all: %.1f%%".format(neverPct),
            neverPct <= 20.0)
    }

    /**
     * The control the anchor exists for: a word the list *does* hold keeps its
     * chip, and keeps it at the same keystroke.
     *
     * This is the failure mode of the unanchored version, which cost 1.9
     * points of German keystroke savings. Every one of these is an ordinary
     * German word that is also a plausible compound head, so each of them is a
     * prefix this feature generates joins for on every keystroke.
     */
    @Test
    fun `an attested German word is not displaced by a join`() {
        val e = engineWith("de", File(assets(), "dictionaries/de.txt").readText())
        val de = Locale.GERMAN
        val late = StringBuilder()
        for (w in listOf(
            "arbeiten", "hausaufgaben", "kinder", "wasser", "jahre", "landung",
            "freundlich", "hausarzt", "abendessen", "handtuch"
        )) {
            val k = lettersTyped(e, w, "de", de)
            // Every one of these is in the shipped list, so the attested
            // completion path reaches it well before the word is finished.
            if (k >= w.length) late.append("\n  $w: $k of ${w.length} letters")
        }
        assertEquals(
            "a German word the list holds is no longer completed, which is the " +
                "cost the anchor exists to avoid:$late",
            "", late.toString()
        )
    }

    /**
     * The three words [Compounds] names, from the shipped list.
     *
     * Its opening paragraph offers "Bananenkuchen", "Flugzeugunfall" and
     * "Nervenzelle" as ordinary German writing rather than coinages, to argue
     * that a frequency list cannot hold the language. **None of the three is
     * in the 200,000 entries it ships with** -- so the file named three
     * examples of exactly what it could judge and could not finish, and they
     * had been typed out letter by letter ever since.
     */
    @Test
    fun `the words this file argues with can now be typed`() {
        val e = engineWith("de", File(assets(), "dictionaries/de.txt").readText())
        val de = Locale.GERMAN
        val dict = e.dictionary("de", de)
        val lines = StringBuilder()
        val unfinished = ArrayList<String>()
        for (w in listOf("bananenkuchen", "flugzeugunfall", "nervenzelle")) {
            // The premise: if the list ever gains one of these, this case stops
            // measuring anything and should be swapped rather than kept green.
            assertTrue("\"$w\" is in the shipped list now; pick another", !dict.contains(w))
            val k = lettersTyped(e, w, "de", de)
            lines.append("%n  %s: %d of %d letters".format(w, k, w.length))
            if (k >= w.length) unfinished.add(w)
        }
        println("the compounds Compounds names:$lines")
        assertEquals(
            "a word this file's own note calls ordinary German still has to be " +
                "typed to the last letter:$lines",
            emptyList<String>(), unfinished
        )
    }

    /**
     * A language that writes its compounds open generates none.
     *
     * The scoping is [Compounds.writesClosed] and it is the same one
     * [Compounds.splitOf] holds: in English the rule would be a bug, because
     * "alot" is two words that wanted a space and the right answer there is
     * [Dictionary.splitInto]'s chip, not a completion that spells it closed.
     */
    @Test
    fun `English generates no joins`() {
        val freq = entries("en").toMap()
        for (p in listOf("airpo", "eachot", "thankyo", "somethin")) {
            assertEquals(
                "English was offered a closed compound for \"$p\"",
                emptyList<String>(),
                Compounds.completionsFor("en", p, 500, { freq[it] ?: 0 }) { pre ->
                    freq.entries.filter { it.key.startsWith(pre) }
                        .sortedByDescending { it.value }.take(12).map { it.key to it.value }
                }
            )
        }
    }

    /**
     * A join is only ever a continuation of what has been typed, so it can add
     * a chip and can never change the one in front.
     */
    @Test
    fun `a generated join always continues the prefix`() {
        val all = entries("de")
        val freq = all.toMap()
        val sorted = all.map { it.first }.sorted()
        fun byPrefix(p: String, n: Int) = sorted
            .filter { it.startsWith(p) }
            .sortedByDescending { freq[it] ?: 0 }
            .take(n).map { it to (freq[it] ?: 0) }
        val bad = StringBuilder()
        for (p in listOf("arbeitsp", "hausauf", "bananenk", "kinderga", "wasserf")) {
            for (w in Compounds.completionsFor("de", p, 500, { freq[it] ?: 0 }) { q ->
                byPrefix(q, 12)
            }) {
                if (!w.startsWith(p)) bad.append("\n  \"$p\" generated \"$w\"")
            }
        }
        assertEquals("a join is not a continuation of what was typed:$bad", "", bad.toString())
    }

    private companion object {
        /**
         * Where the list is cut, so what falls off stands in for what is past
         * the end of the shipped one. Lower than [OutOfVocabularyTest]'s
         * 60,000 because the population here has to be large enough to sample
         * *and* far enough past the cut to be genuinely unlisted vocabulary.
         */
        const val KEEP = 40_000

        /** Words measured. Each one is typed a letter at a time. */
        const val SAMPLE = 400
    }
}
