package com.rimboard.keyboard.model

/**
 * Words like "hellooo" — a real word with a letter held down.
 *
 * These are in the shipped dictionaries, and not by mistake: the frequency
 * lists are built from web text, where people write that way often enough for
 * "hellooo", "helloooo" and "hellooooo" all to clear the cutoff. The keyboard
 * therefore treated them as correctly spelled, offered them as completions of
 * "hello", and never marked them as unrecognised — which is exactly what a
 * user reports as "the spell checker does not notice this".
 *
 * Dropping every word with a trebled letter would be too blunt: "brrr" and
 * "shhh" are words, and a language this keyboard does not ship yet may have
 * others. So a word only counts as an elongation when *collapsing the run
 * produces a word the dictionary already knows*. "hellooo" collapses to
 * "hello", so it is one. "brrr" collapses to "br" and "brr", and if neither is
 * a known word then "brrr" is simply an unknown word, which is a different and
 * much safer thing to call it.
 *
 * And when there is a word underneath it at all: "www" is not "w" held down,
 * it is "www". See [collapsed].
 */
object Elongation {

    /** A letter repeated at least this many times is never ordinary spelling. */
    const val RUN = 3

    /** Whether [word] contains a run of [RUN] or more identical letters. */
    fun hasRun(word: String): Boolean {
        var run = 1
        for (i in 1 until word.length) {
            if (word[i] == word[i - 1]) {
                run++
                if (run >= RUN) return true
            } else {
                run = 1
            }
        }
        return false
    }

    /**
     * The spellings [word] might be an elongation of: every run of three or
     * more collapsed to one letter, and the same collapsed to two.
     *
     * Both, because either can be the real spelling — "hellooo" wants one "o"
     * and "coool" wants two — and there is no way to tell which without asking
     * the dictionary, which is the caller's job.
     */
    fun collapsed(word: String): List<String> {
        if (!hasRun(word)) return emptyList()
        // A word that is one letter over and over is not a word with a letter
        // held down -- there is nothing underneath it to recover. "www" was
        // the case that showed it: absent from every shipped dictionary,
        // collapsing to "w" (9,179 in English) and to "ww" (215), so the
        // corrector replaced it with a single letter and typing "www." into a
        // message came out as "W.". Nineteen of the 22 languages did it, and
        // so did "zzz", "mmm", "aaa" and "ooo" -- every one of them a thing
        // people type on purpose. The three that did not are Greek, Russian
        // and Ukrainian, whose alphabets have no w to begin with.
        //
        // Length is the wrong test here and was tried first: "www" collapses
        // to "ww" as well, which is in the English list, so a floor on the
        // base would have turned "www" into "ww" instead of into "w".
        // A loop rather than `toSet()`: this sits on the keystroke path and
        // the set was an allocation per call.
        if (word.all { it == word[0] }) return emptyList()
        return listOf(collapseTo(word, 1), collapseTo(word, 2))
            .filter { it != word }
            .distinct()
    }

    private fun collapseTo(word: String, keep: Int): String {
        val sb = StringBuilder(word.length)
        var i = 0
        while (i < word.length) {
            var j = i
            while (j < word.length && word[j] == word[i]) j++
            val len = j - i
            // Runs shorter than RUN are ordinary spelling — "book", "spell" —
            // and are left exactly as they are.
            repeat(if (len >= RUN) keep else len) { sb.append(word[i]) }
            i = j
        }
        return sb.toString()
    }
}
