#!/usr/bin/env python3
"""Rebuild the English context asset from the three pinned upstream files.

The arguments are, in order, the Leipzig-derived TSV, Google Books fiction
bigram CSV and Google Books fiction trigram CSV. See CONTEXT-NOTICE.md for
download URLs, exact hashes, source licenses and the intentional limitations.
"""

import csv
import hashlib
import re
import sys
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
OUTPUT = ROOT / "app/src/main/assets/swipe/en_context.tsv"
EXPECTED = (
    "26ddb1797a08c653ecce720e57c9e8e9fa72c7400989885496e05b16ba1a588b",
    "49ed6f0f8c3240f333226d4a8b0659c386220e98845df57ddea0d638a2fbc813",
    "6f285b78654482f02bc035081bad15158175e6b3a8fca2d85feb28603c3d4bda",
)
WORD = re.compile(r"[a-z]+(?:'[a-z]+)*\Z")
BOOK_SCALE = 10000


def verify(path, expected):
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual != expected:
        raise ValueError(f"Wrong upstream file {path}: SHA-256 is {actual}, expected {expected}")


def valid(words):
    return all(len(word) <= 24 and WORD.fullmatch(word) for word in words)


def main():
    if len(sys.argv) != 4:
        raise SystemExit("Usage: build_english_context.py LEIPZIG.tsv BOOKS2.csv BOOKS3.csv")
    source_paths = [Path(name) for name in sys.argv[1:]]
    for path, expected in zip(source_paths, EXPECTED):
        verify(path, expected)

    bigrams = Counter()
    with source_paths[0].open(encoding="utf-8", newline="") as source:
        for row in csv.reader(source, delimiter="\t"):
            if len(row) != 3:
                continue
            words = tuple(part.lower() for part in row[:2])
            if valid(words):
                bigrams[words] += int(row[2])

    # The Leipzig subset excludes every one-character token. The books list
    # fills this gap only when the preceding word is one character, so counts
    # compared for a given previous word come from the same source.
    with source_paths[1].open(encoding="utf-8", newline="") as source:
        for row in csv.DictReader(source):
            words = tuple(row["ngram"].lower().split())
            if len(words) == 2 and len(words[0]) == 1 and valid(words):
                count = int(row["freq"]) // BOOK_SCALE
                if count:
                    bigrams[words] += count

    trigrams = Counter()
    with source_paths[2].open(encoding="utf-8", newline="") as source:
        for row in csv.DictReader(source):
            words = tuple(row["ngram"].lower().split())
            if len(words) == 3 and valid(words):
                count = int(row["freq"]) // BOOK_SCALE
                if count:
                    trigrams[words] += count

    with OUTPUT.open("w", encoding="utf-8", newline="\n") as out:
        out.write("# English context counts. See CONTEXT-NOTICE.md.\n")
        for words, count in sorted(bigrams.items()):
            out.write("\t".join((*words, str(count))) + "\n")
        for words, count in sorted(trigrams.items()):
            out.write("\t".join((*words, str(count))) + "\n")
    print(f"Wrote {len(bigrams)} bigrams and {len(trigrams)} trigrams to {OUTPUT}")


if __name__ == "__main__":
    main()
