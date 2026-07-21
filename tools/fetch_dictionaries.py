#!/usr/bin/env python3
"""Regenerate the bundled word-frequency dictionaries.

Downloads frequency lists from Hermit Dave's FrequencyWords project
(OpenSubtitles corpus, CC BY-SA 4.0), filters to alphabetic words per
language, and writes the top N entries to
app/src/main/assets/dictionaries/<lang>.txt in "word count" format.

Streams and stops early once N words are collected, so even the _full
lists cost only a few MB of transfer each.
"""

import re
import sys
import urllib.request
from pathlib import Path

BASES = [
    "https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/{lang}/{lang}_full.txt",
    "https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/{lang}/{lang}_50k.txt",
    "https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2016/{lang}/{lang}_full.txt",
    "https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2016/{lang}/{lang}_50k.txt",
]

BIG = 200_000
MID = 100_000
TOP = {"en": BIG, "tr": BIG, "de": BIG, "es": BIG, "fr": BIG, "it": BIG, "pt": BIG, "ru": BIG}

PATTERNS = {
    "en": r"^[a-z']+$",
    "tr": r"^[a-z\u00e7\u011f\u0131\u00f6\u015f\u00fc]+$",
    "de": r"^[a-z\u00e4\u00f6\u00fc\u00df]+$",
    "es": r"^[a-z\u00e1\u00e9\u00ed\u00f3\u00fa\u00fc\u00f1]+$",
    "fr": r"^[a-z\u00e0\u00e2\u00e7\u00e9\u00e8\u00ea\u00eb\u00ee\u00ef\u00f4\u00f9\u00fb\u00fc\u00ff\u0153\u00e6']+$",
    "it": r"^[a-z\u00e0\u00e8\u00e9\u00ec\u00ed\u00ee\u00f2\u00f3\u00f9\u00fa']+$",
    "pt": r"^[a-z\u00e1\u00e2\u00e3\u00e0\u00e7\u00e9\u00ea\u00ed\u00f3\u00f4\u00f5\u00fa\u00fc]+$",
    "ru": r"^[\u0430-\u044f\u0451]+$",
    "nl": r"^[a-z\u00e9\u00eb\u00ef\u00f6\u00fc']+$",
    "pl": r"^[a-z\u0105\u0107\u0119\u0142\u0144\u00f3\u015b\u017a\u017c]+$",
    "sv": r"^[a-z\u00e5\u00e4\u00f6\u00e9]+$",
    "id": r"^[a-z]+$",
    # No cedilla forms here on purpose: FOLD rewrites them to the comma-below
    # letters before this pattern is applied, so they cannot reach it.
    "ro": r"^[a-z\u0103\u00e2\u00ee\u0219\u021b]+$",
    "cs": r"^[a-z\u00e1\u010d\u010f\u00e9\u011b\u00ed\u0148\u00f3\u0159\u0161\u0165\u00fa\u016f\u00fd\u017e]+$",
    "da": r"^[a-z\u00e6\u00f8\u00e5\u00e9]+$",
    "no": r"^[a-z\u00e6\u00f8\u00e5\u00e9]+$",
    "fi": r"^[a-z\u00e4\u00f6\u00e5]+$",
    "hu": r"^[a-z\u00e1\u00e9\u00ed\u00f3\u00f6\u0151\u00fa\u00fc\u0171]+$",
    "uk": r"^[\u0430-\u0449\u044c\u044e\u044f\u0454\u0456\u0457\u0491']+$",
    "el": r"^[\u03b1-\u03c9\u03ac\u03ad\u03ae\u03af\u03cc\u03cd\u03ce\u03ca\u03cb\u0390\u03b0\u03c2]+$",
    "hr": r"^[a-z\u010d\u0107\u017e\u0161\u0111]+$",
    "sk": r"^[a-z\u00e1\u00e4\u010d\u010f\u00e9\u00ed\u013a\u013e\u0148\u00f3\u00f4\u0155\u0161\u0165\u00fa\u00fd\u017e]+$",
}

# Spellings folded together before counting, per language.
#
# Romanian: the corpus mixes the correct comma-below letters (ș U+0219,
# ț U+021B) with the legacy cedilla ones (ş U+015F, ţ U+0163) inherited from
# pre-Unicode codepages. They are different characters. The Romanian layout
# offers only the comma-below pair — which is the standard — so every
# cedilla-spelled entry was a word the keyboard could suggest but could not
# type, and the corpus prefers those spellings about ten to one, so they
# outranked the ones a user can actually produce. Folding also reunites the
# frequency of a word that the corpus had split across both spellings.
FOLD = {
    "ro": str.maketrans({"ş": "ș", "ţ": "ț"}),
}

OUT_DIR = Path(__file__).resolve().parent.parent / "app/src/main/assets/dictionaries"


def fetch(lang: str, top: int) -> str:
    pat = re.compile(PATTERNS[lang])
    fold = FOLD.get(lang)
    last = "no source had enough usable words"
    for base in BASES:
        url = base.format(lang=lang)
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "rimboard-dict"})
            counts = {}
            with urllib.request.urlopen(req, timeout=60) as resp:
                buf = b""
                while len(counts) < top:
                    chunk = resp.read(65536)
                    if not chunk:
                        break
                    buf += chunk
                    *lines, buf = buf.split(b"\n")
                    for raw in lines:
                        line = raw.decode("utf-8", "ignore").strip()
                        sp = line.find(" ")
                        if sp <= 0:
                            continue
                        w = line[:sp]
                        if fold:
                            w = w.translate(fold)
                        if len(w) > 24 or not pat.match(w):
                            continue
                        try:
                            n = int(line[sp + 1:])
                        except ValueError:
                            continue
                        # Folded spellings have to sum rather than one being
                        # dropped: the surviving form must carry the frequency
                        # of both, or it ranks far below where the language
                        # actually uses it.
                        if w in counts:
                            counts[w] += n
                        elif len(counts) < top:
                            counts[w] = n
                        if len(counts) >= top:
                            break
            # Frequency descending, ties broken alphabetically so a rerun over
            # the same corpus produces the same file. Folding can move an entry
            # up, so the source order can no longer be relied on.
            out = sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))
            if len(out) >= min(top, 20000) or (out and "50k" in url):
                OUT_DIR.mkdir(parents=True, exist_ok=True)
                (OUT_DIR / f"{lang}.txt").write_text(
                    "\n".join(f"{w} {n}" for w, n in out) + "\n", encoding="utf-8")
                src = url.rsplit("/", 1)[-1]
                return f"{lang}: {len(out):>7} words  <- {src}"
        except Exception as e:
            last = f"{type(e).__name__}: {e}"
            continue
    return f"{lang}: FAILED ({last})"


if __name__ == "__main__":
    langs = sys.argv[1:] or list(PATTERNS.keys())
    for lg in langs:
        print(fetch(lg, TOP.get(lg, MID)), flush=True)
