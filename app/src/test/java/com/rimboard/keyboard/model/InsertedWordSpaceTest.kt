package com.rimboard.keyboard.model

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The space the keyboard adds after a word it inserted itself.
 *
 * "Autospace after picking a suggestion" is a real setting on the Advanced
 * screen, and three paths append that space: the swipe commit, tapping a
 * suggestion, and tapping a *different* suggestion after a swipe. Two of them
 * consulted it and one did not, and a fourth place -- the same replacement
 * path, reading rather than writing -- assumed the space was always there.
 *
 * With the setting off that comes apart in a way nobody would connect to a
 * checkbox:
 *
 *  1. a swipe writes "hello " with the space, ignoring the setting;
 *  2. tapping an alternative matches "hello ", and writes "hallo" without one,
 *     because that line does read the setting;
 *  3. tapping a second alternative looks for "hallo " and finds "hallo", so it
 *     gives up -- and clears the alternatives on its way out, so the strip
 *     loses them too.
 *
 * The visible result is that correcting a swiped word works once and then
 * stops, with the suggestions disappearing. It survived because the setting
 * defaults to on, where every path agrees by accident.
 *
 * The invariant is that what one path writes after an inserted word is exactly
 * what the other expects to find there, so both now come from one expression.
 */
class InsertedWordSpaceTest {

    /** Unit tests run from the module directory; tolerate the project root too. */
    private fun src(): File =
        listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }

    private fun svc(): String =
        src().resolve("com/rimboard/keyboard/RimBoardService.kt").readText()

    private fun bodyOf(name: String): String {
        val s = svc()
        val start = s.indexOf("private fun $name(")
        assertTrue("$name is gone; this scan needs rewriting", start >= 0)
        val next = s.indexOf("\n    private fun ", start + 10)
        return s.substring(start, if (next < 0) s.length else next)
    }

    @Test
    fun `every path uses one expression for the trailing space`() {
        val s = svc()
        val uses = s.split("insertedWordTail()").size - 1
        assertTrue(
            "expected the swipe commit, the suggestion tap, and both halves of " +
                "the replacement to share one expression; found $uses uses",
            uses >= 4
        )
    }

    @Test
    fun `the replacement expects what it wrote`() {
        // The half that reads. A hard-coded space here is the bug: it is only
        // correct while every writer happens to add one.
        val body = bodyOf("replaceLastGlideWith")
        assertTrue(
            "replaceLastGlideWith still looks for a hard-coded space, so with " +
                "the setting off it cannot find the word it just wrote",
            !body.contains("val expect = \"\$old \"")
        )
        assertTrue(
            "the expectation is not built from the shared expression",
            body.contains("insertedWordTail()")
        )
    }

    @Test
    fun `no expectation hard-codes the space`() {
        // Counting the uses was not enough, and the device said so. The scan
        // below found four and passed while a *fifth* place -- the staleness
        // check in onUpdateSelection -- still looked for a literal space, and
        // that one runs on the selection change the commit itself causes, so
        // it wiped the alternatives before the strip drew them once.
        //
        // So the shape to refuse is the shape, not the count: an expectation
        // about what follows an inserted word must never be a string literal
        // with a space in it.
        val hardCoded = Regex("""val expect = "\$""").findAll(svc()).count()
        assertTrue(
            "$hardCoded expectation(s) still built from a string literal; " +
                "every one must come from insertedWordTail()",
            hardCoded == 0
        )
    }

    @Test
    fun `the swipe commit reads the setting`() {
        val s = svc()
        assertTrue(
            "the swipe commit appends a space unconditionally, so the setting " +
                "governs tapping a suggestion but not swiping one",
            !s.contains("ic.commitText(\"\$lead\$best \", 1)")
        )
    }
}
