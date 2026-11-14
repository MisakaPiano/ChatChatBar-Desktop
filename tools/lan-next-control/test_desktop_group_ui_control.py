import unittest
from pathlib import Path
import shutil
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
