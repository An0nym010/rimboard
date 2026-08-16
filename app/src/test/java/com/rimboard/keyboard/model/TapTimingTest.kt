package com.rimboard.keyboard.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Both users of this rule rewrite what the user typed — a lone space becomes
 * ". ", a single shift becomes caps lock — so every way it can say "yes" when
 * it should say "no" is worth pinning.
 */
class TapTimingTest {

    @Test
    fun `two taps inside the window are a double tap`() {
        assertTrue(TapTiming.isDoubleTap(now = 1200, last = 1000, windowMs = 500))
    }

    @Test
    fun `two taps outside the window are not`() {
        assertFalse(TapTiming.isDoubleTap(now = 1600, last = 1000, windowMs = 500))
        // The boundary belongs to the slow side: exactly the window is a miss.
        assertFalse(TapTiming.isDoubleTap(now = 1500, last = 1000, windowMs = 500))
    }

    @Test
    fun `a clock that steps backwards is not a fast double tap`() {
        // This is the bug the extraction exists for. With wall-clock time an
        // NTP correction or a manual clock change moves `now` behind `last`,
        // and a plain `now - last < window` is then true for any gap at all —
        // so the next space typed turned into ". " on its own.
        assertFalse(TapTiming.isDoubleTap(now = 900, last = 1000, windowMs = 500))
        assertFalse(TapTiming.isDoubleTap(now = 0, last = 5_000_000, windowMs = 500))
    }

    @Test
    fun `the never sentinel is not a tap that just happened`() {
        // 0 means "no previous tap". Under wall-clock time `now` was ~1.7e12 so
        // the subtraction rejected it by luck; on a boot-relative clock, early
        // in the boot, `now` really is a small number and the luck runs out.
        assertFalse(TapTiming.isDoubleTap(now = 100, last = TapTiming.NEVER, windowMs = 500))
        assertFalse(TapTiming.isDoubleTap(now = 0, last = TapTiming.NEVER, windowMs = 500))
        // ...including when the sentinel would otherwise land inside the window.
        assertFalse(TapTiming.isDoubleTap(now = 499, last = TapTiming.NEVER, windowMs = 500))
    }

    @Test
    fun `a real tap at the very start of the clock still counts`() {
        // The sentinel is a value, not a range: 1ms after boot is a legitimate
        // timestamp and must not be swept up with it.
        assertTrue(TapTiming.isDoubleTap(now = 200, last = 1, windowMs = 500))
    }
}
