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

BIG = 120_000
MID = 60_000
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
    "ro": r"^[a-z\u0103\u00e2\u00ee\u0219\u021b\u015f\u0163]+$",
    "cs": r"^[a-z\u00e1\u010d\u010f\u00e9\u011b\u00ed\u0148\u00f3\u0159\u0161\u0165\u00fa\u016f\u00fd\u017e]+$",
    "az": r"^[a-z\u00e7\u0259\u011f\u0131\u00f6\u015f\u00fc]+$",
    "da": r"^[a-z\u00e6\u00f8\u00e5\u00e9]+$",
    "no": r"^[a-z\u00e6\u00f8\u00e5\u00e9]+$",
    "fi": r"^[a-z\u00e4\u00f6\u00e5]+$",
    "hu": r"^[a-z\u00e1\u00e9\u00ed\u00f3\u00f6\u0151\u00fa\u00fc\u0171]+$",
    "uk": r"^[\u0430-\u0449\u044c\u044e\u044f\u0454\u0456\u0457\u0491']+$",
    "el": r"^[\u03b1-\u03c9\u03ac\u03ad\u03ae\u03af\u03cc\u03cd\u03ce\u03ca\u03cb\u0390\u03b0\u03c2]+$",
    "hr": r"^[a-z\u010d\u0107\u017e\u0161\u0111]+$",
    "sk": r"^[a-z\u00e1\u00e4\u010d\u010f\u00e9\u00ed\u013a\u013e\u0148\u00f3\u00f4\u0155\u0161\u0165\u00fa\u00fd\u017e]+$",
}

OUT_DIR = Path(__file__).resolve().parent.parent / "app/src/main/assets/dictionaries"


def fetch(lang: str, top: int) -> str:
    pat = re.compile(PATTERNS[lang])
    for base in BASES:
        url = base.format(lang=lang)
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "rimboard-dict"})
            out, seen = [], set()
            with urllib.request.urlopen(req, timeout=60) as resp:
                buf = b""
                while len(out) < top:
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
                        if len(w) > 24 or w in seen or not pat.match(w):
                            continue
                        seen.add(w)
                        out.append(f"{w} {line[sp + 1:]}")
                        if len(out) >= top:
                            break
            if len(out) >= min(top, 20000) or (out and "50k" in url):
                OUT_DIR.mkdir(parents=True, exist_ok=True)
                (OUT_DIR / f"{lang}.txt").write_text("\n".join(out) + "\n", encoding="utf-8")
                src = url.rsplit("/", 1)[-1]
                return f"{lang}: {len(out):>7} words  <- {src}"
        except Exception as e:
            last = f"{type(e).__name__}"
            continue
    return f"{lang}: FAILED"


if __name__ == "__main__":
    langs = sys.argv[1:] or list(PATTERNS.keys())
    for lg in langs:
        print(fetch(lg, TOP.get(lg, MID)), flush=True)
