package com.rimboard.keyboard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The version the README claims is the version the build produces.
 *
 * Two places say it, and both are read by someone who needs it to be true: the
 * "What's new" line, and the example of what Settings - About - Version shows,
 * which the same paragraph calls "the one to quote in a bug report". A stale
 * number there sends people to the wrong release notes and puts the wrong
 * version in a report about the right build.
 *
 * It went stale the moment 2.9.0 was cut, and nothing failed. Cutting a release
 * is rare enough that nobody remembers the checklist and common enough to keep
 * happening, which is the shape of thing this repository writes tests for.
 */
class ReadmeVersionTest {

    /** Tests run from the module directory; tolerate the project root too. */
    private fun root(): File =
        listOf(File(".."), File(".")).first { File(it, "README.md").isFile }

    private fun versionName(): String {
        val gradle = File(root(), "app/build.gradle.kts").readText()
        val m = Regex("""versionName\s*=\s*"([^"]+)"""").find(gradle)
        assertTrue("no versionName in app/build.gradle.kts", m != null)
        return m!!.groupValues[1]
    }

    @Test
    fun `the README names the version the build produces`() {
        val v = versionName()
        val readme = File(root(), "README.md").readText()
        assertTrue(
            "README says \"The latest release is ...\" with a version other " +
                "than $v; a reader following it lands on the wrong notes",
            readme.contains("The latest release is **$v**")
        )
        assertTrue(
            "the README's example of Settings - About - Version is not " +
                "`$v-offline`, and that line is what a bug report is asked to " +
                "quote",
            readme.contains("`$v-offline`")
        )
    }

    @Test
    fun `the changelog has a section for it`() {
        val v = versionName()
        val log = File(root(), "CHANGELOG.md").readText()
        assertTrue(
            "CHANGELOG.md has no \"What's new in $v\" section, so the README " +
                "points at notes that do not exist",
            log.contains("## What's new in $v")
        )
    }
}
