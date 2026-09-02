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
}
