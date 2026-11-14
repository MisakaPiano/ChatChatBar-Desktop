#!/usr/bin/env python3
"""Cloud-only, fresh-emulator proof of the real Android group page.

The only application checkout overlay is one instrumentation fixture. Original
instrumentation output is retained; verification reuses instrumentation_report.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

from instrumentation_report import execution

PACKAGE = "com.example.chatbar.lansyncnext"
CLASS = "com.example.chatbar.lanSync.LanSyncGroupAppUiControlTest"
METHOD = "createSaveLeaveAndReopenUsesRealAppOwnedGroup"
TARGET = "app/app/src/androidTest/java/com/example/chatbar/lanSync/LanSyncGroupAppUiControlTest.kt"
FIXTURE = Path(__file__).resolve().parent / "fixtures/LanSyncGroupAppUiControlTest.kt"
API = 26
INSTRUMENTATION_ARGUMENT = "lanSyncGroupUiFixture"
PROOF_SCOPE = "real group page, lifecycle stop/recreate, app-owned local publication; not remote delivery or OS process death"


class ControlError(ValueError):
    pass


def require(condition: bool, code: str) -> None:
    if not condition:
        raise ControlError(code)


def unique_object(pairs: list[tuple[str, object]]) -> dict:
    result = {}
    for key, value in pairs:
        require(key not in result, "DUPLICATE_REQUEST_KEY")
        result[key] = value
    return result


def request_bytes(raw: bytes, fixture_sha: str) -> dict:
    value = json.loads(raw.decode("utf-8", errors="strict"), object_pairs_hook=unique_object)
    require(isinstance(value, dict) and set(value) == {"format", "sourceSha", "fixtureSha256"}, "REQUEST_FIELDS")
    require(type(value["format"]) is int and value["format"] == 1, "REQUEST_FORMAT")
    require(isinstance(value["sourceSha"], str) and re.fullmatch(r"[a-f0-9]{40}", value["sourceSha"]) is not None,
            "SOURCE_SHA_INVALID")
    require(isinstance(value["fixtureSha256"], str) and re.fullmatch(r"[a-f0-9]{64}", value["fixtureSha256"]) is not None,
            "FIXTURE_SHA_INVALID")
    require(value["fixtureSha256"] == fixture_sha, "FIXTURE_SHA_MISMATCH")
    return value


def read_request(path: Path) -> dict:
    require(not FIXTURE.is_symlink(), "FIXTURE_SYMLINK")
    return request_bytes(path.read_bytes(), hashlib.sha256(FIXTURE.read_bytes()).hexdigest())


def cloud_environment(environment: dict[str, str]) -> None:
    require(environment.get("GITHUB_ACTIONS") == "true" and environment.get("RUNNER_OS") == "Linux",
            "CLOUD_LINUX_ONLY")


def emulator_serial(text: str) -> str:
    rows = [row.strip() for row in text.splitlines() if row.strip()]
    require(bool(rows) and rows[0] == "List of devices attached", "ADB_INVENTORY_FORMAT")
    devices = [row.split() for row in rows[1:]]
    require(len(devices) == 1 and len(devices[0]) == 2 and devices[0][1] == "device" and
            re.fullmatch(r"emulator-[0-9]+", devices[0][0]) is not None, "SINGLE_EMULATOR_REQUIRED")
    return devices[0][0]


def instrumentation_runner(text: str) -> str:
    rows = re.findall(r"^instrumentation:(\S+) \(target=" + re.escape(PACKAGE) + r"\)\s*$", text, re.M)
    require(len(rows) == 1, "EXACT_TARGET_RUNNER_REQUIRED")
    require(rows[0] == PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner", "TEST_RUNNER_MISMATCH")
    return rows[0]


def command(argv: list[str], timeout: int = 60) -> str:
    result = subprocess.run(argv, capture_output=True, check=True, timeout=timeout)
    return result.stdout.decode("utf-8", errors="strict")


def write_json(path: Path, value: dict) -> None:
    with path.open("x", encoding="utf-8", newline="\n") as output:
        json.dump(value, output, indent=2, ensure_ascii=False)
        output.write("\n")
        output.flush()
        os.fsync(output.fileno())


def git(root: Path, *arguments: str) -> str:
    return command(["git", "-C", str(root), *arguments], 30).strip()


def safe_target(source: Path) -> Path:
    source = source.resolve(strict=True)
    current = source
    for part in Path(TARGET).parts:
        current = current / part
        require(not current.is_symlink(), "OVERLAY_SYMLINK")
    require(current.resolve(strict=False).is_relative_to(source), "OVERLAY_ESCAPES_SOURCE")
    return current


def overlay_status(source: Path, request: dict) -> None:
    require(git(source, "rev-parse", "HEAD") == request["sourceSha"], "SOURCE_HEAD_CHANGED")
    require(git(source, "status", "--porcelain", "--untracked-files=all") == "?? " + TARGET,
            "SOURCE_OVERLAY_NOT_EXCLUSIVE")
    require(hashlib.sha256(safe_target(source).read_bytes()).hexdigest() == request["fixtureSha256"],
            "OVERLAY_CHANGED")


def prepare(request_path: Path, source: Path, evidence: Path) -> None:
    cloud_environment(os.environ)
    request = read_request(request_path)
    require(git(source, "rev-parse", "HEAD") == request["sourceSha"], "SOURCE_HEAD_MISMATCH")
    require(git(source, "status", "--porcelain", "--untracked-files=all") == "", "SOURCE_NOT_CLEAN")
    target = safe_target(source)
    require(not target.exists(), "FIXTURE_TARGET_EXISTS")
    require(target.parent.is_dir(), "FIXTURE_TARGET_PARENT_MISSING")
    evidence.mkdir(parents=True, exist_ok=False)
    with target.open("xb") as output:
        output.write(FIXTURE.read_bytes())
        output.flush()
        os.fsync(output.fileno())
    overlay_status(source, request)
    control = Path(__file__).resolve().parents[2]
    write_json(evidence / "prepared.json", {
        "kind": "CCB_GROUP_UI_CONTROL_OVERLAY", "sourceSha": request["sourceSha"],
        "controlSha": git(control, "rev-parse", "HEAD"), "fixtureSha256": request["fixtureSha256"],
        "overlay": TARGET, "productionDiff": "empty", "nativeExecuted": False,
        "class": CLASS, "method": METHOD, "package": PACKAGE, "api": API,
        "instrumentationArgument": INSTRUMENTATION_ARGUMENT,
        "previewCode": 83, "proofScope": "fresh synthetic app; actual group page; app process remains alive"
    })
    with (evidence / "source-sha.txt").open("x", encoding="ascii", newline="\n") as output:
        output.write(request["sourceSha"] + "\n")


def one_apk(source: Path, relative: str) -> Path:
    values = list((source / relative).glob("*.apk"))
    require(len(values) == 1 and values[0].is_file() and not values[0].is_symlink(), "EXACT_APK_REQUIRED")
    return values[0]


def apk_manifest(apk: Path, aapt: Path, expected_package: str, code: str | None = None) -> dict:
    badging = command([str(aapt), "dump", "badging", str(apk)])
    found = re.search(r"^package: name='([^']+)' versionCode='([^']*)'", badging, re.M)
    require(found is not None and found.group(1) == expected_package, "APK_PACKAGE_MISMATCH")
    require(code is None or found.group(2) == code, "APK_VERSION_MISMATCH")
    digest = hashlib.sha256()
    with apk.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return {"package": found.group(1), "versionCode": found.group(2),
            "bytes": apk.stat().st_size, "sha256": digest.hexdigest()}


def run(request_path: Path, source: Path, evidence: Path) -> None:
    cloud_environment(os.environ)
    request = read_request(request_path)
    prepared = json.loads((evidence / "prepared.json").read_text(encoding="utf-8"))
    require(prepared["sourceSha"] == request["sourceSha"] and prepared["fixtureSha256"] == request["fixtureSha256"],
            "PREPARATION_CHANGED")
    require(prepared["api"] == API and prepared["class"] == CLASS and prepared["method"] == METHOD and
            prepared["instrumentationArgument"] == INSTRUMENTATION_ARGUMENT, "PREPARATION_PROFILE_CHANGED")
    require((evidence / "source-sha.txt").read_text(encoding="ascii") == request["sourceSha"] + "\n",
            "SOURCE_BINDING_CHANGED")
    require(prepared["controlSha"] == git(Path(__file__).resolve().parents[2], "rev-parse", "HEAD"),
            "CONTROL_HEAD_CHANGED")
    overlay_status(source, request)
    serial = emulator_serial(command(["adb", "devices"]))
    adb = ["adb", "-s", serial]
    sdk = command(adb + ["shell", "getprop", "ro.build.version.sdk"]).strip()
    qemu = command(adb + ["shell", "getprop", "ro.kernel.qemu"]).strip()
    require(sdk == str(API) and qemu == "1", "REAL_API" + str(API) + "_EMULATOR_REQUIRED")
    require(command(adb + ["shell", "pm", "list", "packages", "com.example.chatbar"]).strip() == "",
            "FRESH_CCB_INSTALL_REQUIRED")
    app = one_apk(source, "app/app/build/outputs/apk/lanPreview")
    tests = one_apk(source, "app/app/build/outputs/apk/androidTest/lanPreview")
    android_sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    require(bool(android_sdk), "ANDROID_SDK_MISSING")
    aapt = Path(android_sdk) / "build-tools/36.0.0/aapt"
    require(aapt.is_file(), "PINNED_AAPT_MISSING")
    app_manifest = apk_manifest(app, aapt, PACKAGE, "83")
    test_manifest = apk_manifest(tests, aapt, PACKAGE + ".test")
    write_json(evidence / "native-inputs.json", {"sourceSha": request["sourceSha"],
        "fixtureSha256": request["fixtureSha256"], "serial": serial, "sdk": sdk, "qemu": qemu,
        "freshBeforeInstall": True, "app": app_manifest, "tests": test_manifest})
    command(adb + ["install", str(app)], 180)
    command(adb + ["install", str(tests)], 180)
    runner = instrumentation_runner(command(adb + ["shell", "pm", "list", "instrumentation"]))
    argv = adb + ["shell", "am", "instrument", "-w", "-r", "-e", INSTRUMENTATION_ARGUMENT, "true",
                  "-e", "class", CLASS + "#" + METHOD, runner]
    try:
        actual = subprocess.run(argv, capture_output=True, timeout=600)
        raw, stderr, returncode = actual.stdout, actual.stderr, actual.returncode
    except subprocess.TimeoutExpired as failure:
        with (evidence / "instrumentation.txt").open("xb") as output:
            output.write(failure.stdout or b"")
        with (evidence / "instrumentation-stderr.txt").open("xb") as output:
            output.write(failure.stderr or b"")
        raise ControlError("INSTRUMENTATION_TIMEOUT") from None
    with (evidence / "instrumentation.txt").open("xb") as output:
        output.write(raw)
    with (evidence / "instrumentation-stderr.txt").open("xb") as output:
        output.write(stderr)
    require(returncode == 0 and not stderr.strip(), "INSTRUMENTATION_PROCESS_FAILED")
    result = execution(raw.decode("utf-8", errors="strict"), CLASS, METHOD)
    overlay_status(source, request)
    result.update(kind="CCB_GROUP_UI_NATIVE_METHOD_VERIFICATION", sourceSha=request["sourceSha"],
                  fixtureSha256=request["fixtureSha256"], rawReportSha256=hashlib.sha256(raw).hexdigest(),
                  proofScope=PROOF_SCOPE)
    write_json(evidence / "verified.json", result)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["request", "prepare", "run"])
    parser.add_argument("--request", type=Path, required=True)
    parser.add_argument("--source", type=Path)
    parser.add_argument("--evidence", type=Path)
    args = parser.parse_args(argv)
    try:
        if args.mode == "request":
            print("sourceSha=" + read_request(args.request)["sourceSha"])
        else:
            require(args.source is not None and args.evidence is not None, "SOURCE_AND_EVIDENCE_REQUIRED")
            if args.mode == "prepare":
                prepare(args.request, args.source, args.evidence)
            else:
                run(args.request, args.source, args.evidence)
        return 0
    except (ValueError, OSError, subprocess.SubprocessError, UnicodeError, KeyError) as failure:
        code = str(failure) if isinstance(failure, ControlError) else type(failure).__name__
        print("GROUP_UI_CONTROL_REFUSED: " + code, file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
