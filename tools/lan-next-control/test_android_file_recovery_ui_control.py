"""Pure Python/FS guards only. Synthetic statuses are never native proof."""
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import android_file_recovery_ui_control as profile

CONTROL = Path(__file__).resolve().parent


def report(method, status=0):
    def block(code):
        return ("INSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS: stream=.\n"
                "INSTRUMENTATION_STATUS: id=AndroidJUnitRunner\n"
                f"INSTRUMENTATION_STATUS: test={method}\nINSTRUMENTATION_STATUS: class={profile.CLASS}\n"
                f"INSTRUMENTATION_STATUS: current=1\nINSTRUMENTATION_STATUS_CODE: {code}\n")
    return (block(1) + block(status) + "INSTRUMENTATION_RESULT: stream=\n\nTime: 1.25\n\nOK (1 test)\n\nINSTRUMENTATION_CODE: -1\n").encode()


def argv(method=None):
    return ["adb", "-s", "emulator-5554", "shell", "am", "instrument", "-w", "-r", "-e", profile.ARGUMENT,
            "true", "-e", "class", profile.CLASS + "#" + (method or profile.FIRST),
            profile.PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"]


class ProfileGuards(unittest.TestCase):
    def base(self):
        return profile.load_engine(CONTROL)

    def test_real_engine_and_parser_are_pinned_and_defaults_not_mutated(self):
        sys.path.insert(0, str(CONTROL))
        try:
            import group_ui_control as public
            before = tuple(getattr(public, key) for key in ("CLASS", "METHOD", "TARGET", "API", "INSTRUMENTATION_ARGUMENT"))
            shared_run = subprocess.run
            a, b = self.base(), self.base()
            self.assertIsNot(a, b)
            self.assertIsNot(a, public)
            self.assertEqual(before, tuple(getattr(public, key) for key in ("CLASS", "METHOD", "TARGET", "API", "INSTRUMENTATION_ARGUMENT")))
            self.assertIs(shared_run, subprocess.run)
            self.assertEqual(35, a.API)
            self.assertEqual(profile.CLASS, a.CLASS)
        finally:
            sys.path.pop(0)

    def test_changed_engine_or_parser_refuses(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ("group_ui_control.py", "instrumentation_report.py"):
                (root / name).write_bytes((CONTROL / name).read_bytes())
            self.assertEqual(profile.FIRST, profile.load_engine(root).METHOD)
            for name in ("group_ui_control.py", "instrumentation_report.py"):
                original = (root / name).read_bytes()
                (root / name).write_bytes(original + b"\n# changed\n")
                with self.assertRaises(ValueError): profile.load_engine(root)
                (root / name).write_bytes(original)

    def test_request_exact_source_fixture_and_three_fields(self):
        base = self.base()
        value = dict(format=1, sourceSha=profile.SOURCE, fixtureSha256=profile.FIXTURE_SHA)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "request.json"
            path.write_text(json.dumps(value))
            self.assertEqual(value, base.read_request(path))
            for bad in (dict(value, sourceSha="0" * 40), dict(value, fixtureSha256="0" * 64),
                        dict(value, api=26), dict(value, method=profile.SECOND), dict(value, format=True)):
                path.write_text(json.dumps(bad))
                with self.assertRaises(ValueError): base.read_request(path)
            path.write_text(json.dumps(value)[:-1] + ',"sourceSha":"' + profile.SOURCE + '"}')
            with self.assertRaises(ValueError): base.read_request(path)

    def test_api35_version85_is_truthful_and_old83_rejected(self):
        base = self.base()
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "fixture.apk"
            apk.write_bytes(b"synthetic-only-not-an-apk")
            base.command = lambda *args: "package: name='" + profile.PACKAGE + "' versionCode='85'\n"
            self.assertEqual("85", base.apk_manifest(apk, Path("unused"), profile.PACKAGE, "83")["versionCode"])
            base.command = lambda *args: "package: name='" + profile.PACKAGE + "' versionCode='83'\n"
            with self.assertRaises(ValueError): base.apk_manifest(apk, Path("unused"), profile.PACKAGE, "83")
            path = Path(directory) / "prepared.json"
            base.write_json(path, {"previewCode":83})
            actual = json.loads(path.read_bytes())
            self.assertEqual(85, actual["previewCode"])
            self.assertEqual([profile.FIRST, profile.SECOND], actual["phaseMethods"])

    def test_existing_cloud_fresh_emulator_guards_remain_strict(self):
        base = self.base()
        base.cloud_environment({"GITHUB_ACTIONS":"true", "RUNNER_OS":"Linux"})
        for env in ({}, {"GITHUB_ACTIONS":"true", "RUNNER_OS":"Windows"}):
            with self.assertRaises(ValueError): base.cloud_environment(env)
        self.assertEqual("emulator-5554", base.emulator_serial("List of devices attached\nemulator-5554 device\n"))
        for text in ("List of devices attached\nreal-phone device\n", "List of devices attached\nemulator-5554 device\nemulator-5556 device\n"):
            with self.assertRaises(ValueError): base.emulator_serial(text)

    def test_two_true_raw_reports_exact_selectors_and_only_force_stop(self):
        base = self.base()
        calls, commands = [], []
        first_actual = subprocess.CompletedProcess(argv(), 0, report(profile.FIRST), b"")
        def native(actual, **kwargs):
            calls.append(actual)
            return first_actual if len(calls) == 1 else subprocess.CompletedProcess(actual, 0, report(profile.SECOND), b"")
        base.command = lambda command: commands.append(command) or ""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            phases = profile.TwoPhases(base, root, native)
            self.assertIs(first_actual, phases(argv(), capture_output=True, timeout=600))
            self.assertEqual([argv(), argv(profile.SECOND)], calls)
            self.assertEqual([["adb", "-s", "emulator-5554", "shell", "am", "force-stop", profile.PACKAGE]], commands)
            self.assertEqual(report(profile.FIRST), (root / "phase1-instrumentation.txt").read_bytes())
            self.assertEqual(report(profile.SECOND), (root / "phase2-instrumentation.txt").read_bytes())
            self.assertEqual([1, 1], [r["passed"] for r in phases.results])
            with self.assertRaises(ValueError): phases(argv())
            self.assertEqual(2, len(calls))

    def test_first_or_second_failure_never_becomes_two_phase_success(self):
        for fail_phase in (1, 2):
            with self.subTest(phase=fail_phase), tempfile.TemporaryDirectory() as directory:
                base = self.base(); base.command = lambda command: ""
                count = 0
                def native(actual, **kwargs):
                    nonlocal count
                    count += 1
                    method = profile.FIRST if count == 1 else profile.SECOND
                    return subprocess.CompletedProcess(actual, 0, report(method, -2 if count == fail_phase else 0), b"")
                root = Path(directory)
                phases = profile.TwoPhases(base, root, native)
                with self.assertRaises(ValueError): phases(argv())
                self.assertEqual(fail_phase, count)
                self.assertEqual(fail_phase - 1, len(phases.results))
                self.assertTrue((root / f"phase{fail_phase}-instrumentation.txt").is_file())
                self.assertFalse((root / f"phase{fail_phase}-verified.json").exists())

    def test_timeout_preserves_partial_original_and_exclusive_evidence_refuses_rerun(self):
        base = self.base(); base.command = lambda command: ""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); calls=[]
            def native(actual, **kwargs):
                calls.append(actual)
                raise subprocess.TimeoutExpired(actual, 600, output=b"original partial", stderr=b"original stderr")
            phases=profile.TwoPhases(base, root, native)
            with self.assertRaises(ValueError): phases(argv())
            self.assertEqual(b"original partial", (root / "phase1-instrumentation.txt").read_bytes())
            self.assertEqual(b"original stderr", (root / "phase1-stderr.txt").read_bytes())
            rerun=profile.TwoPhases(base, root, native)
            with self.assertRaises(ValueError): rerun(argv())
            self.assertEqual(1,len(calls))

    def test_private_subprocess_facade_does_not_patch_shared_stdlib(self):
        original = subprocess.run
        called=[]
        facade=profile.RunFacade(subprocess, lambda actual, **kwargs: called.append(actual) or "synthetic")
        self.assertEqual("synthetic", facade.run(argv()))
        self.assertEqual([argv()],called)
        self.assertIs(original,subprocess.run)
        self.assertIs(facade.TimeoutExpired,subprocess.TimeoutExpired)

    def test_arbitrary_selector_refuses_before_native(self):
        base=self.base(); base.command=lambda command: ""
        for bad in (argv(profile.SECOND), [*argv()[:-1], "untrusted/Runner"], [*argv(), "extra"]):
            with tempfile.TemporaryDirectory() as directory:
                phases=profile.TwoPhases(base,Path(directory),lambda *a,**k: self.fail("must refuse before I/O"))
                with self.assertRaises(ValueError): phases(bad)

    def test_fixture_and_workflow_have_fixed_two_phase_and_no_clear_or_receiver_shortcut(self):
        root=Path(__file__).resolve().parent
        text=(root/"fixtures/LanSyncGroupFileRecoveryUiControlTest.kt").read_text(encoding="utf-8")
        self.assertEqual(profile.FIXTURE_SHA,hashlib.sha256(text.encode()).hexdigest())
        self.assertEqual(2,text.count("@Test fun "))
        for name in (profile.FIRST,profile.SECOND): self.assertEqual(1,text.count("@Test fun "+name+"()"))
        for forbidden in ("recovery.prepare(","recovery.confirm(","node.apply(","node.inspect(","printToLog", "printToString"):
            self.assertNotIn(forbidden,text)
        self.assertIn("Intent.ACTION_OPEN_DOCUMENT",text)
        self.assertIn('current.members.keys == oldLedger.members.keys + access.device',text)
        self.assertIn('Process.myPid().toString() != expected("pid")',text)
        workflow=(root.parents[1]/".github/workflows/lan-next-file-recovery-ui-control.yml").read_text()
        self.assertIn("api-level: 35",workflow)
        self.assertIn("-PlanSyncPreviewCode=85",workflow)
        self.assertNotIn("-PlanSyncPreviewCode=83",workflow)
        self.assertIn("if: always()",workflow)
        self.assertNotIn("adb ",workflow)
        self.assertNotIn("uninstall",workflow)

if __name__ == "__main__": unittest.main()
