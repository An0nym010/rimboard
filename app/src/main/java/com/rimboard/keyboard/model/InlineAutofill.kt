package com.rimboard.keyboard.model

/**
 * The parts of inline autofill that can be reasoned about without a device.
 *
 * Inline autofill is how a password manager puts "fill from Bitwarden" into the
 * suggestion strip instead of a dropdown. The system asks the keyboard whether
 * it wants them, hands back a response when the autofill service has something,
 * and the keyboard inflates each one into a view.
 *
 * **The keyboard never sees the credential.** An inflated inline suggestion is
 * a surface owned and drawn by the autofill provider's own process; this app
 * hosts a rectangle and is told nothing about what is inside it, and taps go
 * back to the provider rather than through here. That is worth knowing before
 * worrying about what a privacy-minded keyboard is doing handling passwords:
 * it is handling a picture of one, and cannot read it.
 *
 * What is left that is genuinely this app's problem is the asynchrony, which is
 * where the bugs live. Each suggestion is inflated separately and arrives on
 * its own callback, so views come back **out of order**, some never come back
 * at all, and any of them can arrive after the user has moved to another field
 * — at which point showing them would offer one form's credentials on top of
 * another's. [Batch] is that problem on its own, testable without Android.
 */
object InlineAutofill {

    /**
     * How many chips to ask the system for.
     *
     * Four is what fits a 44dp strip at a readable width before it starts
     * scrolling, and password managers rarely offer more than two or three for
     * one field anyway. Asking for more costs an inflation each.
     */
    const val MAX = 4

    /** Chip height in dp, sitting inside the 44dp strip with room either side. */
    const val CHIP_HEIGHT = 36

    /** The width a chip may occupy: enough for an icon and a name, not a URL. */
    const val CHIP_MIN_WIDTH = 64
    const val CHIP_MAX_WIDTH = 280

    /**
     * One response's worth of inflations in flight.
     *
     * [generation] is what makes a late arrival harmless. The service bumps it
     * whenever the field changes or a newer response lands, and a callback
     * carrying a stale one is dropped rather than displayed — which is the only
     * defence against the case that actually matters here, one account's
     * suggestion appearing over another account's form.
     *
     * Slots are filled by index rather than appended, because the autofill
     * service ordered them and inflation does not preserve that order. A
     * suggestion that fails to inflate leaves its slot empty and is skipped,
     * so one bad chip costs its own place and not the whole row.
     */
    class Batch<V : Any>(val generation: Int, expected: Int) {

        private val slots = arrayOfNulls<Any>(maxOf(0, expected))
        private var answered = 0

        val size: Int get() = slots.size

        /** True once every slot has been answered, successfully or not. */
        val complete: Boolean get() = answered >= slots.size

        /**
         * Records the view for slot [index], or null where inflation failed.
         *
         * Answering the same slot twice does not advance completion: the
         * platform is not obliged to call back exactly once per suggestion, and
         * a double answer that counted twice would declare the batch complete
         * while a real slot was still outstanding, showing a short row.
         */
        fun accept(index: Int, view: V?) {
            if (index !in slots.indices) return
            if (slots[index] != null) return
            slots[index] = view ?: FAILED
            answered++
        }

        /** The inflated views, in the order the autofill service ranked them. */
        @Suppress("UNCHECKED_CAST")
        fun views(): List<V> = slots.filter { it != null && it !== FAILED }.map { it as V }

        private companion object {
            /** Marks an answered-but-failed slot, so it is not answered twice. */
            val FAILED = Any()
        }
    }
}
