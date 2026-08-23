package com.rimboard.keyboard.model

/**
 * Whether the separator may change the word being composed, and how far.
 *
 * **Two questions, not one**, and separating them is the whole point of this
 * file. Committing on a separator can do two quite different things:
 *
 *  - expand a **shortcut**, which is a rule the user wrote down themselves;
 *  - apply a **correction**, which is the keyboard's own guess.
 *
 * They shared a gate, so the first rule added for the guess — the name rule
 * below — silently switched off shortcut expansion for any trigger typed with
 * a capital in mid-sentence. A shortcut is explicit configuration and a
 * heuristic about names has no standing to overrule it.
 *
 * Extracted from `RimBoardService` for the usual reason: it lived in an
 * `InputMethodService`, so the rule deciding whether the keyboard may silently
 * rewrite what somebody typed could not be executed by anything but a thumb.
 */
object AutocorrectGate {

    /**
     * Whether the separator may replace the composed word at all.
     *
     * @param active            the user's autocorrect setting, and a text field.
     * @param identifierContext a password, email or URL field, where words are
     *                          not prose and a "correction" is a wrong password.
     * @param separator         what is about to be typed; some separators end
     *                          an identifier rather than a word.
     */
    fun mayCommit(
        active: Boolean,
        identifierContext: Boolean,
        separator: String
    ): Boolean =
        active && !identifierContext && !ProseContext.separatorEndsIdentifier(separator)

    /**
     * Whether the keyboard may apply a *correction* of its own devising.
     *
     * Read by the suggestion strip as well as by the commit, and it has to be:
     * the bold chip is a promise about what the separator is going to do, so a
     * word this refuses to commit must never be shown as the one that will be.
     *
     * The extra clause over [mayCommit] is the name rule. A capitalised word in
     * mid-sentence is the user saying "this is a name", and names are exactly
     * the words no 200k-word list holds — so the dictionary's silence about one
     * is not evidence of a typo.
     *
     * Autocorrect ignored that keystroke completely. Measured over the real
     * proper nouns in `src/test/fixtures` that the shipped dictionary does not
     * hold, the capital changed nothing whatever:
     *
     *     typed capitalised   en 11.7% destroyed   tr 15.1%
     *     typed lowercase     en 11.7% destroyed   tr 15.1%
     *
     * "César" was committed as "Cesar", "Noël" as "Noel", "Parijs" as "Paris",
     * "Sundays" as "Sunday". Meanwhile [SpellCandidacy] has declined to
     * underline those same words since it was written, on exactly this
     * reasoning — so the two halves of one keyboard held opposite opinions
     * about whether a capital means anything.
     *
     * **Refusing to commit is not refusing to offer.** The correction keeps its
     * chip on the strip, so a capitalised word that really was mistyped is one
     * tap from being fixed rather than silently changed. The cost is a tap; the
     * benefit is somebody's name surviving.
     *
     * Sentence-initial is excluded and that is not a detail: auto-capitalisation
     * puts a capital on the first word of every sentence, so a rule blind to
     * position would switch autocorrect off for a fifth of everything written.
     */
    fun mayCorrect(
        active: Boolean,
        identifierContext: Boolean,
        separator: String,
        composing: String,
        sentenceInitial: Boolean,
        lang: String
    ): Boolean =
        mayCommit(active, identifierContext, separator) &&
            !SpellCandidacy.looksLikeName(composing, sentenceInitial, lang)
}
