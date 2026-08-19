package com.rimboard.keyboard.model

import java.util.Locale

/**
 * The words in your address book, as words.
 *
 * A contact's display name is not a word: it is "Anne-Marie O'Brien", or
 * "Mum", or "Ahmet Yılmaz (work)", or a phone number someone never named. What
 * the spell checker can use is the parts of it that could plausibly appear in a
 * sentence, so the name of somebody you write to every day stops coming back
 * underlined.
 *
 * Pure, and separate from the reading, because the reading needs a permission
 * and a ContentResolver and the deciding needs neither. Everything here is a
 * judgement about strings and every one of them is checkable.
 *
 * Folded with [Locale.ROOT] on both sides of the comparison rather than with
 * the language being typed. That is not a shortcut: the two sides have to agree
 * with each other, and a Turkish dotted capital folds one way under `tr` and
 * another under `ROOT`. Using the same rule for the stored name and the typed
 * word is what makes them meet.
 */
object ContactNames {

    /**
     * How many name parts are kept.
     *
     * An address book can hold thousands, and this set is consulted for every
     * word that is not in the dictionary. The cap is on the parts rather than
     * the contacts: one entry can contribute three or four.
     */
    const val MAX_NAMES = 4000

    /**
     * Below this a name part is not worth accepting.
     *
     * Two is the shortest thing that is plausibly a name. Anything shorter is
     * an initial, and accepting "a" or "j" as a word would quietly switch off
     * spell checking for two of the commonest typos there are.
     */
    private const val MIN_LENGTH = 2

    private fun isNameChar(c: Char) = c.isLetter() || c == '\'' || c == '\u2019'

    /**
     * The usable name parts of [displayNames], folded and de-duplicated.
     *
     * Split on anything that is not a letter, so "Anne-Marie" gives up both
     * halves and matches either written on its own, and "Ahmet Yılmaz (work)"
     * does not contribute "work" as a name... which it does, and that is the
     * honest limit of this: a bracketed note in a contact's name becomes an
     * accepted word. The alternative is guessing which parts of a display name
     * are a name, and being wrong about somebody's actual name is worse than
     * accepting one extra ordinary word.
     *
     * Anything holding a digit is dropped whole rather than split, so a contact
     * saved as a phone number contributes nothing instead of contributing the
     * letters around the digits.
     */
    fun of(displayNames: Sequence<String>, limit: Int = MAX_NAMES): Set<String> {
        val out = LinkedHashSet<String>()
        for (raw in displayNames) {
            if (out.size >= limit) break
            if (raw.any { it.isDigit() }) continue
            var i = 0
            while (i < raw.length && out.size < limit) {
                if (!isNameChar(raw[i])) {
                    i++
                    continue
                }
                var end = i
                while (end < raw.length && isNameChar(raw[end])) end++
                var s = i
                var e = end
                while (s < e && !raw[s].isLetter()) s++
                while (e > s && !raw[e - 1].isLetter()) e--
                if (e - s >= MIN_LENGTH) out.add(raw.substring(s, e).lowercase(Locale.ROOT))
                i = end
            }
        }
        return out
    }

    /** Whether [word] is one of [names], folded the same way they were. */
    fun contains(names: Set<String>, word: String): Boolean =
        names.isNotEmpty() && word.lowercase(Locale.ROOT) in names
}
