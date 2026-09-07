package com.rimboard.keyboard.model

/**
 * The capitals a word is written with, learned from the person writing it.
 *
 * Every word list this keyboard ships is entirely lower case -- the English
 * one holds 0 capitals in 298,946 entries -- and every key in [UserData] is
 * lower case by explicit contract, because a store that filed "Anthropic" and
 * "anthropic" as two words would learn each of them half as fast. So the
 * keyboard has never had anywhere to put the one thing it watches you do to a
 * word over and over: capitalise it.
 *
 * The consequence is small and constant. Type "anthr" and the strip offers
 * "anthropic"; tap it and the capital you have written forty times is gone.
 * Finish a sentence and the next-word chip offers "sarah", because a learned
 * n-gram carries no case at all -- [SuggestionEngine.predictions] says so in
 * its own comment, and until now the only answer available was the curated
 * model's, which knows how *the language* capitalises a word and nothing about
 * how *you* do. Gboard has remembered this for years.
 *
 * ## What counts as evidence
 *
 * Only a word the user typed themselves, mid-sentence, with caps lock off:
 *
 *  - **Mid-sentence.** A capital at the start of a sentence is the keyboard's
 *    own doing -- auto-capitalisation put it there -- and even when the user
 *    pressed shift for it, it says something about the position and nothing
 *    about the word. Counting those would teach the keyboard that "The",
 *    "But" and "So" are proper nouns, which is every second sentence anyone
 *    writes.
 *  - **Caps lock off.** A stretch of capitals is a tone of voice, not a
 *    spelling. The cost is that acronyms typed the only way a phone can type
 *    them -- caps lock on -- are not learned; the alternative is that one
 *    shouted message recasts a whole vocabulary.
 *  - **Typed, not offered.** The commit paths that put a word in the field
 *    because the *keyboard* chose it -- a tapped chip, a swipe, a correction
 *    -- record nothing here. Their case came from this rule in the first
 *    place, so counting it would be the store reading its own output back as
 *    evidence, which is the fault that cost [PostCorrection] and
 *    [ContextError] their whole purpose one commit apart.
 *
 * ## How a form wins
 *
 * By majority vote, kept in one string and one counter, because this is stored
 * per learned word and there are up to twelve thousand of those. [vote] is
 * Boyer-Moore: the held form gains on agreement, loses on disagreement, and is
 * replaced when its lead reaches zero. That is exactly a majority test -- a
 * form that is written more often than every other spelling combined always
 * ends up holding the slot -- for two fields instead of a map per word.
 *
 * The lead has to reach [MIN_LEAD] before the form is offered, which is the
 * same bar [UserData.isKnown] holds a learned word to and is here for the same
 * reason: one sighting is a slip, two is a habit.
 *
 * ## Where it may speak, and where it may not
 *
 * [cased] refuses any word that already carries a capital, which is what makes
 * this safe to compose with everything else. [WordCase.match] has already
 * copied the case of what was typed -- "TEH" gives "THE", "Anthr" gives
 * "Anthropic" -- and the curated prediction model has already spelled German
 * nouns with their capital. This speaks only in the gap those two leave: a
 * candidate that is still entirely lower case, which is to say the one case
 * where nothing else had an opinion.
 *
 * So the rule can only ever *add* a capital, never remove one. A user who
 * writes "dank" in lower case does not get the curated "Dank" turned back
 * again; they simply get no opinion from here. That asymmetry is deliberate:
 * being wrong in the adding direction shows a capital the user can hold shift
 * to undo, and being wrong in the removing direction silently overrules a
 * language's own spelling.
 */
object PersonalCase {

    /**
     * The lead a form needs before it is offered.
     *
     * Two, matching [com.rimboard.keyboard.engine.UserData.isKnown]. Note this
     * is a *lead* and not a count: two means the form has been written twice
     * more often than every other spelling of the word put together, so a name
     * typed twice qualifies at once while a word the user writes both ways
     * never does.
     */
    const val MIN_LEAD = 2

    /**
     * Whether this commit says anything about how [typed] is spelled.
     *
     * @param sentenceInitial whether this word opened a sentence, in which
     *        case its first letter is the keyboard's opinion rather than the
     *        user's.
     * @param capsLock whether the shift key was latched.
     */
    fun counts(typed: String, sentenceInitial: Boolean, capsLock: Boolean): Boolean =
        typed.length >= 2 && !sentenceInitial && !capsLock

    /**
     * One vote for [form], against the currently held [winner] and its [lead].
     *
     * A lead of zero means nothing is held, whatever string is in [winner], so
     * an empty store and a store whose winner has just been outvoted take the
     * same branch.
     */
    fun vote(winner: String, lead: Int, form: String): Pair<String, Int> = when {
        lead <= 0 -> form to 1
        form == winner -> winner to lead + 1
        else -> winner to lead - 1
    }

    /**
     * The form to offer for [key], or null for no opinion.
     *
     * [winner] is checked against [key] rather than trusted, because these two
     * fields are read back from a file on disk and a mismatched pair would put
     * an unrelated word on the strip under another word's key. Case-insensitive
     * equality is the check the pairing is supposed to satisfy.
     */
    fun formFor(key: String, winner: String, lead: Int): String? = when {
        lead < MIN_LEAD -> null
        winner == key -> null
        !winner.equals(key, ignoreCase = true) -> null
        else -> winner
    }

    /**
     * [word] in the form the user writes it, if this has an opinion.
     *
     * Refuses anything already carrying a capital; see the class doc. [formFor]
     * is the store, keyed by the word itself -- which is a valid key precisely
     * because the word reaching this point is entirely lower case.
     */
    fun cased(word: String, formFor: (String) -> String?): String {
        if (word.length < 2) return word
        for (c in word) if (c.isUpperCase()) return word
        return formFor(word) ?: word
    }
}
