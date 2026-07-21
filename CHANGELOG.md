# Changelog

Release notes for every RimBoard version. The current release is summarised in the [README](README.md).

## Unreleased

**Suggestion bar and tools**
- The chevron is now a drawer: it slides your pinned tools onto the bar and
  closes when you run one. The settings and clipboard icons are no longer
  fixed on the bar — both are ordinary pinnable tools now, so every slot is
  yours.
- Opening the full tools panel became its own pinnable tool ("All tools"),
  and long-pressing the chevron always reaches it.
- Tool slots size themselves to the bar: a couple sit large, a full drawer
  packs tighter, and past a minimum the row scrolls. A fixed width had been
  overflowing the narrower strip of floating mode.

**Look**
- Flat key style is the default: bare letter glyphs, caps only on the keys
  that are not letters, no shadows. The raised style is one switch away in
  Settings, Theme, and returns automatically behind a background image, where
  bare letters on a photo are unreadable.
- Six new palettes: Ocean, Forest, Sunset, Graphite, Rose and Mint.
- Key labels cross-fade on a plane or language switch; the language name
  flashes on the spacebar when you switch. Motion respects the system
  "remove animations" setting.

**Languages** — every bundled language now has the same support
- Native offensive-word lists for all 22 (was English and Turkish; the rest
  relied on the English fallback).
- Starter next-word predictions for all 22 (was English and Turkish, with no
  fallback at all, so the other twenty had none until you had typed enough).
- Settings, the tools panel and every accessibility label translated into all
  seven interface languages.

**Accessibility**
- Five icon-only buttons that screen readers could not announce now have
  labels: "All tools" on the suggestion strip (which every fresh install
  starts with, at the far left), Translate, Undo and Redo in the editing
  panel, and Clear in the clipboard panel — the one destructive control
  there. Still to do: the six cursor arrows in the editing panel.
- Reopening the tools panel after rearranging it in the picker no longer
  leaves a screen reader describing the previous layout.
- Screen readers can use the keyboard. Every key is announced, with character
  keys naming the glyph they would produce so shift is reflected rather than
  described, and keys with long-press alternatives say so.
- The tools panel is navigable too, with pin and unpin as explicit actions —
  dragging is not a gesture a screen reader user can perform.

**Typing**
- The Theme and Keyboard height dropdowns are translated. Twelve of the
  fourteen settings dropdowns pulled their options from translatable strings;
  these two had them written inline, so they stayed English in every
  interface language.
- Inline calculator gained metric/imperial conversion (`5km=`).
- Email fields offer domain completions after `@`, where word suggestions are
  correctly switched off and the bar was otherwise idle.

**Fixes and stability**
- "Leave symbols after space" works. The setting has shipped switched on and
  doing nothing: its one implementation sat on the code path for ordinary
  character keys, which the spacebar never takes.
- Opening emoji, the clipboard or the editing panel from the toolbar drawer
  while the tools panel is open now actually shows them. The tools panel is
  the topmost panel and none of the three hid it, so the new panel was drawn
  underneath and the keyboard looked stuck.
- Fixed a crash when a held key rebuilt the keyboard (floating mode, or
  settings hiding it) while a second finger was landing.
- Fixed a crash when dragging a row in the toolbar arranger if the list was
  mid-relayout.
- "Auto-space after punctuation" no longer inserts a space that was not
  asked for. Its pending state survived pressing space, pressing enter, and
  even moving to another app's text field, so the next letter you typed got
  a space in front of it — a doubled space mid-sentence, or a stray one at
  the start of an empty field.
- Typing statistics format their numbers and the "since" date in the
  interface language you picked, rather than the phone's language.
- The personal dictionary screen shows your words. It read them before the
  load off disk had finished, so it opened saying you had none.
- Text shortcuts restored from a backup now take effect immediately instead
  of waiting for the keyboard process to be killed.
- Emoji that are drawn as plain glyphs rather than colour pictures (⏸ 🕳 🗣
  and friends) now follow the keyboard theme. They were taking their colour
  from the system light/dark setting instead, so they could come out black
  on a black keyboard.
- The skin-tone popup and the "remove word" popup close with the keyboard
  instead of outliving it.
- Picking an image the phone cannot decode now says so, instead of reporting
  "Background removed" — which was both wrong and the opposite of what had
  happened, since the old background was still in place.
- Exporting and restoring a backup no longer freeze the settings screen while
  they work.
- The Theme tool cycles through all thirteen themes. It held its own copy of
  the list, six palettes out of date, so Ocean through Mint were unreachable
  — and starting on one of them threw you back to System with no way in.
- Fixed a data race between the background dictionary warm-up and the UI
  thread that could corrupt the prediction cache.
- Held key repeat no longer survives the keyboard being replaced — a rotation
  mid-hold could keep deleting text from a keyboard that no longer existed.
- Learned words, bigrams and trigrams are now written and waited for at
  shutdown instead of being queued and possibly lost.
- Restoring a backup reports honestly: a failed write no longer says
  "restored", and settings are applied only once the data has landed, so a
  rejected restore leaves your setup alone.
- Dictionary import and background images run off the main thread and always
  report their result; both previously froze the UI on large files and failed
  silently.
- Fixed thread leaks from the settings screens, two force-unwraps on the
  per-keystroke path, and a shortcut save that could throw and vanish.
- Failures that used to be swallowed now log to `adb logcat -s RimBoard`,
  alongside dictionary load timings.

## What's new in 2.8.0

- **Customizable toolbar** — pick your favourite shortcuts in Settings →
  Preferences → Toolbar keys (all 20 actions available); they show in the
  always-visible strip, which now scrolls and shares space with your recent
  emoji instead of replacing them. Press-and-hold icons in the expanded toolbar
  to drag them into your own order.
- **Emoji, finished** — a ninth tab with ~215 flags, long-press any emoji for
  its skin-tone variants (probed from the device font, so only real variants
  are offered), and emoji search now understands German, Spanish, French,
  Italian, Portuguese and Russian keywords alongside English and Turkish.
- **New toggles** — Smart tap correction and the Inline calculator can each be
  switched off under Settings → Suggestions.
- **Accessibility** — every toolbar and strip icon now carries a TalkBack
  label; previously the icon buttons were silent to screen readers.
- **Fixes** — the toolbar stays reachable in incognito mode (so you can turn
  incognito off from it); the block-word popup follows the keyboard theme; the
  floating keyboard no longer publishes an empty touch region before its first
  layout (taps fell through to the app); tapping one-handed while floating now
  exits floating instead of doing nothing.
- **Under the hood** — first unit-test suite (calculator + tap-targeting
  geometry, runs in CI before every APK build); tap-targeting geometry is now
  derived from each language's real layout so it can never drift; release
  builds can be signed with a real key via gradle properties or environment
  variables (no keys in the repo).

## What's new in 2.7.2

- **Gboard-style toolbar** — a chevron on the left of the idle suggestion bar
  expands into a scrollable row of tools: one-handed, resize (height), floating,
  next language, text editing, clipboard, emoji, translate, share, theme, undo,
  redo, incognito, settings and hide. Tapping the chevron again collapses it.
  New actions this adds: quick **theme** cycling, keyboard **resize**, and
  **share** (via the system share sheet — nothing leaves the device unless you
  pick a target).
  - Not included, on purpose: GIF, stickers and scan-text. Those need the
    internet or the camera, which RimBoard deliberately never asks for.
- **Fixes from a code audit** — the character model no longer blocks the first
  taps while a dictionary is still loading; the inline calculator won't fire on
  a text-shortcut expansion or when its expression is truncated off-screen, and
  its chip can't be "blocked" like a word; Turkish taps fold the dotless ı
  correctly in tap targeting.

## What's new in 2.7.1

- **Vibration fixed on Xiaomi/HyperOS** — key-press haptics used a predefined
  vibration effect that many OEM devices silently ignore, so the keyboard felt
  dead even with haptics on. It now uses a predefined effect only when the
  device reports support and otherwise a reliable one-shot vibration, so every
  device with a motor buzzes (and still works when the system touch-feedback
  toggle is off).
- **Floating keyboard fixed** — toggling floating mode rebuilt the keyboard but
  skipped its setup, leaving it blank. It now re-applies layout, theme and
  settings on toggle.
- **Refreshed default theme** — the built-in light and dark themes get a
  cleaner, more modern skin (Gboard layout with a Telegram-style palette):
  quieter neutrals, one confident azure accent, smoother 11dp key corners and a
  touch more spacing. AMOLED, high-contrast, custom and dynamic themes are
  unchanged. All colours are original.

## What's new in 2.7.0

- **Adaptive tap targeting** — the technique behind Gboard's tap accuracy,
  implemented from scratch: when a touch lands near a key boundary, a spatial
  Gaussian around each key centre is combined with a per-language
  character-transition model (built from the bundled dictionary at load), and
  the most probable letter wins. Type "t" then tap between q and w — you get
  the w you meant. Touches comfortably inside a key are never diverted, and
  password fields are exempt. Works for all 22 languages.
- **Trigram predictions** — the keyboard now learns two-word contexts, not
  just word pairs: after "see you" it can predict "soon" even though "you"
  alone is usually followed by "are". Trigram evidence outranks bigram,
  everything stays on-device, and the data is included in backups.
- **Inline calculator** — type `12*34` and the suggestion bar offers `= 408`;
  tap to insert. Proper operator precedence, decimal commas, × and ÷.
  Dates (12/07/2026) and phone-style numbers are left alone unless you type
  an explicit trailing `=`.
- **Telegram-grade rendering** — the technique, not the code: the keyboard is
  one custom-drawn view with a zero-allocation draw loop (shifted key labels
  are now cached instead of re-created every frame), an eased spring-back on
  key release, a soft radial highlight that blooms from the exact touch point,
  and long-press popups and key previews that scale in with a subtle
  overshoot. All animations run on `postInvalidateOnAnimation` frame timing.
- Everything above is offline, permission-free and original code (MIT).

## What's new in 2.6.0

- **Proximity-aware autocorrect** — corrections now weigh where the keys sit on
  the layout, noisy-channel style: an adjacent-key slip (`helko` → `hello`,
  `noq` → `now`) is treated as far more likely than a distant one, so the word
  that gets auto-committed matches what you meant more often. Works for every
  layout (QWERTY/QWERTZ/AZERTY/Cyrillic/Greek/Turkish), and the strip can now
  offer up to two corrections instead of one.
- **Next-word predictions from the first word** — a small bundled starter model
  (English and Turkish) means the suggestion bar predicts your next word before
  it has learned anything from you; your own learned n-grams still take priority
  and take over as you type. Regenerate or extend it with
  `python3 tools/build_predictions.py`.
- **Emoji search** — the emoji panel has a 🔍 search with its own compact
  keypad and a fully offline keyword index (420 English + Turkish terms); type
  `cat`, `pizza`, or `heart` to find emoji. Accent-folding lets an ASCII query
  reach accented keywords (`kopek` → köpek). Extend it with
  `python3 tools/build_emoji_search.py`.
- **Polish** — the auto-commit suggestion now sits on a compact inset pill
  instead of a full-height bar, and the emoji-search keys have rounded,
  press-highlighted backgrounds.
- Also fixed: a type-mismatch compile error in `SuggestionEngine` that broke
  clean builds of 2.5.0.

## What's new in 2.5.0

- **Split keyboard** — off, landscape-only, or always; letter rows part around a centered gap while the bottom row stays full-width.
- **Custom font** — load any .ttf for key labels; plus a key-borders toggle (borderless mode), narrow key gaps, side and bottom padding sliders, and custom space-bar text.
- **Toolbar keys** — pick up to twelve actions for the idle suggestion bar: undo, redo, copy, paste, cut, select all, one-handed, incognito, edit panel, floating, numpad, hide keyboard.
- **Spacebar gestures** — horizontal swipe moves the cursor or switches language; vertical swipe can hide the keyboard; long-press cycles languages or opens the input-method picker.
- **Smarter behaviors** — .com/.net/.org popups on the period key in URL and email fields, per-app language memory, auto-return from symbols after space, optional return after emoji or clipboard picks, long-press symbols for the numpad, and customizable currency symbols.
- **Offensive-word filter** — profanity is never suggested or autocorrected to (English and Turkish lists, on by default, toggleable).
- **Import dictionary from file** — load any “word frequency” text file into the current language; useful for unsupported languages and personal corpora.
- **Precision controls** — touch-and-hold delay in milliseconds and key text size in percent, both as sliders.
- New settings are localized in English and Turkish; other interface languages temporarily fall back to English for the new items.

## What's new in 2.4.0

- **Settings, reorganized Gboard-style** — the flat list is now proper sub-screens with icons and back navigation: Preferences, Look and feel, Suggestions, Glide typing, Clipboard, Privacy, Statistics, Backup, About.
- **Background image themes** — pick any photo as the keyboard background (permission-free document picker), with adjustable dimming so keys stay readable. Works over every theme.
- **New controls** — number row for passwords, auto-space after punctuation, touch-and-hold delay, key text size, key sound volume, vibration strength (light/medium/strong), and a glide-trail toggle.
- **Emoji fast-access row** — the idle suggestion bar now shows your recent emoji between the gear and clipboard; one tap inserts.
- **System (auto) theme** — follows the device light/dark setting.
- Fixed in this release's review: a strip-update regression that would have overwritten the incognito label with the idle bar.

## What's new in 2.3.0

- **Dictionaries at full scale** — 200,000 words for English, Turkish, German, Spanish, French, Italian, Portuguese and Russian; 100,000 for the other 14 languages. Dictionaries now preload on a background thread, so the first keystroke never stalls.
- **Rich settings** — a gradient hero header with version and language count, hand-drawn stroke icons on every key setting (theme, languages, shortcuts, statistics, backup and more), and Material accent coloring on all switches and dialogs.
- **Statistics dashboard** — WPM and total words now headline the stats screen as large accent tiles.

## What's new in 2.2.0

- **Key press animation** — keys sink in slightly on touch and fade smoothly back over 150 ms after release, giving every tap a tactile, fluid feel.
- **Depth** — soft shadows under every key, the preview bubble and long-press popups; the keyboard background now carries a subtle per-theme gradient (true black stays true black on AMOLED).
- **Suggestion highlight pill** — the boosted suggestion sits on a rounded accent-tinted pill instead of plain bold text.
- **Panel transitions** — clipboard, edit and emoji panels slide-fade in instead of snapping.
- **Detail work** — slightly rounder keys, rounded glide-trail stroke caps.

## What's new in 2.1.0

- **Gboard-class dictionaries** — word lists quadrupled: 120,000 words for English, Turkish, German, Spanish, French, Italian, Portuguese and Russian; 60,000 for every other language (previously 30,000 across the board). Sourced from the OpenSubtitles frequency corpus.
- **8 new languages** — Dansk, Norsk, Suomi, Magyar, Українська (full Ukrainian Cyrillic layout with ґ/ї long-press), Ελληνικά (native Greek layout), Hrvatski and Slovenčina — 22 languages total. (Azerbaijani is not available in the source corpus and will be added when a quality word list is found.)
- **Suggestion bar redesign** — when idle: settings gear on the left, Paste chip in the middle, clipboard on the right.
- **Decluttered comma menu** — now holds only edit panel, one-handed, floating, incognito and emoji; language, clipboard and settings live where they belong.
- **Correction engine speedup** — autocorrect candidates are indexed by word length, keeping corrections instant even with the 4x larger dictionaries.
- **Bugs found in this release's audit and fixed**: incognito icon could linger over suggestions while typing; the spacebar showed a raw emoji instead of the vector icon in incognito mode; a dead code path referencing the removed quick-action buttons.

## What's new in 2.0.0

- **Icon redesign** — every emoji in the keyboard chrome is replaced with hand-drawn vector icons that tint with your theme: globe, clipboard, edit, one-handed, floating, incognito, settings, emoji panel, keyboard picker, pin, trash, translate, undo/redo and search. The interface now looks identical on every device instead of depending on the system emoji font. (The emoji *panel* still shows real emoji, of course — that is content, not chrome.)

## What's new in 1.9.0

- **Text shortcuts** — Settings → Suggestions → Text shortcuts: define codes like "brb" → "be right back". While typing, the expansion appears as the first suggestion; hitting space expands it automatically. Included in backups.
- **Emoji suggestions** — type "fire" and 🔥 appears in the suggestion bar (English + Turkish keyword maps, offline).
- **Typing statistics** — Settings → Typing → Typing statistics: words, keys, backspace rate, autocorrections, active time and average WPM. 100% local, resettable.
- **Custom theme** — new Custom theme with a color picker (background, keys, text, accent — the rest of the palette is derived automatically), plus a High-contrast theme for accessibility.
- **Key repeat speed** — slow / normal / fast setting for backspace and arrows.
- **Stable APK signing** — builds are now signed with a fixed debug key committed to the repo, so every new version installs directly over the previous one. (One-time step when upgrading from an older build: export a backup in Settings → Backup, uninstall, install the new APK, import the backup.)
- **Haptics dedup fix** — key vibration now fires exactly once per press via a single path.

## What's new in 1.8.0

- **6 new languages** — Dutch, Polish, Swedish, Indonesian, Romanian, Czech — and every dictionary grown to 30,000 words (3× larger).
- **Floating keyboard** — long-press comma → ▣: a compact keyboard with a ☰ drag handle you can move anywhere; taps outside pass through to the app. Position is remembered.
- **Personal dictionary** — Settings → Suggestions: view every learned word with its use count, add words, remove words.
- **Remove any suggestion** — long-press a word in the suggestion bar → 🗑 to block it forever (works for built-in dictionary words too).
- **Learned words earn their place** — a new word must be typed 3+ times before it appears in suggestions.
- **Language auto-detection** — type 3 words in your other enabled language and suggestions quietly swap priority; it swaps back on its own.
- **Translate** — 🌍 in the edit panel hands text to any installed translator via the system process-text action; RimBoard itself still has no network access.
- **Interface language** moved to the ⋮ menu in Settings; settings reorganized with a Suggestions section.
- **Haptics actually work now** — key presses vibrate via the vibrator service (fixes silent keys on MIUI/HyperOS). This adds the harmless install-time VIBRATE permission; still no network, storage or contacts access.
- First run now enables your device language automatically.

## What's new in 1.7.0

- **Quick actions bar** — when the suggestion bar is idle, it shows shortcuts: 📋 clipboard, ✂ edit panel, 😊 emoji, 🕶 incognito, ⚙ settings. Toggle it in Settings.
- **Undo / redo** — ↶ ↷ buttons in the edit panel (sends Ctrl+Z / Ctrl+Shift+Z to the app).
- **Word delete, forgiven** — after swiping left on backspace, slide back right to restore deleted words one by one, with a haptic tick per word.
- **Auto-clear clipboard** — optional setting to drop unpinned clips after 15 or 60 minutes.
- Backups now include pinned clips; pins reload after a restore.
- Bilingual suggestions are now capitalized with their own language rules (no more Turkish dotted İ on English words).

## What's new in 1.6.0

- **Text editing panel** — long-press comma, tap ✂: arrow keys, Home/End, Select, Select all, Copy, Cut, Paste.
- **Word delete gesture** — touch backspace and swipe left to delete whole words, one per step.
- **Clipboard pinning** — tap 📌 on any clip to pin it. Pinned clips survive restarts (stored in the app's device-encrypted storage because you explicitly chose to keep them); everything else stays RAM-only and vanishes when the keyboard process ends.
- **Bilingual typing** — with two or more languages enabled, suggestions draw from your top two dictionaries, and autocorrect never "fixes" a word that is valid in the other language.
- **Interface language** — Settings → Interface language: the keyboard panels and settings UI in any of the 8 supported languages, independent of your system language.

Feature ideas in this release were informed by studying Gboard, SwiftKey, HeliBoard and FlorisBoard. No code was copied from any of them — HeliBoard/OpenBoard are GPL-3.0 licensed and RimBoard is MIT, so every implementation here is original and written for RimBoard's own architecture.

