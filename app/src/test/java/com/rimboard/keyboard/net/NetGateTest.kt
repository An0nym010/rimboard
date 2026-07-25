package com.rimboard.keyboard.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the two claims the README makes about network access.
 *
 * Both are the kind of property that is true when written and quietly stops
 * being true six commits later, with nothing failing to say so: someone adds a
 * feature, reaches for `URL(...).openConnection()` because it is right there in
 * the JDK, and the promise that every request goes through [Net] is gone
 * without anyone noticing. That is what the source scan below is for.
 *
 * The manifest tests cover the stronger claim — that the offline APK does not
 * request INTERNET at all — which is the one the whole two-flavor split exists
 * to keep.
 */
class NetGateTest {

    /** Unit tests run from the module directory; tolerate the project root too. */
    private fun src(): File {
        for (p in listOf("src", "app/src")) {
            val f = File(p)
            if (f.isDirectory) return f
        }
        throw AssertionError("source directory not found from ${File(".").absolutePath}")
    }

    /**
     * Ways to reach the network from a plain Android app. Not exhaustive
     * against a determined author — nothing textual could be — but it covers
     * every route someone would take without meaning to hide it, which is the
     * failure this is guarding against.
     */
    private val networkApis = listOf(
        "HttpURLConnection",
        "URLConnection",
        "openConnection",
        "java.net.Socket",
        "SocketChannel",
        "DatagramSocket",
        "okhttp",
        "OkHttpClient",
        "Retrofit",
        "HttpClient",
        "URL(" // java.net.URL, whose only real use is opening one
    )

    /**
     * The files allowed to name a networking API, and why.
     *
     * `NetBackend` is the backend itself. `NetProbe` deliberately opens a
     * socket in order to be refused in front of the user — it is the evidence
     * on the network screen, and it exists in both flavors for that reason.
     * Anything else appearing here should be read as a change of policy, not a
     * change of implementation.
     */
    private val exempt = setOf("NetBackend.kt", "NetProbe.kt")

    private fun ktFiles(dir: File): List<File> =
        dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `no file outside the gate touches a networking API`() {
        val roots = listOf(File(src(), "main"), File(src(), "online"), File(src(), "offline"))
        val offenders = mutableListOf<String>()
        for (root in roots) {
            if (!root.isDirectory) continue
            for (f in ktFiles(root)) {
                if (f.name in exempt) continue
                val text = f.readText()
                // Comments discuss these names constantly — this file included.
                // Strip them so the scan judges code rather than prose.
                val code = text
                    .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
                    .lines().filterNot { it.trimStart().startsWith("//") }
                    .joinToString("\n")
                for (api in networkApis) {
                    if (code.contains(api)) offenders.add("${f.name} uses $api")
                }
            }
        }
        assertTrue(
            "network access must go through Net.fetch, but:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test
    fun `the main manifest does not request INTERNET`() {
        val main = File(src(), "main/AndroidManifest.xml").readText()
        assertFalse(
            "INTERNET belongs in src/online only — putting it in the main " +
                "manifest gives it to the offline build too",
            main.contains("android.permission.INTERNET")
        )
    }

    @Test
    fun `the offline flavor contributes no manifest`() {
        // Its absence is the guarantee. A manifest here would be the one place
        // a permission could be added back to the offline APK.
        assertFalse(
            "the offline flavor must not declare a manifest",
            File(src(), "offline/AndroidManifest.xml").exists()
        )
    }

    @Test
    fun `the online flavor requests INTERNET`() {
        val online = File(src(), "online/AndroidManifest.xml").readText()
        assertTrue(online.contains("android.permission.INTERNET"))
    }

    @Test
    fun `only https urls on the allowlist are accepted`() {
        // The check is on the parsed host, so neither a lookalike host nor the
        // allowed name appearing elsewhere in the URL gets through.
        assertEquals("api.klipy.com", Net.hostOf("https://api.klipy.com/api/v1/KEY/gifs/search?q=cat"))
        assertEquals("evil.test", Net.hostOf("https://evil.test/?x=api.klipy.com"))
        assertFalse(Net.hostAllowed(Net.hostOf("https://evil.test/?x=api.klipy.com")))
        assertFalse(Net.hostAllowed(Net.hostOf("https://api.klipy.com.evil.test/")))

        // Plaintext is refused outright: a keyboard's traffic is the last thing
        // that should be readable by everything between here and the server.
        assertNull(Net.hostOf("http://api.klipy.com/v1/gifs/search"))
        assertNull(Net.hostOf("not a url"))
    }

    @Test
    fun `every allowed host is a bare hostname`() {
        // A scheme or path in this set would make the membership test above
        // silently never match, disabling the feature rather than the check.
        for (h in Net.ALLOWED_HOSTS) {
            assertEquals("allowlist entries must be bare hosts: $h", h.lowercase(), h)
            assertFalse("allowlist entries must be bare hosts: $h", h.contains("/"))
            assertFalse("allowlist entries must be bare hosts: $h", h.contains(":"))
        }
    }
}
