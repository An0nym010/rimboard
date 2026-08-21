package com.rimboard.keyboard.net

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The online flavor's backend — the only place in the app that opens a socket.
 *
 * `HttpURLConnection` rather than a client library: the README's claim to be a
 * single small APK with no heavyweight dependencies is worth more than the
 * ergonomics of OkHttp, and the shape of what this does — one request, one
 * string back — does not need more.
 *
 * By the time anything gets here [Net] has already checked the permission, the
 * user's mode, incognito, and the host allowlist. This function deliberately
 * repeats none of those checks; splitting a policy across two files is how one
 * copy of it ends up out of date. What it does add is the transport hardening
 * that has to live next to the connection: timeouts, a redirect refusal, and a
 * response cap.
 */
internal object NetBackend {

    const val CAPABLE = true

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000

    /**
     * 8 MB. Sized for the largest things this app legitimately fetches — a GIF,
     * and an extended dictionary — rather than for text; a page of search
     * metadata or a rewritten sentence is orders of magnitude under it either
     * way.
     *
     * Declared in [Net] so the data can be checked against it: the shipped
     * dictionary manifest is verified to fit, and it is the only flavour-shared
     * code that could do that.
     */
    private const val MAX_RESPONSE_BYTES = Net.MAX_RESPONSE_BYTES

    /**
     * Bytes are the transport; [fetch] is a decode on top of it. Written this
     * way round so text and images cannot drift apart in their timeouts,
     * redirect handling, or size cap — there is one connection path here, not
     * two that merely look alike.
     */
    /**
     * Whether the device has a network at all — the one thing
     * `ACCESS_NETWORK_STATE` is declared for. Lets a failure say "your phone is
     * offline" rather than showing a socket timeout and leaving the user to
     * guess whether the keyboard, the API key, or the wifi is at fault.
     *
     * Null on any error rather than false: "we could not tell" and "there is
     * definitely no network" lead to different messages.
     */
    fun deviceOnline(c: android.content.Context): Boolean? = try {
        val cm = c.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager
        val active = cm.activeNetwork
        if (active == null) false
        else cm.getNetworkCapabilities(active)
            ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false
    } catch (_: Exception) {
        null
    }

    fun fetchBytes(url: String, body: String?, headers: Map<String, String>): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            // A 30x is the one way a host on the allowlist can hand the
            // connection to one that is not. Following it would route around
            // the check Net just made, so the request fails instead.
            instanceFollowRedirects = false
            requestMethod = if (body == null) "GET" else "POST"
            for ((k, v) in headers) setRequestProperty(k, v)
        }
        try {
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            if (code in 300..399) {
                throw IOException("refusing redirect from ${URL(url).host} (HTTP $code)")
            }
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { readCapped(it) } ?: ByteArray(0)
            if (code !in 200..299) {
                // Error bodies are text even when the request wanted an image.
                // Typed rather than a formatted string, so the caller can tell
                // "their server is broken" from "we asked wrongly" instead of
                // showing the body to the user and letting them guess.
                throw HttpStatusException(code, String(bytes, Charsets.UTF_8).take(200))
            }
            return bytes
        } finally {
            conn.disconnect()
        }
    }

    fun fetch(url: String, body: String?, headers: Map<String, String>): String =
        String(fetchBytes(url, body, headers), Charsets.UTF_8)

    /**
     * Reads at most [MAX_RESPONSE_BYTES] and fails rather than truncating: a
     * silently cut-off JSON body would surface as a confusing parse error
     * somewhere else entirely.
     */
    private fun readCapped(input: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > MAX_RESPONSE_BYTES) {
                throw IOException("response larger than ${MAX_RESPONSE_BYTES / (1 shl 20)} MB")
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
