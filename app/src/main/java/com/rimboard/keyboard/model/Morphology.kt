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
 * Turkish is the only inventory written by hand. It used to be the only
 * inventory at all, and this paragraph used to say that Finnish and Hungarian
 * were "left until someone who knows them can add one" — which stopped being
 * true when `tools/derive_suffixes.py` started *counting* inventories from each
 * language's own word list rather than guessing at them. Twenty languages
 * ship one now, Finnish and Hungarian among them, and a reader who took this
 * paragraph at its word would go and write a list that already exists.
 * (Eighteen when that was written; `MorphologyInventoryTest` now counts them,
 * so the sentence cannot go stale again without a build failing.)
 *
 * What Turkish still has alone is vowel harmony, which is a fact about the
 * language that no amount of counting discovers. See [stemIsKnown]'s two
 * overloads: the hand-written walk uses harmony to filter, and the derived one
 * cannot, which is why the two are held to different floors.
 */
object Morphology {

    /**
     * Which languages get *generated* completions, as opposed to validated ones.
     *
     * Validation is the walk below and it runs wherever a counted inventory
     * exists, which is twenty languages. Generation — offering a form the word
     * list does not contain, built from a stem that it does — is gated here,
     * and here it is Turkish alone.
     *
     * **Finnish has the strongest measured claim to join it, and does not.**
     * Measured 2026-09-05 over held-out Tatoeba prose, share of word tokens
     * absent from the shipped 200,000-word dictionary, which is the ceiling on
     * what generation could ever recover:
     *
     *     fi 3.8%   tr 4.1%   hr 1.6%   cs 1.6%   pl 1.6%   sk 1.3%
     *     da 0.9%   en 0.2%
     *
     * Finnish sits beside Turkish and clear of everything else, and the words
     * are not a rounding error in keystrokes: they average **12.5 characters**
     * against 5.8 for the words the dictionary does hold, so 3% of the tokens
     * are about 6% of the typing. `build_prose_fixture.py` records what that
     * is worth: around three points of keystroke savings — larger than any
     * single constant measured in this engine, `GLIDE_CONTEXT_WEIGHT`'s 1.66
     * included.
     *
     * Not done here, because it is not a one-line change and must not be
     * pretended into one. The generator is `TurkishMorph.completionsFor`,
     * whose tables are Turkish; Finnish has its own vowel harmony and its own
     * morphophonology, and the counted 60-ending inventory deliberately
     * carries neither — the note above this object explains why a derived list
     * is held to a stricter floor than a written one. Generating from it
     * unguarded would put wrong forms in front of Finnish users, and the note
     * at the generation call site in `SuggestionEngine` records generated
     * Turkish candidates *costing* 0.6 points when they merely sat too high in
     * the ranking — and those were correct forms.
     *
     * What the numbers above establish is that the work is worth doing, and
     * roughly what it is worth. What they do not establish is that it is safe,
     * and that is the part still owed.
     */
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
        return peel(wordLower, TR_SUFFIXES, harmony = true, depth = MAX_DEPTH, known = known)
    }

    /**
     * The same walk for a language whose inventory was *counted* rather than
     * written, from its own word list. See `tools/derive_suffixes.py`.
     *
     * The note above this object says a wrong inventory would suppress real
     * corrections and that guessing at one was not on. Counting is not
     * guessing: split every word where the front half is itself a frequent
     * word, and the endings the language really uses rise to the top. Run
     * against Turkish -- the one language whose list was written by hand -- it
     * reproduces that list, which is the check that says the method works.
     *
     * Two things Turkish gets and this does not, both deliberately:
     *
     *  - **Vowel harmony.** It is a fact about Turkish and nothing counted from
     *    a word list knows it. Without it the walk is more willing, so a
     *    derived inventory is held to a floor on how short an ending may be,
     *    where Turkish can afford one- and two-letter endings because harmony
     *    filters them. Measured: admitting shorter endings roughly quadruples
     *    the rate at which a mistyped word is waved through (Turkish 0.3% at
     *    three, 2.8% at two, 10.2% at one).
     *
     *    The floor is per language and not three everywhere, which is what this
     *    said before the sweep that set it: Slavic and Germanic inflection is
     *    short, so nine languages are measured at two and keep their case
     *    endings, while Romance and Uralic would pay several times the cost for
     *    the extra gain and stay at three. `MIN_SUFFIX_BY_LANG` in
     *    `tools/derive_suffixes.py` is the list, `SuffixInventoryTest` carries
     *    the table it was chosen from, and one test there holds the shipped
     *    files to it -- a hand-edited one-letter ending is the 10.2% row, and
     *    nothing else would notice it.
     *  - **A closed list of two-letter roots.** That is an enumeration of
     *    Turkish, so a derived walk simply requires three characters of stem.
     */
    fun stemIsKnown(
        wordLower: String,
        suffixes: List<String>,
        maxDepth: Int = DERIVED_MAX_DEPTH,
        known: (String) -> Boolean
    ): Boolean {
        if (suffixes.isEmpty() || wordLower.length < DERIVED_MIN_STEM) return false
        // At least one ending has to come off. "Is this word in the list" is a
        // question the dictionary already answers, and answering it here as
        // well would make every listed word well-formed -- including the
        // misspellings a subtitle corpus carries. That is how "alot" stopped
        // being offered as "a lot": it is in the English list, so the walk
        // vouched for it without stripping anything, and a word the language's
        // own rules build is not offered a space.
        for (suf in suffixes) {
            if (wordLower.length - suf.length < DERIVED_MIN_STEM) continue
            if (!wordLower.endsWith(suf)) continue
            val stem = wordLower.substring(0, wordLower.length - suf.length)
            if (doubledLetter(stem, suf)) continue
            if (peel(stem, suffixes, harmony = false, depth = maxDepth - 1, known = known)) {
                return true
            }
        }
        return false
    }

    /**
     * Whether [wordLower] is a prefix this language builds words with, in front
     * of something the rest of the walk can vouch for.
     *
     * Everything above this reads the end of a word, because Turkish -- the
     * language the guard was written for -- is purely suffixing, and the note
     * at the top of this file says so in as many words: "the stem is always
     * what is left at the front." That is true of Turkish and false of most of
     * the eighteen languages that inherited the counted walk.
     *
     * `OutOfVocabularyTest` prints the consequence in its own output. Dutch
     * `verschuldigde` is rewritten to `verschuldigd` and German
     * `angeschlichen` to `geschlichen`: correct words destroyed by having a
     * prefix taken off them, where the same words with an *ending* stripped
     * would have been vouched for and left alone. Three of the five real
     * English words destroyed on a phone lost a derivational prefix.
     *
     * So: strip one recognised prefix, and accept if what remains is a known
     * stem or a word the counted ending walk can build. `ver+schuldigde`,
     * `по+вернусь`, `fel+ismertél`, `aus+setzte`, `ne+programovala`.
     *
     * ## One prefix, and never more
     *
     * [stemIsKnown] lets endings stack because stacking is what endings do.
     * Prefixes are not like that in these languages -- Czech `nevy-` and
     * `nepo-` are counted as prefixes in their own right precisely because
     * `ne-` in front of `vy-` is common enough to be one string -- so allowing
     * a second strip would buy what the inventory already holds while
     * multiplying the ways a typo comes apart. The composition that does pay
     * is prefix-then-*ending*, which is what this does.
     *
     * ## What it costs, and why the price is read on the whole walk
     *
     * A prefix is cheap to be wrong about compared with an ending, and the
     * reason is worth stating: a mistyped letter usually lands in the middle or
     * the end of a word, which leaves the prefix intact but breaks the stem,
     * and a broken stem is not known. Measured, the prefix walk adds 0.0 to
     * 0.4 percentage points of false accepts on its own.
     *
     * That number is never read on its own, though. `PrefixInventoryTest`
     * prices endings and prefixes *together*, against the ceiling the endings
     * already answer to, because a user typing a word is exposed to whatever
     * either half of the walk will wave through. Dutch is the language that
     * makes the difference matter: its prefix inventory is the best-earning of
     * any measured, and its ending inventory already spends most of the
     * budget, so the pair does not fit and Dutch ships no prefixes.
     */
    fun prefixedStemIsKnown(
        wordLower: String,
        prefixes: List<String>,
        suffixes: List<String>,
        known: (String) -> Boolean
    ): Boolean {
        // The shortest thing this can accept is a two-letter prefix on a
        // three-letter stem -- Polish "pobil", "po" + "bil". Twice the stem
        // floor was the first guess and it is wrong by one letter, which no
        // measurement here would have caught: every sample in
        // PrefixInventoryTest is six letters or longer.
        if (prefixes.isEmpty() || wordLower.length < DERIVED_MIN_STEM + 2) return false
        for (pre in prefixes) {
            if (wordLower.length - pre.length < DERIVED_MIN_STEM) continue
            if (!wordLower.startsWith(pre)) continue
            val rest = wordLower.substring(pre.length)
            // A known stem outright, or one the counted endings can build. The
            // second is where most of the value is: a prefixed word is usually
            // inflected too, and `aussetzte` is `aus` plus `setzte` plus
            // nothing the list holds until the ending comes off as well.
            if (known(rest)) return true
            if (stemIsKnown(rest, suffixes, known = known)) return true
        }
        return false
    }

    /**
     * Whether [wordLower] is a known word carrying suffixes **after an
     * apostrophe** — "Paris'e", "Türkiye'de", "ABD'de", "Rusya'nın".
     *
     * Turkish attaches its case endings to proper nouns and acronyms across an
     * apostrophe, and this is not a rare flourish: every sentence naming a
     * person, a place or an organisation has one. Measured over the Turkish
     * prose in `src/test/fixtures`, **every single apostrophe word was rejected
     * — 12 of the 15 unknown words in the corpus**, which is 80% of everything
     * the keyboard did not recognise. In English, French and Italian the
     * equivalent forms are handled and the same count is zero.
     *
     * ## Why [Elision] cannot do this and [stemIsKnown] cannot either
     *
     * [Elision] asks whether the two halves are *both dictionary entries with
     * the apostrophe attached to one of them*. That is the right question for
     * `l'homme` and `don't`, whose lists really do hold `l'` and `'t`. The
     * Turkish list holds no apostrophe entry at all, so it matches nothing —
     * Elision's own documentation says as much and treats it as a guarantee.
     *
     * Nor is it enough to delete the apostrophe and hand the result to
     * [stemIsKnown]. That works for nine of the twelve and fails for exactly
     * the ones the apostrophe exists to write:
     *
     *  - **"ABD'de"** joins to "abdde", a doubled consonant Turkish never
     *    writes, and the suffix disagrees with the stem's vowel besides. ABD is
     *    said "a-be-de", so it takes a front-vowel ending after a back-vowel
     *    spelling.
     *
     * **That is the whole point of the apostrophe: it marks a boundary where
     * the spelling of the stem does not predict the suffix.** So harmony is
     * deliberately *not* checked across it, where [peel] checks it everywhere
     * else. Applying the rule that governs ordinary words to the mark whose
     * job is to say "this is not an ordinary word" would be a contradiction.
     *
     * ## What holds it back
     *
     * The head must be a word the dictionary vouches for, which is what stops
     * this accepting anything with a quote in it: "İskenderiye'ye" is still
     * rejected, because the corpus has never seen "iskenderiye" and nothing
     * here can invent it. The tail must decompose entirely into recognised
     * suffixes, and [MAX_DEPTH] bounds the search.
     *
     * It does accept tails nobody would write, because the inventory ends in
     * single letters that really are suffixes — the same trade [Elision]
     * documents for "in't". The cost is a word left un-underlined; the cost of
     * the alternative was underlining "Paris'e" in every app on the phone.
     */
    fun apostropheSuffixed(
        lang: String, wordLower: String, known: (String) -> Boolean
    ): Boolean {
        if (!isAgglutinative(lang)) return false
        val i = wordLower.indexOfFirst { it == '\'' || it == '’' }
        // Head at least a stem long, and something after the mark to be a
        // suffix. A trailing apostrophe is a quote, not a boundary.
        if (i < MIN_STEM || i >= wordLower.length - 1) return false
        if (!known(wordLower.substring(0, i))) return false
        return suffixChain(wordLower.substring(i + 1), MAX_DEPTH)
    }

    /** Whether [t] is nothing but recognised suffixes, stacked. */
    private fun suffixChain(t: String, depth: Int): Boolean {
        if (t.isEmpty()) return true
        if (depth == 0) return false
        for (suf in TR_SUFFIXES) {
            if (t.length >= suf.length && t.endsWith(suf) &&
                suffixChain(t.substring(0, t.length - suf.length), depth - 1)
            ) return true
        }
        return false
    }

    private fun peel(
        word: String,
        suffixes: List<String>,
        harmony: Boolean,
        depth: Int,
        known: (String) -> Boolean
    ): Boolean {
        // The two-letter-root allowance is an enumeration of Turkish, so a
        // counted inventory asks for three characters of stem instead.
        val floor = if (harmony) MIN_STEM else DERIVED_MIN_STEM
        val rootOk = if (harmony) rootShaped(word) else true
        if (word.length >= floor && rootOk && known(word)) return true
        if (depth == 0) return false
        for (suf in suffixes) {
            if (word.length - suf.length >= floor && word.endsWith(suf)) {
                val stem = word.substring(0, word.length - suf.length)
                if (doubledLetter(stem, suf)) continue
                if (harmony && !harmonises(stem, suf)) continue
                if (peel(stem, suffixes, harmony, depth - 1, known)) return true
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

    /**
     * The shortest thing that may be called a stem, and what one has to be.
     *
     * Two letters, because two-letter Turkish roots are real and ordinary: ev,
     * su, el. Raising this to three was tried and is wrong — "evde" and
     * "evlerimizden" peel to "ev" and are pinned in MorphologyTest as words
     * this guard exists to accept. Six tests said so.
     *
     * Length was the wrong question. Frequency cannot tell a root from an
     * abbreviation, and **209 two-letter strings clear
     * [Dictionary.STEM_MIN_FREQ] in the Turkish list** — tv, km, cd, dr, mr,
     * dj, cm, kg, ss, st, mm, plus English that leaks out of subtitles: my,
     * by, up, so, no, we. Every one of them was vouching for anything with a
     * plausible suffix behind it, which is how "sskin" became accepted Turkish
     * by way of "ss", and "ssnki" and "bielikte" the same way.
     *
     * So a two-letter stem has to be on a list. Measured on one-key slips
     * against common words, and on ordinary inflected Turkish the
     * 200,000-word list does not hold — taken from the corpus rather than
     * generated, so no form in it was invented by this file's own rules:
     *
     *     two-letter stems         typos accepted   ordinary Turkish accepted
     *     any frequent string           5.5%              30.9%
     *     [TR_SHORT_ROOTS]              4.7%              30.8%
     *     three letters required        4.5%              29.7%   breaks evde
     *
     * The other constant in reach, [Dictionary.STEM_MIN_FREQ], is a much worse
     * lever and was measured too: 500 to 2000 buys 1.3 points of typo
     * rejection and gives up *eleven* points of ordinary Turkish, because what
     * it filters is real stems rather than short ones.
     */
    private const val MIN_STEM = 2

    /**
     * The shortest stem a *counted* inventory will vouch for.
     *
     * Three, matching `tools/derive_suffixes.py`, which only counted an ending
     * off a stem of three or more. Two different floors would be the derivation
     * and the walk disagreeing about what a stem is, and the walk would then
     * accept splits the counting never saw.
     */
    private const val DERIVED_MIN_STEM = 3

    /**
     * How many counted endings may stack on one stem.
     *
     * Turkish gets [MAX_DEPTH], because stacking is what Turkish *is*. The
     * languages with a counted inventory are not agglutinative, and letting
     * their endings pile up buys almost nothing while multiplying the ways a
     * mistyped word can be taken apart into something that happens to end in a
     * known stem. Swept in `SuffixInventoryTest`.
     */
    private const val DERIVED_MAX_DEPTH = 2

    /** Whether a candidate stem is shaped like a root at all. */
    private fun rootShaped(stem: String): Boolean =
        stem.length > 2 || stem in TR_SHORT_ROOTS

    /**
     * Every two-letter Turkish root a suffix attaches to: nouns, adjectives
     * and verb stems.
     *
     * Closed on purpose. Turkish two-letter roots are enumerable, and the cost
     * of one being missing is this guard declining to vouch for a real word —
     * a red underline under something correct — rather than a typo waved
     * through. Bare particles are absent (mi, ki, ve, and "de" as a clitic):
     * they are not what a suffix attaches to.
     */
    private val TR_SHORT_ROOTS = setOf(
        // nouns and adjectives
        "ev", "su", "el", "at", "ad", "iş", "iç", "üç", "az", "ay", "on",
        "ok", "ot", "öz", "ön", "ün", "üs", "us", "iz", "ip", "ek", "eş",
        "er", "oy", "av", "ağ", "un", "ur", "uç", "il",
        // verb roots
        "aç", "al", "as", "ol", "öl", "öp", "ye", "de", "et", "in", "em",
        "ez", "uy", "üz", "ör", "öv"
    )

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
