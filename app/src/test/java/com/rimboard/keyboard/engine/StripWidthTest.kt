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

    private fun engine(lang: String): SuggestionEngine {
        val files = HashMap<String, String>()
        for (kind in listOf("dictionaries", "predictions")) {
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

}
