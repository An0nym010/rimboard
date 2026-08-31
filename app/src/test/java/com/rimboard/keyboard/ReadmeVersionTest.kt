package com.rimboard.keyboard

import com.rimboard.keyboard.model.Languages
import org.junit.Assert.assertEquals
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

    /**
     * The download size the README quotes, against the assets that make it up.
     *
     * The README's first bullet tells a reader what they are about to download,
     * and it said 29 MB while the release APK was 32.95 -- four megabytes and
     * fourteen per cent understated, on the one number somebody decides with.
     * It drifted because the assets grew: the prediction models were made
     * denser for thirteen languages and put two megabytes on, and before that
     * the dictionaries were widened.
     *
     * Nothing had bounded the sum. Every asset family has a cap of its own --
     * `TOP` words per dictionary, `MAX_ROWS` per prediction model, a per-file
     * count for the inventories -- and every one of them was respected while
     * the total went up by four megabytes. Which is the same shape as the
     * footprint tests weighing a dictionary and a model separately and nobody
     * adding them: the parts were all in bounds and the whole was never asked
     * about.
     *
     * So the sum is what is pinned, and the README figure is pinned to the
     * constant beside it. Growing the assets past the ceiling fails here, and
     * the message says to re-measure the APK and move both numbers together --
     * which is the moment at which somebody is thinking about the trade
     * anyway.
     *
     * Assets are 75.1 MB uncompressed and the APK is 32.95, measured
     * 2026-08-31 with `assembleOfflineRelease`; both flavours are the same
     * size to two decimals.
     */
    @Test
    fun `the README's download size is the one the assets add up to`() {
        val readme = File(root(), "README.md").readText()
        val m = Regex("""(\d+) MB, nearly all of it dictionaries""").find(readme)
        assertTrue(
            "the README no longer states a download size in the form " +
                "\"NN MB, nearly all of it dictionaries\"; a reader decides " +
                "on that number and nothing else here can check it",
            m != null
        )
        assertEquals(
            "the README's download size and the figure recorded here have " +
                "come apart. One of them is a measurement of the release APK " +
                "and the other is a claim to a reader; they have to move " +
                "together.",
            README_APK_MB, m!!.groupValues[1].toInt()
        )

        var bytes = 0L
        File(root(), "app/src/main/assets").walkTopDown()
            .filter { it.isFile }
            .forEach { bytes += it.length() }
        val mb = bytes / 1048576.0
        println("shipped assets: %.2f MB against a ceiling of %.0f".format(mb, ASSET_CEILING_MB))
        assertTrue("no assets were found at all", mb > 10.0)
        assertTrue(
            ("the shipped assets are now %.1f MB, past the %.0f the APK size in " +
                "the README was measured against. Rebuild a release APK, put " +
                "its real size in the README and in README_APK_MB, and raise " +
                "this ceiling deliberately -- that is the decision this test " +
                "exists to force rather than prevent.")
                .format(mb, ASSET_CEILING_MB),
            mb <= ASSET_CEILING_MB
        )
    }

    /**
     * The languages the README names, against the ones that ship.
     *
     * "22 languages built in" appears three times and the full list of names
     * appears once, in `Languages.all` order. Nothing checked any of it, so
     * adding a language would leave a reader three stale counts and a list
     * missing the language they came looking for -- and the person adding it
     * has no reason to think about the README at all, because everything else
     * about a new language is code and assets.
     *
     * The names come from the JDK rather than from a table here, so this
     * cannot drift into being its own opinion about what Croatian is called.
     */
    @Test
    fun `the README names every language that ships, and counts them right`() {
        val readme = File(root(), "README.md").readText()
        val n = Languages.codes.size
        val counts = Regex("""(\d+) languages""").findAll(readme).map { it.groupValues[1] }.toList()
        assertTrue(
            "the README no longer says how many languages ship; it said it in " +
                "three places and a reader counts on all three",
            counts.isNotEmpty()
        )
        assertEquals(
            "the README's language count is not the number that ship",
            List(counts.size) { n.toString() }, counts
        )
        val missing = Languages.all
            .map { it.locale.getDisplayLanguage(java.util.Locale.ENGLISH) }
            .filterNot { readme.contains(it) }
        assertEquals(
            "these languages ship and the README's list does not name them, so " +
                "somebody looking for their own language is told it is absent",
            emptyList<String>(), missing
        )
    }

    private companion object {
        /** What the README tells a reader they are downloading. */
        const val README_APK_MB = 33

        /**
         * Room for a language or a denser model, not for a second corpus.
         *
         * 75.1 MB today. Eighty is about one more prediction model's worth of
         * growth, which is enough that ordinary work does not trip it and
         * little enough that four megabytes of drift cannot happen unnoticed
         * again.
         */
        const val ASSET_CEILING_MB = 80.0
    }

}
