package com.rimboard.keyboard.engine

import android.content.Context
import com.rimboard.keyboard.model.ExtendedDicts
import java.io.File
import java.io.InputStream

/**
 * Dictionaries that arrived after the install: where they live, how one is
 * accepted, and how the engine comes to read it instead of the bundled one.
 *
 * # The seam
 *
 * [open] is consulted by the app's [SuggestionEngine.Assets] before
 * `context.assets`, and answers only for `dictionaries/<lang>.txt`. Everything
 * else in the engine -- the offensive lists, the prediction models, the emoji
 * table -- goes on reading the APK, because nothing else is downloadable and a
 * store that could shadow *any* asset is a much larger thing to have to trust.
 *
 * # Device-protected storage
 *
 * Same reasoning as [UserData]: the keyboard has to work on a lock screen after
 * a reboot, and a dictionary sitting in credential-protected storage would be
 * unreadable exactly then -- the keyboard would silently drop back to the
 * bundled words at the one moment the user cannot investigate why.
 *
 * # What "accepted" means
 *
 * The manifest inside the APK names a SHA-256 for every file, and nothing is
 * installed that does not match one. Both flavours install through here: the
 * online build hands over bytes it downloaded, the offline build hands over
 * bytes the user picked out of a file manager, and neither is trusted more
 * than the other.
 */
object DictionaryStore {

    /**
     * Ceiling on what a verified file may expand to, as insurance rather than
     * as policy: the hash already pins the content exactly, so this can only
     * fire on a file nobody built. Sized well above the largest real one
     * (Finnish, 6.4 MB) and well below anything that would trouble the disk.
     */
    private const val MAX_UNPACKED_BYTES = 64L shl 20

    /**
     * The floor the packaging tool applies too. A dictionary this small is not
     * a small language, it is a truncated file.
     */
    private const val MIN_WORDS = 20_000

    /**
     * Every function here comes in two: one taking the directory, which is
     * where the thinking is and what the tests drive, and one taking a Context
     * to find that directory and to move [DictVersion] afterwards. The split
     * is not decoration -- verifying, unpacking and refusing a file is the part
     * that can be wrong, and a unit test cannot hold a Context.
     */
    /**
     * Reads at most [max] bytes from [stream], or null if it holds more.
     *
     * The import path takes a file the user picked out of a file manager, and
     * until this existed it read the whole thing into memory before checking
     * anything about it. Point it at a video and the settings screen dies of
     * an OutOfMemoryError — which is an Error, not an Exception, so the catch
     * around the install would not have caught it either.
     *
     * The cap is the size the manifest promises, because the hash check
     * demands exactly that many bytes: a longer file is already refused, and
     * this refuses it before it costs anything.
     */
    fun readAtMost(stream: java.io.InputStream, max: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(1 shl 16)
        var total = 0L
        while (true) {
            val n = stream.read(buf)
            if (n <= 0) break
            total += n
            if (total > max) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    fun dir(c: Context): File = File(UserData.dataDir(c), "extdict")

    fun file(c: Context, lang: String): File = File(dir(c), "$lang.txt")

    /** Languages with an installed dictionary, cheapest form of the question. */
    fun installed(dir: File): Set<String> =
        dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".txt") && it.length() > 0 }
            .mapTo(HashSet()) { it.name.removeSuffix(".txt") }

    /**
     * The override stream for [path], or null to fall through to the APK.
     *
     * Deliberately narrow: only the dictionary of a language, only when a file
     * is actually there, and never an exception -- a store that throws here
     * would take out the engine's whole asset path.
     */
    fun open(dir: File, path: String): InputStream? {
        if (!path.startsWith("dictionaries/") || !path.endsWith(".txt")) return null
        val lang = path.removePrefix("dictionaries/").removeSuffix(".txt")
        if (lang.isEmpty() || lang.any { it !in 'a'..'z' }) return null
        return try {
            val f = File(dir, "$lang.txt")
            if (f.length() > 0) f.inputStream() else null
        } catch (_: Exception) {
            null
        }
    }

    fun open(c: Context, path: String): InputStream? = open(dir(c), path)

    /** Why an install did not happen, for a message the user can act on. */
    enum class Refusal { NOT_OFFERED, WRONG_FILE, CORRUPT, NO_SPACE }

    /**
     * Installs [gz] as the dictionary for [entry], or says why not.
     *
     * The verify happens before anything is decompressed, and the decompressed
     * result lands under a temporary name until it has been looked at, so a
     * failure at any point leaves whatever was already installed untouched
     * rather than half-replaced.
     */
    fun install(c: Context, entry: ExtendedDicts.Entry, gz: ByteArray): Refusal? {
        val refusal = install(dir(c), entry, gz)
        // Every cached dictionary in the process is now stale. The counter is
        // in the cache key, so this is the whole of the invalidation -- and it
        // happens here rather than inside the file work so that the tests
        // exercise the refusals without reaching into a global.
        if (refusal == null) DictVersion.bump()
        return refusal
    }

    fun install(dir: File, entry: ExtendedDicts.Entry, gz: ByteArray): Refusal? {
        // The catalogue validates this, and this is the function that turns a
        // language code into a path. Two checks, because only one of them is
        // next to the file write.
        if (entry.lang.isEmpty() || entry.lang.any { it !in 'a'..'z' }) {
            return Refusal.NOT_OFFERED
        }
        if (!ExtendedDicts.accepts(entry, gz)) return Refusal.WRONG_FILE
        dir.mkdirs()
        // A private working file per attempt, not one named after the
        // language. Two installs of the same language can overlap -- rotate
        // the phone mid-download and the screen comes back with its busy set
        // empty, so the button is live again -- and a shared working file
        // means two unpackers writing one path, with a rename at the end of
        // each. The hash was checked against the bytes in memory, so what
        // landed on disk could be an interleaving of both and still pass the
        // format check.
        // Named here rather than by File.createTempFile, which demands a
        // prefix of at least three characters and so rejects every language
        // code this app has. Caught by four existing tests going red.
        val tmp = File(dir, entry.lang + "-" + System.nanoTime() + ".part")
        return try {
            var words = 0
            var first: String? = null
            tmp.outputStream().buffered().use { out ->
                java.util.zip.GZIPInputStream(gz.inputStream()).use { gzin ->
                    val buf = ByteArray(1 shl 16)
                    var total = 0L
                    while (true) {
                        val n = gzin.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > MAX_UNPACKED_BYTES) return Refusal.CORRUPT
                        out.write(buf, 0, n)
                    }
                }
            }
            tmp.bufferedReader().use { r ->
                first = r.readLine()
                if (first != null) {
                    words = 1
                    while (r.readLine() != null) words++
                }
            }
            // "word count", the format the loader parses. A gzip of the wrong
            // thing that happens to hash correctly is not possible; a gzip of
            // the right thing built by a future tool that changed the format
            // silently is, and this is what would catch it.
            val head = first
            if (head == null || !head.contains(' ') ||
                head.substringAfterLast(' ').toIntOrNull() == null
            ) {
                return Refusal.CORRUPT
            }
            if (words < MIN_WORDS) return Refusal.CORRUPT
            val dest = File(dir, entry.lang + ".txt")
            dest.delete()
            if (!tmp.renameTo(dest)) return Refusal.NO_SPACE
            null
        } catch (e: Exception) {
            android.util.Log.w("RimBoard", "extended dictionary install failed", e)
            Refusal.CORRUPT
        } finally {
            tmp.delete()
        }
    }

    /** Removes the installed dictionary for [lang]; the APK's own takes over. */
    fun remove(c: Context, lang: String): Boolean {
        val f = file(c, lang)
        val gone = !f.exists() || f.delete()
        if (gone) DictVersion.bump()
        return gone
    }
}
