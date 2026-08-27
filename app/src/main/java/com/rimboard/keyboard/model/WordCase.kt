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

    fun match(typed: String, candidate: String, locale: Locale): String = when {
        typed.length > 1 && typed.all { it.isUpperCase() } -> candidate.uppercase(locale)
        typed.isNotEmpty() && typed.first().isUpperCase() ->
            candidate.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(locale) else it.toString()
            }
        else -> candidate
    }
}
