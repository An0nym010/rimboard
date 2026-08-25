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
 * Ukrainian words whose apostrophe is a letter: `комп'ютер`, `здоров'я`.
 *
 * The corpus behind the shipped lists split at every apostrophe, so a Ukrainian
 * word containing one was cut in two and neither piece is a word. The keyboard
 * therefore called the language's most ordinary vocabulary misspelled —
 * computer, health, family, name, five — and **autocorrect rewrote
 * `комп'ютер` to `компьютер`, which is the Russian spelling.** Underlining a
 * word is one thing; silently replacing a Ukrainian word with a Russian one is
 * another, and it is what this fixes.
 *
 * See [com.rimboard.keyboard.model.InnerApostrophe] for why the halves being
 * present is evidence rather than an accident.
 */
class UkrainianApostropheTest {

    private lateinit var dir: File
    private lateinit var userData: UserData
    private val lang = "uk"
    private val locale: Locale = Locale.forLanguageTag("uk")

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-uk", "").let { it.delete(); it.mkdirs(); it }
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

    private fun engine(): SuggestionEngine {
        val files = listOf("dictionaries/$lang.txt", "predictions/$lang.txt")
            .associateWith { File(assets(), it).readText() }
        return SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
    }

    /** Everyday words, all written with an apostrophe. */
    private val everyday = listOf(
        "комп'ютер", "здоров'я", "сім'я", "ім'я", "м'ясо", "п'ять",
        "об'єкт", "з'їзд", "пір'я", "в'язень", "п'яний", "м'який",
        "інтерв'ю", "прем'єр", "об'єднання", "дев'ять", "подвір'я"
    )

    @Test
    fun `ordinary Ukrainian vocabulary is spelled correctly`() {
        val e = engine()
        val rejected = everyday.filterNot { e.acceptedWord(it, lang, locale) }
        assertEquals("these are ordinary Ukrainian words", emptyList<String>(), rejected)
    }

    @Test
    fun `the computer is not quietly turned Russian`() {
        // The worst of it. Not merely underlined — rewritten on the space bar,
        // into the spelling of a different language.
        val e = engine()
        assertEquals(
            "комп'ютер must not be autocorrected",
            null, e.correctionFor("комп'ютер", lang, locale)
        )
        assertEquals(null, e.correctionFor("здоров'я", lang, locale))
    }

    @Test
    fun `the mark is only a letter where the orthography allows it`() {
        val e = engine()
        // A vowel before the mark is not Ukrainian spelling.
        assertFalse(e.acceptedWord("сі'ям", lang, locale))
        // Nor is a non-iotated vowel after it: the four are я ю є ї.
        assertFalse(e.acceptedWord("комп'утер", lang, locale))
        // Nothing on one side of it.
        assertFalse(e.acceptedWord("'ясо", lang, locale))
        assertFalse(e.acceptedWord("комп'", lang, locale))
    }

    @Test
    fun `a half the corpus never saw is still refused`() {
        // The floor doing its job: "солов" is absent, so this stays unknown
        // rather than being invented.
        val e = engine()
        assertFalse(e.acceptedWord("солов'ї", lang, locale))
        assertFalse(e.acceptedWord("зззз'ято", lang, locale))
    }

    @Test
    fun `Russian is untouched, without being named`() {
        // The rule names no language. Russian shares the alphabet and does not
        // write an apostrophe before an iotated vowel, so it matches nothing —
        // the same self-limiting guarantee Elision relies on.
        val ruFiles = listOf("dictionaries/ru.txt", "predictions/ru.txt")
            .associateWith { File(assets(), it).readText() }
        val ru = SuggestionEngine.forTesting(userData) { p -> ruFiles[p]?.byteInputStream() }
        val loc = Locale.forLanguageTag("ru")
        assertFalse(ru.acceptedWord("комп'ютер", "ru", loc))
    }

    @Test
    fun `over the real corpus no apostrophe word is left unknown`() {
        val e = engine()
        val unknown = ArrayList<String>()
        var apostrophes = 0
        File(fixtures(), "prose_$lang.txt").readLines().filter { it.isNotBlank() }
            .forEach { line ->
                val sb = StringBuilder()
                val words = ArrayList<String>()
                for (ch in line) {
                    if (ch.isLetter() || ch == '\'' || ch == '’') sb.append(ch)
                    else { if (sb.isNotEmpty()) words.add(sb.toString()); sb.setLength(0) }
                }
                if (sb.isNotEmpty()) words.add(sb.toString())
                for (raw in words) {
                    val w = raw.trim('\'').lowercase(locale)
                    val i = w.indexOfFirst { it == '\'' || it == '’' }
                    if (w.length < 2 || i <= 0 || i >= w.length - 1) continue
                    apostrophes++
                    if (!e.acceptedWord(w, lang, locale)) unknown.add(w)
                }
            }
        assertTrue(
            "the Ukrainian corpus no longer contains apostrophe words, so this " +
                "measures nothing (found $apostrophes)",
            apostrophes >= 3
        )
        assertEquals(
            "apostrophe words still called unknown", emptyList<String>(), unknown
        )
    }
}
