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

OUT_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "assets", "predictions",
)

EN = {
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
    "ben": ["de", "bir", "seni", "çok", "sana", "bunu"],
    "sen": ["de", "bir", "ne", "çok", "misin", "bana"],
    "bu": ["bir", "çok", "ne", "kadar", "gün", "sabah"],
    "çok": ["güzel", "iyi", "teşekkür", "fazla", "iyiyim", "güzeldi"],
    "teşekkür": ["ederim", "ederiz"],
    "nasıl": ["bir", "oldu", "yani"],
    "ne": ["zaman", "kadar", "oldu", "yapıyorsun", "güzel", "haber", "olacak"],
    "bir": ["şey", "gün", "daha", "de", "tane", "sorun"],
    "için": ["bir", "çok", "seni", "bunu"],
    "iyi": ["geceler", "günler", "akşamlar", "misin", "bir", "ki"],
    "seni": ["seviyorum", "çok", "bir"],
    "bana": ["bir", "ne", "bunu", "söyle"],
    "daha": ["sonra", "iyi", "çok", "önce", "fazla"],
    "evet": ["ama", "tabii", "bir", "biliyorum"],
    "hayır": ["ama", "teşekkürler", "sorun"],
}

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
