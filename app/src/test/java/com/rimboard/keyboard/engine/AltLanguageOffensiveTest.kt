package com.rimboard.keyboard.engine

import com.rimboard.keyboard.model.GlidePath
import com.rimboard.keyboard.model.KeyProximity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * "Block offensive words" asked one language, and users have two.
 *
 * `isOffensive` takes a single language and every caller handed it the
 * effective one — the language the user is detected to be writing. The other
 * enabled dictionary contributes candidates to both the strip and the glide
 * decoder, and those candidates were judged by a list that has never heard of
 * them. Every shipped language has between 36 and 63 words on its own
 * offensive list that English's does not carry, all of them present in its own
 * dictionary and several among the commonest words it has: French "merde" at
 * 121,318 per million-scaled corpus counts, Spanish "mierda" at 221,567,
 * Polish "kurwa" at 80,388, German "scheiße" at 61,982.
 *
 * **The glide half is the serious one.** A completion is offered and waits to
 * be chosen; a swipe's first candidate is committed on the lift with no
 * keystroke in between. Swiping the shape of "merde" with English effective
 * and French second put it in the message, with the setting on.
 *
 * ## Provenance was tried first and is not enough
 *
 * The obvious fix is to judge a chip by the dictionary it came out of, which
 * is what the strip already does for casing. It fixes German, Swedish and
 * Turkish and leaves French, Spanish, Polish and Dutch leaking, because an
 * English corpus built from subtitles holds "merde" (393), "mierda" (120),
 * "verdomme" (61) and "kurwa" (22) as well. Those chips are not foreign by
 * that test and go on being judged by the English list.
 *
 * So the rule is *either* language, which is what `isOffensive`'s own note
 * asks for: "this filter may only ever err strict."
 *
 * ## Except that it may not widen the exemption
 *
 * A Swede writing Swedish has to get "slut" back — there it means end — and a
 * German writing German has to get "dick". Those live in
 * [com.rimboard.keyboard.model.FalseFriends] and in the effective language's
 * answer, and asking English as well would take them straight back off, undoing
 * the fix that put them there. So the effective language's judgement that a
 * word is ordinary *here* wins over the other list, and only the other
 * direction can add. Both halves are pinned below, because a fix to one that
 * breaks the other is the failure mode this seam actually has.
 */
class AltLanguageOffensiveTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-altoff", "").let { it.delete(); it.mkdirs(); it }
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
            for (p in listOf("dictionaries/$l.txt", "offensive/$l.txt", "predictions/$l.txt")) {
                val f = assets().resolve(p)
                if (f.isFile) files[p] = f.readText()
            }
        }
        files["offensive/en.txt"] = assets().resolve("offensive/en.txt").readText()
        return SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
            .also { it.blockOffensive = true }
    }

    private fun strip(primary: String, alt: String, prefix: String): List<String> =
        engine(primary, alt).suggestionsFor(
            prefix, primary, Locale.forLanguageTag(primary),
            allowAutocorrect = false, personalized = false,
            altLang = alt, altLocale = Locale.forLanguageTag(alt)
        ).items.map { it.lowercase(Locale.ROOT) }

    /** A deliberate swipe through each letter's key centre on [lang]'s layout. */
    private fun pathOf(word: String, lang: String): GlidePath? {
        val prox = KeyProximity.forLang(lang)
        val pts = ArrayList<Float>()
        var px: Float? = null
        var py: Float? = null
        for (c in word) {
            val x = prox.gridX(c) ?: return null
            val y = prox.gridY(c) ?: return null
            val lx = px
            val ly = py
            if (lx != null && ly != null) {
                for (k in 1..6) {
                    val t = k / 6f
                    pts.add(lx + (x - lx) * t)
                    pts.add(ly + (y - ly) * t)
                }
            } else {
                pts.add(x)
                pts.add(y)
            }
            px = x
            py = y
        }
        return GlidePath.of(pts.toFloatArray(), prox)
    }

    private fun glide(primary: String, alt: String, word: String): List<String> {
        val gp = pathOf(word, primary) ?: return emptyList()
        return engine(primary, alt).glideFor(
            gp, primary, Locale.forLanguageTag(primary), personalized = false,
            altLang = alt, altLocale = Locale.forLanguageTag(alt)
        ).map { it.lowercase(Locale.ROOT) }
    }

    /**
     * Each case is a word its own language lists and English does not, with a
     * prefix that reaches it. The English layout draws every letter of these
     * four, so the swipe can be made on it — which is the configuration the
     * fault needs.
     */
    private val cases = listOf(
        Triple("fr", "mer", "merde"),
        Triple("es", "mier", "mierda"),
        Triple("pl", "kurw", "kurwa"),
        Triple("nl", "verdom", "verdomme")
    )

    @Test
    fun `the other language's list is read when it is the second one`() {
        val leaks = StringBuilder()
        for ((lang, prefix, slur) in cases) {
            if (strip("en", lang, prefix).contains(slur)) leaks.append(" strip en+$lang: $slur")
            if (glide("en", lang, slur).contains(slur)) leaks.append(" glide en+$lang: $slur")
        }
        assertEquals(
            "a word the second language calls offensive was offered, or swiped " +
                "into the message, with the setting on.$leaks",
            "", leaks.toString()
        )
    }

    /**
     * The third path, which had no notion of a second language at all.
     *
     * [SuggestionEngine.predictions] takes one language and needed to: the
     * curated model is the effective language's and nothing else reaches it
     * from the other list. The **learned** n-grams do. They are the user's own
     * history and carry no language with them -- somebody who writes French
     * and English has both in one map -- so a slur learned while French was
     * effective was predicted while they wrote English, unfiltered, and
     * predictions are the strip *before a single letter is typed*.
     *
     * Threaded through for the filter alone; nothing here ranks or sources
     * from the other language, which is what separates it from the strip.
     *
     * **The boundary this leaves is deliberate.** A word learned in a language
     * the user does not have enabled is judged by the lists of the ones they
     * do, and can survive. Widening to all twenty-two would block "mal",
     * "slut", "dick", "prick" and "cock" for everybody, which is the
     * [com.rimboard.keyboard.model.FalseFriends] problem multiplied by
     * twenty-two. The enabled languages are what the user has told the
     * keyboard they write, and they are the scope all three paths now use.
     */
    @Test
    fun `a slur learned in the other language is not predicted in this one`() {
        for (w in listOf("merde", "verdomme")) {
            repeat(3) { userData.recordBigram("oh", w) }
        }
        val leaks = StringBuilder()
        for ((primary, alt, slur) in listOf(
            Triple("en", "fr", "merde"),
            Triple("fr", "en", "merde"),
            Triple("en", "nl", "verdomme")
        )) {
            val out = engine(primary, alt).predictions(
                "", "oh", primary, Locale.forLanguageTag(primary), 5,
                personalized = true,
                altLang = alt, altLocale = Locale.forLanguageTag(alt)
            ).map { it.lowercase(Locale.ROOT) }
            if (out.contains(slur)) leaks.append(" $primary+$alt: $slur")
        }
        assertEquals(
            "a slur the user typed in their other language was predicted, with " +
                "the setting on, before they had typed a letter.$leaks",
            "", leaks.toString()
        )
    }

    /** The control: with that language effective, the filter always worked. */
    @Test
    fun `and it was already read when it is the first one`() {
        val leaks = StringBuilder()
        for ((lang, prefix, slur) in cases) {
            if (strip(lang, "en", prefix).contains(slur)) leaks.append(" strip $lang+en: $slur")
            if (glide(lang, "en", slur).contains(slur)) leaks.append(" glide $lang+en: $slur")
        }
        assertEquals("the filter stopped working in its own language.$leaks", "", leaks.toString())
    }

    /**
     * The half that must not be lost to fix the half above.
     *
     * "slut" is Swedish for end and English profanity; "dick" is German for
     * thick. Both are on the English list, and the exemption that gives them
     * back is a claim about the language being written, not about the pair of
     * languages enabled.
     */
    @Test
    fun `the false-friend exemption survives having English as the other language`() {
        val missing = StringBuilder()
        for ((lang, prefix, word) in listOf(
            Triple("sv", "slu", "slut"),
            Triple("de", "dic", "dick")
        )) {
            if (!strip(lang, "en", prefix).contains(word)) missing.append(" $lang: $word")
        }
        assertEquals(
            "a word that is ordinary vocabulary in the language being written " +
                "was withheld because the other language calls it profanity.$missing",
            "", missing.toString()
        )
    }

    /** And the same word is still filtered for somebody writing English. */
    @Test
    fun `writing English, the English judgement is the one that applies`() {
        assertTrue(
            "\"slut\" was offered to somebody writing English because Swedish " +
                "is their other language",
            !strip("en", "sv", "slu").contains("slut")
        )
    }

    private fun offensiveList(lang: String): List<String> {
        val f = assets().resolve("offensive/$lang.txt")
        if (!f.isFile) return emptyList()
        return f.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    private fun shippedLanguages(): List<String> =
        assets().resolve("dictionaries").listFiles().orEmpty()
            .map { it.name.removeSuffix(".txt") }.sorted()

    /**
     * The population the either-language rule acts on, enumerated.
     *
     * Every word that is on some other shipped language's offensive list, is
     * absent from English's, and is in the English dictionary: 162 of them.
     * Before [Dictionary.ORDINARY_HERE_PER_MILLION] every one was taken off an
     * English writer who had that language enabled, because the only exemption
     * the rule consulted was [com.rimboard.keyboard.model.FalseFriends], which
     * holds no English entry -- it was written for English words that mean
     * something else abroad, and this is the reverse direction.
     *
     * **Measured by toggling the setting, not by reading the strip.** Asking
     * whether a word appears among five completions measures its *rank*: "am"
     * is not offered for "a" either way, because "and" outranks it. The
     * difference between the same engine with `blockOffensive` off and on is
     * the filter's doing and nothing else.
     */
    @Test
    fun `the other language may add to English's list but not delete from it`() {
        val en = Locale.ENGLISH
        val enOff = offensiveList("en").toSet()
        val enDict = engine("en").dictionary("en", en)
        val ordinary = ArrayList<Pair<String, String>>()
        val notOrdinary = ArrayList<Pair<String, String>>()
        val deleted = ArrayList<Pair<String, String>>()
        val reachable = ArrayList<Pair<String, String>>()
        val leaked = ArrayList<Pair<String, String>>()
        var filteredAway = 0
        for (lang in shippedLanguages()) {
            if (lang == "en") continue
            val words = offensiveList(lang).filter { it !in enOff && enDict.contains(it) }
            if (words.isEmpty()) continue
            // One engine for this language, both settings asked of it, and
            // nothing holding it once the language is done. Each word's two
            // answers are taken here rather than in three later passes over
            // the same lists, so a language is loaded once. See [stripFor].
            val e = engine("en", lang)
            for (w in words) {
                val offered = stripFor(e, w, lang, block = false).contains(w)
                val removed = offered && !stripFor(e, w, lang, block = true).contains(w)
                if (enDict.ordinaryVocabulary(w)) {
                    ordinary.add(w to lang)
                    if (removed) deleted.add(w to lang)
                } else {
                    notOrdinary.add(w to lang)
                    // Not every one of these can be *shown* the filter in the
                    // first place -- a word the ranking never offers is never
                    // filtered either -- so the leak test asks only about the
                    // ones the strip does reach.
                    if (offered) {
                        reachable.add(w to lang)
                        if (!removed) leaked.add(w to lang)
                    }
                    if (removed) filteredAway++
                }
            }
        }
        println(
            "on another list, absent from English's, in the English dictionary: " +
                "${ordinary.size + notOrdinary.size}; ordinary English by the corpus: " +
                "${ordinary.size} -> ${ordinary.map { it.first }.distinct()}"
        )
        assertTrue("the enumeration found nothing", ordinary.size + notOrdinary.size >= 100)
        assertTrue("nothing came back at all", ordinary.size >= 10)

        assertEquals(
            "an ordinary English word was taken off the strip because the user's " +
                "other language calls it offensive.",
            emptyList<Pair<String, String>>(), deleted
        )
        println("of ${notOrdinary.size} not ordinary here, ${reachable.size} reach the strip at all")
        assertTrue("nothing reachable to check", reachable.size >= 20)
        assertEquals(
            "a word the other language calls offensive, and that English does not " +
                "use, was offered anyway.",
            emptyList<Pair<String, String>>(), leaked
        )
        assertTrue("expected some of these to be filtered", filteredAway > 0)
    }

    /**
     * The strip for [w]'s own prefix on [e], with the filter set as asked.
     *
     * **The engine is passed in, and there is no cache, deliberately.** This
     * was a `HashMap` keyed by language, which reads as an obvious saving and
     * is the opposite of one. Building an engine loads two full word lists
     * *and* pins the text they were parsed from -- [SuggestionEngine.forTesting]
     * takes a lambda over a map of file contents and the engine holds the
     * lambda -- so a map that never evicts ended up holding **all twenty-one
     * at once**, English's nine megabytes twenty-one times over. It ran out of
     * heap on the build machine and passed here only because this desk has
     * more of it, which is fifteen commits of red CI whose log nobody could
     * read. Every caller walks the languages in order and builds one engine
     * per language either way, so there was no speed in the cache to lose.
     */
    private fun stripFor(
        e: SuggestionEngine, w: String, alt: String, block: Boolean
    ): List<String> {
        e.blockOffensive = block
        return e.suggestionsFor(
            w.dropLast(1), "en", Locale.ENGLISH,
            allowAutocorrect = false, personalized = false,
            altLang = alt, altLocale = Locale.forLanguageTag(alt)
        ).items.map { it.lowercase(Locale.ROOT) }
    }

    /** Whether switching the setting on is what takes [w] away. */
    private fun removedByFilter(e: SuggestionEngine, w: String, alt: String): Boolean =
        stripFor(e, w, alt, block = false).contains(w) &&
            !stripFor(e, w, alt, block = true).contains(w)

    /**
     * The fourth path, and the only one that acts without being chosen.
     *
     * The strip offers, the glide decoder offers on a lift, predictions offer
     * before a letter is typed -- and [SuggestionEngine.correctionCandidates]
     * is what the **space bar commits**. It filtered with the single-language
     * [SuggestionEngine.isOffensive] while the other three had moved on, so a
     * typo whose repair is a word only the second language calls offensive
     * went straight into the message.
     *
     * Enumerated rather than sampled: every word on another shipped list that
     * English's does not carry and that the English dictionary holds, one
     * neighbour-key substitution at each position, asked of the commit path
     * with that language second. **64 of 707 typos committed a slur** across
     * eleven languages -- "merdr" to "merde", "mierfa" to "mierda", "pendrjo"
     * to "pendejo", "curvr" to "curve", six spellings of "pedal", fifteen of
     * "piranha".
     *
     * Which is also why this test could not simply be "nothing commits": half
     * of those sixty-four are ordinary English words that Romanian, Polish,
     * Portuguese and Danish happen to list, and taking them away is the bug
     * [Dictionary.ORDINARY_HERE_PER_MILLION] fixes. The two halves are asserted
     * together here because they are one seam.
     */
    @Test
    fun `the space bar does not commit what the other language calls offensive`() {
        val en = Locale.ENGLISH
        val enOff = offensiveList("en").toSet()
        val enDict = engine("en").dictionary("en", en)
        val prox = KeyProximity.forLang("en")
        var typos = 0
        val committed = ArrayList<String>()
        val lost = ArrayList<String>()
        for (lang in shippedLanguages()) {
            if (lang == "en") continue
            val words = offensiveList(lang)
                .filter { it !in enOff && enDict.contains(it) && it.length >= 3 }
            if (words.isEmpty()) continue
            // Built here and dropped with the iteration, for the reason on
            // [stripFor]: twenty-one of these do not fit in a test JVM.
            val e = engine("en", lang)
            e.blockOffensive = true
            val altLoc = Locale.forLanguageTag(lang)
            for (w in words) {
                val ordinaryHere = enDict.ordinaryVocabulary(w)
                for (i in w.indices) {
                    val n = prox.neighbours(w[i]).firstOrNull() ?: continue
                    val typo = w.substring(0, i) + n + w.substring(i + 1)
                    if (typo == w || enDict.contains(typo)) continue
                    typos++
                    val c = e.correctionFor(typo, "en", en, altLang = lang, altLocale = altLoc)
                        ?.lowercase(Locale.ROOT)
                    if (c != w) continue
                    if (ordinaryHere) lost.add("$lang:$typo->$w") else committed.add("$lang:$typo->$w")
                }
            }
        }
        println("neighbour-key typos tried: $typos; still repaired to an ordinary English word: ${lost.size}")
        assertTrue("the enumeration found nothing to try", typos >= 500)
        assertEquals(
            "a word only the other language calls offensive was committed on the " +
                "space bar, with the setting on: $committed",
            emptyList<String>(), committed
        )
        // And the other half of the same seam: a typo of an ordinary English
        // word still gets repaired. "curve", "pedal", "pike" and "piranha" are
        // on somebody's list and are not English's business.
        assertTrue(
            "the commit path stopped repairing typos of ordinary English words " +
                "because another language lists them",
            lost.isNotEmpty()
        )
    }

    /**
     * The two words that found this, named because they are the whole point.
     *
     * Turkish lists "got" -- the bare-key spelling of "göt" -- and "am". They
     * run at 2,702 and 1,079 per million of the English corpus, and an English
     * writer with Turkish as their second language had both taken away.
     *
     * Only "got" can be shown on the strip: "am" never outranks "and" and
     * "ama" for the prefix "a", so toggling the setting changes nothing
     * visible and the filter is not what is keeping it out. Both are asserted
     * where the decision is actually made -- [Dictionary.ordinaryVocabulary],
     * which is the whole of what the fix consults.
     */
    @Test
    fun `an English writer with Turkish enabled keeps "got" and "am"`() {
        val enDict = engine("en").dictionary("en", Locale.ENGLISH)
        val notOrdinary = listOf("got", "am").filterNot { enDict.ordinaryVocabulary(it) }
        assertEquals(
            "the corpus no longer calls these ordinary English, so the other " +
                "language's list is free to delete them again.",
            emptyList<String>(), notOrdinary
        )
        assertEquals(
            "\"got\" was withheld from an English writer because Turkish lists " +
                "the bare-key spelling of \"göt\".",
            emptyList<String>(),
            engine("en", "tr").let { e -> listOf("got").filter { removedByFilter(e, it, "tr") } }
        )
    }
}
