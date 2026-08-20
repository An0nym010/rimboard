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
                if (doubledLetter(stem, suf)) continue
                if (!harmonises(stem, suf)) continue
                if (peel(stem, depth - 1, known)) return true
            }
        }
        return false
    }

    /**
     * Whether stripping [suf] from [stem] is undoing a repeated keystroke
     * rather than a suffix.
     *
     * The list ends in single letters — "i", "ı", "u", "ü", "e", "a", "n", "m"
     * — because every one of them really is a Turkish suffix. The cost of that
     * showed up the first time this keyboard was used in anger: "nasılsınn",
     * a held final key, peels its second "n" and lands on "nasılsın", which is
     * a real word, so the guard declared the typo **correct**. It was never
     * underlined and the fix was never offered. Doubling the last letter is one
     * of the commonest typos there is, and the suffix inventory happens to
     * contain the letters people double.
     *
     * Only single-character suffixes are refused this way, and only when the
     * letter repeats. Turkish does not produce that shape: a vowel suffix after
     * a vowel takes a buffer consonant ("-ya", "-yı"), and "n" and "m" attach to
     * stems that do not already end in them. A doubled letter inside a stem —
     * "hakkı" — is untouched, because that doubling is not at the boundary.
     *
     * A run of three or more never reaches here: [Elongation] catches those
     * first, which is why "iyiyimmm" was offered a correction on the same
     * device where "nasılsınn" silently was not.
     */
    private fun doubledLetter(stem: String, suf: String): Boolean =
        suf.length == 1 && stem.isNotEmpty() && stem.last() == suf[0]

    // ---- vowel harmony -------------------------------------------------

    private const val BACK = "aıou"
    private const val FRONT = "eiöü"

    /** After one of these, a suffix-initial d hardens to t and c to ç. */
    private const val VOICELESS = "pçtkfhsş"

    /**
     * Suffixes that do not harmonise, and so must not be checked as if they
     * did.
     *
     * "-ki" is a Persian loan and keeps its vowel whatever sits in front of
     * it: "masadaki", never "masadakı". Checking it against the four-way vowel
     * would reject exactly the ordinary words it was added for. The rounded
     * "-kü" is the whole of the exception to the exception — "dünkü" and
     * "bugünkü", where the ü of dün and gün pulls it — so both spellings are
     * allowed and neither is harmony-checked.
     */
    private val INVARIANT = setOf("ki", "kü")

    private fun lastVowel(w: String): Char? = w.lastOrNull { it in BACK || it in FRONT }

    /**
     * The four-way high vowel a suffix must take after [stem].
     *
     * Frontness *and* rounding both carry over, which is what makes this four
     * ways rather than two: after a/ı it is ı, after e/i it is i, after o/u it
     * is u, after ö/ü it is ü.
     */
    private fun high(stem: String): Char = when (lastVowel(stem)) {
        'a', 'ı' -> 'ı'
        'e', 'i' -> 'i'
        'o', 'u' -> 'u'
        'ö', 'ü' -> 'ü'
        else -> ' '
    }

    /** The two-way low vowel: frontness only, never rounding. e or a. */
    private fun low(stem: String): Char = when (lastVowel(stem)) {
        in FRONT.toList() -> 'e'
        in BACK.toList() -> 'a'
        else -> ' '
    }

    /**
     * Whether [suf] is a form Turkish can actually produce after [stem].
     *
     * Turkish suffixes agree with the word in front of them, so most of the
     * strings that *look* like a stem plus a suffix are not words at all. The
     * guard had no idea: it peeled by spelling alone, so "bunın" came apart as
     * "bu" plus the genitive and was pronounced correct — while the real word
     * is "bunun", because after u the four-way vowel is u. Measured on a corpus
     * of realistic typos, that blindness was swallowing 28% of them, and
     * 46% of dropped letters: never underlined, never corrected, and with
     * autocorrect on, never noticed.
     *
     * Only the suffix's **first** vowel is checked, which is where the
     * agreement lives; the rest of a multi-syllable suffix is fixed by the
     * inventory that lists it. A suffix with no vowel at all ("n", "m") has
     * nothing to agree with and is left alone.
     *
     * The buffer consonants are skipped rather than special-cased: "nın" and
     * "yla" begin with a consonant that exists to keep two vowels apart, and
     * the vowel after it is the one that harmonises.
     *
     * Consonant agreement too, in the other direction: after a voiceless
     * consonant a suffix-initial d hardens to t and c to ç, which is why it is
     * "kitapta" and not "kitapda". A stem ending in a vowel or a voiced
     * consonant takes the soft form.
     */
    private fun harmonises(stem: String, suf: String): Boolean {
        if (stem.isEmpty()) return false
        if (suf in INVARIANT) return true
        val head = suf.firstOrNull() ?: return true
        val hardened = stem.last() in VOICELESS.toList()
        if (head == 'd' || head == 't') {
            if (hardened != (head == 't')) return false
        }
        if (head == 'c' || head == 'ç') {
            if (hardened != (head == 'ç')) return false
        }
        val v = suf.firstOrNull { it in BACK || it in FRONT } ?: return true
        return when (v) {
            'e', 'a' -> v == low(stem)
            else -> v == high(stem)
        }
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
        // "meli", not "melı": the necessitative is -mAlI, so a front-vowel stem
        // takes "meli" ("gelmeli") and a back-vowel one "malı" ("yapmalı").
        // "melı" mixes the two and is not a form Turkish can produce — it
        // matched nothing in the 200k-word list, where "meli" ends 173 entries.
        "meli", "malı", "mekte", "makta",
        "dik", "dık", "duk", "dük", "tik", "tık", "tuk", "tük",
        // case
        "den", "dan", "ten", "tan",
        "nin", "nın", "nun", "nün",
        "yle", "yla", "nda", "nde",
        "de", "da", "te", "ta", "in", "ın", "un", "ün",
        "le", "la", "ye", "ya",
        // past / person / possessive singular / accusative-dative vowels
        "di", "dı", "du", "dü", "ti", "tı", "tu", "tü",
        // All four variants, not the two that were here. With harmony
        // checked, a missing variant is no longer merely incomplete -- it
        // makes the legitimate form fail, because the spelling that would
        // have matched is the one that is absent.
        "sin", "sın", "sun", "sün",
        "siniz", "sınız", "sunuz", "sünüz",
        "im", "ım", "um", "üm", "si", "sı", "su", "sü",
        "i", "ı", "u", "ü", "e", "a", "n", "m",

        // ---- derivational, added 2026-08-20 --------------------------------
        //
        // The list above is inflection: the same word in a different role.
        // These five build *new* words, and they are here for the same reason
        // inflection is — they are productive, so a word list cannot hold all
        // their output, and what it does not hold gets corrected away.
        //
        // Deliberately these five and no more. They are the ones a curated set
        // of everyday Turkish actually needed; every further candidate was
        // either rare enough not to matter or too short to add without
        // measuring again.
        //
        // "-sIz" without: şekersiz, tuzsuz, gözsüz
        "siz", "sız", "suz", "süz",
        // "-lI" with: şekerli, tuzlu, gözlü
        "li", "lı", "lu", "lü",
        // "-lIk" the quality of: güzellik, tuzluk, gözlük
        "lik", "lık", "luk", "lük",
        // "-CI" the one who does: işçi, yolcu, kitapçı, sütçü. The c hardens
        // to ç after a voiceless consonant, which [harmonises] already checks.
        "ci", "cı", "cu", "cü", "çi", "çı", "çu", "çü",
        // "-ki" the one at: evdeki, masadaki. Harmony-exempt; see [INVARIANT].
        "ki", "kü"
    ).sortedByDescending { it.length }
}
