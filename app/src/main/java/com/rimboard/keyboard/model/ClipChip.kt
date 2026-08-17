package com.rimboard.keyboard.model

/**
 * What the paste chip on the suggestion strip says.
 *
 * It used to say "Paste", which tells you an action is available but not
 * whether it is the thing you meant to copy — and the common case is having
 * copied something a moment ago and wanting to confirm it before pasting it
 * into a message. Showing the text answers that at a glance.
 *
 * Which makes *not* showing it the part that needs care. The clipboard is
 * where password managers put passwords, and the strip sits above the keyboard
 * in plain view of anyone near the screen, for as long as the field stays
 * empty. So there are cases that keep the old label, and they are the reason
 * this is a function with a test rather than a string substitution.
 */
object ClipChip {

    /**
     * How much of the clip to put on the chip.
     *
     * The view is one line and ellipsises, so this is not what stops it
     * overflowing — it is a bound on how much of a copied document is built
     * into a string on every strip update.
     */
    const val MAX = 60

    /**
     * The chip's label: [clip] made fit for one line, or [fallback] where the
     * content should not be shown at all.
     *
     * [sensitive] is the clipboard's own `EXTRA_IS_SENSITIVE` flag, which is
     * how a password manager says "do not preview this" — Android's own
     * clipboard preview honours it and so does this. [inPasswordField] covers
     * the case the flag does not: someone pasting into a password field is
     * probably pasting a credential whether or not the app that copied it
     * bothered to say so.
     */
    fun label(
        clip: String?,
        sensitive: Boolean,
        inPasswordField: Boolean,
        fallback: String
    ): String {
        if (clip == null || sensitive || inPasswordField) return fallback
        // Whitespace is collapsed rather than trimmed away: a copied paragraph
        // rendered on one line runs its last word into the next one, and
        // "endBegin" is a worse preview than no preview.
        val flat = clip.replace(Regex("\\s+"), " ").trim()
        if (flat.isEmpty()) return fallback
        return if (flat.length <= MAX) flat else flat.take(MAX).trimEnd() + "…"
    }
}
