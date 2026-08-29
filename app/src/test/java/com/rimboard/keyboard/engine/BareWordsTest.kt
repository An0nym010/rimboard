package com.rimboard.keyboard.engine

import com.rimboard.keyboard.model.BareWords
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.text.Normalizer
import java.util.Locale

/**
 * Every word claimed to stand on its own, checked against the shipped lists.
 *
 * [com.rimboard.keyboard.model.BareWords] exists because frequency cannot
 * settle whether a bare spelling is a word. Croatian `sto` (a hundred) runs at
 * 42.2x its accented twin and `nista` at 42.1x, a tenth of a point apart and
 * meaning entirely different things — so the ones that are words are named.
 *
 * A named list is only as good as its evidence, and the failure mode is
 * silence: an entry that is wrong, or that names a word which was never at
 * risk, costs a catch and nothing says so. Each claim is therefore checked
 * against the dictionaries rather than trusted:
 *
 *  * the word is in that language's list, so it is a word the corpus knows;
 *  * it has an accented twin in the same list, so it was genuinely at risk —
 *    an entry protecting a word nothing threatened is dead weight and reads as
 *    evidence when it is not;
 *  * and being listed actually protects it, at every one of the three
 *    thresholds rather than the one that happens to be loosest.
 */
class BareWordsTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-bare", "").let { it.delete(); it.mkdirs(); it }
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
        val files = langs
            .flatMap { l -> listOf("dictionaries/$l.txt", "predictions/$l.txt") }
            .filter { File(assets(), it).exists() }
            .associateWith { File(assets(), it).readText() }
        return SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
    }

    private fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }

    private fun freqs(lang: String): Map<String, Int> {
        val out = HashMap<String, Int>()
        File(assets(), "dictionaries/$lang.txt").forEachLine { line ->
            val p = line.split(' ')
            if (p.size > 1) p[1].toIntOrNull()?.let { out[p[0]] = it }
        }
        return out
    }

    @Test
    fun `every entry is a word the corpus holds`() {
        for ((lang, words) in BareWords.entries()) {
            val f = freqs(lang)
            for (w in words) {
                assertTrue(
                    "$lang '$w' is claimed to be a word but is not in the shipped " +
                        "dictionary at all, so nothing here is evidence of anything",
                    f.containsKey(w)
                )
            }
        }
    }

    @Test
    fun `every entry was genuinely at risk`() {
        // An entry that names a word with no accented twin protects nothing and
        // reads as evidence. The curated list this was seeded from held three
        // such words -- Turkish "cami", "sik" and Spanish-style spellings with
        // no counterpart -- and they are deliberately not carried over.
        for ((lang, words) in BareWords.entries()) {
            val f = freqs(lang)
            for (w in words) {
                val twin = f.keys.filter { it != w && fold(it) == w }
                assertTrue(
                    "$lang '$w' has no accented twin in the dictionary, so nothing " +
                        "would ever have treated it as a bare spelling; the entry is " +
                        "dead weight",
                    twin.isNotEmpty()
                )
            }
        }
    }

    @Test
    fun `being listed protects a word at every threshold`() {
        // The point of naming them. Before this, Turkish "cop" was safe only
        // because 28.3x happened to fall under the constant of the day, and the
        // margin was a point and a half.
        val cases = mapOf(
            "hr" to listOf("sto"),
            "tr" to listOf("cop", "tas", "cam"),
            "es" to listOf("mas", "papa", "tenia"),
            "de" to listOf("mochte", "konnte")
        )
        for ((lang, words) in cases) {
            val e = engine(lang)
            val loc = Locale.forLanguageTag(lang)
            for (w in words) {
                assertTrue(
                    "$lang '$w' must not be underlined",
                    e.acceptedWord(w, lang, loc, underlining = true)
                )
                assertTrue(
                    "$lang '$w' must not be rewritten",
                    e.acceptedWord(w, lang, loc)
                )
                assertEquals(
                    "$lang '$w' must not be corrected",
                    null, e.correctionFor(w, lang, loc)
                )
                val strip = e.suggestionsFor(
                    w, lang, loc, allowAutocorrect = true, personalized = false
                ).items
                assertEquals("$lang '$w' must keep slot zero", w, strip.firstOrNull())
                val accented = strip.drop(1).filter { fold(it) == w && it != w }
                assertTrue(
                    "$lang '$w' is a word in its own right and must not be offered " +
                        "its accented twin either: $strip",
                    accented.isEmpty()
                )
            }
        }
    }

    @Test
    fun `the neighbours it sits between are still caught`() {
        // The list must not be a way of switching the feature off. Croatian
        // "nista" is 42.1x and "sto" is 42.2x -- adjacent, opposite answers,
        // and this is the assertion that says the list is doing the separating
        // rather than a threshold quietly moving.
        val e = engine("hr")
        val hr = Locale.forLanguageTag("hr")
        for (w in listOf("nista", "zasto", "mozda", "znas")) {
            assertTrue(
                "hr '$w' should still be underlined",
                !e.acceptedWord(w, "hr", hr, underlining = true)
            )
        }
        assertTrue(
            "hr 'sto' must not be, and it is a tenth of a point away from 'nista'",
            e.acceptedWord("sto", "hr", hr, underlining = true)
        )
    }
}
