package com.rimboard.keyboard.model

/**
 * What is drawn behind the keys, and who therefore has to stay out of the way.
 *
 * `PhotoBackdrop` is the root view: it paints a photo, or a live background, or
 * neither, and the suggestion strip and the keyboard are its children. A
 * `ViewGroup` draws its own `onDraw` *before* its children, so anything the
 * backdrop paints is underneath both of them — and the keyboard covers almost
 * all of it.
 *
 * That makes this a rule three places have to agree about, which is exactly the
 * shape of thing this file exists to stop being three opinions. It was three
 * opinions: the photo case was handled in all of them and the live case in none
 * of them, so **"Night sky" and "Particles" have never drawn anything a user
 * could see** unless a photo happened to be set as well — which is the one case
 * where the sky is deliberately not drawn at all. Reported as "live backgrounds
 * don't work".
 *
 * Extracted for the reason [AutocorrectGate] gives about itself: the rule lived
 * inside an `InputMethodService` and a `View.onDraw`, so the only way to
 * execute it was to open a keyboard and look.
 */
object Backdrop {

    /** The value [liveMode] takes when no live background is wanted. */
    const val NONE = "none"

    /**
     * Whether the sky is actually being drawn.
     *
     * A photo outranks it. Two moving backgrounds behind one set of keys is one
     * too many, and the photo is the more deliberate choice of the two — so a
     * live mode chosen months ago does not start competing with a picture set
     * this morning. `PhotoBackdrop.onDraw` has always taken that view; this
     * states it where the other callers can read it.
     */
    fun liveVisible(hasPhoto: Boolean, liveMode: String): Boolean =
        !hasPhoto && liveMode != NONE

    /**
     * Whether the keyboard and the suggestion strip must leave their own
     * backgrounds unpainted.
     *
     * True whenever the backdrop is drawing anything at all, because an opaque
     * fill in a child blots out everything the parent drew. The keyboard's fill
     * and the strip's `setBackgroundColor` are the two that matter; both cover
     * their whole bounds.
     *
     * The cost, and it is worth stating rather than discovering: the keyboard's
     * fill carries a faint top-to-bottom gradient when key borders are on, and
     * a flat backdrop colour does not reproduce it. That is already what a
     * photo does, so this is not a new behaviour — and the gradient is a shade
     * either side of the background colour, against a setting whose entire
     * point is what is behind the keys.
     */
    fun surfacesTransparent(hasPhoto: Boolean, liveMode: String): Boolean =
        hasPhoto || liveVisible(hasPhoto, liveMode)
}
