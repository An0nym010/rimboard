package com.rimboard.keyboard.model

/**
 * Whether the word being typed is part of prose or part of an identifier.
 *
 * Autocorrect is a claim about language, and a URL is not language. Typing
 * "docs.gogle.com/teh" into an ordinary message field used to come out as
 * "docs.gogle.com/the", because the keyboard sees only the letters it is
 * composing — the dots and slashes are separators, committed and forgotten —
 * so by the time it judges "teh" there is nothing left to say it is inside an
 * address. The word looks like prose because everything that made it not prose
 * has already scrolled out of view.
 *
 * The signal is in the field, not in the word. The token the cursor is sitting
 * in still holds the glue, so reading back to the last space answers the
 * question the composing buffer cannot.
 *
 * **This gates committing, never offering.** A correction still appears on the
 * strip and is still one tap away, exactly as with the confidence bar in
 * [com.rimboard.keyboard.engine.Dictionary.autoCommitConfident]. Somebody
 * typing a domain who genuinely wanted the correction can have it; what they
 * cannot have is the keyboard rewriting a link on its own.
 */
object ProseContext {

    /**
     * Characters that make a token an address rather than a word.
     *
     * [SpellCandidacy] refuses to judge a *word* containing `@ / \ : _`, which
     * is the same instinct applied to a different question — there the mark is
     * inside the word, here it is beside it. This set is the wider one because
     * a token can be an identifier by its punctuation without any single word
     * of it looking unusual: "v2/teh" has an ordinary-looking word in it and is
     * not prose.
     *
     * The apostrophe and the hyphen are deliberately absent. "well-knwon" and
     * "dont" are prose with punctuation in them, and refusing to correct those
     * would give up the two commonest corrections there are to make.
     */
    private const val MARKS = "./:@#?&=%~\\_"

    /**
     * The subset that is never ordinary sentence punctuation.
     *
     * [MARKS] is the right set when the question is "does what I have already
     * seen look like an address", because there the full stop is surrounded by
     * context that has not arrived yet. It is the wrong set for a finished
     * sentence, where a full stop is far more often the end of one.
     *
     * Derived from [SentenceContext.ENDERS] rather than written out, because
     * the two lists have to agree and this project has shipped a stale
     * duplicate of a list before. "?" is the one that is easy to miss by hand:
     * it opens a query string and it also ends a question.
     */
    private val UNAMBIGUOUS_MARKS: String =
        MARKS.filter { it !in SentenceContext.ENDERS }

    /** How far back to read. A domain or path prefix is well inside this. */
    const val LOOKBACK = 48

    /**
     * Whether what sits immediately before the word being typed makes this an
     * identifier rather than prose.
     *
     * [textBefore] is the field's text ending at the cursor. Only the run since
     * the last whitespace matters: "see docs.gogle.com/" is an identifier at
     * the cursor even though the sentence around it is prose.
     *
     * A digit counts. Version numbers, IDs and hostnames like "v2.api" are not
     * words, and [com.rimboard.keyboard.engine.SuggestionEngine] already
     * declines to correct a word with a digit *in* it — this extends the same
     * judgement to a word standing next to one.
     */
    fun isIdentifierPrefix(textBefore: CharSequence?): Boolean {
        if (textBefore.isNullOrEmpty()) return false
        var i = textBefore.length
        while (i > 0 && !textBefore[i - 1].isWhitespace()) {
            val c = textBefore[i - 1]
            if (c.isDigit() || c in MARKS) return true
            i--
        }
        return false
    }

    /**
     * Whether the separator just typed says the word before it was an
     * identifier.
     *
     * "@" and "/" and ":" do not end sentences, so a word followed by one is
     * an address, a path or a scheme — "user@" and "docs/" want no correcting.
     *
     * **A full stop is deliberately not in this set**, and that is the known
     * hole rather than an oversight. It is the ordinary end of a sentence, and
     * treating it as evidence would switch autocorrect off for the last word
     * of every sentence anybody writes — a far worse trade than the case it
     * would catch. The consequence is that the *first* label of a bare domain
     * is unprotected: typing "gogle.com" can still have "gogle" corrected
     * before any dot has been seen, while "com" is safe because the dot is
     * behind it by then. Closing that needs lookahead the keyboard does not
     * have at the moment it must decide.
     */
    fun separatorEndsIdentifier(separator: String): Boolean =
        separator.length == 1 && separator[0] in "@/:"

    /**
     * Whether the word at [start], [end) sits inside an address rather than a
     * sentence.
     *
     * The keyboard's question and the spell checker's are the same question
     * asked at different moments. The keyboard only ever has the text *before*
     * the word, because the word is still being typed; the spell checker is
     * handed the finished sentence and can see both sides. They share this
     * file so the two halves of the app cannot come to disagree about what an
     * address looks like — the keyboard declining to autocorrect inside a URL
     * while the spell checker went on underlining the same words is exactly
     * the split this is here to prevent.
     *
     * A mark counts only when it has a letter or digit on **both** sides
     * within the whitespace-delimited run, and only when it is one of
     * [UNAMBIGUOUS_MARKS] — which excludes the full stop.
     *
     * The full stop is left out because "a.b" and "gogle.com" are the same
     * shape, and so is "end.Begin". A dot between two letters is either an
     * address or a missing space, and nothing here can tell which. The project
     * has already decided that question the other way: `SpellTokens` treats a
     * full stop between two words as a sentence boundary, deliberately and
     * with a test naming it, so that a typo after one is still caught. Reading
     * it as an address instead would stop the spell checker judging either
     * word, and losing a real typo is the worse of the two mistakes.
     *
     * That leaves the known hole, and it is the same one
     * [separatorEndsIdentifier] has from the other side: a bare two-label
     * domain written with no scheme and no path — "gogle.com" — is not
     * recognised. Anything with a slash, an at-sign, a colon or a query in it
     * is, which is most of what people paste.
     */
    /**
     * Whether a mark just typed at the end of [textBefore] is the end of a
     * sentence, or part of a token that inserting a space would break.
     *
     * "Auto-space after punctuation" saves the space keystroke after a full
     * stop, and it had no idea what the full stop belonged to. Typing
     * "example.com" on the device produced **"example. com"**, and "e.g."
     * produced **"e. g."** -- an address cut in half and the commonest
     * abbreviation in written English pulled apart, with no chip and no
     * underline to say what happened. The keyboard already declines to
     * *autocorrect* inside an address ([isIdentifierPrefix], read by
     * [AutocorrectGate]) on the grounds that "a URL is not language"; the same
     * keyboard was editing that URL itself one function away.
     *
     * Two things disqualify the mark, and the run they are read from is the
     * one since the last whitespace with its trailing punctuation removed --
     * so "example." asks about "example" and "page.html?" asks about
     * "page.html".
     *
     * 1. **The run is an identifier prefix**: it holds a digit or one of
     *    [MARKS]. That is [isIdentifierPrefix] unchanged, which is the point
     *    -- one definition of "not language" for both halves of the app. It
     *    catches "user@example.", "www.example.", "docs.gogle.com/a.",
     *    "page.html?", "v2." and "192.168.1.".
     * 2. **One distinct letter before a full stop**: "e.", "i.", "U." and
     *    also "www.". A sentence whose last word has one letter in it barely
     *    exists -- nought of the 1165 below, for a token of two letters or
     *    more, and two for a token of one. An abbreviation written that way
     *    is everywhere, and so is the commonest URL prefix there is.
     *    Restricted to the full stop, because the comma after Turkish "o," is
     *    a real sentence comma and there are eight of those in the corpus.
     *
     *    It is the same predicate [com.rimboard.keyboard.model.Elongation]
     *    uses to decide "www" is not "w" held down, for the same reason: a
     *    token of one repeated letter has no word inside it.
     *
     * An empty run -- the mark with whitespace straight behind it -- is
     * neither, and is left to take its space. That is French typography,
     * which writes a narrow no-break space (U+202F) before ? ! ; and : and so
     * leaves the mark standing alone as its own run. An earlier draft of this
     * refused all five of them in the corpus.
     *
     * ## What it costs, measured
     *
     * The population is every place in the 22 prose fixtures (and the six
     * held-out ones) where a mark is followed by a space and a letter -- 1165
     * of them -- because those are exactly the keystrokes this feature exists
     * to save. Suppressing one costs the user the space they were typing
     * anyway; inserting one wrongly costs them a broken address they have to
     * notice first. The two are not the same price, which is why this errs
     * towards leaving text alone.
     *
     * Rule 1 suppresses **1** of the 1165 (0.09%): Croatian "20.", an ordinal
     * date. Rule 2 suppresses **2** (0.17%): Polish "P. Smith" and "P. Brown",
     * the honorific. Three in total, 0.26%, and each of the three is a space
     * the writer had typed for themselves.
     *
     * ## The hole that is left, deliberately
     *
     * A bare two-label domain -- "example.com", no scheme, no path, no "www"
     * -- still takes a space after its *first* dot, because at that moment the
     * text reads "example." and nothing here distinguishes that from the end
     * of a sentence. It is the same hole [separatorEndsIdentifier] names from
     * the other side, and closing it needs lookahead the keyboard does not
     * have. Every later dot in the run is protected by rule 1, and "www." by
     * rule 2, so what is left is a domain whose first label is an ordinary
     * word.
     */
    fun punctuationTakesSpace(textBefore: CharSequence?, marks: String): Boolean {
        if (textBefore.isNullOrEmpty()) return false
        if (textBefore[textBefore.length - 1] !in marks) return false
        var lo = textBefore.length
        while (lo > 0 && !textBefore[lo - 1].isWhitespace()) lo--
        var hi = textBefore.length
        while (hi > lo && textBefore[hi - 1] in marks) hi--
        // A mark standing alone as its own run is prose punctuation, and
        // French says so five times in the corpus: it writes a narrow
        // no-break space (U+202F) before ? ! ; and :, which leaves the mark
        // as its own run. Refusing those cost five real French sentences to
        // protect a leading ".hidden" nobody was typing.
        val core = textBefore.subSequence(lo, hi)
        if (isIdentifierPrefix(core)) return false
        // Case-insensitively, because auto-capitalisation gets there first:
        // at the start of a field "www" is committed as "Www", which has two
        // distinct characters in it and slipped through this on the device.
        if (textBefore[textBefore.length - 1] == '.' && core.isNotEmpty() &&
            core[0].isLetter() && core.all { it.equals(core[0], ignoreCase = true) }
        ) {
            return false
        }
        return true
    }

    fun insideIdentifier(text: CharSequence, start: Int, end: Int): Boolean {
        var lo = start
        while (lo > 0 && !text[lo - 1].isWhitespace()) lo--
        var hi = end
        while (hi < text.length && !text[hi].isWhitespace()) hi++
        for (k in lo until hi) {
            if (text[k] !in UNAMBIGUOUS_MARKS) continue
            if (k > lo && text[k - 1].isLetterOrDigit() &&
                k + 1 < hi && text[k + 1].isLetterOrDigit()
            ) {
                return true
            }
        }
        return false
    }
}
