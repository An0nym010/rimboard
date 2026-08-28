package com.rimboard.keyboard.model

/**
 * English words that are ordinary vocabulary in somebody else's language.
 *
 * The offensive filter reads the language's own list and then the English one
 * behind it, and the fallback earns its place: the per-language lists do not
 * cover the English slurs that turn up in every corpus, so without it a German
 * or Danish keyboard would suggest them. What the fallback never asked is
 * whether the word means anything where it is being typed.
 *
 * It means quite a lot. Measured against the shipped dictionaries, in
 * occurrences per million words of that language:
 *
 *     sv  slut     361.90    "end". Twenty-eight times its English rate.
 *     da  slut     145.94    the same word, the same meaning.
 *     fr  retard   126.67    "delay". "En retard" is how you say you are late.
 *     de  dick      32.87    "thick", "fat". An everyday adjective.
 *     sv  prick     10.17    "dot", "spot".
 *     da  fag        5.94    "subject", "trade" -- what you study.
 *     no  fag        3.50    the same word.
 *
 * All seven were filtered out of completions, corrections, predictions and
 * glide results for every user of those languages, with the setting on by
 * default. A Swede lost the word for "end".
 *
 * # Why this is a list and not a rule
 *
 * The obvious rule is frequency: a word that is real in a language should be
 * commoner there than in English. It very nearly works -- and then it does not,
 * in both directions at once. The slur that the fallback exists to catch is
 * *also* commoner in Danish, German, Hungarian, Norwegian and Swedish subtitle
 * text than in English (17.44 against 8.54 per million for Danish), because it
 * is the same English word appearing in the same imported material. And German
 * "dick" at 32.87 sits *below* English "dick" at 49.42, so any ratio or floor
 * that rescues it unblocks the slur everywhere.
 *
 * The ratios, sorted, with the two groups interleaved:
 *
 *     42.03x fr retard    27.86x sv slut     11.23x da slut
 *      2.04x da nigger     1.81x de nigger    1.08x da fag
 *      0.76x sv prick      0.67x de dick
 *
 * There is no line through that. Whether a word is offensive in a language is
 * a fact about the language, not about counts, and the honest answer is to say
 * so word by word with the evidence attached.
 *
 * # This list is not complete
 *
 * It holds what could be justified twice over: a frequency in the shipped data
 * that shows the word is used, and a meaning that is not in question. Entries
 * that failed the second test were left out even where the first passed --
 * Dutch "dick" at 16.55 is the given name and unblocking it would unblock the
 * slur for Dutch, and Scandinavian "fager" is real but archaic and rare enough
 * that nobody would notice it missing.
 *
 * Growing it wants a native speaker, which is the same thing
 * [Contractions] says about its own per-language maps and the same open
 * question the machine-translated locales carry. The mechanism is here so that
 * pass has somewhere to land.
 */
object FalseFriends {

    private val byLang = mapOf(
        "de" to setOf("dick"),
        "sv" to setOf("slut", "prick"),
        "da" to setOf("slut", "fag"),
        "no" to setOf("fag"),
        "fr" to setOf("retard")
    )

    /**
     * Whether [wordLower] is ordinary vocabulary in [lang], despite being on
     * the English list.
     *
     * Consulted only for the English fallback. A language's own list still
     * decides about its own words first, so this can never make a word that a
     * language calls offensive acceptable in that language.
     */
    fun ordinaryHere(lang: String, wordLower: String): Boolean =
        byLang[lang]?.contains(wordLower) == true

    /** For the test that walks the population; not used by the keyboard. */
    internal fun entries(): Map<String, Set<String>> = byLang

    /**
     * The same problem in the emoji map, which has the same fallback.
     *
     * `emojiFor` reads the language's keywords and then English behind them,
     * for the same good reason -- a language with no map of its own still
     * answers for the English words people mix in. And again it never asked
     * what the word means where it is being typed:
     *
     *     hr  sad     1222.96 ppm   "now"            offered a crying face
     *     sv  dog      276.79       "died"           offered a dog
     *     no  fire     272.60       "four"           offered a flame
     *     da  fire     261.54       "four"
     *     da  dog      203.86       "however"        offered a dog
     *     da  mad      203.00       "food"           offered an angry face
     *     sv  skull    186.19       "sake"           offered a skull
     *     ro  sun      164.33       "I call"         offered the sun
     *     da  gift     159.51       "married", "poison"   offered a present
     *     fr  lit      155.27       "bed"            offered a flame
     *     no  gift     134.03       "married", "poison"
     *     it  camera   126.64       "room"           offered a camera
     *     sv  gift     116.12       "married", "poison"
     *     da  sad       87.39       "sat"
     *
     * A separate map from [byLang] rather than one union, though the predicate
     * reads the same, because the two carry different risk. An entry here that
     * is wrong costs a suggestion nobody gets -- no emoji instead of the wrong
     * emoji, which is an improvement either way. An entry in the offensive
     * exemptions that is wrong lets a slur through. Merging them would let the
     * cheap judgement quietly buy the expensive one.
     *
     * The keywords that survive the fallback are the ones that mean the same
     * thing: "ok" is ok in seven of these languages, and "idea", "question",
     * "photo", "perfect", "wow", "hmm" and "rose" all travel intact. That is
     * why this is not a rule about frequency either -- "ok" is commoner in
     * Italian than in English and is perfectly correct there.
     */
    private val emojiByLang = mapOf(
        "hr" to setOf("sad"),
        "sv" to setOf("dog", "skull", "gift"),
        "no" to setOf("fire", "gift"),
        "da" to setOf("fire", "dog", "mad", "gift", "sad"),
        "ro" to setOf("sun"),
        "fr" to setOf("lit"),
        "it" to setOf("camera")
    )

    /**
     * Whether the English emoji keyword [wordLower] means something else in
     * [lang], so the English fallback should not answer for it.
     *
     * The language's own map is still read first, so this only ever declines
     * to guess -- it cannot override a keyword somebody wrote for that
     * language.
     */
    fun emojiMeansSomethingElse(lang: String, wordLower: String): Boolean =
        emojiByLang[lang]?.contains(wordLower) == true

    /** For the test that walks the population; not used by the keyboard. */
    internal fun emojiEntries(): Map<String, Set<String>> = emojiByLang
}
