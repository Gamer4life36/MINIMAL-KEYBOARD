"""Turn AOSP LatinIME's en_US wordlist (Apache 2.0) into the keyboard's compact assets.

Usage: python -I tools/build_dict.py <en_US_wordlist.combined.gz> <assets dir>
Source: android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/dictionaries/

words.txt     one line per lowercase key, sorted: key<TAB>freq[<TAB>Canonical]
              freq 0 = a real word that must never be offered as a correction
              (offensive / non-words); Canonical only when it differs from key.
shortcuts.txt typed<TAB>replacement (the list's own whitelist, e.g. im -> I'm)
"""
import gzip
import os
import re
import sys

WORD_RE = re.compile(r"^[A-Za-z][A-Za-z']*$")

# Everyday chat words the list lacks; added so they are never "corrected".
EXTRA_WORDS = {
    "lol": 90, "lmao": 60, "omg": 80, "idk": 80, "tbh": 60, "thx": 60, "nah": 70,
    "hmm": 70, "brb": 50, "ty": 60, "ya": 70, "yall": 50, "y'all": 60, "bruh": 50,
    "lmk": 60, "smh": 50, "ikr": 50, "gonna": 100, "wanna": 90, "gotta": 90,
    "kinda": 80, "sorta": 60, "dunno": 60, "lemme": 60, "gimme": 60, "ok": 150,
}


def main(src: str, out_dir: str) -> None:
    best = {}          # key -> (freq, canonical, has_lowercase_form)
    shortcuts = {}
    last_word = None

    with gzip.open(src, "rt", encoding="utf-8") as f:
        for line in f:
            if line.startswith(" word="):
                fields = dict(p.split("=", 1) for p in line.strip().split(",") if "=" in p)
                word = fields["word"]
                last_word = word
                if not WORD_RE.match(word):
                    continue
                flags = fields.get("flags", "")
                not_word = fields.get("not_a_word") == "true" or "nonword" in flags
                freq = int(fields.get("f", "0"))
                if "offensive" in flags or not_word:
                    freq = 0
                if not_word:
                    continue  # placeholder entries that only carry a shortcut
                key = word.lower()
                is_lower = word == key
                old = best.get(key)
                if old is None:
                    best[key] = (freq, word, is_lower)
                else:
                    o_freq, o_word, o_lower = old
                    # Prefer the lowercase spelling as canonical when one exists
                    # (so "us" is not turned into "US"); otherwise the most common one.
                    if is_lower and not o_lower:
                        best[key] = (max(freq, o_freq), word, True)
                    elif o_lower and not is_lower:
                        best[key] = (max(freq, o_freq), o_word, True)
                    elif freq > o_freq:
                        best[key] = (freq, word, is_lower)
            elif line.startswith("  shortcut=") and last_word:
                fields = dict(p.split("=", 1) for p in line.strip().split(",") if "=" in p)
                if fields.get("f") == "whitelist" and WORD_RE.match(last_word):
                    shortcuts[last_word.lower()] = fields["shortcut"]

    for word, freq in EXTRA_WORDS.items():
        old = best.get(word)
        if old is None or not old[2]:
            # Lowercase form wins (so "ok" stays "ok", not "OK").
            best[word] = (max(freq, old[0] if old else 0), word, True)

    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, "words.txt"), "w", encoding="utf-8", newline="\n") as out:
        for key in sorted(best):
            freq, canonical, _ = best[key]
            out.write(f"{key}\t{freq}" + (f"\t{canonical}" if canonical != key else "") + "\n")
    with open(os.path.join(out_dir, "shortcuts.txt"), "w", encoding="utf-8", newline="\n") as out:
        for typed in sorted(shortcuts):
            out.write(f"{typed}\t{shortcuts[typed]}\n")
    print(f"{len(best)} words, {len(shortcuts)} shortcuts")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
