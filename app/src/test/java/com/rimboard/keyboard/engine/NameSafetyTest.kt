package com.rimboard.keyboard.engine

import com.rimboard.keyboard.model.AutocorrectGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * What the keyboard does to a name it has never seen.
 *
 * Autocorrect is measured from two sides already — [AutocorrectAccuracyTest]
 * scores what it repairs and what it overwrites — but the "overwrites" corpus
 * there is real words of *another language*, standing in for names, brands and
 * jargon. This asks the question directly, with real proper nouns, and it asks
 * it about the one signal that separates a name from a typo: **the capital the
 * user typed on purpose.**
 *
 * ## The corpus
 *
 * Capitalised, never sentence-initial words from the real prose in
 * `src/test/fixtures`, pooled across every language, then filtered to those
 * the dictionary under test does not know. Pooling is what makes it a fair
 * corpus rather than a vacuous one: a language's *own* fixture holds only the
 * famous names its subtitle corpus already contains, so measured within one
 * language this scored 0 destroyed of 304 names and said nothing at all. A
 * Finnish or Indonesian place name typed into an English field is the real
 * case — a colleague, a city, a brand — and it is the one that was broken.
 *
 * German is excluded from the *harvest* because it capitalises every noun, so
 * it would contribute ordinary vocabulary rather than names. It is excluded
 * from the *rule* for the same reason, and that exclusion has a real cost:
 * German names get no protection from this. That is a deliberate trade, made
 * the same way in [com.rimboard.keyboard.model.SpellCandidacy], and it is
 * better recorded here than discovered later.
 *
 * ## What was measured, 2026-08-23
 *
 * Unknown proper nouns silently overwritten on the space bar:
 *
 *     typed capitalised   en 11.7% (15/128)   tr 15.1% (23/152)
 *     typed lowercase     en 11.7% (15/128)   tr 15.1% (23/152)
 *
 * The two rows being identical is the whole finding: the capital changed
 * nothing. "César" was committed as "Cesar", "Noël" as "Noel", "Parijs" as
 * "Paris", "Sundays" as "Sunday" — while the spell checker, reading the same
 * capital, had been declining to underline those very words all along.
 *
 * ## All twenty-one, 2026-08-30
 *
 * The guard was measured on English and Turkish and asserted for them. It is
 * now asked of every language that ships, and **it holds in all of them: not
 * one capitalised name is overwritten anywhere.** The lowercase column, which
 * is what the same words suffer without the capital, runs 7.5% to 15.3% --
 * no 15.3, cs 13.4, sv 12.9, tr 12.4, pl 12.4, fr 11.3, fi 11.0, hr 10.9,
 * id 10.8, it 10.7, da 10.4, sk 10.3, es 10.1, hu 10.1, pt 9.7, ro 9.3,
 * en 9.2, nl 7.5. So the risk this guards against is real in every one of
 * them and about as large as it is in English.
 *
 * **German is excluded from the sweep as it is from the rule**, and the cost
 * of that exclusion is now measured rather than assumed: asked anyway,
 * German overwrites 25 of its 205 capitalised names. That is the price of
 * SpellCandidacy`s CAPITALS_ARE_ORDINARY, which exists because German
 * capitalises every noun and reading a mid-sentence capital as "this is a
 * name" there would stop the keyboard checking most of a German sentence.
 * The trade is deliberate; the number is what it costs.
 *
 * **Russian, Ukrainian and Greek cannot express this failure at all** and are
 * pinned by name below rather than quietly passing. The pooled corpus is
 * overwhelmingly Latin names, and a Latin string is not within any edit
 * budget of a Cyrillic or Greek word, so nothing in it is at risk for them.
 * An assertion that is true because the question was never askable is the
 * exact fault this file was rewritten to avoid.
 */
class NameSafetyTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-names", "").let { it.delete(); it.mkdirs(); it }
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

    private fun realEngine(lang: String): SuggestionEngine {
        val files = listOf("dictionaries/$lang.txt", "predictions/$lang.txt")
            .associateWith { File(assets(), it).readText() }
        return SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
    }

    private fun tokens(sentence: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (ch in sentence) {
            if (ch.isLetter() || ch == '\'') sb.append(ch)
            else {
                if (sb.isNotEmpty()) out.add(sb.toString())
                sb.setLength(0)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    /**
     * Every prose fixture, the held-out ones included.
     *
     * The held-out six were pooled in when the corpus ran out of headroom: the
     * at-risk count below is a *sample size*, and every real improvement to the
     * keyboard shrinks it -- the accented names ("Cézar", "Papá", "Noël") left
     * it for [ForeignAccentTest], and English fell to seven, one under the
     * floor that keeps this test honest. Drawing more sample is the answer to
     * that; moving the floor down to meet it is not, because the floor is the
     * only thing standing between this measurement and the vacuous one it
     * started as.
     */
    private fun proseFiles(): List<File> =
        (fixtures().listFiles().orEmpty().toList() +
            File(fixtures(), "heldout").listFiles().orEmpty().toList())
            .filter { it.name.startsWith("prose_") && it.name != "prose_de.txt" }
            .sortedBy { it.path }

    /** `drop(1)` is the sentence-initial word, which carries no evidence. */
    private fun properNouns(): List<String> {
        val out = LinkedHashSet<String>()
        proseFiles()
            .forEach { f ->
                f.readLines().filter { it.isNotBlank() }.forEach { line ->
                    tokens(line).drop(1).forEach { w ->
                        if (w.length >= 3 && w[0].isUpperCase() &&
                            w.drop(1).none { c -> c.isUpperCase() } &&
                            w.all { c -> c.isLetter() }
                        ) out.add(w)
                    }
                }
            }
        return out.toList()
    }

    /** What the keyboard commits for [typed], or null if it leaves it alone. */
    private fun committed(
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

    @Test
    fun `a name typed with its capital is not silently rewritten`() {
        val nouns = properNouns()
        val report = StringBuilder()
        val weak = mutableListOf<String>()
        for ((lang, locale) in listOf(
            *com.rimboard.keyboard.model.Languages.all
                .filter { it.code != "de" }
                .map { it.code to it.locale }.toTypedArray()
        )) {
            val engine = realEngine(lang)
            val unknown = nouns.filter { !engine.acceptedWord(it, lang, locale) }

            // What the old behaviour did, which is what the same words suffer
            // when typed in lower case. Measured rather than assumed, because
            // it is also the floor that keeps this corpus honest.
            val exposed = unknown.filter { n ->
                val f = engine.correctionFor(n.lowercase(locale), lang, locale)
                f != null && f.lowercase(locale) != n.lowercase(locale)
            }
            val destroyed = unknown.filter { committed(engine, it, lang, locale) != null }

            report.append(
                "%s: %d unknown proper nouns, %d would be overwritten lowercase (%.1f%%), %d capitalised\n"
                    .format(lang, unknown.size, exposed.size,
                        exposed.size * 100.0 / unknown.size, destroyed.size)
            )
            report.append("    lowercase casualties: ")
            report.append(exposed.take(8).joinToString(", ") {
                it + "->" + engine.correctionFor(it.lowercase(locale), lang, locale)
            })
            report.append('\n')

            // The corpus must actually contain the failure, or the assertion
            // below is vacuous. The first version of this measurement scored
            // 0 of 304 because every name in a language's own fixture is one
            // its dictionary already holds; that mistake must not pass again.
            // Collected rather than asserted per language, because three of
            // the twenty-one genuinely cannot hold anything at risk -- see
            // below the loop.
            if (exposed.size < AT_RISK_FLOOR) weak.add(lang)
            assertEquals(
                "the keyboard silently overwrote a capitalised name in $lang.\n$report",
                emptyList<String>(), destroyed
            )
        }
        println(report)
        // Three languages cannot express this failure and it is not a fault
        // in them. The pooled corpus is overwhelmingly Latin names, and a
        // Latin string is not within any edit budget of a Cyrillic or Greek
        // word -- so nothing in it is at risk, and for those three the
        // assertion above is true without being evidence. Pinned by name so
        // that a *fourth* language falling into the set says so: the floor
        // caught English at seven once already, when a real fix took the
        // accented names out of the corpus.
        assertEquals(
            "which languages the pooled name corpus can and cannot put at " +
                "risk has changed.\n" + report,
            listOf("ru", "uk", "el"), weak
        )
    }

    @Test
    fun `the guard does not leak into ordinary lowercase typing`() {
        // The same words in lower case are still corrected exactly as before.
        // Without this, "protect names" could be implemented by switching
        // autocorrect off, and every figure in AutocorrectAccuracyTest would
        // still pass while the keyboard did nothing.
        val nouns = properNouns()
        // The same twenty-one, for the same reason: a rule that fires where
        // it should not would fire in some language other than the two this
        // used to ask about.
        val weak = mutableListOf<String>()
        for ((lang, locale) in listOf(
            *com.rimboard.keyboard.model.Languages.all
                .filter { it.code != "de" }
                .map { it.code to it.locale }.toTypedArray()
        )) {
            val engine = realEngine(lang)
            val lower = nouns.map { it.lowercase(locale) }
                .filter { !engine.acceptedWord(it, lang, locale) }
            val corrected = lower.count { committed(engine, it, lang, locale) != null }
            if (corrected < AT_RISK_FLOOR) weak.add(lang)
        }
        assertEquals(
            "lowercase words stopped being corrected somewhere, which means " +
                "the name guard is firing where it should not -- or the three " +
                "languages whose corpus cannot express this have changed.",
            listOf("ru", "uk", "el"), weak
        )
    }

    @Test
    fun `refusing to commit a name still offers the correction to tap`() {
        // The trade only works if this holds. Declining to auto-commit is
        // cheap precisely because the guess stays one tap away; if the chip
        // vanished too, protecting names would mean hiding real repairs.
        val lang = "en"
        val locale = Locale.ENGLISH
        val engine = realEngine(lang)
        val nouns = properNouns()
        // A name the engine would have overwritten, so there is a correction
        // to look for at all.
        val victim = nouns.first { n ->
            !engine.acceptedWord(n, lang, locale) &&
                engine.correctionFor(n.lowercase(locale), lang, locale)
                    ?.lowercase(locale)?.let { it != n.lowercase(locale) } == true
        }
        val res = engine.suggestionsFor(
            victim, lang, locale,
            // What the strip now passes for a mid-sentence capital.
            allowAutocorrect = false,
            personalized = false
        )
        assertEquals(
            "the bold must be gone: nothing may claim the separator will commit it",
            -1, res.autocorrectIndex
        )
        assertTrue(
            "the correction for $victim disappeared from the strip entirely, " +
                "so the name is protected by hiding the repair: ${res.items}",
            res.items.size > 1
        )
    }

    @Test
    fun `a capitalised word that opens a sentence is still corrected`() {
        // Auto-capitalisation capitalises the first word of every sentence, so
        // this is the difference between a name rule and switching autocorrect
        // off for a fifth of everything anyone writes.
        //
        // The fixture word was "Teh" first and this test failed, which is the
        // self-check below doing its job: "teh" is in the subtitle corpus as a
        // word, so the engine offers nothing for it and the test would have
        // passed while proving nothing.
        val engine = realEngine("en")
        val gate = { s: Boolean ->
            AutocorrectGate.mayCorrect(
                active = true, identifierContext = false, separator = " ",
                composing = "Helko", sentenceInitial = s, lang = "en"
            )
        }
        assertTrue("a sentence opener must still be corrected", gate(true))
        assertTrue(
            "the fixture word must be one the engine actually repairs, " +
                "or this test would pass for the wrong reason",
            engine.correctionFor("helko", "en", Locale.ENGLISH)?.lowercase() == "hello"
        )
    }

    private companion object {
        /**
         * How many names the pooled corpus must actually put at risk in a
         * language before the guard measured on it means anything.
         *
         * Eight, unchanged, and it has already earned its keep twice: the
         * first version of this measurement scored 0 of 304 because a
         * language's own fixture holds only the names its dictionary already
         * knows, and English later fell to seven when a real fix took the
         * accented names out of the corpus. Both times this number is what
         * said so.
         */
        const val AT_RISK_FLOOR = 8
    }

}
