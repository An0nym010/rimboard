package com.rimboard.keyboard.net

import android.content.Context
import com.rimboard.keyboard.settings.Prefs
import org.json.JSONObject

/**
 * GIF search, via Tenor's v2 API.
 *
 * Two hosts are involved and both are on [Net.ALLOWED_HOSTS]: search metadata
 * comes from `tenor.googleapis.com`, and the image bytes come from
 * `media.tenor.com`. They are separate entries on purpose — allowing the search
 * API is not the same decision as allowing the CDN.
 */
object Tenor {

    private const val SEARCH = "https://tenor.googleapis.com/v2/search"

    /**
     * One screen of results. Deliberately small: every entry costs a thumbnail
     * download, and a keyboard panel shows a handful at a time.
     */
    const val LIMIT = 24

    /**
     * What to search for. The difference is not cosmetic: stickers come back
     * with transparency and are meant to be sent without a message bubble
     * around them.
     */
    enum class Kind {
        GIF,

        /**
         * Tenor serves stickers as transparent WebP *and* transparent GIF.
         * RimBoard asks for the GIF variants deliberately: animated WebP only
         * decodes from API 28, and `BitmapFactory` — which is what draws the
         * grid thumbnails — cannot reliably read an animated WebP below that.
         * minSdk here is 26, so choosing WebP would leave the sticker grid
         * blank on Android 8.0 and 8.1 while working fine on the test device.
         * Transparent GIF decodes everywhere and costs only file size.
         */
        STICKER
    }

    data class Gif(
        val id: String,
        /** Tenor's own alt text. Used as the accessibility label. */
        val description: String,
        /** Small still-ish preview for the grid. */
        val previewUrl: String,
        /** Full animated GIF, fetched only when one is actually picked. */
        val gifUrl: String
    )

    /**
     * Searches for [query].
     *
     * The API key travels as a query parameter because that is the only place
     * Tenor accepts it. That would normally be the wrong place for a
     * credential — but [NetLog] records only the host of every request, never
     * the path or query, so neither the key nor the user's search terms reach
     * the log. Worth keeping in mind if that ever changes.
     */
    fun search(c: Context, query: String, kind: Kind = Kind.GIF): Result<List<Gif>> {
        val q = query.trim()
        if (q.isEmpty()) return Result.success(emptyList())
        val key = ApiKeys.tenor(c) ?: return Result.failure(GifError.NoKey)

        val url = buildString {
            append(SEARCH)
            append("?q=").append(enc(q))
            append("&key=").append(enc(key))
            append("&limit=").append(LIMIT)
            // Ask for only the formats actually used. Without this Tenor
            // returns a dozen variants per result, most of a megabyte of JSON
            // for a single search.
            append("&media_filter=").append(
                if (kind == Kind.STICKER) "tinygif_transparent,gif_transparent"
                else "tinygif,gif"
            )
            if (kind == Kind.STICKER) append("&searchfilter=sticker")
            // Reuses the keyboard's existing "block offensive words" setting
            // rather than inventing a second content preference that could
            // disagree with it.
            append("&contentfilter=").append(if (Prefs.blockOffensive(c)) "high" else "medium")
        }
        return Net.fetchBytes(
            c, url,
            reason = if (kind == Kind.STICKER) "Sticker search" else "GIF search",
            // The query is what the user typed, so this is refused in incognito.
            sendsTypedText = true
        ).mapCatching { parse(String(it, Charsets.UTF_8), kind) }
    }

    /**
     * Downloads one GIF's bytes.
     *
     * `sendsTypedText` is false: by this point the request is for a fixed URL
     * that Tenor already returned, and carries nothing the user typed. The
     * search that produced the URL was the part that did.
     */
    fun download(c: Context, gif: Gif): Result<ByteArray> =
        Net.fetchBytes(c, gif.gifUrl, reason = "GIF download", sendsTypedText = false)

    /**
     * Results missing either format are dropped rather than carried with a
     * blank URL — a tile that cannot draw and cannot be inserted is worse than
     * one fewer result.
     */
    internal fun parse(json: String, kind: Kind = Kind.GIF): List<Gif> {
        val previewKey = if (kind == Kind.STICKER) "tinygif_transparent" else "tinygif"
        val fullKey = if (kind == Kind.STICKER) "gif_transparent" else "gif"
        val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Gif>(results.length())
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val formats = item.optJSONObject("media_formats") ?: continue
            // Falls back to the opaque formats: Tenor does not always return a
            // transparent variant for every sticker, and an opaque one is far
            // better than a gap in the grid.
            val preview = (formats.optJSONObject(previewKey)
                ?: formats.optJSONObject("tinygif"))?.optString("url").orEmpty()
            val full = (formats.optJSONObject(fullKey)
                ?: formats.optJSONObject("gif"))?.optString("url").orEmpty()
            if (preview.isEmpty() || full.isEmpty()) continue
            // Both URLs are checked against the allowlist by Net when they are
            // fetched, so a response pointing somewhere else fails closed.
            out.add(
                Gif(
                    id = item.optString("id"),
                    description = item.optString("content_description").ifEmpty { "GIF" },
                    previewUrl = preview,
                    gifUrl = full
                )
            )
        }
        return out
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    sealed class GifError(message: String) : java.io.IOException(message) {
        object NoKey : GifError("No Tenor API key set")
    }
}
