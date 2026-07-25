package com.rimboard.keyboard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the rule that every way of producing input asks the search router
 * first.
 *
 * The pickers sit above the keyboard rather than replacing it, so the keys stay
 * live while one is open and what they type belongs to its search box rather
 * than to the app behind it. That check was originally written at one entry
 * point out of four, and the three that were missed meant a *held* backspace
 * deleted the user's actual message while a tapped one edited the query.
 *
 * A unit test cannot drive an InputMethodService, so this reads the source the
 * way `NetGateTest` reads it for network calls: the failure being guarded
 * against is someone adding a fifth entry point and not knowing this seam
 * exists, and that is visible in the text.
 */
class InputRoutingTest {

    private fun serviceSource(): String {
        for (p in listOf(
            "src/main/java/com/rimboard/keyboard/RimBoardService.kt",
            "app/src/main/java/com/rimboard/keyboard/RimBoardService.kt"
        )) {
            val f = File(p)
            if (f.isFile) return f.readText()
        }
        throw AssertionError("RimBoardService not found from ${File(".").absolutePath}")
    }

    /**
     * The callbacks through which a keystroke can arrive, and how each one is
     * expected to consult the router.
     *
     * `onGlideComplete` is listed separately because it resolves a whole word
     * rather than a key, so it routes characters instead of asking whether a
     * key was consumed.
     */
    private val keyEntryPoints = listOf(
        "override fun onKeyPressed",
        "override fun onKeyRepeated",
        "override fun onPopupKeySelected"
    )

    /** The body of a function, from its declaration to the next one. */
    private fun bodyOf(src: String, decl: String): String {
        val start = src.indexOf(decl)
        assertTrue("$decl not found — was it renamed?", start >= 0)
        val next = src.indexOf("\n    override fun ", start + decl.length)
        val end = if (next < 0) src.length else next
        return src.substring(start, end)
    }

    @Test
    fun `every key entry point offers the keystroke to the search router first`() {
        val src = serviceSource()
        val missing = keyEntryPoints.filterNot { bodyOf(src, it).contains("consumedBySearch(") }
        assertTrue(
            "these produce input but never ask consumedBySearch, so they will " +
                "type into the app behind an open picker:\n" + missing.joinToString("\n"),
            missing.isEmpty()
        )
    }

    @Test
    fun `the router is consulted before any typing state is touched`() {
        // The guard has to come first. Half-updating composing text, stats or
        // undo history for a keystroke the text field never receives leaves
        // the editor believing something was typed that was not.
        val body = bodyOf(serviceSource(), "override fun onKeyPressed")
        val guard = body.indexOf("consumedBySearch(")
        for (sideEffect in listOf("wordUndo.clear()", "Stats.key(", "backspaceRepeats =")) {
            val at = body.indexOf(sideEffect)
            if (at < 0) continue
            assertTrue(
                "\"$sideEffect\" runs before the search-router guard in onKeyPressed",
                guard in 0 until at
            )
        }
    }

    @Test
    fun `glide routes into the query rather than committing to the field`() {
        val body = bodyOf(serviceSource(), "override fun onGlideComplete")
        assertTrue(
            "onGlideComplete must hand its word to the search box while a " +
                "picker is open, not commit it to the app behind one",
            body.contains("routeCharToSearch(")
        )
        // The commit has to be after the guard, or the word lands in the field
        // and *then* gets typed into the query as well.
        val guard = body.indexOf("searchRoute != SearchRoute.NONE")
        val commit = body.indexOf("ic.commitText(")
        assertTrue("the glide guard must precede the commit", guard in 0 until commit)
    }
}
