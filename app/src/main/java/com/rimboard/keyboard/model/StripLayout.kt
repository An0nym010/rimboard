package com.rimboard.keyboard.model

/**
 * How the suggestion slots are filled for a word the dictionary does not know.
 *
 * [SLOTS] says how many there are, and every rule below is written in terms of
 * it rather than of a digit -- the strip was three wide for most of this
 * file's life, and the prose that said so outlived it in six places.
 *
 * Pulled out of the service, which is an `InputMethodService` and cannot run on
 * a plain JVM. These are ordering rules with an index that has to follow the
 * words as they move, which is precisely the kind of thing that is wrong in a
 * way nobody notices — a stale highlight points at the wrong chip, and the
 * chip it points at is what the space bar commits.
 */
object StripLayout {

    /**
     * How many suggestion chips the strip has room for.
     *
     * It was three, and three was never measured -- it is what Gboard shows
     * and what this keyboard copied. Measured over 500 words of prose per
     * language, taking the target word from the top N of the engine's own
     * ranked list, keystrokes saved:
     *
     *     lang   3 chips     4       6       8      12
     *     en      41.8%   47.7%   53.4%   56.7%   60.0%
     *     de      39.6%   47.4%   54.2%   58.1%   62.1%
     *     tr      30.6%   38.0%   45.2%   49.4%   54.8%
     *     fi      33.3%   42.2%   49.9%   54.7%   59.4%
     *     cs      32.2%   39.7%   48.0%   52.2%   57.7%
     *     pl      35.3%   43.3%   51.0%   54.8%   59.5%
     *     ru      35.1%   41.2%   48.8%   53.4%   57.5%
     *     es      39.0%   45.0%   51.3%   54.7%   58.9%
     *
     * **The ranking already knew the word; the strip could not show it.** One
     * more chip is worth six to nine points, an order of magnitude more than
     * anything else measured in this engine.
     *
     * Five rather than more, and the limit is width rather than ranking. The
     * strip is the screen less the chevron and the padding -- about 960px of
     * 1080 on the phone this was checked on. Split five ways at the minimum
     * weight below that is 192px, or **70dp**, comfortably past the 48dp
     * Android asks of a touch target; six would be 58dp and seven 50dp, which
     * is where it stops being a button. The emoji chip and the incognito mark
     * share the same row and are not free either.
     *
     * The figures above are an **upper bound**: they assume the reader finds
     * the right chip among N, and reading five costs more attention than
     * reading three. What is not an assumption is that at three the answer was
     * often computed and thrown away.
     */
    const val SLOTS = 5

    /**
     * The narrowest a chip may be and still be a button.
     *
     * Android asks 48dp of anything meant to be tapped, and a suggestion chip
     * is meant to be tapped.
     */
    const val MIN_CHIP_DP = 48

    /**
     * How many of [SLOTS] actually fit in [freeDp], never dropping the chip at
     * [keepAtLeast] - 1.
     *
     * [SLOTS] is a maximum, not a count, and the first version of this change
     * did not say so -- it justified five with "960px of row divided five ways
     * is 70dp", which is this phone, at full width, with neither the emoji
     * chip nor the incognito mark on screen. The strip's fixed children come
     * to **114dp** (chevron 34, emoji chip 38, incognito 30, padding 8, four
     * dividers), and once they are subtracted five chips are:
     *
     *     393dp phone   full  55.8dp     floating  44.8dp
     *     360dp phone   full  49.2dp     floating  39.1dp
     *     320dp phone   full  41.2dp     floating  32.2dp
     *
     * Three of those six are below the touch target and one is half of it. So
     * the row counts what it can afford instead of assuming, which also covers
     * one-handed mode, landscape, and whatever screen this has not seen.
     *
     * [keepAtLeast] is the promise from the bold chip: what the separator is
     * going to commit must be *on* the strip, so a narrow row may drop the
     * candidates after it and never the one it points at.
     */
    fun chipsThatFit(freeDp: Int, want: Int = SLOTS, keepAtLeast: Int = 1): Int {
        if (want <= 0) return 0
        // Before the first layout there is no width to divide. The strip is
        // redrawn on the next keystroke, by which time there is.
        if (freeDp <= 0) return want
        val floor = keepAtLeast.coerceIn(1, want)
        return (freeDp / MIN_CHIP_DP).coerceIn(floor, want)
    }

    /**
     * The smallest share of the strip a chip may take, as a word length.
     *
     * Chips used to divide the width equally, so "Bananenkuchen" was
     * ellipsised to "Banane...uchen" while "Kinde" beside it sat in a slot
     * two-thirds empty. Weighting by length fixes that and is what makes five
     * chips affordable at all -- but a bare length weight gives a one-letter
     * chip an eighth of what a long one gets, which is a target too small to
     * hit. Four is the floor, so the narrowest chip is still the width of an
     * ordinary short word.
     */
    const val MIN_WEIGHT = 4f

    /**
     * And the largest, so one long word cannot starve the rest.
     *
     * Twelve characters is past the point where a chip is comfortably read at
     * a glance; beyond it the word is ellipsised either way and the space is
     * better spent on its neighbours.
     */
    const val MAX_WEIGHT = 12f

    /**
     * Width shares for [words], proportional to how much room each needs.
     *
     * Character count rather than measured text: this runs on every keystroke,
     * the difference between "iii" and "mmm" is not worth a `Paint` call per
     * chip, and a rule expressed in characters is one a test can check without
     * a font. Empty slots weigh nothing because they are not shown.
     */
    fun weights(words: List<String>): List<Float> = words.map {
        if (it.isEmpty()) 0f else it.length.toFloat().coerceIn(MIN_WEIGHT, MAX_WEIGHT)
    }

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
        if (known) return Arranged(items.take(SLOTS), autocorrectIndex, null)
        val others = items.drop(1).filter { it.isNotEmpty() }
        val chip = quote(verbatim)
        if (others.isEmpty()) return Arranged(listOf(chip), -1, verbatim)
        // Second, not first, whatever the strip is wide enough for: the front
        // slot carries the best suggestion and the quoted word sits beside it.
        val arranged = (listOf(others[0], chip) + others.drop(1)).take(SLOTS)
        // Re-found by value: the target moved, so carrying the old index over
        // would highlight — and commit on space — whatever now sits at it.
        val target = items.getOrNull(autocorrectIndex)
        val hi = if (target != null) arranged.indexOf(target) else -1
        return Arranged(arranged, hi, verbatim)
    }

    /**
     * **Nothing here pads, and everything here trims.** Every entry returned
     * is a chip with a word on it,
     * so a caller counting suggestions is counting suggestions -- the view
     * fills the row out to [SLOTS] and hides what is left over.
     *
     * That distinction did not matter while the strip was three wide and
     * usually full. At five it matters constantly, and getting it wrong made
     * four separate tests report a keyboard "offering a different word" that
     * was offering an empty one.
     */
}
