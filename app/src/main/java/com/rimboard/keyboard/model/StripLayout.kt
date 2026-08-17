package com.rimboard.keyboard.model

/**
 * How the three suggestion slots are filled for a word the dictionary does not
 * know.
 *
 * Pulled out of the service, which is an `InputMethodService` and cannot run on
 * a plain JVM. These are ordering rules with an index that has to follow the
 * words as they move, which is precisely the kind of thing that is wrong in a
 * way nobody notices — a stale highlight points at the wrong chip, and the
 * chip it points at is what the space bar commits.
 */
object StripLayout {

    /**
     * [words] as they should appear, [highlight] as the index the space bar
     * would commit (-1 for none), and [quotedWord] as the raw word behind the
     * quoted chip, if there is one.
     */
    data class Arranged(val words: List<String>, val highlight: Int, val quotedWord: String?)

    /**
     * Arranges [items] — slot 0 being the verbatim typed word, as the engine
     * returns it — for display.
     *
     * A word the dictionary knows is left exactly as the engine ranked it.
     *
     * A word it does not know moves off the front and into the middle, wrapped
     * by [quote], so the two suggestions sit either side of it and the front
     * slot carries the best one rather than a repeat of what is already visible
     * in the field. The word stays on the strip and stays tappable, because a
     * keyboard that hides what you actually typed is arguing with you about
     * your own name.
     *
     * With no candidates at all it is alone, which is the honest display for
     * something like "mndsnfms": there is nothing to rank against it, and
     * filling the other slots would mean inventing entries. This is decided by
     * having no candidates, never by judging a word to be random — the
     * difference matters, because a rare or foreign word is not gibberish and
     * would be shown alone here purely for want of anything better to say.
     */
    fun arrange(
        items: List<String>,
        autocorrectIndex: Int,
        known: Boolean,
        quote: (String) -> String
    ): Arranged {
        val verbatim = items.firstOrNull()
            ?: return Arranged(items, autocorrectIndex, null)
        if (known) return Arranged(items, autocorrectIndex, null)
        val others = items.drop(1).filter { it.isNotEmpty() }
        val chip = quote(verbatim)
        if (others.isEmpty()) return Arranged(listOf(chip, "", ""), -1, verbatim)
        val arranged = listOf(others[0], chip, others.getOrNull(1) ?: "")
        // Re-found by value: the target moved, so carrying the old index over
        // would highlight — and commit on space — whatever now sits at it.
        val target = items.getOrNull(autocorrectIndex)
        val hi = if (target != null) arranged.indexOf(target) else -1
        return Arranged(arranged, hi, verbatim)
    }
}
