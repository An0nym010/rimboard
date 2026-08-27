#!/usr/bin/env python3
"""Add the inflected forms of listed words to assets/offensive/<lang>.txt.

    python tools/expand_offensive.py            # write
    python tools/expand_offensive.py --check    # report, change nothing

Why
---
`SuggestionEngine.isOffensive` is an exact membership test. It is careful about
case and about which locale folds the word, and it has no idea that a plural is
the same word. "Block offensive words" says *Never suggest or autocorrect to
profanity*, and the lists held the base forms only, so the keyboard went on
offering the inflections: 62 attested ones in English alone, 277 across the
twelve languages this tool covers.

Fixing it in the matcher was tried and rejected. The suffix inventories in
`assets/suffixes/` are derivational -- they contain "the", "you", "land",
"town" -- so peeling with them makes a listed word out of ordinary vocabulary,
including one word with a corpus frequency of 232,845. A filter that
over-blocks common words is worse than one that under-blocks rare ones.

So this stays data, and every addition has to earn its place three times:

  1. it is a listed word plus one suffix from a small closed set for that
     language -- inflection, not derivation and not compounding;
  2. the shipped dictionary actually contains it, so nothing is invented;
  3. it is **no more frequent than the word it derives from**. A real
     inflection is rarer than its base; something commoner is a different word
     that merely looks derived, and this is what keeps "cocker" and "titer" out
     of the list. It drops 10 of the 277 candidates.

Languages absent from SUFFIXES are left alone rather than guessed at.
"""
import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")

# Inflectional endings only. Short and closed on purpose: every entry here is
# a grammatical ending, never a derivational one, because a derivation makes a
# different word and this list is about the same word in another form.
SUFFIXES = {
    "en": ["s", "es", "ed", "ing", "er", "ers", "y", "ies"],
    "de": ["e", "en", "er", "es", "n", "s"],
    "es": ["s", "es", "a", "as", "os"],
    "fr": ["s", "es", "e"],
    "it": ["i", "e", "a", "o"],
    "nl": ["en", "s", "e"],
    "pt": ["s", "es", "a", "as", "os"],
    "sv": ["ar", "er", "en", "et", "s"],
    "da": ["er", "en", "et", "e", "s"],
    "no": ["er", "en", "et", "e", "s"],
    "pl": ["y", "i", "a", "e", "ow"],
    "tr": ["ler", "lar", "i", "u"],
}


def dictionary(lang):
    out = {}
    p = os.path.join(ASSETS, "dictionaries", lang + ".txt")
    with io.open(p, encoding="utf-8") as f:
        for line in f:
            parts = line.split()
            if len(parts) >= 2:
                try:
                    out[parts[0].lower()] = int(parts[1])
                except ValueError:
                    pass
    return out


def everyday(lang):
    """Words the bundled next-word model has an opinion about.

    Both halves of it: a context somebody typed, and a continuation the strip
    would offer. Either is evidence that ordinary messages use the word, which
    is what condition 4 is asking.
    """
    out = set()
    p = os.path.join(ASSETS, "predictions", lang + ".txt")
    with io.open(p, encoding="utf-8") as f:
        for line in f:
            if "\t" not in line:
                continue
            k, v = line.rstrip("\n").split("\t", 1)
            out.update(k.split())
            out.update(v.split())
    return out


def listed(lang):
    p = os.path.join(ASSETS, "offensive", lang + ".txt")
    with io.open(p, encoding="utf-8") as f:
        return [w.strip().lower() for w in f if w.strip()]


def main():
    check = "--check" in sys.argv
    total = 0
    for lang in sorted(SUFFIXES):
        words = listed(lang)
        have = set(words)
        freq = dictionary(lang)
        common = everyday(lang)
        # To a fixed point. An added form is itself a listed word, and its own
        # inflections are then attested in exactly the same way -- Turkish
        # stacks two endings on one stem often enough that a single pass left
        # seven forms behind, which is how the test found this.
        added = []
        frontier = list(words)
        while frontier:
            nxt = []
            for w in frontier:
                base = freq.get(w, 0)
                if not base:
                    # Not in the dictionary, so there is nothing to compare a
                    # candidate against and nothing the engine could offer.
                    continue
                if w in common:
                    # Ordinary messages use this word, so it carries a sense
                    # the list is not about, and its inflections belong to that
                    # sense rather than to this one. Condition 4.
                    continue
                for s in SUFFIXES[lang]:
                    form = w + s
                    if form in have or form in added:
                        continue
                    f = freq.get(form, 0)
                    if f and f <= base and form not in common:
                        added.append(form)
                        nxt.append(form)
            frontier = nxt
        total += len(added)
        print("%-3s %3d listed, +%d inflected" % (lang, len(words), len(added)))
        if added and not check:
            out = sorted(have | set(added))
            p = os.path.join(ASSETS, "offensive", lang + ".txt")
            with io.open(p, "w", encoding="utf-8", newline="\n") as f:
                f.write("\n".join(out) + "\n")
    print("%s %d forms" % ("would add" if check else "added", total))


if __name__ == "__main__":
    main()
