package com.rimboard.keyboard.engine

import com.rimboard.keyboard.model.AutocorrectGate
import com.rimboard.keyboard.model.Diacritics
import com.rimboard.keyboard.model.Key
import com.rimboard.keyboard.model.Languages
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * A word the keyboard knows, wearing an accent the language does not use.
 *
 * Every dictionary here is filtered to one orthography. `tools/
 * fetch_dictionaries.py` keeps `[a-z']` for English and `[a-zäöüß]` for
 * German, which is right for building a word list of a language and wrong
 * about the language, because every one of them borrows outside its own
 * alphabet. The English list holds 298,946 words and **not one** of them
 * carries an accent — no "café", "fiancé", "cliché", "résumé", "naïve",
 * "piñata", "déjà", "façade", "señor", "touché", and no "José", "André",
 * "Renée" or "Beyoncé" either.
 *
 * So the keyboard underlined all of them, and overwrote four:
 *
 *     fiancée -> fiance     touché -> touch
 *     jalapeño -> jalapeno  purée  -> pure
 *
 * ## The size of it
 *
 * Measured on the OpenSubtitles corpus the English list is built from: words
 * that are accented Latin and nothing else account for 307,399 tokens, and
 * **222,084 of them — 302 per million, one word in every 3,300 — fold onto a
 * word the shipped English dictionary already holds.** Those are the ones this
 * fixes. The remaining 116/M fold onto nothing and go on being underlined,
 * which is the filter working: that bucket is where the corpus's OCR damage
 * lives ("nköö", "öèëid", "îþi", "dåådh").
 *
 * ## Why accepting them is safe, and where the safety comes from
 *
 * Not from the words being loanwords — the rule cannot tell "café" from "aquí"
 * and does not try. It comes from what it costs to type one. An accent the
 * language does not use is on no key of its layout, so producing it takes a
 * long press and a second aimed tap at a popup. It cannot be a slip of the
 * thumb, so there is no typo to repair.
 *
 * That premise is load-bearing, so it is checked rather than assumed: the
 * first test below pins that **no shipped layout prints an accented letter
 * absent from its own word list**, on any of the twenty-two. Turkish draws
 * ı ö ü ş ç ğ and every one of them is Turkish; nothing prints é except the
 * languages that write with it.
 *
 * It is the same premise `Dictionary.dropsAnAccentThatWasReachedFor` already
 * used to stop the space bar undoing an accent — "Papá" committing as "papa".
 * That rule bounded only the relaxed commit bar and only same-length pairs, so
 * "fiancée" (a deletion as well as a fold) walked straight past it, and the
 * squiggle was never covered at all.
 *
 * ## What it cost, measured either side of the change
 *
 * The argument above says the false-accept cost should be *zero*, because a
 * typo generator substitutes keys and no key produces a foreign accent. It
 * reads zero:
 *
 *     AutocorrectAccuracyTest   en typos fixed   179/186 -> 179/186
 *                               tr typos fixed   238/251 -> 238/251
 *                               en real words destroyed  17/200 -> 16/200
 *     MorphologyGuardTest       tr typos accepted  68/1500 -> 68/1500
 *                               real unlisted Turkish  168/500 -> 183/500
 *     OutOfVocabularyTest       all sixteen languages, unchanged to the digit
 *
 * Not one typo was newly waved through anywhere. What moved is the other
 * column: a word destroyed became a word kept, and fifteen more real Turkish
 * words were recognised.
 *
 * ## The fifteen Turkish words are the second finding
 *
 * `tr.txt` is filtered to `[a-zçğıöşü]` and contains no â and no î — so the
 * circumflex Turkish actually writes with, in "kâğıt", "rüzgâr", "hâlâ" and
 * "resmî", is foreign to the Turkish dictionary by this rule's own test, and
 * those words were unknown. This is not only about borrowing, then: it is
 * about the orthography filter being narrower than the orthography, and the
 * same rule covers both because it never asks *why* a letter is missing.
 *
 * ## What it deliberately does not do
 *
 * Put "café" on the strip when "caf" is typed. That is a completion, it costs
 * a slot, and it needs the word to be *in* the list — a change to what
 * `fetch_dictionaries.py` keeps, priced separately. This is only about not
 * destroying or doubting a word already typed.
 */
class ForeignAccentTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-foreign", "").let { it.delete(); it.mkdirs(); it }
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

    private fun engineFor(lang: String): SuggestionEngine {
        val files = listOf("dictionaries/$lang.txt", "predictions/$lang.txt")
            .associateWith { File(assets(), it).readText() }
        return SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
    }

    private fun dictFor(lang: String, locale: Locale): Dictionary =
        engineFor(lang).dictionary(lang, locale)

    /** Only what is printed on a key, with no long press involved. */
    private fun onKeys(lang: Languages.Lang): Set<Char> {
        val out = HashSet<Char>()
        fun take(k: Key) = k.label.lowercase(lang.locale).forEach { out.add(it) }
        for (numberRow in listOf(false, true)) {
            for (globe in listOf(false, true)) {
                lang.layout(numberRow, globe).rows.forEach { r -> r.keys.forEach { take(it) } }
            }
        }
        return out
    }

    /**
     * The premise the whole rule rests on: a foreign accent costs a long press.
     *
     * If any layout printed an accented letter its language's word list does
     * not contain, that letter would be foreign by this rule's definition and
     * one tap away in practice — and a genuine mistyping of it would stop being
     * corrected. Nothing does, and this is what says so.
     */
    @Test
    fun `no layout prints an accent its language does not write with`() {
        val bad = StringBuilder()
        for (lang in Languages.all) {
            val native = dictFor(lang.code, lang.locale).nativeAccentLetters
            val drawn = onKeys(lang).filter { Diacritics.fold(it) != it }
            val foreign = drawn.filterNot { it in native }.sorted()
            if (foreign.isNotEmpty()) bad.append("  ${lang.code}: ${foreign.joinToString(" ")}")
        }
        assertTrue(
            "these layouts print an accented letter their own word list never " +
                "uses, so it is reachable without a long press and " +
                "Dictionary.withoutForeignAccents must not vouch for it:$bad",
            bad.isEmpty()
        )
    }

    /**
     * The words that were being destroyed, pinned by name.
     *
     * Four of them committed as something else on the space bar and the rest
     * were underlined. Both halves are asked here, because they are two
     * different acts at two different prices and only one of them had a guard.
     */
    @Test
    fun `english loanwords are neither underlined nor overwritten`() {
        val engine = engineFor("en")
        val loc = Locale.ENGLISH
        val overwritten = listOf("fiancée", "touché", "jalapeño", "purée")
        val underlined = listOf(
            "café", "fiancé", "cliché", "résumé", "naïve", "piñata", "déjà",
            "crème", "façade", "séance", "protégé", "soufflé", "attaché",
            "château", "voilà", "señor", "olé", "entrée", "exposé", "décor",
            "élite", "matinée", "sauté", "née"
        )
        val casualties = StringBuilder()
        for (w in overwritten + underlined) {
            if (!engine.acceptedWord(w, "en", loc, underlining = true)) {
                casualties.append(" $w(underlined)")
            }
            committedFor(engine, w, "en", loc)?.let { casualties.append(" $w->$it") }
        }
        assertEquals("", casualties.toString())
    }

    /** Names, which are the larger half of the population and the same rule. */
    @Test
    fun `a name with a foreign accent survives the space bar`() {
        val engine = engineFor("en")
        val loc = Locale.ENGLISH
        val casualties = StringBuilder()
        for (n in listOf(
            "José", "André", "Renée", "Beyoncé", "César", "Martín",
            "Ramón", "Gérard", "Hélène", "Françoise", "Pokémon"
        )) {
            if (!engine.acceptedWord(n, "en", loc, underlining = true)) {
                casualties.append(" $n(underlined)")
            }
            committedFor(engine, n, "en", loc)?.let { casualties.append(" $n->$it") }
        }
        assertEquals("", casualties.toString())
    }

    /**
     * The filter, from the other side.
     *
     * A string is not vouched for merely by carrying an accent. It has to fold
     * onto a word the list already holds, which is what keeps the corpus's own
     * OCR damage underlined, and it must carry no letter that has no base to
     * fold to — "ß", "æ" and "þ" come back from [Diacritics.fold] unchanged.
     */
    @Test
    fun `a foreign accent alone vouches for nothing`() {
        val dict = dictFor("en", Locale.ENGLISH)
        for (junk in listOf("nköö", "öèëid", "dåådh", "møhge", "rýza")) {
            assertNull(junk, dict.withoutForeignAccents(junk))
        }
        // Folds to itself where it matters: "æ" is not an accent over a base.
        assertNull("varnæs", dict.withoutForeignAccents("varnæs"))
        assertEquals("cafe", dict.withoutForeignAccents("café"))
    }

    /**
     * A language's own accents are its own business.
     *
     * Turkish prints ç on a key, so "saç" for "sac" is an ordinary typo and
     * stays one; Czech writes with ý, so a stray one is a misspelling and not a
     * long press. The rule has to be silent on both, or it would switch off the
     * accent handling of every language that has any.
     */
    @Test
    fun `a native accent is not foreign`() {
        val tr = dictFor("tr", Locale.forLanguageTag("tr"))
        for (w in listOf("saç", "çöp", "günaydın", "için", "şık")) {
            assertNull(w, tr.withoutForeignAccents(w))
        }
        val cs = dictFor("cs", Locale.forLanguageTag("cs"))
        for (w in listOf("být", "žádný", "můžeš")) {
            assertNull(w, cs.withoutForeignAccents(w))
        }
        // And the bare-key direction is untouched: it is reached only by words
        // carrying no accent at all, so nothing here can shadow it.
        assertNull("icin", tr.withoutForeignAccents("icin"))
        assertNotNull("icin", tr.accentedFormOf("icin"))
    }

    /**
     * The Turkish circumflex, which is the same defect without any borrowing.
     *
     * `fetch_dictionaries.py` filters Turkish to `[a-zçğıöşü]`, so â and î are
     * not in the list at all and "kâğıt" (paper), "rüzgâr" (wind), "hâlâ"
     * (still) and "resmî" (official) were words the keyboard did not have. By
     * this rule's test they are foreign accents — which is the right answer for
     * the wrong-sounding reason, and the reason it works: the rule asks what
     * the word list is written with, never why a letter is absent from it.
     *
     * Worth 15 of the 500 real unlisted Turkish words in `MorphologyGuardTest`,
     * which is where it surfaced.
     */
    @Test
    fun `the circumflex Turkish writes with is missing from the Turkish list`() {
        val locale = Locale.forLanguageTag("tr")
        val engine = engineFor("tr")
        val tr = engine.dictionary("tr", locale)
        assertEquals(emptySet<Char>(), tr.nativeAccentLetters.filter { it in "âî" }.toSet())
        val casualties = StringBuilder()
        for (w in listOf("kâğıt", "kâğıdı", "şikâyette", "resmî")) {
            if (!engine.acceptedWord(w, "tr", locale, underlining = true)) {
                casualties.append(" $w(underlined)")
            }
            committedFor(engine, w, "tr", locale)?.let { casualties.append(" $w->$it") }
        }
        assertEquals("", casualties.toString())
    }

    /**
     * The survey, printed rather than asserted.
     *
     * The population is every accented word in the pooled prose fixtures of all
     * twenty-two languages — real text rather than a list somebody thought of —
     * filtered per target language to those carrying at least one accent that
     * language does not use. "vouched" is what this rule now protects; "still
     * unknown" is what it leaves to the ordinary rules, because taking the
     * foreign accents off lands on nothing the list holds.
     *
     * Russian, Ukrainian and Greek read zero, and that is the right answer
     * rather than a broken one: an accented Latin word stripped of its accents
     * is still Latin, and a Cyrillic or Greek list has never held one.
     */
    @Test
    fun `what the rule reaches, per language`() {
        val pool = LinkedHashSet<String>()
        fixtures().listFiles().orEmpty()
            .filter { it.name.startsWith("prose_") }
            .sortedBy { it.name }
            .forEach { f ->
                f.readLines().forEach { line ->
                    val sb = StringBuilder()
                    for (ch in line) {
                        if (ch.isLetter()) {
                            sb.append(ch)
                        } else {
                            if (sb.isNotEmpty()) pool.add(sb.toString())
                            sb.setLength(0)
                        }
                    }
                    if (sb.isNotEmpty()) pool.add(sb.toString())
                }
            }
        val accented = pool.map { it.lowercase(Locale.ROOT) }
            .filter { w -> w.any { Diacritics.fold(it) != it } }
            .distinct()
        println("accented word types across the pooled prose fixtures: ${accented.size}")
        val rows = ArrayList<Triple<String, Int, Int>>()
        for (lang in Languages.all) {
            val dict = dictFor(lang.code, lang.locale)
            val native = dict.nativeAccentLetters
            val carriesForeign = accented.filter { w ->
                w.any { it.code >= 0x80 && Diacritics.fold(it) != it && it !in native }
            }
            val vouched = carriesForeign.count { dict.withoutForeignAccents(it) != null }
            rows.add(Triple(lang.code, vouched, carriesForeign.size - vouched))
        }
        for ((code, vouched, unknown) in rows.sortedByDescending { it.second }) {
            println("    %-3s vouched %5d   still unknown %5d".format(code, vouched, unknown))
        }
    }

    /** What the keyboard commits for [typed], or null if it leaves it alone. */
    private fun committedFor(
        engine: SuggestionEngine, typed: String, lang: String, locale: Locale
    ): String? {
        val mayCorrect = AutocorrectGate.mayCorrect(
            active = true,
            identifierContext = false,
            separator = " ",
            composing = typed,
            sentenceInitial = false,
            lang = lang
        )
        if (!mayCorrect) return null
        return engine.correctionFor(typed, lang, locale)
            ?.takeIf { it.lowercase(locale) != typed.lowercase(locale) }
    }
}
