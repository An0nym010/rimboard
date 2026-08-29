package com.rimboard.keyboard.model

/**
 * Words that look like somebody dropped an accent, and are not.
 *
 * `Dictionary.accentedFormOf` and its two siblings decide whether a spelling
 * with no accents on it is really a spelling of an accented word. They decide
 * it by frequency: if the accented form outnumbers the bare one by enough, the
 * bare one is what somebody types when they cannot be bothered with the
 * diacritics, and the keyboard may offer, underline or replace it.
 *
 * That works because the two populations are usually far apart — German "für"
 * outnumbers "fur" 557 times over, and nobody writing German means "fur". It
 * stops working where a language has a genuine word that happens to be its own
 * accented word with the marks taken off.
 *
 * # Why this is a list and not a rule
 *
 * The same reason [FalseFriends] is. Sorted by ratio, the real words and the
 * unaccented typing interleave:
 *
 *     49.1x hr zelis     "you want", unaccented — *not* a word
 *     45.9x hr mozda     "maybe", unaccented — not a word
 *     44.0x hr zasto     "why", unaccented — not a word
 *     42.2x hr sto       **a word: one hundred**
 *     42.1x hr nista     "nothing", unaccented — not a word
 *     28.3x tr cop       **a word: a baton** (çöp is rubbish)
 *     25.0x es aqui      "here", unaccented — not a word
 *     21.8x tr tas       **a word: a stone** (taş)
 *     18.5x de mochte    **a word: liked** (möchte is "would like")
 *     14.5x es mas       **a word: but** (más is "more")
 *
 * There is no number that keeps `sto` and loses `nista`; they are a tenth of a
 * point apart and mean entirely different things. What separates them is not
 * how often each is written but what each one *is*, which is a fact about the
 * language and not about the corpus.
 *
 * # What being listed here does
 *
 * The word is never treated as a bare spelling of anything, at any of the three
 * thresholds. It is not replaced on the space bar, not underlined, and not
 * offered its accented twin as a chip.
 *
 * That is deliberately absolute. Before this existed the same words were safe
 * only because their ratios happened to fall below the constant of the day —
 * Turkish `cop` at 28.3x survived a threshold of 30 by a point and a half, and
 * a dictionary rebuild that nudged it either way would have started rewriting
 * a real word with nothing to say so. Protection by name does not move when
 * the corpus does.
 *
 * The cost is a catch given up: somebody who types `sto` meaning `što` is not
 * told. That is the right way round. A missed squiggle is silence; a squiggle
 * under the word for "hundred" is the keyboard being wrong out loud.
 *
 * # This list is incomplete, and knowingly so
 *
 * Every entry below is a word the project already had evidence for — the four
 * languages `BareKeySpellingTest` curates, which were vetted when that test was
 * written, plus Croatian `sto`, which a device run turned up. Twenty-two
 * languages ship and five are represented.
 *
 * Candidates seen in the data and *not* added, because deciding them needs
 * somebody who speaks the language rather than somebody reading a frequency
 * table: Croatian `vise` and `sta`, Czech `rada` and `dolu`, Slovak `ta` and
 * `rad`, German `losen` and `rachen`, French `cote`, Portuguese `la`. Each is
 * a plausible real word whose accented twin is commoner, and each would be a
 * wrong squiggle today if it is one. This is where a native pass lands, the
 * same as [FalseFriends].
 */
object BareWords {

    /**
     * Bare spellings that are words in their own right, by language.
     *
     * Frequencies are occurrences in that language's shipped dictionary, so
     * every claim here is checkable against `assets/dictionaries/`.
     */
    private val byLang = mapOf(
        // Croatian "sto" is one hundred; "što" is "what". 59,214 occurrences,
        // and the ratio between them (42.2x) sits between "zasto" at 44.0x and
        // "nista" at 42.1x, which are both merely unaccented.
        "hr" to setOf("sto"),

        // Turkish. "cop" is a baton and "çöp" is rubbish; "tas" is a bowl and
        // "taş" a stone; "cam" is glass and "çam" a pine; "ucu" is its tip and
        // "üçü" three of them; "yas" is mourning and "yaş" age or wet; "bas" is
        // "press" and "baş" a head.
        "tr" to setOf("cam", "cop", "tas", "ucu", "yas", "bas"),

        // Spanish. "mas" is "but" against "más" for "more"; "papa" a potato
        // against "papá" for father; "mama" and "mamá" the same shape; "tenia"
        // a tapeworm against "tenía" for "had"; "seria" is "serious" against
        // "sería" for "would be"; "aun" is "even" against "aún" for "still".
        // The rest are the commonest words in the language: "si"/"sí",
        // "el"/"él", "tu"/"tú", "mi"/"mí", "se"/"sé", "esta"/"está",
        // "como"/"cómo".
        "es" to setOf(
            "mas", "papa", "mama", "tenia", "seria", "aun",
            "si", "el", "tu", "mi", "se", "esta", "como"
        ),

        // French. "ou" is "or" and "où" is "where"; "la" the article and "là"
        // there; "du" the partitive and "dû" the participle of devoir; "sur"
        // is "on" and "sûr" is "sure"; "mur" a wall and "mûr" ripe; "des" the
        // article and "dès" is "from".
        "fr" to setOf("ou", "la", "du", "sur", "mur", "des"),

        // German, four genuine minimal pairs. "schon" is "already" and "schön"
        // beautiful; "konnte" is "could" and "könnte" the subjunctive; "mochte"
        // is "liked" and "möchte" is "would like"; "waren" is "were" and
        // "wären" "would be".
        "de" to setOf("schon", "konnte", "mochte", "waren")
    )

    /**
     * Whether [wordLower] is a word of [lang] rather than a bare spelling of
     * an accented one.
     */
    fun isWordItself(lang: String, wordLower: String): Boolean =
        byLang[lang]?.contains(wordLower) == true

    /** For the test that holds every entry to the shipped dictionaries. */
    internal fun entries(): Map<String, Set<String>> = byLang
}
