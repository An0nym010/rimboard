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
 * The prose fixtures cannot see it. **Every shipped language scores about 0.0%
 * unrecognised on its own fixture** — Finnish and Hungarian included. That is
 * not a keyboard with full coverage, it is a test set inside the training set.
 * Any measurement of vocabulary coverage built on those files will report
 * success no matter what the keyboard does.
 *
 * This used to say the reason was that the fixtures come from the corpora the
 * dictionaries were counted from. They do not: the dictionaries are OPUS
 * OpenSubtitles and the fixtures are Tatoeba, and `build_prose_fixture.py`
 * says so in as many words. The observation was right and the cause was not,
 * which matters — it makes the problem look like something a different corpus
 * would fix.
 *
 * The actual cause was measured on 2026-09-05 and is a filter, not a corpus.
 * `build_prose_fixture.py` keeps a sentence only if every word passes its
 * outlier test, and a word the dictionary has never seen cannot be tested for
 * over-representation, so it fails. **Any sentence containing an
 * out-of-dictionary word is dropped whole**, from whatever corpus it came.
 * `StripAccuracyTest.what the fixture's selection rule costs` prices that at
 * 3.2 points of keystroke savings in Finnish and under the sampling noise in
 * English, and `fixtures/openvocab` holds the pair it is measured on.
 *
 * So the dictionary is truncated to its commonest [KEEP] entries and the words
 * that were cut are offered to the corrector as if typed. They are mostly
 * inflections of stems that survive the cut, which is exactly the shape of the
 * words a user reaches past the end of a shipped list.
 *
 * This used to say they are "real words of the language by construction", and
 * for an accented language that is false. The corpus holds what people type and
 * people type without accents, so the tail carries "sangeros" beside
 * "sângeros", "armady" beside "armády", "perderas" beside "perderás". Turning
 * one into the other is the accent feature doing its job, and it was being
 * counted as destruction -- which made the languages with the most accents look
 * like the worst correctors. Romanian's figure was 20.7% of it, Slovak's 18.4%,
 * Spanish's 13.8%; the direction is almost entirely one way, 37 of Romanian's
 * 37 and 29 of Slovak's 32 putting accents *back*.
 *
 * Both numbers are printed now. The first is every rewrite, which is the figure
 * the tripwire holds; the second sets the accent restorations aside, and the
 * gap between them is how much of a language's score is an artefact of its
 * orthography rather than a fault in its corrector.
 *
 * ## What it found
 *
 * A correct word outside the list is not merely underlined. It is *rewritten*:
 *
 *                en    tr    de    pl    es    ru    cs    fi    hu
 *     found    25.5  26.0  29.8  40.0  40.7  40.7  41.3  41.7  46.0
 *     shape    21.7  20.7  25.5  31.7  33.3  33.7  33.7  33.3  36.3
 *     endings  20.2  20.7  22.3  26.3  27.8  32.2  29.8  28.3  28.8
 *     prefixes 20.2  20.7  21.0  24.0  27.8  29.5  29.8  28.3  27.0
 *     accents  20.2  20.2  20.8  22.0  24.0  29.3  27.8  28.3  25.8
 *
 * Three things brought it down. [Dictionary.looksLikeAWord] holds a string
 * shaped like a word of the language to a tighter bar than one that is not,
 * which is worth five to ten points everywhere and costs nothing on the repair
 * side. Then a counted suffix inventory (`tools/derive_suffixes.py`) lets
 * eighteen languages recognise a word built out of parts they know, worth
 * another one and a half to eight points each; only Greek, Ukrainian and
 * Slovak have none, the first two deriving nothing at all and the third an
 * inventory that prevents nothing. Which languages have one, on what
 * measurement, and why English very nearly did not, is in
 * [SuffixInventoryTest].
 *
 * The bottom row is not a change to the keyboard at all: it is the same
 * measurement with accent restorations no longer scored as damage, and it is
 * here because the row above it was being read as a ranking of correctors. On
 * the corrected figures Slovak moves 5.3 points and Spanish 3.8, and Croatian
 * -- still the worst -- is worst by less than it looked.
 *
 * The prefix row is the one that took longest to think of, because every clause
 * in the engine's word-formation check read the *end* of a word. The walk was
 * written for Turkish, which is purely suffixing, and eighteen languages
 * inherited that assumption along with it — so `verschuldigde` and
 * `angeschlichen`, whose only unusual feature is a prefix, were words no rule
 * could vouch for. `tools/derive_prefixes.py` counts the other end of the word
 * the same way, ten languages ship one, and [PrefixInventoryTest] holds each of
 * them to the same trade. Dutch drops 2.2 points, Russian 2.7, Polish 2.3.
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
 * Three of those five are left alone now — `refactored`, `deduplicate` and
 * `idempotent` all look enough like English to be given the benefit. The two
 * that remain, `unhelpfully` and `prepopulated`, are each one cheap edit from a
 * real word, which is the shape no amount of looking at the string can settle.
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
        // The suffix inventory is an asset like any other, and leaving it out
        // of the map is how this measured no change at all from adding one.
        // It then happened a second time, to the prefixes: this file reported
        // every language unchanged while PrefixInventoryTest measured Russian
        // 2.7 points better, because the map is built by hand and a new asset
        // is invisible until it is named here. Any inventory the engine reads
        // has to be listed, or this test quietly measures the old build.
        File(assets(), "suffixes/$lang.txt").takeIf { it.exists() }?.let {
            files["suffixes/$lang.txt"] = it.readText()
        }
        File(assets(), "prefixes/$lang.txt").takeIf { it.exists() }?.let {
            files["prefixes/$lang.txt"] = it.readText()
        }
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

    /**
     * Whether [a] and [b] are the same word with different accents.
     *
     * The held-out sample is drawn from the tail of the dictionary, and this
     * file says those words "are real words of the language by construction".
     * For an accented language that is not true: the corpus holds what people
     * type and people type without accents, so the tail carries "sangeros"
     * beside "sângeros" and "armady" beside "armády". Rewriting one into the
     * other is the accent feature working, not the corrector destroying
     * anything, and counting it as destruction flatters nothing -- it makes
     * the languages with the most accents look like the worst correctors.
     */
    private fun accentOnly(a: String, b: String): Boolean =
        a != b && fold(a) == fold(b)

    private fun fold(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }

    /**
     * How often the corrector rewrites a correct word it has not been given,
     * and how much of that is accents being put back.
     */
    private fun destructionRate(lang: String): Triple<Double, Double, List<String>>? {
        val s = split(lang) ?: return null
        val e = engineWith(lang, s.kept)
        val locale = Locale.forLanguageTag(lang)
        var destroyed = 0
        var accents = 0
        val examples = ArrayList<String>()
        for (w in s.heldOut) {
            val fix = e.correctionFor(w, lang, locale) ?: continue
            val lower = fix.lowercase(locale)
            if (lower == w) continue
            destroyed++
            if (accentOnly(w, lower)) {
                accents++
                continue
            }
            if (examples.size < 4) examples.add("$w->$fix")
        }
        return Triple(
            destroyed.toDouble() / s.heldOut.size,
            (destroyed - accents).toDouble() / s.heldOut.size,
            examples
        )
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
                    "would make it look like a coverage test. It is not one: " +
                    "build_prose_fixture.py keeps a sentence only if every word " +
                    "passes its outlier test, and a word the dictionary does not " +
                    "hold fails that test by construction.",
                rate < 1.0
            )
        }
    }

    @Test
    fun `a correct word outside the dictionary is often rewritten, and the figure is here`() {
        val langs = listOf("tr", "fi", "hu", "pl", "cs", "de", "en", "es", "ru",
            "no", "sk", "da", "hr", "nl", "sv", "id", "uk")
        val lines = StringBuilder()
        val rates = HashMap<String, Double>()
        for (lang in langs) {
            val (rate, netRate, examples) = destructionRate(lang) ?: continue
            rates[lang] = rate
            lines.append(
                "%-4s %5.1f%%  (%4.1f%% once accents are set aside)   %s%n"
                    .format(lang, rate * 100, netRate * 100, examples.joinToString("  "))
            )
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
        // Finnish and Hungarian accepted *none* when this was written, for want
        // of any morphology at all. They have counted inventories now -- see
        // `tools/derive_suffixes.py` -- and the point of this arm is that the
        // acceptance is what moves the destruction, in both directions and for
        // the same reason: a word the keyboard can vouch for is never offered a
        // correction, so it cannot be silently rewritten.
        for (lang in listOf("fi", "hu", "pl")) {
            val sp = split(lang)!!
            val e2 = engineWith(lang, sp.kept)
            val loc = Locale.forLanguageTag(lang)
            val ok = sp.heldOut.count { e2.acceptedWord(it, lang, loc) }
            assertTrue(
                "$lang accepts only $ok of ${sp.heldOut.size} held-out words; it " +
                    "accepted zero before it had an inventory and about a tenth " +
                    "after.",
                ok >= sp.heldOut.size / 20
            )
        }
        // And a language with no inventory still accepts none, which is what
        // says the acceptance above comes from the inventory rather than from
        // something that would have happened anyway.
        //
        // Greek is the control, and is the fourth language to hold the job:
        // Czech lost it when its endings turned out to be short rather than
        // absent, Russian when the criterion moved from how much an inventory
        // accepts to how much destruction it prevents, and Slovak when its
        // stem floor turned out to be measuring the size of its corpus. A
        // control that keeps being promoted is worth re-checking after every
        // change to the derivation -- and the next promotion has to be Turkish
        // or nothing, which is a good sign that the job is nearly done.
        val el = split("el")!!
        val elEngine = engineWith("el", el.kept)
        assertTrue(
            "Greek has no shipped inventory and should still accept nothing",
            el.heldOut.none { elEngine.acceptedWord(it, "el", Locale.forLanguageTag("el")) }
        )
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
