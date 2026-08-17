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

    /**
     * Guards both caches.
     *
     * They are written from [prefetch]'s worker and read from the typing path,
     * and a `HashMap` resized under one thread while another reads it does not
     * fail cleanly. Not a `ConcurrentHashMap`, because "this package has no
     * answer" is itself a cached result and that map cannot hold a null value —
     * losing the distinction would mean re-reading a whole resource table on
     * every focus change into an app that had already declined to answer.
     */
    private val lock = Any()

    private val cache = HashMap<String, Int?>()

    /**
     * The apps whose icons are read under the default "curated" setting.
     *
     * The point of a list rather than a switch is that it is finite and can be
     * read: on this setting the keyboard looks at these and at nothing else,
     * whatever else the manifest makes visible to it. Everything absent keeps
     * the package-name hue, which is what the whole feature did before.
     *
     * Chosen as the apps people spend their typing in — messaging first, then
     * the social and work apps a keyboard is opened in every day. It is not
     * meant to be complete; "All apps" is the setting for that.
     */
    val CURATED = setOf(
        // messaging
        "com.whatsapp", "com.whatsapp.w4b",
        "org.telegram.messenger", "org.thunderdog.challegram",
        "org.thoughtcrime.securesms", "com.viber.voip", "jp.naver.line.android",
        "com.tencent.mm", "com.facebook.orca", "com.google.android.apps.messaging",
        "com.discord", "com.skype.raider", "im.vector.app", "org.telegram.plus",
        // social
        "com.instagram.android", "com.facebook.katana", "com.facebook.lite",
        "com.twitter.android", "com.snapchat.android", "com.reddit.frontpage",
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
        "com.pinterest", "com.linkedin.android", "com.tumblr",
        // work and mail
        "com.google.android.gm", "com.microsoft.office.outlook",
        "com.microsoft.teams", "com.Slack", "com.notion.id",
        "com.google.android.apps.docs", "com.google.android.keep",
        "com.todoist", "com.trello",
        // browsers and the rest
        "com.android.chrome", "org.mozilla.firefox",
        "com.google.android.youtube", "com.spotify.music",
        "com.duolingo", "com.medium.reader"
    )

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
    /**
     * The hue of [pkg]'s own declared primary colour, or null.
     *
     * Tried before the icon, because it is a better answer to "the app's main
     * colour": an icon is a picture and its dominant hue may be a detail, while
     * `colorPrimary` is the colour the app *chose* to paint itself. It is also
     * the colour the user is looking at while they type, which is the whole
     * point of matching it.
     *
     * `colorPrimary` exists twice over. The framework attribute is resolved by
     * a fixed id; AppCompat and Material define their own, and those live in
     * the *target app's* resource namespace with an id only that app's
     * resources can tell us — hence the lookup by name. Modern apps overwhelm-
     * ingly use the second, so trying only the first would miss most of them.
     *
     * Needs no permission beyond the package visibility the tint already
     * requires: reading another app's resources is not reading its data.
     */
    private fun themeHue(context: Context, pkg: String): Int? = try {
        val pm = context.packageManager
        val info = pm.getApplicationInfo(pkg, 0)
        val res = pm.getResourcesForApplication(info)
        val theme = res.newTheme()
        theme.applyStyle(info.theme, true)
        var found: Int? = null
        for (name in listOf("colorPrimary", "colorPrimaryDark", "colorAccent")) {
            val attr = res.getIdentifier(name, "attr", pkg)
            val ids = if (attr != 0) intArrayOf(attr) else when (name) {
                "colorPrimary" -> intArrayOf(android.R.attr.colorPrimary)
                "colorPrimaryDark" -> intArrayOf(android.R.attr.colorPrimaryDark)
                else -> intArrayOf(android.R.attr.colorAccent)
            }
            val ta = theme.obtainStyledAttributes(ids)
            val c = try { ta.getColor(0, 0) } finally { ta.recycle() }
            // A transparent or unset value is not an answer, and a grey is not
            // a brand colour — plenty of themes leave colorPrimary near-white.
            if (c != 0 && (c ushr 24) > 0) {
                found = dominantHue(intArrayOf(c or (0xFF shl 24)))
                if (found != null) break
            }
        }
        found
    } catch (_: Exception) {
        null
    }

    private val lightCache = HashMap<String, Boolean?>()

    /**
     * Whether [pkg] presents itself as a light theme, or null if it cannot be
     * told.
     *
     * Two questions, asked in order. `isLightTheme` is the app declaring the
     * answer and is exact where it is set; where it is not, the luminance of
     * the window background is what the user is actually looking at, which is
     * the thing being matched. A theme that answers neither gets null, and the
     * keyboard then keeps following the system as before rather than guessing.
     *
     * Needs no permission beyond the package visibility the tint already
     * requires — reading another app's theme is not reading its data.
     */
    private fun computeIsLight(context: Context, pkg: String, curatedOnly: Boolean): Boolean? {
        if (curatedOnly && pkg !in CURATED) return null
        return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(pkg, 0)
            val res = pm.getResourcesForApplication(info)
            val theme = res.newTheme()
            theme.applyStyle(info.theme, true)
            val declared = theme.obtainStyledAttributes(
                intArrayOf(android.R.attr.isLightTheme)
            ).let { ta ->
                try {
                    // -1 rather than a boolean default: "not set" and "set to
                    // false" are different answers and only one of them means
                    // the app is dark.
                    val v = ta.getInt(0, -1)
                    if (v == -1) null else v != 0
                } finally {
                    ta.recycle()
                }
            }
            declared ?: run {
                val ta = theme.obtainStyledAttributes(
                    intArrayOf(android.R.attr.windowBackground)
                )
                val bg = try { ta.getColor(0, 0) } finally { ta.recycle() }
                // A windowBackground that is a drawable rather than a colour
                // reads as 0 here, which is not an answer.
                if (bg == 0 || (bg ushr 24) == 0) null else luminanceOf(bg) > 0.5f
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun luminanceOf(c: Int): Float {
        val r = (c shr 16 and 0xFF) / 255f
        val g = (c shr 8 and 0xFF) / 255f
        val b = (c and 0xFF) / 255f
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    private fun computeHue(context: Context, pkg: String, curatedOnly: Boolean): Int? {
        if (curatedOnly && pkg !in CURATED) return null
        // The declared theme colour first, the icon second. Both are the app
        // describing itself; the theme is the more direct statement of it.
        return themeHue(context, pkg) ?: try {
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
    }

    /** Installing or updating an app can change its icon; the process outlives
     *  that, so the cache is dropped whenever memory is being reclaimed anyway. */
    fun clearCache() = synchronized(lock) {
        cache.clear()
        lightCache.clear()
    }

    /** Whether both answers for [pkg] are already known, so nothing is needed. */
    private fun known(key: String): Boolean = synchronized(lock) {
        cache.containsKey(key) && lightCache.containsKey(key)
    }

    private fun cacheKey(pkg: String, curatedOnly: Boolean) =
        if (curatedOnly) "c:$pkg" else "a:$pkg"

    /**
     * The hue for [pkg] if it has already been worked out, without doing any
     * work now. Null means "not yet", not "no colour".
     */
    fun cachedHue(pkg: String?, curatedOnly: Boolean): Int? {
        if (pkg.isNullOrEmpty()) return null
        return synchronized(lock) { cache[cacheKey(pkg, curatedOnly)] }
    }

    /** As [cachedHue], for the light/dark answer. */
    fun cachedIsLight(pkg: String?, curatedOnly: Boolean): Boolean? {
        if (pkg.isNullOrEmpty()) return null
        return synchronized(lock) { lightCache[cacheKey(pkg, curatedOnly)] }
    }

    /**
     * Works out both answers for [pkg] off the main thread, then calls [onReady].
     *
     * Reading another app's theme means `getResourcesForApplication`, which
     * parses that app's whole resource table, and reading its icon means
     * loading a drawable and rasterising it. Both were being done on the IME's
     * main thread inside the focus change, so the first time the keyboard
     * opened in any given app it paid for both while appearing — the exact
     * stall `SuggestionEngine.warm` exists to keep off that path, reintroduced
     * by the back door.
     *
     * The keyboard therefore opens with whatever is already known, which on a
     * first visit is nothing, and is told to reapply once the answers land.
     * Being a frame late with a colour is not something anyone will notice;
     * a keyboard that hesitates when it appears is.
     */
    fun prefetch(context: Context, pkg: String?, curatedOnly: Boolean, onReady: () -> Unit) {
        if (pkg.isNullOrEmpty()) return
        val key = cacheKey(pkg, curatedOnly)
        if (known(key)) return
        val app = context.applicationContext
        Thread {
            try {
                val hue = computeHue(app, pkg, curatedOnly)
                val light = computeIsLight(app, pkg, curatedOnly)
                synchronized(lock) {
                    cache[key] = hue
                    lightCache[key] = light
                }
                android.os.Handler(android.os.Looper.getMainLooper()).post(onReady)
            } catch (e: Exception) {
                // A package that cannot be read is an ordinary outcome, not a
                // failure — but it has to be recorded, or every focus change
                // starts another thread to fail the same way.
                synchronized(lock) {
                    cache[key] = null
                    lightCache[key] = null
                }
                android.util.Log.w("RimBoard", "app palette: $pkg", e)
            }
        }.apply { isDaemon = true }.start()
    }
}
