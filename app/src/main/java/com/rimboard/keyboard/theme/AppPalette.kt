package com.rimboard.keyboard.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas

/**
 * The colour an app is actually recognised by, taken from its launcher icon.
 *
 * The per-app tint began as a hash of the package name, because reading
 * another package's icon needs package visibility and this keyboard had
 * deliberately declared almost none. A hash gives a colour that is distinct and
 * stable, which is most of what the feature needs — but it is not the app's
 * colour, and for anyone who expected the keyboard to go green in WhatsApp it
 * reads as broken rather than as a trade-off.
 *
 * So the icon is tried first and the hash remains the fallback. Where the
 * platform does not make the package visible, [hueOf] returns null and nothing
 * changes; nothing here throws, and nothing here needs a permission that the
 * offline build does not already hold.
 */
object AppPalette {

    /** Icon draw size. Large enough to survive a flat icon's few real pixels,
     *  small enough that the draw and the scan are both trivial. */
    private const val SIZE = 32

    /** Hue buckets, 15 degrees each. */
    private const val BUCKETS = 24

    /**
     * Below this saturation a pixel is a grey, a white or a black, and says
     * nothing about which app this is. Most icons are mostly those — the
     * padding, the shadow, the white of a glyph — so they have to be excluded
     * or every app averages out to the same muddy neutral.
     */
    private const val MIN_SAT = 0.25f

    /** Below this brightness, hue is not reliably perceptible. */
    private const val MIN_VAL = 0.15f

    /** Pixels dimmer or more transparent than this contribute nothing. */
    private const val MIN_ALPHA = 128

    private val cache = HashMap<String, Int?>()

    /**
     * The dominant hue of [pixels] in ARGB, or null if there is no colour in
     * them worth calling dominant.
     *
     * Bucketed by hue and weighted by saturation times value, rather than
     * counting pixels: the largest area of an icon is usually its background,
     * and on a great many icons that background is white. What identifies an
     * app is the colour it uses *emphatically*, not the colour it uses most.
     *
     * Null for a monochrome icon rather than an arbitrary answer — plenty of
     * apps have black-and-white marks, and inventing a hue for them would be
     * worse than the hash, which at least distinguishes them from each other.
     */
    /**
     * RGB to hue/saturation/value, written out rather than taken from
     * `android.graphics.Color`.
     *
     * That class is a stub in unit tests — the build sets
     * `unitTests.isReturnDefaultValues`, so `colorToHSV` would quietly write
     * nothing and leave every pixel looking like black. The scan would then
     * discard all of them and return null for every icon ever passed in, and a
     * test written against it would be asserting on a function that never ran.
     * Twenty lines is a fair price for an algorithm that can be checked.
     */
    private fun toHsv(argb: Int): FloatArray? {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val d = max - min
        val h = when {
            d == 0f -> 0f
            max == r -> 60f * (((g - b) / d) % 6f)
            max == g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }
        return floatArrayOf(((h % 360f) + 360f) % 360f, if (max == 0f) 0f else d / max, max)
    }

    fun dominantHue(pixels: IntArray): Int? {
        val weights = FloatArray(BUCKETS)
        val sinSum = FloatArray(BUCKETS)
        val cosSum = FloatArray(BUCKETS)
        for (p in pixels) {
            if ((p ushr 24) < MIN_ALPHA) continue
            val hsv = toHsv(p) ?: continue
            if (hsv[1] < MIN_SAT || hsv[2] < MIN_VAL) continue
            val b = ((hsv[0] / 360f) * BUCKETS).toInt().coerceIn(0, BUCKETS - 1)
            val w = hsv[1] * hsv[2]
            weights[b] += w
            // Averaged as angles, because hue wraps: the mean of 350 and 10 is
            // 0, not 180, and reds sit exactly on that seam.
            val rad = Math.toRadians(hsv[0].toDouble())
            sinSum[b] += (Math.sin(rad) * w).toFloat()
            cosSum[b] += (Math.cos(rad) * w).toFloat()
        }
        // Scored over each bucket *and its two neighbours*, because a bucket
        // edge is an arbitrary line through a continuous quantity and a single
        // colour routinely straddles one. Red is the case that forces this: it
        // sits on the 0/360 seam, so a red mark splits across the first and
        // last buckets and each half competes alone — a smaller but tidier
        // block of some other colour then wins a vote red should have taken.
        var best = -1
        var bestScore = 0f
        for (i in 0 until BUCKETS) {
            val score = weights[(i + BUCKETS - 1) % BUCKETS] + weights[i] +
                weights[(i + 1) % BUCKETS]
            if (best < 0 || score > bestScore) {
                best = i
                bestScore = score
            }
        }
        if (best < 0 || bestScore <= 0f) return null
        // Averaged as angles over the same three buckets: hue wraps, so the
        // mean of 350 and 10 is 0, not 180.
        var s = 0f
        var c = 0f
        for (d in -1..1) {
            val i = (best + d + BUCKETS) % BUCKETS
            s += sinSum[i]
            c += cosSum[i]
        }
        if (s == 0f && c == 0f) return null
        val deg = Math.toDegrees(Math.atan2(s.toDouble(), c.toDouble()))
        return ((deg.toInt() % 360) + 360) % 360
    }

    /**
     * The hue of [pkg]'s launcher icon, or null when it cannot be read.
     *
     * Null is the ordinary case, not an error: without package visibility the
     * platform reports the package as simply not installed, which is exactly
     * what it is from here. Cached because it cannot change while the app is
     * installed, and because this is called on a focus change.
     */
    fun hueOf(context: Context, pkg: String?): Int? {
        if (pkg.isNullOrEmpty()) return null
        cache[pkg]?.let { return it }
        if (cache.containsKey(pkg)) return null
        val hue = try {
            val icon = context.packageManager.getApplicationIcon(pkg)
            val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            icon.setBounds(0, 0, SIZE, SIZE)
            icon.draw(canvas)
            val px = IntArray(SIZE * SIZE)
            bmp.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
            bmp.recycle()
            dominantHue(px)
        } catch (_: Exception) {
            null
        }
        cache[pkg] = hue
        return hue
    }

    /** Installing or updating an app can change its icon; the process outlives
     *  that, so the cache is dropped whenever memory is being reclaimed anyway. */
    fun clearCache() = cache.clear()
}
