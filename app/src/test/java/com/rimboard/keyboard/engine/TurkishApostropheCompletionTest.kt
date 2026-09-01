package com.rimboard.keyboard.engine

import com.rimboard.keyboard.model.StripLayout
import com.rimboard.keyboard.model.TurkishMorph
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * The most expensive word in the keyboard was a Turkish name.
 *
 * `Paris'e`, `Rusya'nın`, `İskenderiye'ye` — a proper noun carrying its case
 * ending across an apostrophe. [Morphology.apostropheSuffixed] has recognised
 * that shape as a *word* since it was written, so the spell checker leaves it
 * alone and autocorrect will not overwrite it. Nothing has ever *completed*
 * one. Measured over the Turkish prose fixture, a word with an apostrophe in
 * it saved **0.0%** of its keystrokes where the rest of Turkish saved 38.9%,
 * and cost **8.92 letters** against 3.02.
 *
 * The cause is one line of filtering doing exactly what it should. `stemOf`
 * finds "paris" inside "paris'e" and [TurkishMorph.inflections] then builds
 * "parise" — which is not a continuation of what is on screen, so it is
 * dropped by the check that makes generation safe at all. Every candidate died
 * that way. Generating across the mark is the same rule with the mark put back
 * in.
 *
 * ## Measured
 *
 * Over every frequent Turkish word carrying each of the real case endings
 * across an apostrophe — a derived population, because what makes a word take
 * one is that it is a name and the list cannot say which words are names,
 * while the mechanism under test is the mark rather than the name:
 *
 *     n = 1,935      before      after
 *     letters typed    8.76       7.57   of a possible 8.76
 *     never offered   100.0%      19.0%
 *
 * **Every one of them, without exception, was typed out in full.** The 19% is
 * a ceiling rather than a residue: a population derived this way contains
 * `için'i` and `daha'm` beside `rusya'nın`, and nothing should complete those.
 *
 * And on the eleven real ones in the prose fixture, 9.09 letters to 8.27:
 *
 *     brezilya'nın 12 -> 10    rusya'nın  9 -> 7    japonya'nın 11 -> 9
 *     fransa'da     9 ->  8    ken'in     6 -> 5    tim'in       6 -> 5
 *
 * ## The three that do not move, and why each is right
 *
 *  - **`paris'e`, `allah'a`** — the ending is one letter, and this refuses to
 *    answer until at least one character has been typed past the mark. Same
 *    refusal the elided-article path makes for a bare `l'`, and for the same
 *    reason: with nothing after the mark there is no evidence about which of
 *    seventeen endings is wanted, and the three commonest are not a guess
 *    about this sentence.
 *  - **`iskenderiye'ye`, `finlandiya'ya`** — the corpus has never seen the
 *    stem, so there is nothing to inflect. [Morphology.apostropheSuffixed]
 *    already says so in its own doc about this exact word.
 *  - **`abd'de`** — an acronym. ABD is said "a-be-de" and takes a front-vowel
 *    ending that its spelling does not predict, which is the whole reason the
 *    apostrophe is there. Generating from spelling cannot reach it, and
 *    `apostropheSuffixed` declines to check harmony across the mark for the
 *    same reason in the other direction. A name spelled as it sounds is
 *    reached, which is most of them.
 *
 * Softening is deliberately not applied across the mark either: Turkish writes
 * `Ahmet'i`, not `Ahmed'i`, because the apostrophe marks the stem as a name to
 * be left as it is.
 */
class TurkishApostropheCompletionTest {

    private lateinit var dir: File
    private lateinit var userData: UserData
    private val tr = Locale.forLanguageTag("tr")

    @Before
    fun setUp() {
        dir = File.createTempFile("trapos", "").let { it.delete(); it.mkdirs(); it }
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
    private fun lastVowel(s: String) = s.lastOrNull { it in vowels }
    private fun fourWay(v: Char) = when {
        v in "aıou" && v in "ouöü" -> 'u'
        v in "aıou" -> 'ı'
        v in "ouöü" -> 'ü'
        else -> 'i'
    }

    private fun strip(e: SuggestionEngine, r: SuggestionsResult): List<String> {
        val v = r.items.firstOrNull() ?: return emptyList()
        return StripLayout.arrange(r.items, r.autocorrectIndex, e.acceptedWord(v, "tr", tr)) {
            "“$it”"
        }.words
    }

    /** Keystrokes before the strip offers [w]; `w.length` means never. */
    private fun keystrokes(e: SuggestionEngine, w: String): Int {
        for (k in 1..w.length) {
            val r = e.suggestionsFor(
                w.substring(0, k), "tr", tr, allowAutocorrect = true, personalized = false
            )
            if (strip(e, r).any { it.trim('“', '”').equals(w, ignoreCase = true) }) return k
        }
        return w.length
    }

    @Test
    fun `a name carrying its ending across an apostrophe is completed`() {
        val e = engine("tr")
        e.dictionary("tr", tr)
        e.predictions("", "x", "tr", tr, 1)
        val freq = HashMap<String, Int>()
        File(assets(), "dictionaries/tr.txt").forEachLine { line ->
            val i = line.indexOf(' ')
            if (i > 0) line.substring(i + 1).toIntOrNull()?.let { freq[line.substring(0, i)] = it }
        }
        val heads = freq.entries.asSequence()
            .filter {
                it.value >= 3000 && it.key.length in 4..8 &&
                    it.key.all(Char::isLetter) && lastVowel(it.key) != null
            }
            .sortedByDescending { it.value }
            .take(300)
            .map { it.key }
            .toList()
        var typed = 0
        var full = 0
        var never = 0
        var n = 0
        val examples = StringBuilder()
        for (h in heads) {
            val v = fourWay(lastVowel(h)!!)
            val loc = if (v in "aı") "da" else "de"
            val endings =
                if (h.last() in vowels) listOf("y$v", "s$v", "n${v}n", "m", "m${v}z", "n${v}z", loc)
                else listOf("$v", "${v}n", "${v}m", "${v}m${v}z", "${v}n${v}z", loc)
            for (suffix in endings) {
                val w = "$h'$suffix"
                n++
                val k = keystrokes(e, w)
                typed += k
                full += w.length
                if (k == w.length) {
                    never++
                    if (examples.length < 400) examples.append(" $w")
                }
            }
        }
        println(
            ("apostrophe words n=%d: %.2f letters typed of %.2f, never offered %.1f%%" +
                "  e.g.%s").format(n, typed.toDouble() / n, full.toDouble() / n,
                100.0 * never / n, examples)
        )
        assertTrue("the population collapsed: $n", n >= 1500)
        // The floor is loose because the three classes in the class comment
        // cannot be reached at all and are a real share of any such sample.
        assertTrue(
            "%.1f%% of these were typed out in full; before this existed it was 100%%"
                .format(100.0 * never / n),
            never.toDouble() / n < 0.40
        )
        assertTrue(
            "mean letters typed is %.2f of %.2f, which is no saving at all"
                .format(typed.toDouble() / n, full.toDouble() / n),
            typed.toDouble() / n < full.toDouble() / n - 0.6
        )
    }

    /** The named ones, so a regression says which word it broke. */
    @Test
    fun `the words from the prose fixture are offered before their last letter`() {
        val e = engine("tr")
        val bad = StringBuilder()
        for ((w, want) in listOf(
            "rusya'nın" to 7, "japonya'nın" to 9, "brezilya'nın" to 10,
            "fransa'da" to 8, "ken'in" to 5, "tim'in" to 5
        )) {
            val k = keystrokes(e, w)
            if (k > want) bad.append("\n  $w offered after $k letters, not $want")
        }
        assertEquals("a Turkish name stopped being completed across the mark:$bad", "", bad.toString())
    }

    /**
     * The refusal, which is deliberate.
     *
     * A bare mark with nothing after it has no evidence in it about which of
     * seventeen endings is meant.
     */
    @Test
    fun `a bare apostrophe offers nothing`() {
        assertEquals(
            "a mark with nothing typed past it must not offer the commonest " +
                "endings; there is nothing in the sentence saying which",
            emptyList<String>(),
            TurkishMorph.completionsFor("paris'", 4) { it == "paris" }
        )
    }

    /** And the stem keeps its own spelling across the mark. */
    @Test
    fun `a name does not soften across the apostrophe`() {
        val got = TurkishMorph.completionsFor("ahmet'i", 4) { it == "ahmet" }
        assertTrue("\"ahmet'in\" is the genitive and was not offered: $got", "ahmet'in" in got)
        assertTrue(
            "the stem softened across the mark, which Turkish does not do: $got",
            got.none { it.startsWith("ahmed") }
        )
    }

    /**
     * Control: a word without a mark in it generates exactly what it did.
     *
     * Asked of [TurkishMorph.completionsFor] rather than of the strip, because
     * the strip has three slots and whether a generated form reaches one of
     * them is a ranking question about the whole engine. This is about the
     * function that changed.
     */
    @Test
    fun `an ordinary word is unaffected`() {
        val known = setOf("kitap", "ev", "araba", "kapı")
        val missing = listOf(
            "kitapl" to "kitaplar", "evler" to "evlerde",
            "arabala" to "arabalar", "kapıla" to "kapılar"
        ).filterNot { (typed, wanted) ->
            wanted in TurkishMorph.completionsFor(typed, 6) { it in known }
        }
        assertEquals(
            "generation for a word without an apostrophe in it changed.",
            emptyList<Pair<String, String>>(), missing
        )
    }
}
