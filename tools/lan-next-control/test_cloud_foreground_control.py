import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import cloud_foreground_control as cloud
import group_ui_control as control


class CloudForegroundControlTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="ccb-cloud-fgs-control-")
        self.root = Path(self.temporary.name).resolve()
        self.assertTrue(self.root.is_relative_to(Path(tempfile.gettempdir()).resolve()))
        self.addCleanup(self.temporary.cleanup)
        self.source = self.root / "source"
        self.source.mkdir()
        self.git("init", "-q")
        self.target = self.source / cloud.TARGET
        self.target.parent.mkdir(parents=True)
        # Deliberate leading/trailing whitespace proves raw Git bytes are not stripped or normalized.
        self.body = b"\npackage synthetic.fixture\n// no app data, credentials or native execution\n\n"
        self.target.write_bytes(self.body)
        self.git("add", "--", cloud.TARGET)
        self.git("-c", "user.name=Synthetic Control", "-c", "user.email=control@synthetic.invalid",
                 "commit", "-q", "-m", "synthetic tracked fixture")
        self.sha = self.git("rev-parse", "HEAD")
        self.request = self.root / "request.json"
        self.value = {"format": 1, "sourceSha": self.sha,
                      "fixtureSha256": hashlib.sha256(self.body).hexdigest()}
        self.write_request()

    def git(self, *arguments):
        return subprocess.run(["git", "-C", str(self.source), *arguments], check=True,
                              capture_output=True, timeout=15).stdout.decode("utf-8").strip()

    def write_request(self, **changes):
        self.request.write_bytes(json.dumps(dict(self.value, **changes)).encode("utf-8"))

    def test_request_binds_raw_committed_bytes_without_a_control_fixture_copy(self):
        with patch.object(cloud, "control_root", return_value=self.source):
            self.assertEqual(self.value, cloud.read_request(self.request))
        self.assertEqual(self.body, cloud.committed_fixture(self.source, self.sha))
        self.assertEqual("", self.git("status", "--porcelain", "--untracked-files=all"))

    def test_unknown_dynamic_duplicate_and_invalid_fields_refuse_before_git_or_sdk(self):
        variants = [dict(self.value, path="other.kt"), dict(self.value, api=26),
                    dict(self.value, method="other"), dict(self.value, format=True),
                    dict(self.value, sourceSha="main"), dict(self.value, fixtureSha256="A" * 64), []]
        raw_values = [json.dumps(value).encode("utf-8") for value in variants]
        raw_values.append(json.dumps(self.value).replace('"format": 1', '"format": 1, "format": 1').encode())
        with patch.object(cloud, "committed_fixture") as git, patch.object(control, "command") as sdk:
            for raw in raw_values:
                self.request.write_bytes(raw)
                with self.subTest(raw=raw), self.assertRaises(control.ControlError):
                    cloud.read_request(self.request)
            git.assert_not_called()
            sdk.assert_not_called()

    def test_wrong_blob_digest_refuses_before_sdk(self):
        self.write_request(fixtureSha256="0" * 64)
        with patch.object(cloud, "control_root", return_value=self.source), \
                patch.object(control, "command") as sdk:
            with self.assertRaisesRegex(control.ControlError, "FIXTURE_SHA_MISMATCH"):
                cloud.read_request(self.request)
            sdk.assert_not_called()

    def test_prepare_records_tracked_mode_without_changing_source(self):
        evidence = self.root / "evidence"
        before = self.target.read_bytes()
        with patch.object(cloud, "control_root", return_value=self.source), \
                patch.dict(cloud.os.environ, {"GITHUB_ACTIONS": "true", "RUNNER_OS": "Linux"}):
            cloud.prepare(self.request, self.source, evidence)
        prepared = json.loads((evidence / "prepared.json").read_text(encoding="utf-8"))
        self.assertEqual(prepared["sourceMode"], "tracked")
        self.assertIsNone(prepared["overlay"])
        self.assertEqual(prepared["trackedTest"], cloud.TARGET)
        self.assertEqual((prepared["api"], prepared["class"], prepared["method"]),
                         (35, cloud.CLASS, cloud.METHOD))
        self.assertEqual(prepared["fixtureSha256"], self.value["fixtureSha256"])
        self.assertEqual((evidence / "source-sha.txt").read_bytes(), (self.sha + "\n").encode("ascii"))
        self.assertEqual(self.target.read_bytes(), before)
        self.assertEqual("", self.git("status", "--porcelain", "--untracked-files=all"))

    def test_dirty_and_untracked_source_refuse_before_sdk(self):
        for path in (self.target, self.source / "unexpected.txt"):
            with self.subTest(path=path):
                path.write_bytes(b"unexpected change\n")
                with patch.object(control, "one_apk") as sdk:
                    with self.assertRaisesRegex(control.ControlError, "SOURCE_NOT_CLEAN"):
                        cloud.overlay_status(self.source, self.value)
                    sdk.assert_not_called()
                if path == self.target:
                    path.write_bytes(self.body)
                else:
                    path.unlink()

    def test_ignored_untracked_target_cannot_masquerade_as_a_tracked_test(self):
        self.git("rm", "--cached", "--", cloud.TARGET)
        (self.source / ".gitignore").write_text(cloud.TARGET + "\n", encoding="utf-8")
        self.git("add", ".gitignore")
        self.git("-c", "user.name=Synthetic Control", "-c", "user.email=control@synthetic.invalid",
                 "commit", "-q", "-m", "synthetic ignored untracked target")
        self.assertEqual("", self.git("status", "--porcelain", "--untracked-files=all"))
        value = dict(self.value, sourceSha=self.git("rev-parse", "HEAD"))
        with self.assertRaisesRegex(control.ControlError, "TRACKED_SOURCE_TEST_REQUIRED"):
            cloud.overlay_status(self.source, value)

    def test_worktree_body_hash_is_checked_even_if_git_hides_a_change(self):
        self.target.write_bytes(b"changed worktree bytes\n")
        self.git("update-index", "--assume-unchanged", "--", cloud.TARGET)
        self.assertEqual("", self.git("status", "--porcelain", "--untracked-files=all"))
        with self.assertRaisesRegex(control.ControlError, "SOURCE_TEST_BODY_CHANGED"):
            cloud.overlay_status(self.source, self.value)

    def test_simulated_root_ancestor_and_target_symlinks_refuse(self):
        real = Path.is_symlink
        for unsafe in (self.source, self.root, self.target, self.target.parent):
            with self.subTest(unsafe=unsafe), \
                    patch.object(Path, "is_symlink", lambda path: path == unsafe or real(path)):
                with self.assertRaisesRegex(control.ControlError, "SYMLINK"):
                    cloud.checked_target(self.source)

    def test_symlink_before_parent_component_cannot_be_normalized_away(self):
        alias = self.root / "alias"
        unsafe = alias / ".." / "source"
        real = Path.is_symlink
        with patch.object(Path, "is_symlink", lambda path: path == alias or real(path)):
            with self.assertRaisesRegex(control.ControlError, "SOURCE_ROOT_SYMLINK"):
                cloud.checked_target(unsafe)

    def test_profile_restores_constants_and_hooks_after_success_and_exception(self):
        keys = ("PACKAGE", "CLASS", "METHOD", "TARGET", "API", "INSTRUMENTATION_ARGUMENT", "PROOF_SCOPE",
                "read_request", "prepare", "overlay_status")
        original = {key: getattr(control, key) for key in keys}

        def inspect(argv):
            self.assertEqual(control.API, 35)
            self.assertEqual(control.CLASS, cloud.CLASS)
            self.assertEqual(control.METHOD, cloud.METHOD)
            self.assertEqual(control.INSTRUMENTATION_ARGUMENT, "lanSyncCloudForegroundFixture")
            self.assertIs(control.prepare, cloud.prepare)
            self.assertIs(control.read_request, cloud.read_request)
            self.assertIs(control.overlay_status, cloud.overlay_status)
            return 7

        with patch.object(control, "main", inspect):
            self.assertEqual(cloud.main(["request", "--request", str(self.request)]), 7)
        self.assertEqual(original, {key: getattr(control, key) for key in keys})
        with patch.object(control, "main", side_effect=control.ControlError("synthetic refusal")):
            with self.assertRaises(control.ControlError):
                cloud.main([])
        self.assertEqual(original, {key: getattr(control, key) for key in keys})

    def test_real_base_run_rechecks_dirty_source_before_any_sdk_command(self):
        evidence = self.root / "evidence"
        real_command = control.command
        sdk_calls = []

        def git_only(argv, timeout=60):
            if argv[0] != "git":
                sdk_calls.append(argv)
                raise AssertionError("No SDK or native command may run in a metadata test")
            return real_command(argv, timeout)

        with patch.object(cloud, "control_root", return_value=self.source), \
                patch.dict(cloud.os.environ, {"GITHUB_ACTIONS": "true", "RUNNER_OS": "Linux"}):
            cloud.prepare(self.request, self.source, evidence)
            # Base.run binds controlSha to the real control checkout, not this metadata repository.
            prepared_path = evidence / "prepared.json"
            prepared = json.loads(prepared_path.read_text(encoding="utf-8"))
            prepared["controlSha"] = real_command(["git", "-C", str(Path(control.__file__).resolve().parents[2]),
                                                   "rev-parse", "HEAD"], 30).strip()
            prepared_path.write_text(json.dumps(prepared), encoding="utf-8")
            self.target.write_bytes(b"dirty before run\n")
            with cloud.profile(), patch.object(control, "command", side_effect=git_only):
                with self.assertRaisesRegex(control.ControlError, "SOURCE_NOT_CLEAN"):
                    control.run(self.request, self.source, evidence)
        self.assertEqual(sdk_calls, [])


if __name__ == "__main__":
    unittest.main()
