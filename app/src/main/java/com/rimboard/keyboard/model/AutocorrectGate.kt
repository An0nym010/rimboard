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
     * Whether a swipe may be decoded into a word at all.
     *
     * A glide is this file's question in its strongest form. A correction
     * replaces a word the user typed; a glide replaces a shape they drew, with
     * no keystroke in between and nothing on screen to compare it against. So
     * the fields where a correction is refused are fields where a swipe must
     * not be read either — and it was, because the gesture asked only whether
     * gliding was switched on and whether this was a text field.
     *
     * A password field is a text field. [mayCommit]'s own note says what that
     * means: "a password, email or URL field, where words are not prose and a
     * correction is a wrong password". Swiping in one produced a dictionary
     * word and committed it, in the one kind of field where the user cannot
     * read back what they got.
     *
     * @param enabled       the user's glide setting.
     * @param isTextClass   a text field at all, rather than a number pad.
     * @param isPassword    where a decoded word is a wrong password.
     * @param noSuggestions the app asked for no suggestions; a swipe is one.
     * @param isEmailOrUri  an address, where words are not prose.
     */
    fun mayDecodeSwipe(
        enabled: Boolean,
        isTextClass: Boolean,
        isPassword: Boolean,
        noSuggestions: Boolean,
        isEmailOrUri: Boolean
    ): Boolean =
        enabled && isTextClass && !isPassword && !noSuggestions && !isEmailOrUri

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
     * Whether an ambiguous tap may be re-aimed by the language model.
     *
     * The keyboard picks among the letter keys a touch actually lands on by
     * weighing the spatial fit against P(letter | previous letter). That is a
     * silent change to what somebody typed -- a smaller one than an
     * autocorrect, and made without any word being replaced -- so it belongs
     * to the same set of rules as the rest of this file.
     *
     * It was refused in password fields only, and its own note gives the
     * reason: "people type precisely and unusual sequences (no language prior
     * should second-guess them)". That is a description of every field this
     * keyboard already declines to treat as prose. An email local part, a URL,
     * a field whose app asked for no suggestions, and the middle of an address
     * typed into an ordinary message are all runs of letters that do not follow
     * the language's statistics, and a prior that nudges one of them has
     * changed a character with nothing on screen to say so and no word for the
     * user to blame.
     *
     * [fieldTakesProse] is the field's own answer -- a text field that is not a
     * password, not an address, and has not asked for no suggestions.
     * [identifierContext] is the per-word one, for the address typed inside a
     * field that is otherwise prose.
     */
    fun mayArbitrateTap(
        enabled: Boolean,
        fieldTakesProse: Boolean,
        identifierContext: Boolean
    ): Boolean = enabled && fieldTakesProse && !identifierContext

    /**
     * Whether a separator may expand a text shortcut.
     *
     * Everything [mayCommit] asks except the autocorrect preference, and the
     * exception is the whole point. A shortcut is not a guess: the user wrote
     * down that "omw" means "on my way", and "Expand short codes into full
     * phrases" is all the setting for it claims. Turning autocorrect off says
     * do not guess at my words; it does not say forget the phrases I defined.
     *
     * It used to be read off `autocorrectActive`, so one switch governed two
     * features -- and worse, only one end of one of them. The strip offered
     * the expansion as its bold first chip whatever the setting said, while
     * the space bar committed the raw trigger, which is exactly the promise
     * [mayCorrect] exists to keep: "a word this refuses to commit must never
     * be shown as the one that will be."
     *
     * [fieldTakesProse] is the field half of `autocorrectActive` -- a text
     * field that is not a password, not an address, and has not asked for no
     * suggestions. Those still apply: nothing should silently rewrite what
     * somebody typed into a password box, shortcut or not.
     */
    fun mayExpandShortcut(
        fieldTakesProse: Boolean,
        identifierContext: Boolean,
        separator: String
    ): Boolean =
        fieldTakesProse && !identifierContext &&
            !ProseContext.separatorEndsIdentifier(separator)

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
