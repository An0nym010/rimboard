package com.rimboard.keyboard.model

import java.text.Normalizer

/**
 * Accents off, base letters left.
 *
 * One definition, in the layer both callers can reach. It began as a private
 * helper on the dictionary, which was the right place while spelling was the
 * only thing that asked — and the wrong place the moment gliding needed the
 * same answer, because [GlidePath] lives here and must not depend on the
 * engine. Copying it would have made a second opinion about what "the same
 * letter" means, and this project has already paid for having several opinions
 * about what a word is.
 *
 * `Dictionary.foldDiacritics` delegates here and keeps its name and behaviour.
 */
object Diacritics {

    /**
     * Letters that are atomic code points with no decomposition, so Unicode
     * normalisation cannot strip anything off them.
     */
    private val ATOMIC: Map<Char, Char> = mapOf(
        'ı' to 'i', 'ł' to 'l', 'ø' to 'o', 'đ' to 'd', 'ð' to 'd',
        'İ' to 'I', 'Ł' to 'L', 'Ø' to 'O', 'Đ' to 'D', 'Ð' to 'D'
    )

    /**
     * [s] with its accents removed.
     *
     * Returns the argument itself when there is nothing to do, which callers
     * rely on twice over: `fold(x) != x` is how three of them ask "did this
     * have an accent", and the identity spares a Unicode normalisation on the
     * keystroke that commits a word.
     */
    fun fold(s: String): String {
        // Nothing below 0x80 decomposes, nothing below 0x80 is a combining
        // mark, and every key of ATOMIC is above it — so for a purely ASCII
        // string the answer is the string, exactly rather than nearly.
        var ascii = true
        for (ch in s) if (ch.code >= 0x80) { ascii = false; break }
        if (ascii) return s
        val decomposed = Normalizer.normalize(s, Normalizer.Form.NFD)
        val sb = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            when {
                Character.getType(ch) == Character.NON_SPACING_MARK.toInt() -> {}
                else -> sb.append(ATOMIC[ch] ?: ch)
            }
        }
        return sb.toString()
    }

    /**
     * The base letter of [ch], or [ch] where there is no single one.
     *
     * The character-at-a-time form, for callers holding one letter rather than
     * a word — the glide decoder asking which key could have drawn it. "ß" and
     * "æ" come back unchanged, because their base is two letters and a key is
     * one; a caller wanting those wants [fold] on a string.
     */
    fun fold(ch: Char): Char {
        if (ch.code < 0x80) return ch
        ATOMIC[ch]?.let { return it }
        val d = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD)
        for (c in d) {
            if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) return c
        }
        return ch
    }
}
