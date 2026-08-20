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
}
