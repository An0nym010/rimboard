package com.rimboard.keyboard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A keyboard that targets Android 15 has to handle window insets itself.
 *
 * # Why this is a ratchet rather than a fix
 *
 * From Android 15, an app targeting SDK 35 or above is laid out edge to edge
 * whether it asks to be or not, and the system stops reserving space for the
 * navigation bar. For most apps that means a banner tucked under a status bar.
 * For an input method it means the bottom row of keys sits *underneath* the
 * navigation bar, which is the row with the space bar in it.
 *
 * This is not a hypothetical. It is the single largest cluster of open bug
 * reports against the nearest comparable project — a keyboard obscured at the
 * bottom, wrong bottom padding on first launch, too much space on Android 15 —
 * and every one of them arrived with a target-SDK bump rather than with a code
 * change.
 *
 * RimBoard targets 34 today and is therefore insulated: the system still
 * reserves the space, and nothing here needs to do anything. That will not
 * last. Google Play requires an app to target within a year of the current
 * major release to stay updatable, so the bump is coming whether or not
 * anybody plans it, and it is exactly the kind of change that gets made in a
 * one-line commit titled "bump target sdk".
 *
 * So this test does nothing at all today and fails the moment that line
 * changes without the insets work being done alongside it. The failure message
 * is the handover: it says what to do and why, at the moment somebody is
 * actually in a position to do it.
 *
 * # Why the fix is not simply written now
 *
 * Because it cannot be checked from here. Padding a keyboard correctly means
 * knowing what the navigation bar is doing on a real device — three-button
 * versus gesture, portrait versus landscape, floating mode, split screen, a
 * cutout — and none of that is observable on a JVM. Writing it blind and
 * declaring it done would be worse than an honest ratchet, because it would
 * look finished. See the device-testing item in the project's notes.
 */
class TargetSdkInsetsTest {

    /** Unit tests run from the module directory; tolerate the project root too. */
    private fun moduleFile(name: String): File =
        listOf(File(name), File("app/$name")).first { it.isFile }

    private fun kotlinSources(): List<File> =
        listOf(File("src/main/java"), File("app/src/main/java"))
            .first { it.isDirectory }
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    private fun targetSdk(): Int {
        val text = moduleFile("build.gradle.kts").readText()
        val m = Regex("""targetSdk\s*=\s*(\d+)""").find(text)
        // A build file that no longer states one is itself worth failing on:
        // the number this guards would have moved somewhere this cannot see.
        assertTrue("no targetSdk found in build.gradle.kts", m != null)
        return m!!.groupValues[1].toInt()
    }

    /**
     * Whether anything in the app reads window insets.
     *
     * Deliberately a presence check and nothing more, in the manner of the
     * other source scans here. Whether the padding is *correct* is a question
     * for a device; whether anybody has thought about it at all is a question
     * a scan can answer exactly, and the failure this exists to stop is having
     * given it no thought whatsoever.
     */
    private fun handlesInsets(): Boolean =
        kotlinSources().any { f ->
            val t = f.readText()
            t.contains("setOnApplyWindowInsetsListener") ||
                t.contains("onApplyWindowInsets") ||
                t.contains("WindowInsetsCompat")
        }

    @Test
    fun `targeting Android 15 or later means handling insets`() {
        val target = targetSdk()
        if (target < 35) return
        assertTrue(
            "targetSdk is now $target. From SDK 35 the system no longer reserves\n" +
                "space for the navigation bar, so the bottom row of keys — the one\n" +
                "with the space bar — will be drawn underneath it.\n" +
                "\n" +
                "Nothing in this app reads window insets. Before shipping this bump:\n" +
                "  - apply the bottom navigation-bar inset as padding on the input view\n" +
                "  - check three-button and gesture navigation, both orientations\n" +
                "  - check floating mode and split screen, which change the anchor\n" +
                "\n" +
                "This is the largest cluster of bug reports against comparable\n" +
                "keyboards and it arrives with the target bump, not before it.",
            handlesInsets()
        )
    }

    @Test
    fun `the assumption this rests on is still true`() {
        // Guards the guard. If targetSdk were read as 0 — a renamed property, a
        // move to a version catalog, a different build file — the test above
        // would return early forever and this ratchet would be decoration.
        assertTrue(
            "targetSdk read as ${targetSdk()}, which is not a real Android level",
            targetSdk() >= 26
        )
    }
}
