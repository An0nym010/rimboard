#!/usr/bin/env python3
"""Extract the real-prose fixture `StripAccuracyTest` measures against.

    python tools/build_prose_fixture.py            # every shipped language
    python tools/build_prose_fixture.py en tr      # just these

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

The Tom problem, again
----------------------
Tatoeba is overwhelmingly about Tom and Mary, and a corpus that is one third
proper noun measures how well the keyboard knows one name. build_ngrams.py
solves this without a per-language blocklist by comparing each token's share of
the corpus against its share of the shipped frequency dictionary, which comes
from subtitles and has no such bias; anything over-represented by more than
OUTLIER is an artifact of this corpus whatever it is called. The same detector
is reused here, and reusing it matters for a reason beyond tidiness: a fixture
filtered by a *different* rule than the model was built with would measure the
difference between the two rules.

An earlier version of this script rejected any sentence with an interior
capitalised word. That works for English and throws away nearly all of German,
which capitalises every noun.

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
import collections
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CORPUS = os.path.join(ROOT, "build", "corpus")
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
OUT_DIR = os.path.join(ROOT, "app", "src", "test", "fixtures")

# The app's code -> Tatoeba's. Kept identical to build_ngrams.ISO3.
ISO3 = {
    "en": "eng", "tr": "tur", "de": "deu", "es": "spa", "fr": "fra", "it": "ita",
    "pt": "por", "ru": "rus", "nl": "nld", "pl": "pol", "sv": "swe", "id": "ind",
    "ro": "ron", "cs": "ces", "da": "dan", "no": "nob", "fi": "fin", "hu": "hun",
    "uk": "ukr", "el": "ell", "hr": "hrv", "sk": "slk",
}

WANT = 200
OUTLIER = 8.0
STRIP = ".,!?;:\"'()[]{}«»‘’“”…-—"

# Ordinary prose: no digits, no quotation, ends like a sentence. Digits are out
# because the metric counts keystrokes for words, and a sentence whose content
# is "1975" measures the tokeniser rather than the strip.
SHAPE = re.compile(r"^[^\W\d_][^\d\"“”«»]*[.!?]$", re.UNICODE)


def dictionary(lang):
    out = {}
    p = os.path.join(ASSETS, "dictionaries", lang + ".txt")
    with open(p, encoding="utf-8", errors="replace") as f:
        for line in f:
            parts = line.split()
            if len(parts) >= 2:
                out[parts[0]] = int(parts[1])
    return out


def sentences(path):
    with bz2.open(path, "rt", encoding="utf-8", errors="replace") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 3:
                yield parts[2].strip()


def tokens(text):
    ws = [w.strip(STRIP).lower() for w in text.split()]
    return [w for w in ws if w and w.isalpha()]


def build(lang):
    code3 = ISO3.get(lang)
    src = os.path.join(CORPUS, "%s_sentences.tsv.bz2" % code3)
    if not os.path.exists(src):
        print("  %s: no corpus at %s -- run tools/build_ngrams.py to fetch" % (lang, src))
        return
    freq = dictionary(lang)
    total_dict = sum(freq.values())

    # One pass to learn what this corpus over-represents, one to select.
    unigram = collections.Counter()
    total = 0
    for text in sentences(src):
        for w in tokens(text):
            unigram[w] += 1
            total += 1
    if total == 0:
        print("  %s: empty corpus" % lang)
        return

    def ordinary(w):
        d = freq.get(w, 0)
        if d == 0:
            return False
        return (unigram[w] / total) / (d / total_dict) <= OUTLIER

    hits = []
    seen = 0
    for text in sentences(src):
        if not SHAPE.match(text):
            continue
        ws = tokens(text)
        if not 5 <= len(ws) <= 14:
            continue
        if not all(ordinary(w) for w in ws):
            continue
        seen += 1
        # Every 37th, a prime, so the stride cannot land in step with any batch
        # structure in the export.
        if seen % 37 == 0:
            hits.append(text)
            if len(hits) >= WANT:
                break
    out = os.path.join(OUT_DIR, "prose_%s.txt" % lang)
    with open(out, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(hits) + "\n")
    print("  %s: %d sentences (of %d qualifying)" % (lang, len(hits), seen))


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    langs = sys.argv[1:] or sorted(ISO3)
    for lang in langs:
        build(lang)


if __name__ == "__main__":
    main()
