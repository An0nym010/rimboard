package com.rimboard.keyboard.model

import java.util.Locale

/**
 * The case a replacement takes from the word it replaces.
 *
 * Every path that swaps one word for another in the field runs what the user
 * typed past this: corrections, splits, contractions, accented forms, and the
 * strip's own chips. "Dont" becomes "Don't" and "TEH" becomes "THE", because
 * the keyboard replacing a word is not an invitation to restyle it.
 *
 * It lived as a private method of `SuggestionEngine`, which is why the one
 * replacement that happens outside the engine -- a text shortcut, looked up by
 * the service -- was committed verbatim. At the start of a sentence with
 * auto-capitalisation on, that is plainly visible: the keyboard capitalises
 * the trigger as it is typed, "Omw", and then commits "on my way" in
 * lower-case, where "Teh" in the same position commits "The".
 *
 * The first character is only ever *raised*, never lowered, and only when it
 * is a letter that is currently lower-case. An expansion beginning with a
 * digit or an already-capital -- a street number, a name -- comes back
 * untouched.
 */
object WordCase {

    /**
     * [candidate] cased to match [typed].
     *
     * All capitals only when [typed] is more than one character, so the "I" of
     * an English sentence, or any single auto-capitalised letter, does not
     * shout its replacement.
     */
    /**
     * The case for a word that replaces nothing.
     *
     * A next-word prediction is offered before a single letter of it has been
     * typed, so [match] has nothing to take its case from. The only statement
     * about case at that moment is the shift key, and the typing path already
     * reads all of it: `applyShift` capitalises a typed letter whenever the
     * state is anything but NONE.
     *
     * The strip read one of the four states. It capitalised its chips under
     * AUTO and ignored MANUAL and CAPSLOCK, so pressing shift for a name left
     * the predictions lower-case and tapping one committed the capital away.
     */
    fun forShift(
        candidate: String,
        capsLock: Boolean,
        shifted: Boolean,
        locale: Locale
    ): String = when {
        capsLock -> candidate.uppercase(locale)
        shifted -> candidate.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(locale) else it.toString()
        }
        else -> candidate
    }

    /**
     * The English first-person pronoun, capitalised on the way out.
     *
     * The one word in English that is always a capital, and the keyboard has
     * to supply it because nobody presses shift for a single letter mid
     * sentence. Gboard does it; so does every phone keyboard worth using.
     *
     * [lang] is the language being **typed**, not the one selected, and the
     * difference is the whole reason this is a function rather than a line at
     * the commit. The commit path asked `currentLangCode()` while everything
     * around it -- the correction, the alternate dictionary, the emoji lookup
     * -- asked the effective language, so the rule was wrong in both
     * directions at once for anyone with two languages enabled:
     *
     *  - Turkish selected, English second, writing English: every other
     *    English behaviour arrived and the pronoun did not.
     *  - English selected, Turkish second, writing Turkish: the pronoun rule
     *    fired on Turkish text, and in Turkish that is not a capitalisation
     *    but a substitution. "i" and "ı" are separate letters with separate
     *    capitals, `İ` and `I`; turning a Turkish "i" into "I" does not shout
     *    the word, it changes which word it is.
     *
     * Nothing changes for the great majority of installs, which have one
     * language enabled and for which the effective language is the selected
     * one by definition.
     */
    fun pronoun(typed: String, lang: String): String =
        if (lang == "en" && typed == "i") "I" else typed

    fun match(typed: String, candidate: String, locale: Locale): String = when {
        typed.length > 1 && typed.all { it.isUpperCase() } -> candidate.uppercase(locale)
        typed.isNotEmpty() && typed.first().isUpperCase() ->
            candidate.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(locale) else it.toString()
            }
        else -> candidate
    }
}
