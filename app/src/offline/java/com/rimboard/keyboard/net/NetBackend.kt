package com.rimboard.keyboard.net

/**
 * The offline flavor's backend: there isn't one.
 *
 * This file is the whole of the network layer in the offline build. It imports
 * nothing, opens nothing, and names no networking type — there is no
 * `HttpURLConnection` and no `Socket` compiled into this flavor outside the
 * deliberate self-test in `NetProbe`, whose only job is to be refused in front
 * of the user.
 *
 * None of that is the actual guarantee, though. The guarantee is that this
 * flavor's APK does not request `android.permission.INTERNET`, so the kernel
 * denies a socket to this UID no matter what the code above it asks for. This
 * object exists so the shared code in [Net] still compiles, not to enforce
 * anything: if it were the enforcement, you would have to trust it.
 */
internal object NetBackend {

    const val CAPABLE = false

    // Signature matched to the online backend so the shared caller compiles
    // against both; nothing here reads the arguments, because nothing here
    // does anything with them.
    /**
     * Always unknown, and deliberately does not ask.
     *
     * This build holds no `ACCESS_NETWORK_STATE`, so querying
     * `ConnectivityManager` would throw — and "unknown" is the truthful answer
     * on a build that could not use a connection if one existed.
     */
    @Suppress("UNUSED_PARAMETER")
    fun deviceOnline(c: android.content.Context): Boolean? = null

    @Suppress("UNUSED_PARAMETER")
    fun fetchBytes(url: String, body: String?, headers: Map<String, String>): ByteArray =
        throw UnsupportedOperationException(
            "This is the offline build of RimBoard: it holds no INTERNET " +
                "permission and has no network backend compiled in."
        )

    @Suppress("UNUSED_PARAMETER")
    fun fetch(url: String, body: String?, headers: Map<String, String>): String =
        throw UnsupportedOperationException(
            "This is the offline build of RimBoard: it holds no INTERNET " +
                "permission and has no network backend compiled in."
        )
}
