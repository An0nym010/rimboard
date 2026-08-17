package com.rimboard.keyboard.spell

import android.service.textservice.SpellCheckerService
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import com.rimboard.keyboard.engine.SuggestionEngine
import com.rimboard.keyboard.engine.UserData
import com.rimboard.keyboard.model.Languages
import com.rimboard.keyboard.settings.Prefs
import java.util.Locale

/**
 * The red underlines, in other apps.
 *
 * A keyboard only ever sees what is typed *into* it. The squiggles under a
 * misspelling in Gmail or Chrome come from whichever spell checker the system
 * has bound, which until now was never this one — so RimBoard's dictionaries,
 * its Turkish suffix handling and the words it has learned from you all stopped
 * at the edge of its own suggestion strip.
 *
 * This is the same APK and the same engine, exposed through the second
 * component Android offers for it. Nothing is installed separately and no new
 * permission is requested; the user picks RimBoard under Settings → Languages
 * and input → Spell checker, and that binding is the whole of the consent.
 *
 * **Two rules this service holds itself to, both stricter than it has to be:**
 *
 * 1. **It never writes.** The keyboard learns as you type; this does not. A
 *    spell checker is handed text from every app on the phone — including text
 *    that was pasted, autofilled, or already sitting in the field before you
 *    arrived — and quietly folding all of that into your personal dictionary
 *    would turn "RimBoard learns as you type" into something much broader than
 *    anyone agreed to. It reads your learned words so they stop being
 *    underlined; it adds nothing to them.
 *
 * 2. **It never reaches the network.** Not a policy so much as a fact: the
 *    engine is entirely local and this class calls nothing else. On the
 *    `offline` build there is no permission to reach it with anyway.
 */
class RimSpellService : SpellCheckerService() {

    // One engine for the process, shared across sessions. Dictionaries are
    // several hundred KB each and the system creates a session per text field.
    private val userDataLazy = lazy {
        // loadAsync is not optional. UserData reads nothing at construction, so
        // without this the learned words, the blocked words and the n-grams are
        // all empty — which would have meant the spell checker underlining
        // every word the keyboard had ever learned from this person, and
        // offering corrections they had explicitly blocked. Exactly the
        // opposite of what this service is documented to do.
        UserData(this).also { it.loadAsync() }
    }

    private val userData: UserData by userDataLazy

    private val engineLazy = lazy { SuggestionEngine(this, userData) }

    private val engine: SuggestionEngine by engineLazy

    /**
     * Starts loading the dictionary before anything asks for a word.
     *
     * A cold load is ~350ms of parsing on a desktop JVM and several times that
     * on a phone, and without this it happens inside the first
     * [Session.onGetSuggestions] — on a binder thread, with the framework
     * waiting. That is the difference between underlines appearing as you type
     * and the first paragraph you write going unchecked.
     *
     * The keyboard warms on the same principle when it opens; this is the same
     * call, for the other entry point into the same engine.
     */
    override fun onCreate() {
        super.onCreate()
        val lang = Prefs.languages(this).firstOrNull() ?: "en"
        engine.warm(lang, Languages.byCode(lang).locale, null, null)
    }

    override fun createSession(): Session = RimSession(engine, this)

    companion object {
        /**
         * "No opinion": neither in the dictionary nor a typo, so the framework
         * draws nothing — the right answer for a URL or a version number.
         *
         * A new instance every time, and that is the whole point. This was a
         * shared `val`, which looked like an obvious saving on an immutable
         * constant. [SuggestionsInfo] is not immutable: the framework calls
         * `setCookieAndSequence` on whatever a session returns, to tag the
         * answer with the word it belongs to. `onGetSuggestionsMultiple` walks
         * a batch of words calling this method and tagging each result in turn,
         * so returning one object for every unjudged word in a batch left them
         * all as the same object, carrying whichever cookie was written last —
         * and the framework matches answers to words by that cookie. Sessions
         * also run on binder threads, so the shared instance was being mutated
         * from several at once.
         */
        internal fun notJudged() = SuggestionsInfo(0, null)
    }

    /**
     * The store owns a background thread. Nothing here ever writes — this
     * service is read-only by design — so there is nothing to flush, but the
     * executor still has to be released when the system unbinds us.
     */
    override fun onDestroy() {
        // Guarded, so unbinding a service that never checked a word does not
        // build the store purely in order to tear it down.
        if (engineLazy.isInitialized()) engine.shutdown()
        if (userDataLazy.isInitialized()) userData.shutdown()
        super.onDestroy()
    }

    /**
     * One bound text field.
     *
     * Only [onGetSuggestions] is implemented. The framework's own sentence
     * splitter calls it per word, and it handles the tokenisation edge cases —
     * scripts without spaces, punctuation, sentence boundaries — that this
     * would otherwise have to reimplement badly.
     */
    private class RimSession(
        private val engine: SuggestionEngine,
        private val service: RimSpellService
    ) : Session() {

        private var lang = "en"
        private var loc: Locale = Locale.ENGLISH
        private var altLang: String? = null
        private var altLoc: Locale? = null

        override fun onCreate() {
            // The system hands over the locale it bound this session for, which
            // is the language of the *field*, not of the keyboard. Honoured as
            // given: someone writing German in one app and Turkish in another
            // should get each judged on its own terms.
            val requested = locale?.replace('_', '-')?.let { Locale.forLanguageTag(it) }
                ?: Locale.getDefault()
            val code = requested.language.lowercase(Locale.ROOT)
            lang = if (code in Languages.codes) code else "en"
            loc = Languages.byCode(lang).locale

            // The user's other enabled keyboard language, so a bilingual writer
            // does not get every English word in a Turkish message underlined.
            // Read from the same setting the keyboard uses, for the same reason.
            altLang = Prefs.languages(service).firstOrNull { it != lang }
            altLoc = altLang?.let { Languages.byCode(it).locale }
        }

        override fun onGetSuggestions(textInfo: TextInfo?, suggestionsLimit: Int): SuggestionsInfo {
            val word = textInfo?.text.orEmpty()
            if (!worthChecking(word)) return notJudged()

            if (engine.acceptedWord(word, lang, loc, altLang, altLoc)) {
                return SuggestionsInfo(SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY, null)
            }

            // Same ranking the suggestion strip uses, so the fix offered by a
            // long-press on the underline is the fix the keyboard would have
            // made. Contractions first: "dont" is a missing apostrophe rather
            // than a mistyped word, and edit distance does not know that.
            val contraction = engine.contractionFor(word, lang, loc)?.first
            val corrections = engine.correctionCandidates(
                word, lang, loc, altLang, altLoc,
                limit = suggestionsLimit.coerceIn(1, MAX_SUGGESTIONS)
            )
            // A run-together pair. Last, because it is the largest change: the
            // others fix a word, this one adds a boundary between two.
            val split = engine.splitFor(word, lang, loc)
            val out = (listOfNotNull(contraction) + corrections + listOfNotNull(split))
                .distinct()
                .take(suggestionsLimit.coerceAtLeast(1))

            var attrs = SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO
            if (out.isNotEmpty()) {
                attrs = attrs or SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS
            }
            return SuggestionsInfo(attrs, out.toTypedArray())
        }

        /**
         * Whether this token is the kind of thing a spell checker should have
         * an opinion about at all.
         *
         * The distinction that matters is between "correctly spelled" and "not
         * my business": returning the former for a URL would be a lie, and
         * returning "typo" would underline half of every technical message. An
         * empty attribute set is the API's way of saying nothing, and it is the
         * right answer for all of these.
         */
        private fun worthChecking(word: String): Boolean {
            if (word.length < MIN_LENGTH) return false
            // Digits anywhere: version numbers, IDs, "covid19".
            if (word.any { it.isDigit() }) return false
            // Acronyms and constants — NASA, HTTP, MAX_VALUE — are not in any
            // word list and are not misspelled either.
            if (word.length > 1 && word == word.uppercase(loc)) return false
            // A capital inside the word: camelCase, brand names, and the
            // mid-word capitals autocorrect already refuses to touch.
            if (word.drop(1).any { it.isUpperCase() }) return false
            // Anything with the shape of an address rather than a word.
            if (word.any { it in "@/\\:_" }) return false
            return word.all { it.isLetter() || it == '\'' || it == '’' }
        }

        private companion object {

            /** Two-letter words are too easily "corrected" into something else. */
            const val MIN_LENGTH = 3

            /** More than this and the popup is a menu rather than a fix. */
            const val MAX_SUGGESTIONS = 5
        }
    }
}
