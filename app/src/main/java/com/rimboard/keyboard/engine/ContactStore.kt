package com.rimboard.keyboard.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.rimboard.keyboard.model.ContactNames
import com.rimboard.keyboard.settings.Prefs
import java.util.concurrent.Executors

/**
 * The address book, read once and held as a set of words.
 *
 * Two gates, both of which must be open, and neither of which this class opens
 * for itself. The user turns on "Names from contacts", and the user grants
 * READ_CONTACTS. With either shut this reads nothing and reports an empty set,
 * which every consumer already treats as "no names" rather than as an error.
 *
 * Process-wide, because the keyboard and the spell checker both want it and
 * neither should be reading a thousand contacts of its own. The same reasoning
 * as the shared dictionaries, and the same consequence: it outlives either
 * service, so [forget] exists and the trim path calls it.
 *
 * What leaves this class is a set of lowercase name parts and nothing else. No
 * numbers, no addresses, no contact identity — [ContactNames] takes display
 * names and gives back words, and the words are all that is kept.
 */
object ContactStore {

    @Volatile
    private var names: Set<String> = emptySet()

    @Volatile
    private var loaded = false

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "rimboard-contacts").apply { isDaemon = true }
    }

    /** What has been read, or empty. Never blocks and never reads. */
    fun names(): Set<String> = names

    fun granted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Whether both gates are open.
     *
     * Asked on every focus change, so it is two cheap reads: a preference from
     * an in-memory map and a permission check the framework caches.
     */
    fun enabled(context: Context): Boolean =
        Prefs.contactNames(context) && granted(context)

    /**
     * Read the address book if it has not been read and is allowed to be.
     *
     * Queued, never done on the caller's thread: this is called from a focus
     * change and from a spell-checker session, and a ContentResolver query
     * across a thousand contacts is not something either can wait for.
     *
     * Once per process. Contacts change rarely and a keyboard is not the thing
     * that should notice; [forget] is how it is asked to look again.
     */
    fun warm(context: Context) {
        if (loaded || !enabled(context)) return
        loaded = true
        val app = context.applicationContext
        io.execute {
            names = try {
                read(app)
            } catch (e: Exception) {
                // A revoked permission mid-read, a locked profile, an OEM
                // provider that throws: none of these are worth a crash in a
                // keyboard, and the answer for all of them is "no names".
                android.util.Log.w("RimBoard", "contacts unreadable", e)
                emptySet()
            }
        }
    }

    private fun read(context: Context): Set<String> {
        val projection = arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI, projection, null, null, null
        )?.use { c ->
            val col = c.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            if (col < 0) return emptySet()
            val raw = ArrayList<String>(c.count.coerceAtMost(ContactNames.MAX_NAMES))
            while (c.moveToNext()) {
                c.getString(col)?.let { raw.add(it) }
            }
            return ContactNames.of(raw.asSequence())
        }
        return emptySet()
    }

    /**
     * Drop what was read, so the next [warm] reads again.
     *
     * Called when the setting or the permission is turned off, where forgetting
     * is the whole point, and under memory pressure, where it is the same
     * bargain the dictionaries make: cheap to rebuild, and being killed
     * mid-sentence is not.
     */
    fun forget() {
        names = emptySet()
        loaded = false
    }
}
