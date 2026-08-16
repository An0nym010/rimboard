package com.rimboard.keyboard.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every view that schedules delayed work must cancel it when it is detached.
 *
 * Android does not do this for you. `View.postDelayed` puts the runnable on the
 * looper's queue, and detaching the view does not take it back off — it fires
 * on time, into a view that nothing can see any more, and calls whatever
 * listener it was given. In an ordinary app that is a leak; in a keyboard it is
 * a behaviour, because the input view is torn down and rebuilt constantly —
 * every rotation goes through `onConfigurationChanged` → `setInputView`, and
 * that is a path where nothing tells the panels they are going away.
 *
 * The rule was already understood here — four of the six views that post
 * delayed work cancelled it on detach, one of them with a comment describing
 * exactly this hazard. The other two did not, and one of those fires a
 * *billed* network request. That is the shape of invariant worth enforcing
 * rather than remembering: it is invisible when broken, and it is broken by
 * adding a feature rather than by editing the rule.
 *
 * A source scan, in the manner of the network-gate scan, and deliberately only
 * a presence check: it requires the override to exist, not that its body is
 * correct. An earlier draft tried to verify the cancelling too, and had to
 * resolve helper calls to do it — `stopRepeat`, `disarmLift`, `cancelTimers`
 * behind `cancelAll` — because cancelling through a named helper is the normal
 * shape here. It accused two correct files before it accused anything real. A
 * check that cries wolf gets deleted, and then guards nothing at all, so this
 * one asks the question it can answer exactly.
 *
 * That is enough for the failure it exists to stop, because the failure is not
 * a subtly wrong cancel — it is no detach handler whatsoever, which is what
 * both real offenders looked like. Whether the body cancels the right thing is
 * left to review.
 */
class DelayedWorkTest {

    /** Unit tests run from the module directory; tolerate the project root too. */
    private fun src(): File {
        for (p in listOf("src", "app/src")) {
            val f = File(p)
            if (f.isDirectory) return f
        }
        throw AssertionError("source directory not found from ${File(".").absolutePath}")
    }

    private fun kotlinFiles(): List<File> =
        File(src(), "main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `a view that posts delayed work cancels it when detached`() {
        val offenders = mutableListOf<String>()
        for (f in kotlinFiles()) {
            val text = f.readText()
            if (!text.contains("postDelayed")) continue
            // Only views can be detached; a plain class holding a Handler is
            // someone else's responsibility to shut down (see UserData).
            if (!text.contains("fun onDetachedFromWindow")) {
                offenders += "${f.name}: posts delayed work and never overrides onDetachedFromWindow"
            }
        }
        assertTrue(
            "views scheduling delayed work without handling detach:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }
}
