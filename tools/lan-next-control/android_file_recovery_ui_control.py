#!/usr/bin/env python3
"""Fixed f83/API35 file-recovery UI and separate cold-process proof.

Composes the authenticated existing runner; no combined instrumentation transcript.
The request can select neither a source nor a method/API/path beyond this profile.
"""
from __future__ import annotations
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys

SOURCE = "f83a73a388b7c189f81a852fb2cfeabfd6994dd0"
ENGINE_SHA = "c2f4dc2ffb7f42933abb1286982e7e990d5f292fa25fa2066d66300958a0c77b"
PARSER_SHA = "af65a1a275fa5d4b80c7c46d1f09bb03bb9c19e5c822bad928974f6f67f22158"
FIXTURE_SHA = "1f1fe1ccb82b380017974e2c9fc12ef81b06dc09a18658ff4ef240d9acaa9028"
PACKAGE = "com.example.chatbar.lansyncnext"
CLASS = "com.example.chatbar.lanSync.LanSyncGroupFileRecoveryUiControlTest"
FIRST = "realFilePickerResultRestoresLatestConversationWithSourceAlreadyOffline"
SECOND = "coldProcessReopensOriginalIdentityWithoutCodeSourceOrPackage"
TARGET = "app/app/src/androidTest/java/com/example/chatbar/lanSync/LanSyncGroupFileRecoveryUiControlTest.kt"
ARGUMENT = "lanSyncFileRecoveryUiFixture"
SCOPE = ("API35 real Activity file-selection result, AndroidKeystore, authenticated file/ledger/native "
         "restore and a separate force-stopped cold process; not DocumentsUI, physical devices, cloud or GB")


def require(value, code):
    if not value:
        raise ValueError(code)


def digest(path):
    require(path.is_file() and not path.is_symlink(), "TRUSTED_FILE_REQUIRED")
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_engine(directory=None):
    directory = Path(directory) if directory is not None else Path(__file__).resolve().parent
    path = directory / "group_ui_control.py"
    parser_path = directory / "instrumentation_report.py"
    require(digest(path) == ENGINE_SHA, "ENGINE_CHANGED")
    require(digest(parser_path) == PARSER_SHA, "PARSER_CHANGED")
    def checked_module(name, file):
        spec = importlib.util.spec_from_file_location(name, file)
        module = importlib.util.module_from_spec(spec)
        exec(compile(file.read_bytes(), str(file), "exec"), module.__dict__)
        return module
    parser = checked_module("_ccb_file_recovery_parser", parser_path)
    previous = sys.modules.get("instrumentation_report")
    try:
        sys.modules["instrumentation_report"] = parser
        base = checked_module("_ccb_file_recovery_engine", path)
    finally:
        if previous is None:
            sys.modules.pop("instrumentation_report", None)
        else:
            sys.modules["instrumentation_report"] = previous
    base.CLASS, base.METHOD, base.TARGET = CLASS, FIRST, TARGET
    base.FIXTURE = Path(__file__).resolve().parent / "fixtures/LanSyncGroupFileRecoveryUiControlTest.kt"
    base.API, base.INSTRUMENTATION_ARGUMENT, base.PROOF_SCOPE = 35, ARGUMENT, SCOPE
    original_read = base.read_request
    def fixed_request(path):
        request = original_read(path)
        require(request["sourceSha"] == SOURCE, "FIXED_SOURCE_REQUIRED")
        require(request["fixtureSha256"] == FIXTURE_SHA, "FIXED_FIXTURE_REQUIRED")
        return request
    base.read_request = fixed_request
    original_write = base.write_json
    def truthful_json(path, value):
        if path.name == "prepared.json":
            value = dict(value, previewCode=85, proofScope=SCOPE, phaseMethods=[FIRST, SECOND])
        original_write(path, value)
    base.write_json = truthful_json
    original_manifest = base.apk_manifest
    def version85(apk, aapt, package, code=None):
        if package == PACKAGE:
            require(code == "83", "LEGACY_ENGINE_CALL_CHANGED")
            code = "85"
        return original_manifest(apk, aapt, package, code)
    base.apk_manifest = version85
    return base


class RunFacade:
    """Private per-engine facade: never changes subprocess.run on the shared module."""
    def __init__(self, original, native):
        self._original, self._native = original, native
    def __getattr__(self, key):
        return getattr(self._original, key)
    def run(self, argv, **kwargs):
        if isinstance(argv, list) and "instrument" in argv:
            return self._native(argv, **kwargs)
        return self._original.run(argv, **kwargs)


class TwoPhases:
    def __init__(self, base, evidence, original_run):
        self.base, self.evidence, self.original_run = base, evidence, original_run
        self.results = []
        self.calls = 0
    def _one(self, argv, method, number, kwargs):
        for suffix in ("-instrumentation.txt", "-stderr.txt", "-verified.json"):
            require(not (self.evidence / ("phase" + str(number) + suffix)).exists(), "PHASE_EVIDENCE_EXISTS")
        raw, stderr = b"", b""
        try:
            actual = self.original_run(argv, **kwargs)
            raw, stderr = actual.stdout, actual.stderr
        except subprocess.TimeoutExpired as failure:
            raw, stderr = failure.stdout or b"", failure.stderr or b""
            raise ValueError("PHASE_" + str(number) + "_TIMEOUT") from None
        finally:
            with (self.evidence / ("phase" + str(number) + "-instrumentation.txt")).open("xb") as output:
                output.write(raw)
            with (self.evidence / ("phase" + str(number) + "-stderr.txt")).open("xb") as output:
                output.write(stderr)
        require(actual.returncode == 0 and not stderr.strip(), "PHASE_PROCESS_FAILED")
        result = self.base.execution(raw.decode("utf-8", errors="strict"), CLASS, method)
        result.update(phase=number, sourceSha=SOURCE, fixtureSha256=FIXTURE_SHA,
                      rawReportSha256=hashlib.sha256(raw).hexdigest())
        self.base.write_json(self.evidence / ("phase" + str(number) + "-verified.json"), result)
        self.results.append(result)
        return actual
    def __call__(self, argv, **kwargs):
        self.calls += 1
        require(self.calls == 1, "SINGLE_ENGINE_INSTRUMENTATION_REQUIRED")
        require(len(argv) == 15 and argv[:2] == ["adb", "-s"] and
                argv[3:13] == ["shell", "am", "instrument", "-w", "-r", "-e", ARGUMENT, "true", "-e", "class"] and
                argv[13] == CLASS + "#" + FIRST and
                argv[14:] == [PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"],
                "FIXED_INSTRUMENTATION_ARGV_REQUIRED")
        first = self._one(argv, FIRST, 1, kwargs)
        self.base.command(argv[:3] + ["shell", "am", "force-stop", PACKAGE])
        second = list(argv)
        second[13] = CLASS + "#" + SECOND
        self._one(second, SECOND, 2, kwargs)
        return first  # genuine first report, never a combined or generated report


def binding(base):
    return {"kind": "CCB_ANDROID_FILE_RECOVERY_TWO_PHASE_PROFILE", "sourceSha": SOURCE,
            "profileSha256": digest(Path(__file__).resolve()), "engineSha256": ENGINE_SHA,
            "parserSha256": PARSER_SHA, "fixtureSha256": FIXTURE_SHA, "class": CLASS,
            "phaseMethods": [FIRST, SECOND], "api": 35, "previewCode": 85,
            "overlay": TARGET, "package": PACKAGE, "proofScope": SCOPE}


def main(argv=None):
    base = load_engine()
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["request", "prepare", "run"])
    parser.add_argument("--request", type=Path, required=True)
    parser.add_argument("--source", type=Path)
    parser.add_argument("--evidence", type=Path)
    args = parser.parse_args(argv)
    try:
        if args.mode == "request":
            print("sourceSha=" + base.read_request(args.request)["sourceSha"])
            return 0
        require(args.source is not None and args.evidence is not None, "SOURCE_AND_EVIDENCE_REQUIRED")
        if args.mode == "prepare":
            base.prepare(args.request, args.source, args.evidence)
            base.write_json(args.evidence / "two-phase-profile.json", binding(base))
        else:
            expected = binding(base)
            require(json.loads((args.evidence / "two-phase-profile.json").read_bytes()) == expected, "PROFILE_CHANGED")
            prepared = json.loads((args.evidence / "prepared.json").read_bytes())
            require(prepared["previewCode"] == 85 and prepared["phaseMethods"] == [FIRST, SECOND], "PREPARED_PHASES_CHANGED")
            for name in ("instrumentation.txt", "instrumentation-stderr.txt", "verified.json", "two-phase-verified.json",
                         "phase1-instrumentation.txt", "phase1-stderr.txt", "phase1-verified.json",
                         "phase2-instrumentation.txt", "phase2-stderr.txt", "phase2-verified.json"):
                require(not (args.evidence / name).exists(), "NATIVE_EVIDENCE_EXISTS")
            phases = TwoPhases(base, args.evidence, subprocess.run)
            base.subprocess = RunFacade(subprocess, phases)
            base.run(args.request, args.source, args.evidence)
            require(phases.calls == 1 and len(phases.results) == 2, "TWO_NATIVE_PHASES_REQUIRED")
            base.write_json(args.evidence / "two-phase-verified.json", dict(expected, phases=phases.results,
                nativeExecuted=True, forceStoppedPackageOnly=PACKAGE, clearedOrReinstalledBetweenPhases=False))
        return 0
    except (ValueError, OSError, subprocess.SubprocessError, UnicodeError, KeyError):
        print("FILE_RECOVERY_UI_CONTROL_REFUSED", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
