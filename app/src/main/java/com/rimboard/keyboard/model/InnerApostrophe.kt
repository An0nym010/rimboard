package com.rimboard.keyboard.model

/**
 * A word whose apostrophe is a **letter**, not a join.
 *
 * The third thing an apostrophe can be in this project, and the only one that
 * is not a boundary between two pieces of language:
 *
 *  - [Elision] — two lexical items written as one: `l'homme`, `don't`. Both
 *    halves are dictionary entries with the mark attached to one of them.
 *  - [Morphology.apostropheSuffixed] — a Turkish morpheme boundary: `Paris'e`.
 *    The head is a word, the tail is suffixes.
 *  - **Here** — Ukrainian, where the apostrophe is part of the spelling.
 *    `комп'ютер` is one indivisible word and neither half means anything.
 *
 * ## Why the halves are in the list anyway
 *
 * The shipped lists come from a corpus whose tokeniser split at the
 * apostrophe, so a Ukrainian word that contains one was cut in two and both
 * pieces were counted as words. That is what makes this recoverable: the
 * fragments are *there*, and their frequencies give them away —
 *
 *     комп 366   ютер 100        здоров 432   я 168773
 *     об   2649  єкт   66        з      35420 їзд  44
 *
 * A tail like `ютер`, `єкт` or `їзд` is not a Ukrainian word; it exists in the
 * list only because something was split in front of it. So "both halves are
 * present" is strong evidence of exactly the word we are trying to recognise.
 *
 * Measured against twenty everyday Ukrainian words written with an apostrophe
 * — computer, health, family, name, meat, five, object, congress, prisoner,
 * interview — **nineteen have both halves in the list**. The one that does not
 * is `солов'ї`, whose head the corpus never saw.
 *
 * ## What stops it accepting anything
 *
 * Ukrainian orthography permits the mark in exactly one place: after a
 * consonant and before one of the four iotated vowels я ю є ї. That is a hard
 * rule of the writing system rather than a guess about frequency, and it does
 * most of the filtering here. Both halves must also clear a floor.
 *
 * **It names no language**, on purpose and for the reason [Elision] gives: a
 * language that does not write `'` before я/ю/є/ї matches nothing. Russian
 * shares the alphabet and does not use the apostrophe this way, so it is
 * untouched without having to be listed.
 *
 * The cost of being wrong here is a word that goes un-underlined. The cost of
 * the behaviour it replaces was underlining `комп'ютер` and `здоров'я` — and
 * autocorrect rewriting the first of them to the *Russian* spelling.
 */
object InnerApostrophe {

    /** The only vowels Ukrainian writes after an apostrophe. */
    private const val IOTATED = "яюєї"

    /** Ukrainian vowels; the mark may only follow a consonant. */
    private const val VOWELS = "аеиіоуяюєї"

    /**
     * How often each half must have been seen.
     *
     * Low, and it has to be: a half is a *fragment*, so its count is the
     * number of times the whole word appeared in the corpus, not the number of
     * times a word appeared. Across the twenty words measured the smallest
     * useful halves were `бур` at 34 and `явитися` at 39, so the floor sits
     * just under them. [Dictionary.STEM_MIN_FREQ] of 500 — the floor a
     * compound part or an elided article clears — would reject nearly every
     * one of these, because those are whole words and these are not.
     *
     * The orthographic rule above, not this number, is what does the work.
     */
    const val MIN_HALF_FREQ = 25

    /**
     * Whether [lower] is a word spelled with an apostrophe inside it.
     *
     * [freq] answers how often a half was seen; 0 for absent.
     */
    fun isWord(lower: String, freq: (String) -> Int): Boolean {
        val i = lower.indexOfFirst { it == '\'' || it == '’' }
        if (i <= 0 || i >= lower.length - 1) return false
        // Consonant, then one of the four. Both halves of the rule matter:
        // dropping the consonant test would let the mark sit anywhere.
        if (lower[i + 1] !in IOTATED) return false
        if (lower[i - 1] in VOWELS) return false
        return freq(lower.substring(0, i)) >= MIN_HALF_FREQ &&
            freq(lower.substring(i + 1)) >= MIN_HALF_FREQ
    }
}
