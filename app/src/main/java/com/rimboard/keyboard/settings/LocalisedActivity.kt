package com.rimboard.keyboard.settings

import android.content.Context
import androidx.appcompat.app.AppCompatActivity

/**
 * An activity built for the chosen interface language, and rebuilt when that
 * choice changes underneath it.
 *
 * Every activity already wrapped its base context, so each was correct at the
 * moment it was created — and that is the whole of what was wrong. Changing
 * the language recreated only the screen the picker was on. Every other
 * activity was already sitting in the back stack, built with the old locale,
 * and pressing Back returned to it unchanged: the setup screen stayed in the
 * previous language until the app was killed. Android does not recreate the
 * back stack for a preference it knows nothing about.
 *
 * So the tag each activity was built with is remembered, and checked when it
 * comes back to the front. Doing it here rather than in the picker is what
 * makes it hold for a stack of any depth, for a language changed from
 * anywhere, and for activities not written yet — the picker cannot know who is
 * behind it.
 */
abstract class LocalisedActivity : AppCompatActivity() {

    /** The interface language this instance's resources were built for. */
    private var builtFor: String? = null

    override fun attachBaseContext(newBase: Context) {
        builtFor = Prefs.uiLanguage(newBase)
        super.attachBaseContext(L10n.wrap(newBase))
    }

    override fun onStart() {
        super.onStart()
        // onStart rather than onResume: this runs before the activity is
        // visible, so the rebuild is not seen as a flash of the old language.
        val now = Prefs.uiLanguage(this)
        if (builtFor != null && builtFor != now) {
            builtFor = now
            recreate()
        }
    }

    companion object {
        /**
         * Whether an activity built for [builtFor] is still current.
         *
         * Separated from the lifecycle so the rule can be tested without an
         * Android runtime. The null case is "we never recorded one", which must
         * not trigger a rebuild — an activity that recreates itself on the way
         * up with nothing to compare against would do so forever.
         */
        fun needsRebuild(builtFor: String?, current: String): Boolean =
            builtFor != null && builtFor != current
    }
}
