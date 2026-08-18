package com.rimboard.keyboard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Nothing may hardcode the application id.
 *
 * The two flavors used to share one, so writing `com.rimboard.keyboard` into a
 * resource was harmless and several places did. They no longer share it — the
 * builds are two apps now, installable side by side — and every one of those
 * literals became a reference to the *other* build.
 *
 * The failures are quiet. A preference whose `<intent>` names a package that
 * is not installed does nothing when tapped; a screen telling you to run
 * `dumpsys package <name>` sends you to inspect a build you are not running,
 * on the screen whose whole purpose is checking the one you are.
 *
 * Class names are exempt and deliberately so: `com.rimboard.keyboard.settings
 * .SettingsActivity` is the *namespace*, which the suffix does not touch, and
 * `method.xml` and `spellchecker.xml` must name it in full.
 */
class ApplicationIdTest {

    private fun res(): File =
        listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    @Test
    fun `no resource hardcodes the application id`() {
        val offenders = mutableListOf<String>()
        res().walkTopDown()
            .filter { it.isFile && it.extension == "xml" }
            .forEach { f ->
                f.readLines().forEachIndexed { i, line ->
                    val at = line.indexOf("com.rimboard.keyboard")
                    if (at < 0) return@forEachIndexed
                    val after = line.substring(at + "com.rimboard.keyboard".length)
                    // A class reference continues with a dotted lower-case
                    // package and an upper-case type; the bare id does not.
                    val isClassName = Regex("""^(\.[a-z][A-Za-z0-9_]*)*\.[A-Z]""").containsMatchIn(after)
                    if (!isClassName) offenders += "${f.name}:${i + 1}: ${line.trim()}"
                }
            }
        assertTrue(
            "these name the application id, which differs between flavors:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }
}
