package com.rimboard.keyboard.engine

import com.rimboard.keyboard.model.TurkishMorph
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * The generator wrote `arabaı`, which is not a thing Turkish does.
 *
 * [TurkishMorph] builds inflected forms so that completion and accent
 * restoration can work on the words no frequency list contains — which in an
 * agglutinative language is most of what anybody types. It encodes vowel
 * harmony and consonant assimilation, and it encoded exactly one of the two
 * rules that govern the **seam** where a suffix meets its stem.
 *
 * Turkish never lets two vowels collide there and never lets two consonants
 * collide either, and it has two ways out:
 *
 *  - a **buffer consonant** after a vowel — "ev-e" but "araba-ya", "ev-in" but
 *    "araba-nın", "ev-i" but "araba-sı";
 *  - a **linking vowel** after a consonant — "ev-im" but "araba-m".
 *
 * Only the first existed, and only in its `y` form. So five templates that
 * attach straight to the stem — accusative, genitive, and the three
 * possessives — produced nothing usable for any stem ending in a vowel, which
 * is roughly a third of Turkish and includes the two commonest case endings in
 * the language. What they produced instead was the collision itself:
 *
 *     inflections("araba") = [arabalar, arabaı, arabaya, arabada, arabadan,
 *                             arabaın, arabaım, arabaımız, arabaınız, ...]
 *
 * ## Measured against the shipped list, with a control
 *
 * Stems the corpus counted at least three thousand times, checked against the
 * forms Turkish actually writes for them:
 *
 *                   vowel-final stems      consonant-final (the control)
 *     accusative      0 -> 344 of 344      1219 -> 1219 of 1219
 *     poss 3sg        0 -> 429 of 429      1219 -> 1219
 *     genitive        0 -> 629 of 629       942 ->  942
 *     poss 1sg        0 -> 683 of 683       692 ->  692
 *     poss 1pl        0 -> 298 of 298       186 ->  186
 *     poss 2pl        0 -> 391 of 391       151 ->  151
 *                    ───                   ────
 *                     0 -> 2,774            4,409 unchanged
 *
 * The control is the point: the harmony rules were never what was wrong.
 *
 * Those 2,774 are all *in* the word list, so the strip could reach them by
 * prefix anyway. The number this generator exists for is the other one. At the
 * [Dictionary.STEM_MIN_FREQ] floor of 500 there are **62,027 well-formed
 * inflections of vowel-final stems that the list does not contain** and it
 * produced none of them; for consonant-final stems, 71,897, all produced.
 *
 * Over-generation rises 24.00 to 24.41 forms per stem, which is what pays for
 * it, and over-generation is the safe direction here — every form is filtered
 * against what the user actually typed before it is shown.
 *
 * ## The second half, which the first half exposed
 *
 * Generating correct Turkish made a collision reachable that never had been.
 * "bişi" is in the list 379 times; its 1sg possessive is "bişim", which folds
 * onto "bisim" — and "bisim" is one thumb's width from "bizim", which the
 * corpus counted 149,991 times. The band arm of [AutocorrectAccuracyTest]
 * caught it immediately: `bisim` stopped correcting to `bizim`.
 *
 * The cause was not the new form. `accentedBuilt` asked only whether a stem
 * was `contains`ed, where [Morphology.prefixedStemIsKnown], [Compounds.splitOf],
 * [Elision.splitOf] and [Morphology.apostropheSuffixed] every one of them ask
 * it to clear [Dictionary.stemMinFreq]. It was the only path building a word
 * out of a stem without a floor under it, and nothing had reached the hole
 * before because the generator could not build the forms that fall in.
 *
 * ## What moved, end to end
 *
 * Three figures, all the same way. Turkish autocorrect at rank ~10,000 goes
 * 83% to 85%; Turkish's destruction of correct words the list does not hold
 * goes 20.7% to 20.5% (20.2% to 20.0% with accent restorations set aside);
 * and in the band arm `bahai` now corrects to "bahsi" where it used to produce
 * the generated non-word "bahaı". Every other figure in [StripAccuracyTest],
 * [AutocorrectAccuracyTest], [BilingualTest], [ForeignAccentTest],
 * [LanguageBoostAccuracyTest], [WordShapeTest] and [PredictionTest] is
 * identical.
 */
class TurkishSeamTest {

    private lateinit var dir: File
    private lateinit var userData: UserData
    private val tr = Locale.forLanguageTag("tr")

    @Before
    fun setUp() {
        dir = File.createTempFile("trseam", "").let { it.delete(); it.mkdirs(); it }
        userData = UserData.inDir(dir)
    }

    @After
    fun tearDown() {
        userData.shutdown()
        dir.deleteRecursively()
    }

    private fun assets(): File =
        listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }

    private fun engine(lang: String): SuggestionEngine {
        val files = listOf("dictionaries/$lang.txt", "predictions/$lang.txt")
            .filter { File(assets(), it).isFile }
            .associateWith { File(assets(), it).readText() }
        return SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
    }

    private val vowels = "aıoueiöü"
    private fun lastVowel(s: String): Char? = s.lastOrNull { it in vowels }
    private fun fourWay(v: Char): Char = when {
        v in "aıou" && v in "ouöü" -> 'u'
        v in "aıou" -> 'ı'
        v in "ouöü" -> 'ü'
        else -> 'i'
    }

    private fun frequencies(): Map<String, Int> {
        val out = HashMap<String, Int>(220_000)
        File(assets(), "dictionaries/tr.txt").forEachLine { line ->
            val i = line.indexOf(' ')
            if (i > 0) line.substring(i + 1).toIntOrNull()?.let { out[line.substring(0, i)] = it }
        }
        return out
    }

    /** The six forms, spelled the way Turkish spells them for this stem. */
    private fun realForms(stem: String): Map<String, String> {
        val v = fourWay(lastVowel(stem)!!)
        return if (stem.last() in vowels) {
            mapOf(
                "accusative" to "${stem}y$v", "poss3sg" to "${stem}s$v",
                "genitive" to "${stem}n${v}n", "poss1sg" to "${stem}m",
                "poss1pl" to "${stem}m${v}z", "poss2pl" to "${stem}n${v}z"
            )
        } else {
            // Softening is the neighbouring rule and the control has to model
            // it, or it is measuring its own sampler rather than the generator:
            // "kitap" takes all of these as "kitab-".
            val s = TurkishMorph.softened(stem) ?: stem
            mapOf(
                "accusative" to "$s$v", "poss3sg" to "$s$v",
                "genitive" to "$s${v}n", "poss1sg" to "$s${v}m",
                "poss1pl" to "$s${v}m${v}z", "poss2pl" to "$s${v}n${v}z"
            )
        }
    }

    @Test
    fun `a stem ending in a vowel inflects, and one ending in a consonant still does`() {
        val freq = frequencies()
        val stems = freq.entries
            .filter {
                it.value >= 3000 && it.key.length >= 3 &&
                    it.key.all(Char::isLetter) && lastVowel(it.key) != null
            }
            .map { it.key }
        val report = StringBuilder()
        val misses = StringBuilder()
        var vowelAttested = 0
        var vowelHit = 0
        var consAttested = 0
        var consHit = 0
        for (stem in stems) {
            val built = TurkishMorph.inflections(stem).toSet()
            val vowelFinal = stem.last() in vowels
            for ((name, form) in realForms(stem)) {
                // Only forms the corpus vouches for: this asks whether the
                // generator can reach real Turkish, not whether it can reach
                // everything the templates could spell.
                if ((freq[form] ?: 0) < 200) continue
                val hit = form in built
                if (vowelFinal) {
                    vowelAttested++
                    if (hit) vowelHit++ else if (misses.length < 900) {
                        misses.append("\n  $stem: $name \"$form\" not generated")
                    }
                } else {
                    consAttested++
                    if (hit) consHit++ else if (misses.length < 900) {
                        misses.append("\n  $stem: $name \"$form\" not generated (control)")
                    }
                }
            }
        }
        report.append("vowel-final: $vowelHit of $vowelAttested   ")
        report.append("consonant-final: $consHit of $consAttested")
        println(report)
        assertTrue("the list produced almost no stems: ${stems.size}", stems.size >= 5000)
        assertTrue("nothing was measured on the vowel side", vowelAttested >= 2000)
        assertEquals(
            "an inflected form Turkish actually writes, of a stem the corpus " +
                "counted thousands of times, is not generated:$misses",
            0, vowelAttested - vowelHit + (consAttested - consHit)
        )
    }

    /**
     * The seam, spelled out, so a regression says which rule it broke.
     *
     * Both directions and the softening that runs across them — the last of
     * those is what broke when the markers were first added, and the probe
     * caught `kitapın` beside the correct `kitabı`.
     */
    @Test
    fun `the buffer consonant and the linking vowel both appear`() {
        val bad = StringBuilder()
        for ((stem, wanted) in listOf(
            // After a vowel: a consonant is inserted, and which one is the
            // suffix's business.
            "araba" to listOf("arabayı", "arabası", "arabanın", "arabam", "arabamız", "arabanız"),
            "kapı" to listOf("kapıyı", "kapısı", "kapının", "kapım", "kapımız", "kapınız"),
            "oda" to listOf("odayı", "odası", "odanın", "odam", "odamız", "odanız"),
            // After a consonant: the linking vowel appears instead, and the
            // stem softens under all of them.
            "kitap" to listOf("kitabı", "kitabın", "kitabım", "kitabımız", "kitabınız"),
            "ev" to listOf("evi", "evin", "evim", "evimiz", "eviniz")
        )) {
            val built = TurkishMorph.inflections(stem).toSet()
            for (w in wanted) if (w !in built) bad.append("\n  $stem -> $w missing")
        }
        assertEquals("the seam rules stopped producing real Turkish:$bad", "", bad.toString())
    }

    /** And the collision itself is gone. */
    @Test
    fun `it does not write two vowels where Turkish writes one consonant`() {
        val bad = StringBuilder()
        for (stem in listOf("araba", "kapı", "oda", "rusya", "japonya")) {
            for (form in TurkishMorph.inflections(stem)) {
                val tail = form.removePrefix(stem)
                if (tail.isNotEmpty() && stem.last() in vowels && tail.first() in vowels) {
                    bad.append("\n  $stem -> $form has two vowels at the seam")
                }
            }
        }
        assertEquals(
            "a generated form collides two vowels, which Turkish orthography " +
                "does not do:$bad",
            "", bad.toString()
        )
    }

    /**
     * A form built from a rare stem may not displace a far commoner word.
     *
     * The floor every other stem-consuming path in the engine already asks
     * for, and the case that found it missing.
     */
    @Test
    fun `a generated accent does not outrank a common word`() {
        val e = engine("tr")
        val d = e.dictionary("tr", tr)
        assertTrue(
            "\"bişi\" has moved above the stem floor, so this no longer tests " +
                "what it says: ${d.frequency("bişi")} vs ${d.stemMinFreq}",
            d.frequency("bişi") < d.stemMinFreq
        )
        assertEquals(
            "\"bisim\" was corrected to a form generated from a stem seen ${
                d.frequency("bişi")
            } times, over a word seen ${d.frequency("bizim")} times",
            "bizim", e.correctionFor("bisim", "tr", tr)
        )
    }

    /** The floor is a floor, not a wall: a frequent stem still restores accents. */
    @Test
    fun `accents are still restored from a stem above the floor`() {
        val e = engine("tr")
        val d = e.dictionary("tr", tr)
        // The examples TurkishMorph's own doc gives for this feature. Not
        // "kitabimiz": that needs the stem hardened back from "kitab" to
        // "kitap", which this path has never done and which the floor has
        // nothing to do with -- it fails with the floor reverted too.
        val missing = listOf("kagitlarimiz", "kitaplarimizdan", "gunaydin").filterNot { bare ->
            e.suggestionsFor(bare, "tr", tr, allowAutocorrect = true, personalized = false)
                .items.any { Dictionary.foldDiacritics(it.lowercase(tr)) == bare && it != bare }
        }
        assertEquals(
            "bare-key accent restoration stopped working; stem floor is " +
                "${d.stemMinFreq}",
            emptyList<String>(), missing
        )
    }
}
