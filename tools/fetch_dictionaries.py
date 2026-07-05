#!/usr/bin/env python3
"""Regenerate the bundled word-frequency dictionaries.

Downloads frequency lists from Hermit Dave's FrequencyWords project
(OpenSubtitles 2018 corpus, CC BY-SA 4.0), filters them to alphabetic
words for each language, and writes the top N entries to
app/src/main/assets/dictionaries/<lang>.txt in "word count" format.

Usage:
    python3 tools/fetch_dictionaries.py            # en + tr, top 10000
    python3 tools/fetch_dictionaries.py --top 20000
    python3 tools/fetch_dictionaries.py --langs en tr de

To add a new language you must also add a matching regex below and a
layout/locale in the app (model/Layouts.kt, res/xml/method.xml).
"""

import argparse
import re
import urllib.request
from pathlib import Path

BASE = ("https://raw.githubusercontent.com/hermitdave/FrequencyWords/"
        "master/content/2018/{lang}/{lang}_50k.txt")

# Allowed characters per language (lowercase forms as they appear in the lists)
PATTERNS = {
    "en": re.compile(r"^[a-z']+$"),
    "tr": re.compile(r"^[a-zçğıöşü]+$"),
    "de": re.compile(r"^[a-zäöüß]+$"),
    "es": re.compile(r"^[a-záéíóúüñ]+$"),
    "fr": re.compile(r"^[a-zàâçéèêëîïôùûüÿœæ']+$"),
    "it": re.compile(r"^[a-zàèéìíîòóùú']+$"),
    "pt": re.compile(r"^[a-záâãàçéêíóôõúü]+$"),
    "ru": re.compile(r"^[а-яё]+$"),
}

OUT_DIR = Path(__file__).resolve().parent.parent / "app/src/main/assets/dictionaries"


def fetch(lang: str, top: int) -> None:
    if lang not in PATTERNS:
        raise SystemExit(f"No character pattern defined for '{lang}'; "
                         f"add one to PATTERNS in this script.")
    url = BASE.format(lang=lang)
    print(f"[{lang}] downloading {url}")
    with urllib.request.urlopen(url, timeout=60) as resp:
        text = resp.read().decode("utf-8", errors="replace")

    pat = PATTERNS[lang]
    kept = []
    for line in text.splitlines():
        parts = line.strip().split(" ")
        if len(parts) != 2:
            continue
        word, count = parts
        if not count.isdigit():
            continue
        if len(word) < 1 or len(word) > 24:
            continue
        if not pat.match(word):
            continue
        kept.append((word, int(count)))
        if len(kept) >= top:
            break

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    out = OUT_DIR / f"{lang}.txt"
    with out.open("w", encoding="utf-8") as f:
        for word, count in kept:
            f.write(f"{word} {count}\n")
    print(f"[{lang}] wrote {len(kept)} words -> {out}")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--langs", nargs="+",
                default=["en", "tr", "de", "es", "fr", "it", "pt", "ru"])
    ap.add_argument("--top", type=int, default=10000)
    args = ap.parse_args()
    for lang in args.langs:
        fetch(lang, args.top)


if __name__ == "__main__":
    main()
