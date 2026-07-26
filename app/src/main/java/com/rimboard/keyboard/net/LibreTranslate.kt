package com.rimboard.keyboard.net

import android.content.Context
import org.json.JSONObject

/**
 * Translation via LibreTranslate — the self-hosted option.
 *
 * This is here for the person the whole app is aimed at: LibreTranslate is
 * open source and runs on your own machine, which makes it the only translator
 * in this list where the text never reaches a third party at all. Point it at
 * `http`-free `https://your-box/` and the privacy story stops involving anyone
 * else's server.
 *
 * The public instance at `libretranslate.com` no longer answers anonymously —
 * it now returns "Visit the portal to get an API key" — so this source is
 * useful only with either a key or an instance of your own. That is why it is
 * not the default; [Lingva] is, because it needs neither.
 */
object LibreTranslate {

    const val DEFAULT_HOST = "libretranslate.com"

    /** LibreTranslate's detect-the-source token. */
    const val AUTO = "auto"

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
        // Built with JSONObject rather than string concatenation: the text is
        // arbitrary user input and hand-assembling JSON around a quote or a
        // backslash is how a request becomes a different request.
        val body = JSONObject()
            .put("q", q)
            .put("source", source)
            .put("target", target)
            .put("format", "text")
        ApiKeys.libre(c)?.let { body.put("api_key", it) }
        return Net.fetch(
            c, "https://$host/translate",
            reason = "Translate",
            sendsTypedText = true,
            body = body.toString(),
            headers = mapOf("Content-Type" to "application/json")
        ).mapCatching { parse(it) }
    }

    /**
     * Unwraps `{"translatedText": "…"}`.
     *
     * The failure that matters is the keyless one: the public instance answers
     * a valid request with `{"error": "Visit https://portal…"}`, and committing
     * that into the field would type a marketing URL into the user's message.
     */
    internal fun parse(json: String): String {
        val root = JSONObject(json)
        root.optString("error").takeIf { it.isNotBlank() }?.let {
            throw Translate.Error.Api(it)
        }
        val out = root.optString("translatedText")
        if (out.isBlank()) throw Translate.Error.Api("empty response")
        return out
    }
}
