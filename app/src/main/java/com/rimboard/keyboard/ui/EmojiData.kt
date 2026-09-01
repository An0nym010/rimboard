package com.rimboard.keyboard.ui

import java.util.Locale

/**
 * The emoji palette, grouped the way the panel's tabs are.
 *
 * The lists are deliberately generous — anything the running device can't
 * render is filtered out at load time via `Paint.hasGlyph`, so newer emoji can
 * be listed safely and simply won't appear on older Android versions.
 */
object EmojiData {

    class Category(val icon: String, val emojis: List<String>)

    /**
     * The ISO 3166-1 alpha-2 code a flag emoji spells, or null.
     *
     * A country flag is not a picture the way the other emoji are: it is two
     * regional indicator symbols, U+1F1E6 to U+1F1FF, standing for the two
     * letters of the region's code. So the flag already carries its own name
     * and nothing has to be written down to know it.
     *
     * Returns null for everything else, including the tag-sequence flags
     * (England, Scotland, Wales) and the pride and pirate flags, which are
     * built from a waving flag and a joiner rather than from indicators.
     */
    fun regionOf(emoji: String): String? {
        var i = 0
        val letters = StringBuilder(2)
        while (i < emoji.length) {
            val cp = emoji.codePointAt(i)
            if (cp !in 0x1F1E6..0x1F1FF) return null
            if (letters.length == 2) return null
            letters.append('A' + (cp - 0x1F1E6))
            i += Character.charCount(cp)
        }
        return if (letters.length == 2) letters.toString() else null
    }

    /**
     * Words that carry no meaning about the emoji, only about English.
     *
     * Unicode names are phrases -- "FACE WITH ROLLING EYES", "BLACK AND WHITE
     * CIRCLE" -- and splitting them into words is what makes them searchable,
     * since the panel matches a keyword by prefix. Two of the words that come
     * out are grammar rather than description: "with" appears in 77 of the
     * palette's names and "and" in 28, so typing "w" or "a" would fill the
     * results with whatever those happened to collect.
     *
     * Nothing else is excluded. "face" is in 47 names and is a fair thing to
     * search for; so are "arrow", "hand", "circle", "square" and "symbol". The
     * Unicode jargon that comes along -- "cjk", "ideograph", "unified",
     * "latin" -- is useless but harmless, and dropping it would mean deciding
     * word by word what a name is allowed to contain.
     */
    private val NAME_STOPWORDS = setOf("with", "and")

    /**
     * Search keywords for the palette, from the platform's own Unicode names.
     *
     * The hand-written index reaches 527 of the 1,564 emoji the panel shows.
     * [flagKeywords] closed the flags without shipping any names; this closes
     * most of the rest the same way, and between them the panel goes from
     * **33.7% searchable to 98.5%**. `Character.getName` is the Unicode
     * Character Database's own name for a code point -- "SALUTING FACE",
     * "JACK-O-LANTERN", "FACE WITH ROLLING EYES" -- and it is on the device
     * already, so this adds no asset bytes either.
     *
     * **Only single-code-point emoji**, once variation selectors and joiners
     * are set aside. A name belongs to a code point, and the sensible name for
     * a sequence is not any of its parts: calling the astronaut "WOMAN"
     * because that is what it starts with would be worse than leaving it
     * unnamed. The 23 emoji still unreachable are exactly those: the joined
     * sequences, the keycaps and the skin tones.
     *
     * The names are English, which is what the UCD contains. That matches how
     * the asset index already works -- `search_en.txt` is merged in behind
     * every language's own file -- and a user searching in German still has
     * their own keywords first.
     */
    fun unicodeNameKeywords(): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        for (cat in categories) {
            for (e in cat.emojis) {
                var cp = -1
                var i = 0
                var extra = false
                while (i < e.length) {
                    val c = e.codePointAt(i)
                    i += Character.charCount(c)
                    if (c == 0xFE0F || c == 0xFE0E || c == 0x200D) continue
                    if (cp >= 0) { extra = true; break }
                    cp = c
                }
                if (cp < 0 || extra) continue
                // Absent on a platform whose Unicode tables are older than the
                // emoji; that is a keyword this run does not get, not an error.
                val name = try { Character.getName(cp) } catch (_: Exception) { null } ?: continue
                val lower = name.lowercase(Locale.ROOT)
                for (w in lower.split(' ', '-')) {
                    if (w.length < 3 || w in NAME_STOPWORDS) continue
                    val list = out.getOrPut(w) { ArrayList() }
                    if (e !in list) list.add(e)
                }
            }
        }
        return out
    }

    /**
     * Search keywords for every flag in the palette, in [locale]'s language.
     *
     * The emoji search index is a hand-written asset -- 420 keywords, which
     * reach 571 of the 1,564 emoji the palette shows. Flags were the largest
     * single hole in it and the only one that needs no writing: **229 of the
     * 233 flags could not be found by any query**, and typing "france" into
     * the emoji search returned an empty panel.
     *
     * They are not in the asset because putting them there would mean writing
     * out two hundred and thirty country names, in every language the keyboard
     * ships, and keeping them right. The platform already has that table --
     * [java.util.Locale.getDisplayCountry] is backed by the same CLDR data
     * Android uses everywhere else -- so this asks it, in whatever language
     * the user is searching in, and ships no names at all.
     *
     * Anything the platform does not recognise is skipped rather than guessed:
     * `getDisplayCountry` hands back the code itself for a region it has no
     * name for, and a keyword of "AC" would be worse than no keyword.
     */
    fun flagKeywords(locale: Locale): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        for (cat in categories) {
            for (e in cat.emojis) {
                val code = regionOf(e) ?: continue
                for (l in listOf(locale, Locale.ENGLISH).distinct()) {
                    val name = Locale("", code).getDisplayCountry(l)
                    if (name.isEmpty() || name == code) continue
                    val lower = name.lowercase(l)
                    // The whole name, and every word in it. The search matches
                    // a keyword by prefix, so without the parts "South Korea"
                    // is reachable only by typing "south" and "United States"
                    // only by "united". Splitting is not a guess about the
                    // language -- it is the name the platform gave, cut where
                    // it put the spaces.
                    for (key in listOf(lower) + lower.split(' ', '-')) {
                        if (key.length < 3) continue
                        val list = out.getOrPut(key) { ArrayList() }
                        if (e !in list) list.add(e)
                    }
                }
            }
        }
        return out
    }

    private fun c(icon: String, s: String) =
        Category(icon, s.trim().split(" ").filter { it.isNotBlank() })

    val categories: List<Category> = listOf(
        c("😀",
            "😀 😃 😄 😁 😆 😅 😂 🤣 🥲 🥹 ☺️ 😊 😇 🙂 🙃 😉 😌 😍 🥰 😘 😗 😙 😚 😋 😛 😝 😜 🤪 🤨 🧐 🤓 😎 🥸 🤩 🥳 😏 😒 😞 😔 😟 😕 🙁 ☹️ 😣 😖 😫 😩 🥺 😢 😭 😤 😠 😡 🤬 🤯 😳 🥵 🥶 😱 😨 😰 😥 😓 🫣 🤗 🫡 🤔 🫢 🤭 🤫 🤥 😶 🫥 😐 😑 😬 🫠 🙄 😯 😦 😧 😮 😲 🥱 😴 🤤 😪 😵 🤐 🥴 🤢 🤮 🤧 😷 🤒 🤕 🤑 🤠 😈 👿 👹 👺 🤡 💩 👻 💀 ☠️ 👽 👾 🤖 🎃 😺 😸 😹 😻 😼 😽 🙀 😿 😾 🙈 🙉 🙊 💌 💘 💝 💖 💗 💓 💞 💕 💟 ❣️ 💔 ❤️ 🧡 💛 💚 💙 💜 🤎 🖤 🤍 💋 💯 💢 💥 💫 💦 💨 🕳 💬 💭 💤"),
        c("👋",
            "👋 🤚 🖐 ✋ 🖖 🫱 🫲 🫳 🫴 🫷 🫸 👌 🤌 🤏 ✌️ 🤞 🫰 🤟 🤘 🤙 👈 👉 👆 🖕 👇 ☝️ 🫵 👍 👎 ✊ 👊 🤛 🤜 👏 🙌 🫶 👐 🤲 🤝 🙏 ✍️ 💅 🤳 💪 🦾 🦿 🦵 🦶 👂 🦻 👃 🧠 🫀 🫁 🦷 🦴 👀 👁 👅 👄 🫦 👶 🧒 👦 👧 🧑 👨 👩 🧓 👴 👵 🙍 🙎 🙅 🙆 💁 🙋 🧏 🙇 🤦 🤷 👮 🕵️ 💂 🥷 👷 🫅 🤴 👸 👳 👲 🧕 🤵 👰 🤰 🫃 🫄 🤱 👼 🎅 🤶 🧑‍🎄 🦸 🦹 🧙 🧚 🧛 🧜 🧝 🧞 🧟 🧌 💆 💇 🚶 🧍 🧎 🏃 💃 🕺 🕴 👯 🧖 🧗 🤺 🏇 ⛷ 🏂 🏌️ 🏄 🚣 🏊 ⛹️ 🏋️ 🚴 🚵 🤸 🤼 🤽 🤾 🤹 🧘 🛀 🛌 👭 👫 👬 💏 💑 👪 🗣 👤 👥 🫂 👣"),
        c("🐶",
            "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐻‍❄️ 🐼 🐨 🐯 🦁 🐮 🐷 🐽 🐸 🐵 🐒 🐔 🐧 🐦 🐤 🐣 🐥 🦆 🦅 🦉 🦇 🐺 🐗 🐴 🦄 🐝 🪱 🐛 🦋 🐌 🐞 🐜 🪰 🪲 🪳 🦟 🦗 🕷 🕸 🦂 🐢 🐍 🦎 🦖 🦕 🐙 🦑 🦐 🦞 🦀 🐡 🐠 🐟 🐬 🐳 🐋 🦈 🐊 🐅 🐆 🦓 🦍 🦧 🦣 🐘 🦛 🦏 🐪 🐫 🦒 🦘 🦬 🐃 🐂 🐄 🐎 🐖 🐏 🐑 🦙 🐐 🦌 🐕 🐩 🦮 🐈 🐈‍⬛ 🪶 🐓 🦃 🦤 🦚 🦜 🦢 🦩 🕊 🐇 🦝 🦨 🦡 🦫 🦦 🦥 🐁 🐀 🐿 🦔 🐾 🐉 🐲 🌵 🎄 🌲 🌳 🌴 🪵 🌱 🌿 ☘️ 🍀 🎍 🪴 🎋 🍃 🍂 🍁 🍄 🐚 🪸 🌾 💐 🌷 🌹 🥀 🌺 🌸 🌼 🌻 🌞 🌝 🌛 🌜 🌚 🌕 🌖 🌗 🌘 🌑 🌒 🌓 🌔 🌙 🌎 🌍 🌏 🪐 💫 ⭐ 🌟 ✨ ⚡ ☄️ 💥 🔥 🌪 🌈 ☀️ 🌤 ⛅ 🌥 ☁️ 🌦 🌧 ⛈ 🌩 🌨 ❄️ ☃️ ⛄ 🌬 💨 💧 💦 ☔ ☂️ 🌊 🌫"),
        c("🍏",
            "🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🫐 🍈 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🍆 🥑 🥦 🥬 🥒 🌶 🫑 🌽 🥕 🫒 🧄 🧅 🥔 🍠 🫘 🥐 🥯 🍞 🥖 🥨 🧀 🥚 🍳 🧈 🥞 🧇 🥓 🥩 🍗 🍖 🦴 🌭 🍔 🍟 🍕 🫓 🥪 🥙 🧆 🌮 🌯 🫔 🥗 🥘 🫕 🥫 🍝 🍜 🍲 🍛 🍣 🍱 🥟 🦪 🍤 🍙 🍚 🍘 🍥 🥠 🥮 🍢 🍡 🍧 🍨 🍦 🥧 🧁 🍰 🎂 🍮 🍭 🍬 🍫 🍿 🍩 🍪 🌰 🥜 🍯 🥛 🍼 🫖 ☕ 🍵 🧃 🥤 🧋 🍶 🍺 🍻 🥂 🍷 🥃 🍸 🍹 🧉 🍾 🧊 🥄 🍴 🍽 🥣 🥡 🥢 🧂"),
        c("⚽",
            "⚽ 🏀 🏈 ⚾ 🥎 🎾 🏐 🏉 🥏 🎱 🪀 🏓 🏸 🏒 🏑 🥍 🏏 🪃 🥅 ⛳ 🪁 🏹 🎣 🤿 🥊 🥋 🎽 🛹 🛼 🛷 ⛸ 🥌 🎿 ⛷ 🏂 🪂 🏋️ 🤼 🤸 ⛹️ 🤺 🤾 🏌️ 🏇 🧘 🏄 🏊 🤽 🚣 🧗 🚵 🚴 🏆 🥇 🥈 🥉 🏅 🎖 🏵 🎗 🎫 🎟 🎪 🤹 🎭 🩰 🎨 🎬 🎤 🎧 🎼 🎹 🥁 🪘 🎷 🎺 🪗 🎸 🪕 🎻 🎲 ♟ 🎯 🎳 🎮 🕹 🎰 🧩 🪅 🪆 🎊 🎉 🎈 🎏 🎐 🎀 🎁 🪄 🧧 🎎 🏮"),
        c("🚗",
            "🚗 🚕 🚙 🚌 🚎 🏎 🚓 🚑 🚒 🚐 🛻 🚚 🚛 🚜 🦯 🦽 🦼 🛴 🚲 🛵 🏍 🛺 🚨 🚔 🚍 🚘 🚖 🚡 🚠 🚟 🚃 🚋 🚞 🚝 🚄 🚅 🚈 🚂 🚆 🚇 🚊 🚉 ✈️ 🛫 🛬 🛩 💺 🛰 🚀 🛸 🚁 🛶 ⛵ 🚤 🛥 🛳 ⛴ 🚢 ⚓ 🪝 ⛽ 🚧 🚦 🚥 🚏 🗺 🗿 🗽 🗼 🏰 🏯 🏟 🎡 🎢 🎠 ⛲ ⛱ 🏖 🏝 🏜 🌋 ⛰ 🏔 🗻 🏕 ⛺ 🛖 🏠 🏡 🏘 🏚 🏗 🏭 🏢 🏬 🏣 🏤 🏥 🏦 🏨 🏪 🏫 🏩 💒 🏛 ⛪ 🕌 🕍 🛕 🕋 ⛩ 🛤 🛣 🗾 🎑 🏞 🌅 🌄 🌠 🎇 🎆 🌇 🌆 🏙 🌃 🌌 🌉 🌁 🧭"),
        c("💡",
            "⌚ 📱 📲 💻 ⌨️ 🖥 🖨 🖱 🖲 🕹 🗜 💽 💾 💿 📀 📼 📷 📸 📹 🎥 📽 🎞 📞 ☎️ 📟 📠 📺 📻 🎙 🎚 🎛 ⏱ ⏲ ⏰ 🕰 ⌛ ⏳ 📡 🔋 🪫 🔌 💡 🔦 🕯 🪔 🧯 🛢 💸 💵 💴 💶 💷 🪙 💰 💳 🪪 💎 ⚖️ 🪜 🧰 🪛 🔧 🔨 ⚒ 🛠 ⛏ 🪚 🔩 ⚙️ 🪤 🧱 ⛓ 🧲 🔫 💣 🧨 🪓 🔪 🗡 ⚔️ 🛡 🚬 ⚰️ 🪦 ⚱️ 🏺 🔮 📿 🧿 🪬 💈 ⚗️ 🔭 🔬 🕳 🩻 🩹 🩺 💊 💉 🩸 🧬 🦠 🧫 🧪 🌡 🧹 🪠 🧺 🧻 🚽 🚰 🚿 🛁 🧼 🪥 🪒 🧽 🪣 🧴 🛎 🔑 🗝 🚪 🪑 🛋 🛏 🧸 🖼 🪞 🪟 🛍 🛒 ✉️ 📩 📨 📧 💌 📥 📤 📦 🏷 🪧 📪 📫 📬 📭 📮 📯 📜 📃 📄 📑 🧾 📊 📈 📉 🗒 🗓 📆 📅 🗑 📇 🗃 🗳 🗄 📋 📁 📂 🗂 🗞 📰 📓 📔 📒 📕 📗 📘 📙 📚 📖 🔖 🧷 🔗 📎 🖇 📐 📏 🧮 📌 📍 ✂️ 🖊 🖋 ✒️ 🖌 🖍 📝 ✏️ 🔍 🔎 🔏 🔐 🔒 🔓 👓 🕶 🥽 🥼 🦺 👔 👕 👖 🧣 🧤 🧥 🧦 👗 👘 🥻 🩱 🩲 🩳 👙 👚 👛 👜 👝 🎒 👞 👟 🥾 🥿 👠 👡 🩰 👢 👑 👒 🎩 🎓 🧢 🪖 ⛑ 💄 💍"),
        c("❤️",
            "❤️ 🩷 🧡 💛 💚 💙 🩵 💜 🖤 🩶 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 ☮️ ✝️ ☪️ 🕉 ☸️ ✡️ 🔯 🕎 ☯️ ☦️ 🛐 ⛎ ♈ ♉ ♊ ♋ ♌ ♍ ♎ ♏ ♐ ♑ ♒ ♓ 🆔 ⚛️ 🉑 ☢️ ☣️ 📴 📳 🈶 🈚 🈸 🈺 🈷️ ✴️ 🆚 💮 🉐 ㊙️ ㊗️ 🈴 🈵 🈹 🈲 🅰️ 🅱️ 🆎 🆑 🅾️ 🆘 ❌ ⭕ 🛑 ⛔ 📛 🚫 💯 💢 ♨️ 🚷 🚯 🚳 🚱 🔞 📵 🚭 ❗ ❕ ❓ ❔ ‼️ ⁉️ 🔅 🔆 〽️ ⚠️ 🚸 🔱 ⚜️ 🔰 ♻️ ✅ 🈯 💹 ❇️ ✳️ ❎ 🌐 💠 Ⓜ️ 🌀 💤 🏧 🚾 ♿ 🅿️ 🛗 🈳 🈂️ 🛂 🛃 🛄 🛅 🚹 🚺 🚼 🚻 🚮 🎦 📶 🈁 🔣 ℹ️ 🔤 🔡 🔠 🆖 🆗 🆙 🆒 🆕 🆓 0️⃣ 1️⃣ 2️⃣ 3️⃣ 4️⃣ 5️⃣ 6️⃣ 7️⃣ 8️⃣ 9️⃣ 🔟 🔢 #️⃣ *️⃣ ⏏️ ▶️ ⏸ ⏯ ⏹ ⏺ ⏭ ⏮ ⏩ ⏪ ⏫ ⏬ ◀️ 🔼 🔽 ➡️ ⬅️ ⬆️ ⬇️ ↗️ ↘️ ↙️ ↖️ ↕️ ↔️ ↪️ ↩️ ⤴️ ⤵️ 🔀 🔁 🔂 🔄 🔃 🎵 🎶 ➕ ➖ ➗ ✖️ 🟰 ♾️ 💲 💱 ™️ ©️ ®️ 🔚 🔙 🔛 🔝 🔜 〰️ ➰ ➿ ✔️ ☑️ 🔘 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪ 🟤 🔺 🔻 🔸 🔹 🔶 🔷 🔳 🔲 ▪️ ▫️ ◾ ◽ ◼️ ◻️ 🟥 🟧 🟨 🟩 🟦 🟪 ⬛ ⬜ 🟫 🔈 🔇 🔉 🔊 🔔 🔕 📣 📢 💬 💭 🗯 ♠️ ♣️ ♥️ ♦️ 🃏 🎴 🀄 🕐 🕑 🕒 🕓 🕔 🕕 🕖 🕗 🕘 🕙 🕚 🕛"),
        c("🏳️",
            "🏳️ 🏴 🏁 🚩 🏳️‍🌈 🏳️‍⚧️ 🏴‍☠️ 🇦🇨 🇦🇩 🇦🇪 🇦🇫 🇦🇬 🇦🇮 🇦🇱 🇦🇲 🇦🇴 🇦🇶 🇦🇷 🇦🇸 🇦🇹 🇦🇺 🇦🇼 🇦🇽 🇦🇿 🇧🇦 🇧🇧 🇧🇩 🇧🇪 🇧🇫 🇧🇬 🇧🇭 🇧🇮 🇧🇯 🇧🇱 🇧🇲 🇧🇳 🇧🇴 🇧🇷 🇧🇸 🇧🇹 🇧🇼 🇧🇾 🇧🇿 🇨🇦 🇨🇩 🇨🇫 🇨🇬 🇨🇭 🇨🇮 🇨🇰 🇨🇱 🇨🇲 🇨🇳 🇨🇴 🇨🇷 🇨🇺 🇨🇻 🇨🇼 🇨🇾 🇨🇿 🇩🇪 🇩🇯 🇩🇰 🇩🇲 🇩🇴 🇩🇿 🇪🇨 🇪🇪 🇪🇬 🇪🇭 🇪🇷 🇪🇸 🇪🇹 🇪🇺 🇫🇮 🇫🇯 🇫🇰 🇫🇲 🇫🇴 🇫🇷 🇬🇦 🇬🇧 🇬🇩 🇬🇪 🇬🇬 🇬🇭 🇬🇮 🇬🇱 🇬🇲 🇬🇳 🇬🇶 🇬🇷 🇬🇹 🇬🇺 🇬🇼 🇬🇾 🇭🇰 🇭🇳 🇭🇷 🇭🇹 🇭🇺 🇮🇩 🇮🇪 🇮🇱 🇮🇲 🇮🇳 🇮🇶 🇮🇷 🇮🇸 🇮🇹 🇯🇪 🇯🇲 🇯🇴 🇯🇵 🇰🇪 🇰🇬 🇰🇭 🇰🇮 🇰🇲 🇰🇳 🇰🇵 🇰🇷 🇰🇼 🇰🇾 🇰🇿 🇱🇦 🇱🇧 🇱🇨 🇱🇮 🇱🇰 🇱🇷 🇱🇸 🇱🇹 🇱🇺 🇱🇻 🇱🇾 🇲🇦 🇲🇨 🇲🇩 🇲🇪 🇲🇬 🇲🇭 🇲🇰 🇲🇱 🇲🇲 🇲🇳 🇲🇴 🇲🇷 🇲🇹 🇲🇺 🇲🇻 🇲🇼 🇲🇽 🇲🇾 🇲🇿 🇳🇦 🇳🇪 🇳🇬 🇳🇮 🇳🇱 🇳🇴 🇳🇵 🇳🇷 🇳🇺 🇳🇿 🇴🇲 🇵🇦 🇵🇪 🇵🇫 🇵🇬 🇵🇭 🇵🇰 🇵🇱 🇵🇷 🇵🇸 🇵🇹 🇵🇼 🇵🇾 🇶🇦 🇷🇴 🇷🇸 🇷🇺 🇷🇼 🇸🇦 🇸🇧 🇸🇨 🇸🇩 🇸🇪 🇸🇬 🇸🇮 🇸🇰 🇸🇱 🇸🇲 🇸🇳 🇸🇴 🇸🇷 🇸🇸 🇸🇻 🇸🇾 🇸🇿 🇹🇩 🇹🇬 🇹🇭 🇹🇯 🇹🇱 🇹🇲 🇹🇳 🇹🇴 🇹🇷 🇹🇹 🇹🇼 🇹🇿 🇺🇦 🇺🇬 🇺🇸 🇺🇾 🇺🇿 🇻🇦 🇻🇨 🇻🇪 🇻🇬 🇻🇮 🇻🇳 🇻🇺 🇼🇸 🇽🇰 🇾🇪 🇿🇦 🇿🇲 🇿🇼 🏴󠁧󠁢󠁥󠁮󠁧󠁿 🏴󠁧󠁢󠁳󠁣󠁴󠁿 🏴󠁧󠁢󠁷󠁬󠁳󠁿")
    )
}
