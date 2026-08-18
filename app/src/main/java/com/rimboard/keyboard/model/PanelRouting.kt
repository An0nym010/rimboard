package com.rimboard.keyboard.model

/**
 * Which surface is showing, and what has to be shut down to show another.
 *
 * The keyboard has two families of surface that cover different things and are
 * therefore easy to leave both open at once. *Panels* — clipboard, editing,
 * tools — replace the keys. *Pickers* — emoji, GIF, translate — sit above them
 * in their own container. Opening either has to close the other family, and
 * opening one picker has to stop whatever the previous picker had in flight.
 *
 * That last rule is why this is a file rather than a comment. It was stated at
 * the call site and only half implemented: opening a picker cancelled the GIF
 * search and not the translate bar's, so switching from the bar to the emoji
 * or GIF picker left a debounce armed that then fired into a bar the user had
 * already left — sending a translation request, which against a metered source
 * is a request the user pays for and did not ask for.
 */
object PanelRouting {

    /** A surface that sits above the keys and can hold a search query. */
    enum class Picker { NONE, EMOJI, GIF, TRANSLATE }

    /** A surface that replaces the keys. */
    enum class Panel { CLIPBOARD, EDIT, TOOLS }

    /**
     * The pickers whose pending work must be stopped when [opening] appears.
     *
     * Every picker but the one being opened, stated positively so that adding
     * a fourth cannot quietly go uncancelled — which is exactly how the
     * translate bar came to be missed when the GIF picker was written.
     */
    fun toCancel(opening: Picker): Set<Picker> =
        Picker.entries.filter { it != Picker.NONE && it != opening }.toSet()

    /**
     * Whether the GIF picker's field-seed bookkeeping should be discarded.
     *
     * The seed records how many characters of the message were the search
     * query, so picking a GIF can remove them. It belongs to the GIF picker
     * alone and is meaningless once anything else is open.
     */
    fun clearsGifSeed(opening: Picker): Boolean = opening != Picker.GIF

    /**
     * Whether opening [opening] leaves a request that may already be on the
     * wire and therefore needs its generation moved on.
     *
     * Cancelling a debounce stops a request that has not been sent. A request
     * already sent cannot be recalled, and its answer would otherwise be
     * written into a bar the user has left — so the counter it carries has to
     * be invalidated as well.
     */
    fun abandonsTranslate(opening: Picker): Boolean = opening != Picker.TRANSLATE

    /** Panels to hide when any picker or another panel opens. */
    fun panelsToHide(keeping: Panel?): List<Panel> = Panel.entries.filter { it != keeping }
}
