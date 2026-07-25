package com.rimboard.keyboard.net

import android.content.ClipDescription
import android.content.Context
import android.net.Uri
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import java.io.File

/**
 * Handing a GIF to the app the user is typing in.
 *
 * A keyboard cannot simply "type" an image. The only supported route is
 * `commitContent`: write the file somewhere the app can be granted read access
 * to, describe it, and offer it. The receiving app decides whether to take it —
 * and many refuse, which is why [accepts] exists and is checked before the
 * download rather than after.
 */
object GifInsert {

    private const val MIME = "image/gif"

    /** Must match the provider authority declared in src/online/AndroidManifest.xml. */
    private fun authority(c: Context) = "${c.packageName}.gifs"

    /**
     * Whether the focused field will take a GIF at all.
     *
     * Checked up front because the alternative is downloading a megabyte,
     * committing it, and having nothing appear — which reads as a broken
     * keyboard rather than an app that does not support images. Plenty of
     * fields (password boxes, most single-line inputs, many older apps) accept
     * only text, and that is a normal answer, not a failure.
     */
    fun accepts(editorInfo: EditorInfo?): Boolean {
        val info = editorInfo ?: return false
        // The declared types can be wildcards ("image/*"), so this is a MIME
        // comparison rather than a string equality check.
        return EditorInfoCompat.getContentMimeTypes(info)
            .any { ClipDescription.compareMimeTypes(MIME, it) }
    }

    /** What actually happened, because the fallback needs saying out loud. */
    enum class Result {
        /** Placed straight into the field. */
        INSERTED,

        /** The field would not take it, so it is on the clipboard to paste. */
        COPIED,

        /** Neither worked. */
        FAILED
    }

    /**
     * Gets [bytes] into the app the user is typing in, by whichever route works.
     *
     * `commitContent` is the good path but a narrow one: only apps that
     * deliberately opt into rich content ever declare `contentMimeTypes`, and
     * the large majority of text fields — notes apps, search boxes, plain
     * `EditText`s, this app's own setup screen — declare nothing at all. An
     * earlier version refused to even open the picker in that case, which made
     * the whole feature look broken nearly everywhere.
     *
     * So a decline is now a fallback rather than an error: the GIF goes on the
     * clipboard and the user pastes it. That works in far more places than
     * `commitContent` does, and it is what the user would otherwise have to do
     * by hand.
     */
    fun commit(
        c: Context,
        ic: InputConnection,
        editorInfo: EditorInfo,
        bytes: ByteArray,
        description: String
    ): Result {
        val file = write(c, bytes) ?: return Result.FAILED
        val uri: Uri = try {
            FileProvider.getUriForFile(c, authority(c), file)
        } catch (_: Exception) {
            // Almost always a provider/authority mismatch — i.e. the offline
            // build, which declares no provider and should never reach here.
            return Result.FAILED
        }

        // Grant explicitly to the app being typed into. commitContent can do
        // this itself via its flag, but the clipboard route cannot: a content
        // URI pasted into another app is unreadable unless that app has been
        // given permission, and the failure is silent — a blank or broken
        // image rather than an error.
        editorInfo.packageName?.let { pkg ->
            try {
                c.grantUriPermission(pkg, uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
                // Not fatal on its own; commitContent's own grant may still do it.
            }
        }

        if (accepts(editorInfo)) {
            val info =
                InputContentInfoCompat(uri, ClipDescription(description, arrayOf(MIME)), null)
            val ok = try {
                InputConnectionCompat.commitContent(
                    ic, editorInfo, info,
                    InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                    null
                )
            } catch (_: Exception) {
                false
            }
            // A declared type is not a promise — apps still return false — so
            // this falls through to the clipboard rather than giving up.
            if (ok) return Result.INSERTED
        }

        return if (copyToClipboard(c, uri, description)) Result.COPIED else Result.FAILED
    }

    /**
     * `newUri` rather than plain text: it carries the MIME type, so an app that
     * understands image pastes gets the GIF rather than a `content://` string.
     */
    private fun copyToClipboard(c: Context, uri: Uri, label: String): Boolean = try {
        val cm = c.getSystemService(Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newUri(c.contentResolver, label, uri))
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Cache, not files: these are disposable, and the OS may reclaim them under
     * storage pressure without breaking anything. Credential-protected rather
     * than device-protected — a GIF is not needed on a lock screen, and this is
     * the one directory another app is granted a read into.
     */
    private fun dir(c: Context) = File(c.cacheDir, "gifs").apply { mkdirs() }

    private fun write(c: Context, bytes: ByteArray): File? = try {
        prune(c)
        File(dir(c), "gif_${System.currentTimeMillis()}.gif").apply { writeBytes(bytes) }
    } catch (_: Exception) {
        null
    }

    /**
     * Keeps the newest few and deletes the rest.
     *
     * Not deleted immediately after commit: the receiving app may still be
     * reading the file when the keyboard closes, and pulling it out from under
     * a chat app mid-upload produces a broken send. Trimming on the next
     * insertion instead means the previous one has long finished.
     */
    private fun prune(c: Context, keep: Int = 3) {
        val files = dir(c).listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(keep).forEach { runCatching { it.delete() } }
    }
}
