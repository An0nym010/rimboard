package com.rimboard.keyboard.model

/**
 * Whether two taps count as one double-tap.
 *
 * Three lines, pulled out of [com.rimboard.keyboard.RimBoardService] because
 * the service is an `InputMethodService` and cannot run on a plain JVM, which
 * left both users of this rule — double-space-to-period and double-tap-shift
 * for caps lock — with no way to be tested at all. Each of them silently
 * rewrites what the user typed, so "untestable" was the wrong place for them
 * to live.
 *
 * Both callers must pass a *monotonic* clock (`SystemClock.uptimeMillis`).
 * Wall-clock time is not monotonic: an NTP correction or the user changing the
 * clock moves it backwards, and a negative gap reads as "well under the
 * window". That is why the lower bound below is checked rather than assumed —
 * it is the last line of defence if a caller ever passes the wrong clock again.
 */
object TapTiming {

    /**
     * True when [last] was a real tap and [now] falls inside [windowMs] of it.
     *
     * [last] of 0 means "no previous tap" — the value both fields start at and
     * are reset to once the gesture has fired or been broken. Under wall-clock
     * time the difference from 0 was astronomically large and the window
     * comparison rejected it by accident; on a boot-relative clock it is not,
     * so the sentinel is tested for what it means.
     */
    fun isDoubleTap(now: Long, last: Long, windowMs: Long): Boolean {
        if (last == NEVER) return false
        val gap = now - last
        return gap >= 0 && gap < windowMs
    }

    /** The "no previous tap" value for a timestamp field. */
    const val NEVER = 0L
}
