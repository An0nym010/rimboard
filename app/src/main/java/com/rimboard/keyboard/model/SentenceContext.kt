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
     * Reads [before] — the text immediately preceding the cursor — into the two
     * words a prediction is keyed on and whether a new sentence is starting.
     *
     * Words are taken from the current sentence only. That is the whole fix: at
     * a sentence start there is no preceding word, which is what makes the
     * empty context mean "start of a sentence" rather than "nothing to go on",
     * and one word into a new sentence the *second* word back is not the last
     * word of the previous one.
     */
    fun from(before: String, locale: Locale): Context {
        // Only spaces and tabs are trimmed: a trailing newline is itself a
        // sentence break, and trimming it would remove the character being
        // tested for.
        val tail = before.trimEnd(' ', '\t')
        val atStart = tail.isEmpty() || tail.last() in ENDERS
        val lastBreak = before.indexOfLast { it in ENDERS }
        val sentence = if (lastBreak >= 0) before.substring(lastBreak + 1) else before
        val words = Regex("""[\p{L}\p{N}']+""").findAll(sentence).map { it.value }.toList()
        return Context(
            prevWord2 = words.getOrNull(words.size - 2)?.lowercase(locale).orEmpty(),
            prevWord = words.lastOrNull()?.lowercase(locale).orEmpty(),
            atSentenceStart = atStart
        )
    }
}
