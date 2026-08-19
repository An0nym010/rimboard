package com.rimboard.keyboard.engine

import android.content.Context
import android.provider.UserDictionary
import com.rimboard.keyboard.model.PersonalWords
import com.rimboard.keyboard.settings.Prefs
import java.util.concurrent.Executors

/**
 * Android's own personal dictionary, the one at
 * Settings -> Languages -> Personal dictionary.
 *
 * It is shared by every app on the phone, and it is the list a user types into
 * by hand specifically to say "this is a word". RimBoard keeps its own learned
 * words and never looked at the system one, so a word added there, or taught by
 * another keyboard, or carried over from an old phone, was still underlined
 * here and could still be autocorrected away.
 *
 * The same two gates and the same shape as [ContactStore], deliberately: the
 * setting is off until turned on, the permission is asked for separately, and
 * with either shut this reads nothing and reports an empty set.
 *
 * Unlike contacts, an entry holding digits is kept. A phone number in a
 * contact's name is not a name; "covid19" in a personal dictionary is a word
 * somebody sat down and typed.
 */
object UserDictionaryStore {

    @Volatile
    private var words: Set<String> = emptySet()

    @Volatile
    private var loaded = false

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "rimboard-userdict").apply { isDaemon = true }
    }

    /** What has been read, or empty. Never blocks and never reads. */
    fun words(): Set<String> = words

    /**
     * One gate, not two, and the reason is worth writing down.
     *
     * `READ_USER_DICTIONARY` is not in the public SDK. `android.Manifest`
     * carries READ_CONTACTS and does not carry this one, though
     * `android.provider.UserDictionary` is still public and still documented.
     * So there is no constant to hand to a runtime request and no public way
     * to ask for it — which also means `checkSelfPermission` is the wrong
     * question: on a build where an input method may read this provider
     * anyway, a check would report DENIED and switch off a feature that works.
     *
     * So this asks the only question it can answer honestly — has the user
     * turned it on — attempts the read, and treats a refusal as an empty
     * list. All three possible worlds behave: granted at install and it works,
     * allowed to the active IME and it works, refused and there are no extra
     * words.
     */
    fun enabled(context: Context): Boolean = Prefs.systemDictionary(context)

    /**
     * Read it if it has not been read and is allowed to be.
     *
     * Queued rather than done here, for the same reason as everything else on
     * this path: the caller is a focus change or a spell-checker session, and
     * neither can wait for a ContentResolver.
     */
    fun warm(context: Context) {
        if (loaded || !enabled(context)) return
        loaded = true
        val app = context.applicationContext
        io.execute {
            words = try {
                read(app)
            } catch (e: SecurityException) {
                // The expected refusal, and not an error: this build does not
                // let us read it. Logged at info because it says something
                // true about the device rather than something wrong with us.
                android.util.Log.i("RimBoard", "user dictionary not readable here")
                emptySet()
            } catch (e: Exception) {
                // Some builds restrict the provider, some OEMs replace it, and
                // none of that is worth a crash in a keyboard.
                android.util.Log.w("RimBoard", "user dictionary unreadable", e)
                emptySet()
            }
        }
    }

    private fun read(context: Context): Set<String> {
        val projection = arrayOf(UserDictionary.Words.WORD)
        context.contentResolver.query(
            UserDictionary.Words.CONTENT_URI, projection, null, null, null
        )?.use { c ->
            val col = c.getColumnIndex(UserDictionary.Words.WORD)
            if (col < 0) return emptySet()
            val raw = ArrayList<String>(c.count.coerceAtMost(PersonalWords.MAX_NAMES))
            while (c.moveToNext()) {
                c.getString(col)?.let { raw.add(it) }
            }
            return PersonalWords.of(raw.asSequence(), dropEntriesWithDigits = false)
        }
        return emptySet()
    }

    /** Drop what was read, so the next [warm] reads again. */
    fun forget() {
        words = emptySet()
        loaded = false
    }
}
