package com.rimboard.keyboard.model

import java.util.Locale

/**
 * Words the user has vouched for outside the dictionary, as words.
 *
 * Two sources feed this and neither hands over anything usable as it stands. A
 * contact's display name is "Anne-Marie O'Brien", or "Mum", or "Ahmet Yılmaz
 * (work)", or a phone number nobody named. An entry in Android's own personal
 * dictionary is usually one word and is sometimes "New York". What the spell
 * checker can use out of either is the parts that could plausibly appear in a
 * sentence, so the people you write to and the words you have taught the phone
 * stop coming back underlined.
 *
 * One rule rather than two, because the two would have been the same rule with
 * one difference, and a duplicated rule in this project has twice now been the
 * thing that drifted. The difference is a parameter: a contact holding digits
 * is a phone number and contributes nothing, while a dictionary entry holding
 * digits is something the user typed on purpose.
 *
 * Pure, and separate from the reading, because the reading needs a permission
 * and a ContentResolver and the deciding needs neither.
 *
 * Folded with [Locale.ROOT] on both sides of the comparison rather than with
 * the language being typed. That is not a shortcut: the two sides have to agree
 * with each other, and a Turkish dotted capital folds one way under `tr` and
 * another under `ROOT`. Using the same rule for the stored word and the typed
 * one is what makes them meet.
 */
object PersonalWords {

    /**
     * How many name parts are kept.
     *
     * An address book can hold thousands, and this set is consulted for every
     * word that is not in the dictionary. The cap is on the parts rather than
     * the contacts: one entry can contribute three or four.
     */
    const val MAX_NAMES = 4000

    /**
     * Below this a name part is not worth accepting.
     *
     * Two is the shortest thing that is plausibly a name. Anything shorter is
     * an initial, and accepting "a" or "j" as a word would quietly switch off
     * spell checking for two of the commonest typos there are.
     */
    private const val MIN_LENGTH = 2

    private fun isNameChar(c: Char) = c.isLetter() || c == '\'' || c == '\u2019'

    /**
     * The usable name parts of [displayNames], folded and de-duplicated.
     *
     * Split on anything that is not a letter, so "Anne-Marie" gives up both
     * halves and matches either written on its own, and "Ahmet Yılmaz (work)"
     * does not contribute "work" as a name... which it does, and that is the
     * honest limit of this: a bracketed note in a contact's name becomes an
     * accepted word. The alternative is guessing which parts of a display name
     * are a name, and being wrong about somebody's actual name is worse than
     * accepting one extra ordinary word.
     *
     * Anything holding a digit is dropped whole rather than split, so a contact
     * saved as a phone number contributes nothing instead of contributing the
     * letters around the digits.
     */
    fun of(
        entries: Sequence<String>,
        limit: Int = MAX_NAMES,
        dropEntriesWithDigits: Boolean = true
    ): Set<String> = index(entries, limit, dropEntriesWithDigits).keys

    /**
     * The same parts, each mapped to the spelling it was written with.
     *
     * [of] is this with the spellings thrown away, and for a long time that
     * was all anybody needed: the only caller was `acceptedWord`, which asks
     * whether a word is real and does not care how it is capitalised. Offering
     * one is a different act. A word somebody typed into Android's personal
     * dictionary is a declaration of a spelling -- capitals included, which is
     * the whole reason "Kubernetes" is in there and not "kubernetes" -- so a
     * keyboard that puts it on the strip in lower case has taken the
     * declaration and dropped the half of it that was hardest to type.
     *
     * First spelling wins where two entries fold together, which is arbitrary
     * and is the only sensible answer: nothing here can tell which of "Ada"
     * and "ada" the writer meant, and both were declared.
     */
    fun index(
        entries: Sequence<String>,
        limit: Int = MAX_NAMES,
        dropEntriesWithDigits: Boolean = true
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in entries) {
            if (out.size >= limit) break
            if (dropEntriesWithDigits && raw.any { it.isDigit() }) continue
            var i = 0
            while (i < raw.length && out.size < limit) {
                if (!isNameChar(raw[i])) {
                    i++
                    continue
                }
                var end = i
                while (end < raw.length && isNameChar(raw[end])) end++
                var s = i
                var e = end
                while (s < e && !raw[s].isLetter()) s++
                while (e > s && !raw[e - 1].isLetter()) e--
                if (e - s >= MIN_LENGTH) {
                    val part = raw.substring(s, e)
                    out.putIfAbsent(part.lowercase(Locale.ROOT), part)
                }
                i = end
            }
        }
        return out
    }

    /** Whether [word] is one of [words], folded the same way they were. */
    fun contains(words: Set<String>, word: String): Boolean =
        words.isNotEmpty() && word.lowercase(Locale.ROOT) in words

    /**
     * The folded [words] that continue [typed], shortest first.
     *
     * Shortest first because a declaration is not ranked by anything else --
     * there are no counts here and never will be, since nobody types into
     * Android's settings often enough to have a frequency -- and the shortest
     * continuation is the one nearest to being finished. Alphabetical after
     * that, so the strip does not reorder itself between two words of the same
     * length for reasons a hash map decided.
     *
     * Folded keys rather than spellings, which is why this takes the bare set
     * and both sources can use it: the caller merges these with the
     * dictionary's own candidates under a folded key. A declared word gets its
     * spelling put back at the end; a contact name does not, and that is
     * deliberate -- see where this is called from. [Locale.ROOT] on both sides
     * for the reason [contains] gives: the two have to agree with each other,
     * and the price is that a Turkish dotted capital is reachable by typing
     * the capital and not by typing the dotless letter.
     */
    fun startingWith(words: Set<String>, typed: String, limit: Int): List<String> {
        if (words.isEmpty() || typed.isEmpty()) return emptyList()
        val prefix = typed.lowercase(Locale.ROOT)
        var hits: ArrayList<String>? = null
        for (k in words) {
            if (k.length > prefix.length && k.startsWith(prefix)) {
                (hits ?: ArrayList<String>(4).also { hits = it }).add(k)
            }
        }
        val found = hits ?: return emptyList()
        found.sortWith(compareBy({ it.length }, { it }))
        return if (found.size > limit) found.subList(0, limit).toList() else found
    }

    /**
     * The [words] a swiped [path] could spell, closest fit first.
     *
     * The rule is [GlidePath.costOf] plus the two key tests, and the two tests
     * are not an optimisation. Fit alone excludes nothing -- every word made of
     * letters the layout draws has *some* finite distance from *some* path --
     * so without them a personal word could be offered for a swipe it has
     * nothing to do with. It was: teaching the keyboard "wolfram" put it on the
     * strip after swiping "helo".
     *
     * Here rather than in `UserData` because there are two personal lists now,
     * what the user has typed and what they declared in Android's own
     * dictionary, and a second copy of a rule is what has twice been the thing
     * that drifted in this project.
     */
    fun fitting(
        words: Set<String>,
        path: GlidePath,
        limit: Int
    ): List<Pair<String, Double>> {
        if (words.isEmpty()) return emptyList()
        val startKeys = path.startKeys
        val endKeys = path.endKeys
        var hits: ArrayList<Pair<String, Double>>? = null
        for (w in words) {
            if (w.length < 2) continue
            if (!startKeys.contains(w[0]) || !endKeys.contains(w[w.length - 1])) continue
            val cost = path.costOf(w)
            if (cost.isInfinite()) continue
            (hits ?: ArrayList<Pair<String, Double>>(4).also { hits = it }).add(w to cost)
        }
        val found = hits ?: return emptyList()
        found.sortBy { it.second }
        return if (found.size > limit) found.subList(0, limit).toList() else found
    }

    /**
     * The folded [words] within [maxDist] edits of [typed], nearest first.
     *
     * [distance] is supplied rather than imported: the measure that matters is
     * the keyboard-geometry-aware one in `Dictionary`, and this object is a
     * pure rule that a spell checker and a settings screen both reach without
     * an engine. The length gate in front of it is the same one every other
     * walk in this project puts there, and for the same reason -- the distance
     * is the expensive part and a length difference settles most candidates
     * without computing one.
     */
    fun within(
        words: Set<String>,
        typed: String,
        maxDist: Int,
        distance: (String, String) -> Int
    ): List<String> {
        if (words.isEmpty() || typed.isEmpty()) return emptyList()
        val lower = typed.lowercase(Locale.ROOT)
        var hits: ArrayList<Pair<String, Int>>? = null
        for (k in words) {
            if (k == lower) continue
            if (kotlin.math.abs(k.length - lower.length) > maxDist) continue
            val d = distance(lower, k)
            if (d in 1..maxDist) {
                (hits ?: ArrayList<Pair<String, Int>>(4).also { hits = it }).add(k to d)
            }
        }
        val found = hits ?: return emptyList()
        found.sortWith(compareBy({ it.second }, { it.first }))
        return found.map { it.first }
    }
}
