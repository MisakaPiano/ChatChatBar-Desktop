#!/usr/bin/env python3
"""Cloud-only API35 dataSync proof using an unchanged, tracked source test."""
from __future__ import annotations

from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

import group_ui_control as control


PACKAGE = "com.example.chatbar.lansyncnext"
CLASS = "com.example.chatbar.lanSync.LanSyncGroupCloudForegroundTest"
METHOD = "actualDataSyncPromotionContinuesAfterHomeStopsAndRejectsTheNextBackgroundBatch"
TARGET = "app/app/src/androidTest/java/com/example/chatbar/lanSync/LanSyncGroupCloudForegroundTest.kt"
API = 35
INSTRUMENTATION_ARGUMENT = "lanSyncCloudForegroundFixture"
PROOF_SCOPE = (
    "actual API35 dataSync foreground service, HOME continuation and refusal of the next background batch; "
    "synthetic work only, not cloud account/queue delivery, OS timeout/quota exhaustion, Doze or process death"
)


def control_root() -> Path:
    return Path(__file__).resolve().parents[2]


def git_bytes(root: Path, *arguments: str) -> bytes:
    return subprocess.run(["git", "-C", str(root), *arguments], check=True,
                          capture_output=True, timeout=30).stdout


def committed_fixture(root: Path, source_sha: str) -> bytes:
    control.require(git_bytes(root, "cat-file", "-t", source_sha).strip() == b"commit",
                    "SOURCE_NOT_COMMIT")
    row = git_bytes(root, "ls-tree", "--full-name", source_sha, "--", TARGET)
    expected = rb"100644 blob [a-f0-9]{40}\t" + re.escape(TARGET.encode("ascii")) + rb"\n"
    control.require(re.fullmatch(expected, row) is not None, "TRACKED_SOURCE_TEST_REQUIRED")
    # Binary stdout is intentional: text decoding/strip would change the committed fixture identity.
    return git_bytes(root, "show", source_sha + ":" + TARGET)


def read_request(path: Path) -> dict:
    raw = path.read_bytes()
    value = json.loads(raw.decode("utf-8", errors="strict"), object_pairs_hook=control.unique_object)
    # Validate the full, fixed three-field schema before constructing any Git object expression.
    claimed = value.get("fixtureSha256") if isinstance(value, dict) else None
    request = control.request_bytes(raw, claimed)
    body = committed_fixture(control_root(), request["sourceSha"])
    return control.request_bytes(raw, hashlib.sha256(body).hexdigest())


def checked_target(source: Path) -> Path:
    # Reject aliases before resolve: base.safe_target resolves the root before checking descendants.
    raw_absolute = source if source.is_absolute() else Path.cwd() / source
    current = Path(raw_absolute.anchor)
    for part in raw_absolute.parts[1:]:
        current = current / part
        control.require(not current.is_symlink(), "SOURCE_ROOT_SYMLINK")
    absolute = Path(os.path.abspath(source))
    for ancestor in (absolute, *absolute.parents):
        control.require(not ancestor.is_symlink(), "SOURCE_ROOT_SYMLINK")
    control.require(source.resolve(strict=True) == absolute, "SOURCE_ROOT_NOT_CANONICAL")
    target = absolute
    for part in Path(TARGET).parts:
        target = target / part
        control.require(not target.is_symlink(), "SOURCE_TEST_SYMLINK")
    control.require(target.is_file(), "SOURCE_TEST_MISSING")
    control.require(target.resolve(strict=True).is_relative_to(absolute), "SOURCE_TEST_ESCAPES_ROOT")
    return target


def overlay_status(source: Path, request: dict) -> None:
    """The base hook name is retained, but this mode never creates an overlay."""
    target = checked_target(source)
    source = Path(os.path.abspath(source))
    control.require(control.git(source, "rev-parse", "HEAD") == request["sourceSha"],
                    "SOURCE_HEAD_CHANGED")
    control.require(control.git(source, "status", "--porcelain", "--untracked-files=all") == "",
                    "SOURCE_NOT_CLEAN")
    try:
        tracked = control.git(source, "ls-files", "--error-unmatch", "--", TARGET)
    except subprocess.CalledProcessError:
        raise control.ControlError("TRACKED_SOURCE_TEST_REQUIRED") from None
    control.require(tracked == TARGET, "TRACKED_SOURCE_TEST_REQUIRED")
    committed = committed_fixture(source, request["sourceSha"])
    control.require(hashlib.sha256(committed).hexdigest() == request["fixtureSha256"],
                    "COMMITTED_SOURCE_TEST_CHANGED")
    control.require(hashlib.sha256(target.read_bytes()).hexdigest() == request["fixtureSha256"],
                    "SOURCE_TEST_BODY_CHANGED")


def prepare(request_path: Path, source: Path, evidence: Path) -> None:
    control.cloud_environment(os.environ)
    request = read_request(request_path)
    overlay_status(source, request)
    evidence.mkdir(parents=True, exist_ok=False)
    control.write_json(evidence / "prepared.json", {
        "kind": "CCB_CLOUD_FOREGROUND_TRACKED_SOURCE",
        "sourceSha": request["sourceSha"], "controlSha": control.git(control_root(), "rev-parse", "HEAD"),
        "fixtureSha256": request["fixtureSha256"], "trackedTest": TARGET,
        "sourceMode": "tracked", "overlay": None, "productionDiff": "empty", "nativeExecuted": False,
        "class": CLASS, "method": METHOD, "package": PACKAGE, "api": API,
        "instrumentationArgument": INSTRUMENTATION_ARGUMENT, "previewCode": 83,
        "proofScope": PROOF_SCOPE,
    })
    with (evidence / "source-sha.txt").open("x", encoding="ascii", newline="\n") as output:
        output.write(request["sourceSha"] + "\n")


@contextmanager
def profile():
    # Only this trusted entry point chooses the selector, API and tracked-source policy.
    selected = {
        "PACKAGE": PACKAGE, "CLASS": CLASS, "METHOD": METHOD, "TARGET": TARGET, "API": API,
        "INSTRUMENTATION_ARGUMENT": INSTRUMENTATION_ARGUMENT, "PROOF_SCOPE": PROOF_SCOPE,
        "read_request": read_request, "prepare": prepare, "overlay_status": overlay_status,
    }
    previous = {key: getattr(control, key) for key in selected}
    try:
        for key, value in selected.items():
            setattr(control, key, value)
        yield
    finally:
        for key, value in previous.items():
            setattr(control, key, value)


def main(argv: list[str] | None = None) -> int:
    with profile():
        return control.main(argv)


if __name__ == "__main__":
    raise SystemExit(main())
