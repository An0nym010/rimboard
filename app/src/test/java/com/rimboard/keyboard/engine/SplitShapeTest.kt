package com.rimboard.keyboard.engine

import com.rimboard.keyboard.model.Languages
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Which shape of missing space the keyboard is allowed to believe in.
 *
 * `Dictionary.splitInto` turns "alot" into "a lot" and "thankyou" into "thank
 * you", and it decides by one statistic: how much rarer the run-together form
 * is than the rarer of its two halves. That was calibrated on eight English
 * words — "alot" ~495, "thankyou" ~496, "infact" ~363 against "cannot" ~37,
 * "awhile" ~49, "everyone" ~1.6 — and `SPLIT_DOMINANCE` was put in the gap.
 *
 * Swept over the top 20,000 words of all twenty-two shipped lists, that
 * statistic offers a split for about 2,900 of them. This file is about the
 * part of that population where the answer is not a matter of degree.
 *
 * ## The one-letter tail: 185 offers, none of them right
 *
 * The note on `SPLIT_SINGLE_MIN_FREQ` allows a single-letter half and argues
 * for it with "a lot" — a leading one. The rule was applied in both
 * directions, and in the other direction a single letter at the end of a word
 * is not a word whose space went missing. It is an inflection:
 *
 *     en   the a    her a    should a   could a   person a   dad a
 *     de   keine s  war s    sein s     wissen s  mutter s   sohn s
 *     nl   maar t   zal m    die n      toch t    niemand s  iedereen s
 *     da   huske s  klare s  hele s     begge s   der i      hvor i
 *     ro   fost a   spus e   vazut e    facea     nimic a    destul a
 *     pt   com a    ver a    melhor a   tens o    sei o      nem o
 *     id   tepat i  duduk i  membunuh i tinggal i tempat i
 *     hr   kao s    sam u    mislim a   nema s    izgleda s  trebas
 *
 * Every one of the 185 was a real word of its own language or a name — "maart"
 * is March, "zalm" is salmon, "minta" is a pattern, "kaos" is chaos, "yeti" is
 * a yeti. Nothing was lost by refusing them: `StripAccuracyTest` reads
 * identical to the digit in all twenty-two languages, because a chip nobody
 * would ever tap saves nobody a keystroke.
 *
 * The leading half keeps its allowance, and the reason is that it is a genuine
 * mix rather than uniformly wrong: "a lot", Danish "i går" and "i aften",
 * Norwegian "i morgen" and "i kveld", Czech "v pořádku", Slovak "v poriadku",
 * Swedish "i fråga".
 *
 * ## What is still wrong, and why no threshold fixes it
 *
 * The survey below prints the two populations this does *not* touch, because
 * both still hold false positives and neither can be separated by a number.
 *
 * English offers "on to" for **onto**, "the me" for theme, "are as" for areas,
 * "be have" for behave, "no on" for noon, "go at" for goat, "a like" for
 * alike, "some what" for somewhat. Those sit at ranks 1,572 to 4,999, while
 * "alot" is at 19,354, "thankyou" at 13,251 and "infact" at 31,644 — so in
 * English a rank cap would separate them cleanly.
 *
 * It does not generalise, and that is the finding rather than the oversight.
 * Norwegian "atjeg" (at jeg) is the 2,305th commonest entry in its corpus and
 * Swedish "attjag" (att jag) the 3,403rd — genuine missing spaces, sitting
 * exactly where English's false positives are. Subtitles lose spaces, and a
 * corpus that records the mistake often enough puts it above the word.
 *
 * So the pair "onto" / "atjeg" is this seam's "sto" / "nista": two strings
 * with the same statistics and opposite answers. A named list is the shape
 * that fixes it, and it wants a speaker of each language rather than a guess.
 */
class SplitShapeTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-split", "").let { it.delete(); it.mkdirs(); it }
        userData = UserData.inDir(dir)
    }

    @After
    fun tearDown() {
        userData.shutdown()
        dir.deleteRecursively()
    }

    private fun assets(): File =
        listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }

    private fun engineFor(lang: String): SuggestionEngine {
        val files = listOf("dictionaries/$lang.txt", "predictions/$lang.txt")
            .associateWith { File(assets(), it).readText() }
        return SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
    }

    /** The split offered for every word in the top [TOP] of [lang]'s own list. */
    private fun splitsOf(lang: Languages.Lang): List<Pair<String, String>> {
        val engine = engineFor(lang.code)
        val out = ArrayList<Pair<String, String>>()
        File(assets(), "dictionaries/${lang.code}.txt").useLines { lines ->
            for ((i, line) in lines.withIndex()) {
                if (i >= TOP) break
                val w = line.substringBefore(' ')
                if (w.length < 4) continue
                engine.splitFor(w, lang.code, lang.locale)?.let { out.add(w to it) }
            }
        }
        return out
    }

    @Test
    fun `no language offers a single letter as the second word`() {
        val casualties = StringBuilder()
        for (lang in Languages.all) {
            val bad = splitsOf(lang)
                .filter { it.second.substringAfterLast(' ').length == 1 }
                .take(6)
            if (bad.isNotEmpty()) {
                casualties.append("  ${lang.code}: ")
                casualties.append(bad.joinToString(", ") { "${it.first} -> ${it.second}" })
            }
        }
        assertEquals(
            "a one-letter second half is an inflection, not a word that lost " +
                "the space before it; all 185 of these were real words of " +
                "their own language.$casualties",
            "", casualties.toString()
        )
    }

    /**
     * The cases the feature exists for, which the rule above must not take.
     *
     * A leading single letter is the whole of "a lot", and four other
     * languages write a one-letter preposition that their corpus regularly
     * runs into the next word.
     */
    @Test
    fun `a leading single letter still splits`() {
        val kept = listOf(
            Triple("en", "alot", "a lot"),
            Triple("en", "infact", "in fact"),
            Triple("en", "thankyou", "thank you"),
            Triple("da", "igår", "i går"),
            Triple("da", "iaften", "i aften"),
            Triple("no", "imorgen", "i morgen"),
            Triple("no", "ikveld", "i kveld"),
            Triple("cs", "vpořádku", "v pořádku"),
            Triple("sk", "vporiadku", "v poriadku"),
            Triple("sv", "ifråga", "i fråga")
        )
        val lost = StringBuilder()
        for ((code, typed, want) in kept) {
            val locale = Languages.all.first { it.code == code }.locale
            val got = engineFor(code).splitFor(typed, code, locale)
            if (got != want) lost.append("  $typed wanted '$want' got '$got'")
        }
        assertEquals("", lost.toString())
    }

    /**
     * The two populations still open, printed rather than asserted.
     *
     * "leading" keeps its allowance and is a mix; "neither" is the bulk and is
     * where "onto" lives. Neither is empty and neither is separable by a
     * number — see the note on this class.
     */
    @Test
    fun `what shape the remaining splits are`() {
        var leading = 0
        var rest = 0
        val report = StringBuilder()
        for (lang in Languages.all) {
            val splits = splitsOf(lang)
            val h = splits.filter { it.second.substringBefore(' ').length == 1 }
            val n = splits.size - h.size
            leading += h.size
            rest += n
            report.append(
                "    %-3s leading-letter %3d   other %4d    %s\n"
                    .format(lang.code, h.size, n, h.take(4)
                        .joinToString("  ") { "${it.first}->${it.second}" })
            )
        }
        println(report)
        println("leading $leading, other $rest, one-letter tails 0")
        // The sweep has to keep finding something, or the assertion above is
        // measuring an empty corpus rather than a rule.
        assertTrue("the split sweep found nothing at all", leading + rest > 1000)
    }

    private companion object {
        /** Deep enough to be the vocabulary people type, shallow enough to run. */
        const val TOP = 20_000
    }
}
