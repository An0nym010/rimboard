package com.rimboard.keyboard.model

/**
 * What a long-press on a letter offers, and in what order.
 *
 * Three layers, most-wanted first:
 *
 *  1. **The digit**, on the top row. It is the single most common reason to
 *     long-press when the number row is off, so it leads regardless of what
 *     else the letter carries.
 *  2. **The active language's own letters**, taken from the per-layout map in
 *     [Layouts]. On a Turkish keyboard `u` should offer `ü` before `ú`, because
 *     one of those is a letter of the language and the other is decoration.
 *  3. **Everything else that letter takes anywhere**, ordered by roughly how
 *     widely the accented form is used across languages — `é` before `ě`, `ö`
 *     before `ő`.
 *
 * Then a symbol, where the letter has an obvious one (`c` → `©`, `r` → `®`).
 * That is what keeps a long-press useful on consonants that take no accent at
 * all, so no key is a dead press.
 *
 * The orderings below are judgement calls about worldwide usage rather than
 * measurements. They are deliberately one table so they can be argued with and
 * changed in one place.
 */
object KeyPopups {

    /**
     * Accented forms per base letter, most widely used first.
     *
     * Ordered by how many speakers meet the form at all, not by how common the
     * letter is: `ü` leads `u` because German, Turkish and Hungarian all use
     * it, while `ů` is essentially Czech alone.
     */
    private val accents = mapOf(
        'a' to "áàâäãåāăą",
        'c' to "çćčĉ",
        'd' to "ďđ",
        'e' to "éèêëēėęě",
        'g' to "ğĝģ",
        'h' to "ĥħ",
        'i' to "íìîïīįı",
        'j' to "ĵ",
        'l' to "łĺľļ",
        'n' to "ñńňņ",
        'o' to "óòôöõøōő",
        'r' to "řŕŗ",
        's' to "şšśßș",
        't' to "ţťț",
        'u' to "üúùûūůű",
        'w' to "ŵ",
        'y' to "ýÿŷ",
        'z' to "žźż"
    )

    /**
     * A symbol for letters that would otherwise have an empty long-press.
     *
     * Mnemonic where one exists, so it is guessable rather than memorised. The
     * bare consonants get punctuation that is otherwise two taps away on the
     * symbol pages; that assignment is the most arbitrary thing in this file
     * and the easiest to change.
     */
    private val symbols = mapOf(
        'b' to "•",
        'c' to "©¢",
        'd' to "°",
        'e' to "€",
        'f' to "ƒ",
        'h' to "⁄",   // fraction slash
        'j' to "¡",
        'k' to "¿",
        'l' to "£",
        'm' to "µ",
        'p' to "¶§",
        'q' to "“",   // opening quote
        'r' to "®",
        's' to "§",
        't' to "™†",
        'v' to "✓",   // check mark
        'w' to "”",   // closing quote
        'x' to "×",
        'y' to "¥",
        'z' to "≈"    // approximately
    )

    /**
     * The popup string for one key.
     *
     * [native] is the layout's own entry for this letter — the language's
     * priority set. Anything in it is hoisted above the general ordering, in
     * the order the language declared it.
     *
     * Deduplicated by code point, so a letter listed both natively and
     * globally appears once, in its earlier (higher-priority) position. Capped
     * because a popup wider than the screen gets scaled down until the cells
     * are too small to aim at, which is worse than offering fewer options.
     */
    fun forLetter(letter: Char, native: String?, digit: String?): String {
        val out = StringBuilder()
        val seen = HashSet<Char>()
        fun add(s: String?) {
            s ?: return
            for (c in s) {
                // The base letter itself is already under the finger.
                if (c == letter) continue
                if (seen.add(c)) out.append(c)
            }
        }
        add(digit)
        add(native)
        add(accents[letter.lowercaseChar()])
        add(symbols[letter.lowercaseChar()])
        return out.take(MAX).toString()
    }

    /** Nine cells is about what fits before they stop being tappable. */
    private const val MAX = 9
}
