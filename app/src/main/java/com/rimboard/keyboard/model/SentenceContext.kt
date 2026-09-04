package com.rimboard.keyboard.model

import java.util.Locale

/**
 * The next-word context read back from the text before the cursor.
 *
 * Pulled out of the service and made pure because the previous version
 * contradicted itself in the space of four lines: it decided whether the cursor
 * sat at the start of a sentence, and separately took the preceding word by
 * scanning *straight across* the sentence boundary it had just found. Both
 * lines were individually right and their combination was not, which is exactly
 * the shape of thing that survives review and cannot be seen from a screenshot.
 *
 * What it cost: after typing "Hello. " the openers appeared for a few
 * milliseconds and were then replaced by continuations of "hello", because the
 * cursor update arrived and overwrote the empty context the separator had just
 * set. The sentence-opener model was therefore almost unreachable — an empty
 * context needs no word characters at all in the preceding text, and a field
 * that empty is handled earlier. Worse, the next word committed was learned as
 * following "hello", teaching the model a pair that spans a full stop.
 */
object SentenceContext {

    /** Characters that end a sentence. A newline counts: pressing enter is a
     *  sentence break, and the rest of the service already treats it as one.
     *
     *  Internal rather than private because [SpellTokens] asks the same
     *  question of the sentence it is splitting, and this project has already
     *  shipped two copies of a list that silently fell behind the original. */
    internal const val ENDERS = ".!?\n"

    data class Context(
        val prevWord2: String,
        val prevWord: String,
        val atSentenceStart: Boolean
    )

    /**
     * Whether the cursor sits *inside* a word rather than at a boundary.
     *
     * Both neighbours have to be word characters. Looking only backwards is
     * not enough and gets the commonest case exactly wrong: with the cursor
     * between "hello " and "world" the character *after* it is a letter while
     * the cursor is at a perfectly ordinary word boundary.
     */
    fun insideWord(before: String, after: String): Boolean {
        val b = before.lastOrNull() ?: return false
        val a = after.firstOrNull() ?: return false
        return isWordChar(b) && isWordChar(a)
    }

    private fun isWordChar(c: Char): Boolean =
        c.isLetter() || c.isDigit() || Apostrophe.isMark(c)

    /**
     * The words [from] reads a context out of.
     *
     * Both apostrophes, because [isWordChar] says both are word characters and
     * the two ran the same text through different rules — one deciding whether
     * the cursor sits inside a word, the other deciding what a prediction is
     * keyed on. Only the second was missing U+2019, so in text written with
     * it "I don’t " gave `don` and `t` as the two context words: a fragment,
     * predicted from confidently, and filed in the learned n-grams under the
     * same fragment. That is 13.1% of the positions in the French fixture,
     * 4.9% in the English one.
     */
    private val WORD =
        Regex("[" + "\\p{L}\\p{N}" + Apostrophe.LIST_MARK + Apostrophe.CURLY + "]+")

    /**
     * A word from the text, in the form everything downstream is keyed on.
     *
     * Lower case, and the apostrophe written the way the data writes it — the
     * same pair of normalisations `SuggestionEngine.acceptedWord` does, and
     * for the same reason. Without the second, a context read back from curly
     * text would key on "don’t" while the same word typed here keys on
     * "don't", so the learned store would hold two rows for one word and
     * neither would have the other's count. See [Apostrophe].
     */
    private fun key(word: String?, locale: Locale): String =
        if (word == null) "" else Apostrophe.asWritten(word.lowercase(locale))

    /**
     * Reads [before] — the text immediately preceding the cursor — into the two
     * words a prediction is keyed on and whether a new sentence is starting.
     *
     * Words are taken from the current sentence only. That is the whole fix: at
     * a sentence start there is no preceding word, which is what makes the
     * empty context mean "start of a sentence" rather than "nothing to go on",
     * and one word into a new sentence the *second* word back is not the last
     * word of the previous one.
     *
     * [insideWord] is [insideWord]'s answer for this cursor position, and it
     * short-circuits everything below.
     */
    fun from(before: String, locale: Locale, insideWord: Boolean = false): Context {
        // A cursor in the middle of a word is not a place where a next word is
        // being chosen, and the last thing before it is not a word -- it is
        // half of one. Tapping into "wor|ld" left the strip predicting what
        // follows "wor", and where the fragment happened to be a word itself
        // ("work|ing", "cat|s") it predicted confidently from the wrong one.
        // Those chips are tappable, so the offer was to insert a word into the
        // middle of another.
        //
        // Reported as no context at all rather than as the words further back,
        // because the service's own test for whether to predict is "is there a
        // previous word", and there is no *next word position* here to predict
        // into. See RimBoardService.refreshContextFromCursor.
        if (insideWord) return Context("", "", atSentenceStart = false)
        // Only spaces and tabs are trimmed: a trailing newline is itself a
        // sentence break, and trimming it would remove the character being
        // tested for.
        val tail = before.trimEnd(' ', '\t')
        val atStart = tail.isEmpty() || tail.last() in ENDERS
        val lastBreak = before.indexOfLast { it in ENDERS }
        val sentence = if (lastBreak >= 0) before.substring(lastBreak + 1) else before
        val words = WORD.findAll(sentence).map { it.value }.toList()
        return Context(
            prevWord2 = key(words.getOrNull(words.size - 2), locale),
            prevWord = key(words.lastOrNull(), locale),
            atSentenceStart = atStart
        )
    }
}
