package com.rimboard.keyboard.engine

import com.rimboard.keyboard.model.KeyProximity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.ln

/**
 * Covers the ranking logic behind suggestions, autocorrect and glide typing.
 * Feeding the dictionary a handful of words keeps every expectation something
 * you can verify by hand, unlike the 200k-word assets the app ships.
 */
class DictionaryTest {

    private fun dict(vararg entries: String, user: String? = null) = Dictionary(
        entries.joinToString("\n").byteInputStream(),
        user?.byteInputStream(),
        Locale.US
    )

    private val en = KeyProximity.forLang("en")

    @Test
    fun `reads entries and answers membership regardless of file order`() {
        val d = dict("zebra 300", "apple 9000", "ant 5000")
        assertEquals(3, d.size)
        assertTrue(d.contains("apple"))
        assertTrue(d.contains("zebra"))
        assertFalse(d.contains("aardvark"))
    }

    @Test
    fun `a malformed line is skipped instead of derailing the load`() {
        val d = dict("apple 9000", "no-frequency-here", "ant 5000")
        assertEquals(2, d.size)
        assertTrue(d.contains("apple"))
    }

    @Test
    fun `prefix lookup ranks by frequency and honours the limit`() {
        val d = dict("ant 100", "apple 9000", "apply 4000", "banana 8000")
        assertEquals(listOf("apple", "apply"), d.byPrefix("ap", 5).map { it.first })
        assertEquals(listOf("apple"), d.byPrefix("ap", 1).map { it.first })
        assertTrue(d.byPrefix("zz", 5).isEmpty())
    }

    @Test
    fun `prefix lookup is case insensitive`() {
        val d = dict("apple 9000")
        assertEquals(listOf("apple"), d.byPrefix("APP", 5).map { it.first })
    }

    @Test
    fun `corrects a single-character slip`() {
        val d = dict("hello 9000", "world 8000")
        assertEquals("hello", d.corrections("helko", en, 3).firstOrNull())
    }

    @Test
    fun `an adjacent-key slip outranks a distant one at equal frequency`() {
        // "hellp": p neighbours o on qwerty, while a is right across the board,
        // so the proximity model should prefer hello over hella.
        val d = dict("hello 5000", "hella 5000")
        assertEquals("hello", d.corrections("hellp", en, 2).firstOrNull())
    }

    @Test
    fun `a much more frequent word can outrank a slightly closer rare one`() {
        val d = dict("hello 900000", "hellp 250")
        assertEquals("hello", d.corrections("hellu", en, 2).firstOrNull())
    }

    @Test
    fun `rare words are never offered as corrections`() {
        // Below the frequency floor, so it must not be suggested even though it
        // is exactly one edit away.
        val d = dict("helko 10")
        assertTrue(d.corrections("helno", en, 3).isEmpty())
    }

    @Test
    fun `a correctly typed word is not corrected to itself`() {
        val d = dict("hello 9000")
        assertFalse(d.corrections("hello", en, 3).contains("hello"))
    }

    @Test
    fun `distant words are out of correction range`() {
        val d = dict("hello 9000")
        assertTrue(d.corrections("xyzzy", en, 3).isEmpty())
    }

    @Test
    fun `works without proximity data`() {
        val d = dict("hello 9000")
        assertEquals("hello", d.corrections("helko", null, 3).firstOrNull())
    }

    @Test
    fun `the character model prefers transitions it has seen`() {
        val d = dict("the 9000", "then 5000", "there 4000")
        // "th" is everywhere in this corpus; "tz" never occurs.
        assertTrue(d.charLogP('t', 'h') > d.charLogP('t', 'z'))
        // ' ' marks the word-initial position: these all start with t.
        assertTrue(d.charLogP(Dictionary.WORD_START, 't') > d.charLogP(Dictionary.WORD_START, 'q'))
    }

    @Test
    fun `an unseen transition is floored rather than unbounded`() {
        val d = dict("the 9000")
        assertTrue(d.charLogP('q', 'z') >= -6.0)
    }

    /**
     * Pins the exact smoothed probability rather than just its ordering, so the
     * storage behind the model can be reworked without changing what it says.
     * One word of frequency 999 weighs ln(1000), and every transition in it is
     * seen exactly once.
     */
    @Test
    fun `transition probability follows the documented smoothing`() {
        val d = dict("ab 999")
        val w = ln(1000.0)
        val seen = ln((w + 0.5) / (w + 40.0))
        assertEquals(seen, d.charLogP(Dictionary.WORD_START, 'a'), 1e-9)
        assertEquals(seen, d.charLogP('a', 'b'), 1e-9)
        // 'b' follows 'a', never the word start, so it falls to the +0.5 floor.
        assertEquals(ln(0.5 / (w + 40.0)), d.charLogP(Dictionary.WORD_START, 'b'), 1e-9)
    }

    @Test
    fun `a character that never begins a transition is unknown, not zero`() {
        // 'b' ends the only word, so it is never a source character.
        val d = dict("ab 999")
        assertEquals(-6.0, d.charLogP('b', 'a'), 1e-9)
        assertEquals(-6.0, d.charLogP('z', 'a'), 1e-9)
    }

    @Test
    fun `glide collapses doubled letters along the path`() {
        // Swiping h-e-l-o should still find "hello".
        val d = dict("hello 9000", "help 5000")
        assertEquals("hello", d.glideCandidates("helo", 3).firstOrNull()?.first)
    }

    @Test
    fun `glide forgives overshooting the last key`() {
        val d = dict("hello 9000")
        assertEquals("hello", d.glideCandidates("helop", 3).firstOrNull()?.first)
    }

    @Test
    fun `glide will not invent a word from letters that were never crossed`() {
        val d = dict("world 9000")
        assertTrue(d.glideCandidates("helo", 3).isEmpty())
    }

    @Test
    fun `learned words merge in and do not duplicate the bundled list`() {
        val d = dict("apple 9000", user = "apricot 7000\napple 100")
        assertEquals(2, d.size)
        assertTrue(d.contains("apricot"))
        // The bundled frequency wins; the user line must not re-add the word.
        assertEquals(listOf("apple", "apricot"), d.byPrefix("ap", 5).map { it.first })
    }

    @Test
    fun `a missing dictionary degrades to empty rather than throwing`() {
        val d = Dictionary(null, null, Locale.US)
        assertEquals(0, d.size)
        assertFalse(d.contains("apple"))
        assertTrue(d.byPrefix("a", 5).isEmpty())
        assertTrue(d.corrections("helko", en, 3).isEmpty())
        assertTrue(d.glideCandidates("helo", 3).isEmpty())
    }
}
