package com.rimboard.keyboard.net

import android.content.Context
import android.content.pm.PackageManager
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Evidence, rather than reassurance, that the offline build is offline.
 *
 * A settings screen that reads "no network access ✓" because the app decided
 * to draw that is worth nothing — it is the app vouching for itself, which is
 * exactly what someone worried about a keyboard should not accept. So this
 * does two things the user can check independently:
 *
 *  1. reads back the permission list of the installed APK from
 *     [PackageManager]. That list comes from the system's copy of the
 *     manifest, not from a constant in our code, and it is the same list
 *     `adb shell dumpsys package` and `aapt dump permissions` will show;
 *  2. actually tries to open a TCP connection, and reports whatever the OS
 *     says back, verbatim.
 *
 * The second one is the point. On the offline build the connection is refused
 * by the kernel with `EACCES (Permission denied)` — the app tried, in front of
 * the user, and was not allowed. That is a demonstration; "we promise not to"
 * is not. It also means this screen cannot lie on the online build: there the
 * same probe connects, and says so.
 */
object NetProbe {

    /**
     * A literal address, never a hostname. Resolving a name needs the network
     * too, so a DNS failure would be indistinguishable from a socket refusal
     * and would muddy the very thing the probe is trying to isolate.
     * 1.1.1.1:443 is Cloudflare's resolver, reachable from essentially anywhere
     * that has a working connection.
     */
    private const val PROBE_HOST = "1.1.1.1"
    private const val PROBE_PORT = 443
    private const val PROBE_TIMEOUT_MS = 5_000

    data class Report(
        /** Every permission this APK declares, straight from the system. */
        val declaredPermissions: List<String>,
        val hasInternetPermission: Boolean,
        /** What the OS did with a real connection attempt. */
        val probe: Probe,
        /** The exact text of the exception, or of the success. */
        val detail: String
    )

    enum class Probe {
        /** The kernel refused the socket: no INTERNET permission for this UID. */
        REFUSED_BY_OS,

        /** The socket opened. This build can reach the internet. */
        CONNECTED,

        /**
         * Neither — no route, airplane mode, a captive portal. Says nothing
         * either way about permissions, and is reported as inconclusive rather
         * than being quietly counted as proof of being offline.
         */
        INCONCLUSIVE
    }

    /**
     * Blocking, and must not be called on the main thread: Android raises
     * `NetworkOnMainThreadException` for any socket work there, and that would
     * pre-empt the permission check the probe is trying to observe — producing
     * a failure that looks like proof but was caused by the thread we asked
     * from.
     */
    fun run(c: Context): Report {
        val declared = declaredPermissions(c)
        val hasInternet = declared.contains(android.Manifest.permission.INTERNET)

        var probe: Probe
        var detail: String
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(PROBE_HOST, PROBE_PORT), PROBE_TIMEOUT_MS)
            }
            probe = Probe.CONNECTED
            detail = "Connected to $PROBE_HOST:$PROBE_PORT"
        } catch (e: Throwable) {
            val msg = "${e.javaClass.name}: ${e.message}"
            detail = msg
            // EACCES is the kernel telling us this UID is not in the inet
            // group — the signature of a missing INTERNET permission. Matched
            // on the text because Android surfaces it as a plain
            // SocketException with the errno in the message, with no typed
            // form to check against.
            probe = if (msg.contains("EACCES") || msg.contains("Permission denied")) {
                Probe.REFUSED_BY_OS
            } else {
                Probe.INCONCLUSIVE
            }
        }
        return Report(declared, hasInternet, probe, detail)
    }

    private fun declaredPermissions(c: Context): List<String> = try {
        c.packageManager
            .getPackageInfo(c.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.toList()
            .orEmpty()
            .sorted()
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * The one-line verdict, kept next to the evidence that produced it so the
     * two cannot drift apart.
     *
     * A build with no permission whose probe was merely inconclusive still
     * reads as offline — the permission is the guarantee and the probe is the
     * demonstration of it — but the screen shows both lines, so the user can
     * see which of the two they are being asked to weigh.
     */
    fun verdict(r: Report): Verdict = when {
        !r.hasInternetPermission && r.probe == Probe.REFUSED_BY_OS -> Verdict.PROVEN_OFFLINE
        !r.hasInternetPermission -> Verdict.OFFLINE_BY_PERMISSION
        r.probe == Probe.CONNECTED -> Verdict.ONLINE
        else -> Verdict.ONLINE_IDLE
    }

    enum class Verdict {
        /** No permission, and the OS was watched refusing a real attempt. */
        PROVEN_OFFLINE,

        /** No permission; the probe could not run to confirm it. */
        OFFLINE_BY_PERMISSION,

        /** Holds the permission and just used it. */
        ONLINE,

        /** Holds the permission; nothing reachable at the moment. */
        ONLINE_IDLE
    }
}
