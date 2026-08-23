package com.rimboard.keyboard.model

import java.util.Locale

/**
 * Whether a token is the kind of thing a spell checker should have an opinion
 * about at all.
 *
 * The distinction that matters is between "correctly spelled" and "not my
 * business": claiming the former for a URL would be a lie, and claiming a typo
 * would underline half of every technical message. The API has a way of saying
 * nothing, and for all of these it is the right answer.
 *
 * Pulled out of the session so it can be tested. It was a private method on a
 * class that needs a bound text field to exist, which meant the rule deciding
 * what does and does not get a red underline — the most visible thing this
 * service does — was the one part of it nothing could reach.
 */
object SpellCandidacy {

    /** Two-letter words are too easily "corrected" into something else. */
    const val MIN_LENGTH = 3

    /**
     * Languages where an initial capital says nothing about a word.
     *
     * German capitalises every noun, not just proper ones, so the
     * proper-noun rule below would stop checking most of the words in a German
     * sentence — the opposite of an improvement, and the sort of thing that
     * would have been discovered by a German speaker rather than by a test.
     */
    private val CAPITALS_ARE_ORDINARY = setOf("de")

    /**
     * Whether a capital on [word] is the user saying "this is a name".
     *
     * A capitalised word in the middle of a sentence is overwhelmingly a name —
     * a person, a place, a product — and names are not in any dictionary. The
     * capital is the only evidence available that the word was never meant to
     * be in one, and it is the user's own deliberate keystroke.
     *
     * **Read by both the spell checker and the keyboard**, which is the point
     * of it being a function rather than a line inside [worthChecking]. The
     * two were answering this question differently: the underline declined to
     * judge a name, and autocorrect went ahead and silently replaced it on the
     * space bar. Measured over the real proper nouns in `src/test/fixtures`
     * that the shipped dictionary does not hold, and the capital changed
     * nothing whatever:
     *
     *     typed capitalised   en 11.7% destroyed   tr 15.1%
     *     typed lowercase     en 11.7% destroyed   tr 15.1%
     *
     * "César" was committed as "Cesar", "Noël" as "Noel", "Parijs" as "Paris",
     * "Sundays" as "Sunday". This project has already had four features hold
     * four different opinions about whether something is a word; this is the
     * same fault about whether something is a *name*.
     */
    fun looksLikeName(word: String, sentenceInitial: Boolean, lang: String): Boolean =
        !sentenceInitial && word.isNotEmpty() && word[0].isUpperCase() &&
            lang !in CAPITALS_ARE_ORDINARY

    /**
     * Whether [word] should be judged, given whether it opens its sentence.
     *
     * [sentenceInitial] is the part that needed the sentence-level rewrite to
     * be answerable. Judging names meant every name the user typed came back
     * underlined, offering a "correction" to some real word a letter or two
     * away, in every app on the phone. This service deliberately learns
     * nothing, so it has no way to stop being wrong about a name except to
     * decline the question.
     *
     * The cost is a genuine typo that happens to start with a capital in
     * mid-sentence, which goes unflagged. That is a much rarer event than
     * writing somebody's name.
     */
    fun worthChecking(
        word: String,
        sentenceInitial: Boolean,
        lang: String,
        locale: Locale
    ): Boolean {
        if (word.length < MIN_LENGTH) return false
        // Digits anywhere: version numbers, IDs, "covid19".
        if (word.any { it.isDigit() }) return false
        // Acronyms and constants — NASA, HTTP, MAX_VALUE — are not in any
        // word list and are not misspelled either.
        if (word.length > 1 && word == word.uppercase(locale)) return false
        // A capital inside the word: camelCase, brand names, and the mid-word
        // capitals autocorrect already refuses to touch.
        if (word.drop(1).any { it.isUpperCase() }) return false
        // A name, in every language that reserves capitals for them.
        if (looksLikeName(word, sentenceInitial, lang)) return false
        // Anything with the shape of an address rather than a word.
        if (word.any { it in "@/\\:_" }) return false
        return word.all { it.isLetter() || it == '\'' || it == '’' }
    }
}
