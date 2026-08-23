package com.rimboard.keyboard.model

/**
 * A word written with an apostrophe in it, and whether it is one.
 *
 * The keyboard treats an apostrophe as part of a word — it has to, or "don't"
 * would compose as two — and the shipped word lists do not contain the joined
 * forms. They come from subtitles, whose tokeniser split at the apostrophe, so
 * what the lists hold is the two halves. The consequence was visible in every
 * app on the phone: the system spell checker underlined "don't", "it's",
 * "can't" and "we'll" as misspellings and offered *donut*, *its*, *cant* and
 * *well* to replace them. French elides in almost every sentence, so French was
 * underlined almost everywhere.
 *
 * ## The two shapes, which are the same rule
 *
 * The halves are stored the way each language elides, and the apostrophe stays
 * attached to whichever half owns it:
 *
 *  - **English** puts it on the right: `don` + `'t`, `you` + `'re`,
 *    `we` + `'ll`, `doesn` + `'t`. The suffixes are entries in their own right
 *    and among the commonest in the list.
 *  - **French and Italian** put it on the left: `l'` + `homme`, `qu'` + `il`,
 *    `dell'` + `amore`, `c'` + `è`. The elided articles are the entries.
 *
 * So a word is well formed if it splits at an apostrophe into two halves that
 * the dictionary knows, with the apostrophe kept on one side or the other.
 * Nothing here names a language. A list that holds no apostrophe entries — as
 * German's and Turkish's do not — matches nothing and is unaffected, which is a
 * better guarantee than a list of languages somebody has to remember to update.
 *
 * ## What stops it accepting anything
 *
 * Both halves have to be known and to clear the same frequency floor a compound
 * part does. "asdf'qwer" fails on both halves; "don't" passes because `don` and
 * `'t` are ordinary entries. The floor matters more than usual here because one
 * half is often a single letter, and a corpus has a stray entry for nearly
 * every letter.
 */
object Elision {

    /** Both kinds of apostrophe: typed straight, autocorrected to curly. */
    private fun isApostrophe(c: Char) = c == '\'' || c == '’'

    /**
     * The two halves [wordLower] elides into, or null if it is not an elision.
     *
     * The apostrophe is kept on whichever half the dictionary stores it with,
     * so the returned pair is exactly what was looked up rather than a
     * reconstruction of it.
     */
    fun splitOf(
        wordLower: String,
        minFrequency: Int,
        frequency: (String) -> Int
    ): Pair<String, String>? {
        if (wordLower.length < 3) return null
        for (i in 1 until wordLower.length - 1) {
            if (!isApostrophe(wordLower[i])) continue
            val left = wordLower.substring(0, i)
            val right = wordLower.substring(i + 1)
            if (left.isEmpty() || right.isEmpty()) continue
            // The apostrophe belongs to the right half: don + 't.
            val suffix = wordLower.substring(i)
            if (frequency(left) >= minFrequency && frequency(suffix) >= minFrequency) {
                return left to suffix
            }
            // The apostrophe belongs to the left half: l' + homme.
            val prefix = wordLower.substring(0, i + 1)
            if (frequency(prefix) >= minFrequency && frequency(right) >= minFrequency) {
                return prefix to right
            }
        }
        return null
    }
}
