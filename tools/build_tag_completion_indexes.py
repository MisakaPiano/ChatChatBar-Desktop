"""Build ranked character posting indexes from the two bundled SQLite dictionaries.

The source databases remain untouched. Runtime downloaded catalogs use the same
schema and gram encoding in RankedTagIndex.kt. No external packages required.
"""
from __future__ import annotations

import gzip
import hashlib
import json
import sqlite3
import tempfile
from contextlib import closing
from itertools import groupby
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/app/src/main/assets"
VERSION = 2


def grams(text: str) -> set[int]:
    raw = text.encode("utf-16-le", errors="surrogatepass")
    units = [int.from_bytes(raw[i:i + 2], "little") + 1 for i in range(0, len(raw), 2)]
    return set(units) | {(a << 17) | b for a, b in zip(units, units[1:])}


def build(kind: str, source: Path, version: str, destination: Path) -> None:
    output = sqlite3.connect(destination)
    output.executescript("""
        PRAGMA journal_mode=OFF;
        PRAGMA synchronous=OFF;
        PRAGMA temp_store=FILE;
        CREATE TABLE entries(rank INTEGER PRIMARY KEY, name TEXT NOT NULL,
            cn_name TEXT NOT NULL, post_count INTEGER NOT NULL, category INTEGER NOT NULL,
            a TEXT NOT NULL, b TEXT NOT NULL);
        CREATE TABLE postings(gram INTEGER NOT NULL, rank INTEGER NOT NULL,
            PRIMARY KEY(gram, rank)) WITHOUT ROWID;
        CREATE TABLE grams(gram INTEGER PRIMARY KEY, n INTEGER NOT NULL, ranks BLOB NOT NULL);
        CREATE TABLE metadata(version INTEGER NOT NULL, source TEXT NOT NULL);
    """)
    output.execute("INSERT INTO metadata VALUES (?, ?)", (VERSION, version))
    with closing(sqlite3.connect(f"file:{source.as_posix()}?mode=ro", uri=True)) as db:
        sql = (
            "SELECT name, coalesce(cn_name,''), coalesce(post_count,0), category, "
            "lower(name), replace(lower(coalesce(cn_name,'')), ' ', '') "
            "FROM tags ORDER BY post_count DESC, lower(name) ASC"
            if kind == "danbooru" else
            "SELECT word, meaning, 0, 0, lower(word), lower(meaning) FROM words ORDER BY word"
        )
        rank = 0
        for name, translation, count, category, a, b in db.execute(sql):
            if kind == "danbooru" and (
                category not in (0, 1, 3, 4, 5) or not name or
                not 1 <= len(name.strip()) <= 200 or
                any(c.isspace() or c == ',' or not 0x21 <= ord(c) <= 0x7e for c in name.strip())
            ):
                continue
            output.execute("INSERT INTO entries VALUES (?, ?, ?, ?, ?, ?, ?)",
                           (rank, name.strip() if kind == "danbooru" else name,
                            translation, max(0, count), category, a, b))
            output.executemany("INSERT INTO postings VALUES (?, ?)",
                               ((gram, rank) for gram in grams(a) | grams(b)))
            rank += 1
            if rank % 20000 == 0:
                output.commit()
                print(f"{kind}: {rank} entries", flush=True)
    for gram, rows in groupby(output.execute("SELECT gram, rank FROM postings ORDER BY gram, rank"), key=lambda row: row[0]):
        packed = bytearray()
        previous = 0
        count = 0
        for _, rank in rows:
            delta = rank - previous
            previous = rank
            while delta >= 128:
                packed.append((delta & 127) | 128)
                delta >>= 7
            packed.append(delta)
            count += 1
        output.execute("INSERT INTO grams VALUES (?, ?, ?)", (gram, count, packed))
    output.execute("DROP TABLE postings")
    output.execute("CREATE INDEX entries_name ON entries(a)")
    output.commit()
    output.execute("VACUUM")
    output.close()


def main() -> None:
    output = ASSETS / "tag_completion"
    output.mkdir(exist_ok=True)
    for kind, packed, metadata, key in (
        ("danbooru", "danbooru/tag.sqlite.bundle", "danbooru/catalog.json", "sourceSha"),
        ("dictionary", "prompt_dictionary/ecdict.sqlite.binz", "prompt_dictionary/metadata.json", "databaseSha256"),
    ):
        version = json.loads((ASSETS / metadata).read_text(encoding="utf-8-sig"))[key]
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "source.sqlite"
            source.write_bytes(gzip.decompress((ASSETS / packed).read_bytes()))
            index = Path(temporary) / "index.sqlite"
            build(kind, source, version, index)
            raw = index.read_bytes()
            compressed = gzip.compress(raw, compresslevel=9, mtime=0)
        # AAPT transparently expands .gz assets and strips their extension. Preserve
        # the gzip payload and exact AssetManager path with our existing .binz convention.
        (output / f"{kind}.sqlite.binz").write_bytes(compressed)
        (output / f"{kind}.json").write_text(json.dumps({
            "format": VERSION, "source": version, "bytes": len(raw),
            "sha256": hashlib.sha256(raw).hexdigest(),
        }, indent=2) + "\n", encoding="utf-8")
        print(f"{kind}: index {len(raw):,} bytes; bundled {len(compressed):,} bytes", flush=True)


if __name__ == "__main__":
    main()
