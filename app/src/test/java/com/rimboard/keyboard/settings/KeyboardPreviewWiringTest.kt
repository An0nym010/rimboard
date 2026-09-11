package com.rimboard.keyboard.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * That the Look and feel preview shows the keyboard people actually get.
 *
 * The preview is the real `KeyboardView` inside the real `PhotoBackdrop`, fed
 * by the one `KeyboardLook` the service is fed by. Everything below guards that
 * arrangement, because every way of breaking it produces a preview that still
 * *looks* like a keyboard:
 *
 * - a second copy of the look logic drifts from the keyboard silently;
 * - a cold palette cache tints the preview with the hue derived from the
 *   package *name* instead of the app's real colour, which is the bug this
 *   preview shipped with for about twenty minutes;
 * - an unregistered preference listener leaves it frozen at whatever the
 *   settings were when the screen opened.
 *
 * None of those throws. All of them make the preview a picture of a keyboard
 * nobody has, which is worse than having no preview at all.
 */
class KeyboardPreviewWiringTest {

    private fun at(vararg candidates: String): File {
        for (c in candidates) File(c).let { if (it.isFile) return it }
        throw AssertionError("none of ${candidates.toList()} from ${File(".").absolutePath}")
    }

    private fun src(rel: String) =
        at("src/main/java/$rel", "app/src/main/java/$rel").readText()

    private val preview by lazy {
        src("com/rimboard/keyboard/ui/KeyboardPreviewView.kt")
    }
    private val service by lazy { src("com/rimboard/keyboard/RimBoardService.kt") }
    private val settings by lazy {
        src("com/rimboard/keyboard/settings/SettingsActivity.kt")
    }

    @Test
    fun `the preview and the keyboard read the same look`() {
        assertTrue(
            "the preview no longer goes through KeyboardLook, so it has its " +
                "own idea of what the keyboard looks like",
            preview.contains("KeyboardLook.of(") && preview.contains("KeyboardLook.applyTo(")
        )
        assertTrue(
            "the service no longer goes through KeyboardLook, so the shared " +
                "rule is shared with nobody and the preview is the only caller",
            service.contains("KeyboardLook.of(") && service.contains("KeyboardLook.applyTo(")
        )
    }

    /**
     * The look-only properties belong to `KeyboardLook` and to nothing else.
     *
     * A line put back into the service — `kv.keyBorders = ...` written inline
     * because it was quicker — would work perfectly on the keyboard and leave
     * the preview showing the old value. That asymmetry is invisible until
     * somebody compares the two side by side, which is exactly what nobody
     * does.
     */
    @Test
    fun `no look property is set behind the shared helper's back`() {
        val owned = listOf(
            "keyBorders", "narrowGaps", "sidePadPct", "bottomPadPct",
            "labelScale", "customTypeface", "keyHeightFactor", "showDigitHints",
            "splitFraction", "backdropDrawn"
        )
        val stray = owned.filter { service.contains("kv.$it =") }
        assertEquals(
            "these are set on the view directly by the service as well as by " +
                "KeyboardLook, so the preview and the keyboard can disagree " +
                "about them: $stray",
            emptyList<String>(), stray
        )
    }

    @Test
    fun `the preview warms the palette before trusting the tint`() {
        assertTrue(
            "the preview reads the app-colour cache without filling it. The " +
                "settings process has its own cache, cold, so a per-app tint " +
                "falls back to the package-name hue and the preview shows a " +
                "colour the keyboard never takes.",
            preview.contains("KeyboardLook.warm(")
        )
        assertTrue(
            "the warm callback arrives on a background thread and touches " +
                "views, so it has to be posted",
            Regex("""warm\([^)]*\)\s*\{\s*post\s*\{""").containsMatchIn(preview)
        )
    }

    /**
     * The preview is meant to work under a finger and produce nothing.
     *
     * Those are two requirements, not one, and the second is the one that can
     * rot quietly. Every path that would change a document goes out through a
     * listener, so `Inert` has to keep dropping them — a member filled in later
     * "to make the preview more useful" is how a settings screen starts typing
     * into whatever field the user was last in.
     */
    @Test
    fun `the preview is interactive and still writes nothing`() {
        assertTrue(
            "the views have no listener, so the layout keys, shift and the " +
                "tool drawer all do nothing -- the preview is a picture again",
            preview.contains("keyboard.listener = Inert()") &&
                preview.contains("strip.listener = Inert()")
        )
        assertTrue(
            "touches are being swallowed again, which takes the popups and " +
                "the pressed states with them",
            !preview.contains("onInterceptTouchEvent")
        )
        // The members that would carry something out of this view must stay
        // empty. Written as a body check rather than a name check, because an
        // empty override is the whole point.
        val mustBeEmpty = listOf(
            "onGlideComplete(points: FloatArray, keys: String)",
            "onSuggestionPicked(index: Int, word: String)",
            "onQuickAction(code: Int)",
            "onBackspaceWord()",
            "onPopupKeySelected(key: com.rimboard.keyboard.model.Key)",
            "onClipboardPasteRequested()"
        )
        val filled = mustBeEmpty.filter { sig ->
            val i = preview.indexOf(sig)
            i >= 0 && !preview.substring(i + sig.length).trimStart().startsWith("{}")
        }
        assertEquals(
            "these would carry a key press, a suggestion or a tool action out " +
                "of the preview, and there is nowhere for it to go: $filled",
            emptyList<String>(), filled
        )
        assertTrue(
            "the preview reaches for an input connection, which a settings " +
                "screen has no business holding",
            !preview.contains("InputConnection") && !preview.contains("commitText")
        )
    }

    @Test
    fun `the interactive parts are the ones that only move this view`() {
        assertTrue(
            "the layout keys no longer switch anything, so ?123 is dead",
            preview.contains("Codes.MODE_SYM") && preview.contains("applyLayout()")
        )
        assertTrue(
            "shift no longer latches",
            preview.contains("keyboard.shiftState =")
        )
        assertTrue(
            "the chevron no longer opens the drawer",
            preview.contains("strip.setDrawerOpen(expand)")
        )
    }

    @Test
    fun `the preview follows the settings while the screen is open`() {
        assertTrue(
            "nothing listens for preference changes, so the preview is a " +
                "screenshot taken when the screen opened",
            settings.contains("OnSharedPreferenceChangeListener")
        )
        assertTrue(
            "the listener is created at the point of registration rather than " +
                "held in a field. SharedPreferences keeps a weak reference, so " +
                "it is collected at the next GC and the preview quietly stops " +
                "following anything.",
            settings.contains("private val previewWatcher")
        )
        assertTrue(
            "the listener is never unregistered",
            settings.contains("unregisterOnSharedPreferenceChangeListener")
        )
        assertTrue(
            "the preview is not refreshed on resume, so a theme chosen in the " +
                "picker -- a separate activity, which sends no change callback " +
                "to this stopped screen -- does not reach it",
            settings.contains("override fun onResume()") &&
                settings.contains("preview?.refresh()")
        )
    }

    @Test
    fun `the preview is pinned, not a row in the list`() {
        assertTrue(
            "the preview is built somewhere other than onCreateView, so it " +
                "scrolls with the list and leaves you changing a control whose " +
                "effect is off screen",
            settings.contains("override fun onCreateView(") &&
                settings.contains("KeyboardPreviewView(requireContext())")
        )
        assertTrue(
            "the preview is built for every settings screen rather than the " +
                "one it describes",
            settings.contains("ARG_XML, 0) != R.xml.prefs_theme")
        )
    }
}
