package com.rimboard.keyboard.model

import org.json.JSONObject

/**
 * The catalogue of downloadable dictionaries, and the rules for accepting one.
 *
 * Every language ships 200,000 words inside the APK. That is a cap, not a
 * vocabulary: measured against the engine, it silently overwrites a fifth to a
 * third of correctly-typed words drawn from the band it omits, and the
 * languages it hurts most are the ones whose morphology produces the most
 * distinct forms -- Finnish has 488,508 words worth keeping where English has
 * 298,946.
 *
 * So the deeper dictionaries live outside the APK and are fetched on request.
 * This object is the part of that with no Context in it: what exists, where it
 * comes from, and whether a pile of bytes is the thing it claims to be.
 *
 * # Why there is no "everything" tier
 *
 * The depth is `minCount` in the manifest -- every word the source corpus saw
 * at least that many times -- and it is five because deeper measurably breaks
 * the keyboard. A word seen once in a subtitle corpus is usually a misspelling,
 * a name, or an OCR artifact; include them all and the spell checker accepts
 * 29% of real typos as words, stops underlining them, and autocorrect stops
 * fixing them. Repair falls from 91% to 69%. There is no user-facing switch for
 * that, for the same reason there is no "more eager autocorrect" one.
 *
 * # Trust
 *
 * A downloaded dictionary decides what the keyboard offers and what it accepts
 * as a word, so it is not ordinary data. The manifest ships *inside* the APK
 * and names a SHA-256 for every file, and nothing is installed that does not
 * match one. That is what makes the offline build's import path safe as well:
 * a file that arrived on a memory stick is checked against the same list as a
 * file that arrived over HTTPS.
 */
object ExtendedDicts {

    /** Where the manifest lives inside the APK. */
    const val ASSET = "extended.json"

    data class Entry(
        val lang: String,
        /** Words in the uncompressed dictionary; shown to the user. */
        val words: Int,
        /** Size of the compressed file, which is what gets transferred. */
        val bytes: Long,
        /** SHA-256 of the compressed file, lower-case hex. */
        val sha256: String
    )

    data class Catalogue(
        val version: Int,
        val minCount: Int,
        val base: String,
        val entries: List<Entry>
    ) {
        fun forLang(lang: String): Entry? = entries.firstOrNull { it.lang == lang }

        /**
         * Where [entry] is fetched from.
         *
         * Built here rather than stored per entry so a manifest cannot name one
         * host for the catalogue and a different one for a file in it.
         */
        fun urlFor(entry: Entry): String = base + entry.lang + ".txt.gz"
    }

    val EMPTY = Catalogue(0, 0, "", emptyList())

    /**
     * Reads the manifest, or returns [EMPTY] if it cannot be trusted to be one.
     *
     * Never throws: a corrupt manifest means the download screen has nothing to
     * offer, which is a keyboard that works, while an exception here happens on
     * a settings screen the user opened for some other reason.
     */
    fun parse(json: String?): Catalogue {
        if (json.isNullOrBlank()) return EMPTY
        return try {
            val o = JSONObject(json)
            val arr = o.optJSONArray("entries") ?: return EMPTY
            val base = o.optString("base")
            // https only, and it has to be a URL rather than a path: this
            // string becomes the host the app connects to.
            if (!base.startsWith("https://") || !base.endsWith("/")) return EMPTY
            val out = ArrayList<Entry>(arr.length())
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val lang = e.optString("lang")
                val sha = e.optString("sha256").lowercase()
                val words = e.optInt("words")
                val bytes = e.optLong("bytes")
                // A malformed entry is dropped rather than failing the file:
                // one bad line should not take twenty-one good languages down
                // with it.
                if (lang.length !in 2..3 || !lang.all { it in 'a'..'z' }) continue
                if (sha.length != 64 || !sha.all { it in "0123456789abcdef" }) continue
                if (words <= 0 || bytes <= 0) continue
                out.add(Entry(lang, words, bytes, sha))
            }
            Catalogue(
                version = o.optInt("version"),
                minCount = o.optInt("minCount"),
                base = base,
                entries = out
            )
        } catch (_: Exception) {
            EMPTY
        }
    }

    /** Lower-case hex SHA-256 of [data]. */
    fun sha256(data: ByteArray): String {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(data)
        val sb = StringBuilder(d.size * 2)
        for (b in d) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    /**
     * Whether [data] is the file [entry] describes.
     *
     * Length is checked first because it is free and because a mismatch there
     * is the ordinary case -- a truncated download, or the wrong file picked
     * out of a downloads folder -- and it produces a message worth reading. The
     * hash is what actually decides.
     */
    fun accepts(entry: Entry, data: ByteArray): Boolean =
        data.size.toLong() == entry.bytes && sha256(data) == entry.sha256

    private const val HEX = "0123456789abcdef"
}
