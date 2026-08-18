package com.rimboard.keyboard.model

/**
 * Splitting a sentence into the words a spell checker should judge, keeping
 * where each one starts.
 *
 * The framework will split sentences for you, and that is what the spell
 * checker used to accept: its own `SentenceLevelAdapter` chops the text up and
 * calls `onGetSuggestions` once per word. It works, and it throws away the one
 * thing that makes a correction good. "the stroe" is one edit from both
 * "store" and "stone", and only the preceding word says which the sentence
 * wanted. Handed the words one at a time, with no way to ask what came before,
 * the checker had no choice but to guess by frequency.
 *
 * So the sentence is tokenised here instead, and each word is judged knowing
 * its two predecessors. The offsets are what the framework needs to put the
 * underline in the right place, which is why they are carried rather than
 * recomputed: `indexOf` would find the first "the" in "the the", not the
 * second.
 */
object SpellTokens {

    /**
     * One word, where it sits in the text it came from, and whether it opens a
     * sentence.
     *
     * [startsSentence] carries two things at once and both were wrong without
     * it. A capital only means "this is a name" when it is *not* at a sentence
     * start, so the first word after a full stop was read as a name and its
     * typos went unflagged. And the preceding word is only evidence about this
     * one when the two are in the same sentence: threading context across a
     * full stop is exactly the bug [SentenceContext] was extracted to fix on
     * the keyboard side, and this code had reintroduced it on the other side
     * of the same engine.
     */
    data class Token(val start: Int, val text: String, val startsSentence: Boolean) {
        val length: Int get() = text.length
    }

    private fun isWordChar(c: Char) = c.isLetter() || c == '\'' || c == '\u2019'

    /**
     * The word tokens of [text], in order.
     *
     * A token is a run of letters, which may contain an apostrophe but may not
     * begin or end with one: "don't" is one word, and the quotes around
     * 'quoted' are not part of it. Everything else — digits, punctuation,
     * spaces — is a boundary, and what is left of a token after trimming
     * apostrophes may be nothing at all, in which case there is no token.
     */
    fun of(text: CharSequence): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        // The first word opens a sentence by definition; after that, anything
        // in ENDERS standing between two words opens another.
        var opens = true
        while (i < text.length) {
            if (!isWordChar(text[i])) {
                if (text[i] in SentenceContext.ENDERS) opens = true
                i++
                continue
            }
            var end = i
            while (end < text.length && isWordChar(text[end])) end++
            var s = i
            var e = end
            while (s < e && !text[s].isLetter()) s++
            while (e > s && !text[e - 1].isLetter()) e--
            if (e > s) {
                out.add(Token(s, text.subSequence(s, e).toString(), opens))
                opens = false
            }
            i = end
        }
        return out
    }
}
