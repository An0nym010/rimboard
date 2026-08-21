package com.rimboard.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The two builds are two apps, and their launcher icons have to say which.
 *
 * `offline` and `online` install side by side with the same name and the same
 * keyboard symbol, so the only thing telling them apart on a home screen is
 * the word under the symbol. That word is a per-flavour resource
 * (`ic_launcher_wordmark`), and the failure it can have is silent: a flavour
 * that stops supplying one would fall back to whatever `src/main` holds and
 * ship an icon that is either unmarked or -- worse -- marked as the other
 * build. So `src/main` deliberately holds no fallback, and this pins that.
 *
 * The second thing pinned is geometry. An adaptive icon is authored on a
 * 108x108 viewport of which a launcher is only guaranteed to show a circle of
 * radius 33 about the centre; masks vary by device and several crop harder
 * than the square they appear to. Artwork drifting outside that circle is not
 * a build error and not visible on the machine that drew it -- it is a word
 * with its ends shaved off on somebody else's phone.
 */
class LauncherIconTest {

    private fun app(): File =
        listOf(File("src"), File("app/src")).first { it.isDirectory }

    /** The source sets that become APKs: everything but main and the tests. */
    private fun flavours(): List<File> =
        app().listFiles()!!
            .filter { it.isDirectory && it.name !in setOf("main", "test", "androidTest") }
            .sortedBy { it.name }

    private fun wordmark(flavour: File) = File(flavour, "res/drawable/ic_launcher_wordmark.xml")

    @Test
    fun `every flavour marks its own icon`() {
        val missing = flavours().filter { !wordmark(it).isFile }
        assertTrue(
            "these builds would ship an unmarked launcher icon: " +
                missing.joinToString { it.name },
            missing.isEmpty()
        )
        assertTrue("expected at least two flavours to tell apart", flavours().size >= 2)
    }

    @Test
    fun `main holds no wordmark to fall back to`() {
        // With one here, a flavour that lost its own would still link, and the
        // mistake would only show up on a home screen.
        val fallback = File(app(), "main/res/drawable/ic_launcher_wordmark.xml")
        assertTrue(
            "src/main must not define ic_launcher_wordmark: it would let a " +
                "flavour ship the wrong word instead of failing to build",
            !fallback.exists()
        )
    }

    @Test
    fun `the flavours do not carry the same word`() {
        // Guarded, not asserted: a flavour with no wordmark at all is the
        // test above's finding, and crashing here as well only buries it.
        val words = flavours().filter { wordmark(it).isFile }
            .associate { it.name to wordmark(it).readText() }
        assertEquals(
            "two flavours with identical wordmarks are two icons nobody can tell apart",
            words.values.size, words.values.toSet().size
        )
    }

    @Test
    fun `the foreground composes the symbol with the flavour word`() {
        val fg = File(app(), "main/res/drawable/ic_launcher_foreground.xml").readText()
        for (ref in listOf("@drawable/ic_launcher_symbol", "@drawable/ic_launcher_wordmark")) {
            assertTrue("the icon foreground no longer references $ref", ref in fg)
        }
    }

    @Test
    fun `no icon artwork leaves the adaptive-icon safe zone`() {
        val files = (flavours().map { wordmark(it) } +
            File(app(), "main/res/drawable/ic_launcher_symbol.xml")).filter { it.isFile }
        val offenders = mutableListOf<String>()
        for (f in files) {
            val worst = f.readText().let { text ->
                PATH_DATA.findAll(text).map { furthestFromCentre(it.groupValues[1]) }.maxOrNull()
            } ?: 0.0
            if (worst > SAFE_RADIUS) offenders += "${f.name}: reaches %.1f".format(worst)
        }
        assertTrue(
            "artwork outside radius $SAFE_RADIUS of (54,54) can be cropped by a " +
                "launcher's mask:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    /**
     * Walks the subset of path syntax these icons are drawn with -- absolute
     * moves and lines, relative horizontals and verticals -- and returns how
     * far the furthest point strays from the centre of the viewport.
     */
    private fun furthestFromCentre(d: String): Double {
        var x = 0.0
        var y = 0.0
        var worst = 0.0
        for (m in COMMAND.findAll(d)) {
            val arg = m.groupValues[2]
            when (m.groupValues[1]) {
                "M", "L" -> {
                    val (a, b) = arg.split(",").map { it.toDouble() }
                    x = a; y = b
                }
                "h" -> x += arg.toDouble()
                "v" -> y += arg.toDouble()
                else -> continue
            }
            worst = maxOf(worst, Math.hypot(x - 54.0, y - 54.0))
        }
        return worst
    }

    private companion object {
        /** What a launcher mask is guaranteed to show, in viewport units. */
        const val SAFE_RADIUS = 33.0
        val PATH_DATA = Regex("""android:pathData="([^"]*)"""")
        val COMMAND = Regex("""([MLhvz])([-0-9.,]*)""")
    }
}
