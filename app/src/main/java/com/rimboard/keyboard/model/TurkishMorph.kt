package com.rimboard.keyboard.model

/**
 * Turkish word *generation*, as opposed to the validity check in [Morphology].
 *
 * [Morphology] can only ever stop a correction from being offered: it peels
 * suffixes off and asks whether what is left is a word. That fixed the false
 * corrections, but it left the rest of the keyboard blind. Typing "kitapl"
 * offered no completion, because "kitaplar" happens not to be in the frequency
 * list; typing "kitaplarimizdan" on bare keys got no accents back, because
 * accent restoration works by looking a word up and that form is not there
 * either. In an agglutinative language most of what anyone types is absent from
 * any word list, so "look it up" is the wrong primitive.
 *
 * Turkish inflection is regular enough to run forwards instead. The suffix
 * vowels are not free choices — they are determined by the last vowel of what
 * they attach to, by a rule with no exceptions in the productive inflections:
 *
 *  - **Two-way harmony** (written `A` below): `a` after a back vowel
 *    (a, ı, o, u), `e` after a front one (e, i, ö, ü).
 *  - **Four-way harmony** (`I`): `ı` back unrounded, `u` back rounded,
 *    `i` front unrounded, `ü` front rounded.
 *  - **Consonant assimilation** (`D`): `t` after a voiceless consonant
 *    (p ç t k f h s ş), `d` otherwise.
 *
 * So `kitap` + `lAr` is deterministically `kitaplar`, and `ev` + `lAr` is
 * `evler`. Given a stem the dictionary does know, every ordinary inflected form
 * of it can be produced — which is what lets completion and accent restoration
 * work on words no corpus contains.
 *
 * Scope is deliberately the productive **inflectional** suffixes: plural,
 * possessive, case, and the two that stack on top of those. Derivation (turning
 * a noun into a verb into a noun again) is where Turkish morphology gets deep
 * and where a naive generator starts inventing words, so it is left out. The
 * output is also always checked against what the user actually typed before it
 * is shown — this proposes, it never asserts.
 */
object TurkishMorph {

    /**
     * Inflection templates, in rough order of how often they turn up.
     *
     * `A` and `I` are the harmony archiphonemes above, `D` the assimilating
     * stop, and `Y` a buffer consonant that appears only after a vowel — "ev-e"
     * but "kitap-a" versus "araba-ya". Order matters: it is the ranking of the
     * completions offered, and a plural should be proposed before an ablative
     * possessive.
     */
    private val TEMPLATES: List<String> = listOf(
        "lAr",          // plural: kitaplar
        "I",            // accusative / 3sg possessive: kitabı, evi
        "YA",           // dative: eve, kitaba
        "DA",           // locative: evde, kitapta
        "DAn",          // ablative: evden, kitaptan
        "In",           // genitive / 2sg possessive: evin
        "Im",           // 1sg possessive: evim
        "ImIz",         // 1pl possessive: evimiz
        "InIz",         // 2pl possessive: eviniz
        "lArI",         // plural + possessive: kitapları
        "lArA",         // plural + dative: kitaplara
        "lArDA",        // plural + locative: kitaplarda
        "lArDAn",       // plural + ablative: kitaplardan
        "lArIn",        // plural + genitive: kitapların
        "lArImIz",      // plural + 1pl possessive: kitaplarımız
        "lArInIz",      // plural + 2pl possessive: kitaplarınız
        "lArImIzDAn",   // the textbook example: kitaplarımızdan
        "lArImIzDA",
        "lArInIzDAn",
        "YlA",          // instrumental: evle, arabayla
        "sIz",          // privative: evsiz
        "lI",           // "with": evli
        "cI",           // agent: kitapçı
        "lIk"           // abstract: kitaplık
    )

    private const val BACK = "aıou"
    private const val FRONT = "eiöü"
    private const val ROUNDED = "ouöü"
    private const val VOICELESS = "pçtkfhsş"

    /** Every vowel, in both harmony classes. */
    private const val VOWELS = "aıoueiöü"

    /**
     * Every inflected form of [stem] this generator knows how to build.
     *
     * Returns an empty list for anything that does not look like a Turkish
     * stem — no vowel to harmonise with means there is no right answer, and
     * guessing one would produce noise.
     */
    fun inflections(stem: String): List<String> {
        if (stem.length < MIN_STEM || lastVowel(stem) == null) return emptyList()
        val soft = softened(stem)
        return TEMPLATES.mapNotNull { t ->
            // Consonant softening happens only before a vowel: "kitap" stays
            // itself in "kitaplar" but becomes "kitab-" in "kitabı".
            val base = if (soft != null && startsWithVowel(t)) soft else stem
            apply(base, t)
        }.distinct()
    }

    /**
     * The softened stem — p ç t k becoming b c d ğ before a vowel — or null
     * where the rule does not apply.
     *
     * Restricted to stems with more than one vowel, which is the standard
     * approximation: polysyllabic "kitap" softens to "kitab-ı", monosyllabic
     * "top" does not ("topu", not "tobu"). It is an approximation and not a
     * law — "at" behaves, "ad" does not — but a wrong guess here costs nothing,
     * because every generated form is filtered against what the user actually
     * typed before it is offered. Over-generating is safe; under-generating
     * means a real word silently gets no suggestion.
     */
    internal fun softened(stem: String): String? {
        if (stem.length < 3) return null
        // "nk" softens even in a single syllable — renk/rengi, ahenk/ahengi —
        // which is a real exception to the polysyllable rule rather than an
        // oversight in it.
        val nk = stem.endsWith("nk")
        if (!nk && stem.count { it in VOWELS } < 2) return null
        val soft = when (stem.last()) {
            'p' -> 'b'
            'ç' -> 'c'
            't' -> 'd'
            'k' -> if (stem.length >= 2 && stem[stem.length - 2] in "nlr") 'g' else 'ğ'
            else -> return null
        }
        return stem.dropLast(1) + soft
    }

    /** The reverse, for finding a stem inside something already softened. */
    internal fun hardened(prefix: String): String? {
        val hard = when (prefix.lastOrNull()) {
            'b' -> 'p'
            'c' -> 'ç'
            'd' -> 't'
            'ğ', 'g' -> 'k'
            else -> return null
        }
        return prefix.dropLast(1) + hard
    }

    /** Whether a template's first sound is a vowel, which is what triggers softening. */
    private fun startsWithVowel(template: String): Boolean =
        template.firstOrNull() in setOf('A', 'I', 'Y')

    /**
     * Attaches one template to a stem, resolving harmony left to right.
     *
     * Resolved progressively rather than from the stem alone: in `lArImIz` the
     * `I` harmonises with the `A` that the same suffix just produced, not with
     * the stem's final vowel. Doing it in one pass off the stem gives
     * "kitaplarımız" the wrong vowels the moment a suffix has two of them.
     */
    internal fun apply(stem: String, template: String): String? {
        val sb = StringBuilder(stem)
        for (ch in template) {
            when (ch) {
                'A' -> sb.append(if (isBack(lastVowel(sb) ?: return null)) 'a' else 'e')
                'I' -> sb.append(fourWay(lastVowel(sb) ?: return null))
                'D' -> sb.append(if (sb.isNotEmpty() && sb.last() in VOICELESS) 't' else 'd')
                // Buffer consonant: only where a vowel would otherwise collide
                // with the suffix's own vowel.
                'Y' -> if (sb.isNotEmpty() && sb.last() in VOWELS) sb.append('y')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun isBack(v: Char): Boolean = v in BACK

    private fun fourWay(v: Char): Char = when {
        v in BACK && v in ROUNDED -> 'u'
        v in BACK -> 'ı'
        v in ROUNDED -> 'ü'
        else -> 'i'
    }

    private fun lastVowel(s: CharSequence): Char? {
        for (i in s.length - 1 downTo 0) if (s[i] in VOWELS) return s[i]
        return null
    }

    /**
     * Completions for [typed] built from whichever of its own prefixes is a
     * known word.
     *
     * The stem is found by walking the typed text from the longest prefix down,
     * so "kitaplar" finds "kitap" rather than the also-valid-but-wrong "ki".
     * Only the first stem found is used: two different stems would produce two
     * unrelated sets of words, and the longer one is the one being typed.
     *
     * [known] is asked whether a prefix is a real word. Results are only those
     * that actually continue what the user has typed, so this can add
     * candidates but never change the word in front of them.
     */
    fun completionsFor(typed: String, limit: Int, known: (String) -> Boolean): List<String> {
        if (typed.length < MIN_STEM + 1) return emptyList()
        val stem = stemOf(typed, known) ?: return emptyList()
        val out = ArrayList<String>(limit)
        for (form in inflections(stem)) {
            if (form.length > typed.length && form.startsWith(typed)) {
                out.add(form)
                if (out.size >= limit) break
            }
        }
        return out
    }

    /**
     * The longest prefix of [typed] that is a known word, or the word it
     * softened from.
     *
     * Longest first, because "kitaplar" contains both "kitap" and the also-real
     * but irrelevant "ki". The softening check is what lets a stem be found
     * inside "kitabımız", where the prefix present in the text ("kitab") is not
     * the dictionary form ("kitap").
     */
    private fun stemOf(typed: String, known: (String) -> Boolean): String? {
        for (end in (typed.length - 1).downTo(MIN_STEM)) {
            val prefix = typed.substring(0, end)
            if (known(prefix)) return prefix
            hardened(prefix)?.let { if (known(it)) return it }
        }
        return null
    }

    /**
     * The properly accented form of a bare-keys inflected word, or null.
     *
     * "kitaplarimizdan" typed without accents is not in any dictionary and
     * never will be, so the ordinary folded-index lookup cannot reach it. This
     * generates the real forms of a stem and folds *those* to compare — the
     * accents come from the generator rather than from a lookup table.
     *
     * [accentedStem] resolves a bare stem to its dictionary spelling, so the
     * stem may be unaccented too: "kagitlarimiz" finds "kağıt" before building
     * anything on it.
     */
    fun accentedInflection(
        bareTyped: String,
        fold: (String) -> String,
        accentedStem: (String) -> String?
    ): String? {
        if (bareTyped.length < MIN_STEM + 1) return null
        for (end in (bareTyped.length - 1).downTo(MIN_STEM)) {
            val barePrefix = bareTyped.substring(0, end)
            for (stem in listOfNotNull(accentedStem(barePrefix), hardened(barePrefix))) {
                for (form in inflections(stem)) {
                    // Must differ from what was typed. "evler" carries no
                    // accents to restore, and generating it back from "ev"
                    // would report the word as a correction of itself — which
                    // reads downstream as "this is misspelled".
                    if (form != bareTyped && fold(form) == bareTyped) return form
                }
            }
        }
        return null
    }

    /** Matches [Morphology]: two-letter roots are ordinary Turkish. */
    private const val MIN_STEM = 2
}
