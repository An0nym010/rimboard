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
 *
 * # One dimension that table does not have
 *
 * Every row is a *share of the words a language is missing*, which says nothing
 * about how many it is missing or how much they cost to type. Measured
 * 2026-09-05, out-of-dictionary tokens as a share of all corpus tokens, and
 * their mean length against the words the dictionary does hold:
 *
 *     fi  4.5% of tokens   12.5 letters against 5.8
 *     tr  4.1%             10.6 against 5.7
 *     hr  1.6%             en 0.2%
 *
 * **Finnish is missing the most and the longest.** Its 8.5% row is a share of a
 * base several times larger than Danish's or Dutch's, whose dictionaries the
 * note above says already cover them — which is true, and is exactly why their
 * rows are not the comparison Finnish should be read against. Against German,
 * Finnish still loses on the trade, and that part of the decision stands.
 *
 * What the reasoning never weighed is that 8.5% of Finnish's missing words is
 * more absolute vocabulary, in longer words, than 21.5% of Dutch's. Whether it
 * clears the bar is a keystroke question this table cannot answer, because it
 * is not denominated in keystrokes. [Morphology.isAgglutinative] carries the
 * other half of the same argument: of everything Finnish is missing, a counted
 * ending reaches a sixth and a compound split reaches a third.
 *
 * Not reopened here. The cost column is what governs, 0.7% of one-key typos
 * newly accepted is a real cost, and nothing above measures what enabling this
 * would do to Finnish keystroke savings. That measurement is the price of
 * reopening it, and it is worth someone paying.
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

    /**
     * The compounds [prefixLower] could still be the beginning of.
     *
     * [splitOf] answers whether a finished word *is* a compound, and that is
     * the only question this file was ever asked. The other one -- what the
     * word being typed is going to be -- was never put to it, and it is the
     * one the user is waiting on: a compound the list does not hold has no
     * completion at all, so every letter of it is typed by hand while the
     * strip offers three words that are not it.
     *
     * The same shape as [TurkishMorph.completionsFor] and for the same reason.
     * There a stem takes endings; here two words are written closed. Both are
     * productive, so in both the form being typed is usually not in the list,
     * and in both the list holds everything needed to build it.
     *
     * ## Measured
     *
     * German, cut at 40,000 entries so the words past the cut stand in for the
     * ones past the end of the shipped 200,000. Of those, 29,591 are two
     * listed words joined -- 24% of what the list misses, which is the figure
     * this file's own note opens with:
     *
     *     letters typed        12.10 -> 9.78
     *     never offered at all  100% -> 11.6%
     *
     * And on ordinary German prose, where the list does hold the word,
     * keystrokes saved are **47.75% either way** -- unchanged to the digit.
     * That is not luck: it is what anchoring the joins below the weakest
     * attested completion buys, and scoring them on their own frequency
     * instead costs 1.9 points of exactly that. See
     * [com.rimboard.keyboard.engine.SuggestionEngine.suggestionsFor] for
     * where the anchor is applied.
     *
     * ## Why the head may end in a linking -s
     *
     * "Arbeitsplatz" is "Arbeit" + "Platz", and by the time enough has been
     * typed to know that, the joint has been typed too. So a head is accepted
     * when it is a word *or* when dropping a final -s makes it one, which is
     * the same joint [splitOf] already knows about, met from the other side.
     * Without it a sixth of these words are unreachable: never-offered goes
     * from 11.6% to 23.0%.
     *
     * Only ever forms that continue what has been typed, so this adds
     * candidates and can never change the word in front of the user.
     *
     * @param completions the dictionary's own prefix search: the words it
     *   holds that begin with a string, commonest first, with their counts.
     */
    fun completionsFor(
        lang: String,
        prefixLower: String,
        minFrequency: Int,
        frequency: (String) -> Int,
        completions: (String) -> List<Pair<String, Int>>
    ): List<String> {
        if (!writesClosed(lang)) return emptyList()
        if (prefixLower.length <= MIN_PART) return emptyList()
        // Keyed by the joined word so the same compound reached by two split
        // points is one candidate, held at the better of the two counts.
        val found = HashMap<String, Int>()
        for (i in MIN_PART until prefixLower.length) {
            val head = prefixLower.substring(0, i)
            if (frequency(head) < minFrequency &&
                !(head.length > MIN_PART && head[i - 1] == 's' &&
                    frequency(head.substring(0, i - 1)) >= minFrequency)
            ) {
                continue
            }
            for ((w, f) in completions(prefixLower.substring(i))) {
                // The halves have to be words rather than fragments, which is
                // the same floor [splitOf] holds the finished word to -- a
                // chip this offers must not be a word the underline then
                // refuses.
                if (w.length < MIN_PART) continue
                val joined = head + w
                if (joined == prefixLower) continue
                val prev = found[joined]
                if (prev == null || prev < f) found[joined] = f
            }
        }
        return found.entries.sortedByDescending { it.value }.map { it.key }
    }
}
