package com.rimboard.keyboard.net

import android.content.Context
import com.rimboard.keyboard.settings.Prefs
import org.json.JSONObject

/**
 * GIF and sticker search, via KLIPY.
 *
 * Third provider, and the reason is worth recording so it is not relitigated.
 * Tenor was closed to new API clients in January 2026 and shut down that June.
 * Giphy still issues keys but moved production access to paid, with developers
 * reporting four-figure quotes. KLIPY offers a lifetime free tier, still issues
 * keys, and — the part that matters for this app — its ad monetisation is
 * opt-in and controlled by the developer rather than injected into results.
 * RimBoard does not enable it, which is what keeps "no ads, no analytics" true.
 *
 * There is still no keyless option. Every provider needs a key; the ways round
 * that are shipping a shared one inside an open-source APK, where the first
 * `strings` run finds it, or scraping. Neither is acceptable here.
 */
object Klipy {

    private const val BASE = "https://api.klipy.com/api/v1"

    /**
     * One screen of results. The API caps `per_page` at 50; this is smaller
     * because every entry costs a thumbnail download and a test key is limited
     * to 100 API requests per hour.
     */
    const val LIMIT = 24

    enum class Kind { GIF, STICKER }

    data class Gif(
        val id: String,
        /** KLIPY's title, used as the accessibility label. */
        val description: String,
        /** Small still-ish preview for the grid — the `xs` gif, ~100 KB. */
        val previewUrl: String,
        /** What actually gets inserted, fetched only when one is picked. */
        val gifUrl: String
    )

    /**
     * Searches for [query].
     *
     * Deliberately omits `customer_id`. KLIPY accepts a stable per-user
     * identifier to personalise results and power a "recents" feature; sending
     * one would hand the provider a durable handle tying every search this
     * keyboard ever makes to the same person. The feature is not worth that,
     * and this app is the wrong app to make that trade in.
     */
    fun search(c: Context, query: String, kind: Kind = Kind.GIF): Result<List<Gif>> {
        val q = query.trim()
        if (q.isEmpty()) return Result.success(emptyList())
        val key = ApiKeys.klipy(c) ?: return Result.failure(GifError.NoKey)
        if (!isSafeKey(key)) return Result.failure(GifError.BadKey)

        val path = if (kind == Kind.STICKER) "stickers" else "gifs"
        val url = buildString {
            append(BASE).append('/').append(key).append('/').append(path).append("/search")
            append("?q=").append(enc(q))
            append("&per_page=").append(LIMIT)
            append("&page=1")
            // Only GIF renditions. Without this every result also carries webp,
            // mp4, webm and jpg variants at four sizes, which is a much larger
            // response for formats this app cannot use — animated webp does not
            // decode below API 28, and the grid draws with BitmapFactory.
            append("&format_filter=gif")
            // Reuses the keyboard's existing "block offensive words" setting
            // rather than inventing a second content preference that could
            // disagree with it.
            append("&content_filter=").append(if (Prefs.blockOffensive(c)) "high" else "medium")
        }
        return Net.fetchBytes(
            c, url,
            reason = if (kind == Kind.STICKER) "Sticker search" else "GIF search",
            // The query is what the user typed, so this is refused in incognito.
            sendsTypedText = true
        ).mapCatching { parse(String(it, Charsets.UTF_8)) }
    }

    /**
     * The key is a path segment, not a query parameter, so a key containing a
     * slash would not be a broken request — it would be a *different* request,
     * silently addressing another endpoint on a host the gate has already
     * allowed. Restricting it to the characters an API key actually uses closes
     * that off at the point the key is used rather than trusting the field it
     * was typed into.
     */
    internal fun isSafeKey(key: String): Boolean =
        key.isNotEmpty() && key.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    fun download(c: Context, gif: Gif): Result<ByteArray> =
        Net.fetchBytes(c, gif.gifUrl, reason = "GIF download", sendsTypedText = false)

    /**
     * Unwraps `{result, data:{data:[…]}}`.
     *
     * The envelope carries its own success flag separately from the HTTP
     * status, so a failure can arrive as a 200 whose `data` is absent — which
     * would otherwise read as "no results" and send the user hunting for a
     * better search term when the real answer is a bad key.
     */
    internal fun parse(json: String): List<Gif> {
        val root = JSONObject(json)
        if (!root.optBoolean("result", true)) {
            throw GifError.Api(root.optString("message").ifEmpty { "request rejected" })
        }
        val items = root.optJSONObject("data")?.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<Gif>(items.length())
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val file = item.optJSONObject("file") ?: continue
            // xs for the grid: ~100 KB against md's ~2 MB, and it is drawn at
            // thumbnail size regardless. md rather than hd for insertion —
            // roughly half the bytes for something that will be viewed in a
            // chat bubble.
            val preview = firstUrl(file, "xs", "sm", "md")
            val full = firstUrl(file, "md", "hd", "sm")
            if (preview.isEmpty() || full.isEmpty()) continue
            out.add(
                Gif(
                    // A numeric id in JSON, so read as a string rather than
                    // optString, which returns "" for a number on some parsers.
                    id = item.opt("id")?.toString() ?: item.optString("slug"),
                    description = item.optString("title").ifBlank { "GIF" },
                    previewUrl = preview,
                    gifUrl = full
                )
            )
        }
        return out
    }

    /** Each size holds one object per format; this app asked for `gif` only. */
    private fun firstUrl(file: JSONObject, vararg sizes: String): String {
        for (s in sizes) {
            val u = file.optJSONObject(s)?.optJSONObject("gif")?.optString("url").orEmpty()
            if (u.isNotEmpty()) return u
        }
        return ""
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    sealed class GifError(message: String) : java.io.IOException(message) {
        object NoKey : GifError("No KLIPY API key set")
        object BadKey : GifError("That KLIPY API key contains unexpected characters")
        class Api(detail: String) : GifError(detail)
    }
}
