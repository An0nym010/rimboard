package com.rimboard.keyboard.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * A word typed without its accents, against the same word spelled properly.
 *
 * Accent restoration used to fire only for bare spellings the corpus happened
 * not to contain, which is close to the opposite of what is wanted. A
 * dictionary built from subtitles holds what people type, and people type
 * without accents, so every common bare spelling *is* in there — "gunaydin" 88
 * times against "günaydın" 41,743, "teşekkürler" 224,510 against
 * "tesekkurler" 530. Being present was taken as being right, and the words the
 * feature exists for were exactly the ones it never touched.
 *
 * The rule now asks whether the bare form holds its own rather than whether it
 * appears at all. Both halves are tested here, because the danger is symmetric:
 * a threshold that fixes "gunaydin" and also rewrites "cam" into "çam" has
 * traded one wrong answer for a worse one.
 *
 * ## Three prices for three acts
 *
 * That threshold governs *replacing* a word, and fifty is what overruling
 * somebody costs. Two cheaper acts were being charged the same price.
 *
 * Offering the accented form as a chip costs a chip, and is asked at a fifth
 * of it — see [AccentSuggestionTest]. **Underlining** costs the reader a
 * glance and changes nothing they wrote, and is asked at thirty.
 *
 * Thirty is not a new number. The note on `BARE_KEY_RATIO` already found this
 * boundary: "the band below thirty holds genuine distinct words — Turkish
 * `cop` and `çöp` at 28x, `cami` and `camı` at 3x, `ucu` and `üçü` at 1x — and
 * above fifty there is nothing but accents somebody did not type." Both ends
 * were characterised and the middle never was, because one constant was doing
 * all three jobs. The squiggle lives in that middle.
 *
 * Measured on the prose fixtures with their accents stripped, which is exactly
 * what somebody typing without them produces — words caught, at fifty and at
 * thirty:
 *
 *     hr   6% -> 68%      sk  72% -> 92%      pl  84% -> 88%
 *     el  36% -> 84%      da  77% -> 87%      hu  91% -> 93%
 *     es  15% -> 33%      de  83% -> 83%      cs  90% -> 91%
 *
 * 3,494 words to 4,072 across twenty languages, for **one** extra squiggle over
 * 26,000 words of correctly accented prose. And on the curated list below —
 * the words this file pins as words in their own right — thirty wrongly
 * underlines none. Below it they start going, and the order is the argument
 * for stopping: 28 takes `cop`, 20 takes `tas`, 15 takes `möchte`, 10 takes
 * `mas`.
 *
 * Those words are no longer protected by that arithmetic, though, and the
 * order above is why. `cop` cleared a threshold of thirty by a point and a
 * half; a dictionary rebuild that moved it would have started rewriting a real
 * Turkish word with nothing to say so. They are named in
 * [com.rimboard.keyboard.model.BareWords] now and exempt at every threshold,
 * so the protection does not move when the corpus does. Croatian `sto` — a
 * hundred, at 42.2x, wedged between `zasto` at 44.0 and `nista` at 42.1 — is
 * there for the same reason and could never have been anywhere else.
 *
 * **Spanish gains least, and that is the interesting part.** The words that
 * prompted all this — `aqui` at 24.9x, `también` 28.3x, `día` 24.5x — sit
 * below thirty and go unmarked, because Turkish `tas` (21.8x) and `cop`
 * (28.3x) are genuine words at the same ratios. One number cannot separate a
 * Spanish misspelling from a Turkish noun; what differs is the language, not
 * the frequency. A per-language table is the shape that would, and calibrating
 * twenty of them needs ground truth this file has for four. Spanish is not
 * left empty-handed in the meantime: it gets the chip at ten.
 *
 * The strip's quoting of an unrecognised word still asks at fifty. It is the
 * same cost profile and could reasonably move too, but it was not measured
 * here and one change at a time is the rule that keeps these honest.
 *
 * Against the real shipped dictionaries. There is no way to write a fixture
 * for this — the whole question is what the corpus actually contains.
 */
class BareKeySpellingTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-barekey", "").let { it.delete(); it.mkdirs(); it }
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

    private val tr: Locale = Locale.forLanguageTag("tr")

    @Test
    fun `a bare spelling the corpus also contains is still a spelling`() {
        val eng = engine("tr")
        for ((bare, want) in listOf(
            "gunaydin" to "günaydın",
            "cocuklar" to "çocuklar",
            "tesekkurler" to "teşekkürler",
            "uzgunum" to "üzgünüm",
            "gorusuruz" to "görüşürüz",
            "gunler" to "günler"
        )) {
            assertFalse(
                "'$bare' is in the dictionary as corpus noise and is not a word",
                eng.acceptedWord(bare, "tr", tr)
            )
            assertEquals(
                "and '$want' is what was meant",
                want, eng.correctionCandidates(bare, "tr", tr, limit = 3).firstOrNull()
            )
        }
    }

    @Test
    fun `a word that is genuinely itself is left alone`() {
        // The other half, and the reason the threshold is where it is. Each of
        // these has an accented near-twin that means something else, and each
        // must survive: "cam" is glass and "çam" is a pine, "cop" is a baton
        // and "çöp" is rubbish, "ucu" is its tip and "üçü" is three of them.
        val eng = engine("tr")
        for (w in listOf("cam", "cop", "tas", "cami", "ucu", "sik", "yas", "bas")) {
            assertTrue(
                "'$w' is a Turkish word in its own right",
                eng.acceptedWord(w, "tr", tr)
            )
        }
    }

    @Test
    fun `the pairs most at risk in other languages protect themselves`() {
        // These are the ones a careless threshold would ruin: both spellings
        // real, both common. They are safe for the reason that makes them
        // risky -- the bare form is the *commoner* of the two, so the ratio
        // never comes near it.
        for ((lang, words) in listOf(
            "es" to listOf("si", "mas", "el", "tu", "mi", "se", "esta", "como"),
            "fr" to listOf("ou", "la", "du", "sur", "mur", "des"),
            "de" to listOf("schon", "konnte", "mochte", "waren")
        )) {
            val eng = engine(lang)
            val loc = Locale.forLanguageTag(lang)
            for (w in words) {
                assertTrue("$lang '$w' is a word and must not be rewritten",
                    eng.acceptedWord(w, lang, loc))
            }
        }
    }

    @Test
    fun `a squiggle may suspect what the space bar must leave alone`() {
        // The split, asserted from both sides. Each of these is a bare
        // spelling nobody writes on purpose, sitting between thirty and fifty
        // where the old single threshold left it entirely unremarked.
        for ((lang, words) in listOf(
            "hr" to listOf("zasto", "jos", "nesto", "vise", "znas"),
            "sk" to listOf("ked", "velmi", "moj", "este", "mna"),
            "el" to listOf("ειναι", "αυτο", "εδω")
        )) {
            val eng = engine(lang)
            val loc = Locale.forLanguageTag(lang)
            for (w in words) {
                assertFalse(
                    "$lang '$w' should now be underlined",
                    eng.acceptedWord(w, lang, loc, underlining = true)
                )
                assertTrue(
                    "$lang '$w' must still be accepted by the space bar -- a " +
                        "squiggle asks the reader to look, it does not overrule them",
                    eng.acceptedWord(w, lang, loc)
                )
                assertEquals(
                    "$lang '$w' must not be corrected to anything",
                    null, eng.correctionFor(w, lang, loc)
                )
            }
        }
    }

    @Test
    fun `Spanish is not covered, and the reason is not a missing knob`() {
        // The words that started this -- "aqui" 24.9x, "tambien" 28.3x, "dia"
        // 24.5x, "estan" 22.3x -- are all below thirty and stay unmarked.
        //
        // Not an oversight, and not fixable by turning the number down.
        // Spanish's bare spellings sit at 22-28x and Turkish's genuine words
        // sit at 21.8x ("tas") and 28.3x ("cop"), interleaved: any threshold
        // low enough to underline "aqui" also underlines a Turkish word that
        // means "stone". One number cannot separate them, because the thing
        // that differs is not the frequency but the language.
        //
        // A per-language table would -- this file already has the shape of one
        // in tools/, for suffix and prefix floors -- but calibrating twenty of
        // them needs ground truth per language, and the curated list here
        // covers four. That is the honest reason it has not been done, rather
        // than a claim that it should not be.
        //
        // Spanish is not left with nothing meanwhile: it gets the chip at ten,
        // which is what [AccentSuggestionTest] measures and what a Spanish user
        // taps to reach "aquí".
        val eng = engine("es")
        val es = Locale.forLanguageTag("es")
        for (w in listOf("aqui", "tambien", "dia", "estan", "ahi", "asi")) {
            assertTrue(
                "es '$w' is inside the band Turkish 'cop' and 'tas' live in; " +
                    "underlining it means underlining those",
                eng.acceptedWord(w, "es", es, underlining = true)
            )
        }
    }

    @Test
    fun `the words that are words in their own right are not underlined either`() {
        // The same curated list as above, asked the stricter question. This is
        // the cost side of the thirty, and it is why it is thirty: at 28 the
        // Turkish "cop" goes, at 20 "tas", at 15 the German "mochte", at 10 the
        // Spanish "mas".
        for ((lang, words) in listOf(
            "tr" to listOf("cam", "cop", "tas", "cami", "ucu", "sik", "yas", "bas"),
            "es" to listOf("si", "mas", "el", "tu", "mi", "se", "esta", "como"),
            "fr" to listOf("ou", "la", "du", "sur", "mur", "des"),
            "de" to listOf("schon", "konnte", "mochte", "waren")
        )) {
            val eng = engine(lang)
            val loc = Locale.forLanguageTag(lang)
            for (w in words) {
                assertTrue(
                    "$lang '$w' is a word in its own right and must not be underlined",
                    eng.acceptedWord(w, lang, loc, underlining = true)
                )
            }
        }
    }

    @Test
    fun `the rule follows the language, not the slot it was put in`() {
        // `acceptedWord` says the second language is "asked the *same* question
        // the primary was asked -- not merely whether the word is in its list",
        // and names the fault it fixed: "the second language got a bare
        // `contains` and the first got five rules." That fix reached
        // `wellFormedWord` and stopped short of this one, because the accent
        // rule is asked further up, where only the primary language is in hand.
        // So there really was a sixth rule the second language never got.
        //
        // It matters more than a second slot usually does. The spell checker
        // takes its primary from the *system* locale, not from the keyboard --
        // so for anyone whose phone is set to a language they do not type in,
        // the second slot is the only slot, and accent restoration was off.
        //
        // Found on a phone: Croatian "zasto" with the device in English would
        // not underline, however loudly the Croatian dictionary disagreed.
        val eng = engine("hr", "en")
        val hr = Locale.forLanguageTag("hr")
        val en = Locale.ENGLISH
        // Two conditions on a word used here, and both are the design rather
        // than an inconvenience. It must be above the underline ratio -- "cemo"
        // is 22x and correctly stays unmarked -- and English must not hold it,
        // which rules out "jos", "nesto", "sta" and "vise", all four in the
        // English list as names or words. English claiming a word is the veto
        // working, not failing.
        for (w in listOf("zasto", "znas", "mozda", "nista")) {
            assertFalse(
                "hr '$w' must be underlined whichever slot Croatian is in",
                eng.acceptedWord(w, "en", en, "hr", hr, underlining = true)
            )
            assertFalse(
                "...and it is, when Croatian is primary",
                eng.acceptedWord(w, "hr", hr, "en", en, underlining = true)
            )
        }
        // The other direction, which is what stops this being a way to reject
        // ordinary words: only a word the primary language does *not* hold
        // reaches the second at all. English "cote" is an English word, so it
        // never meets French "côte", and English "fur" never meets German
        // "für".
        val fr = engine("en", "fr")
        for (w in listOf("cote", "fur", "mas")) {
            assertTrue(
                "en '$w' is an English word and the second language cannot veto it",
                fr.acceptedWord(w, "en", en, "fr", Locale.FRENCH, underlining = true)
            )
        }
    }

    @Test
    fun `restoration is not a Turkish-only feature`() {
        // "fur" and "uber" are in the German list because subtitles are full of
        // English, not because German has those words.
        val eng = engine("de")
        val de = Locale.GERMAN
        for ((bare, want) in listOf("fur" to "für", "uber" to "über")) {
            assertFalse("German has no word '$bare'", eng.acceptedWord(bare, "de", de))
            assertEquals(
                want, eng.correctionCandidates(bare, "de", de, limit = 3).firstOrNull()
            )
        }
    }
}
