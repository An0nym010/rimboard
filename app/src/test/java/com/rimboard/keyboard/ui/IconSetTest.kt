package com.rimboard.keyboard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * That every tool draws the artwork the app actually ships.
 *
 * There are now **two** icon sets behind [Icons]: the VectorDrawables in
 * `res/drawable`, and the hand-drawn `Canvas` glyphs [Icons.draw] falls back
 * to. Until the redesign landed those were two renderings of one design, so a
 * missing `vectorRes` entry cost nothing and showed nothing. They are two
 * different designs now, and a tool that never reaches its drawable is drawn
 * in the old language beside twenty-seven that are not — wrong on screen, with
 * nothing failing and nothing logged.
 *
 * That is the fault shape this file exists for, and it is the same one the
 * other wiring tests here guard: **an absent mapping has no behaviour to
 * observe.** No test that executes code can see a table entry that was never
 * written, and `vectorRes` is private and filled by [Icons.attach], which
 * needs a `Context` no JVM test has. So this reads the source, as
 * `StripLegibilityWiringTest` and `IncognitoSessionTest` do.
 *
 * `src/main/res` and the Kotlin are already declared inputs of the test task;
 * `../NOTICE` had to be added for the last test below, which is the seventh
 * time this project has learned that a test reading an undeclared file is
 * decoration. See the comment on it in `app/build.gradle.kts`.
 */
class IconSetTest {

    private fun at(vararg candidates: String): File {
        for (c in candidates) {
            val f = File(c)
            if (f.exists()) return f
        }
        throw AssertionError(
            "none of ${candidates.toList()} found from ${File(".").absolutePath}"
        )
    }

    private fun source(rel: String) =
        at("src/main/java/$rel", "app/src/main/java/$rel").readText()

    private fun drawableDir() = at("src/main/res/drawable", "app/src/main/res/drawable")

    private val icons by lazy { source("com/rimboard/keyboard/ui/Icons.kt") }
    private val catalog by lazy { source("com/rimboard/keyboard/ui/ToolCatalog.kt") }

    /** Icon constant name -> drawable file name, as [Icons.attach] wires it. */
    private fun vectorMap(): Map<String, String> =
        Regex("""vectorRes\[(\w+)] = R\.drawable\.(\w+)""")
            .findAll(icons)
            .associate { it.groupValues[1] to it.groupValues[2] }

    /** Tool id -> icon constant name, as the catalog declares it. */
    private fun catalogIcons(): List<Pair<String, String>> =
        Regex("""Tool\("([\w-]+)",\s*Icons\.(\w+)""")
            .findAll(catalog)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()

    private fun toolDrawables(): Set<String> =
        drawableDir().listFiles()!!
            .map { it.name }
            .filter { it.startsWith("ic_tool_") && it.endsWith(".xml") }
            .map { it.removeSuffix(".xml") }
            .toSet()

    @Test
    fun `the source scan still finds what it is scanning for`() {
        // A regex that matches nothing passes every assertion below it. This
        // is the check that the other three are not vacuous: the catalog is
        // parsed here and also loaded for real, and the two have to agree.
        assertEquals(
            "the Tool(...) scan no longer sees the whole catalog, so every " +
                "assertion in this file is now vacuous",
            ToolCatalog.all.size, catalogIcons().size
        )
        assertEquals(
            "the parsed tool ids do not match the catalog's own",
            ToolCatalog.all.map { it.id }, catalogIcons().map { it.first }
        )
        assertTrue("no vectorRes entries were found at all", vectorMap().size > 20)
    }

    @Test
    fun `every tool in the catalog draws a vector`() {
        val mapped = vectorMap()
        val missing = catalogIcons()
            .filter { it.second !in mapped }
            .map { "${it.first} (Icons.${it.second})" }
        assertTrue(
            "these tools have no drawable and fall back to the hand-drawn " +
                "glyph, which is the pre-redesign artwork: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `the drawables and the wiring account for each other`() {
        val files = toolDrawables()
        val mapped = vectorMap().values.toSet()
        assertEquals(
            "wired to a drawable that is not there -- this compiles only " +
                "while the R field exists, so it means the file was deleted",
            emptySet<String>(), mapped - files
        )
        assertEquals(
            "shipped in the APK and drawn by nothing. Either wire it in " +
                "Icons.attach or delete it; an icon added and left unwired is " +
                "how the redesign nearly shipped with proofread still " +
                "hand-drawn",
            emptySet<String>(), files - mapped
        )
    }

    /**
     * That there is only one icon set left to draw.
     *
     * This used to scan every file in `ui/` for an `Icons.draw` without an
     * `Icons.attach`, because `vector()` answered null until something had
     * supplied a `Context` and `draw` then fell back to a hand-drawn glyph --
     * which after the redesign was *different artwork*, on whichever surface
     * got there first, with nothing thrown and nothing logged.
     *
     * `Icons.draw` takes the `Context` now, so the compiler asks the question
     * this test used to. What is left to guard is that nobody puts the second
     * set back: a `when (icon)` of drawing commands inside `Icons`, reachable
     * when a drawable does not load, is the shape that made a missing table
     * entry invisible.
     */
    @Test
    fun `there is no second icon set to fall back to`() {
        assertTrue(
            "Icons.draw no longer takes a Context, so a caller can reach the " +
                "table before anything has attached it -- which is what let " +
                "the hand-drawn set onto the screen",
            Regex("""fun draw\([^)]*context: Context""").containsMatchIn(icons)
        )
        assertTrue(
            "Icons has a canvas-drawing fallback again. Two sets behind one " +
                "table is how the bar and the panel drew different artwork " +
                "for the same tool.",
            !icons.contains("grid24") && !icons.contains("Paint.Style.STROKE")
        )
    }

    @Test
    fun `NOTICE names exactly the icons that are still third-party`() {
        // The answer is now none of them: all 28 are RimBoard's own and
        // NOTICE's Lucide section has gone with the last five. An empty list
        // is the easiest kind to leave stale, which is when a check like this
        // earns its keep -- drop a borrowed icon into the directory without
        // the header and this fails until either the file says so or NOTICE
        // does. It also fails the other way, holding NOTICE to the artwork
        // rather than letting it credit a project for work it did not do.
        val dir = drawableDir()
        val stillLucide = toolDrawables()
            .filter { !File(dir, "$it.xml").readText().contains("Original work, not Lucide") }
            .map { "$it.xml" }
            .toSortedSet()
        val notice = at("../NOTICE", "NOTICE").readText()
        val named = Regex("""ic_tool_\w+\.xml""").findAll(notice)
            .map { it.value }
            .toSortedSet()
        assertEquals(
            "NOTICE and the icon headers disagree about what is third-party",
            stillLucide, named
        )
    }
}
