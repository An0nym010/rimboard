package com.rimboard.keyboard.net

import android.content.Context
import com.rimboard.keyboard.settings.Prefs

/**
 * The one door to the network.
 *
 * Nothing in this app opens a connection on its own. Every feature that wants
 * the network calls [fetch] here, and [fetch] can refuse for four separate
 * reasons before a socket is ever considered:
 *
 *  1. the build has no INTERNET permission ([capable] is false on the offline
 *     flavor, where [NetBackend] has no networking code in it at all);
 *  2. the user picked fully-offline in the first-run dialog or in Settings;
 *  3. the request would carry text the user typed, and the keyboard is in
 *     incognito;
 *  4. the caller asked for a host that is not on [ALLOWED_HOSTS].
 *
 * Funnelling everything through one function is what makes the online build
 * auditable: `NetGateTest` asserts that no other file in the app references a
 * networking API, so reading this file is enough to know every place the
 * keyboard can talk to. That is a much weaker claim than the offline build's —
 * there the kernel enforces it — and the UI never presents it as more.
 */
object Net {

    const val MODE_OFFLINE = "offline"
    const val MODE_ONLINE = "online"

    /**
     * Hosts this app is allowed to contact, checked against the resolved URL
     * rather than a prefix of the string, so `https://evil.test/?x=tenor.com`
     * cannot slip through. A feature that wants a new endpoint has to be added
     * here in a diff someone can read.
     */
    val ALLOWED_HOSTS = setOf(
        "api.klipy.com",                    // GIF search metadata
        "api.anthropic.com",                // AI translation and proofreading
        "lingva.ml",                        // keyless translation
        "libretranslate.com"                // translation, self-hostable
    )

    /**
     * Domains allowed in full, for services that spread one job across many
     * hostnames. KLIPY serves media from `static`, `static1` and `static2`
     * under `klipy.com`, so an exact list would break the first time they
     * added `static3` — and it would break as a blank grid, which reads as the
     * feature being broken rather than the allowlist being stale.
     *
     * Deliberately a *suffix on a dot*, not a substring: see [hostAllowed].
     * Kept to domains an endpoint above already talks to, so this widens which
     * machines answer, never which company does.
     */
    val ALLOWED_SUFFIXES = setOf("klipy.com")

    /**
     * Whether [host] may be contacted.
     *
     * The suffix test matches either the domain itself or something below a
     * dot within it. Without the dot, `evilklipy.com` would pass; without the
     * equality case, `klipy.com` itself would not. Both directions are pinned
     * by `NetGateTest`.
     */
    internal fun hostAllowed(host: String?, userHost: String? = null): Boolean {
        if (host == null) return false
        if (host in ALLOWED_HOSTS) return true
        if (userHost != null && host == userHost) return true
        return ALLOWED_SUFFIXES.any { host == it || host.endsWith(".$it") }
    }

    /** True only on the `online` flavor, whose manifest carries INTERNET. */
    val capable: Boolean get() = NetBackend.CAPABLE

    /**
     * Whether the first-run network dialog has been dealt with yet. Absence of
     * the key is the signal, not its value — "offline" is a real answer and
     * must not read as "never asked".
     *
     * True on both flavors once dismissed. The offline build still shows the
     * dialog, as a statement rather than a question: someone who installed the
     * wrong APK should find that out on first launch, not the first time a
     * feature is missing.
     */
    fun chosen(c: Context): Boolean = Prefs.get(c).contains(Prefs.KEY_NET_MODE)

    /**
     * The mode in force. On the offline flavor this is [MODE_OFFLINE] no matter
     * what is stored — a build with no permission cannot be talked into going
     * online by a stale preference, or by a backup restored from an online
     * install.
     */
    fun mode(c: Context): String {
        if (!capable) return MODE_OFFLINE
        return Prefs.get(c).getString(Prefs.KEY_NET_MODE, MODE_OFFLINE) ?: MODE_OFFLINE
    }

    fun setMode(c: Context, mode: String) {
        Prefs.get(c).edit().putString(Prefs.KEY_NET_MODE, mode).apply()
    }

    /**
     * Why a request would be refused right now, or null if it would be allowed.
     *
     * Exposed so the UI can grey out a feature and say which of the four
     * reasons applies, instead of letting the user tap it and watch it fail.
     */
    fun blockedBy(c: Context, sendsTypedText: Boolean): Block? = when {
        !capable -> Block.NO_PERMISSION
        mode(c) != MODE_ONLINE -> Block.USER_OFFLINE
        sendsTypedText && incognito(c) -> Block.INCOGNITO
        else -> null
    }

    fun allowed(c: Context, sendsTypedText: Boolean): Boolean =
        blockedBy(c, sendsTypedText) == null

    /**
     * Perform a request, or refuse and say why.
     *
     * [reason] is a short human-readable label for what the request is for. It
     * is required rather than optional because it is what the network log shows
     * the user, and a log of bare URLs would not tell them anything they could
     * act on.
     *
     * [sendsTypedText] must be true for any request whose body or query carries
     * something the user typed. It is what wires these features into incognito:
     * a GIF search is typed text, a request for a static config file is not.
     *
     * Blocking; callers are responsible for being off the main thread.
     */
    fun fetch(
        c: Context,
        url: String,
        reason: String,
        sendsTypedText: Boolean,
        body: String? = null,
        headers: Map<String, String> = emptyMap()
    ): Result<String> = guarded(c, url, reason, sendsTypedText) {
        NetBackend.fetch(url, body, headers)
    }

    /**
     * The same door, for responses that are not text — currently GIF bytes.
     *
     * Separate from [fetch] only in what it hands back. Every check runs in
     * [guarded], so an image request cannot end up on a laxer path than a text
     * one; adding a second copy of the policy here is exactly the mistake this
     * file exists to prevent.
     */
    fun fetchBytes(
        c: Context,
        url: String,
        reason: String,
        sendsTypedText: Boolean,
        headers: Map<String, String> = emptyMap()
    ): Result<ByteArray> = guarded(c, url, reason, sendsTypedText) {
        NetBackend.fetchBytes(url, null, headers)
    }

    /** Permission, user mode, incognito, host allowlist, logging — in one place. */
    private fun <T> guarded(
        c: Context,
        url: String,
        reason: String,
        sendsTypedText: Boolean,
        transport: () -> T
    ): Result<T> {
        blockedBy(c, sendsTypedText)?.let {
            NetLog.record(c, reason, url, NetLog.Outcome.REFUSED, it.name)
            return Result.failure(NetBlockedException(it))
        }
        val host = hostOf(url)
        // The one entry that is not in the source: a translation instance the
        // user is hosting themselves. Nobody could have listed their address in
        // advance, so it is read back from the setting where they typed it —
        // matched exactly, never as a suffix. The network screen shows it
        // alongside the static list so the effective allowlist stays visible.
        if (!hostAllowed(host, Translate.customHost(c))) {
            NetLog.record(c, reason, url, NetLog.Outcome.REFUSED, "HOST_NOT_ALLOWED")
            return Result.failure(NetBlockedException(Block.HOST_NOT_ALLOWED))
        }
        return try {
            val out = transport()
            NetLog.record(c, reason, url, NetLog.Outcome.SENT, null)
            Result.success(out)
        } catch (e: Exception) {
            NetLog.record(c, reason, url, NetLog.Outcome.FAILED, e.javaClass.simpleName)
            Result.failure(e)
        }
    }

    /**
     * Parsed rather than pattern-matched, and rejected unless the scheme is
     * https: a plaintext request from a keyboard would put whatever it carries
     * in front of every device on the network.
     */
    internal fun hostOf(url: String): String? = try {
        val u = java.net.URI(url)
        if (!u.scheme.equals("https", ignoreCase = true)) null else u.host?.lowercase()
    } catch (_: Exception) {
        null
    }

    /**
     * Whether the device itself currently has a network, or null when this
     * build cannot know.
     *
     * This is what `ACCESS_NETWORK_STATE` is declared for. It exists so a
     * failure can say "your phone is offline" instead of showing the user a
     * socket timeout and leaving them to guess whether the keyboard, the key,
     * or the café wifi is at fault.
     *
     * Returns null rather than false on the offline flavor: that build holds
     * no `ACCESS_NETWORK_STATE`, so asking would throw, and "unknown" is the
     * honest answer there rather than a guess.
     *
     * Implemented per flavor rather than guarded by [capable] here. A runtime
     * guard is invisible to lint, which correctly objected to a call needing
     * `ACCESS_NETWORK_STATE` sitting in code that the permission-less build
     * also compiles. Putting it behind the same seam as [NetBackend.fetch]
     * keeps every permission-requiring line inside the flavor that holds it.
     */
    fun deviceOnline(c: Context): Boolean? = NetBackend.deviceOnline(c)

    private fun incognito(c: Context): Boolean =
        Prefs.incognitoAlways(c) || Prefs.incognitoSession(c)

    enum class Block { NO_PERMISSION, USER_OFFLINE, INCOGNITO, HOST_NOT_ALLOWED }
}

class NetBlockedException(val block: Net.Block) :
    java.io.IOException("network refused: ${block.name}")

/**
 * A non-2xx answer, carrying the status separately from the body.
 *
 * The message was previously built as `"HTTP $code: ${body.take(200)}"` and
 * shown to the user as-is, which put a raw JSON fragment from someone else's
 * server into the translate bar — clipped mid-word, because the bar is two
 * lines tall. That tells the user nothing they can act on and reads as the
 * keyboard having broken.
 *
 * The status is what decides what to say, and [detail] stays available for the
 * log without being the thing on screen.
 */
class HttpStatusException(val code: Int, val detail: String) :
    java.io.IOException("HTTP $code: $detail") {

    /** 5xx is the provider's fault and worth retrying; 4xx is not. */
    val isServerFault: Boolean get() = code in 500..599

    /** Asked to slow down, which is a wait rather than a failure. */
    val isRateLimited: Boolean get() = code == 429
}

/**
 * A record of every request the app has attempted, for the network screen.
 *
 * RAM-only and capped, on the same reasoning as the clipboard history: a
 * durable list of what someone searched for is itself the kind of thing this
 * keyboard exists not to keep. The lifetime counter is separate and does
 * persist, because "0 requests since you installed this" is worth more than a
 * list that resets with the process — and a counter carries no content.
 */
object NetLog {

    enum class Outcome { SENT, REFUSED, FAILED }

    data class Entry(
        val at: Long,
        val reason: String,
        val host: String,
        val outcome: Outcome,
        val detail: String?
    )

    private const val MAX = 50

    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun record(c: Context, reason: String, url: String, outcome: Outcome, detail: String?) {
        // The URL is deliberately reduced to its host before being stored: the
        // path and query of a GIF search are the user's search terms.
        val host = Net.hostOf(url) ?: "?"
        entries.addFirst(Entry(System.currentTimeMillis(), reason, host, outcome, detail))
        while (entries.size > MAX) entries.removeLast()
        if (outcome == Outcome.SENT) bump(c)
    }

    @Synchronized
    fun recent(): List<Entry> = entries.toList()

    /**
     * Requests actually put on the wire since install.
     *
     * Read through here rather than from a field the caller refreshes. It used
     * to be an in-memory counter that the settings screen loaded on open and
     * saved on close — but the keyboard *service* is what increments it, and
     * never saved. Opening Settings -> Network therefore overwrote the live
     * count with a stale one and silently under-reported, on the one screen
     * whose entire job is to be trustworthy about this number.
     *
     * Now: seeded from storage once per process, authoritative in memory after
     * that, and persisted on every increment.
     */
    @Volatile
    private var count = 0
    private var loaded = false

    @Synchronized
    private fun ensureLoaded(c: Context) {
        if (loaded) return
        count = Prefs.get(c).getInt(Prefs.KEY_NET_SENT, 0)
        loaded = true
    }

    fun sentCount(c: Context): Int {
        ensureLoaded(c)
        return count
    }

    private fun bump(c: Context) {
        ensureLoaded(c)
        count++
        Prefs.get(c).edit().putInt(Prefs.KEY_NET_SENT, count).apply()
    }
}
