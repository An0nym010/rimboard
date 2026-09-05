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
The bundled *dictionaries* come from OPUS OpenSubtitles, so the *prose* here is
independent of them. The *selection* is not, and every figure this fixture has
ever produced was read as though it were.

`ordinary()` has two effects and is documented above for one of them. It
rejects a word over-represented against the frequency dictionary, which is the
Tom problem. It also returns False when `freq.get(w, 0) == 0` -- a word the
dictionary has never heard of cannot be tested for over-representation -- and
since a sentence is kept only if *every* word passes, any sentence containing
an out-of-dictionary word is dropped whole.

So the fixture is a sample in which the dictionary knows every word, and the
blind arm is conditioned on that. Measured 2026-09-05 against the same builder
with only that second effect removed, 600 sentences each way:

              out of dict   blind KSR      delta
    en           0.0%      40.22 -> 40.68  +0.46
    da           0.5%      41.60 -> 40.84  -0.77
    cs           0.6%      36.39 -> 35.95  -0.44
    tr           1.7%      37.41 -> 36.94  -0.47
    fi           3.1%      40.64 -> 37.47  -3.17

English has no out-of-dictionary words to lose, so its +0.46 is the sampling
noise between two draws and the scale everything else is read against. Four
languages sit inside it. **Finnish does not**: its figure is inflated by about
three points, and the reason is not the count but the length -- the words being
dropped average 12.5 characters against 5.8 for the rest, so 3% of the tokens
are about 6% of the keystrokes.

They are not corpus junk. They are `kaksikymmentäneljävuotias`,
`amerikanenglannista`, `hampurilaisissaan` -- ordinary Finnish compounds, and
Turkish's are the same shape. The filter removes exactly the words a keyboard
has the most to offer on, and removes eighty times more of them in Finnish
than in English.

The fixture is left as it is, and the figures above are the correction to
apply when reading it. Regenerating would move every recorded number in the
repository -- twenty-one of them by less than the noise -- to fix one, and the
finding underneath is not really about the benchmark: 3% of Finnish words are
absent from a 200,000-word dictionary and the strip can offer nothing for
them. That is a gap in the keyboard, not in the fixture. See
`Morphology.isAgglutinative`, which is `lang == "tr"`, and the counted
60-ending Finnish suffix inventory that generation does not consult.

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
