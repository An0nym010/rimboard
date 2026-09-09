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
 * in the old language beside twenty-two that are not — wrong on screen, with
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

    @Test
    fun `every view that draws an icon has attached them`() {
        // Icons.vector() answers null until attach() has supplied a Context,
        // and draw() then falls back to the hand-drawn glyph. Before the
        // redesign that was invisible -- the two paths drew one picture. Now
        // it is the whole old icon set, on whichever surface got there first.
        // The failure needs no exception and logs nothing, so the guard has to
        // be structural.
        val dir = at("src/main/java/com/rimboard/keyboard/ui",
                     "app/src/main/java/com/rimboard/keyboard/ui")
        val offenders = dir.listFiles()!!
            .filter { it.name.endsWith(".kt") && it.name != "Icons.kt" }
            .filter { it.readText().contains("Icons.draw(") }
            .filter { !it.readText().contains("Icons.attach(") }
            .map { it.name }
        assertTrue(
            "these draw icons but never attach them, so they render the " +
                "hand-drawn set unless some other view happened to attach " +
                "first: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `NOTICE names exactly the icons that are still Lucide`() {
        // Attribution drifts silently and in the direction that matters: the
        // 23 replaced here stopped being Lucide the moment their path data
        // was, and a NOTICE still claiming the whole directory would be
        // crediting a project for work it did not do -- while a *new* Lucide
        // icon added without a line here would be the licence breach in the
        // other direction.
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
            "NOTICE's Lucide list and the files whose header still says Lucide " +
                "have diverged",
            stillLucide, named
        )
    }
}
