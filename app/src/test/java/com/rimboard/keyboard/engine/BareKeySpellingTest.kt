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

    private fun engine(lang: String): SuggestionEngine {
        val files = listOf("dictionaries/$lang.txt", "predictions/$lang.txt")
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
