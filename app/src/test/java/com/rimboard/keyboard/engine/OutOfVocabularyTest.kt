package com.rimboard.keyboard.engine

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * What happens to a correctly spelled word the dictionary does not hold.
 *
 * Every other measurement in this project asks how well the keyboard handles
 * words it knows. This asks about the ones it does not, which is the population
 * where the worst thing it can do — silently rewriting correct text — actually
 * happens.
 *
 * ## Why nothing found this before
 *
 * The prose fixtures cannot see it. They are drawn from the same corpora the
 * dictionaries were counted from, so **every shipped language scores about 0.0%
 * unrecognised on its own fixture** — Finnish and Hungarian included. That is
 * not a keyboard with full coverage, it is a test set inside the training set.
 * Any measurement of vocabulary coverage built on those files will report
 * success no matter what the keyboard does.
 *
 * So the dictionary is truncated to its commonest [KEEP] entries and the words
 * that were cut are offered to the corrector as if typed. They are real words
 * of the language by construction, and mostly inflections of stems that survive
 * the cut, which is exactly the shape of the words a user reaches past the end
 * of a shipped list.
 *
 * ## What it found
 *
 * A correct word outside the list is not merely underlined. It is *rewritten*:
 *
 *     en  25.5%      de  29.8%      pl  40.0%      cs  41.3%
 *     tr  26.0%      es  40.7%      ru  40.7%      fi  41.7%
 *                                                  hu  46.0%
 *
 * `elohopea` becomes `elohopeaa`, `kisegített` becomes `segített`,
 * `zignorowałeś` becomes `zignorował`, `povinnostech` becomes `povinnostem`,
 * `иностранными` becomes `иностранным`. In every case the corrector has swapped
 * one inflection for another — a claim about grammar, which nothing in it
 * knows. The two least inflected languages here, English and German, sit at the
 * bottom of the table beside Turkish, and that is the whole shape of it.
 *
 * **Turkish is the lowest of the inflecting languages, and that is not an
 * accident**: it is the only language with morphology
 * ([com.rimboard.keyboard.model.Morphology.isAgglutinative] is `lang == "tr"`).
 * Stripping suffixes to a known stem takes acceptance of held-out words from 0%
 * to 46%, and those accepted words are never offered a correction, so they
 * cannot be destroyed. Finnish and Hungarian are agglutinative in exactly the
 * same way and get none of it.
 *
 * ## Four cheaper answers, measured and rejected
 *
 * Morphology is the expensive answer, so the cheap ones were tried first. All
 * four failed, and they failed for one reason worth stating plainly: **the
 * shape of a change says nothing about whether it is a repair or a
 * destruction.** Every shape that is destructive on a correct word is also the
 * shape of a common typo, and usually a much commoner one. Only knowing the
 * language separates them. The numbers are here so none of this is tried again:
 *
 *  - **Refuse corrections that only change the tail.** Destruction is 60-75%
 *    that shape in the inflecting languages against 34-39% of real repairs —
 *    a real difference, but repairs are far commoner than out-of-vocabulary
 *    words, so the guard loses several repairs for every destruction it stops.
 *
 *  - **Cap the absolute change on long words.** The bar is a cost per
 *    character, so a twelve-letter word may be altered about twice as much as a
 *    six-letter one. But destruction peaks in the middle (fi 34% at 6-7 letters,
 *    51% at 10-11, 26% at 14+) rather than climbing, so length does not separate
 *    the two.
 *
 *  - **Refuse a correction that is the typed word with two or more trailing
 *    letters removed.** This looked decisive: 6-10% of destructions and
 *    **0.0% of real repairs** across eight languages and some 6,200 samples.
 *    It was wrong, and wrong in an instructive way — that repair sample held
 *    only substituted letters. Asked about the shape the guard would actually
 *    refuse, two genuinely extra letters at the end, the corrector repairs
 *    43-62% of them: `businessze` to `business`, `brauchenjh` to `brauchen`.
 *    A clean separation measured on the wrong population is not a clean
 *    separation.
 *
 *  - **Refuse a correction that drops two or more *leading* letters.** Three of
 *    the five real English words destroyed on the phone (below) lost a
 *    derivational prefix, so this looked promising. It is worse than the last
 *    one: only 0-2.8% of destruction has that shape, while 48-66% of words
 *    typed with two extra letters at the front are legitimately repaired. This
 *    time the cost population was measured in the same run rather than after
 *    the fact.
 *
 * ## The same thing on a real phone, with the real dictionary
 *
 * The construction above truncates the list, so the obvious objection is that
 * it measures an artefact. It does not. Twenty-two ordinary English words that
 * are simply absent from the shipped 298,946-entry list were typed on a Redmi
 * Note 8 running the built keyboard, and five of them were silently rewritten
 * into different words:
 *
 *     unhelpfully   -> unhelpful        deduplicate  -> duplicate
 *     refactored    -> factored         prepopulated -> repopulate
 *     idempotent    -> impotent
 *
 * Typed as a sentence, `unhelpfully refactored deduplicate idempotent
 * prepopulated` commits as `Unhelpful factored duplicate impotent repopulate`.
 * Nothing was underlined and nothing was offered; the words were simply
 * replaced. `idempotent` to `impotent` is the one to remember when weighing how
 * much this matters.
 *
 * ## What this test is for
 *
 * Not to hold a number down — 46% is not a number anyone chose. It is here so
 * the figure exists, so the one thing known to help shows up as helping, and so
 * a change that makes any of it worse is visible.
 */
class OutOfVocabularyTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-oov", "").let { it.delete(); it.mkdirs(); it }
        userData = UserData.inDir(dir)
    }

    @After
    fun tearDown() {
        userData.shutdown()
        dir.deleteRecursively()
    }

    private fun assets(): File =
        listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }

    private fun fixtures(): File =
        listOf(File("src/test/fixtures"), File("app/src/test/fixtures")).first { it.isDirectory }

    /** An engine whose dictionary is [dictText] rather than the shipped file. */
    private fun engineWith(lang: String, dictText: String): SuggestionEngine {
        val files = HashMap<String, String>()
        files["dictionaries/$lang.txt"] = dictText
        files["predictions/$lang.txt"] = File(assets(), "predictions/$lang.txt").readText()
        return SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
    }

    private class Split(val kept: String, val heldOut: List<String>)

    /** [lang]'s list cut at [KEEP], with a sample of what fell off the end. */
    private fun split(lang: String, want: Int = 600): Split? {
        val f = File(assets(), "dictionaries/$lang.txt")
        if (!f.exists()) return null
        val all = f.readLines().filter { it.isNotBlank() }
        if (all.size < KEEP + 5000) return null
        val cut = all.drop(KEEP)
            .mapNotNull { it.split(' ').firstOrNull() }
            .filter { w -> w.length in 6..16 && w.all { it.isLetter() } }
        if (cut.size < 500) return null
        val step = maxOf(1, cut.size / want)
        return Split(
            all.take(KEEP).joinToString(String(charArrayOf('\n'))),
            cut.filterIndexed { i, _ -> i % step == 0 }.take(want)
        )
    }

    /** How often the corrector rewrites a correct word it has not been given. */
    private fun destructionRate(lang: String): Pair<Double, List<String>>? {
        val s = split(lang) ?: return null
        val e = engineWith(lang, s.kept)
        val locale = Locale.forLanguageTag(lang)
        var destroyed = 0
        val examples = ArrayList<String>()
        for (w in s.heldOut) {
            val fix = e.correctionFor(w, lang, locale) ?: continue
            if (fix.lowercase(locale) == w) continue
            destroyed++
            if (examples.size < 4) examples.add("$w->$fix")
        }
        return destroyed.toDouble() / s.heldOut.size to examples
    }

    @Test
    fun `the prose fixtures cannot measure vocabulary coverage`() {
        // The premise of everything above, asserted rather than asserted-in-a
        // -comment: if these files ever stop being in-distribution, the
        // reasoning here needs revisiting and this is where that shows.
        val langs = listOf("fi", "hu", "tr", "pl")
        for (lang in langs) {
            val locale = Locale.forLanguageTag(lang)
            val e = engineWith(lang, File(assets(), "dictionaries/$lang.txt").readText())
            val words = File(fixtures(), "prose_$lang.txt").readLines()
                .filter { it.isNotBlank() }
                .flatMap { line ->
                    val sb = StringBuilder()
                    val out = ArrayList<String>()
                    for (c in line) {
                        if (c.isLetter() || c == '\'' || c == '’') sb.append(c)
                        else { if (sb.isNotEmpty()) out.add(sb.toString()); sb.setLength(0) }
                    }
                    if (sb.isNotEmpty()) out.add(sb.toString())
                    out.map { it.trim('\'').lowercase(locale) }.filter { it.length > 1 }
                }
            val unknown = words.count { !e.acceptedWord(it, lang, locale) }
            val rate = unknown * 100.0 / words.size
            assertTrue(
                "prose_$lang.txt reports ${"%.2f".format(rate)}% unrecognised, which " +
                    "would make it look like a coverage test. It is not one: these " +
                    "files come from the corpora the dictionaries were counted from.",
                rate < 1.0
            )
        }
    }

    @Test
    fun `a correct word outside the dictionary is often rewritten, and the figure is here`() {
        val langs = listOf("tr", "fi", "hu", "pl", "cs", "de", "en", "es", "ru")
        val lines = StringBuilder()
        val rates = HashMap<String, Double>()
        for (lang in langs) {
            val (rate, examples) = destructionRate(lang) ?: continue
            rates[lang] = rate
            lines.append("%-4s %5.1f%%   %s%n".format(lang, rate * 100, examples.joinToString("  ")))
        }
        println(lines)
        assertTrue("too few languages measured to mean anything", rates.size >= 6)
        // Not a target, a tripwire. The worst measured is Hungarian at 46%.
        val worst = rates.maxByOrNull { it.value }!!
        assertTrue(
            "${worst.key} now rewrites ${"%.0f".format(worst.value * 100)}% of correct " +
                "words it has not been given, which is worse than anything measured " +
                "when this was written.\n$lines",
            worst.value <= 0.55
        )
    }

    @Test
    fun `the one language with morphology is the least destructive of them`() {
        // The property that says morphology earns its place, and the thing that
        // would notice if it stopped working. Turkish, Finnish and Hungarian are
        // agglutinative in the same way; only Turkish is treated as such.
        val tr = destructionRate("tr")?.first
        val fi = destructionRate("fi")?.first
        val hu = destructionRate("hu")?.first
        assertTrue("could not measure all three", tr != null && fi != null && hu != null)
        val report = "tr %.1f%%  fi %.1f%%  hu %.1f%%".format(tr!! * 100, fi!! * 100, hu!! * 100)
        assertTrue(
            "Turkish is the only one of these three with suffix morphology and " +
                "should therefore destroy the fewest of its own words. $report",
            tr < fi && tr < hu
        )
    }

    @Test
    fun `morphology is what accounts for the difference`() {
        // The same comparison from the other side: with the suffix walk turned
        // off, Turkish should look like its untreated neighbours. This is what
        // makes the claim above about morphology rather than about Turkish.
        val s = split("tr")!!
        val e = engineWith("tr", s.kept)
        val locale = Locale.forLanguageTag("tr")
        val accepted = s.heldOut.count { e.acceptedWord(it, "tr", locale) }
        val rate = accepted * 100.0 / s.heldOut.size
        assertTrue(
            "Turkish accepts only ${"%.0f".format(rate)}% of held-out words; it was " +
                "46%, and an accepted word is never offered a correction and so " +
                "cannot be destroyed. Finnish and Hungarian accept 0%.",
            rate >= 30.0
        )
        for (lang in listOf("fi", "hu")) {
            val sp = split(lang)!!
            val en2 = engineWith(lang, sp.kept)
            val loc = Locale.forLanguageTag(lang)
            val ok = sp.heldOut.count { en2.acceptedWord(it, lang, loc) }
            assertTrue(
                "$lang now accepts $ok held-out words. It accepted none, for want " +
                    "of any morphology at all -- if that has changed, the figures " +
                    "in this file need remeasuring.",
                ok == 0
            )
        }
    }


    /**
     * The same finding without the truncation, in words anyone can check.
     *
     * Everything else here cuts the dictionary and measures what falls off,
     * which invites the objection that it measures the cut. These are ordinary
     * English words that are simply not in the shipped list, and the keyboard
     * on the phone rewrote five of them into different words -- typed as a
     * sentence, `unhelpfully refactored deduplicate idempotent prepopulated`
     * came back `Unhelpful factored duplicate impotent repopulate`.
     *
     * The premise is asserted rather than assumed: if any of these words is
     * added to the dictionary the test says so, because then it is measuring
     * something else.
     */
    @Test
    fun `ordinary words missing from the shipped list are rewritten, not underlined`() {
        val lang = "en"
        val locale = Locale.ENGLISH
        val e = engineWith(lang, File(assets(), "dictionaries/en.txt").readText())
        val absent = listOf(
            "misconfigured", "unhelpfully", "backported", "refactored", "deduplicate",
            "idempotent", "observability", "parallelised", "orthogonality",
            "heuristically", "prepopulated", "unsubscribing", "misconfiguration",
            "serialisation", "interoperable", "extensibility", "discoverability",
            "tokenisation", "normalisation", "parameterised", "unmaintainable",
            "disambiguation"
        )
        val nowPresent = absent.filter { e.knownIn(it, lang, locale) }
        assertTrue(
            "these are in the dictionary now, so this no longer measures a word " +
                "the keyboard has never seen: $nowPresent",
            nowPresent.isEmpty()
        )
        val rewritten = LinkedHashMap<String, String>()
        for (w in absent) {
            val fix = e.correctionFor(w, lang, locale) ?: continue
            if (fix.lowercase(locale) != w) rewritten[w] = fix
        }
        println("rewritten: " + rewritten.entries.joinToString("  ") { "${it.key}->${it.value}" })
        assertTrue(
            "more ordinary English words are being rewritten than when this was " +
                "written, which was 5 of ${absent.size}: $rewritten",
            rewritten.size <= 7
        )
        // Not an aspiration, a record of where this stands: none of them is
        // merely left alone and underlined, which is what should happen to a
        // word the keyboard does not know.
        assertTrue(
            "if nothing is rewritten any more, something has improved and these " +
                "figures need remeasuring",
            rewritten.isNotEmpty()
        )
    }

    private companion object {
        /**
         * Where the dictionary is cut. Deep enough that the stems of the words
         * beyond it are mostly still present, which is what makes the held-out
         * set inflections rather than unrelated rare words.
         */
        const val KEEP = 60000
    }
}
