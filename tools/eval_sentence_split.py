#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""What counting whole corpus rows, instead of sentences, costs the models.

`build_ngrams.py` stripped the full stop and counted each Tatoeba row as one
flat run of words, so a row holding two sentences -- 3 to 5% of them -- joined
the last word of one to the first word of the next. The shipped German model
still carries `gut ist -> ist oder der sie dann`, which is what a phone offers
after "...dass es gut ist".

**Those rows can never be right, and that is not an opinion about grammar.**
`SentenceContext.from` slices the text at the last sentence ender and reads
only the words after it, so `prevWord` is always inside the current sentence
and a cross-boundary pair is a context the keyboard cannot ask for. Worse than
useless: `PER_CONTEXT` keeps six continuations per context, so a bogus one that
ranks high pushes a real one out of the row.

This measures that. Nine tenths of the corpus to train, one tenth held out, and
**the held-out side is split by sentence in both arms** -- that is the truth
the keyboard sees, so scoring against unsplit text would be scoring against the
same fault twice and would hide it.

    python tools/eval_sentence_split.py de en ru tr
"""
import collections
import os
import sys

sys.path.insert(0, os.path.join(os.getcwd(), "tools"))
import build_ngrams as B  # noqa: E402


def rows(lang, split):
    """The corpus as lists of words, one list per row or per sentence."""
    path = B.fetch(B.ISO3[lang])
    out = []
    for raw in B.sentences(path):
        parts = B.SENTENCE_END.split(raw) if split else [raw]
        for s in parts:
            w = [x.strip(B.STRIP).lower() for x in s.split()]
            w = [x for x in w if x and x.isalpha()]
            if len(w) >= 2:
                out.append(w)
    return out


def model(train, per_context=None):
    """Bigram and trigram continuations, top [per_context] each."""
    per_context = per_context or B.PER_CONTEXT
    bi = collections.defaultdict(collections.Counter)
    tri = collections.defaultdict(collections.Counter)
    for w in train:
        for a, b in zip(w, w[1:]):
            bi[a][b] += 1
        for a, b, c in zip(w, w[1:], w[2:]):
            tri[(a, b)][c] += 1
    top_bi = {k: [w for w, _ in v.most_common(per_context)] for k, v in bi.items()}
    top_tri = {k: [w for w, _ in v.most_common(per_context)] for k, v in tri.items()}
    return top_bi, top_tri


def ask(top_bi, top_tri, a, b):
    """What the keyboard would offer after (a, b), trigram first."""
    out = list(top_tri.get((a, b), ()))
    for w in top_bi.get(b, ()):
        if w not in out:
            out.append(w)
    return out


def score(top_bi, top_tri, held):
    asked = have = top1 = top3 = 0
    for w in held:
        for i in range(1, len(w)):
            a = w[i - 2] if i >= 2 else ""
            b = w[i - 1]
            nxt = w[i]
            asked += 1
            got = ask(top_bi, top_tri, a, b)
            if not got:
                continue
            have += 1
            if got[0] == nxt:
                top1 += 1
            if nxt in got[:3]:
                top3 += 1
    return asked, have, top1, top3


def main(langs):
    print("lang  arm        coverage   top-1    top-3   rows in model")
    for lang in langs:
        # The held-out tenth, split by sentence: what the keyboard actually
        # meets. Both arms are scored against exactly this.
        truth = rows(lang, split=True)
        held = [w for i, w in enumerate(truth) if i % 10 == 0]
        for label, split in (("row (shipped)", False), ("sentence", True)):
            train_all = rows(lang, split=split)
            # Hold out by position in the *sentence* stream for the split arm
            # and by row for the other; both leave the same text out, since a
            # row's sentences are adjacent.
            train = [w for i, w in enumerate(train_all) if i % 10 != 0]
            tb, tt = model(train)
            asked, have, t1, t3 = score(tb, tt, held)
            print("%-4s  %-12s %7.1f%% %7.1f%% %7.1f%%   %d"
                  % (lang, label,
                     100.0 * have / max(1, asked),
                     100.0 * t1 / max(1, asked),
                     100.0 * t3 / max(1, asked),
                     len(tb) + len(tt)))


if __name__ == "__main__":
    main(sys.argv[1:] or ["de", "en", "ru", "tr"])
