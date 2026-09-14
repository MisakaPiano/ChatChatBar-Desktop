"""Verify runtime index asset paths and payload integrity in a finished APK.

Checks packaging/encoding contracts only, not dictionary content or search results.
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
from zipfile import ZipFile


def verify(apk: str) -> None:
    with ZipFile(apk) as archive:
        for kind, source_path, source_key in (
            ("danbooru", "assets/danbooru/catalog.json", "sourceSha"),
            ("dictionary", "assets/prompt_dictionary/metadata.json", "databaseSha256"),
        ):
            prefix = f"assets/tag_completion/{kind}"
            manifest = json.loads(archive.read(f"{prefix}.json"))
            source = json.loads(archive.read(source_path))
            if manifest["source"] != source[source_key]:
                raise ValueError(f"{kind}: bundled index does not match bundled source")
            digest = hashlib.sha256()
            size = 0
            with archive.open(f"{prefix}.sqlite.binz") as asset:
                with gzip.GzipFile(fileobj=asset) as payload:
                    header = payload.read(16)
                    if header != b"SQLite format 3\x00":
                        raise ValueError(f"{kind}: decoded index is not SQLite")
                    digest.update(header)
                    size += len(header)
                    while chunk := payload.read(1024 * 1024):
                        digest.update(chunk)
                        size += len(chunk)
            if size != manifest["bytes"] or digest.hexdigest() != manifest["sha256"]:
                raise ValueError(f"{kind}: packaged index size/hash mismatch")
            print(f"{kind}: runtime path, gzip, SQLite header, source, size and SHA-256 verified")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk")
    verify(parser.parse_args().apk)
