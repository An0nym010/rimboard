package com.rimboard.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the shipped APK actually asks for, rather than what the source says.
 *
 * `NetGateTest` asserts that `src/main/AndroidManifest.xml` does not name
 * INTERNET and that `src/online` does. That is the right check for the code in
 * this repository and it cannot see the one route by which a permission
 * arrives without anyone writing it: **manifest merging**. Every dependency
 * contributes its own manifest, and a library that declares INTERNET hands it
 * to whatever includes the library. The offline build would then request it
 * while both source manifests still read exactly as they do now.
 *
 * That matters more here than it would elsewhere, because the README does not
 * describe the offline build as careful. It describes it as unable:
 *
 *   "No INTERNET in the offline build, which is what makes its guarantee a
 *    guarantee rather than a promise."
 *
 * A guarantee enforced by the kernel is only as good as the manifest that
 * reaches it, so this reads the merged manifest -- the one AGP hands to the
 * packager -- and pins the whole set rather than the one permission. The set
 * is the point: the README names each of these and says what it is for, so an
 * addition should have to edit this list and that paragraph together.
 */
class ManifestPermissionsTest {

    /** Named in the README, one paragraph each. */
    private val offline = setOf(
        "android.permission.VIBRATE",
        "android.permission.READ_CONTACTS",
        "android.permission.READ_USER_DICTIONARY"
    )

    /** The online flavor adds exactly these two. */
    private val online = offline + setOf(
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE"
    )

    /** Tests run from the module directory; tolerate the project root too. */
    private fun buildDir(): File =
        listOf(File("build"), File("app/build")).first { it.isDirectory }

    /** Every merged manifest a build has produced, by variant. */
    private fun merged(): List<Pair<String, Set<String>>> {
        val root = File(buildDir(), "intermediates/merged_manifest")
        if (!root.isDirectory) return emptyList()
        return root.listFiles().orEmpty()
            .filter { it.isDirectory }
            .flatMap { variant ->
                variant.walkTopDown()
                    .filter { it.name == "AndroidManifest.xml" }
                    .map { variant.name to permissionsIn(it) }
            }
            // The pre-flavor `debug` directory is left over from before the
            // split and describes no variant that ships.
            .filter { it.first.startsWith("offline") || it.first.startsWith("online") }
    }

    private fun permissionsIn(f: File): Set<String> =
        Regex("""<uses-permission[^>]*android:name="([^"]+)"""")
            .findAll(f.readText())
            .map { it.groupValues[1] }
            .toSet()

    /**
     * The one permission in the shipped manifest that this repository does not
     * write, and the proof that the route exists.
     *
     * AndroidX Core declares a signature-level permission so that the
     * receivers it registers at runtime are not exported, and manifest merging
     * hands it to whatever includes the library. It is namespaced under the
     * application id, so the two flavors carry different names -- which is why
     * this is matched by suffix rather than spelled out.
     *
     * Harmless, and *nowhere in this source tree*. Everything said above about
     * a dependency contributing INTERNET is not hypothetical; this is the same
     * mechanism, already in use, carrying something benign.
     */
    private val fromAndroidX = "DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"

    @Test
    fun `the merged manifest asks for exactly what the README names`() {
        val found = merged()
        assertTrue(
            "no merged manifest under ${buildDir()}/intermediates -- this test " +
                "reads what the packager is handed, so it needs a build to have " +
                "run (any assemble or test task for a flavor produces one)",
            found.isNotEmpty()
        )
        for ((variant, perms) in found) {
            val expected = if (variant.startsWith("offline")) offline else online
            val platform = perms.filter { it.startsWith("android.permission.") }.toSet()
            assertEquals(
                "the $variant manifest requests a different set than the README " +
                    "describes. A permission can arrive here from a dependency's " +
                    "manifest without appearing in this repository at all.",
                expected, platform
            )
            val rest = perms - platform
            assertTrue(
                "$variant carries a non-platform permission this test does not " +
                    "know about: $rest",
                rest.all { it.endsWith(fromAndroidX) }
            )
        }
    }

    @Test
    fun `no offline variant is handed INTERNET by anything`() {
        // Stated separately from the set above because it is the claim the
        // whole two-flavor split exists to keep, and it should fail on its own
        // terms rather than as one line of a set mismatch.
        for ((variant, perms) in merged().filter { it.first.startsWith("offline") }) {
            assertTrue(
                "$variant requests ${perms.filter { "INTERNET" in it || "NETWORK" in it }}, " +
                    "so the offline build's guarantee is now only a promise",
                perms.none { "INTERNET" in it || "NETWORK" in it }
            )
        }
    }
}
