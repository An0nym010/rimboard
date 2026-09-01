package com.rimboard.keyboard.model

/**
 * Restores the apostrophe in a contraction typed without one.
 *
 * The bundled dictionaries come from a corpus that stripped apostrophes, so
 * "dont" sits in the English list with a frequency of 9523 as though it were a
 * word, and "don't" is absent entirely. The result is a keyboard that treats
 * "dont" as correctly spelled, never fixes it, and even suggests it over the
 * real spelling — on some of the most common words in the language. Editing
 * 40 MB of frequency data would not be the honest fix even if it were cheap;
 * the apostrophe-less forms are genuinely in the corpus. This is a small,
 * explicit override on top of it.
 *
 * Two confidence levels, because the risk is entirely about ambiguity:
 *
 *  - [auto] forms are ones whose apostrophe-less spelling is never itself an
 *    English word — "dont", "youre", "im". Correcting these on space is safe.
 *  - Suggest-only forms have a real word as their bare spelling — "cant" (the
 *    noun), "wont" (accustomed), "ill" (sick). The contraction is offered on
 *    the strip to tap, but never committed automatically, because the bare
 *    word is a thing someone might actually mean.
 *
 * Words like "its", "were" and "well" appear in neither list: their bare form
 * is not only a real word but an extremely common and usually-correct one, and
 * a keyboard that turned "its" into "it's" would be wrong far more often than
 * right.
 *
 * English only for now. Other languages' contractions are elisions ("c'est",
 * "l'eau") that turn ambiguous quickly, and inventing a list per language is
 * the same unreviewed-content trap as the translations — they want a native
 * pass, not a guess. The maps are per-language so that pass has somewhere to go.
 *
 * # What that pass would be looking at, measured
 *
 * The gap is real and the same shape as the English one. Typed into the French
 * engine today, every one of these is accepted as spelled and commits as
 * itself, and the apostrophe form is offered nowhere — not on the strip, not
 * as a correction. What the strip offers instead is the tail of the word list:
 *
 *     cest     -> cest, cestus, cesternino          j'ai     never offered
 *     jai      -> jai, jaime, jaillir               c'est    never offered
 *     quon     -> quon, quonsett, quong             qu'on    never offered
 *     daccord  -> daccord                           d'accord never offered
 *
 * The frequencies sit in the same band as English entries that do ship, so the
 * bar is not the question — per million of their own corpus, French "jai" runs
 * at 5.64 and "cest" at 4.44, against English "cant" at 5.40 and "thats" at
 * 5.31.
 *
 * **A rule cannot do it, and that is worth knowing before anyone tries.** The
 * obvious one — split at an elided article the list holds, require both halves
 * to be frequent, which is exactly what [Elision] asks of the apostrophe form
 * — fires on 3,900 French entries and 6,272 Italian ones, and the ones it
 * fires hardest on are the commonest words in the language: "pas" to "p'as",
 * "les" to "l'es", "mais" to "m'ais", "dans" to "d'ans". The elided articles
 * are single letters, so nearly everything splits. Which is the same reason
 * this object is a list rather than a rule for English.
 *
 * And the trap the list will have to handle is already visible: "quelle" runs
 * at 412.96 per million and is an ordinary French word, so it belongs in
 * [suggest] and never in [auto] — the "cant" and "wont" case, in a language
 * where it is easier to miss.
 */
object Contractions {

    data class Expansion(val canonical: String, val auto: Boolean)

    private val autoEn = mapOf(
        "dont" to "don't", "doesnt" to "doesn't", "didnt" to "didn't",
        "isnt" to "isn't", "wasnt" to "wasn't", "arent" to "aren't",
        "werent" to "weren't", "havent" to "haven't", "hasnt" to "hasn't",
        "hadnt" to "hadn't", "wouldnt" to "wouldn't", "couldnt" to "couldn't",
        "shouldnt" to "shouldn't", "mustnt" to "mustn't", "neednt" to "needn't",
        "im" to "I'm", "youre" to "you're", "theyre" to "they're",
        "ive" to "I've", "youve" to "you've", "weve" to "we've",
        "theyve" to "they've", "youd" to "you'd", "theyd" to "they'd",
        "youll" to "you'll", "theyll" to "they'll", "itll" to "it'll",
        "thatll" to "that'll", "wouldve" to "would've", "couldve" to "could've",
        "shouldve" to "should've", "mustve" to "must've", "mightve" to "might've",
        "whats" to "what's", "thats" to "that's", "wheres" to "where's",
        "hows" to "how's", "heres" to "here's", "theres" to "there's",
        "hes" to "he's", "shes" to "she's", "whos" to "who's",
        "oclock" to "o'clock", "yall" to "y'all"
    )

    private val suggestEn = mapOf(
        "cant" to "can't", "wont" to "won't", "ill" to "I'll",
        "hell" to "he'll", "shell" to "she'll", "wed" to "we'd",
        // "youd" was here as well as in the auto list above, where it wins.
        // The two lists disagreed about the same word: one said its bare form
        // is never an English word (true, and the criterion for auto) and the
        // other said it was ambiguous. The dead copy is gone and
        // ContractionLanguageTest keeps the lists disjoint.
        "whod" to "who'd"
    )

    private val auto = mapOf("en" to autoEn)
    private val suggest = mapOf("en" to suggestEn)

    /** The contraction for a lowercased bare word, or null if there is none. */
    fun expand(lang: String, typedLower: String): Expansion? {
        auto[lang]?.get(typedLower)?.let { return Expansion(it, auto = true) }
        suggest[lang]?.get(typedLower)?.let { return Expansion(it, auto = false) }
        return null
    }

    /**
     * The bare forms that auto-correct in [lang].
     *
     * Exposed so a test can enumerate them rather than sample them: every one
     * of these is checked against every other shipped dictionary, because a
     * bare English contraction is sometimes an ordinary word somewhere else.
     */
    internal fun autoForms(lang: String): Set<String> = auto[lang]?.keys.orEmpty()

    /** Every form, either confidence, for the disjointness check. */
    internal fun suggestForms(lang: String): Set<String> = suggest[lang]?.keys.orEmpty()

    /**
     * Every canonical spelling in [lang], both confidences.
     *
     * So a test can enumerate what the table promises rather than sample it —
     * the case bug this exists to watch for was invisible in a sample of one
     * pronoun and obvious across all of them.
     */
    internal fun allCanonical(lang: String): List<String> =
        auto[lang]?.values.orEmpty().toList() + suggest[lang]?.values.orEmpty().toList()

    /** Whether a bare word is an auto-correctable contraction — used to keep
     *  its unapostrophised form out of the completion suggestions. */
    fun isAutoBareForm(lang: String, wordLower: String): Boolean =
        auto[lang]?.containsKey(wordLower) == true

    /**
     * Contractions that begin with [prefixLower], commonest spelling first.
     *
     * The other direction of the same table. Restoring a missing apostrophe
     * needs bare -> canonical; *completing* one somebody has already typed
     * needs prefix -> canonical, and the answer is a curated list rather than
     * anything derived, for a reason worth stating.
     *
     * The word lists hold `don` and `'t` but not `don't`, so a keyboard can
     * tell that "don't" is a word (see [Elision]) without having any idea which
     * suffix belongs to which stem. Generating them would offer "don's" ahead
     * of "don't", because `'s` is the commoner suffix in a corpus that counted
     * them separately. English contractions are a closed set of a few dozen; a
     * list is the honest way to know them, and this one already existed.
     */
    fun completionsFor(lang: String, prefixLower: String): List<String> {
        if (prefixLower.length < 2) return emptyList()
        val out = LinkedHashSet<String>()
        for (m in listOf(auto[lang], suggest[lang])) {
            m ?: continue
            for (canonical in m.values) {
                // Case-insensitively, because three of these canonical forms
                // carry a capital that is part of the spelling rather than a
                // position in a sentence -- "I'm", "I've", "I'll" -- and the
                // prefix arrives lower-cased. `"I'm".startsWith("i'")` is
                // false, so the one English word that is always a capital was
                // the one word whose contractions could not be completed.
                if (canonical.startsWith(prefixLower, ignoreCase = true) &&
                    !canonical.equals(prefixLower, ignoreCase = true)
                ) {
                    out.add(canonical)
                }
            }
        }
        return out.toList()
    }
}
