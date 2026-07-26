package com.rimboard.keyboard.net

import android.content.Context
import org.json.JSONObject

/**
 * Keyless translation, via Lingva.
 *
 * Lingva is an open-source front end that fetches a translation and hands it
 * back without the tracking the original service attaches. For this keyboard
 * the properties that matter are: no key, no account, no per-request quota to
 * explain to the user, and source-language auto-detection — which the previous
 * keyless option could not do, and which is why the translate bar used to have
 * to make the user declare what they were typing.
 *
 * The trade is the same one every online translator carries and is stated
 * plainly in Settings: the instance sees the text. What it does *not* see is
 * anything identifying — no key, no account, no `customer_id`, and the request
 * carries no header this app did not put there.
 *
 * The instance is configurable because a public one is a single point of
 * failure: the default has been up throughout, but its mirrors have variously
 * gone behind a bot check or been paused, and someone who self-hosts should be
 * able to point at their own. See [Translate.instanceHost].
 */
object Lingva {

    const val DEFAULT_HOST = "lingva.ml"

    /** Lingva's own detect-the-source token, passed straight through as a path segment. */
    const val AUTO = "auto"

    /**
     * Comfortably inside the 8 KB request line every common server allows,
     * leaving room for the host and the two language segments.
     */
    private const val MAX_URL = 6000

    /**
     * Translates [text] from [source] (or [AUTO]) into [target].
     *
     * The text is a *path segment*, not a query parameter, which is the one
     * genuinely sharp edge in this API: an unescaped `/` or `?` would not make
     * a malformed request, it would make a different one — a request to another
     * endpoint on a host the gate has already allowed. [seg] is what stops
     * that, and it escapes rather than rejects, because "you cannot translate a
     * sentence containing a slash" would be an absurd limitation.
     */
    fun translate(
        c: Context,
        text: String,
        source: String,
        target: String,
        host: String = DEFAULT_HOST
    ): Result<String> {
        val q = text.trim()
        if (q.isEmpty()) return Result.success("")
        if (source == target) return Result.success(q)
        val url = "https://$host/api/v1/${seg(source)}/${seg(target)}/${seg(q)}"
        // The text rides in the URL, and servers reject over-long request lines
        // with a 414 rather than anything explanatory. Caught here so the bar
        // can say what is wrong instead of showing a bare HTTP code. Accented
        // text encodes to three times its length, so this is a length check on
        // the URL, not on the sentence.
        if (url.length > MAX_URL) return Result.failure(Translate.Error.TooLong)
        return Net.fetchBytes(
            c, url,
            reason = "Translate",
            // The whole point of the request is text the user typed, so this is
            // refused outright in incognito.
            sendsTypedText = true
        ).mapCatching { parse(String(it, Charsets.UTF_8)) }
    }

    /**
     * Unwraps `{"translation": "…"}`.
     *
     * An instance that is up but unhappy answers with `{"error": "…"}` at HTTP
     * 200, so the error has to be looked for explicitly — otherwise it reads as
     * "no translation" and the bar silently does nothing.
     */
    internal fun parse(json: String): String {
        val root = JSONObject(json)
        root.optString("error").takeIf { it.isNotBlank() }?.let {
            throw Translate.Error.Api(it)
        }
        val out = root.optString("translation")
        if (out.isBlank()) throw Translate.Error.Api("empty response")
        return out
    }

    /**
     * Percent-encodes one path segment.
     *
     * `URLEncoder` is form encoding, not path encoding: it turns a space into
     * `+`, which in a path is a literal plus sign and would be translated as
     * one. Hence the fixup. `~` is unreserved in RFC 3986 and encoding it is
     * merely ugly, but `*` and `!` are left encoded — being over-strict inside
     * a segment is safe, and being under-strict is the bug this exists for.
     */
    internal fun seg(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")
            .replace("+", "%20")
            .replace("%7E", "~")
}
