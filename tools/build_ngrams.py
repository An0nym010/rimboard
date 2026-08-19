#!/usr/bin/env python3
"""Add corpus-derived next-word predictions to the bundled models.

    python tools/build_predictions.py     # hand-written model first
    python tools/build_ngrams.py tr en    # then this, which merges into it

Why this exists
---------------
The bundled prediction models were written by hand: 2,235 lines of authored
word associations producing 1,746 rows across all 22 languages, of which
English had 224 and Hungarian 36. Every context feature in the engine -- the
preceding word, the following word, sentence openers -- reads that model, so a
well-built ranking layer was running on almost no fuel.

Source and licence
------------------
Tatoeba sentence exports (https://tatoeba.org), CC BY 2.0 FR. Chosen over the
alternatives deliberately: OPUS OpenSubtitles, which is where the dictionaries
come from, is not reachable over TLS from every machine, and the Leipzig
corpora are CC BY-NC, which an app that anyone may redistribute cannot use.
Tatoeba is also the right register -- short everyday sentences rather than news
or encyclopedia prose.

Attribution belongs in NOTICE. CC BY does not impose share-alike, so unlike the
dictionaries these files do not carry a licence onward.

The Tom problem
---------------
Tatoeba is a language-teaching corpus and its sentences are overwhelmingly
about Tom and Mary. "tom" is the single most frequent token in the Turkish
export -- more common than "bir" -- and taken raw it would become the top
prediction after almost every word.

Rather than a hand-written blocklist of names, which would need one per
language and would miss whatever the next corpus over-represents, this compares
each token's share of the corpus against its share of the shipped frequency
dictionary. That dictionary comes from subtitles and has no such bias, so the
ratio between the two is an artifact detector that needs no list:

    tom 262x    mary 39x    john 1.0x    bir 1.1x    merhaba 0.1x

Ordinary words sit between 0.1 and about 2. Anything above OUTLIER is corpus
noise, whatever it happens to be called.
"""
import bz2
import collections
import os
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
CACHE = os.path.join(ROOT, "build", "corpus")
START = "\u0001"

# Tatoeba uses ISO 639-3; the app uses 639-1.
ISO3 = {
    "en": "eng", "tr": "tur", "de": "deu", "es": "spa", "fr": "fra", "it": "ita",
    "pt": "por", "ru": "rus", "nl": "nld", "pl": "pol", "sv": "swe", "id": "ind",
    "ro": "ron", "cs": "ces", "da": "dan", "no": "nor", "fi": "fin", "hu": "hun",
    "uk": "ukr", "el": "ell", "hr": "hrv", "sk": "slk",
}

# How far a token may be over-represented in the corpus before it is treated as
# an artifact of that corpus rather than a fact about the language.
OUTLIER = 8.0
# Continuations kept per context word, and context words kept per language.
PER_CONTEXT = 6
MAX_ROWS = 6000
# A pair seen fewer times than this is not evidence of anything.
MIN_PAIR = 3

STRIP = ".,!?;:\"'()[]{}\u00ab\u00bb\u2018\u2019\u201c\u201d\u2026-\u2014"


def fetch(code3):
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, code3 + "_sentences.tsv.bz2")
    if os.path.exists(path):
        return path
    url = ("https://downloads.tatoeba.org/exports/per_language/%s/%s_sentences.tsv.bz2"
           % (code3, code3))
    print("  downloading %s" % url)
    req = urllib.request.Request(url, headers={"User-Agent": "curl/8"})
    with urllib.request.urlopen(req, timeout=180) as r, open(path, "wb") as f:
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)
    return path


def dictionary(lang):
    """word -> frequency, from the list the app already ships."""
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
                yield parts[2]


def build(lang):
    code3 = ISO3.get(lang)
    if not code3:
        print("  no Tatoeba code for %s" % lang)
        return
    path = fetch(code3)
    freq = dictionary(lang)
    total_dict = sum(freq.values())

    unigram = collections.Counter()
    bigram = collections.defaultdict(collections.Counter)
    opener = collections.Counter()
    total_tokens = 0
    for s in sentences(path):
        words = [w.strip(STRIP).lower() for w in s.split()]
        words = [w for w in words if w and w.isalpha()]
        if not words:
            continue
        opener[words[0]] += 1
        for w in words:
            unigram[w] += 1
            total_tokens += 1
        for a, b in zip(words, words[1:]):
            bigram[a][b] += 1

    def ordinary(w):
        """Not a corpus artifact, and a word the app already knows."""
        d = freq.get(w, 0)
        if d == 0:
            return False
        share_c = unigram[w] / total_tokens
        share_d = d / total_dict
        return share_c / share_d <= OUTLIER

    rows = {}
    ranked = sorted(bigram.items(), key=lambda kv: -unigram[kv[0]])
    for prev, nexts in ranked:
        if len(rows) >= MAX_ROWS:
            break
        if not ordinary(prev):
            continue
        keep = [w for w, n in nexts.most_common(PER_CONTEXT * 4)
                if n >= MIN_PAIR and ordinary(w)][:PER_CONTEXT]
        if keep:
            rows[prev] = keep
    starts = [w for w, n in opener.most_common(PER_CONTEXT * 8)
              if n >= MIN_PAIR and ordinary(w)][:20]

    merge(lang, rows, starts)


def merge(lang, rows, starts):
    """Hand-written entries keep their place; corpus entries fill in behind."""
    p = os.path.join(ASSETS, "predictions", lang + ".txt")
    existing = {}
    order = []
    with open(p, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if "\t" not in line:
                continue
            k, v = line.split("\t", 1)
            existing[k] = v.split()
            order.append(k)

    added_rows = 0
    added_words = 0
    if starts:
        cur = existing.get(START, [])
        merged = cur + [w for w in starts if w not in cur]
        added_words += len(merged) - len(cur)
        existing[START] = merged
        if START not in order:
            order.append(START)
    for k, v in rows.items():
        cur = existing.get(k)
        if cur is None:
            existing[k] = v
            order.append(k)
            added_rows += 1
            added_words += len(v)
        else:
            merged = cur + [w for w in v if w not in cur]
            added_words += len(merged) - len(cur)
            existing[k] = merged

    with open(p, "w", encoding="utf-8", newline="\n") as f:
        for k in order:
            f.write(k + "\t" + " ".join(existing[k]) + "\n")
    print("  %s: +%d rows, +%d continuations, %d rows total"
          % (lang, added_rows, added_words, len(order)))


if __name__ == "__main__":
    langs = sys.argv[1:] or ["tr", "en"]
    for lang in langs:
        print(lang)
        build(lang)
