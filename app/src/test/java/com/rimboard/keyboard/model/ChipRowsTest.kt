package com.rimboard.keyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic behind the expanded suggestion panel.
 *
 * Small, and meant to stay small: a panel is a grid opened on purpose, where
 * [StripLayout] has a bold chip whose place is promised and fixed children to
 * subtract. What is worth pinning here is the two ends -- a panel with no
 * width yet, and a panel on a screen wide enough to be silly.
 */
class ChipRowsTest {

    @Test
    fun `the column count follows the width`() {
        // 360dp is the narrow phone the strip's own arithmetic is written
        // against and 393dp is the one everything here was measured on. Three
        // chips of 120dp, or four of 98dp -- against the five of about 56dp
        // the strip squeezes into the same row, which is the comparison that
        // makes this feature worth having.
        assertEquals(3, ChipRows.columnsFor(360))
        assertEquals(4, ChipRows.columnsFor(393))
        assertEquals(2, ChipRows.columnsFor(200))
    }

    @Test
    fun `no width yet is the narrowest answer, not a crash and not everything`() {
        // The panel is built before it is laid out, so this is asked with a
        // width of zero on the first pass and again from `onSizeChanged`.
        // Answering with the whole list would put twenty chips in one row.
        assertEquals(ChipRows.MIN_COLUMNS, ChipRows.columnsFor(0))
        assertEquals(ChipRows.MIN_COLUMNS, ChipRows.columnsFor(-1))
    }

    @Test
    fun `a very wide screen is capped`() {
        // A tablet in landscape would otherwise put a dozen words in a row,
        // which is not a thing anybody reads. The cap is about legibility
        // rather than about width.
        assertEquals(ChipRows.MAX_COLUMNS, ChipRows.columnsFor(2000))
        assertTrue(ChipRows.MAX_COLUMNS >= ChipRows.MIN_COLUMNS)
    }

    @Test
    fun `words fill left to right and then down`() {
        // Row-major, because the engine ranked them and a column-major fill
        // would put the best word top left and the second-best halfway down
        // the panel, which reads as no order at all.
        assertEquals(
            listOf(listOf("a", "b", "c"), listOf("d", "e")),
            ChipRows.rows(listOf("a", "b", "c", "d", "e"), 3)
        )
    }

    @Test
    fun `a short list is one short row`() {
        assertEquals(listOf(listOf("a", "b")), ChipRows.rows(listOf("a", "b"), 3))
    }

    @Test
    fun `nothing to show is no rows at all`() {
        assertEquals(emptyList<List<String>>(), ChipRows.rows(emptyList(), 3))
        assertEquals(emptyList<List<String>>(), ChipRows.rows(listOf("a"), 0))
    }

    @Test
    fun `every word survives the split`() {
        val words = (1..23).map { "w$it" }
        for (cols in ChipRows.MIN_COLUMNS..ChipRows.MAX_COLUMNS) {
            assertEquals(
                "a word was dropped or duplicated at $cols columns",
                words, ChipRows.rows(words, cols).flatten()
            )
        }
    }

    @Test
    fun `the panel asks for more than the strip can hold`() {
        // The whole point of the feature, as a number. If these ever met, the
        // gesture would open a panel showing exactly what it was already
        // showing.
        assertTrue(
            "the expanded panel no longer asks for more than the strip shows",
            ChipRows.CAPACITY > StripLayout.SLOTS
        )
        assertEquals(ChipRows.MAX_COLUMNS * ChipRows.ROWS, ChipRows.CAPACITY)
    }
}
