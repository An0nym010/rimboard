package com.rimboard.keyboard.net

import android.content.Context
import com.rimboard.keyboard.settings.Prefs
import org.json.JSONObject

/**
 * GIF and sticker search, via Giphy.
 *
 * This replaced a Tenor client that could never have worked: Google stopped
 * accepting new Tenor API clients in January 2026 and wound the service down
 * that June, so nobody who did not already hold a key could have used it.
 * Giphy still issues keys self-serve.
 *
 * Two hosts are involved. Search metadata comes from `api.giphy.com`; the
 * images come from Giphy's CDN, which is spread over `media0`–`media4` and
 * `i.giphy.com` and picks one per result. That is why [Net] allows a bounded
 * domain suffix here rather than a list of exact names — see `ALLOWED_SUFFIXES`.
 */
object Giphy {

    private const val API = "https://api.giphy.com/v1"

    /**
     * One screen of results. Small on purpose: every entry costs a thumbnail
     * download, and Giphy's free tier is rate-limited per hour.
     */
    const val LIMIT = 24

    /**
     * What to search. Giphy serves these from different endpoints rather than
     * a filter parameter, and stickers come back as GIFs with transparency —
     * which is what this app wants anyway, since animated WebP does not decode
     * below API 28 and the grid thumbnails go through `BitmapFactory`.
     */
    enum class Kind { GIF, STICKER }

    data class Gif(
        val id: String,
        /** Giphy's title, used as the accessibility label. */
        val description: String,
        /** Small preview for the grid. */
        val previewUrl: String,
        /** Full-size, fetched only when one is actually picked. */
        val gifUrl: String
    )

    /**
     * Searches for [query].
     *
     * The API key travels as a query parameter because that is where Giphy
     * accepts it. Normally the wrong place for a credential — tolerable here
     * only because [NetLog] records the host of every request and never the
     * path or query, so neither the key nor the search terms are written down.
     * That stops being true the moment anything logs full URLs.
     */
    fun search(c: Context, query: String, kind: Kind = Kind.GIF): Result<List<Gif>> {
        val q = query.trim()
        if (q.isEmpty()) return Result.success(emptyList())
        val key = ApiKeys.giphy(c) ?: return Result.failure(GifError.NoKey)

        val endpoint = if (kind == Kind.STICKER) "$API/stickers/search" else "$API/gifs/search"
        val url = buildString {
            append(endpoint)
            append("?api_key=").append(enc(key))
            append("&q=").append(enc(q))
            append("&limit=").append(LIMIT)
            // Reuses the keyboard's existing "block offensive words" setting
            // rather than inventing a second content preference that could
            // disagree with it.
            append("&rating=").append(if (Prefs.blockOffensive(c)) "g" else "pg-13")
        }
        return Net.fetchBytes(
            c, url,
            reason = if (kind == Kind.STICKER) "Sticker search" else "GIF search",
            // The query is what the user typed, so this is refused in incognito.
            sendsTypedText = true
        ).mapCatching { parse(String(it, Charsets.UTF_8)) }
    }

    /**
     * Downloads one result's bytes.
     *
     * `sendsTypedText` is false: by here the request is for a fixed URL Giphy
     * already returned and carries nothing the user typed. The search that
     * produced it was the part that did.
     */
    fun download(c: Context, gif: Gif): Result<ByteArray> =
        Net.fetchBytes(c, gif.gifUrl, reason = "GIF download", sendsTypedText = false)

    /**
     * Giphy reports failures in a `meta` envelope with HTTP 200 in some cases,
     * so the status is checked before the payload rather than assuming an
     * empty `data` array means "no results".
     */
    internal fun parse(json: String): List<Gif> {
        val root = JSONObject(json)
        root.optJSONObject("meta")?.let { meta ->
            val status = meta.optInt("status", 200)
            if (status !in 200..299) {
                throw GifError.Api(meta.optString("msg").ifEmpty { "HTTP $status" })
            }
        }
        val data = root.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<Gif>(data.length())
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val images = item.optJSONObject("images") ?: continue
            // Renditions are not all present on every result, so each is a
            // preference order rather than a single lookup. A missing preview
            // draws as a grey tile; a missing full size fails at the moment of
            // tapping, after the user has already chosen. Both are dropped.
            val preview = firstUrl(images, "fixed_width_small", "fixed_width", "preview_gif")
            val full = firstUrl(images, "original", "downsized", "fixed_width")
            if (preview.isEmpty() || full.isEmpty()) continue
            out.add(
                Gif(
                    id = item.optString("id"),
                    description = item.optString("title").ifBlank { "GIF" },
                    previewUrl = preview,
                    gifUrl = full
                )
            )
        }
        return out
    }

    private fun firstUrl(images: JSONObject, vararg names: String): String {
        for (n in names) {
            val u = images.optJSONObject(n)?.optString("url").orEmpty()
            if (u.isNotEmpty()) return u
        }
        return ""
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    sealed class GifError(message: String) : java.io.IOException(message) {
        object NoKey : GifError("No Giphy API key set")
        class Api(detail: String) : GifError(detail)
    }
}
