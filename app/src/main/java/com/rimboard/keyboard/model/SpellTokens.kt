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

    /**
     * Digits are part of a word here, though they can never be part of a
     * *spelling*.
     *
     * [SpellCandidacy] declines anything containing a digit — version
     * numbers, identifiers, "covid19", which its comment names — and it can
     * only decline what it is shown whole. Splitting on digits handed it
     * "covid" instead, which is not in the dictionary and so came back
     * underlined with corrections offered for it. The framework's own splitter
     * kept such runs together, so this was a regression introduced by taking
     * tokenisation over, and invisible from here: the rule that should have
     * caught it was still there, still correct, and no longer being asked.
     */
    private fun isWordChar(c: Char) = c.isLetter() || c.isDigit() || c == '\'' || c == '\u2019'

    /**
     * The word after [index], or empty if there is none in the same sentence.
     *
     * The same rule as the word before it, applied in the other direction: a
     * word on the far side of a full stop is not evidence about this one.
     * Without the boundary check, "He left. Store was shut" would hand "left"
     * the word "Store" as its follower, which is the mirror of the bug the
     * sentence markers were added to fix.
     */
    fun followerOf(tokens: List<Token>, index: Int): String {
        val next = tokens.getOrNull(index + 1) ?: return ""
        if (next.startsSentence) return ""
        // Never the last token. It is the one under the cursor, so it is a
        // word in progress rather than a word: ranking "stroe" against "wa"
        // asks what usually follows a fragment, which is nothing, and the
        // answer changes on every keypress.
        //
        // That second part is what makes this a bug rather than a nicety. The
        // follower is part of the verdict cache key, so a follower that grows
        // by a letter at a time is a fresh key at a time — and the whole
        // point of that cache was to stop re-running a full dictionary scan
        // for a misspelled word while the user carries on typing past it.
        // Right-context ranking had quietly undone the memoising it was built
        // on, two commits after it was built.
        //
        // Costs one word of context at the end of a sentence the user has
        // finished with, and buys back a bounded number of scans: a word is
        // now judged once with no follower and at most once more when its
        // follower settles.
        if (index + 1 == tokens.size - 1) return ""
        return next.text
    }

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
            // Trims the apostrophes off the ends and nothing else. Written as
            // "not a letter" when a letter and an apostrophe were the only two
            // things a token could contain; now that a digit can be in one too,
            // that spelling quietly ate the digits — "covid19" trimmed back
            // to "covid", which is the exact fault admitting digits was meant
            // to fix, one line further down.
            while (s < e && !text[s].isLetterOrDigit()) s++
            while (e > s && !text[e - 1].isLetterOrDigit()) e--
            if (e > s) {
                // A word inside a URL, an email or a path is not a word this
                // service has an opinion about. It is dropped rather than
                // judged-and-accepted, because the API's way of saying nothing
                // is to return nothing — see [SpellCandidacy], which refuses
                // the same class of token on the marks it can see *inside* a
                // word. This catches the ones where the marks are beside it.
                //
                // opens is still consumed: a link is content, so the word after
                // one is mid-sentence and must not get the lenient reading of a
                // capital that a sentence opener gets.
                if (!ProseContext.insideIdentifier(text, s, e)) {
                    out.add(Token(s, text.subSequence(s, e).toString(), opens))
                }
                opens = false
            }
            i = end
        }
        return out
    }
}
