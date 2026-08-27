package com.rimboard.keyboard.model

/**
 * Whether the bubble above a pressed key may show the character.
 *
 * "Popup on keypress" is the user's setting for it and governs everywhere. In
 * a password field there is a second question, and Android already has an
 * answer to it: **Show passwords**, in the system settings, is the platform's
 * user-level statement about whether characters being typed into a masked
 * field may be revealed as they are typed. The field itself honours it -- that
 * is the setting that decides whether an `EditText` flashes the last character
 * before turning it into a dot.
 *
 * The keyboard was not honouring it. The bubble renders the character
 * magnified, above the key, at the moment it is pressed -- which is the same
 * disclosure the field is making, larger and for the same instant, in the one
 * kind of field whose whole point is that what you type is not on screen.
 *
 * Measured on a phone, reading the value this computes at each focus change,
 * against a real password field (the online build's API key row, which the
 * strip marks "Incognito - not learning") and an ordinary one:
 *
 *     password field, Show passwords at its default   ->  true
 *     password field, Show passwords off              ->  false
 *     ordinary field, Show passwords off              ->  true
 *
 * The first row was also confirmed in pixels: the bubble is there, rendering
 * the character above the key. The third is the one worth having a row for --
 * turning the platform setting off must not take the bubble away from ordinary
 * typing, and it does not.
 *
 * Deferring to the platform rather than inventing a rule matters here. Somebody
 * who turns *Show passwords* off has said what they want in the place Android
 * provides for saying it, and a keyboard that ignored that would be as wrong
 * as one that ignored the preference above. Somebody who leaves it on -- which
 * is the platform default -- sees no change at all.
 */
object KeyPreview {

    /**
     * @param enabled            the "Popup on keypress" preference.
     * @param isPassword         a field whose contents are masked on screen.
     * @param systemShowsPasswords `Settings.System.TEXT_SHOW_PASSWORD`, which
     *        defaults to on; see the note in the service about reading it.
     */
    fun mayShow(
        enabled: Boolean,
        isPassword: Boolean,
        systemShowsPasswords: Boolean
    ): Boolean = enabled && (!isPassword || systemShowsPasswords)
}
