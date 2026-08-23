package com.rimboard.keyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The buffer and the change of coordinates behind glide typing.
 *
 * This logic lived in `KeyboardView` and so had never been run by anything but
 * a thumb. It is two off-by-ones waiting to happen: a stride that drops the
 * wrong point, and a division that anchors to the wrong key.
 */
class GlideTrailTest {

    private val en = KeyProximity.forLang("en")

    /** A keyboard 10 keys wide and 3 rows deep, 100x120 pixels a key. */
    private fun qwertyBoxes(): Array<Any> {
        val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val labels = ArrayList<Char>()
        val cx = ArrayList<Float>()
        val cy = ArrayList<Float>()
        for ((r, row) in rows.withIndex()) {
            val inset = listOf(0f, 50f, 150f)[r]
            for ((i, ch) in row.withIndex()) {
                labels.add(ch)
                cx.add(inset + i * 100f + 50f)
                cy.add(r * 120f + 60f)
            }
        }
        return arrayOf(
            labels.toCharArray(),
            cx.toFloatArray(),
            cy.toFloatArray(),
            FloatArray(labels.size) { 100f },
            FloatArray(labels.size) { 120f }
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun toGrid(t: GlideTrail): FloatArray {
        val b = qwertyBoxes()
        return t.toGrid(
            b[0] as CharArray, b[1] as FloatArray, b[2] as FloatArray,
            b[3] as FloatArray, b[4] as FloatArray, en
        )
    }

    // ---- the buffer --------------------------------------------------------

    @Test
    fun `thinning keeps the first point and the last`() {
        // The whole reason this class exists. A buffer that drops from the
        // front beheads a long swipe, and a stride that drops the last point
        // moves where the swipe ended -- which decides what words are even
        // considered.
        val t = GlideTrail(cap = 8)
        for (i in 0 until 40) t.add(i.toFloat(), -i.toFloat())
        assertEquals("the first point was lost", 0f, t[0], 0f)
        assertEquals(-0f, t[1], 0f)
        assertEquals("the last point was lost", 39f, t[t.size - 2], 0f)
        assertEquals(-39f, t[t.size - 1], 0f)
    }

    @Test
    fun `thinning never lets the buffer run past its cap`() {
        val t = GlideTrail(cap = 8)
        for (i in 0 until 500) {
            t.add(i.toFloat(), i.toFloat())
            assertTrue("size ${t.size} passed the cap", t.size <= 8)
        }
    }

    @Test
    fun `thinning keeps the points in order and does not repeat one`() {
        val t = GlideTrail(cap = 16)
        for (i in 0 until 200) t.add(i.toFloat(), 0f)
        val xs = (0 until t.points).map { t[it * 2] }
        assertEquals("points came back out of order", xs.sorted(), xs)
        assertEquals("a point was duplicated", xs.distinct().size, xs.size)
    }

    @Test
    fun `an odd number of points still keeps the last one`() {
        // The parity case: a stride of two over an odd count lands on the last
        // point, over an even count it does not, and only one of those needs
        // the carry.
        for (count in 3..24) {
            val t = GlideTrail(cap = 8)
            for (i in 0 until count) t.add(i.toFloat(), 0f)
            assertEquals(
                "last point lost for a trail of $count",
                (count - 1).toFloat(), t[t.size - 2], 0f
            )
        }
    }

    @Test
    fun `a cleared trail holds nothing`() {
        val t = GlideTrail()
        t.add(1f, 2f)
        t.clear()
        assertEquals(0, t.size)
        assertEquals(0, toGrid(t).size)
    }

    // ---- the change of coordinates ----------------------------------------

    @Test
    fun `a point on a key centre lands on that key's grid position`() {
        val t = GlideTrail()
        // Centre of 'q' is (50, 60); centre of 'p' is (950, 60).
        t.add(50f, 60f)
        t.add(950f, 60f)
        val g = toGrid(t)
        assertEquals(4, g.size)
        assertEquals(en.gridX('q')!!, g[0], 1e-4f)
        assertEquals(en.gridY('q')!!, g[1], 1e-4f)
        assertEquals(en.gridX('p')!!, g[2], 1e-4f)
        assertEquals(en.gridY('p')!!, g[3], 1e-4f)
    }

    @Test
    fun `a point between two keys lands in the same place from either side`() {
        // The property the doc claims and the reason "which key is nearest"
        // does not matter: the grid is spaced exactly one key width apart, so a
        // point halfway between q and w is q + 0.5 and also w - 0.5. If this
        // broke, a path crossing a key boundary would jump.
        val t = GlideTrail()
        t.add(99f, 60f)   // a hair inside q
        t.add(101f, 60f)  // a hair inside w
        val g = toGrid(t)
        assertEquals("a seam opened at the key boundary", g[0], g[2], 0.05f)
        assertEquals(en.gridX('q')!! + 0.49f, g[0], 0.02f)
    }

    @Test
    fun `the conversion is independent of how big the keys are`() {
        // One-handed mode, a split keyboard and a denser screen all move pixels
        // without moving a letter relative to its neighbours, so the same
        // gesture has to come out the same.
        val big = GlideTrail()
        big.add(50f, 60f)
        big.add(550f, 180f)
        val small = GlideTrail()
        small.add(25f, 30f)
        small.add(275f, 90f)

        val b = qwertyBoxes()
        val labels = b[0] as CharArray
        val half = { a: FloatArray -> FloatArray(a.size) { a[it] / 2f } }
        val gBig = big.toGrid(
            labels, b[1] as FloatArray, b[2] as FloatArray,
            b[3] as FloatArray, b[4] as FloatArray, en
        )
        val gSmall = small.toGrid(
            labels, half(b[1] as FloatArray), half(b[2] as FloatArray),
            half(b[3] as FloatArray), half(b[4] as FloatArray), en
        )
        assertEquals(gBig.size, gSmall.size)
        for (i in gBig.indices) {
            assertEquals("point $i moved when the keys were resized", gBig[i], gSmall[i], 1e-3f)
        }
    }

    @Test
    fun `no key geometry means no path rather than a path at the origin`() {
        // A point that cannot be anchored must not become (0, 0): that is a
        // real place on this grid, right on 'q', and would read as a deliberate
        // visit there.
        val t = GlideTrail()
        t.add(50f, 60f)
        t.add(150f, 60f)
        assertEquals(
            0,
            t.toGrid(CharArray(0), FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0), en)
                .size
        )
    }

    @Test
    fun `a smudge too short to be a swipe converts to nothing`() {
        val t = GlideTrail()
        t.add(50f, 60f)
        assertEquals(0, toGrid(t).size)
    }
}
