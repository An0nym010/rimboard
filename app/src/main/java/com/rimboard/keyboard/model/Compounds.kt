package com.rimboard.keyboard.model

/**
 * Words that are two words, written closed.
 *
 * A 200,000-entry frequency list cannot hold German. Compounding is productive
 * there — "Bananenkuchen", "Flugzeugunfall", "Nervenzelle" are ordinary
 * writing, not coinages — so a list built from a corpus holds whichever
 * compounds that corpus happened to contain and underlines the rest. Measured
 * against the shipped German dictionary, **24.4% of the German words the list
 * misses are two words in the list joined together**.
 *
 * This is the same shape of problem [Morphology] solves for Turkish, and it
 * needs a different answer: Turkish builds long words by stacking suffixes on
 * one stem, German by putting two stems together.
 *
 * # Why this is scoped by language and not simply on
 *
 * Because in English the same rule would be a bug. "alot" splits into "a" and
 * "lot", and the right response there is to offer the split — which
 * [com.rimboard.keyboard.engine.Dictionary.splitInto] does — not to accept the
 * word. A language either writes compounds closed or it does not, and that
 * decides which of the two features applies.
 *
 * Measured for every candidate, as the share of missing corpus words this
 * would accept against the share of one-key typos it would newly wave through:
 *
 *     de   24.4% gained   0.5% cost      shipped
 *     nl   21.5%          1.3%           219 missing words in total
 *     da   10.7%          1.6%
 *     fi    8.5%          0.7%
 *     tr    7.3%          2.6%           and Turkish has [Morphology]
 *     en    6.9%          1.6%           where accepting "alot" is the bug
 *     sv, no  negligible
 *
 * German is the only one where the trade is not close: fifty words of real
 * vocabulary for every typo let through. The others are listed so the next
 * person has the numbers rather than the intuition — the Dutch and Danish
 * corpora are small enough that their dictionaries already cover them.
 */
object Compounds {

    /** Whether [lang] writes its compounds without a space. */
    fun writesClosed(lang: String): Boolean = lang == "de"

    /**
     * The shortest either half may be.
     *
     * Four, because the halves have to be words rather than fragments. At
     * three, "ver", "ein" and "aus" — prefixes that are also words — turn
     * ordinary misspellings into compounds; at five the real ones start
     * dropping out ("landtiere" is "land" + "tiere").
     */
    const val MIN_PART = 4

    /**
     * The two known words [wordLower] is made of, or null.
     *
     * [frequency] answers how often the dictionary has seen a word, and zero
     * for one it does not hold. Both halves have to clear [minFrequency], so a
     * compound cannot be built out of corpus noise — the halves are supposed
     * to be words the language actually uses, not strings that happen to
     * appear.
     */
    fun splitOf(
        lang: String,
        wordLower: String,
        minFrequency: Int,
        frequency: (String) -> Int
    ): Pair<String, String>? {
        if (!writesClosed(lang)) return null
        if (wordLower.length < MIN_PART * 2) return null
        for (i in MIN_PART..wordLower.length - MIN_PART) {
            val head = wordLower.substring(0, i)
            if (frequency(head) < minFrequency) continue
            val tail = wordLower.substring(i)
            if (frequency(tail) >= minFrequency) return head to tail
            // The linking -s-, which is the commonest German joint and belongs
            // to neither half: Arbeit+s+platz, Liebling+s+lied. Only tried
            // after the plain split, so "haus" + "schuh" is never read as
            // "hau" + "s" + "schuh".
            if (tail.length > MIN_PART && tail[0] == 's') {
                val rest = tail.substring(1)
                if (frequency(rest) >= minFrequency) return head to rest
            }
        }
        return null
    }
}
