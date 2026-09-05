package com.rimboard.keyboard.engine

import com.rimboard.keyboard.model.GlidePath
import com.rimboard.keyboard.model.KeyProximity
import com.rimboard.keyboard.model.StripLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Everything that answers the strip answers it at the strip's width.
 *
 * The count lives in [StripLayout.SLOTS] and used to live in four places. When
 * the strip went from three chips to five, one of the copies did not move:
 * `GLIDE_OFFERED` was a literal three, with a note explaining that it was
 * three *because the strip has three slots* — so every swipe went on
 * answering three and the last two chips sat empty after every gesture. The
 * sentence and the number had come apart, and nothing was checking.
 *
 * A comment saying "three slots" that outlives the strip is untidy. A *number*
 * saying it is a defect, and this is the test for the number. Both halves are
 * asserted from behaviour rather than by scanning the source, because a scan
 * passes on the comment above the call — which is how a guard of this shape
 * has already been fooled once in this project.
 */
class StripWidthTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("stripwidth", "").let { it.delete(); it.mkdirs(); it }
        userData = UserData.inDir(dir)
    }

    @After
    fun tearDown() {
        userData.shutdown()
        dir.deleteRecursively()
    }

    private fun assets(): File =
        listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }

    /**
     * Everything the APK ships for [lang], not just the two big files.
     *
     * The suffix and prefix inventories are assets, and an engine built without
     * them is a weaker engine than the one that ships -- it vouches for fewer
     * words, so it corrects more of them. `OutOfVocabularyTest` carries the
     * scar: it measured "no change at all" from adding an inventory, twice,
     * because its own map named only the files it already knew about.
     *
     * Measured 2026-09-05 across the three benchmark helpers that had the same
     * gap: it moves the autocorrect destroy rate by at most one word in two
     * hundred, moves three English typos out of the fix denominator because
     * morphology now vouches for them, and moves the glide figures not at all.
     * Immaterial, and listed anyway, because the next arm added to one of these
     * files should not have to find that out.
     */
    private fun engine(lang: String): SuggestionEngine {
        val files = HashMap<String, String>()
        for (kind in listOf("dictionaries", "predictions", "suffixes", "prefixes")) {
            File(assets(), "$kind/$lang.txt").takeIf { it.isFile }?.let {
                files["$kind/$lang.txt"] = it.readText()
            }
        }
        return SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
    }

    /** Typing fills every chip the strip has, and never asks for one more. */
    @Test
    fun `the strip is filled to its width and no further`() {
        val e = engine("en")
        val en = Locale.ENGLISH
        var widest = 0
        for (p in listOf("th", "wond", "lov", "com", "sta", "pre", "inte")) {
            val n = e.suggestionsFor(
                p, "en", en, allowAutocorrect = true, personalized = false
            ).items.count { it.isNotEmpty() }
            assertTrue("\"$p\" filled $n chips, more than the strip has", n <= StripLayout.SLOTS)
            if (n > widest) widest = n
        }
        assertEquals(
            "no prefix filled the strip; the engine is capped below its width",
            StripLayout.SLOTS, widest
        )
    }

    /**
     * And it stays filled over real prose, not just over prefixes chosen here.
     *
     * The arm above proves the engine *can* fill five chips, from seven English
     * prefixes picked by hand. That is the right shape for catching a literal
     * three left behind somewhere, and the wrong shape for knowing what a user
     * sees: seven prefixes chosen to work say nothing about the ninety-ninth
     * keystroke of an ordinary sentence.
     *
     * This types every word of the prose fixture one letter at a time and
     * counts the chips that come back.
     *
     * ```
     *      chips filled, by letters typed        spare chips
     *      1     2     3     4     5     6     7     8    per keystroke
     * en  5.00  5.00  4.99  4.93  4.89  4.81  4.74  4.61     0.04
     * tr  5.00  5.00  5.00  4.98  4.96  4.90  4.88  4.80     0.03
     * fi  5.00  5.00  5.00  5.00  4.97  4.93  4.94  4.83     0.02
     * de  5.00  5.00  4.99  4.98  4.97  4.97  4.90  4.86     0.01
     * ```
     *
     * **While a word is being typed there is no slack at all.** A hundredth to
     * four hundredths of a chip per keystroke, and what little opens up only
     * does so past the seventh letter, by which point the word is nearly typed
     * and the list is nearly right. Whatever else the strip could be doing, it
     * is not doing it in space left over mid-word.
     *
     * That is **not** the same question as the open one about filling spare
     * slots with next-word predictions, and the two must not be read as one.
     * That question is about the moment a word is *finished*, when the chips
     * hold corrections of what was just committed rather than completions of
     * what is being typed, and there the slack is real and large — over one
     * chip on average in the Slavic languages. This arm never looks at that
     * moment: every prefix it measures is strictly shorter than its word.
     *
     * Asserted at [FILL_FLOOR] rather than at the measured 4.96–4.99, because
     * the failure worth catching is not a hundredth: it is a change that caps
     * the list below the strip's width, which is what happened to
     * `GLIDE_OFFERED` and would land here around 3.0.
     */
    @Test
    fun `the strip stays filled over real prose`() {
        val fixtures = listOf(File("src/test/fixtures"), File("app/src/test/fixtures"))
            .first { it.isDirectory }
        val report = StringBuilder()
        val thin = mutableListOf<String>()
        for (lang in FILL_LANGS) {
            val file = File(fixtures, "prose_$lang.txt")
            if (!file.isFile) continue
            val locale = Locale.forLanguageTag(lang)
            val engine = engine(lang)
            engine.dictionary(lang, locale)
            engine.predictions("", "x", lang, locale, 1)
            var filledSum = 0L
            var keystrokes = 0L
            val byLen = LongArray(MAX_PREFIX + 1)
            val seenLen = LongArray(MAX_PREFIX + 1)
            for (line in file.readLines().filter { it.isNotBlank() }) {
                for (w in line.split(Regex("[^\\p{L}']+"))) {
                    val word = w.trim('\'').lowercase(locale)
                    if (word.isEmpty() || !word.all { it.isLetter() || it == '\'' }) continue
                    for (k in 1..minOf(word.length - 1, MAX_PREFIX)) {
                        val n = engine.suggestionsFor(
                            word.substring(0, k), lang, locale,
                            allowAutocorrect = true, personalized = false
                        ).items.count { it.isNotEmpty() }
                        filledSum += n.toLong()
                        keystrokes++
                        byLen[k] += n.toLong()
                        seenLen[k]++
                    }
                }
            }
            if (keystrokes == 0L) continue
            val mean = filledSum.toDouble() / keystrokes
            report.append("    %-3s mean %.3f of %d  spare %.2f per keystroke   by length %s%n".format(
                lang, mean, StripLayout.SLOTS, StripLayout.SLOTS - mean,
                (1..MAX_PREFIX).filter { seenLen[it] > 0 }.joinToString(" ") {
                    "%.2f".format(byLen[it].toDouble() / seenLen[it])
                }
            ))
            if (mean < FILL_FLOOR) thin += "%s %.3f".format(lang, mean)
        }
        println(report)
        assertTrue("no prose fixtures found", report.isNotEmpty())
        assertTrue(
            "the strip is coming back with fewer chips than it has room for," +
                " which is what a list capped below StripLayout.SLOTS looks" +
                " like from the outside:" + System.lineSeparator() +
                thin.joinToString(System.lineSeparator()) +
                System.lineSeparator() + report,
            thin.isEmpty()
        )
    }

    /**
     * And a swipe, which is the copy that was missed.
     *
     * With `GLIDE_OFFERED` left at a literal three this fails at three against
     * a width of five, which is precisely the state that shipped.
     */
    @Test
    fun `a swipe offers as many words as the strip can show`() {
        val e = engine("en")
        val en = Locale.ENGLISH
        val prox = KeyProximity.forLang("en")
        var widest = 0
        for (word in listOf("there", "hello", "morning", "people", "something")) {
            val pts = ArrayList<Float>()
            var px: Float? = null
            var py: Float? = null
            for (c in word) {
                val x = prox.gridX(c) ?: continue
                val y = prox.gridY(c) ?: continue
                val lx = px
                val ly = py
                if (lx != null && ly != null) {
                    for (k in 1..6) {
                        val t = k / 6f
                        pts.add(lx + (x - lx) * t)
                        pts.add(ly + (y - ly) * t)
                    }
                } else {
                    pts.add(x); pts.add(y)
                }
                px = x; py = y
            }
            val path = GlidePath.of(pts.toFloatArray(), prox) ?: continue
            val n = e.glideFor(path, "en", en, personalized = false).size
            assertTrue("\"$word\" offered $n words, more than the strip has",
                n <= StripLayout.SLOTS)
            if (n > widest) widest = n
        }
        assertEquals(
            "a swipe never offers as many words as the strip can show, so the " +
                "last chips are empty after every gesture",
            StripLayout.SLOTS, widest
        )
    }
    /**
     * Every chip the strip draws is still a button.
     *
     * [StripLayout.chipsThatFit] counts the row by dividing it **equally**,
     * and the view draws the chips **proportionally to word length**. Those
     * are two models of one row, and for a long time only the second one was
     * the one on screen: the count was decided on the promise that every chip
     * could be 48dp and the drawing then made some of them smaller.
     *
     * Measured over 8,649 strips this engine really produces across all
     * twenty-two languages, on a 393dp phone with no emoji chip and no
     * incognito mark, the narrowest chip ran to a **minimum of 30.2dp** and
     * sat under 48dp on **14.6% of rows** — one in seven, with the worst under
     * two thirds of the size [StripLayout.MIN_CHIP_DP] exists to promise. A
     * chip that small is still a word, and tapping the wrong one puts the
     * wrong word in the message.
     *
     * This asserts the promise over the same shape of data: real suggestion
     * words, real weights, the floor applied. It costs no suggestions — the
     * floor comes out of the surplus, not out of the chip count.
     */
    @Test
    fun `no chip is drawn narrower than a touch target`() {
        // A 393dp phone with nothing optional on the row, which is the
        // widest case and therefore the one with no excuse.
        val freeDp = 393 - (34 + 8 + (StripLayout.SLOTS - 1))
        val fits = StripLayout.chipsThatFit(freeDp, StripLayout.SLOTS)
        val floor = StripLayout.chipFloorDp(freeDp, fits)
        val tooNarrow = ArrayList<String>()
        // The mix that breaks it is *several* long words beside one short,
        // not one. One long word among four short ones still leaves the
        // narrowest at 49.6dp, which is why the first version of this test
        // passed with the floor removed and proved nothing — the same failure
        // as the elision fixture that invented the data it asserted on. Four
        // long and one short is 4/52 of the row: 26.7dp.
        for (long in listOf("Bananenkuchen", "kitaplarımızda", "understanding", "razumijevanje")) {
            for (short in listOf("a", "the", "and", "is")) {
                val words = List(fits - 1) { long } + listOf(short)
                val w = StripLayout.weights(words)
                val surplus = freeDp - fits * floor
                val narrowest = words.indices.minOf { i ->
                    floor + w[i] / w.sum() * surplus
                }
                if (narrowest < StripLayout.MIN_CHIP_DP) {
                    tooNarrow.add("$long + $short -> ${"%.1f".format(narrowest)}dp")
                }
            }
        }
        assertEquals(
            "a chip is drawn under " + StripLayout.MIN_CHIP_DP + "dp: " + tooNarrow,
            emptyList<String>(), tooNarrow
        )
    }

    /**
     * ...and the floor stands down when the row genuinely cannot afford it.
     *
     * [StripLayout.chipsThatFit] cannot always keep the count inside the
     * width, because `keepAtLeast` outranks it: what the space bar is about to
     * commit has to be on the strip even on a narrow row. Giving those chips a
     * hard minimum would run them off the end, so there the old behaviour —
     * share out what there is — is the right one.
     */
    @Test
    fun `the floor stands down on a row that cannot afford it`() {
        assertEquals(0, StripLayout.chipFloorDp(freeDp = 100, chips = 5))
        assertEquals(StripLayout.MIN_CHIP_DP, StripLayout.chipFloorDp(freeDp = 347, chips = 5))
        // Exactly enough is enough.
        assertEquals(
            StripLayout.MIN_CHIP_DP,
            StripLayout.chipFloorDp(freeDp = 5 * StripLayout.MIN_CHIP_DP, chips = 5)
        )
        assertEquals(0, StripLayout.chipFloorDp(freeDp = 5 * StripLayout.MIN_CHIP_DP - 1, chips = 5))
    }


    private companion object {
        /** Languages the fill arm walks: two analytic, two that inflect hard. */
        val FILL_LANGS = listOf("en", "tr", "fi", "de")

        /** Letters typed before the completion list is not interesting any more. */
        const val MAX_PREFIX = 8

        /**
         * Measured 4.96 to 4.99 of five across the four languages.
         *
         * The floor is far below that on purpose. What it exists to catch is a
         * list capped under the strip's width -- the shape of the GLIDE_OFFERED
         * defect this file was written for -- which would land near 3.0, not a
         * change of a hundredth from a dictionary rebuild.
         */
        const val FILL_FLOOR = 4.5
    }
}
