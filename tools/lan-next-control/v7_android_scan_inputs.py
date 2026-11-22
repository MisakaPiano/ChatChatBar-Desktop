"""Authenticate the exact delivered V7 APK for the synthetic scan check."""
from pathlib import Path
import hashlib
import os
import shutil
import sys
from zipfile import ZipFile

from desktop_control import REPOSITORY, api, original_stream, require


ARTIFACT = 14596387952
RUN = 44663981532
CONTROL = "107d9049a720bdbc370a58fd2941514235964822"
OUTER_BYTES = 221311422
OUTER_SHA = "1b126a8e56ea93e69220b7f57af321e79615b7903877e559f2c79ef8781708c8"
APK_BYTES = 109663831
APK_SHA = "ac8e1b84c6cc0327eb7c2dd5ccb4c2cab594298040939b68966d0379632ad7e0"


def verify(path, size, digest):
    require(path.stat().st_size == size, "DELIVERED_BYTES")
    with path.open("rb") as stream:
        require(hashlib.file_digest(stream, "sha256").hexdigest() == digest, "DELIVERED_SHA256")


def main():
    require(os.environ.get("GITHUB_ACTIONS") == "true"
            and os.environ.get("GITHUB_REPOSITORY") == REPOSITORY, "CI_ONLY")
    root = Path(os.environ["RUNNER_TEMP"]).resolve(strict=True) / "ccb-v7-scan"
    root.mkdir()
    artifact = api("artifacts/" + str(ARTIFACT))
    run = api("runs/" + str(RUN))
    require(artifact["id"] == ARTIFACT and artifact["workflow_run"]["id"] == RUN
            and artifact["name"] == "ccb-lan-delivered85-candidate86-v6-verified-apks"
            and not artifact["expired"] and artifact["size_in_bytes"] == OUTER_BYTES
            and artifact["digest"] == "sha256:" + OUTER_SHA, "EXACT_SIGNED_ARTIFACT")
    require(run["id"] == RUN and run["conclusion"] == "success"
            and run["head_sha"] == CONTROL, "EXACT_SIGNED_RUN")
    archive = root / "original.zip"
    with original_stream(ARTIFACT) as source, archive.open("xb") as output:
        shutil.copyfileobj(source, output, 1024 * 1024)
    verify(archive, OUTER_BYTES, OUTER_SHA)
    with ZipFile(archive) as original:
        require(set(original.namelist()) == {"base85.apk", "seed85.apk", "candidate86.apk", "verify86.apk"}
                and len(original.namelist()) == 4, "EXACT_SIGNED_MEMBERS")
        apk = root / "candidate86.apk"
        require(original.getinfo("candidate86.apk").file_size == APK_BYTES, "EXACT_CANDIDATE_MEMBER")
        with original.open("candidate86.apk") as source, apk.open("xb") as output:
            shutil.copyfileobj(source, output, 1024 * 1024)
    verify(apk, APK_BYTES, APK_SHA)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # In particular do not surface a signed artifact URL in a CI log.
        print(f"V7_SCAN_INPUT_REFUSED: {type(error).__name__}", file=sys.stderr)
        raise SystemExit(2) from None
