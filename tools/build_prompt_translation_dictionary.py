#!/usr/bin/env python3
"""Build compact ECDICT SQLite from upstream CSV (MIT). Usage: SOURCE_CSV OUTPUT_DIR."""
from __future__ import annotations
import argparse
import csv
import gzip
import hashlib
import json
import re
import sqlite3
import tempfile
from contextlib import closing
from pathlib import Path

HAN = re.compile(r"[\u3400-\u9fff]")
WORD = re.compile(r"[a-z0-9][a-z0-9 '\-./+]*[a-z0-9.]|[a-z]")
PREFIX = re.compile(r"^(?:(?:[a-z]+\.|\[[^]]+\])\s*)+", re.I)

def normalize(word: str) -> str:
    return " ".join(word.lower().replace("_", " ").replace("’", "'").split())

def simplify(raw: str) -> str | None:
    meanings = []
    for line in raw.replace(r"\n", "\n").splitlines():
        line = PREFIX.sub("", line.strip())
        for part in re.split(r"[,，;；]", line):
            part = PREFIX.sub("", part.strip()).strip(" .")
            if HAN.search(part) and part not in meanings:
                meanings.append(part)
            if len(meanings) >= 2:
                break
        if len(meanings) >= 2:
            break
    return "；".join(meanings)[:64] if meanings else None

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    entries: dict[str, str] = {}
    variants: list[tuple[str, str]] = []
    with args.source.open(encoding="utf-8-sig", newline="") as source:
        for row in csv.DictReader(source):
            word = normalize(row["word"])
            if not WORD.fullmatch(word) or len(word) > 100:
                continue
            # Keep all standalone words, plus phrases with upstream usage/learning evidence.
            # The unfiltered file contains hundreds of thousands of specialist multiword names.
            if " " in word and not (
                any(int(row.get(field) or 0) > 0 for field in ("bnc", "frq", "collins", "oxford"))
                or row.get("tag", "").strip()
            ):
                continue
            meaning = simplify(row["translation"])
            if meaning:
                entries.setdefault(word, meaning)
            for exchange in row.get("exchange", "").split("/"):
                kind, _, forms = exchange.partition(":")
                if kind in {"p", "d", "i", "3", "r", "t", "s"}:
                    for form in forms.split(","):
                        form = normalize(form)
                        if WORD.fullmatch(form) and len(form) <= 100:
                            variants.append((form, word))
    for form, base in variants:
        if base in entries:
            entries.setdefault(form, entries[base])
    args.output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as temporary:
        database = Path(temporary) / "dictionary.sqlite"
        with closing(sqlite3.connect(database)) as connection:
            connection.execute("CREATE TABLE words (word TEXT PRIMARY KEY, meaning TEXT NOT NULL) WITHOUT ROWID")
            connection.executemany("INSERT INTO words VALUES (?, ?)", sorted(entries.items()))
            connection.commit()
            connection.execute("VACUUM")
        raw = database.read_bytes()
    packed = gzip.compress(raw, compresslevel=9, mtime=0)
    (args.output / "ecdict.sqlite.binz").write_bytes(packed)
    metadata = {
        "source": "https://github.com/skywind3000/ECDICT",
        "sourceFile": "ecdict.csv",
        "sourceSha256": hashlib.sha256(args.source.read_bytes()).hexdigest(),
        "license": "MIT", "entries": len(entries),
        "databaseBytes": len(raw), "compressedBytes": len(packed),
        "databaseSha256": hashlib.sha256(raw).hexdigest(), "format": 1,
    }
    (args.output / "metadata.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(metadata))
    for word in ("penis", "vagina", "genital", "backlit", "iridescent", "heterochromia", "pleated"):
        print(f"{word}: {entries.get(word, '[missing]')}")

if __name__ == "__main__":
    main()
