package com.rimboard.keyboard.model

/**
 * Whether the paste chip is offered on the suggestion strip, and what it says.
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
 *
 * "For as long as the field stays empty" was the whole of the old offer rule,
 * and it is the thing [offer] exists to replace. See [WINDOW_MS].
 */
object ClipChip {

    /**
     * How much of the clip to put on the chip before cutting it.
     *
     * Short on purpose. The chip is a *notice* that something was copied and
     * can be pasted, not a preview of the document — twelve characters is
     * enough to recognise your own clipboard and not enough to crowd out the
     * suggestions beside it. The cut is explicit rather than left to the view's
     * ellipsising, so the width is the same whatever the theme's text size.
     */
    const val MAX = 12

    /**
     * How long after the copy the chip is offered.
     *
     * The chip used to have no expiry at all: it was shown whenever the field
     * was empty and the system clipboard held text, so something copied on
     * Monday was still being offered on Friday, and every empty field in every
     * app opened with a stale clip sitting on the strip. The suggestion strip
     * is prime space and it was being spent on a guess that got worse by the
     * hour.
     *
     * A minute is the window this is worth. Pasting is something you do
     * *because* you just copied — you switch app, tap the field, and paste —
     * and past that the intent has gone; the clipboard panel is the right way
     * to reach an older clip, and it is one long-press away on this same chip.
     * The figure matches what Gboard appears to use, which is not documented
     * anywhere authoritative, so treat it as a defensible choice rather than a
     * measured one.
     */
    const val WINDOW_MS = 60_000L

    /**
     * The chip's label, or null when there should be no chip.
     *
     * Every reason to withhold it lives here rather than in the service, so
     * they can be enumerated in a test: the field has something in it already,
     * the clipboard holds nothing that can be pasted as text, this very clip
     * has already been pasted from this chip, the copy is older than
     * [WINDOW_MS], or its age cannot be established at all.
     *
     * [copiedAt] of zero means the age is unknown, and that is a refusal
     * rather than a pass. An offer with no expiry is the behaviour this
     * replaced, and defaulting back to it on the one path where the clock is
     * missing would leave the old bug alive in exactly the case nobody tests.
     *
     * [pastedAt] is the [copiedAt] of the clip last pasted from the chip, so a
     * clip retires when it is used and re-copying the same text brings it
     * back — a fresh copy carries a fresh timestamp. Without this the chip
     * returns the moment the field is cleared, offering to paste again the
     * thing that is already in the message.
     */
    fun offer(
        clip: String?,
        sensitive: Boolean,
        copiedAt: Long,
        now: Long,
        pastedAt: Long,
        fieldEmpty: Boolean,
        clipboardHasText: Boolean,
        inPasswordField: Boolean,
        fallback: String
    ): String? {
        if (!fieldEmpty || !clipboardHasText) return null
        if (copiedAt <= 0L) return null
        if (copiedAt == pastedAt) return null
        // A clip stamped in the future is a clock that moved, not a copy that
        // has not happened yet. Refusing both ends keeps a backwards jump from
        // parking the chip on the strip for as long as the clock is out.
        val age = now - copiedAt
        if (age < 0L || age > WINDOW_MS) return null
        return label(clip, sensitive, inPasswordField, fallback)
    }

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
     *
     * A null [clip] is the third case and means the caller could not identify
     * what is on the clipboard — not that the clipboard is empty. The chip is
     * still worth offering there; it just goes back to naming the action.
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
        return cut(hostOf(flat) ?: flat)
    }

    /**
     * A mask, of a fixed width so it does not disclose what it hides.
     *
     * Eight bullets whatever the length: a mask that grows with the secret
     * tells you how long the password is, which is worth having if you are
     * guessing one. Not a translated word, deliberately -- the bullet is what
     * every password field on the phone already uses for exactly this, and it
     * reads the same in every language the keyboard ships.
     */
    const val MASK = "••••••••"

    /**
     * What the clipboard panel's card shows for a clip.
     *
     * The panel's counterpart to [label], and the reason it exists is that it
     * did not: the panel built its own list of plain strings, so the
     * do-not-preview flag that every other part of the clipboard carries was
     * dropped on the last line before the card was drawn. See
     * `SensitiveClipTest`.
     *
     * Hides the content rather than dropping the entry, which is what [label]
     * already does on the strip -- the action stays offered, the text does
     * not. A masked card still pastes the real clip.
     */
    fun cardLabel(text: String, sensitive: Boolean): String =
        if (sensitive) MASK else text

    private val URL = Regex(
        "^https?://(?:www\\.)?([^/?#\\s]+)(.*)$", RegexOption.IGNORE_CASE
    )

    /**
     * The host of [flat] when the whole clip is one URL, else null.
     *
     * Twelve characters of a copied link is "https://www.…", which identifies
     * nothing at all — every link previews identically, so the chip answers
     * "did I copy the right thing" with "you copied a link". The host is the
     * part a person recognises, and it is what the browser's own address bar
     * emphasises for the same reason. The trailing marker says a path was
     * dropped, so "github.com" and "github.com/…" are not confusable.
     *
     * Only when the clip *is* the URL. "look at https://example.com/x" keeps
     * its leading words, because there the words are what identifies it.
     */
    private fun hostOf(flat: String): String? {
        val m = URL.matchEntire(flat) ?: return null
        val host = m.groupValues[1]
        if (host.isEmpty()) return null
        val rest = m.groupValues[2]
        return if (rest.isEmpty() || rest == "/") host else "$host/…"
    }

    private fun cut(s: String): String =
        // "…" rather than three dots: it is one character where three would eat
        // a quarter of the budget, and it is what every other truncation on the
        // strip already uses.
        if (s.length <= MAX) s else s.take(MAX).trimEnd() + "…"
}
