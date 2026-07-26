package com.rimboard.keyboard.net

import android.content.Context
import com.rimboard.keyboard.settings.Prefs

/**
 * Which service 🌍 talks to, and how that is decided.
 *
 * There are three, and they exist for three different reasons rather than as
 * redundancy:
 *
 *  - [Src.LINGVA] — keyless. Nothing to sign up for, nothing to configure, and
 *    it detects the source language. This is the default, and it is why
 *    translation works on a fresh install.
 *  - [Src.LIBRE] — open source and self-hostable. The only option where the
 *    text need never leave a machine the user controls. Its public instance
 *    now requires a key, so this is for people running their own.
 *  - [Src.ANTHROPIC] — best quality and the only one that handles long text,
 *    at the cost of the user's own metered API key.
 *
 * [Src.AUTO] is the shipped default and means "the best one that is actually
 * usable right now" — the key the user has set, or the keyless service if they
 * have set none. Everything else is an explicit override, because someone who
 * has an Anthropic key for proofreading may still not want to spend it on
 * every translation.
 */
object Translate {

    enum class Src(val id: String) {
        AUTO("auto"),
        LINGVA("lingva"),
        LIBRE("libre"),
        ANTHROPIC("anthropic");

        companion object {
            fun of(id: String?): Src = entries.firstOrNull { it.id == id } ?: AUTO
        }
    }

    fun stored(c: Context): Src =
        Src.of(Prefs.get(c).getString(Prefs.KEY_TRANSLATE_SOURCE, Src.AUTO.id))

    fun store(c: Context, s: Src) {
        Prefs.get(c).edit().putString(Prefs.KEY_TRANSLATE_SOURCE, s.id).apply()
    }

    /**
     * The source that will actually be used.
     *
     * A stored choice that cannot work is *not* honoured: Anthropic without a
     * key, or LibreTranslate against the public instance without a key, would
     * both fail on every request, and failing repeatedly is a worse answer than
     * quietly using the one that works. The settings screen says which is in
     * force so this is visible rather than mysterious.
     */
    fun effective(c: Context): Src {
        val anthropic = ApiKeys.unlocked(c) && ApiKeys.anthropic(c) != null
        return when (val s = stored(c)) {
            Src.AUTO -> if (anthropic) Src.ANTHROPIC else Src.LINGVA
            Src.ANTHROPIC -> if (anthropic) Src.ANTHROPIC else Src.LINGVA
            Src.LIBRE -> if (libreUsable(c)) Src.LIBRE else Src.LINGVA
            else -> s
        }
    }

    /**
     * LibreTranslate needs either a key or an instance of the user's own; with
     * neither, every request comes back as a link to the signup portal.
     */
    private fun libreUsable(c: Context): Boolean =
        (ApiKeys.unlocked(c) && ApiKeys.libre(c) != null) || customHost(c) != null

    /** The user's self-hosted instance, or null. Bare hostname, already validated. */
    fun customHost(c: Context): String? =
        Prefs.get(c).getString(Prefs.KEY_TRANSLATE_HOST, null)
            ?.trim()?.lowercase()?.takeIf { isHost(it) }

    fun setCustomHost(c: Context, host: String?) {
        // Typed with a scheme or a trailing slash more often than not; accepting
        // "https://my.box/" and storing "my.box" is less annoying than an error
        // message about a format nobody was told about.
        val cleaned = host.orEmpty().trim().lowercase()
            .removePrefix("https://").removePrefix("http://")
            .substringBefore('/').substringBefore(':')
        Prefs.get(c).edit().apply {
            if (isHost(cleaned)) putString(Prefs.KEY_TRANSLATE_HOST, cleaned)
            else remove(Prefs.KEY_TRANSLATE_HOST)
        }.apply()
    }

    /**
     * A conservative hostname check.
     *
     * This value ends up widening the network allowlist, so it is worth being
     * strict: letters, digits, dots and hyphens, at least one dot, and no
     * leading or trailing dot. That rejects paths, ports, credentials, IPv6
     * literals and anything with a `@` in it — all of which would otherwise be
     * ways to make `hostAllowed` agree to something other than what the user
     * thinks they typed.
     */
    internal fun isHost(s: String): Boolean =
        s.length in 4..253 &&
            '.' in s.trim('.') &&
            !s.startsWith('.') && !s.endsWith('.') &&
            !s.contains("..") &&
            s.all { it.isDigit() || it in 'a'..'z' || it == '.' || it == '-' }

    /** The host [src] should be asked, honouring a self-hosted instance. */
    fun hostFor(c: Context, src: Src): String {
        val custom = customHost(c)
        return when (src) {
            Src.LINGVA -> custom ?: Lingva.DEFAULT_HOST
            Src.LIBRE -> custom ?: LibreTranslate.DEFAULT_HOST
            else -> ""
        }
    }

    /**
     * Runs a translation on whichever keyless-or-self-hosted source is in
     * force. The Anthropic path is not here: it needs the prompt-facing
     * language *name* rather than a code, and lives with the rest of the model
     * work in [AiText].
     */
    fun run(c: Context, src: Src, text: String, target: String): Result<String> = when (src) {
        Src.LIBRE -> LibreTranslate.translate(
            c, text, LibreTranslate.AUTO, target, hostFor(c, Src.LIBRE)
        )
        else -> Lingva.translate(c, text, Lingva.AUTO, target, hostFor(c, Src.LINGVA))
    }

    /** Shared by both keyless clients so a caller can catch one type. */
    sealed class Error(message: String) : java.io.IOException(message) {
        class Api(detail: String) : Error(detail)
        object TooLong : Error("Too long for this translator — an Anthropic key handles long text")
    }
}
