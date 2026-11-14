import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
from unittest.mock import patch
from types import SimpleNamespace

import desktop_recovery_ui_control as control
import desktop_group_ui_control as public_desktop


class RecoveryUiDraftGuards(unittest.TestCase):
    def setUp(self):
        control.profile()

    def test_profile_pins_exact_fixed_source_and_fixture_bytes(self):
        self.assertEqual("480fbc2e6f6c6c0ac8cf3af937ecccb18bc73507", control.SOURCE)
        self.assertEqual(control.FIXTURE_SHA, hashlib.sha256(control.FIXTURE.read_bytes()).hexdigest())
        self.assertEqual(control.CLASS, control.base.CLASS)
        self.assertEqual(control.METHOD, control.base.METHOD)

    def test_profile_preserves_all_public_legacy_defaults(self):
        names = ("CLASS", "METHOD", "TARGET", "FIXTURE", "read_request")
        before = {name: getattr(public_desktop, name) for name in names}
        self.assertIsNot(control.base, public_desktop)
        control.profile(); control.profile()
        for name in names:
            self.assertIs(before[name], getattr(public_desktop, name), name)
        self.assertEqual("com.example.chatbar.desktop.lanSync.DesktopGroupAppUiControlTest", public_desktop.CLASS)
        self.assertEqual("actualGroupSceneKeepsAppOwnerAfterLeavingAndReopensProtectedProfile", public_desktop.METHOD)
        self.assertEqual(control.CLASS, control.base.CLASS)

    def test_private_namespace_refuses_unverified_engine_before_execution(self):
        with tempfile.TemporaryDirectory(prefix="ccb-ui-engine-guard-") as directory:
            path = Path(directory) / "desktop_group_ui_control.py"
            path.write_text("raise AssertionError('unverified engine must not execute')\n", encoding="utf-8")
            with patch.object(control.importlib.util, "find_spec", return_value=SimpleNamespace(origin=str(path))):
                with self.assertRaisesRegex(ValueError, "RECOVERY_ENGINE_CHANGED"):
                    control._load_private_engine()

    def test_new_owner_pin_is_not_weakened_for_another_body(self):
        with tempfile.TemporaryDirectory(prefix="ccb-ui-owner-guard-") as directory:
            path = Path(directory) / "real_webdav_process_owner.py"
            path.write_bytes(b"synthetic changed owner; never executed")
            with patch.object(control.owner, "__file__", str(path)):
                with self.assertRaisesRegex(ValueError, "RECOVERY_PROCESS_OWNER_CHANGED"):
                    control.profile()

    def test_wrong_source_request_is_rejected_even_inside_reused_engine(self):
        with tempfile.TemporaryDirectory(prefix="ccb-ui-request-guard-") as directory:
            path = Path(directory) / "request.json"
            value = dict(format=1, sourceSha="a" * 40, fixtureSha256=control.FIXTURE_SHA)
            path.write_text(json.dumps(value), encoding="utf-8")
            with self.assertRaises(ValueError): control.base.read_request(path)
            value["sourceSha"] = control.SOURCE
            path.write_text(json.dumps(value), encoding="utf-8")
            self.assertEqual(control.SOURCE, control.request(path)["sourceSha"])

    def test_companion_is_one_real_method_and_has_no_direct_recovery_callback_bypass(self):
        text = control.FIXTURE.read_text(encoding="utf-8")
        self.assertEqual(1, text.count("@Test"))
        self.assertIn("DesktopLanSyncEntry(app.lanSyncGroupCoordinator", text)
        self.assertIn("newContainer(resolved)", text)
        self.assertNotIn("recovery.confirm(", text)
        self.assertNotIn("recovery.prepare(", text)
        self.assertNotIn("LanSyncGroupCloudRecoveryState(", text)
        self.assertNotIn("Assume.", text)

    def test_choices_and_preapproval_close_have_real_persistence_assertions(self):
        text = control.FIXTURE.read_text(encoding="utf-8")
        for expected in ("listOf(false, true)", "explicit-close-retains-preparation-not-completion",
                         "check(!recovery.state.value.complete)", "cold.initializePersistentState()",
                         "coldDaily.cloudRoutes().rows() == routes", "receiver.characterRepository.getById(card.id) == card"):
            self.assertIn(expected, text)

    def test_native_command_reuses_original_runner_and_forces_execution(self):
        argv = control.base.native_command(Path("source"))
        self.assertEqual(control.CLASS, argv[argv.index("--tests") + 1])
        self.assertIn("--no-build-cache", argv); self.assertIn("--rerun-tasks", argv)
        self.assertEqual([":desktopApp:test"], [word for word in argv if word.startswith(":")])

    def report(self, **changes):
        value = dict(tests="1", skipped="0", failures="0", errors="0", name=control.CLASS, method=control.METHOD, children="")
        value.update(changes)
        return ('<testsuite name="{name}" tests="{tests}" skipped="{skipped}" failures="{failures}" errors="{errors}">'
                '<testcase classname="{name}" name="{method}" time="1.5">{children}</testcase></testsuite>').format(**value).encode()

    def test_exact_original_report_engine_is_reused(self):
        self.assertEqual(1, control.base.junit_result(self.report())["passed"])
        for change in (dict(tests="0"), dict(skipped="1"), dict(children="<skipped/>"), dict(failures="1"),
                       dict(name="Other"), dict(method="other"), dict(children="<error/>")):
            with self.subTest(change=change), self.assertRaises(ValueError):
                control.base.junit_result(self.report(**change))

    def test_tls_certificate_is_loopback_and_password_values_are_not_argv(self):
        argv = control.keytool_argv(Path("jdk/keytool.exe"), Path("owned"))
        self.assertIn("SAN=IP:127.0.0.1,DNS:localhost", argv)
        self.assertIn("EKU=serverAuth", argv)
        self.assertIn("-storepass:env", argv); self.assertNotIn("-storepass", argv)
        self.assertIn("-keypass:env", argv); self.assertNotIn("-keypass", argv)

    def test_server_is_direct_owned_loopback_tls(self):
        argv = control.server_argv(Path("rclone.exe"), Path("owned"), 18769, "synthetic-not-an-account")
        self.assertEqual("127.0.0.1:18769", argv[argv.index("--addr") + 1])
        self.assertEqual("tls1.2", argv[argv.index("--min-tls-version") + 1])
        self.assertIn("--cert", argv); self.assertIn("--key", argv)
        self.assertNotIn("--no-check-certificate", argv)

    def test_release_identity_is_exact_and_download_is_not_an_import_action(self):
        self.assertEqual(32193987, control.RCLONE_BYTES)
        self.assertEqual("8b4fd2737ade4e70c057ef4a49deb7cffe94b0fb9d7587c189d149823f21a1dc", control.RCLONE_SHA)
        self.assertTrue(control.RCLONE_URL.endswith("/v1.76.0/rclone-v1.76.0-windows-amd64.zip"))

    def test_zip_identity_is_checked_before_a_binary_is_published(self):
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, "w") as zipped:
            zipped.writestr("rclone-v1.76.0-windows-amd64/rclone.exe", b"synthetic non-executable bytes")
        raw = archive.getvalue()
        with tempfile.TemporaryDirectory(prefix="ccb-ui-zip-guard-") as directory:
            case = Path(directory)
            with patch.object(control.urllib.request, "urlopen", return_value=io.BytesIO(raw)), \
                    patch.object(control, "RCLONE_BYTES", len(raw)), patch.object(control, "RCLONE_SHA", "0" * 64):
                with self.assertRaises(ValueError): control.download_rclone(case)
            self.assertFalse((case / "rclone.exe").exists())

    def test_valid_synthetic_zip_extracts_only_the_expected_member_without_execution(self):
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, "w") as zipped:
            zipped.writestr("rclone-v1.76.0-windows-amd64/rclone.exe", b"synthetic non-executable bytes")
            zipped.writestr("../outside", b"must not extract")
        raw = archive.getvalue()
        with tempfile.TemporaryDirectory(prefix="ccb-ui-zip-guard-") as directory:
            case = Path(directory)
            with patch.object(control.urllib.request, "urlopen", return_value=io.BytesIO(raw)), \
                    patch.object(control, "RCLONE_BYTES", len(raw)), \
                    patch.object(control, "RCLONE_SHA", hashlib.sha256(raw).hexdigest()):
                binary = control.download_rclone(case)
            self.assertEqual(b"synthetic non-executable bytes", binary.read_bytes())
            self.assertEqual({"rclone.zip", "rclone.exe"}, {p.name for p in case.iterdir()})

    def test_no_duplicate_server_cleanup_or_system_trust_mutation(self):
        text = Path(control.__file__).read_text(encoding="utf-8")
        self.assertIn("owner.run_owned_server(", text)
        self.assertNotIn("subprocess.Popen(", text)
        self.assertNotIn("killpg(", text)
        self.assertNotIn("cacerts", text)
        self.assertNotIn("JAVA_TOOL_OPTIONS", text)
        self.assertIn("project.path == ':desktopApp'", text)
        self.assertIn("withType(org.gradle.api.tasks.testing.Test)", text)

    def test_native_run_is_rejected_outside_cloud_windows(self):
        with patch.dict(control.os.environ, {}, clear=True), self.assertRaises(ValueError):
            control.run(Path("missing-request"), Path("missing-source"), Path("missing-evidence"))


if __name__ == "__main__":
    unittest.main()
