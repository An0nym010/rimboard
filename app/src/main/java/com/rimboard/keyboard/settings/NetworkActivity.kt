package com.rimboard.keyboard.settings

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.rimboard.keyboard.R
import com.rimboard.keyboard.net.ApiKeys
import com.rimboard.keyboard.net.Net
import com.rimboard.keyboard.net.NetLog
import com.rimboard.keyboard.model.TranslateTargets
import com.rimboard.keyboard.net.NetProbe
import com.rimboard.keyboard.net.Translate
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * What this build can reach, and the evidence for it.
 *
 * The screen is built around one idea: never assert anything about network
 * access that the user cannot check somewhere other than here. So the
 * permission list is read back from the system rather than printed from a
 * constant, and the verdict at the top is derived from a connection RimBoard
 * actually attempts while the user watches — see [NetProbe].
 */
class NetworkActivity : LocalisedActivity() {

    private lateinit var container: LinearLayout
    private var report: NetProbe.Report? = null
    private var probing = false

    private val uiLocale: Locale
        get() = resources.configuration.locales.let {
            if (it.isEmpty) Locale.getDefault() else it[0]
        }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setTitle(R.string.pref_network_title)
        val d = resources.displayMetrics.density
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (16 * d).toInt(), (20 * d).toInt(), (24 * d).toInt())
        }
        setContentView(ScrollView(this).apply { addView(container) })
        render()
        probe()
    }

    /**
     * Off the main thread, without exception: Android raises
     * `NetworkOnMainThreadException` for socket work there, and that would
     * pre-empt the permission refusal this screen exists to show — the probe
     * would "fail" for a reason that has nothing to do with what is being
     * demonstrated.
     */
    private fun probe() {
        probing = true
        render()
        Thread {
            val r = NetProbe.run(this)
            runOnUiThread {
                report = r
                probing = false
                render()
            }
        }.start()
    }

    private fun render() {
        container.removeAllViews()
        val d = resources.displayMetrics.density
        val r = report

        // Verdict card.
        val verdict = r?.let { NetProbe.verdict(it) }
        val (headline, detail, accent) = when {
            probing || r == null -> Triple(getString(R.string.net_probe_running), "", ACCENT_NEUTRAL)
            verdict == NetProbe.Verdict.PROVEN_OFFLINE -> Triple(
                getString(R.string.net_verdict_proven),
                getString(R.string.net_verdict_proven_detail), ACCENT_GOOD)
            verdict == NetProbe.Verdict.OFFLINE_BY_PERMISSION -> Triple(
                getString(R.string.net_verdict_permission),
                getString(R.string.net_verdict_permission_detail), ACCENT_GOOD)
            verdict == NetProbe.Verdict.ONLINE -> Triple(
                getString(R.string.net_verdict_online),
                getString(R.string.net_verdict_online_detail), ACCENT_WARN)
            else -> Triple(
                getString(R.string.net_verdict_online_idle),
                getString(R.string.net_verdict_online_idle_detail), ACCENT_WARN)
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 16 * d
                setColor(accent and 0x00FFFFFF or 0x1F000000)
            }
        }
        card.addView(TextView(this).apply {
            text = headline
            textSize = 20f
            setTextColor(accent)
            setTypeface(typeface, Typeface.BOLD)
        })
        if (detail.isNotEmpty()) {
            card.addView(TextView(this).apply {
                text = detail
                textSize = 14f
                setPadding(0, (8 * d).toInt(), 0, 0)
            })
        }
        container.addView(card, marginLp(d, bottom = 18))

        // The mode switch only means something on a build that could go
        // online. On the offline build there is nothing to switch, and
        // offering a disabled toggle would imply the permission is present.
        if (Net.capable) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (4 * d).toInt(), 0, (12 * d).toInt())
            }
            val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(TextView(this).apply {
                text = getString(R.string.net_mode_title)
                textSize = 16f
            })
            val sub = TextView(this).apply {
                textSize = 13f
                text = getString(
                    if (Net.mode(this@NetworkActivity) == Net.MODE_ONLINE)
                        R.string.net_mode_on else R.string.net_mode_off
                )
            }
            labels.addView(sub)
            row.addView(labels,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Switch(this).apply {
                isChecked = Net.mode(this@NetworkActivity) == Net.MODE_ONLINE
                setOnCheckedChangeListener { _, on ->
                    Net.setMode(this@NetworkActivity,
                        if (on) Net.MODE_ONLINE else Net.MODE_OFFLINE)
                    render()
                }
            })
            container.addView(row)
        }

        // Only on a build that could use a key. Offering a key field on the
        // offline build would imply the features are one setting away.
        if (Net.capable) {
            section(d, getString(R.string.pref_keys_header))
            keyRow(d, R.string.pref_key_anthropic, R.string.pref_key_anthropic_none, "sk-ant-…",
                ApiKeys.anthropic(this)) { ApiKeys.setAnthropic(this, it) }
            keyRow(d, R.string.pref_key_klipy, R.string.pref_key_klipy_none, "abc123…",
                ApiKeys.klipy(this)) { ApiKeys.setKlipy(this, it) }
            keyRow(d, R.string.pref_key_libre, R.string.pref_key_libre_none, "abc123…",
                ApiKeys.libre(this)) { ApiKeys.setLibre(this, it) }
            container.addView(TextView(this).apply {
                text = getString(R.string.pref_key_note)
                textSize = 12f
                setPadding(0, (8 * d).toInt(), 0, 0)
            })

            section(d, getString(R.string.tr_src_title))
            container.addView(TextView(this).apply {
                text = getString(R.string.tr_src_current, srcLabel(Translate.effective(this@NetworkActivity)))
                textSize = 16f
                setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
                isClickable = true
                isFocusable = true
                setOnClickListener { showSourcePicker() }
            })
            container.addView(TextView(this).apply {
                text = getString(R.string.tr_src_note)
                textSize = 12f
            })

            // A self-hosted instance is the only address the app cannot know in
            // advance, so it is entered here and the network gate reads it back.
            hostRow(d)

            // Lives beside the Anthropic key because that is what makes the
            // feature work at all; it is the first thing anyone will look for
            // after setting one.
            section(d, getString(R.string.tr_target_title))
            val targetRow = TextView(this).apply {
                text = TranslateTargets.currentLabel(this@NetworkActivity, uiLocale)
                textSize = 16f
                setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
                isClickable = true
                isFocusable = true
                setOnClickListener { showTargetPicker() }
            }
            container.addView(targetRow)
            container.addView(TextView(this).apply {
                text = getString(R.string.tr_target_note)
                textSize = 12f
            })
        }

        section(d, getString(R.string.net_perm_header))
        val perms = r?.declaredPermissions.orEmpty()
        container.addView(mono(d, if (perms.isEmpty()) "…" else perms.joinToString("\n")))
        container.addView(TextView(this).apply {
            text = getString(R.string.net_perm_note)
            textSize = 12f
            setPadding(0, (6 * d).toInt(), 0, 0)
        })

        section(d, getString(R.string.net_probe_header))
        container.addView(mono(d, r?.detail ?: getString(R.string.net_probe_running)))
        container.addView(Button(this).apply {
            text = getString(R.string.net_probe_again)
            isEnabled = !probing
            setOnClickListener { probe() }
        }, marginLp(d, top = 8, bottom = 8))

        section(d, getString(R.string.net_log_header))
        val log = NetLog.recent()
        val sent = NetLog.sentCount(this)
        if (sent == 0 && log.isEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.net_log_none)
                textSize = 14f
            })
        } else {
            container.addView(TextView(this).apply {
                text = getString(R.string.net_log_count, sent)
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
            })
            val fmt = DateFormat.getTimeInstance(DateFormat.MEDIUM, uiLocale)
            container.addView(mono(d, log.joinToString("\n") { e ->
                "${fmt.format(Date(e.at))}  ${e.outcome}  " +
                    getString(R.string.net_log_entry, e.reason, e.host) +
                    (e.detail?.let { "  ($it)" } ?: "")
            }))
        }
    }

    /**
     * One key: what is stored (masked), a field to replace it, and a clear.
     *
     * The stored value is never put back into the input — only the mask is
     * shown. A settings screen is exactly the kind of thing that gets
     * screenshotted, mirrored to a projector, or screen-shared while someone
     * asks for help.
     */
    private fun keyRow(
        d: Float,
        labelRes: Int,
        emptyRes: Int,
        hintText: String,
        current: String?,
        save: (String?) -> Unit
    ) {
        container.addView(TextView(this).apply {
            text = getString(labelRes)
            textSize = 15f
            setPadding(0, (10 * d).toInt(), 0, 0)
        })
        container.addView(TextView(this).apply {
            text = ApiKeys.masked(current) ?: getString(emptyRes)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setPadding(0, (2 * d).toInt(), 0, (6 * d).toInt())
        })
        val field = EditText(this).apply {
            hint = hintText
            // Password variation plus no-suggestions: the keyboard being
            // configured here is the one that would otherwise learn this.
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
        }
        container.addView(field)
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (6 * d).toInt(), 0, 0)
        }
        buttons.addView(Button(this).apply {
            text = getString(R.string.pref_key_save)
            setOnClickListener {
                save(field.text.toString())
                field.setText("")
                toast(R.string.pref_key_saved)
                offerToEnableOnline()
                render()
            }
        })
        buttons.addView(Button(this).apply {
            text = getString(R.string.pref_key_clear)
            isEnabled = current != null
            setOnClickListener {
                save(null)
                toast(R.string.pref_key_cleared)
                render()
            }
        })
        container.addView(buttons)
    }

    private fun srcLabel(s: Translate.Src): String = getString(
        when (s) {
            Translate.Src.ANTHROPIC -> R.string.tr_src_anthropic
            Translate.Src.LIBRE -> R.string.tr_src_libre
            Translate.Src.LINGVA -> R.string.tr_src_lingva
            Translate.Src.AUTO -> R.string.tr_src_auto
        }
    )

    /**
     * Which service does the translating.
     *
     * The list shows what each one costs you — a key, a server, or nothing —
     * because that is the only axis anyone is actually choosing on. "Automatic"
     * is first and is what a fresh install uses.
     */
    private fun showSourcePicker() {
        val order = listOf(
            Translate.Src.AUTO, Translate.Src.LINGVA,
            Translate.Src.LIBRE, Translate.Src.ANTHROPIC
        )
        val labels = order.map { srcLabel(it) }.toTypedArray()
        val current = order.indexOf(Translate.stored(this))
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.tr_src_title)
            .setSingleChoiceItems(labels, current) { dlg, which ->
                Translate.store(this, order[which])
                dlg.dismiss()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * The self-hosted instance field.
     *
     * Not a [keyRow]: this is not a secret, so it is shown in full rather than
     * masked — being able to read back the address you typed is the whole point
     * when a translation is failing and you are checking for a typo.
     */
    private fun hostRow(d: Float) {
        val current = Translate.customHost(this)
        container.addView(TextView(this).apply {
            text = getString(R.string.tr_host_title)
            textSize = 15f
            setPadding(0, (14 * d).toInt(), 0, 0)
        })
        container.addView(TextView(this).apply {
            text = current ?: getString(R.string.tr_host_none)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setPadding(0, (2 * d).toInt(), 0, (6 * d).toInt())
        })
        // An address is only used for a service the user picked, because one
        // field cannot say which software answers at it and the two speak
        // incompatible protocols. Saying so beats letting the row imply the
        // instance is live when nothing is being sent to it.
        if (current != null && Translate.stored(this) == Translate.Src.AUTO) {
            container.addView(TextView(this).apply {
                text = getString(R.string.tr_host_unused)
                textSize = 12f
                setPadding(0, 0, 0, (6 * d).toInt())
            })
        }
        val field = EditText(this).apply {
            hint = getString(R.string.tr_host_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
        }
        container.addView(field)
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (6 * d).toInt(), 0, 0)
        }
        buttons.addView(Button(this).apply {
            text = getString(R.string.pref_key_save)
            setOnClickListener {
                Translate.setCustomHost(this@NetworkActivity, field.text.toString())
                // Rejected silently would look like it saved, and the user
                // would go hunting for the failure in the wrong place.
                if (Translate.customHost(this@NetworkActivity) == null &&
                    field.text.isNotBlank()
                ) {
                    toast(R.string.tr_host_bad)
                } else {
                    field.setText("")
                    toast(R.string.pref_key_saved)
                }
                render()
            }
        })
        buttons.addView(Button(this).apply {
            text = getString(R.string.pref_key_clear)
            isEnabled = current != null
            setOnClickListener {
                Translate.setCustomHost(this@NetworkActivity, null)
                toast(R.string.pref_key_cleared)
                render()
            }
        })
        container.addView(buttons)
        container.addView(TextView(this).apply {
            text = getString(R.string.tr_host_note)
            textSize = 12f
            setPadding(0, (6 * d).toInt(), 0, 0)
        })
    }

    /**
     * The full platform language list, with the user's keyboard languages on
     * top.
     *
     * Deliberately not limited to the 22 bundled layouts: those exist so you
     * can *type* a language, and translating into one has nothing to do with
     * having its keyboard. The names come from the platform, so they arrive
     * already translated into whatever the UI is set to.
     */
    private fun showTargetPicker() {
        val targets = TranslateTargets.list(this, uiLocale)
        val labels = targets.map { it.label }.toTypedArray()
        val current = targets.indexOfFirst { it.code == TranslateTargets.stored(this) }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.tr_target_title)
            .setSingleChoiceItems(labels, current) { dlg, which ->
                TranslateTargets.store(this, targets[which].code)
                dlg.dismiss()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Saving a key while the network switch is off leaves the features still
     * refusing, for a reason on a different part of the same screen.
     *
     * Someone who has just pasted an API key has plainly decided they want the
     * feature; the switch is the consent step and is not skipped, but it is
     * asked about here rather than left to be discovered. Before this, the
     * common outcome was a key set, the switch off, and the tool silently
     * doing something else entirely.
     */
    private fun offerToEnableOnline() {
        if (Net.mode(this) == Net.MODE_ONLINE) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.net_enable_title)
            .setMessage(R.string.net_enable_msg)
            .setPositiveButton(R.string.net_enable_yes) { _, _ ->
                Net.setMode(this, Net.MODE_ONLINE)
                render()
            }
            .setNegativeButton(R.string.net_enable_no, null)
            .show()
    }

    private fun toast(res: Int) {
        android.widget.Toast.makeText(this, res, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun section(d: Float, title: String) {
        container.addView(TextView(this).apply {
            text = title
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, (20 * d).toInt(), 0, (6 * d).toInt())
        })
    }

    /**
     * Monospace, and selectable so the user can copy the exact text out and
     * compare it against what `adb` says rather than reading it off a screen.
     */
    private fun mono(d: Float, text: String): View = TextView(this).apply {
        this.text = text
        textSize = 12f
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
        setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
        background = GradientDrawable().apply {
            cornerRadius = 10 * d
            setColor(0x14808080)
        }
    }

    private fun marginLp(d: Float, top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, (top * d).toInt(), 0, (bottom * d).toInt()) }

    private companion object {
        const val ACCENT_GOOD = 0xFF1E8E3E.toInt()
        const val ACCENT_WARN = 0xFFE37400.toInt()
        const val ACCENT_NEUTRAL = 0xFF5F6368.toInt()
    }
}
