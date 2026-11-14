import unittest
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
from unittest.mock import patch

import desktop_group_ui_control as control
from group_ui_control import ControlError


class DesktopGroupUiControlGuardsTest(unittest.TestCase):
    def test_native_command_forces_actual_execution_of_only_the_new_class(self):
        argv = control.native_command(Path("fixed-source"))
        self.assertIn("--no-build-cache", argv)
        self.assertIn("--rerun-tasks", argv)
        self.assertEqual(control.CLASS, argv[argv.index("--tests") + 1])
        self.assertEqual([":desktopApp:test"], [value for value in argv if value.startswith(":")])

    def test_missing_test_package_is_created_without_overwriting_a_fixture(self):
        temporary_base = Path(tempfile.gettempdir()).resolve(strict=True)
        scratch = Path(tempfile.mkdtemp(prefix="ccb-group-ui-overlay-", dir=temporary_base)).resolve(strict=True)
        try:
            target = control.install_overlay(scratch, b"synthetic companion\n")
            self.assertEqual(b"synthetic companion\n", target.read_bytes())
            self.assertEqual(scratch / control.TARGET, target)
            with self.assertRaises(ControlError):
                control.install_overlay(scratch, b"replacement")
            self.assertEqual(b"synthetic companion\n", target.read_bytes())
            with patch.object(Path, "is_symlink", return_value=True), self.assertRaises(ControlError):
                control.target_path(scratch)
        finally:
            resolved = scratch.resolve(strict=True)
            if resolved == temporary_base or not resolved.is_relative_to(temporary_base):
                raise RuntimeError("Refused cleanup outside owned temporary test directory")
            shutil.rmtree(resolved)

    def xml(self, **values):
        data = dict(tests="1", failures="0", errors="0", skipped="0", classname=control.CLASS,
                    method=control.METHOD, seconds="1.25", children="")
        data.update(values)
        return ('<testsuite name="' + data["classname"] + '" tests="' + data["tests"] +
                '" failures="' + data["failures"] + '" errors="' + data["errors"] +
                '" skipped="' + data["skipped"] + '"><testcase classname="' + data["classname"] +
                '" name="' + data["method"] + '" time="' + data["seconds"] + '">' +
                data["children"] + '</testcase></testsuite>').encode("utf-8")

    def test_exact_original_one_method_pass(self):
        result = control.junit_result(self.xml())
        self.assertEqual(1, result["executed"])
        self.assertEqual(1.25, result["seconds"])

    def test_wrong_counts_class_method_and_skip_refuse(self):
        for change in [dict(tests="0"), dict(tests="2"), dict(failures="1"), dict(errors="1"),
                       dict(skipped="1"), dict(classname="Other"), dict(method="other"),
                       dict(children="<skipped/>"), dict(children="<failure/>"), dict(children="<error/>")]:
            with self.subTest(change=change), self.assertRaises(ControlError):
                control.junit_result(self.xml(**change))

    def test_nonfinite_and_missing_duration_refuse(self):
        for value in ["nan", "inf", "-1", "-inf", ""]:
            with self.subTest(seconds=value), self.assertRaises(ValueError):
                control.junit_result(self.xml(seconds=value))

    def test_duplicate_cases_and_entity_declarations_refuse(self):
        raw = self.xml()
        start, end = raw.index(b"<testcase"), raw.index(b"</testcase>") + len(b"</testcase>")
        doubled = raw.replace(b"</testsuite>", raw[start:end] + b"</testsuite>")
        for value in [doubled, b'<!DOCTYPE testsuite []>' + raw,
                      b'<!ENTITY x "y">' + raw, raw * 6000]:
            with self.subTest(bytes=len(value)), self.assertRaises(ControlError):
                control.junit_result(value)

    def test_hidden_cases_root_failures_and_non_junit_elements_refuse(self):
        raw = self.xml()
        hidden = ('<properties><testcase classname="' + control.CLASS + '" name="' +
                  control.METHOD + '" time="1"/></properties>').encode("utf-8")
        for extra in [hidden, b"<failure/>", b"<properties><error/></properties>",
                      b"<system-out><skipped/></system-out>", b'<x:failure xmlns:x="urn:test"/>']:
            with self.subTest(extra=extra), self.assertRaises(ControlError):
                control.junit_result(raw.replace(b"</testsuite>", extra + b"</testsuite>"))

    def run_with_fake_native(self, raw, *, timeout=False, returncode=0):
        base = Path(__file__).resolve().parent
        temporary = tempfile.TemporaryDirectory(prefix="desktop-ui-guard-", dir=base)
        scratch = Path(temporary.name).resolve(strict=True)
        self.assertTrue(scratch.is_relative_to(base) and scratch != base)
        self.addCleanup(temporary.cleanup)
        source, evidence = scratch / "source", scratch / "evidence"
        source.mkdir(); (source / "app").mkdir(); evidence.mkdir()
        request = {"sourceSha": "a" * 40, "fixtureSha256": "b" * 64}
        (evidence / "prepared.json").write_text(json.dumps(dict(request, controlSha="c" * 40)), encoding="utf-8")
        xml = source / ("app/desktopApp/build/test-results/test/TEST-" + control.CLASS + ".xml")
        def native(argv, **kwargs):
            if raw is not None:
                xml.parent.mkdir(parents=True); xml.write_bytes(raw)
            if timeout:
                raise subprocess.TimeoutExpired(["synthetic-not-executed"], 1800)
            return subprocess.CompletedProcess(argv, returncode)
        with patch.object(control, "cloud_windows"), patch.object(control, "read_request", return_value=request), \
             patch.object(control, "git", return_value="c" * 40), patch.object(control, "overlay_status"), \
             patch.object(control, "isolated_environment", return_value={}), \
             patch.object(control, "native_command", return_value=["synthetic-not-executed"]), \
             patch.object(control.subprocess, "run", side_effect=native):
            with self.assertRaises(ControlError):
                control.run(scratch / "request.json", source, evidence)
        self.assertFalse((evidence / "verified.json").exists())
        return evidence

    def test_timeout_retains_failed_original_xml_without_creating_a_pass(self):
        raw = self.xml(failures="1", children="<failure message='synthetic-failure'/>")
        evidence = self.run_with_fake_native(raw, timeout=True)
        self.assertEqual(raw, (evidence / "original-test.xml").read_bytes())

    def test_timeout_retains_already_passed_xml_but_runner_still_fails(self):
        raw = self.xml()
        evidence = self.run_with_fake_native(raw, timeout=True)
        self.assertEqual(raw, (evidence / "original-test.xml").read_bytes())

    def test_timeout_without_xml_stays_missing_without_a_fabricated_report(self):
        evidence = self.run_with_fake_native(None, timeout=True)
        self.assertFalse((evidence / "original-test.xml").exists())

    def test_nonzero_runner_with_one_pass_xml_cannot_be_verified(self):
        raw = self.xml()
        evidence = self.run_with_fake_native(raw, returncode=9)
        self.assertEqual(raw, (evidence / "original-test.xml").read_bytes())

    def test_runtime_native_environment_is_cloud_windows_only(self):
        control.cloud_windows({"GITHUB_ACTIONS": "true", "RUNNER_OS": "Windows"})
        for environment in [{}, {"GITHUB_ACTIONS": "true", "RUNNER_OS": "Linux"},
                            {"GITHUB_ACTIONS": "True", "RUNNER_OS": "Windows"},
                            {"GITHUB_ACTIONS": "false", "RUNNER_OS": "Windows"}]:
            with self.subTest(environment=environment), self.assertRaises(ControlError):
                control.cloud_windows(environment)
        with self.assertRaises(ControlError):
            control.isolated_environment({})


if __name__ == "__main__":
    unittest.main()
