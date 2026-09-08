package com.rimboard.keyboard.model

/**
 * How the expanded suggestion panel divides itself up.
 *
 * [StripLayout] answers the same question for the one-line strip, and answers
 * it much harder, because that row has fixed children to subtract, a bold chip
 * whose place is promised, and a rule about which chip may be displaced. A
 * panel has none of that: it is a grid, it is opened on purpose, and every
 * word in it means the same thing. So this is a smaller object and is meant to
 * stay one.
 *
 * Separate from the view for the reason every rule in this project is
 * separate from its drawing: the arithmetic is what can be wrong, and a
 * `ViewGroup` cannot be asked a question in a unit test.
 */
object ChipRows {

    /**
     * The width a chip is given, in dp.
     *
     * Twice [StripLayout.MIN_CHIP_DP], which is the smallest a chip may be and
     * still be a button. The strip is at that floor because it has one row to
     * spend; a panel is not short of room, and a word wide enough to be worth
     * expanding the strip for is a word too wide for 48dp. Anything longer
     * still ellipsises, as it does on the strip.
     */
    const val CHIP_DP = 96

    /** Never fewer than two: one per row is a list, and a list of six words is
     *  barely more than the strip already shows. */
    const val MIN_COLUMNS = 2

    /**
     * Never more than four, whatever the screen.
     *
     * A tablet in landscape would otherwise put a dozen words in a row, and a
     * dozen words in a row is not a thing anybody reads -- it is a thing
     * people scan and give up on. The cap is about legibility rather than
     * about width.
     */
    const val MAX_COLUMNS = 4

    /**
     * Rows that fit a keyboard-height panel without scrolling.
     *
     * The panel is sized to the keyboard, which is around 220dp on a phone,
     * and a chip row is 48dp with its gap. Six is what fits; the panel scrolls
     * past it rather than stopping, so this is where the list stops being free
     * to read rather than where it stops.
     */
    const val ROWS = 6

    /**
     * How many words the panel asks the engine for.
     *
     * A ceiling rather than an expectation, and the difference is worth
     * writing down: `SuggestionEngine.COMPLETION_FETCH` is 12 and is a swept
     * constant, so the dictionary contributes at most twelve prefix matches
     * per keystroke, with the learned list and the second language on top.
     * A panel that asks for twenty-four usually gets twelve to twenty. That is
     * still three to four times the strip, and it is the range the keystroke
     * table in `open-items` was measured over -- it stops at twelve chips.
     *
     * Asking for more than exists costs nothing; raising the fetch to fill the
     * grid would cost something real, because `FUZZY_TRIGGER` fires on the
     * size of the merged candidate list and a deeper fetch would silence the
     * typo repair it guards.
     */
    const val CAPACITY = MAX_COLUMNS * ROWS

    /** Chips per row for a panel [availableDp] wide. */
    fun columnsFor(availableDp: Int): Int =
        if (availableDp <= 0) MIN_COLUMNS
        else (availableDp / CHIP_DP).coerceIn(MIN_COLUMNS, MAX_COLUMNS)

    /**
     * [words] split into rows of [columns], in order.
     *
     * Reading order is left to right and then down, which is the order the
     * engine ranked them in. A column-major fill would put the best word top
     * left and the second-best halfway down the panel, which reads as no
     * order at all.
     */
    fun rows(words: List<String>, columns: Int): List<List<String>> {
        if (words.isEmpty() || columns <= 0) return emptyList()
        val out = ArrayList<List<String>>((words.size + columns - 1) / columns)
        var i = 0
        while (i < words.size) {
            out.add(words.subList(i, minOf(i + columns, words.size)))
            i += columns
        }
        return out
    }
}
