package com.rimboard.keyboard.model

/**
 * The two characters that are one apostrophe, and which of them the data uses.
 *
 * Text on a phone is written with U+0027 `'` and U+2019 `’` interchangeably.
 * Both arrive constantly: iOS turns the first into the second by default, most
 * web text uses the second, this keyboard's own long-press on the apostrophe
 * key offers it ([Layouts.symbols]), and the spell checker is asked about text
 * the user never typed at all.
 *
 * **The shipped data uses U+0027 and only U+0027.** Every file under
 * `assets` was built from a corpus whose tokeniser wrote that one, and the
 * word lists alone hold **9,536 entries containing it** — 5,168 English,
 * 3,198 Italian, 1,170 French — against not one containing U+2019 in any of
 * the 22 languages.
 *
 * So a word carrying the curly mark is a key that no list has, and asking for
 * it is not a near miss but a certain miss. [asWritten] puts a query into the
 * form the data is written in, which is the same kind of step as lower-casing
 * it and belongs in the same place.
 */
object Apostrophe {

    /** The mark the shipped data is written with. */
    const val LIST_MARK = '\''

    /** The mark most other software produces. */
    const val CURLY = '’'

    /** Whether [c] is an apostrophe, written either way. */
    fun isMark(c: Char): Boolean = c == LIST_MARK || c == CURLY

    /**
     * [s] with every apostrophe written the way the data writes it.
     *
     * Returns the same instance when there is nothing to change, which is
     * almost always: this sits on the lookup path, and the scan it costs is a
     * few characters against a binary search over three hundred thousand words.
     */
    fun asWritten(s: String): String =
        if (s.indexOf(CURLY) < 0) s else s.replace(CURLY, LIST_MARK)
}
