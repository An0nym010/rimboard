#!/usr/bin/env python3
"""Generate the bundled next-word (starter) prediction models.

Writes app/src/main/assets/predictions/<lang>.txt, one line per context word:

    prev<TAB>next1 next2 next3 ...

The user's own learned bigrams always take priority at runtime; this model only
fills the suggestion strip before the keyboard has learned anything, so typing
feels responsive from the very first word (Gboard-style cold start).

Provenance: these continuations are common, non-creative word associations
written by hand for RimBoard. They are not extracted from any external corpus,
so there are no licensing constraints beyond RimBoard's own MIT license.

Run:  python3 tools/build_predictions.py
"""
import os

# The engine keys sentence-start predictions under this control character;
# it must match UserData.START exactly.
START = "\u0001"

OUT_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "assets", "predictions",
)

EN = {
    # Sentence openers; see START above.
    START: ["i", "the", "it", "we", "you", "thanks", "hi", "hey", "just",
            "ok", "yes", "no", "sorry", "what", "how", "can", "do", "let",
            "good", "please"],
    "i": ["am", "have", "will", "think", "was", "know", "need", "want",
          "really", "just", "love", "hope", "feel", "had", "would", "can't", "don't"],
    "i'm": ["not", "going", "so", "just", "sure", "still", "here", "sorry",
            "glad", "happy", "gonna", "trying"],
    "i'll": ["be", "get", "take", "do", "call", "see", "have", "try",
             "let", "send", "come", "check", "tell"],
    "i've": ["been", "got", "never", "always", "seen", "had", "done"],
    "i'd": ["like", "love", "be", "rather", "say"],
    "you": ["are", "can", "have", "will", "know", "should", "want", "need",
            "don't", "can't", "were", "get", "look", "think"],
    "you're": ["welcome", "right", "not", "so", "going", "the", "gonna"],
    "we": ["are", "have", "can", "will", "need", "should", "were", "don't",
           "can't", "could", "got"],
    "we're": ["going", "not", "here", "all", "still", "gonna"],
    "we'll": ["be", "see", "have", "get", "talk", "take"],
    "they": ["are", "have", "will", "can", "were", "don't", "want", "said",
             "could", "might", "need"],
    "they're": ["going", "not", "all", "still", "the"],
    "he": ["is", "was", "has", "will", "said", "can", "had", "would",
           "does", "didn't", "wants"],
    "she": ["is", "was", "has", "will", "said", "can", "had", "would",
            "does", "didn't", "wants"],
    "it": ["is", "was", "will", "has", "would", "can", "could", "doesn't",
           "might", "looks", "seems", "means"],
    "it's": ["a", "not", "the", "so", "just", "going", "all", "really",
             "been", "ok", "fine", "time", "better"],
    "the": ["best", "first", "same", "next", "last", "only", "other", "most",
            "way", "whole", "right", "new"],
    "a": ["lot", "little", "few", "good", "great", "bit", "new", "while",
          "couple", "big", "better", "chance"],
    "to": ["be", "do", "get", "go", "see", "make", "know", "have", "the",
           "take", "work", "help", "find", "come", "meet"],
    "of": ["the", "course", "them", "us", "all", "it", "my", "your", "this",
           "that", "a"],
    "in": ["the", "a", "my", "this", "that", "order", "fact", "time",
           "front", "case", "there", "general"],
    "is": ["a", "the", "that", "not", "it", "this", "there", "so", "going",
           "good", "really", "still"],
    "are": ["you", "we", "they", "not", "going", "the", "still", "so",
            "there", "all"],
    "was": ["a", "the", "not", "so", "just", "going", "that", "there",
            "really", "still", "like"],
    "will": ["be", "you", "not", "have", "do", "get", "make", "come", "take",
             "go", "help", "never"],
    "would": ["be", "you", "like", "have", "love", "never", "not", "want",
              "rather"],
    "can": ["you", "i", "we", "be", "help", "do", "get", "make", "see",
            "come", "go", "have", "still"],
    "could": ["be", "you", "i", "we", "have", "not", "see", "help", "do"],
    "have": ["a", "to", "you", "been", "the", "some", "no", "any", "that",
             "it", "my"],
    "has": ["been", "a", "to", "the", "not", "no"],
    "had": ["a", "to", "been", "the", "no", "some"],
    "do": ["you", "not", "it", "that", "the", "this", "i", "we", "they", "so"],
    "does": ["not", "it", "that", "this", "anyone", "he", "she"],
    "did": ["you", "not", "it", "that", "he", "she", "they", "we", "i"],
    "how": ["are", "to", "do", "much", "many", "about", "is", "was", "can",
            "come", "long", "old"],
    "what": ["is", "are", "do", "you", "the", "a", "about", "happened", "i",
             "we", "time", "kind", "if"],
    "when": ["you", "i", "the", "we", "they", "it", "are", "did", "is",
             "he", "she"],
    "where": ["are", "is", "you", "the", "did", "do", "we", "i", "they"],
    "why": ["do", "not", "you", "is", "are", "did", "would", "i", "we"],
    "who": ["is", "are", "the", "you", "was", "do", "would"],
    "this": ["is", "was", "one", "will", "would", "morning", "week", "time",
             "year", "weekend", "place"],
    "that": ["is", "was", "would", "i", "you", "the", "one", "way", "it",
             "we", "they", "means"],
    "my": ["friend", "name", "mom", "dad", "house", "phone", "life", "love",
           "dear", "own", "brother", "sister", "family"],
    "your": ["name", "phone", "help", "house", "mom", "dad", "own", "life",
             "family", "friend", "order"],
    "his": ["name", "own", "face", "mother", "father", "life", "house"],
    "her": ["name", "own", "face", "mother", "father", "life", "house"],
    "our": ["own", "house", "family", "team", "new", "best", "first"],
    "thank": ["you"],
    "thanks": ["for", "a", "so", "again"],
    "please": ["let", "don't", "help", "do", "send", "call", "come", "be"],
    "let": ["me", "us", "it", "them", "him", "her"],
    "don't": ["know", "want", "think", "have", "worry", "be", "get",
              "understand", "like", "care", "forget"],
    "can't": ["wait", "believe", "do", "find", "see", "get", "help",
              "stop", "tell"],
    "didn't": ["know", "want", "think", "mean", "see", "do", "have"],
    "won't": ["be", "do", "work", "take", "let", "happen"],
    "there": ["is", "are", "was", "were", "will", "isn't", "has"],
    "here": ["is", "are", "we", "i", "it", "you"],
    "going": ["to", "on", "back", "out", "home", "up", "down"],
    "want": ["to", "a", "you", "the", "some", "it", "that"],
    "need": ["to", "a", "you", "some", "help", "the", "more", "it"],
    "get": ["a", "the", "some", "to", "it", "you", "out", "back", "up", "ready"],
    "got": ["a", "to", "the", "it", "some", "no", "you"],
    "see": ["you", "the", "if", "what", "a", "it", "how", "them"],
    "know": ["what", "how", "that", "the", "if", "you", "about", "why", "where"],
    "think": ["i", "you", "it", "we", "that", "so", "about", "the"],
    "feel": ["like", "free", "so", "better", "good", "the"],
    "make": ["sure", "it", "a", "the", "you", "me", "some"],
    "take": ["a", "care", "the", "it", "your", "some", "me", "this"],
    "come": ["on", "back", "to", "over", "here", "in", "with"],
    "go": ["to", "back", "on", "home", "out", "get", "ahead", "with"],
    "look": ["at", "like", "for", "good", "out", "up"],
    "good": ["morning", "luck", "night", "to", "for", "idea", "thing", "job", "one"],
    "so": ["much", "i", "that", "you", "we", "it", "far", "many", "good", "happy"],
    "just": ["a", "wanted", "want", "to", "got", "the", "like", "be", "saw"],
    "really": ["good", "want", "like", "need", "nice", "appreciate", "hope"],
    "very": ["good", "much", "nice", "well", "happy", "important"],
    "not": ["a", "sure", "the", "going", "to", "that", "really", "yet", "so"],
    "no": ["problem", "one", "more", "i", "worries", "way", "thanks"],
    "yes": ["i", "please", "it", "you", "of", "that"],
    "ok": ["i", "so", "thanks", "let", "then", "good"],
    "okay": ["so", "i", "let", "then", "thanks"],
    "on": ["the", "my", "your", "it", "a", "this", "that", "sunday", "monday"],
    "at": ["the", "all", "home", "work", "least", "a", "my", "your", "night"],
    "for": ["the", "you", "a", "me", "your", "us", "it", "that", "this", "sure"],
    "with": ["the", "you", "me", "a", "my", "your", "us", "it", "that"],
    "about": ["the", "it", "that", "you", "a", "this", "your", "my"],
    "sorry": ["for", "i", "to", "about", "but"],
    "love": ["you", "it", "the", "to", "this", "that"],
    "hope": ["you", "it", "this", "that", "so", "everything", "we"],
    "happy": ["birthday", "to", "new", "that"],
    "right": ["now", "here", "there", "away"],
    "well": ["i", "that", "done", "then", "you", "it"],
    "still": ["have", "not", "need", "here", "want", "working"],
    "now": ["i", "that", "we", "you", "it", "the"],
    "then": ["i", "we", "you", "the", "it", "again"],
}

TR = {
    # Sentence openers. START is the empty-context key the engine uses when
    # nothing has been typed yet; see UserData.START.
    START: ["ben", "bu", "bir", "ne", "çok", "evet", "hayır", "merhaba",
            "selam", "tamam", "şimdi", "bugün", "sen", "biraz", "hadi",
            "günaydın", "nasılsın", "teşekkürler", "peki", "yarın"],

    # pronouns and their common continuations
    "ben": ["de", "bir", "seni", "çok", "sana", "bunu", "şimdi", "onu", "böyle"],
    "sen": ["de", "bir", "ne", "çok", "misin", "bana", "nasılsın", "onu"],
    "biz": ["de", "bir", "onu", "şimdi", "bunu"],
    "o": ["da", "bir", "zaman", "kadar", "gün", "yüzden"],
    "bana": ["bir", "ne", "bunu", "söyle", "yaz", "haber", "yardım"],
    "sana": ["bir", "bunu", "söyledim", "geliyorum", "yazarım"],
    "onu": ["bir", "çok", "da", "ben", "sen"],

    # determiners and quantifiers
    "bu": ["bir", "çok", "ne", "kadar", "gün", "sabah", "akşam", "konuda",
           "yüzden", "arada"],
    "şu": ["an", "anda", "bir", "sıralar"],
    "bir": ["şey", "gün", "daha", "de", "tane", "sorun", "an", "dakika",
            "saat", "süre", "kez", "araya"],
    "her": ["şey", "zaman", "gün", "ikisi", "şeyi"],
    "hiç": ["bir", "sorun", "olmaz", "yoktu"],
    "biraz": ["daha", "sonra", "önce", "zaman", "bekle"],
    "çok": ["güzel", "iyi", "teşekkür", "fazla", "iyiyim", "güzeldi",
            "sevindim", "uzun", "az", "önemli"],
    "daha": ["sonra", "iyi", "çok", "önce", "fazla", "yeni", "az"],
    "en": ["iyi", "güzel", "çok", "az", "son"],

    # question words
    "ne": ["zaman", "kadar", "oldu", "yapıyorsun", "güzel", "haber", "olacak",
           "yapıyorsun", "demek", "olur"],
    "nasıl": ["bir", "oldu", "yani", "gidiyor", "olur"],
    "neden": ["böyle", "olmasın", "bilmiyorum", "acaba"],
    "kim": ["var", "geldi", "bilir", "o"],
    "nerede": ["olduğunu", "kaldın", "buluşalım"],
    "hangi": ["gün", "saat", "biri", "konuda"],
    "kaç": ["tane", "saat", "gün", "para", "kişi"],

    # greetings and politeness
    "merhaba": ["nasılsın", "ben", "iyi"],
    "selam": ["nasılsın", "ben", "naber"],
    "günaydın": ["nasılsın", "iyi", "sana"],
    "iyi": ["geceler", "günler", "akşamlar", "misin", "bir", "ki", "olur",
            "şanslar", "tatiller"],
    "teşekkür": ["ederim", "ederiz"],
    "teşekkürler": ["çok", "ben", "sana"],
    "rica": ["ederim", "etsem"],
    "kolay": ["gelsin"],
    "geçmiş": ["olsun"],
    "hoş": ["geldin", "geldiniz", "bulduk"],
    "görüşmek": ["üzere", "isterim"],

    # affirmation, negation, hedging
    "evet": ["ama", "tabii", "bir", "biliyorum", "haklısın", "olur"],
    "hayır": ["ama", "teşekkürler", "sorun", "gerek"],
    "tamam": ["o", "ben", "peki", "anladım", "olur"],
    "tabii": ["ki", "canım", "olur"],
    "belki": ["de", "bir", "sonra", "yarın"],
    "sanırım": ["bir", "öyle", "evet", "yarın"],
    "galiba": ["bir", "öyle", "yarın"],
    "aslında": ["bir", "çok", "ben", "öyle"],
    "yani": ["bir", "ne", "öyle", "sonuçta"],
    "ama": ["bir", "ben", "çok", "sen", "yine"],
    "çünkü": ["ben", "bir", "çok", "o"],
    "eğer": ["bir", "sen", "ben", "olursa"],

    # very common verbs and their frames
    "seni": ["seviyorum", "çok", "bir", "arayacağım", "özledim"],
    "seninle": ["konuşmak", "birlikte", "gurur"],
    "olur": ["mu", "musun", "muyuz", "diye"],
    "var": ["mı", "bir", "mıydı", "ya"],
    "yok": ["mu", "bir", "canım", "sorun"],
    "gerek": ["yok", "var", "mi"],
    "lazım": ["mı", "değil", "olan"],
    "istiyorum": ["ama", "seni", "bir", "çok"],
    "biliyorum": ["ama", "ben", "seni", "canım"],
    "bilmiyorum": ["ama", "ne", "belki", "henüz"],
    "yapabilir": ["misin", "miyiz", "miyim"],
    "gelebilir": ["misin", "miyiz", "misiniz"],

    # time and place
    "bugün": ["ne", "bir", "çok", "akşam", "biraz"],
    "yarın": ["görüşürüz", "bir", "sabah", "akşam", "ne"],
    "dün": ["akşam", "gece", "bir", "seni"],
    "şimdi": ["ne", "bir", "geliyorum", "değil", "olmaz"],
    "sonra": ["görüşürüz", "bir", "ne", "konuşuruz"],
    "önce": ["bir", "ben", "sen", "bunu"],
    "için": ["bir", "çok", "seni", "bunu", "gerekli"],
    "kadar": ["çok", "iyi", "güzel", "olur"],
    "gibi": ["bir", "görünüyor", "oldu", "duruyor"],
    "ile": ["birlikte", "ilgili", "beraber"],
}


def merge(model, extra):
    """Add continuations without discarding the ones already listed.

    `dict.update` would replace each entry outright, which silently shortened
    contexts that had been curated earlier — an addition that took things away.
    Existing continuations keep their position, because that position is the
    ranking the runtime uses; new ones are appended after them.
    """
    for prev, nexts in extra.items():
        current = model.get(prev, [])
        model[prev] = current + [w for w in nexts if w not in current]


# Second batch: question openings, scheduling, and the short replies that make
# up most of the messages anyone actually sends. Same provenance as above —
# common non-creative associations written by hand, no external corpus.
merge(EN, {
    "what": ["do", "are", "is", "time", "about", "happened", "you", "if",
             "the", "did", "we"],
    "when": ["you", "are", "is", "do", "did", "we", "can", "will"],
    "where": ["are", "is", "do", "did", "you", "we", "should"],
    "why": ["not", "is", "do", "did", "are", "would"],
    "how": ["are", "do", "is", "much", "many", "long", "about", "was", "did"],
    "who": ["is", "are", "was", "do", "else", "said"],
    "which": ["one", "is", "are", "of"],
    "how's": ["it", "the", "everything", "your"],
    "let": ["me", "us", "them", "him", "her"],
    "let's": ["go", "do", "talk", "meet", "get", "say", "try"],
    "thanks": ["for", "a", "so", "again", "very"],
    "thank": ["you", "them", "him", "her"],
    "sorry": ["for", "about", "i", "to", "if"],
    "please": ["let", "send", "call", "check", "do", "can"],
    "sure": ["thing", "i", "no", "let", "that"],
    "maybe": ["we", "i", "you", "next", "later", "tomorrow"],
    "just": ["a", "wanted", "got", "saw", "let", "checking", "the", "now"],
    "not": ["sure", "really", "yet", "a", "the", "going", "much"],
    "no": ["problem", "worries", "one", "idea", "it's", "need"],
    "yes": ["please", "i", "of", "that", "it"],
    "ok": ["thanks", "sounds", "i", "let", "great"],
    "okay": ["thanks", "sounds", "i", "let"],
    "see": ["you", "the", "if", "what", "how", "it"],
    "talk": ["to", "later", "soon", "about", "with"],
    "call": ["you", "me", "him", "her", "back", "them"],
    "send": ["me", "it", "you", "them", "over", "the"],
    "meet": ["you", "up", "at", "me", "tomorrow"],
    "going": ["to", "be", "on", "out", "back", "well"],
    "want": ["to", "me", "you", "a", "it"],
    "need": ["to", "a", "you", "it", "some", "help"],
    "have": ["a", "to", "you", "been", "the", "it", "any"],
    "had": ["a", "to", "been", "the", "no"],
    "get": ["a", "it", "the", "back", "to", "there", "some"],
    "got": ["it", "a", "the", "to", "back", "home"],
    "take": ["a", "care", "it", "the", "your", "some"],
    "make": ["sure", "it", "a", "the", "sense"],
    "look": ["at", "like", "for", "good", "into"],
    "sounds": ["good", "great", "like", "fine"],
    "at": ["the", "a", "least", "home", "work", "all", "night"],
    "on": ["the", "my", "it", "a", "your", "monday", "time"],
    "in": ["the", "a", "my", "about", "case", "fact", "time"],
    "for": ["the", "a", "you", "me", "now", "sure", "us"],
    "to": ["the", "be", "you", "me", "do", "get", "go", "have"],
    "of": ["the", "a", "them", "us", "course", "it"],
    "this": ["is", "week", "one", "morning", "afternoon", "time", "weekend"],
    "that": ["is", "was", "would", "sounds", "one", "the", "you"],
    "next": ["week", "time", "one", "month", "monday", "year"],
    "last": ["night", "week", "one", "time", "year"],
    "tomorrow": ["morning", "at", "if", "we", "afternoon"],
    "tonight": ["at", "if", "we", "or"],
    "today": ["at", "is", "i", "we", "or"],
    "morning": ["at", "or", "i", "we"],
    "really": ["good", "nice", "sorry", "want", "need", "like", "appreciate"],
    "pretty": ["good", "much", "sure", "nice", "busy"],
    "very": ["good", "much", "nice", "well", "happy"],
    "still": ["not", "here", "waiting", "working", "the"],
    "already": ["done", "sent", "did", "there", "have"],
    "almost": ["done", "there", "ready", "always"],
})

MODELS = {"en": EN, "tr": TR}


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    for lang, model in MODELS.items():
        path = os.path.join(OUT_DIR, lang + ".txt")
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            for prev in sorted(model):
                nexts = model[prev]
                f.write(prev + "\t" + " ".join(nexts) + "\n")
        print(f"wrote {path}  ({len(model)} context words)")


if __name__ == "__main__":
    main()
