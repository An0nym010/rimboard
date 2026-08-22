package com.rimboard.keyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlin.math.hypot
import org.junit.Test

/**
 * The geometry a swipe is read with, on its own.
 *
 * [com.rimboard.keyboard.engine.GlideAccuracyTest] measures whether the decoder
 * gets the right word; this pins what the numbers underneath it *mean*. The
 * accuracy figure can stay where it is while the model quietly stops being a
 * distance in key widths, and every weight sized against it would then be
 * wrong without anything failing.
 */
class GlidePathTest {

    private val en = KeyProximity.forLang("en")

    /** A straight-line swipe through each of [stops], sampled finely. */
    private fun swipe(stops: String, samples: Int = 16): GlidePath {
        val pts = ArrayList<Float>()
        for (i in 0 until stops.length - 1) {
            val ax = en.gridX(stops[i])!!
            val ay = en.gridY(stops[i])!!
            val bx = en.gridX(stops[i + 1])!!
            val by = en.gridY(stops[i + 1])!!
            for (s in 0..samples) {
                val t = s.toFloat() / samples
                pts.add(ax + (bx - ax) * t)
                pts.add(ay + (by - ay) * t)
            }
        }
        return GlidePath.of(pts.toFloatArray(), en)!!
    }

    @Test
    fun `a perfectly drawn word costs nothing`() {
        // The property the whole model rests on, and the one whose absence
        // exposed the first version: if a flawless gesture does not score
        // about zero, the number is not measuring what it claims to.
        val p = swipe("helo")
        assertEquals(0.0, p.costOf("hello"), 0.02)
        assertEquals(0.0, p.costOf("helo"), 0.02)
    }

    @Test
    fun `the cost is a distance in key widths`() {
        // Swipe one word, ask about another whose curve runs parallel to it one
        // row below. The answer has to be about one row, because that is what
        // the units are -- and every constant weighed against this cost is
        // sized in them.
        val qp = swipe("qp")
        val az = swipe("az")
        assertEquals(0.0, qp.costOf("qp"), 0.02)
        // a sits one row under q, and ; would be under p -- l is the nearest
        // real key, so the parallel is close to but not exactly one row.
        assertTrue("expected roughly one row, got ${qp.costOf("al")}",
            qp.costOf("al") in 0.7..1.3)
        assertEquals(0.0, az.costOf("az"), 0.02)
    }

    @Test
    fun `a word that stops short of the path costs more than one that covers it`() {
        // The case the rule this replaced could not see at all: "hell" is a
        // subsequence of the keys a swipe to `o` crosses.
        val p = swipe("helo")
        assertTrue(p.costOf("hello") < p.costOf("hell"))
        assertTrue(p.costOf("hello") < p.costOf("he"))
    }

    @Test
    fun `letters strewn along the route do not make a word fit`() {
        // "stuff" beat "said" under the coverage model, because t, u and f sit
        // along the way. Its curve is a different line, and that is what is
        // compared now.
        val p = swipe("said")
        assertTrue(
            "said ${p.costOf("said")} should beat stuff ${p.costOf("stuff")}",
            p.costOf("said") < p.costOf("stuff")
        )
    }

    @Test
    fun `where a gesture was drawn is part of what it means`() {
        // Shape matching normally normalises position away. Here it must not:
        // the same zig-zag drawn on a different part of the keyboard is a
        // different word.
        val p = swipe("wsx")
        assertEquals(0.0, p.costOf("wsx"), 0.05)
        assertTrue(p.costOf("wsx") < p.costOf("ikm"))
    }

    @Test
    fun `both ends of the swipe are read tolerantly`() {
        // A finger that starts a third of a key into its neighbour must still
        // be able to spell a word starting where it meant to.
        val gx = en.gridX('g')!!
        val hx = en.gridX('h')!!
        val pts = ArrayList<Float>()
        val startX = hx + (gx - hx) * 0.34f
        val ey = en.gridY('e')!!
        val ex = en.gridX('e')!!
        for (s in 0..20) {
            val t = s / 20f
            pts.add(startX + (ex - startX) * t)
            pts.add(en.gridY('h')!! + (ey - en.gridY('h')!!) * t)
        }
        val p = GlidePath.of(pts.toFloatArray(), en)!!
        assertTrue("h should still be an option, got ${p.startKeys.concatToString()}",
            p.startKeys.contains('h'))
        assertTrue(p.endKeys.contains('e'))
    }

    @Test
    fun `resampling is independent of how densely the finger was sampled`() {
        // The same gesture reported by a 240 Hz digitiser and a 60 Hz one has
        // to read the same, or the keyboard is more accurate on better phones.
        val sparse = swipe("helo", samples = 3)
        val dense = swipe("helo", samples = 40)
        assertEquals(sparse.costOf("hello"), dense.costOf("hello"), 0.05)
        assertEquals(GlidePath.SHAPE_POINTS, sparse.size)
        assertEquals(GlidePath.SHAPE_POINTS, dense.size)
    }

    @Test
    fun `a path always starts and ends where the finger did`() {
        // Resampling must not shave the ends off: they are the two points that
        // decide which words are even considered.
        val p = swipe("qzp")
        assertEquals(en.gridX('q')!!, p.x(0), 1e-4f)
        assertEquals(en.gridY('q')!!, p.y(0), 1e-4f)
        assertEquals(en.gridX('p')!!, p.x(p.size - 1), 1e-4f)
        assertEquals(en.gridY('p')!!, p.y(p.size - 1), 1e-4f)
    }

    @Test
    fun `resampled points are evenly spaced along the path`() {
        // The comparison in costOf pairs point i of one curve with point i of
        // the other, which is only meaningful if both are spaced by distance.
        val p = swipe("qzpq")
        var min = Float.MAX_VALUE
        var max = 0f
        for (i in 1 until p.size) {
            val d = hypot(p.x(i) - p.x(i - 1), p.y(i) - p.y(i - 1))
            if (d < min) min = d
            if (d > max) max = d
        }
        // Corners are where an even walk of a polyline is least even.
        assertTrue("spacing ran $min..$max", max <= min * 1.6f + 0.02f)
    }

    @Test
    fun `a word this layout cannot spell is refused rather than guessed at`() {
        val p = swipe("helo")
        assertTrue(p.costOf("hellö").isInfinite())
        assertEquals(-1, p.slotOf('ö'))
    }

    @Test
    fun `a smudge is not a swipe`() {
        assertNull(GlidePath.of(floatArrayOf(1f, 1f, 1.05f, 1.02f), en))
        assertNull(GlidePath.of(FloatArray(0), en))
        assertNull(GlidePath.of(floatArrayOf(1f, 1f, 5f), en))
        assertNotNull(GlidePath.of(floatArrayOf(1f, 1f, 5f, 1f), en))
    }

    @Test
    fun `a word longer than the scratch is truncated, not a crash`() {
        // The bound exists so the buffers can be fixed-size. Whatever it does
        // to a word with more turns than that, it must not be throw.
        val p = swipe("qwertyuiop")
        val monster = (0 until 40).joinToString("") { "qwertyuiop"[it % 10].toString() }
        assertTrue(p.costOf(monster).isFinite())
    }
}
