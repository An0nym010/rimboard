package com.rimboard.keyboard.ui

/**
 * How far a downloaded thumbnail can be shrunk while still filling its tile.
 *
 * The GIF grid decoded every preview at whatever resolution the provider sent,
 * in ARGB_8888, and then drew it into a two-column tile 92dp tall. What the
 * grid can show is bounded by the screen; what was being held in memory was
 * not bounded by anything. Two dozen results at 480x270 is about twelve
 * megabytes of bitmap for a surface that can display a fraction of it, and
 * this process is also holding the dictionaries and is a background process
 * that the platform kills before it kills an app the user can see.
 *
 * Kept apart from the decode itself because `BitmapFactory` is an Android
 * class and does not exist on a plain JVM, so a rule living next to it could
 * not be tested. The arithmetic is the part that can be wrong.
 */
object Thumbs {

    /**
     * The `inSampleSize` to decode a [srcW] x [srcH] image for a [reqW] x
     * [reqH] tile: the largest power of two that still leaves both dimensions
     * at or above what the tile needs.
     *
     * Powers of two because `BitmapFactory` rounds anything else down to one,
     * so a value it will not honour is worse than useless — it reads as a
     * request that was made and quietly ignored.
     *
     * At or above, never below: the tile is drawn CENTER_CROP, and an image
     * decoded smaller than the tile is upscaled to fit, which is visible as
     * softness on exactly the content people are choosing between. Halving
     * quarters the memory, so the conservative direction is still worth a
     * great deal.
     */
    fun sampleSizeFor(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
        // A malformed or undecodable header gives 0 or -1 here. Sampling on a
        // number that is not a size cannot be meaningful, and 1 is the value
        // that changes nothing.
        if (srcW <= 0 || srcH <= 0 || reqW <= 0 || reqH <= 0) return 1
        var sample = 1
        // Doubling while *both* halves still clear the requirement: stopping at
        // the first dimension to fall below is what keeps the short edge from
        // being sampled past the tile it has to fill.
        while (srcW / (sample * 2) >= reqW && srcH / (sample * 2) >= reqH) {
            sample *= 2
        }
        return sample
    }
}
