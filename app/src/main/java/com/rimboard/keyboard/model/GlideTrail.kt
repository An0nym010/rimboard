package com.rimboard.keyboard.model

/**
 * The points a finger left while swiping, and the two things done to them.
 *
 * Pulled out of `KeyboardView` because that class is a `View` and cannot run on
 * a plain JVM, so everything living in it is reasoned about rather than tested.
 * What is here is not view work: it is a bounded buffer and a change of
 * coordinates, and both have an off-by-one in them waiting to be written.
 *
 * Two readers, wanting different things from the same points. The trail is
 * *drawn* end to end, so it must not lose its start; and it is *decoded*, so it
 * must not lose its start either — a swipe whose first letters have been
 * discarded is a different word. That is one requirement rather than two, and
 * it is the requirement the buffer this replaced did not meet: it dropped from
 * the front when it filled, silently beheading exactly the long swipes that
 * most need decoding.
 */
class GlideTrail(private val cap: Int = DEFAULT_CAP) {

    private var buf = FloatArray(128)

    /** Floats held, not points — two per point, matching how it is indexed. */
    var size: Int = 0
        private set

    operator fun get(i: Int): Float = buf[i]

    /** Points held. */
    val points: Int get() = size / 2

    fun clear() {
        size = 0
    }

    fun add(x: Float, y: Float) {
        if (size + 2 > buf.size) buf = buf.copyOf(buf.size * 2)
        buf[size++] = x
        buf[size++] = y
        if (size > cap) thin()
    }

    /**
     * Halves the buffer by keeping every other point.
     *
     * Thinning rather than truncating is the whole point of this class. The
     * last point is carried across whatever the parity works out to, because it
     * is where the finger is now: a stride that happened to skip it would move
     * the end of the path, and the end of the path is what decides which words
     * are even considered.
     */
    private fun thin() {
        val n = points
        var out = 0
        var i = 0
        while (i < n) {
            buf[out * 2] = buf[i * 2]
            buf[out * 2 + 1] = buf[i * 2 + 1]
            out++
            i += 2
        }
        val last = n - 1
        if (last >= 0 && last % 2 != 0) {
            buf[out * 2] = buf[last * 2]
            buf[out * 2 + 1] = buf[last * 2 + 1]
            out++
        }
        size = out * 2
    }

    /**
     * The trail as a path in [KeyProximity]'s letter grid, interleaved x,y.
     *
     * Each point is placed relative to the letter key nearest it, in that key's
     * own widths and heights — the same measure a tap reports. That anchoring
     * is what makes the result independent of key size, screen density,
     * one-handed mode and the split keyboard, all of which move pixels around
     * without moving a letter relative to its neighbours.
     *
     * It is also very nearly continuous across a key boundary, because the grid
     * is spaced exactly one key width apart: a point halfway between `q` and
     * `w` lands on the same grid coordinate whichever of the two it is measured
     * from. So which key is "nearest" barely matters, and no seam appears in
     * the middle of a path that crosses one.
     *
     * The key geometry arrives as parallel arrays rather than as objects
     * because the caller has it as objects and this has to be checkable without
     * them. Empty when there is no geometry to convert against, which the
     * decoder reads as "no glide" and falls back on the key the swipe started
     * from.
     */
    fun toGrid(
        labels: CharArray,
        centreX: FloatArray,
        centreY: FloatArray,
        width: FloatArray,
        height: FloatArray,
        prox: KeyProximity
    ): FloatArray {
        if (labels.isEmpty() || size < 4) return FloatArray(0)
        val out = FloatArray(size)
        var n = 0
        var i = 0
        while (i + 1 < size) {
            val x = buf[i]
            val y = buf[i + 1]
            val k = nearest(x, y, labels.size, centreX, centreY)
            val w = width[k]
            val h = height[k]
            val gx = prox.gridX(labels[k])
            val gy = prox.gridY(labels[k])
            // A key with no size or no place on the grid cannot anchor
            // anything. Neither is reachable from a laid-out keyboard whose
            // grid was built from the same layout; the point is dropped rather
            // than placed at the origin, which is a real position on this grid
            // and would read as a deliberate visit to `q`.
            if (w > 0f && h > 0f && gx != null && gy != null) {
                out[n++] = gx + (x - centreX[k]) / w
                out[n++] = gy + (y - centreY[k]) / h
            }
            i += 2
        }
        return if (n == out.size) out else out.copyOf(n)
    }

    private fun nearest(
        x: Float, y: Float, count: Int, centreX: FloatArray, centreY: FloatArray
    ): Int {
        var best = 0
        var bestD = Float.MAX_VALUE
        for (k in 0 until count) {
            val dx = x - centreX[k]
            val dy = y - centreY[k]
            val d = dx * dx + dy * dy
            if (d < bestD) {
                bestD = d
                best = k
            }
        }
        return best
    }

    companion object {
        /**
         * Floats held before the trail is thinned, two per point.
         *
         * Generous: 256 points along one swipe is finer than any hand draws,
         * and the thinning is lossy, so this bounds memory rather than shaping
         * the data.
         */
        const val DEFAULT_CAP = 512
    }
}
