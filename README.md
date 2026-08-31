# RimBoard

A free, open-source Android keyboard with a GBoard-style layout, on-device
suggestions, and a real incognito mode. No ads, no accounts, no analytics.

RimBoard ships as **two builds**, and which one you install is the privacy
decision:

- **`offline`** — **three permissions**: `VIBRATE` for key haptics, and
  `READ_CONTACTS` and `READ_USER_DICTIONARY`, both inert until you switch the
  matching setting on. No `INTERNET`, so Android will not give the app a network
  connection even if its code asked for one — which is what makes the
  contacts permission survivable: nothing read can leave the device by any
  route. This is the default recommendation and the build the privacy claims
  below are about.
- **`online`** — adds `INTERNET` for translation and GIF search. Translation
  works with no key (keyless by default, an optional Anthropic key upgrades it);
  GIF search needs a key you supply. Its offline switch is enforced by
  RimBoard's own code rather than by the system, and any key you do set is your
  own — there is no RimBoard server and no shared key.

The two are separate apps and install side by side under the same name, so
each launcher icon carries its build written under the keyboard symbol —
`OFFLINE` or `ONLINE`. Settings → About → Version says the same thing
(`2.9.1-offline`), which is the one to quote in a bug report.

The split exists because `INTERNET` is a normal, install-time Android
permission: once an APK declares it, it is granted at install and neither you
nor the app can revoke it. An "offline mode" inside a build that holds the
permission is a promise the app makes about itself. Leaving the permission out
is a fact about the APK that you can check without trusting anyone — see
[Proving it](#proving-it).

- Kotlin, no heavyweight dependencies, one APK with everything in it —
  33 MB, nearly all of it dictionaries and language models
- 22 languages built in — English, Turkish, German, Spanish, French, Italian,
  Portuguese, Russian, Dutch, Polish, Swedish, Indonesian, Romanian, Czech,
  Danish, Norwegian, Finnish, Hungarian, Ukrainian, Greek, Croatian, Slovak —
  with native layouts (QWERTY, QWERTZ, AZERTY, ЙЦУКЕН, Greek, Turkish Q)
- Gboard-class typing engine: adaptive tap targeting, proximity-aware
  autocorrect, glide typing, trigram predictions, emoji search, inline
  calculator — all fully offline
- MIT licensed code, CC BY-SA 4.0 dictionaries

## Installing

Every release carries both builds, on the
[Releases page](https://github.com/An0nym010/rimboard/releases). Download one
APK and open it — that is the whole process. It works from the phone itself; no
computer, no `adb`, no account.

| | |
|---|---|
| `RimBoard-<version>-offline.apk` | **Start here.** No `INTERNET` permission at all. |
| `RimBoard-<version>-online.apk` | Only if you want translation or GIF search. |

Android will ask you to allow installing from your browser or files app the
first time; that prompt is Android's, not RimBoard's. Then:

**Settings → System → Languages & input → On-screen keyboard → Manage
keyboards**, turn RimBoard on, and pick it with the keyboard-switch key. The
exact path differs a little by manufacturer. Android warns you that a keyboard
"may be able to collect all the text you type" — it says that about every
keyboard, because it is true of every keyboard. What RimBoard does with it is
[the rest of this README](#privacy), and the `offline` build is the one where
you do not have to take that on trust.

Not on the Play Store. Sideloading is the only distribution.

### Checking what you downloaded

Release APKs are signed with the project's key. To confirm one is genuinely
that build rather than something re-signed:

```
apksigner verify --print-certs RimBoard-<version>-offline.apk
```

The SHA-256 of the certificate is printed in the build log of the run that
produced the release, so the two can be compared. A build signed with the
Android debug key — the one every SDK ships, which anyone can forge — is
refused by CI on a tagged build for exactly this reason.

An `offline` APK can be checked for the thing it claims outright:

```
aapt dump permissions RimBoard-<version>-offline.apk
```

It should list `VIBRATE`, `READ_CONTACTS` and `READ_USER_DICTIONARY`, and
nothing else. See [Proving it](#proving-it) for what each is for and what
bounds it.

**Upgrading:** Android refuses to replace an app in place if the signing key
changed, so if you installed a build signed with the debug key you will have to
uninstall before installing a properly signed one. Export your learned words
first — **Settings → Backup** — because uninstalling deletes them.

## Device compatibility

Runs on any Android 8.0+ phone or tablet (API 26, ~97% of devices) — Samsung, Xiaomi, Pixel, OnePlus, Oppo, Huawei and everything else. No Google services required, pure Kotlin with no native code, works on every CPU architecture.

Both builds are checked against this rather than assumed. `aapt dump badging`
on either release APK reports `sdkVersion:'26'`, all four screen buckets, **no
`native-code` line** (so no ABI restriction) and **no `uses-feature`** (so no
hardware requirement — no camera, no telephony, nothing that would filter a
device out). Lint runs with `NewApi` at error severity on both flavors, so an
API newer than 26 cannot reach a release without a version guard. The online
build's extra features degrade rather than crash: an app that will not accept
a GIF is detected before the picker opens, and sticker thumbnails deliberately
avoid a format that only decodes from API 28.

- **Direct boot aware** — the keyboard works on the lock screen right after a reboot, before your first unlock
- **No fullscreen extract mode** — landscape typing keeps your app visible, Gboard-style
- **Themed navigation bar** — no white system-bar strip under a dark keyboard on 3-button-nav devices
- **Emoji filtered per device** — emoji your Android version can't render are hidden instead of showing ▯ boxes

## What's new

The latest release is **2.9.1**. See **[CHANGELOG.md](CHANGELOG.md)** for the release notes of this and every earlier version.

## Features

**Typing**
- Glide typing: slide across letters to type a word, with a swipe trail and
  tap-to-replace alternatives in the strip (works in all bundled languages)
- GBoard-like key layout, sizes and spacing; adjustable key height; optional
  number row (digit hints on the top row when it's off)
- Auto-capitalization (sentence-aware), double-tap Shift for caps lock
- Autocorrect (edit distance ≤ 2 against a frequency dictionary) with a
  one-tap "↩ original" revert chip that also teaches the keyboard your word
- Suggestion strip: verbatim | best match | alternative, with the word that
  will be auto-committed shown in bold/accent
- 200,000 words per language built in, 298,946 for English, and a deeper list
  per language you can add later — downloaded on the `online` build, imported
  from a file you fetched yourself on the `offline` one, checksum-checked
  either way
- Next-word predictions from a bundled corpus model (282,000 one- and
  two-word contexts across 22 languages) merged with what you have typed
  yourself, which is weighed as trigrams
- Double-space inserts ". ", hold-and-slide the spacebar to move the cursor
- Long-press popups for accents, digits and symbols; key preview bubbles
- Two symbol pages, a phone/number pad for numeric fields, 1,500+ emoji with
  categories and recents (anything your Android version can't render is hidden)
- Long-press Enter inserts a newline in chat apps where Enter sends
- Multi-touch typing (rollover), repeating backspace
- Inline calculator — type `12*34` for a "= 408" chip. Handles parentheses,
  percentages the way a pocket calculator does (`150+18%` → 177, `200-10%` →
  180) and metric/imperial conversion (`5km=` → 3.1069 mi)

**Tools**
- **Tool drawer** — the chevron at the left of the suggestion bar is the only
  fixed control on it; tapping it slides your pinned tools into view, and
  tapping one runs it and closes the drawer. Settings, clipboard and the rest
  are pinned, reordered or removed like any other tool rather than being fixed
  in place.
- **All tools** — a full-height panel listing every action, with a tray at the
  top for what is currently on the suggestion bar. Tap **+** on any tool to pin
  it, or hold and drag it between the two sections. It is itself a pinnable
  tool, and long-pressing the chevron always opens it.
- Long-press the comma key for a quick 😊 shortcut.
- ◨ **One-handed mode** — shrink the keyboard to either side (drawer → ◨); a
  rail switches sides or expands back. Auto-off in landscape.
- 📋 **Clipboard history** — last 10 copied items (drawer → 📋, or long-press
  the paste chip). The history is RAM-only: cleared when the keyboard process
  ends, disabled in incognito, 🗑 wipes it instantly. The one thing that
  persists is a clip you explicitly 📌 pin — that item is saved to the app's
  device-protected storage so it survives a restart, and stays only until you
  unpin it.

**Languages**
- 22 languages out of the box (see the list at the top) — pick any set in
  Settings; 🌐 cycles them, long-press for the system picker; locale-correct
  casing (Turkish i → İ, Cyrillic, etc.)
- Add more languages by dropping a dictionary file and a layout (see below)

**Privacy**
- **`VIBRATE` in every build.** Key haptics drive the vibrator directly,
  because several OEM builds ignore view-level haptics once the system touch
  feedback toggle is off. It grants no access to any data.
- **`READ_CONTACTS`, off by default and behind two gates.** It exists so the
  keyboard and the spell checker stop treating the people you write to as
  misspellings. Nothing is read until you switch on *Names from contacts* and
  grant the permission; what is kept is a set of lowercase name parts in
  memory, never written to disk, dropped when the setting goes off or memory
  runs short. No numbers, no addresses, no contact identity.
- **`READ_USER_DICTIONARY`, off by default.** The list at Settings —
  Languages — Personal dictionary is shared by every app and is where a user
  writes a word down by hand to say "this is a word". Reading it means a word
  you added there, or taught another keyboard, or brought from an old phone,
  stops being underlined here. Read only: `WRITE_USER_DICTIONARY` is
  deliberately not requested, so this never adds to your list.

  Worth knowing that this one is unusual. The permission is **not in the public
  SDK** any more — `android.Manifest` no longer carries a constant for it,
  though the provider it guards is still public and documented. So there is no
  runtime prompt to show you: the setting is the gate, the read is attempted,
  and a refusal from the system is treated as an empty list. On a build that
  declines, the switch simply has no effect.
- **No microphone, no storage, no location** — in either build.
- **No `INTERNET` in the `offline` build**, which is what makes its guarantee
  a guarantee rather than a promise. The `online` build declares `INTERNET`
  and `ACCESS_NETWORK_STATE`. Don't take either on trust; see
  [Proving it](#proving-it) below.
- **A first-run dialog** asks which mode you want, with the trade-offs spelled
  out, before you have enabled the keyboard for anything. The answer is not
  carried over by Export/Import — restoring a backup is not the same act as
  consenting to network access, so a fresh install asks again.
- **All network access goes through one function**, `Net.fetch` in
  [`net/Net.kt`](app/src/main/java/com/rimboard/keyboard/net/Net.kt), which
  refuses a request unless the build holds the permission, the user has
  switched online features on, the target host is on a short allowlist in that
  same file, and — for anything carrying text you typed — you are not in
  incognito. A unit test fails the build if any other file in the app so much
  as names a networking API, so reading that one file tells you everywhere the
  keyboard can talk to.
- **Nothing is sent in the background, ever.** There is no telemetry, no update
  check, and no sync. On the `online` build a request happens only in the
  moment you tap a GIF search or a translate action, and Settings → Network
  shows you the running count and what it was for.
- **Incognito mode** (🕶): open the drawer → 🕶, or enable
  "Always incognito" in settings. While active the keyboard learns nothing,
  suggests nothing personal, and records no emoji history.
- Incognito also turns itself on automatically in password fields and in any
  field that requests no personalized learning (e.g. browsers' private tabs).
- Learned words and the next-word model are plain-text files in the app's
  private device-protected storage (`learned.txt`, `bigrams.txt`,
  `trigrams.txt`). You can wipe them anytime from Settings → "Delete learned
  data".
- Nothing is uploaded to device backups or copied during a phone-to-phone
  transfer. `allowBackup=false` covers Android 11 and below; from Android 12
  that attribute is deprecated, so `res/xml/data_extraction_rules.xml` states
  the same thing in the form those releases actually read. It excludes the
  `device_*` domains too, which is where the learned words really are — the
  keyboard keeps them in device-protected storage so it works on the lock
  screen, and a rule set covering only the ordinary domains would miss them.
- Built-in **Export / Import backup** (Settings → Backup): everything —
  settings, learned words, predictions — goes into one JSON file you control,
  written through the system file picker. Move it between devices yourself;
  nothing ever leaves the phone otherwise.

## Translation and proofreading (`online` build only)

Tap 🌍 to open a translate bar and type — the translation goes into the field
as you pause. **This needs no API key.** Three services are supported, and
Settings → Network shows which one is actually in use:

| Service | Needs | Why you would pick it |
| --- | --- | --- |
| **Lingva** *(default)* | nothing | Works on a fresh install. No key, no account, no quota to think about. |
| **LibreTranslate** | your own server, or a key | Open source and self-hostable — the only option where the text never reaches anyone else. |
| **Anthropic** | your own API key | Best quality, and the only one that handles long text. |

"Automatic" picks the best one you have set up, so setting an Anthropic key is
all it takes to use it. All three detect what language you are typing.

If you run Lingva or LibreTranslate yourself, put the hostname in Settings →
Network and your text goes only to your own machine. That is the single
exception to the built-in host allowlist, it widens it by exactly that one
host, and it is still HTTPS-only.

The **Proofread** tool fixes spelling, grammar and punctuation in a selection
and is Anthropic-only.

Unless you self-host, whichever service translates sees the text, exactly as
any online translator does; nothing is sent in the background, in incognito, or
in a password field.

Both go through the same code path, so every guard below applies to both: they
require a selection, run off the main thread, and re-check the field before
committing.

- **The 🌍 tool is the same tool it has always been.** On the `offline` build,
  with online features off, or with no API key set, it does exactly what it did
  before: hands the text to whatever translator app you have installed, and
  sends nothing itself. The in-place version is what you get when all three of
  those are satisfied — one tool, not a second dead icon.
- **Bring your own key.** Settings → Network → API keys, from
  `console.anthropic.com`. The billing relationship is yours. An open-source
  APK has nowhere to hide a shared key — anything compiled in ships to
  everyone who installs it.
- **The key is not stored where everything else is.** Every other preference
  lives in device-protected storage so the keyboard works on the lock screen,
  which also means it is readable at rest before first unlock. The key goes in
  ordinary credential-protected storage instead — encrypted until you unlock
  the device, because nothing here is reachable from a lock screen. That makes
  it as safe as your lock screen and no safer; root or a full backup taken
  while unlocked can still read it.
- **It requires a selection.** It overwrites text, so it will not run on the
  whole field — translating a half-written message by accident is not a thing
  it should be able to do.
- **It refuses itself in incognito and in password fields**, because the
  request carries what you typed. That check is in [`Net.fetch`](app/src/main/java/com/rimboard/keyboard/net/Net.kt),
  not in the feature, so it cannot be forgotten by the next thing added.
- **The reply is checked before it lands.** A safety decline, an API error, or
  a response cut off at the token limit all surface as a message rather than
  being committed — a truncated translation looks finished, and would silently
  eat the end of your sentence. If the selection changed while the request was
  in flight, the result is discarded rather than committed into whatever is
  focused now.

Requests go to `api.anthropic.com` and carry the selected text. Nothing else is
sent, and nothing is sent in the background — see Settings → Network for the
running count.

## GIF and sticker search (`online` build only)

Tap the **GIF** or **Sticker** tool. The panel has its own compact keypad, so
you can type a query into it directly; tabs at the top switch between GIFs and
stickers and re-run the same search against the other index. If you had already
typed something in the field, that seeds the query — and picking a result
deletes it, because it was the query rather than part of your message.

- **The panel draws its own keys instead of using a text field.** An `EditText`
  inside an IME window competes for focus with the keyboard it belongs to, and
  the field being typed into belongs to a different app entirely. The emoji
  panel has always solved this by drawing a mini-keypad; that is now a shared
  `MiniKeypad` view.
- **Searches fire on a pause in typing, not per keystroke** — otherwise every
  letter is a billable, rate-limited request for a prefix nobody wants results
  for.
- **Stickers come back as transparent GIFs, not WebP.** Animated WebP only
  decodes from API 28, and the grid thumbnails are drawn with `BitmapFactory`.
  Choosing WebP would have left the sticker grid blank on Android 8.0 and 8.1
  while looking fine on a modern test device. Transparent GIF decodes
  everywhere and costs only file size. Results with no transparent variant fall
  back to the opaque one rather than leaving a hole in the grid.
- **It checks the field will take a GIF before it opens.** Plenty of apps and
  fields accept only text. Finding that out *after* browsing, choosing, and
  waiting for a download would read as a broken keyboard rather than an app
  that does not support images.
- **Unavailable is never just "unavailable."** Wrong build, network off,
  incognito, and no API key have four different fixes, so they produce four
  different messages.
- **Two hosts, both on the allowlist and both checked.**
  `api.klipy.com` serves the search results and the images come from KLIPY's
  CDN (`static`, `static1`, `static2`). That is why the allowlist has a bounded
  domain-suffix rule alongside its exact hosts — an exact list would break as a
  blank grid the first time they added `static3`. The rule matches the domain
  or something below a dot within it, so `evilklipy.com` and
  `klipy.com.evil.test` are both refused; both directions are pinned by tests.
  Image URLs come from the response rather than from RimBoard, so they are
  re-checked before being fetched and a response pointing elsewhere fails
  closed.
- **No `customer_id` is ever sent.** KLIPY accepts a stable per-user identifier
  to personalise results and drive a "recents" feature. Sending one would hand
  the provider a durable handle tying every search this keyboard makes to the
  same person, which is not a trade worth making in this app.
- **Attribution is shown in the panel** because it is a condition of using the
  API, so it is always present rather than shown when there happens to be room.
  KLIPY's advertising is opt-in for developers and RimBoard does not enable it.
- **The search query is typed text**, so it is refused in incognito. Fetching a
  chosen GIF is not, since by then the URL is fixed and carries nothing you
  typed.
- **The key is a URL path segment**, which is where KLIPY takes it. That is a
  sharper edge than a query parameter: a key containing a slash would not break
  the request, it would address a *different* endpoint on a host the gate has
  already allowed. Keys are therefore checked for path characters before use.
  It stays out of the request log either way, which records only hosts. That is normally the wrong place for a credential — it is tolerable here
  only because the request log records the host and never the path or query, so
  neither the key nor your search terms are written down.

Insertion uses `commitContent` through a `FileProvider` scoped to one cache
directory — declared **only in the online build**, so the offline APK ships no
such component at all. You can check that the same way as the permission:

```bash
aapt dump xmltree app-offline-release.apk AndroidManifest.xml | grep authorities
```

## What the keyboard can see on your phone (both builds)

One thing worth stating plainly, because it is not a permission and so will
not show up in any of the checks below.

The per-app tint takes its colour from the current app's declared theme and
falls back to its launcher icon, and the light/dark matching reads that same
theme. Both require that app to be *visible* to this process.
Android grants that through a `<queries>` block in the manifest, and RimBoard
declares a launcher-intent query — so **every app on your phone that has an
icon is visible to the keyboard**, and it could in principle enumerate them.

- It is **not** `QUERY_ALL_PACKAGES` and **not** a permission. Package
  visibility does not appear in `aapt dump permissions` at all, which is the
  point worth knowing: the two permissions it does print are not the whole of
  what an app can see.
- On the **offline build this cannot go anywhere**, because that build holds no
  `INTERNET` permission by any route. Whatever it can see, it cannot tell.
- **Settings → Theme → App colours** decides which apps are actually read, and
  **defaults to any app**. It defaulted to a fixed list of about forty
  well-known ones, and that was the wrong pairing: the two features it governs
  are on by default and promise to match the app you are typing in, so the
  narrow default made that promise false everywhere else — and a feature
  that is silently inert in most places reads as broken, not as restrained.
  Set it to **Well-known apps** for the old behaviour: everything off the list
  then keeps a colour derived from a hash of its package name, and follows the
  system rather than the app for light or dark.

Be clear about what that setting is and is not: it restrains RimBoard's own
code, not RimBoard's capability. A setting cannot revoke a manifest
declaration. Making it provable would take a second build dimension, the way
`INTERNET` did — the `<queries>` block would be absent from the APK entirely,
and then the check would be the manifest rather than a promise. That is not
built; if you want the strong version, that is the shape it takes.

## Proving it

Three checks, in increasing order of how little they ask you to trust.

**1. Ask the phone, from the phone.** Settings → **Network** shows the
permission list Android holds for the installed app — not a constant compiled
into RimBoard — and then *actually tries to open a TCP connection to
1.1.1.1:443 while you watch*, reporting whatever the system says back,
verbatim. On the offline build that is:

```
java.net.SocketException: socket failed: EACCES (Permission denied)
```

That is the kernel refusing the app, not the app declining to try. The screen
is deliberately incapable of flattering the build it is running on: on the
`online` build the same probe connects, and the screen says so.

**2. Ask the APK file.** The permission list comes from the manifest, so it
cannot be affected by what the code does at runtime:

```bash
aapt dump permissions app-offline-release.apk
```

```
package: com.rimboard.keyboard
uses-permission: name='android.permission.VIBRATE'
uses-permission: name='android.permission.READ_CONTACTS'
uses-permission: name='android.permission.READ_USER_DICTIONARY'
permission: com.rimboard.keyboard.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
uses-permission: name='com.rimboard.keyboard.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
```

The same command against `app-online-release.apk` additionally lists
`android.permission.INTERNET` and `android.permission.ACCESS_NETWORK_STATE`.
`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` is not a system permission —
AndroidX defines it in the app's own namespace to keep its internal broadcast
receivers private, and nothing outside the app can hold it.

**3. Ask the installed package,** if you would rather not trust the APK file
you were handed either:

```bash
adb shell dumpsys package com.rimboard.keyboard | grep -A20 "requested permissions"
```

CI runs check 2 against both release APKs on every push and fails if the
offline one has picked up `INTERNET` or the online one has lost it, so a
merger change cannot quietly undo the split between releases.

None of this depends on believing the source is what was built. If you want
that too, build it yourself — the whole point of the flavor split is that the
guarantee survives not trusting us.

## Install

### Option A — GitHub Actions (no Android Studio needed)
1. Push this repository to GitHub (or fork it).
2. The **Build APK** workflow runs automatically; open the run and download
   the `RimBoard-debug` artifact.
3. The artifact holds both builds. Copy **`app-offline-debug.apk`** to your
   phone unless you specifically want the network features, and install it
   (allow "install unknown apps" for your file manager if asked).
4. Tagging a commit `v1.0` (or any `v*`) attaches both **release** APKs to a
   GitHub Release automatically. Release builds are not debuggable; the debug
   artifacts above are, so they are for testing rather than for handing to
   anyone.

### Option B — Android Studio
Open the project, let Gradle sync, pick the **offlineDebug** or **onlineDebug**
variant in the Build Variants panel, then **Build → Build APK(s)**, or:

```
./gradlew assembleOfflineDebug
adb install -r app/build/outputs/apk/offline/debug/app-offline-debug.apk
```

Swap `Offline` for `Online` to build the other one. The two share an
`applicationId`, so installing one replaces the other and your settings and
learned words survive the switch — but the network choice does not, and a
switch to the online build asks again on first launch.

### Enable it (Xiaomi/HyperOS example — Poco phones)
1. Open the RimBoard app and follow the three steps, or go to
   **Settings → Additional settings → Languages & input → Manage keyboards**
   and enable RimBoard.
2. Tap any text field, then use the keyboard switcher (or the app's
   "Switch keyboard" button) to select RimBoard.
3. Android will show a standard warning that third-party keyboards may
   collect what you type — that applies to any IME. On the `offline` build it
   is the one case where the warning overstates things: that APK has no
   network permission, so it has no way to send anything anywhere, and
   Settings → Network will demonstrate it. On the `online` build, take the
   warning at face value.

### Use it for spell check in other apps (optional)

RimBoard also registers as a **system spell checker**, so the red underlines in
Gmail, Chrome and everything else can come from its dictionaries instead of the
platform's. This is worth doing mainly for Turkish and the other accented
languages: RimBoard peels suffixes, so `kitaplarımızdan` is not flagged, and it
knows the words you have taught the keyboard.

**Settings → Additional settings → Languages & input → Spell checker →
RimBoard spell checker.** No separate install and no new permission — it is a
second component in the same APK, inert until you select it. Or from a
computer:

```bash
adb shell settings put secure spell_checker_enabled 1
```

```bash
adb shell settings put secure selected_spell_checker com.rimboard.keyboard/.spell.RimSpellService
```

Check it took:

```bash
adb shell settings get secure selected_spell_checker
```

Two things this service does *not* do, both deliberately. It **never learns** —
the keyboard learns as you type, but a spell checker is handed text from every
app on the phone, including text you pasted or never wrote, and folding that
into your personal dictionary would be a much broader claim than "learns as you
type". It reads your learned words so they stop being underlined, and adds
nothing to them. And it **never touches the network**, on either build.

**Spell checking itself needs no permission.** Android has no runtime
permission for it: a spell checker is a service the system binds to, and your
selecting it in Settings is the whole of the consent step. The one
permission-looking line in the manifest, `BIND_TEXT_SERVICE`, is a permission
the *system* must hold in order to bind to this service — a lock on the door,
not a key the app is asking you for.

**Names are the exception, and they are opt-in.** Your contacts are not in any
dictionary, so without help the spell checker underlines the people you write
to, in every app, and offers to "correct" them to some real word a letter
away. Two things address that, and the first one costs nothing:

- **A capitalised word in mid-sentence is read as a name and left alone.** No
  permission, no setup, works for names that are in nobody's address book. It
  applies in every language except German, which capitalises all its nouns,
  and it costs the occasional real typo that happens to start with a capital.
  This is on always.
- **Settings — Advanced — Names from contacts** reads your address book so the
  names in it count as spelled correctly, including lowercase ones and ones
  the rule above cannot see. This is **off**, and turning it on is what
  triggers Android's permission prompt. Refuse the prompt and the switch goes
  back off rather than sitting on claiming to work.
- **Settings — Advanced — Words from the personal dictionary** accepts the
  words in Android's own shared list, the one at Settings — Languages —
  Personal dictionary. Also **off**, read only, and never added to.

What the second one holds is a set of lowercase name parts, in memory, never
written to disk and dropped the moment the setting is turned off or the system
asks for memory back. No numbers, no addresses, nothing that identifies a
contact. And on the `offline` build there is no `INTERNET` permission by any
route, so what it reads cannot leave the device even in principle — which is
the reason this permission is survivable here and would not be in an app that
could talk to a server.

## Dictionaries, bundled and extended

Every language ships with **200,000 words** in the APK, and English with
**298,946** — the two are different rules, not different numbers. 200,000 is a
cap; English's is every word the source corpus saw at least five times, which
is the depth the download below offers for the other twenty-one.

**The cap costs something measurable.** Tested against the shipped engine on
words drawn from the band a 100,000-word list omits, autocorrect silently
overwrites 35% of correctly-typed English words and 20% of Turkish ones, while
repairing ordinary typos at exactly the same rate. That is why nothing ships at
100,000 any more, and why the deeper list is worth fetching for a language you
actually type.

**And going deeper than five stops helping.** English at every depth — typos
still recognised as typos, typos actually repaired, correctly-typed rare words
destroyed:

| dictionary | typos kept | typos repaired | rare words destroyed |
|---|---|---|---|
| top 200,000 | 94% | 91% | 30% |
| count ≥ 5 (**shipped**) | 91% | 88% | 1% |
| count ≥ 3 | 86% | 84% | 1% |
| every word | **71%** | **69%** | 1% |

A word seen once in a subtitle corpus is usually a misspelling, a name, or an
OCR artifact. Take them all and the keyboard accepts nearly a third of real
typos as words — it stops underlining them and autocorrect stops fixing them.
There is deliberately no "everything" tier for the same reason there is no
"more eager autocorrect" one.

### Getting one onto the phone

**Settings → Corrections → Extended dictionaries.** The screen lists every
language with a deeper list, the languages you type first, with the size in
front of the button.

- On the **online** build the button downloads it.
- On the **offline** build there is no download and cannot be one: that APK
  holds no `INTERNET` permission, so the kernel refuses it a socket. The button
  opens the system file picker instead, and you feed it a file you fetched
  yourself — on a computer, over Bluetooth, off a memory stick. Picking a file
  grants access to that one file and needs no permission at all.

Both doors end at the same check. Every file's SHA-256 is in a manifest
compiled into the APK (`app/src/main/assets/extended.json`), and nothing is
installed that does not match one, so an imported file is trusted exactly as
much as a downloaded one: not at all until it matches. Installed dictionaries
live in the app's device-protected storage, are never backed up, and Remove
puts the bundled list back.

### Regenerating and hosting them

```
python3 tools/fetch_dictionaries.py              # the bundled assets, all 22
python3 tools/fetch_dictionaries.py en tr de     # only the ones you name
python3 tools/fetch_dictionaries.py --extended   # the downloadable set + manifest
```

The first form writes `app/src/main/assets/dictionaries/`. The second writes
`dist/dictionaries/<lang>.txt.gz` and regenerates the manifest. Corpora are
cached under `build/freqwords/`, so the two modes cost one download rather than
two.

`TOP`, `MIN_COUNT` and `BUNDLE_EXTENDED` at the top of that script are the
three knobs: the bundled cap, the extended depth, and which languages are
bundled at the extended depth instead of downloading it.

To host the result, push `dist/dictionaries/` to the branch named in
`DIST_BASE` (a `dictionaries` branch of this repository by default):

```
git switch --orphan dictionaries
cp dist/dictionaries/*.txt.gz .
git add *.txt.gz && git commit -m "Extended dictionaries"
git push -u origin dictionaries
```

An orphan branch keeps 23 MB of data out of the main history, and force-pushing
it on a regeneration keeps one copy rather than a pile. **Release assets would
be the tidier home and are not usable here**: a release download answers with a
redirect to `objects.githubusercontent.com`, and the online build's transport
refuses redirects on purpose.

Until that branch exists the screen still lists the languages and the download
fails with a message. The manifest ships regardless, so a build that cannot
reach the files is a feature that does nothing rather than a build that breaks.

Dictionary format is one `word count` pair per line, ordered by frequency.
Adding a whole new language also needs a layout in
`app/src/main/java/com/rimboard/keyboard/model/Layouts.kt` and a subtype in
`app/src/main/res/xml/method.xml`.

## Project notes

- After publishing your fork, update the GitHub URL in
  `app/src/main/res/xml/preferences.xml` (the "Source code" preference) so
  the settings link points at your repository.
- Release builds are signed with your own key when `rimboard.keystore` and
  friends are set (in `~/.gradle/gradle.properties`, or as `RIMBOARD_KEYSTORE`
  / `_PASSWORD` / `_KEY_ALIAS` / `_KEY_PASSWORD` environment variables), and
  fall back to the debug key otherwise. The fallback is fine for personal use, but a
  build signed with the shared debug key carries no proof of who made it, so
  set a real key before distributing to anyone else.

## Roadmap / not implemented yet

- **Voice input.** Not started, and it needs a decision before it can be: a
  keyboard that records audio wants `RECORD_AUDIO`, which is a bigger privacy
  step than `INTERNET` was and would need its own build dimension or a hard
  opt-in. The alternative — handing off to whatever voice IME is installed —
  needs no permission at all. That choice is the blocker, not the code.
- `EmojiView` still has its own inline copy of the mini-keypad rather than
  using the shared `MiniKeypad`. It is now a true no-regression swap —
  `MiniKeypad` gained the hold-to-repeat backspace and key caps that
  `EmojiView` had and it lacked — but it still wants an on-device check of
  emoji search, so it was left alone rather than changed blind.

## License

- **Code:** MIT (see `LICENSE`). If you prefer a public-domain-style grant,
  swapping in The Unlicense only requires replacing that file.
- **Dictionaries:** derived from
  [FrequencyWords](https://github.com/hermitdave/FrequencyWords) by Hermit
  Dave (OpenSubtitles 2018 corpus), CC BY-SA 4.0 — see `NOTICE`. Derivative
  dictionary files must stay CC BY-SA 4.0; the app code is unaffected.
