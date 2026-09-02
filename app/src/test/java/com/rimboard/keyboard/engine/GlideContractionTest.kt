package com.rimboard.keyboard.engine

import com.rimboard.keyboard.model.Contractions
import com.rimboard.keyboard.model.GlidePath
import com.rimboard.keyboard.model.KeyProximity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * The apostrophe the letter layer cannot draw, on the one path that commits.
 *
 * The bundled corpus stripped apostrophes, so "dont" sits in the English list
 * with a real frequency and "don't" is absent entirely. That is the whole
 * reason [Contractions] exists, and every path but one already knows it: the
 * strip's completion loop skips a [Contractions.isAutoBareForm] word outright,
 * and [SuggestionEngine.correctionCandidates] refuses to commit one — so
 * tapping d-o-n-t and pressing space puts **"don't"** in the message.
 *
 * A swipe through the same four keys put **"dont"** in it. And a swipe is the
 * input method that commits on the lift, with no keystroke in between.
 *
 * ## Measured over the forty-four forms in [Contractions.autoForms]
 *
 *     committed by a swipe of their own letters   11 of 44 -> 0
 *     offered as one of the three chips           22 of 44 -> 0
 *
 * The eleven were `dont`, `thats`, `whats`, `wheres`, `theres`, `didnt`,
 * `isnt`, `wouldnt`, `couldnt`, `havent` and `oclock` — which is most of the
 * contractions anybody swipes.
 *
 * ## Substituted, not filtered, and that is the whole design
 *
 * Dropping "dont" from the candidate list makes the same swipe commit
 * **"door"** — the next thing along the path. Filtering is what the other
 * three paths do because they have somewhere else to get the apostrophe from;
 * a swipe has nowhere, because the apostrophe is not on the letter layer and
 * **cannot be swiped**. So the bare form is the only spelling a swipe can
 * express, and turning it into the canonical is the only version of this fix
 * that leaves the user better off than before.
 *
 * ## Only the auto forms
 *
 * [Contractions.Expansion.auto] means the bare spelling is never itself an
 * English word, which is exactly the bar the space bar already holds to. The
 * suggest-only forms are words — "cant" the noun, "wont" meaning accustomed —
 * and a swipe of those still commits what was swiped. Both halves are asserted
 * below, because a fix to one that breaks the other is this seam's failure
 * mode.
 *
 * Nothing else moves: every figure in [GlideAccuracyTest] is identical to the
 * digit with this in and out — all four hands in both languages, both
 * cross-language arms, and the doubled-letter split — because these
 * forty-four words never change which candidate wins, only how it is spelled.
 */
class GlideContractionTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("glidecontraction", "").let { it.delete(); it.mkdirs(); it }
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
        for (kind in listOf("dictionaries", "predictions", "suffixes", "prefixes")) {
            File(assets(), "$kind/$lang.txt").takeIf { it.isFile }?.let {
                files["$kind/$lang.txt"] = it.readText()
            }
        }
        return SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
    }

    /** A deliberate swipe through each letter's key centre on [lang]'s layout. */
    private fun pathOf(word: String, lang: String): GlidePath? {
        val prox = KeyProximity.forLang(lang)
        val pts = ArrayList<Float>()
        var px: Float? = null
        var py: Float? = null
        for (c in word) {
            val x = prox.gridX(c) ?: return null
            val y = prox.gridY(c) ?: return null
            val lx = px
            val ly = py
            if (lx != null && ly != null) {
                for (k in 1..6) {
                    val t = k / 6f
                    pts.add(lx + (x - lx) * t)
                    pts.add(ly + (y - ly) * t)
                }
            } else {
                pts.add(x)
                pts.add(y)
            }
            px = x
            py = y
        }
        return GlidePath.of(pts.toFloatArray(), prox)
    }

    private fun swipe(e: SuggestionEngine, word: String, lang: String, loc: Locale): List<String> {
        val gp = pathOf(word, lang) ?: return emptyList()
        return e.glideFor(gp, lang, loc, personalized = false).map { it.lowercase(Locale.ROOT) }
    }

    @Test
    fun `a swipe never writes a contraction without its apostrophe`() {
        val e = engine("en")
        val en = Locale.ENGLISH
        var committed = 0
        var offered = 0
        val casualties = StringBuilder()
        val forms = Contractions.autoForms("en").sorted()
        for (w in forms) {
            val out = swipe(e, w, "en", en)
            if (out.isEmpty()) continue
            if (out.first() == w) committed++
            if (out.contains(w)) {
                offered++
                if (casualties.length < 500) casualties.append("\n  $w -> $out")
            }
        }
        println("auto forms checked: ${forms.size}; committed $committed, offered $offered")
        assertTrue("the layout could not draw any of these", forms.size >= 40)
        assertEquals(
            "a swipe put an apostrophe-less contraction in the message, which is " +
                "the one spelling the tap path refuses:$casualties",
            0, committed + offered
        )
    }

    /**
     * And the substitution is a substitution: the canonical is what arrives,
     * in the slot the bare form used to hold.
     *
     * This is the half a filter cannot do. Removing "dont" from the candidates
     * leaves "door" first, which is worse than what was there before.
     */
    @Test
    fun `the apostrophe form is what the swipe commits instead`() {
        val e = engine("en")
        val en = Locale.ENGLISH
        val wrong = StringBuilder()
        for ((bare, canonical) in listOf(
            "dont" to "don't", "thats" to "that's", "didnt" to "didn't",
            "isnt" to "isn't", "wouldnt" to "wouldn't", "couldnt" to "couldn't",
            "whats" to "what's", "wheres" to "where's", "havent" to "haven't"
        )) {
            val out = swipe(e, bare, "en", en)
            if (out.firstOrNull() != canonical) {
                wrong.append("\n  $bare -> $out (wanted \"$canonical\" first)")
            }
        }
        assertEquals(
            "a swipe of a contraction's letters no longer commits the contraction, " +
                "which means it was filtered rather than substituted:$wrong",
            "", wrong.toString()
        )
    }

    /**
     * The control, and the line the space bar already draws.
     *
     * "cant" is a noun and "wont" means accustomed, so they are in
     * [Contractions]' suggest-only table and are never restored automatically.
     * A swipe is an automatic commit, so it must not restore them either.
     */
    @Test
    fun `a suggest-only bare form is still what a swipe commits`() {
        val e = engine("en")
        val en = Locale.ENGLISH
        val changed = listOf("cant", "wont", "ill", "shell").filter { w ->
            val out = swipe(e, w, "en", en)
            out.isNotEmpty() && out.first() != w && out.first().contains('\'')
        }
        assertEquals(
            "a swipe restored an apostrophe the space bar refuses to restore, " +
                "on a bare form that is an ordinary English word.",
            emptyList<String>(), changed
        )
    }

    /** A language with no contraction table is not touched by any of this. */
    @Test
    fun `a language with no table is unchanged`() {
        val e = engine("tr")
        val tr = Locale.forLanguageTag("tr")
        val apostrophes = listOf("kitap", "geldim", "yapmak", "onlar").filter { w ->
            swipe(e, w, "tr", tr).any { it.contains('\'') }
        }
        assertEquals(
            "a Turkish swipe grew an apostrophe from the English table.",
            emptyList<String>(), apostrophes
        )
    }
}
