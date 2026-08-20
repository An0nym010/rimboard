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

    /**
     * A file's code with its comments removed.
     *
     * The scan below looks for an identifier by name, and the first version of
     * it read the whole file — so it flagged the very comment explaining why
     * the identifier is not used any more. A check that reports prose as a
     * violation is the kind that gets deleted for crying wolf, and then guards
     * nothing at all.
     *
     * Block comments and line comments, and nothing cleverer. A `//` inside a
     * string literal would take the rest of that line with it, which can only
     * ever hide an offender rather than invent one — the safe direction for a
     * scan to be wrong in.
     */
    private fun codeOf(f: File): String =
        f.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""//[^\n]*"""), " ")

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
    fun `targeting Android 15 or later means tinting the bar a different way`() {
        // Unlike the system-UI flag field, this one has no replacement call and
        // is *not* deprecated at the SDK this app compiles against — which is
        // why the compiler says nothing about it and why it must not simply be
        // deleted. Confirmed against the Android 15 behaviour-changes page:
        //
        //   - The deprecation applies only to apps targeting 35 or above.
        //   - Under gesture navigation it is deprecated *and disabled*.
        //   - Under 3-button navigation it is deprecated but **still works**,
        //     at 80% alpha, defaulting to the window background.
        //
        // So at the bump the keyboard keeps its tinted bar for 3-button users
        // and silently loses it for everyone on gestures, which is most people
        // and is the sort of half-loss nobody files a bug about. The migration:
        //
        //   - give the window a *colour drawable* background matching the
        //     theme, which is what the 3-button default reads;
        //   - for gesture navigation, draw the background behind the bar
        //     yourself, sized from WindowInsets.Type.tappableElement(), which
        //     reports the 3-button bar height and zero under gestures — the
        //     one inset type that distinguishes the two at runtime.
        //
        // isNavigationBarContrastEnforced(false) is already set and stays.
        val target = targetSdk()
        if (target < 35) return
        val tints = kotlinSources().any { codeOf(it).contains("navigationBarColor") }
        if (!tints) return
        val protects = kotlinSources().any { codeOf(it).contains("tappableElement") }
        assertTrue(
            "targetSdk is now $target and navigationBarColor is still what tints\n" +
                "the bar. That API is disabled under gesture navigation from 35 and\n" +
                "keeps working only for 3-button, so the tint quietly disappears for\n" +
                "most users.\n" +
                "\n" +
                "Nothing here reads WindowInsets.Type.tappableElement(), which is the\n" +
                "inset that tells the two navigation modes apart at runtime. Give the\n" +
                "window a colour-drawable background for the 3-button default, and\n" +
                "draw your own background behind the bar for gestures.",
            protects
        )
    }

    @Test
    fun `the system bars are driven through the controller, not the flag field`() {
        // decorView.systemUiVisibility was deprecated at API 30 in favour of
        // WindowInsetsController, and the reason this is worth a scan rather
        // than a comment is that the old spelling keeps working for years
        // before it stops. It compiles, it does the right thing on every
        // device below 30, and it quietly does nothing after the target-SDK
        // bump the test above is holding the door on — so a reintroduction
        // would look correct in review, behave correctly in testing, and be
        // discovered by a user on a new phone.
        //
        // WindowCompat.getInsetsController picks setSystemBarsAppearance where
        // it exists and sets the same flag below it, so there is never a reason
        // to reach for the field directly.
        val offenders = kotlinSources()
            .filter { f ->
                val t = codeOf(f)
                t.contains("systemUiVisibility") || t.contains("SYSTEM_UI_FLAG_")
            }
            .map { it.name }
        assertTrue(
            "these reach for the deprecated system-UI flag field instead of\n" +
                "WindowCompat.getInsetsController(window, view):\n  " +
                offenders.joinToString("\n  "),
            offenders.isEmpty()
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
