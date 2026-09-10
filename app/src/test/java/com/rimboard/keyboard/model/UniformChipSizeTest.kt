package com.rimboard.keyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one rule that decides how big a row of suggestion chips is drawn.
 *
 * It was written into `SuggestionStripView` in `6ba580a` and the expanded
 * panel went on auto-sizing each chip independently for two months, which on a
 * phone is `control` drawn visibly larger than `conversation` in grid columns
 * of *identical* width. That is the failure this file is really about: not the
 * arithmetic, which is four lines, but that there was more than one copy of
 * it. Both views call [StripLayout.uniformTextSp] now.
 *
 * `SuggestionStripView` passes weighted shares and the panel passes equal
 * ones, so the cases below cover both shapes.
 */
class UniformChipSizeTest {

    private val min = 12f
    private val max = 15f

    @Test
    fun `a row with room to spare is drawn at the ceiling`() {
        val sp = StripLayout.uniformTextSp(
            needDp = listOf(40, 44, 38),
            shareDp = listOf(90f, 90f, 90f),
            minSp = min, maxSp = max
        )
        assertEquals("plenty of room, so nothing holds the row back", max, sp, 0.001f)
    }

    @Test
    fun `the tightest chip sets the size for the whole row`() {
        // 90/120 of the floor is 9sp, which the floor then lifts back to 12.
        // The point is that the *widest* need decides, not each chip's own.
        val loose = StripLayout.uniformTextSp(
            needDp = listOf(40), shareDp = listOf(60f), minSp = min, maxSp = max
        )
        val withATightNeighbour = StripLayout.uniformTextSp(
            needDp = listOf(40, 55), shareDp = listOf(60f, 60f), minSp = min, maxSp = max
        )
        assertTrue(
            "a tight chip beside a loose one has to bring the row down with " +
                "it -- otherwise the row is ragged, which is what per-chip " +
                "auto-sizing did",
            withATightNeighbour < loose
        )
    }

    @Test
    fun `equal shares and equal needs give one size, whatever the words`() {
        // The panel's shape: a fixed grid, every cell the same width. Two
        // words of different length in identical columns must come out the
        // same size, because nothing about the space is different.
        val four = StripLayout.uniformTextSp(
            needDp = listOf(46, 46, 46, 46),
            shareDp = listOf(88f, 88f, 88f, 88f),
            minSp = min, maxSp = max
        )
        val one = StripLayout.uniformTextSp(
            needDp = listOf(46), shareDp = listOf(88f), minSp = min, maxSp = max
        )
        assertEquals("the row is one size, so a fourth chip changes nothing", one, four, 0.001f)
    }

    @Test
    fun `it never sizes below the floor`() {
        val sp = StripLayout.uniformTextSp(
            needDp = listOf(200), shareDp = listOf(40f), minSp = min, maxSp = max
        )
        assertEquals(
            "a chip that cannot fit at the floor is dropped by chipsThatRead, " +
                "not shrunk past it -- 8sp on a suggestion strip is not a " +
                "suggestion",
            min, sp, 0.001f
        )
    }

    @Test
    fun `blank slots are skipped rather than dividing by zero`() {
        val sp = StripLayout.uniformTextSp(
            needDp = listOf(44, 0, 0), shareDp = listOf(90f, 90f, 90f),
            minSp = min, maxSp = max
        )
        assertEquals(
            "an empty slot has no need and must not drag the row anywhere",
            max, sp, 0.001f
        )
    }

    @Test
    fun `an empty row falls back to the ceiling`() {
        assertEquals(
            max,
            StripLayout.uniformTextSp(emptyList(), emptyList(), min, max),
            0.001f
        )
    }

    @Test
    fun `a missing share is treated as no room, not as unlimited room`() {
        // Defensive: the two callers build both lists together, but a shorter
        // shares list must not read as "draw it as large as you like".
        val sp = StripLayout.uniformTextSp(
            needDp = listOf(44, 44), shareDp = listOf(90f), minSp = min, maxSp = max
        )
        assertEquals(min, sp, 0.001f)
    }
}
