package com.rimboard.keyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Reading the next-word context back from the text before the cursor.
 *
 * The version this replaces decided whether the cursor sat at a sentence start
 * and then took the preceding word by scanning straight across the boundary it
 * had just found. Two correct lines, four apart, contradicting each other — and
 * nothing in the suite touched either of them.
 */
class SentenceContextTest {

    private val en = Locale.ENGLISH

    @Test
    fun `after a full stop there is no preceding word`() {
        // The bug, exactly. This used to answer "hello", so the openers that
        // the separator had just put on the strip were immediately replaced by
        // continuations of "hello" — visible as a flicker — and the next word
        // committed was learned as following it, across the full stop.
        val c = SentenceContext.from("Hello. ", en)
        assertTrue(c.atSentenceStart)
        assertEquals("", c.prevWord)
        assertEquals("", c.prevWord2)
    }

    @Test
    fun `mid sentence the previous two words are the context`() {
        val c = SentenceContext.from("I would like to", en)
        assertFalse(c.atSentenceStart)
        assertEquals("to", c.prevWord)
        assertEquals("like", c.prevWord2)
    }

    @Test
    fun `the second word back does not reach into the previous sentence`() {
        // One word into a new sentence: the trigram context must not pair the
        // new word with the last word of the sentence before it.
        val c = SentenceContext.from("Hello there. How ", en)
        assertFalse(c.atSentenceStart)
        assertEquals("how", c.prevWord)
        assertEquals("", c.prevWord2)
    }

    @Test
    fun `every sentence ender counts, including a newline`() {
        for (end in listOf(".", "!", "?", "\n")) {
            val c = SentenceContext.from("Done$end", en)
            assertTrue("'$end' did not end the sentence", c.atSentenceStart)
            assertEquals("", c.prevWord)
        }
    }

    @Test
    fun `trailing spaces do not hide the sentence end but a newline is one`() {
        assertTrue(SentenceContext.from("Done.   ", en).atSentenceStart)
        assertTrue(SentenceContext.from("Done.\t", en).atSentenceStart)
        // Trimming everything would have eaten the newline being tested for.
        assertTrue(SentenceContext.from("Done\n", en).atSentenceStart)
    }

    @Test
    fun `an empty field is a sentence start with no context`() {
        val c = SentenceContext.from("", en)
        assertTrue(c.atSentenceStart)
        assertEquals("", c.prevWord)
        assertEquals("", c.prevWord2)
    }

    @Test
    fun `a comma ends a word but not a sentence`() {
        val c = SentenceContext.from("Well, ", en)
        assertFalse(c.atSentenceStart)
        assertEquals("well", c.prevWord)
    }

    @Test
    fun `words are folded in the given language`() {
        // Turkish dotless I: folding with the wrong locale gives a key the
        // typing path can never produce, so the prediction never matches.
        val tr = Locale("tr", "TR")
        assertEquals("ışık", SentenceContext.from("IŞIK", tr).prevWord)
        assertEquals("işık", SentenceContext.from("İŞIK", tr).prevWord)
    }

    @Test
    fun `an apostrophe stays inside the word`() {
        assertEquals("don't", SentenceContext.from("I don't", en).prevWord)
    }

    /**
     * The curly apostrophe too — and read back under the straight one.
     *
     * [SentenceContext.insideWord] has always counted U+2019 a word character;
     * the scan that produces the context words did not. The two ran the same
     * text through different rules, and only the second decides what a
     * prediction is keyed on, so in text written the way most software writes
     * it — iOS smart punctuation, most of the web — "I don’t " gave `don` and
     * `t`: a fragment, predicted from confidently, and filed in the learned
     * n-grams under the same fragment. **13.1% of the positions in the French
     * prose fixture and 4.9% of the English ones.**
     *
     * The mark is then written the way the data writes it, which is the second
     * half of the same rule: without that the store would hold "don’t" from
     * text read back and "don't" from text typed here, two rows for one word
     * with neither carrying the other's count. See [Apostrophe].
     */
    @Test
    fun `the two apostrophes give one context`() {
        val curly = SentenceContext.from("I don" + Apostrophe.CURLY + "t", en)
        assertEquals("don't", curly.prevWord)
        assertEquals("i", curly.prevWord2)
        // The whole claim, on the sentences this actually bites in.
        for (text in listOf(
            "I don't know what",
            "she said it's a",
            "l'homme qui a",
            "si j'ai besoin",
            "non c'è niente"
        )) {
            assertEquals(
                text,
                SentenceContext.from(text, en),
                SentenceContext.from(text.replace('\'', Apostrophe.CURLY), en)
            )
        }
    }

    /**
     * The invariant the bug broke: one rule about what a word character is.
     *
     * Two functions in this object read the same text, and a character either
     * belongs to a word in both or in neither. Asserted rather than assumed,
     * because the disagreement they had was invisible — each was individually
     * reasonable, and nothing in either one mentioned the other.
     */
    @Test
    fun `insideWord and the context scan agree about word characters`() {
        val disagree = ArrayList<Char>()
        for (c in listOf('a', 'Z', 'é', 'ß', 'я', '5', '\'', Apostrophe.CURLY,
                         ' ', '-', '.', ',', '/', '_', '@')) {
            // "a<c>a" is one word exactly when <c> is a word character. The
            // expectation goes through the same normalisation the scan does,
            // so this asks about word boundaries and not about the mark.
            val whole = Apostrophe.asWritten(("a" + c + "a").lowercase(en))
            val oneWord = SentenceContext.from("x a" + c + "a", en).prevWord == whole
            val inside = SentenceContext.insideWord("a" + c, "a")
            if (oneWord != inside) disagree.add(c)
        }
        assertEquals(emptyList<Char>(), disagree)
    }

    /**
     * A cursor inside a word is not a place where a next word is being chosen.
     *
     * `refreshContextFromCursor` runs when there is **no** composing text,
     * which is precisely the state a cursor move leaves behind — so the note
     * that used to stand there, that the composing branch handles the mid-word
     * case, was describing a branch that cannot be reached from it. What
     * happened instead was that the half-word before the cursor became the
     * prediction key: tapping into "wor|ld" predicted what follows "wor".
     *
     * Where the fragment is itself a word it did so confidently, off the wrong
     * one — "work|ing" predicted after "work", "cat|s" after "cat" — and the
     * chips are tappable, so the offer was to insert a word into the middle of
     * another.
     */
    @Test
    fun `a cursor inside a word has no next-word context`() {
        val ctx = SentenceContext.from(
            "I went to work", en,
            insideWord = SentenceContext.insideWord("I went to work", "ing today")
        )
        assertEquals("", ctx.prevWord)
        assertEquals("", ctx.prevWord2)
        assertFalse(ctx.atSentenceStart)
    }

    /**
     * And the case that makes looking *forwards* necessary: with the cursor
     * between a space and the next word, the character after it is a letter
     * and the position is an ordinary word boundary. A backwards-only test
     * would have been right about "wor|ld" and wrong about every space.
     */
    @Test
    fun `a cursor at a word boundary keeps its context`() {
        assertFalse(SentenceContext.insideWord("I went to ", "work today"))
        val ctx = SentenceContext.from("I went to ", en, insideWord = false)
        assertEquals("to", ctx.prevWord)
        assertEquals("went", ctx.prevWord2)
    }

    @Test
    fun `the end of the text is not inside a word`() {
        assertFalse(SentenceContext.insideWord("I went to work", ""))
        assertTrue(SentenceContext.insideWord("I went to work", "ing"))
    }

    @Test
    fun `punctuation on either side is a boundary`() {
        assertFalse(SentenceContext.insideWord("stop", ". Next"))
        assertFalse(SentenceContext.insideWord("(", "word"))
        // An apostrophe is part of a word, so the middle of "don't" is inside.
        assertTrue(SentenceContext.insideWord("don", "'t"))
        assertTrue(SentenceContext.insideWord("don'", "t"))
    }

    /** The service has to look forwards, or none of the above can be true. */
    @Test
    fun `the service reads the character after the cursor`() {
        val svc = listOf(File("src/main/java"), File("app/src/main/java"))
            .first { it.isDirectory }
            .resolve("com/rimboard/keyboard/RimBoardService.kt").readText()
        val start = svc.indexOf("private fun refreshContextFromCursor(")
        assertTrue("refreshContextFromCursor is gone; this scan needs rewriting", start >= 0)
        val end = Regex(String(charArrayOf('\n')) + "    (private )?fun ")
            .find(svc, start + 10)?.range?.first ?: svc.length
        val body = svc.substring(start, end)
        assertTrue(
            "refreshContextFromCursor no longer reads what is after the cursor, " +
                "so a cursor inside a word is indistinguishable from one at a boundary",
            body.contains("getTextAfterCursor(")
        )
        assertTrue(
            "the mid-word answer is no longer passed to SentenceContext",
            body.contains("insideWord =")
        )
    }
}
