#!/usr/bin/env python3
"""Extract the real-prose fixture `StripAccuracyTest` measures against.

    python tools/build_prose_fixture.py

Writes app/src/test/fixtures/prose_<lang>.txt, one sentence per line.

Why a fixture and not the corpus
--------------------------------
build/corpus/ holds the full Tatoeba exports, but build/ is a Gradle output
directory: it is not committed, it is not on CI, and `clean` removes it. A
benchmark that silently measures nothing on every machine but this one is worse
than no benchmark. This lifts a fixed, deterministic slice into the repo so the
number is reproducible everywhere.

Selection is every Nth qualifying sentence rather than the first N, because the
head of a Tatoeba export is the oldest contributions and reads differently from
the body. Nothing is chosen by hand.

What this corpus can and cannot measure
---------------------------------------
The bundled *dictionaries* come from OPUS OpenSubtitles, so completing a word
from its prefix is measured here against an independent corpus.

The bundled *n-gram predictions* are counted from Tatoeba itself, so any
measurement that passes a preceding word is scoring the model on the data it
was built from. That is why StripAccuracyTest runs a context-blind arm and a
context arm separately, and reads the second as a ceiling.

Source and licence: Tatoeba sentence exports (https://tatoeba.org), CC BY 2.0
FR, already attributed in NOTICE for the n-grams built from the same export.
"""
import bz2
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CORPUS = os.path.join(ROOT, "build", "corpus")
OUT_DIR = os.path.join(ROOT, "app", "src", "test", "fixtures")

# Tatoeba's three-letter code -> the app's language code.
LANGS = {"eng": "en", "tur": "tr"}

WANT = 400

# A sentence worth measuring on: ordinary prose, not a fragment and not a list
# of proper nouns. Digits and quotation marks are excluded because the metric
# counts keystrokes for words, and a sentence whose content is "1975" measures
# the tokeniser rather than the strip.
OK = re.compile(r"^[^\W\d_][\w' ,.-]*[.!?]$", re.UNICODE)


def qualifies(text):
    if not OK.match(text):
        return False
    words = [w for w in re.split(r"[^\w']+", text, flags=re.UNICODE) if w]
    if not 5 <= len(words) <= 14:
        return False
    # "Tom" dominates Tatoeba (see build_ngrams.py's note on the same problem).
    # A corpus that is one third proper noun measures how well the keyboard
    # knows one name.
    return not any(w[:1].isupper() for w in words[1:])


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    for code, lang in LANGS.items():
        src = os.path.join(CORPUS, "%s_sentences.tsv.bz2" % code)
        if not os.path.exists(src):
            sys.exit("missing %s -- run tools/build_ngrams.py first to fetch it" % src)
        hits = []
        seen = 0
        with bz2.open(src, "rt", encoding="utf-8") as fh:
            for line in fh:
                parts = line.rstrip("\n").split("\t")
                if len(parts) < 3:
                    continue
                text = parts[2].strip()
                if not qualifies(text):
                    continue
                seen += 1
                # Every 37th, a prime, so the stride cannot land in step with
                # any batch structure in the export.
                if seen % 37 == 0:
                    hits.append(text)
                    if len(hits) >= WANT:
                        break
        out = os.path.join(OUT_DIR, "prose_%s.txt" % lang)
        with open(out, "w", encoding="utf-8", newline="\n") as fh:
            fh.write("\n".join(hits) + "\n")
        print("%s: %d sentences -> %s" % (lang, len(hits), out))


if __name__ == "__main__":
    main()
