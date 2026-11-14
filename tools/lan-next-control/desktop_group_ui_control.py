#!/usr/bin/env python3
"""Pinned Windows Compose component + real AppContainer proof, not packaged Main."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

from group_ui_control import ControlError, command, git, request_bytes, require, write_json

CLASS = "com.example.chatbar.desktop.lanSync.DesktopGroupAppUiControlTest"
METHOD = "actualGroupSceneKeepsAppOwnerAfterLeavingAndReopensProtectedProfile"
TARGET = "app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/lanSync/DesktopGroupAppUiControlTest.kt"
FIXTURE = Path(__file__).resolve().parent / "fixtures/DesktopGroupAppUiControlTest.kt"


def read_request(path: Path) -> dict:
    require(not FIXTURE.is_symlink(), "DESKTOP_FIXTURE_SYMLINK")
    return request_bytes(path.read_bytes(), hashlib.sha256(FIXTURE.read_bytes()).hexdigest())


def cloud_windows(environment: dict[str, str]) -> None:
    require(environment.get("GITHUB_ACTIONS") == "true" and environment.get("RUNNER_OS") == "Windows",
            "CLOUD_WINDOWS_ONLY")


def target_path(source: Path) -> Path:
    source = source.resolve(strict=True)
    current = source
    for part in Path(TARGET).parts:
        current = current / part
        require(not current.is_symlink(), "DESKTOP_OVERLAY_SYMLINK")
    require(current.resolve(strict=False).is_relative_to(source), "DESKTOP_OVERLAY_ESCAPES_SOURCE")
    return current


def overlay_status(source: Path, request: dict) -> None:
    require(git(source, "rev-parse", "HEAD") == request["sourceSha"], "SOURCE_HEAD_CHANGED")
    require(git(source, "status", "--porcelain", "--untracked-files=all") == "?? " + TARGET,
            "DESKTOP_OVERLAY_NOT_EXCLUSIVE")
    require(hashlib.sha256(target_path(source).read_bytes()).hexdigest() == request["fixtureSha256"],
            "DESKTOP_OVERLAY_CHANGED")


def install_overlay(source: Path, raw: bytes) -> Path:
    target = target_path(source)
    require(not target.exists(), "DESKTOP_OVERLAY_TARGET_EXISTS")
    target.parent.mkdir(parents=True, exist_ok=True)
    # Recheck resolved ancestors after making the previously absent test package.
    target = target_path(source)
    with target.open("xb") as output:
        output.write(raw)
        output.flush()
        os.fsync(output.fileno())
    return target


def prepare(request_path: Path, source: Path, evidence: Path) -> None:
    cloud_windows(os.environ)
    request = read_request(request_path)
    require(git(source, "rev-parse", "HEAD") == request["sourceSha"], "SOURCE_HEAD_MISMATCH")
    require(git(source, "status", "--porcelain", "--untracked-files=all") == "", "SOURCE_NOT_CLEAN")
    target = target_path(source)
    require(not target.exists(), "DESKTOP_OVERLAY_TARGET_EXISTS")
    evidence.mkdir(parents=True, exist_ok=False)
    install_overlay(source, FIXTURE.read_bytes())
    overlay_status(source, request)
    write_json(evidence / "prepared.json", {"kind": "CCB_WINDOWS_GROUP_SCENE_CONTROL_OVERLAY",
        "sourceSha": request["sourceSha"], "controlSha": git(Path(__file__).resolve().parents[2], "rev-parse", "HEAD"),
        "fixtureSha256": request["fixtureSha256"], "overlay": TARGET, "productionDiff": "empty",
        "nativeExecuted": False, "class": CLASS, "method": METHOD,
        "scope": "real Compose scene + actual Windows AppContainer/DPAPI lifecycle; not packaged Main"})


def junit_result(raw: bytes) -> dict:
    require(len(raw) <= 1024 * 1024 and b"<!DOCTYPE" not in raw and b"<!ENTITY" not in raw, "JUNIT_FORMAT")
    root = ET.fromstring(raw)
    require(root.tag == "testsuite" and root.get("name") == CLASS, "JUNIT_CLASS")
    require(root.get("tests") == "1" and root.get("failures") == "0" and root.get("errors") == "0" and
            root.get("skipped") == "0", "JUNIT_NOT_ONE_EXECUTED_PASS")
    cases = root.findall("testcase")
    require(len(cases) == 1 and len(list(root.iter("testcase"))) == 1 and
            cases[0].get("classname") == CLASS and cases[0].get("name") == METHOD,
            "JUNIT_METHOD")
    require(not any(item.tag in ("failure", "error", "skipped") for item in root.iter()), "JUNIT_CASE_FAILED")
    require(all(item.tag in ("testsuite", "testcase", "properties", "property", "system-out", "system-err")
                for item in root.iter()), "JUNIT_UNEXPECTED_ELEMENT")
    seconds = float(cases[0].get("time", "nan"))
    require(math.isfinite(seconds) and seconds >= 0, "JUNIT_TIME")
    return {"class": CLASS, "method": METHOD, "executed": 1, "passed": 1,
            "failed": 0, "errors": 0, "skipped": 0, "seconds": seconds}


def isolated_environment(environment: dict[str, str]) -> dict[str, str]:
    cloud_windows(environment)
    run_id, attempt = environment.get("GITHUB_RUN_ID", ""), environment.get("GITHUB_RUN_ATTEMPT", "")
    require(re.fullmatch(r"[0-9]+", run_id) is not None and re.fullmatch(r"[0-9]+", attempt) is not None,
            "WORKFLOW_ID_REQUIRED")
    require(bool(environment.get("RUNNER_TEMP")), "RUNNER_TEMP_REQUIRED")
    runner = Path(environment["RUNNER_TEMP"]).resolve(strict=True)
    owned = runner / ("ccb-group-scene-control-" + run_id + "-" + attempt)
    require(owned.resolve(strict=False).is_relative_to(runner) and owned != runner, "NATIVE_PROFILE_ESCAPES_TEMP")
    owned.mkdir(exist_ok=False)
    local, roaming = owned / "Local", owned / "Roaming"
    local.mkdir()
    roaming.mkdir()
    result = dict(environment)
    result.update(LOCALAPPDATA=str(local), APPDATA=str(roaming), CCB_GROUP_UI_CONTROL="true")
    return result


def native_command(source: Path) -> list[str]:
    return ["cmd.exe", "/d", "/c", str(source / "app/gradlew.bat"), ":desktopApp:test", "--tests", CLASS,
            "-PdesktopTestHeadless=false", "--no-daemon", "--no-build-cache", "--rerun-tasks", "--stacktrace"]


def run(request_path: Path, source: Path, evidence: Path) -> None:
    cloud_windows(os.environ)
    request = read_request(request_path)
    prepared = json.loads((evidence / "prepared.json").read_text(encoding="utf-8"))
    require(prepared["sourceSha"] == request["sourceSha"] and prepared["fixtureSha256"] == request["fixtureSha256"] and
            prepared["controlSha"] == git(Path(__file__).resolve().parents[2], "rev-parse", "HEAD"), "PREPARATION_CHANGED")
    overlay_status(source, request)
    source = source.resolve(strict=True)
    evidence = evidence.resolve(strict=True)
    xml = source / ("app/desktopApp/build/test-results/test/TEST-" + CLASS + ".xml")
    require(not xml.exists() and not xml.is_symlink(), "PREEXISTING_NATIVE_XML_REFUSED")
    child_environment = isolated_environment(dict(os.environ))
    argv = native_command(source)
    raw = None
    with (evidence / "gradle-original.log").open("xb") as output:
        try:
            try:
                completed = subprocess.run(argv, cwd=source / "app", env=child_environment,
                                           stdout=output, stderr=subprocess.STDOUT, timeout=1800)
            finally:
                # Preserve the original report even if Gradle exit/drain times out.
                # Collection is not interpretation and cannot turn a failed runner green.
                if xml.is_file() and not xml.is_symlink():
                    raw = xml.read_bytes()
                    with (evidence / "original-test.xml").open("xb") as retained:
                        retained.write(raw)
        except subprocess.TimeoutExpired:
            raise ControlError("WINDOWS_GROUP_SCENE_TIMEOUT") from None
    require(raw is not None, "WINDOWS_GROUP_SCENE_XML_MISSING")
    actual = junit_result(raw)
    require(completed.returncode == 0, "WINDOWS_GROUP_SCENE_GRADLE_FAILED")
    overlay_status(source, request)
    actual.update(kind="CCB_WINDOWS_GROUP_SCENE_ORIGINAL_XML_VERIFICATION", sourceSha=request["sourceSha"],
                  controlSha=prepared["controlSha"], fixtureSha256=request["fixtureSha256"],
                  rawXmlSha256=hashlib.sha256(raw).hexdigest(),
                  scope="Compose component + actual AppContainer/DPAPI lifecycle; not packaged Main/OS window/remote delivery")
    write_json(evidence / "verified.json", actual)


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
    except (ValueError, OSError, subprocess.SubprocessError, UnicodeError, KeyError, ET.ParseError) as failure:
        code = str(failure) if isinstance(failure, ControlError) else type(failure).__name__
        print("WINDOWS_GROUP_UI_CONTROL_REFUSED: " + code, file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
