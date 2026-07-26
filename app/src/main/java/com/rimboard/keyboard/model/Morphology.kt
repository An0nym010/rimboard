package com.rimboard.keyboard.model

/**
 * A lightweight validity check for agglutinative languages.
 *
 * Turkish builds words by stacking suffixes onto a root — "ev" (house) →
 * "evde" (in the house) → "evlerimizden" (from our houses) — so the set of
 * valid surface forms is effectively unbounded. A frequency dictionary, however
 * large, only holds the forms that happened to appear in the corpus, so a
 * perfectly ordinary word like "kitaplarımızdan" is simply absent. The
 * suggestion engine then reads that absence as a misspelling and tries to
 * "correct" a word that was right all along, which is the single most common
 * way this keyboard feels wrong in Turkish.
 *
 * This does not analyse morphology properly — that is a whole finite-state
 * grammar and vowel-harmony model. It does the pragmatic thing that removes
 * most of the false corrections: peel recognised word-final suffixes off the
 * end and, if what remains is a word the dictionary *does* know, accept the
 * whole thing as valid. Turkish inflection is purely suffixing, so the stem is
 * always what is left at the front.
 *
 * It is a guard, never a generator: the only thing it can do is stop a
 * correction from being offered. The worst case is failing to flag a genuine
 * typo that happens to peel down to a real stem — mild, and far rarer than the
 * false corrections it prevents.
 *
 * Turkish only. Finnish and Hungarian are agglutinative too, but their suffix
 * inventories are not something to guess at — a wrong list would suppress real
 * corrections — so they are left until someone who knows them can add one. The
 * suffix sets are per-language so that has somewhere to go.
 */
object Morphology {

    fun isAgglutinative(lang: String): Boolean = lang == "tr"

    /**
     * Whether [wordLower] is a known stem carrying valid Turkish suffixes.
     *
     * [known] answers whether a candidate stem is in the dictionary. The walk
     * strips one recognised suffix at a time, longest first, and succeeds the
     * moment the remainder is known; [MAX_DEPTH] caps how many suffixes may
     * stack so a pathological input cannot loop for long.
     */
    fun stemIsKnown(lang: String, wordLower: String, known: (String) -> Boolean): Boolean {
        if (!isAgglutinative(lang) || wordLower.length < MIN_STEM) return false
        return peel(wordLower, MAX_DEPTH, known)
    }

    private fun peel(word: String, depth: Int, known: (String) -> Boolean): Boolean {
        if (word.length >= MIN_STEM && known(word)) return true
        if (depth == 0) return false
        for (suf in TR_SUFFIXES) {
            if (word.length - suf.length >= MIN_STEM && word.endsWith(suf)) {
                val stem = word.substring(0, word.length - suf.length)
                if (peel(stem, depth - 1, known)) return true
            }
        }
        return false
    }

    /** Turkish roots as short as two letters are common: ev, su, el, göz. */
    private const val MIN_STEM = 2

    /** Enough for a realistic stack (plural + possessive + case + a verb tail). */
    private const val MAX_DEPTH = 6

    /**
     * Atomic Turkish inflectional suffixes, all vowel-harmony variants, sorted
     * longest-first so the longest match is tried before a short one it
     * contains ("ler" before "er", "den" before "en").
     *
     * Composed by the iterative peel above rather than enumerated as
     * combinations, so "-ler-imiz-den" is three strips, not one entry. Kept to
     * the productive inflectional suffixes — plural, possessive, case, and the
     * common verb tenses and person endings — which is what covers everyday
     * words. Grammar, not vocabulary: this is the stable part of the language.
     */
    private val TR_SUFFIXES: List<String> = listOf(
        // possessive + plural
        "lerimiz", "larımız", "leriniz", "larınız", "lerinden", "larından",
        "imiz", "ımız", "umuz", "ümüz", "iniz", "ınız", "unuz", "ünüz",
        "leri", "ları", "ler", "lar",
        // verb tense / participle
        "iyor", "ıyor", "uyor", "üyor", "yor",
        "ecek", "acak", "eceğ", "acağ",
        "miş", "mış", "muş", "müş",
        "melı", "malı", "mekte", "makta",
        "dik", "dık", "duk", "dük", "tik", "tık", "tuk", "tük",
        // case
        "den", "dan", "ten", "tan",
        "nin", "nın", "nun", "nün",
        "yle", "yla", "nda", "nde",
        "de", "da", "te", "ta", "in", "ın", "un", "ün",
        "le", "la", "ye", "ya",
        // past / person / possessive singular / accusative-dative vowels
        "di", "dı", "du", "dü", "ti", "tı", "tu", "tü",
        "sin", "siniz", "sun", "sunuz",
        "im", "ım", "um", "üm", "si", "sı", "su", "sü",
        "i", "ı", "u", "ü", "e", "a", "n", "m"
    ).sortedByDescending { it.length }
}
