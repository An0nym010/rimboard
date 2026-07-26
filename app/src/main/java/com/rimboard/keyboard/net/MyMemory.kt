package com.rimboard.keyboard.net

import android.content.Context
import org.json.JSONObject

/**
 * Keyless translation, via MyMemory.
 *
 * The point of this over the Anthropic path is that it needs no API key at all:
 * anonymous requests just work, which is what makes translation available out
 * of the box rather than only to someone who has set up a paid key. Anthropic
 * stays as an optional upgrade for quality and longer text; when its key is
 * set the service prefers it, and this is the fallback.
 *
 * The trade is real and worth naming: MyMemory is a third party that sees the
 * text, exactly like any online translator, and the anonymous tier is
 * rate-limited and capped at 500 bytes a request. An optional MyMemory key or
 * email raises the daily limit; it is not required.
 */
object MyMemory {

    private const val BASE = "https://api.mymemory.translated.net/get"

    /** The API's hard per-request cap. UTF-8 bytes, so accented text counts double. */
    const val MAX_BYTES = 500

    /**
     * Translates [text] from [source] to [target] (ISO codes like "en", "tr").
     *
     * MyMemory does not detect the source reliably, so unlike the Anthropic
     * path the caller must say which language the text is in — the keyboard's
     * current language, in practice.
     */
    fun translate(c: Context, text: String, source: String, target: String): Result<String> {
        val q = text.trim()
        if (q.isEmpty()) return Result.success("")
        if (source == target) return Result.success(q)   // nothing to do
        if (q.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            return Result.failure(Error.TooLong)
        }
        val cred = ApiKeys.mymemory(c)
        val url = buildString {
            append(BASE)
            append("?q=").append(enc(q))
            append("&langpair=").append(enc("$source|$target"))
            if (cred != null) {
                // MyMemory raises the free limit for a valid email via `de`, and
                // authenticates a private key via `key`. One field, routed by
                // shape, so the user need not care which they have.
                if (cred.contains('@')) append("&de=").append(enc(cred))
                else append("&key=").append(enc(cred))
            }
        }
        return Net.fetchBytes(
            c, url, reason = "Translate", sendsTypedText = true
        ).mapCatching { parse(String(it, Charsets.UTF_8)) }
    }

    /**
     * Pulls the translation out of `{responseData:{translatedText}, responseStatus}`.
     *
     * `responseStatus` arrives as a number on success and, maddeningly, as a
     * string on some errors, so it is read leniently. On failure MyMemory puts
     * an uppercase message in `translatedText` itself ("INVALID LANGUAGE PAIR",
     * "QUERY LENGTH LIMIT EXCEEDED"), which would otherwise be committed into
     * the user's field as if it were a translation — so status is checked
     * before the text is trusted.
     */
    internal fun parse(json: String): String {
        val root = JSONObject(json)
        val status = when (val s = root.opt("responseStatus")) {
            is Number -> s.toInt()
            is String -> s.toIntOrNull() ?: 0
            else -> 0
        }
        if (status !in 200..299) {
            throw Error.Api(root.optString("responseDetails").ifBlank { "HTTP $status" })
        }
        val out = root.optJSONObject("responseData")?.optString("translatedText").orEmpty()
        if (out.isBlank()) throw Error.Api("empty response")
        return decodeEntities(out)
    }

    /**
     * MyMemory HTML-encodes a few characters in its output (`&#39;`, `&quot;`,
     * `&amp;`). Committing those raw would put "&#39;" where an apostrophe
     * belongs, so they are decoded — the common named and numeric ones only,
     * which is all this API emits.
     */
    private fun decodeEntities(s: String): String {
        if ('&' !in s) return s
        return s
            .replace("&#39;", "'").replace("&apos;", "'")
            .replace("&quot;", "\"")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace(Regex("&#(\\d+);")) { m ->
                m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value
            }
            .replace("&amp;", "&")   // last, so a literal &amp;#39; is not double-decoded
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    sealed class Error(message: String) : java.io.IOException(message) {
        object TooLong : Error("Too long for the free translator (500 bytes)")
        class Api(detail: String) : Error(detail)
    }
}
