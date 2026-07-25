package com.rimboard.keyboard.setup

import android.app.Activity
import android.content.Intent
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.appcompat.app.AlertDialog
import com.rimboard.keyboard.R
import com.rimboard.keyboard.net.Net
import com.rimboard.keyboard.settings.NetworkActivity

/**
 * The one-time network question, shown the first time the app is opened.
 *
 * Both flavors show something, but they are different kinds of message. The
 * online build asks, because there is a real choice to make and the user is
 * the only one who can make it. The offline build tells, because the choice
 * was already made by which APK got installed — and someone who wanted the
 * GIF and translation features would otherwise have to work that out from
 * their absence.
 *
 * Neither version is dismissible by tapping outside it. This is the one
 * decision in the app where a stray tap should not pick an answer.
 */
object NetChoiceDialog {

    fun showIfNeeded(activity: Activity) {
        if (Net.chosen(activity)) return
        if (Net.capable) showChoice(activity) else showOfflineNotice(activity)
    }

    /** Reachable from Settings → Network as well, to re-read the trade-off. */
    fun showChoice(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.net_choice_title)
            .setMessage(choiceBody(activity))
            .setCancelable(false)
            // "Stay offline" is the positive button: it is the safe answer, it
            // is what this keyboard has always done, and it is the one that
            // needs no further setup. Nothing here should make going online
            // the path of least resistance.
            .setPositiveButton(R.string.net_choice_pick_offline) { _, _ ->
                Net.setMode(activity, Net.MODE_OFFLINE)
            }
            .setNegativeButton(R.string.net_choice_pick_online) { _, _ ->
                Net.setMode(activity, Net.MODE_ONLINE)
            }
            .show()
    }

    private fun showOfflineNotice(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.net_offline_build_title)
            .setMessage(R.string.net_offline_build_body)
            .setCancelable(false)
            .setPositiveButton(R.string.net_dismiss) { _, _ ->
                Net.setMode(activity, Net.MODE_OFFLINE)
            }
            .setNegativeButton(R.string.net_show_proof) { _, _ ->
                Net.setMode(activity, Net.MODE_OFFLINE)
                activity.startActivity(Intent(activity, NetworkActivity::class.java))
            }
            .show()
    }

    /**
     * Built in code rather than as one long string so each option's heading can
     * be bold. A wall of bullet points with no visual break between the two
     * options is not something anyone reads before tapping, and this is the
     * dialog where reading it is the entire point.
     */
    private fun choiceBody(activity: Activity): CharSequence {
        val sb = SpannableStringBuilder()
        fun head(res: Int) {
            val start = sb.length
            sb.append(activity.getString(res))
            sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append("\n")
        }
        head(R.string.net_choice_offline_head)
        sb.append(activity.getString(R.string.net_choice_offline_body)).append("\n\n")
        head(R.string.net_choice_online_head)
        sb.append(activity.getString(R.string.net_choice_online_body)).append("\n\n")
        sb.append(activity.getString(R.string.net_choice_footer))
        return sb
    }
}
