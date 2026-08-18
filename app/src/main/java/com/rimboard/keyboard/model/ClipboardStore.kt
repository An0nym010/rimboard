package com.rimboard.keyboard.model

/**
 * The clipboard history and the pinned clips, as a thing that can be tested.
 *
 * This lived as four fields and five methods spread through the IME service,
 * which is where it could not be. Pulling it out immediately showed two faults
 * that had been sitting in it, both about the "do not preview this" flag that
 * password managers set — the one piece of state here where being wrong has a
 * cost beyond tidiness.
 *
 * Nothing here is persisted and nothing here writes: history is RAM-only by
 * design, on the reasoning that a durable list of everything someone copied is
 * exactly what this keyboard exists not to keep. The pinned list is written by
 * the caller, which owns the file.
 */
class ClipboardStore(private val cap: Int = 10) {

    /** [sensitive] is the copier's `EXTRA_IS_SENSITIVE`: never preview this. */
    data class Entry(val text: String, val at: Long, val sensitive: Boolean = false)

    private val history = ArrayDeque<Entry>()
    private val pinned = ArrayList<Entry>()

    /**
     * Records a newly copied clip. Returns false when it was not taken.
     *
     * A pinned clip is not added: it is already kept, and adding it would show
     * the same text twice.
     */
    fun add(text: String, at: Long, sensitive: Boolean): Boolean {
        if (text.isBlank()) return false
        if (pinned.any { it.text == text }) return false
        history.removeAll { it.text == text }
        history.addFirst(Entry(text, at, sensitive))
        trim()
        return true
    }

    private fun trim() {
        while (history.size > cap) history.removeLast()
    }

    /**
     * Drops everything older than [timeoutMin] minutes. A timeout of zero or
     * less means never.
     */
    fun prune(now: Long, timeoutMin: Int) {
        if (timeoutMin <= 0) return
        val cutoff = now - timeoutMin * 60_000L
        history.removeAll { it.at < cutoff }
    }

    /**
     * The history as it stands *after* pruning at [now].
     *
     * Pruning here rather than leaving it to the caller is the fix for the
     * second fault: the paste chip on the suggestion strip read the newest
     * entry directly, and nothing on that path pruned, so a clip stayed
     * previewable on screen after the auto-clear time the user had set. The
     * clipboard panel pruned before drawing and the chip did not, which is why
     * only one of the two obeyed the setting.
     */
    fun history(now: Long, timeoutMin: Int): List<Entry> {
        prune(now, timeoutMin)
        return history.toList()
    }

    /** The newest unexpired clip, or null. */
    fun latest(now: Long, timeoutMin: Int): Entry? = history(now, timeoutMin).firstOrNull()

    fun pinnedTexts(): List<String> = pinned.map { it.text }

    fun pinnedEntries(): List<Entry> = pinned.toList()

    /**
     * Pins [text], moving it out of the history.
     *
     * The entry is carried across rather than rebuilt, so a clip marked
     * sensitive stays marked. That is the first fault this extraction found:
     * pinning and then unpinning used to rebuild the entry from the text
     * alone, and the flag was silently dropped — a password a manager had
     * explicitly asked not to be previewed became previewable by being pinned
     * and unpinned again.
     */
    fun pin(text: String, at: Long) {
        val existing = history.firstOrNull { it.text == text }
        history.removeAll { it.text == text }
        pinned.removeAll { it.text == text }
        pinned.add(0, existing?.copy(at = at) ?: Entry(text, at))
    }

    /** Unpins [text], returning it to the top of the history with its flag. */
    fun unpin(text: String, at: Long) {
        val existing = pinned.firstOrNull { it.text == text }
        pinned.removeAll { it.text == text }
        history.removeAll { it.text == text }
        history.addFirst(existing?.copy(at = at) ?: Entry(text, at))
        trim()
    }

    /** Restores pinned clips from storage, newest first. */
    fun setPinned(entries: List<Entry>) {
        pinned.clear()
        pinned.addAll(entries)
    }

    fun clearHistory() = history.clear()

    fun clearAll() {
        history.clear()
        pinned.clear()
    }
}
