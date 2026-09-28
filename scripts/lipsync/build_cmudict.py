#!/usr/bin/env python3
"""Build the compact CMUdict asset used by Masha's English lip-sync G2P.

Input : cmudict.dict from https://github.com/cmusphinx/cmudict (BSD-2-Clause,
        Copyright (C) 1993-2015 Carnegie Mellon University).
Output: app/src/main/assets/lipsync/cmudict.txt (+ CMUDICT_LICENSE.txt next to it).

Format (ASCII, one entry per line, sorted by the word's bytes so the app can
binary-search the raw bytes without building a map):

    <word> <codes>\n

<word> is lower-case as in cmudict (letters, apostrophes, dots, hyphens,
digits). Only the first pronunciation of each word is kept (the "(2)"
variants and "# comments" are dropped). <codes> has one character per
phoneme: chr(0x30 + index), where index is

    vowel:     VOWELS.index(p) * 3 + stress          (0..44)
    consonant: 45 + CONSONANTS.index(p)              (45..68)

The Kotlin decoder (CmuDict.kt) uses exactly the same two lists, in the same
order. Usage:

    python scripts/lipsync/build_cmudict.py path/to/cmudict.dict path/to/LICENSE
"""
import os
import sys

VOWELS = ["AA", "AE", "AH", "AO", "AW", "AY", "EH", "ER", "EY", "IH", "IY", "OW", "OY", "UH", "UW"]
CONSONANTS = ["B", "CH", "D", "DH", "F", "G", "HH", "JH", "K", "L", "M", "N", "NG", "P", "R",
              "S", "SH", "T", "TH", "V", "W", "Y", "Z", "ZH"]


def code(ph: str) -> str:
    if ph[-1].isdigit():
        return chr(0x30 + VOWELS.index(ph[:-1]) * 3 + int(ph[-1]))
    return chr(0x30 + 45 + CONSONANTS.index(ph))


def main() -> None:
    src = sys.argv[1]
    lic = sys.argv[2] if len(sys.argv) > 2 else None
    root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    out_dir = os.path.join(root, "app", "src", "main", "assets", "lipsync")
    os.makedirs(out_dir, exist_ok=True)
    entries = {}
    with open(src, encoding="ascii") as f:
        for line in f:
            line = line.split("#", 1)[0].strip()
            if not line:
                continue
            word, *phones = line.split()
            if "(" in word or not phones:
                continue
            if word in entries:
                continue
            entries[word] = "".join(code(p) for p in phones)
    words = sorted(entries, key=lambda w: w.encode("ascii"))
    with open(os.path.join(out_dir, "cmudict.txt"), "w", encoding="ascii", newline="\n") as f:
        for w in words:
            f.write(f"{w} {entries[w]}\n")
    if lic:
        with open(lic, encoding="utf-8") as fin, open(os.path.join(out_dir, "CMUDICT_LICENSE.txt"), "w", encoding="utf-8", newline="\n") as fout:
            fout.write("CMUdict (https://github.com/cmusphinx/cmudict), compacted for Elyndra by\n")
            fout.write("scripts/lipsync/build_cmudict.py (first pronunciation only, phonemes re-encoded).\n\n")
            fout.write(fin.read())
    print(f"{len(words)} entries -> {out_dir}")


if __name__ == "__main__":
    main()
