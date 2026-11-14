import importlib.util
import io
import json
from pathlib import Path
import re
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("proof_scope", Path(__file__).resolve().parents[1] / "select-proof-scope.py")
scope = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = scope
spec.loader.exec_module(scope)

REPOSITORY = "synthetic/ccb"
BRANCH = "codex/lan-sync-synthetic"
HEAD = "d" * 40
FIRST = "a" * 40
SECOND = "b" * 40
THIRD = "c" * 40
FOURTH = "f" * 40


def run_evidence(run_id, source, **changes):
    return dict({"id": run_id, "run_attempt": 1, "head_sha": source, "head_branch": BRANCH,
                 "head_repository": {"full_name": REPOSITORY}, "path": scope.WORKFLOW_PATH,
                 "event": "push", "status": "completed", "conclusion": "failure"}, **changes)


def job_evidence(run, kind, **changes):
    expected = scope.PROOF_JOBS[kind]
    return dict({"id": run["id"] * 10 + list(scope.PROOF_JOBS).index(kind) + 1,
                 "run_id": run["id"], "head_sha": run["head_sha"], "head_branch": run["head_branch"],
                 "name": expected.name, "status": "completed", "conclusion": "success",
                 "steps": [{"name": expected.step, "status": "completed", "conclusion": "success"}]}, **changes)


def workflow_evidence(needs="android", kinds=None, scope_job=False):
    lines = ["name: synthetic", "on: push", "permissions:", "  contents: read", "jobs:"]
    if scope_job:
        lines += ["  proof_scope:", "    runs-on: ubuntu-latest", "    steps:", "      - run: SYNTHETIC_SCOPE_GUARDS"]
    for kind in scope.PROOF_JOBS if kinds is None else kinds:
        expected = scope.PROOF_JOBS[kind]
        lines += ["  " + expected.name + ":", "    needs: " + needs,
                  "    if: needs." + needs + ".outputs." + kind + "_large == 'true'",
                  "    runs-on: ubuntu-latest", "    env:", "      PROOF_KIND: " + kind,
                  "    steps:", "      - uses: actions/setup-java@v4", "        with:", "          java-version: '17'",
                  "      - name: " + expected.step, "        run: PROOF_COMMAND_" + kind]
    lines += ["  android:", "    runs-on: ubuntu-latest", "    steps:", "      - run: SYNTHETIC_ANDROID_CHECK"]
    return "\n".join(lines) + "\n"


class SyntheticApi:
    def __init__(self, runs=(), jobs=None):
        self.runs = list(runs)
        self.jobs = jobs or {}
        self.calls = []
        self.failure = None
        self.override = None

    def get(self, path, query):
        self.calls.append((path, dict(query)))
        if self.failure:
            raise self.failure
        if self.override:
            result = self.override(path, query)
            if result is not None:
                return result
        if path.endswith("/workflows/lan-sync-ci.yml/runs"):
            field, values = "workflow_runs", self.runs
        else:
            match = re.search(r"/runs/(\d+)/attempts/(\d+)/jobs$", path)
            if not match:
                raise AssertionError("Only workflow-run and attempt-job metadata may be requested")
            field, values = "jobs", self.jobs.get(tuple(map(int, match.groups())), [])
        offset = (query["page"] - 1) * query["per_page"]
        return {"total_count": len(values), field: values[offset:offset + query["per_page"]]}


class SyntheticGit:
    def __init__(self, changes=None, ancestors=(FIRST, SECOND, THIRD, FOURTH), files=None):
        self.changes = changes or {}
        self.ancestors = set(ancestors)
        self.compared = []
        self.files = files or {}
        self.read = []

    def verify_head(self, head):
        if head != HEAD:
            raise scope.EvidenceUnavailable("PROOF_CHECKOUT_HEAD_MISMATCH")

    def is_ancestor(self, source, head):
        return source in self.ancestors and head == HEAD

    def paths(self, source, head):
        self.compared.append((source, head))
        value = self.changes.get(source, [])
        if isinstance(value, Exception):
            raise value
        return value

    def read_file(self, source, path):
        self.read.append((source, path))
        value = self.files.get((source, path))
        if value is None:
            raise scope.EvidenceUnavailable("PROOF_SOURCE_UNAVAILABLE")
        return value


class ProofScopeTest(unittest.TestCase):
    def select(self, api, git=None):
        return scope.select_scope(api, git or SyntheticGit(), REPOSITORY, BRANCH, HEAD, 999)

    def control_scope(self, paths, before=None, after=None, kinds=None, files=None):
        proven = run_evidence(102, FIRST)
        jobs = [job_evidence(proven, kind) for kind in (scope.PROOF_JOBS if kinds is None else kinds)]
        sources = {(FIRST, scope.WORKFLOW_PATH): workflow_evidence() if before is None else before,
                   (HEAD, scope.WORKFLOW_PATH): workflow_evidence() if after is None else after}
        sources.update(files or {})
        git = SyntheticGit({FIRST: paths}, files=sources)
        return self.select(SyntheticApi([proven], {(102, 1): jobs}), git), git

    def test_orchestration_decoupling_and_new_known_file_job_keep_older_three_successful_execution_contracts(self):
        previous = workflow_evidence(kinds=("legacy", "group", "cloud"))
        current = workflow_evidence(needs="proof_scope", scope_job=True)
        result, git = self.control_scope(list(scope.CI_CONTROL_FILES), previous, current,
                                         kinds=("legacy", "group", "cloud"))
        self.assertEqual({"legacy": False, "group": False, "cloud": False, "file": True}, result.required)
        self.assertEqual({"legacy", "group", "cloud"}, set(result.verified_controls))
        self.assertNotIn("file", result.baselines)
        self.assertEqual(2, len(git.read))

    def test_ci_controls_are_reused_only_after_each_exact_job_contract_is_verified(self):
        before = workflow_evidence()
        after = workflow_evidence(needs="proof_scope", scope_job=True).replace("PROOF_COMMAND_cloud", "CHANGED_CLOUD_COMMAND")
        result, _ = self.control_scope(list(scope.CI_CONTROL_FILES), before, after)
        self.assertEqual({"legacy": False, "group": False, "cloud": True, "file": False}, result.required)
        self.assertEqual(("cloud",), result.changed_contracts)
        self.assertEqual({"legacy", "group", "file"}, set(result.verified_controls))

    def test_proof_environment_java_runner_and_global_runtime_changes_cannot_reuse_execution_evidence(self):
        source = workflow_evidence(kinds=("legacy",))
        before = scope.workflow_proof_contract(source, "legacy")
        for old, new in (("PROOF_KIND: legacy", "PROOF_KIND: changed"), ("java-version: '17'", "java-version: '21'"),
                         ("runs-on: ubuntu-latest", "runs-on: windows-latest"), ("PROOF_COMMAND_legacy", "DIFFERENT_COMMAND")):
            with self.subTest(old=old):
                self.assertNotEqual(before, scope.workflow_proof_contract(source.replace(old, new), "legacy"))
        changed = workflow_evidence().replace("permissions:\n  contents: read", "permissions:\n  contents: read\nenv:\n  SHARED_RUNTIME: changed")
        result, _ = self.control_scope([scope.WORKFLOW_PATH], after=changed)
        self.assertEqual(dict.fromkeys(scope.PROOF_JOBS, True), result.required)
        self.assertEqual(set(scope.PROOF_JOBS), set(result.changed_contracts))

    def test_unknown_workflow_structure_missing_source_and_unexpected_orchestration_fail_closed(self):
        normal = workflow_evidence()
        for changed in (normal + "env:\n  NEW_GLOBAL: changed\n",
                        normal + "  unknown-job:\n    runs-on: ubuntu-latest\n",
                        normal.replace("    needs: android", "    needs: [android, unknown]"),
                        normal.replace("    needs: android", "    needs: proof_scope"),
                        normal.replace("  contents: read", "  contents: read\n  inherited: *runtime")):
            with self.subTest(changed=changed):
                result, _ = self.control_scope([scope.WORKFLOW_PATH], after=changed)
                self.assertEqual(dict.fromkeys(scope.PROOF_JOBS, True), result.required)
                self.assertFalse(result.baselines)
        proven = run_evidence(103, FIRST)
        git = SyntheticGit({FIRST: [scope.SCOPE_SCRIPT]})
        result = self.select(SyntheticApi([proven], {(103, 1): [job_evidence(proven, kind) for kind in scope.PROOF_JOBS]}), git)
        self.assertEqual(dict.fromkeys(scope.PROOF_JOBS, True), result.required)
        self.assertEqual("PROOF_SOURCE_UNAVAILABLE", result.diagnostic)

    def test_verified_ci_controls_do_not_mask_source_or_unknown_tool_changes(self):
        result, _ = self.control_scope([scope.SCOPE_SCRIPT, scope.LAN + "LanSyncGroupSourceWriter.kt"])
        self.assertEqual({"legacy": False, "group": True, "cloud": True, "file": True}, result.required)
        result, _ = self.control_scope([scope.WORKFLOW_PATH, "tools/lan-sync/new-runtime-hook.py"])
        self.assertEqual(dict.fromkeys(scope.PROOF_JOBS, True), result.required)

    def test_only_the_exact_native_preview_code_allowlist_can_reuse_the_android_gradle_contract(self):
        before = 'plugins { id("synthetic.android") }\nrequire(lanSyncPreviewSeries == "next" && raw in setOf("81", "82")) { "LAN_PREVIEW_SERIES_OR_CODE_INVALID" }\n'
        after = before.replace('"81", "82"', '"81", "82", "83", "84", "85"')
        files = {(FIRST, scope.ANDROID_BUILD_PATH): before, (HEAD, scope.ANDROID_BUILD_PATH): after}
        result, _ = self.control_scope([scope.ANDROID_BUILD_PATH], files=files)
        self.assertEqual(dict.fromkeys(scope.PROOF_JOBS, False), result.required)
        files[(HEAD, scope.ANDROID_BUILD_PATH)] = after.replace("synthetic.android", "changed.android")
        result, _ = self.control_scope([scope.ANDROID_BUILD_PATH], files=files)
        self.assertEqual(dict.fromkeys(scope.PROOF_JOBS, True), result.required)

    def test_fixed_file_owner_focus_accepts_only_the_authorized_ancestral_change_scope(self):
        request = {"schema": 1, "mode": scope.ANDROID_FOCUS_MODE, "baseline": scope.ANDROID_FOCUS_BASELINE}
        paths = list(scope.ANDROID_FOCUS_FILES) + [".agents/skills/chatbar-lan-sync/SKILL.md"]
        git = SyntheticGit({scope.ANDROID_FOCUS_BASELINE: paths}, ancestors=(scope.ANDROID_FOCUS_BASELINE,))
        result = scope.select_android_scope(git, HEAD, request)
        self.assertEqual("file-recovery-owner", result.mode)
        self.assertEqual("ANDROID_FOCUS_ONLY_PRIOR_FULL_SUITE_REMAINS_FAILED", result.diagnostic)
        self.assertEqual([(scope.ANDROID_FOCUS_BASELINE, HEAD)], git.compared)
        actual = json.loads((Path(__file__).resolve().parents[3] / scope.FOCUS_REQUEST).read_text(encoding="utf-8"))
        self.assertEqual(request, actual)
        self.assertEqual("78d399e037ae5b4f6a88e722261caba8d96ba146", scope.ANDROID_FOCUS_BASELINE)

    def test_focus_unknown_source_build_or_test_changes_and_nonancestry_require_the_full_suite(self):
        request = {"schema": 1, "mode": scope.ANDROID_FOCUS_MODE, "baseline": scope.ANDROID_FOCUS_BASELINE}
        for path in (scope.LAN + "LanSyncGroupNode.kt", "app/sharedCore/build.gradle.kts", scope.ANDROID_BUILD_PATH,
                     "app/sharedCore/src/test/kotlin/com/example/chatbar/domain/lanSync/OtherTest.kt",
                     ".agents/skills/new-hook.py"):
            with self.subTest(path=path):
                git = SyntheticGit({scope.ANDROID_FOCUS_BASELINE: [path]}, ancestors=(scope.ANDROID_FOCUS_BASELINE,))
                result = scope.select_android_scope(git, HEAD, request)
                self.assertEqual("full", result.mode)
                self.assertEqual("ANDROID_FOCUS_OUTSIDE_ALLOWED_CHANGE_SCOPE", result.diagnostic)
        self.assertEqual("ANDROID_FOCUS_BASELINE_UNAVAILABLE", scope.select_android_scope(SyntheticGit(), HEAD, request).diagnostic)

    def test_exact_file_large_fixture_can_keep_owner_focus_without_skipping_its_independent_byte_proof(self):
        fixture = "app/sharedCore/src/test/kotlin/com/example/chatbar/domain/syncStoreProof/SyncGroupFileLargeNativeProofTest.kt"
        request = {"schema": 1, "mode": scope.ANDROID_FOCUS_MODE, "baseline": scope.ANDROID_FOCUS_BASELINE}
        paths = [scope.LAN + "LanSyncGroupFileRecoveryOwner.kt",
                 "app/sharedCore/src/test/kotlin/com/example/chatbar/domain/lanSync/FileRecoveryOwnerTest.kt", fixture]
        git = SyntheticGit({scope.ANDROID_FOCUS_BASELINE: paths}, ancestors=(scope.ANDROID_FOCUS_BASELINE,))
        focused = scope.select_android_scope(git, HEAD, request)
        self.assertEqual("file-recovery-owner", focused.mode)
        self.assertEqual("ANDROID_FOCUS_ONLY_PRIOR_FULL_SUITE_REMAINS_FAILED", focused.diagnostic)
        proof, _ = self.control_scope(paths)
        self.assertEqual({"legacy": False, "group": False, "cloud": False, "file": True}, proof.required)
        neighbor = fixture.replace("SyncGroupFileLargeNativeProofTest.kt", "SyncGroupFileLargeNativeProofTestExtra.kt")
        for extra in (neighbor, scope.LAN + "LanSyncGroupNativeApplyOwner.kt", scope.LAN + "LanSyncWorkspace.kt"):
            with self.subTest(extra=extra):
                outside = SyntheticGit({scope.ANDROID_FOCUS_BASELINE: paths + [extra]}, ancestors=(scope.ANDROID_FOCUS_BASELINE,))
                refused = scope.select_android_scope(outside, HEAD, request)
                self.assertEqual("full", refused.mode)
                self.assertEqual("ANDROID_FOCUS_OUTSIDE_ALLOWED_CHANGE_SCOPE", refused.diagnostic)
        native, _ = self.control_scope(paths + [scope.LAN + "LanSyncGroupNativeApplyOwner.kt"])
        self.assertEqual({"legacy": False, "group": True, "cloud": True, "file": True}, native.required)
        common, _ = self.control_scope(paths + [scope.LAN + "LanSyncWorkspace.kt"])
        self.assertEqual(dict.fromkeys(scope.PROOF_JOBS, True), common.required)

    def test_exact_progress_helper_and_cloud_fixture_keep_focus_but_require_their_independent_byte_jobs(self):
        prefix = "app/sharedCore/src/test/kotlin/com/example/chatbar/domain/syncStoreProof/"
        helper = prefix + "LargeProofProgress.kt"
        cloud = prefix + "SyncGroupCloudLargeNativeProofTest.kt"
        file = prefix + "SyncGroupFileLargeNativeProofTest.kt"
        request = {"schema": 1, "mode": scope.ANDROID_FOCUS_MODE, "baseline": scope.ANDROID_FOCUS_BASELINE}
        paths = [helper, cloud, file, scope.LAN + "LanSyncGroupFileRecoveryOwner.kt"]
        git = SyntheticGit({scope.ANDROID_FOCUS_BASELINE: paths}, ancestors=(scope.ANDROID_FOCUS_BASELINE,))
        self.assertEqual("file-recovery-owner", scope.select_android_scope(git, HEAD, request).mode)
        for changed, expected in (([helper], {"legacy": False, "group": False, "cloud": True, "file": True}),
                                  ([cloud], {"legacy": False, "group": False, "cloud": True, "file": False}),
                                  (paths, {"legacy": False, "group": False, "cloud": True, "file": True})):
            with self.subTest(changed=changed):
                proof, _ = self.control_scope(changed)
                self.assertEqual(expected, proof.required)
        for neighbor in (prefix + "LargeProofProgressExtra.kt", prefix + "SyncGroupCloudLargeNativeProofTestExtra.kt"):
            with self.subTest(neighbor=neighbor):
                outside = SyntheticGit({scope.ANDROID_FOCUS_BASELINE: paths + [neighbor]}, ancestors=(scope.ANDROID_FOCUS_BASELINE,))
                self.assertEqual("full", scope.select_android_scope(outside, HEAD, request).mode)
                self.assertEqual((False, True), scope.classify([neighbor]))
                self.assertTrue(scope.classify_cloud([neighbor]))
                self.assertTrue(scope.classify_file([neighbor]))

    def test_focus_filter_or_baseline_injection_and_unavailable_diff_are_never_a_focused_success(self):
        valid = {"schema": 1, "mode": scope.ANDROID_FOCUS_MODE, "baseline": scope.ANDROID_FOCUS_BASELINE}
        for request in (dict(valid, tests="OtherTest; injected-command"), dict(valid, mode="arbitrary-filter"),
                        dict(valid, baseline=FIRST), dict(valid, schema=True), None):
            with self.subTest(request=request):
                git = SyntheticGit()
                self.assertEqual("full", scope.select_android_scope(git, HEAD, request).mode)
                self.assertFalse(git.compared)
        git = SyntheticGit({scope.ANDROID_FOCUS_BASELINE: RuntimeError("synthetic private failure")},
                           ancestors=(scope.ANDROID_FOCUS_BASELINE,))
        result = scope.select_android_scope(git, HEAD, valid)
        self.assertEqual("full", result.mode)
        self.assertEqual("ANDROID_FOCUS_SCOPE_UNAVAILABLE", result.diagnostic)

    def test_pure_group_layout_state_and_clipboard_do_not_repeat_large_byte_proofs(self):
        self.assertEqual((False, False), scope.classify(list(scope.PURE_FILES)))

    def test_native_planner_and_group_io_keep_the_group_byte_proof(self):
        for name in ("LanSyncGroupNativePlanner.kt", "LanSyncGroupSourceWriter.kt", "LanSyncGroupCiphertextTransport.kt"):
            self.assertEqual((False, True), scope.classify([scope.LAN + name]))

    def test_common_native_io_and_tls_require_both_proofs(self):
        for name in ("LanSyncTls.kt", "LanSyncWorkspace.kt", "LanSyncResourceStore.kt", "LanSyncScope.kt"):
            self.assertEqual((True, True), scope.classify([scope.LAN + name]))

    def test_crypto_and_inbox_changes_keep_group_proof(self):
        for name in ("SyncStreamingCipher.kt", "SyncGroupInbox.kt", "SyncGroupObjectCodec.kt"):
            self.assertEqual((False, True), scope.classify([scope.STORE + name]))

    def test_legacy_runtime_keeps_its_own_large_transfer_proof(self):
        self.assertEqual((True, False), scope.classify([scope.LAN + "LanSyncRuntime.kt"]))

    def test_large_proof_sources_are_never_ordinary_small_unit_tests(self):
        self.assertEqual((True, False), scope.classify([scope.LEGACY_PROOF + "Proof.kt"]))
        self.assertEqual((False, True), scope.classify([scope.GROUP_PROOF + "Proof.kt"]))

    def test_docs_and_ordinary_tests_reuse_existing_byte_evidence(self):
        self.assertEqual((False, False), scope.classify([".agents/skills/chatbar-lan-sync/SKILL.md",
            "app/sharedCore/src/test/kotlin/com/example/chatbar/domain/lanSync/LanSyncGroupNodeTest.kt",
            "app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/DesktopLanSyncResolutionProcessTest.kt"]))

    def test_unknown_files_including_new_group_owners_and_build_config_stay_conservative(self):
        for path in (scope.LAN + "LanSyncGroupUnknown.kt", scope.STORE + "FutureCodec.kt", "app/gradle/libs.versions.toml",
                     ".github/workflows/lan-sync-ci.yml", "tools/lan-sync/select-proof-scope.py"):
            self.assertEqual((True, True), scope.classify([path]))
            self.assertTrue(scope.classify_cloud([path]))
            self.assertTrue(scope.classify_file([path]))

    def test_pure_files_cannot_mask_an_independent_io_change_in_the_same_push(self):
        self.assertEqual((False, True), scope.classify(list(scope.PURE_FILES) + [scope.LAN + "LanSyncGroupNativePlanner.kt"]))

    def test_cloud_only_bytes_select_the_cloud_proof_without_repeating_old_lan_proofs(self):
        paths = [scope.LAN + name for name in scope.CLOUD_LAN] + [scope.STORE + name for name in scope.CLOUD_STORE]
        for path in paths:
            self.assertEqual((False, False), scope.classify([path]), path)
            self.assertTrue(scope.classify_cloud([path]), path)

    def test_cloud_fixture_is_not_the_legacy_group_resource_fixture(self):
        for name in scope.CLOUD_PROOF_NAMES:
            path = scope.GROUP_PROOF + name
            self.assertEqual((False, False), scope.classify([path]))
            self.assertTrue(scope.classify_cloud([path]))

    def test_common_group_crypto_requires_both_group_and_cloud_byte_proofs(self):
        for path in (scope.LAN + "LanSyncGroupNativePlanner.kt", scope.STORE + "SyncStreamingCipher.kt"):
            self.assertEqual((False, True), scope.classify([path]))
            self.assertTrue(scope.classify_cloud([path]))
            self.assertTrue(scope.classify_file([path]))

    def test_app_controls_require_native_checks_without_claiming_large_shared_coverage(self):
        for path in scope.APP_CONTROLS:
            self.assertEqual((False, False), scope.classify([path]))
            self.assertFalse(scope.classify_cloud([path]))
            self.assertFalse(scope.classify_file([path]))

    def test_file_proof_and_its_synthetic_inputs_do_not_repeat_the_other_three_proofs(self):
        for name in scope.FILE_PROOF_NAMES:
            path = scope.GROUP_PROOF + name
            self.assertEqual((False, False), scope.classify([path]))
            self.assertFalse(scope.classify_cloud([path]))
            self.assert_helper_requires(path, {"legacy": False, "group": False, "cloud": False, "file": True})

    def test_file_codec_source_and_owners_have_their_own_byte_proof(self):
        for name in scope.FILE_LAN:
            path = scope.LAN + name
            self.assertEqual((False, False), scope.classify([path]))
            self.assertFalse(scope.classify_cloud([path]))
            self.assertTrue(scope.classify_file([path]))

    def test_shared_recovery_export_and_native_dependencies_keep_the_file_and_cloud_proofs(self):
        names = scope.FILE_SHARED_LAN | {
            "LanSyncGroupManagementRecovery.kt", "LanSyncGroupManagementBackup.kt", "LanSyncGroupNode.kt",
            "LanSyncGroupJoining.kt", "LanSyncGroupSourceWriter.kt", "LanSyncGroupNativeApplyOwner.kt",
        }
        for name in names:
            path = scope.LAN + name
            self.assertTrue(scope.classify_cloud([path]), name)
            self.assertTrue(scope.classify_file([path]), name)

    def test_exact_new_group_owners_do_not_repeat_the_old_direct_group_or_legacy_proofs(self):
        paths = [scope.LAN + name for name in scope.CLOUD_FILE_OWNERS]
        paths += [scope.STORE + name for name in scope.GROUP_CONTROL_STORE]
        for path in paths:
            self.assertEqual((False, False), scope.classify([path]), path)
            self.assertTrue(scope.classify_cloud([path]), path)
            self.assertTrue(scope.classify_file([path]), path)

    def test_cloud_provider_http_and_scheduling_do_not_repeat_the_file_proof(self):
        paths = [scope.LAN + name for name in scope.CLOUD_LAN - scope.FILE_SHARED_LAN]
        paths += [scope.STORE + name for name in scope.CLOUD_STORE]
        paths += [scope.GROUP_PROOF + name for name in scope.CLOUD_PROOF_NAMES]
        for path in paths:
            self.assertTrue(scope.classify_cloud([path]), path)
            self.assertFalse(scope.classify_file([path]), path)

    def test_existing_group_and_legacy_large_test_bodies_do_not_repeat_the_file_proof(self):
        for path in [scope.GROUP_PROOF + name for name in scope.GROUP_PROOF_NAMES] + [scope.LEGACY_PROOF + "Proof.kt"]:
            self.assertFalse(scope.classify_file([path]))

    def assert_helper_requires(self, path, required):
        proven = run_evidence(101, FIRST, conclusion="success")
        api = SyntheticApi([proven], {(101, 1): [job_evidence(proven, kind) for kind in scope.PROOF_JOBS]})
        git = SyntheticGit({FIRST: [path]})
        result = self.select(api, git)
        self.assertEqual(required, result.required)
        self.assertEqual(set(scope.PROOF_JOBS), set(result.baselines))
        self.assertEqual([(FIRST, HEAD)], git.compared)

    def test_administration_fixture_change_requires_cloud_and_file_with_all_successful_baselines(self):
        self.assert_helper_requires(
            "app/sharedCore/src/test/kotlin/com/example/chatbar/domain/lanSync/LanSyncGroupAdministrationTest.kt",
            {"legacy": False, "group": False, "cloud": True, "file": True})

    def test_legacy_runtime_harness_change_requires_only_legacy_with_all_successful_baselines(self):
        self.assert_helper_requires(
            "app/sharedCore/src/test/kotlin/com/example/chatbar/domain/lanSync/LanSyncRuntimeTest.kt",
            {"legacy": True, "group": False, "cloud": False, "file": False})

    def test_group_loopback_harness_change_requires_only_group_with_all_successful_baselines(self):
        self.assert_helper_requires(
            "app/sharedCore/src/test/kotlin/com/example/chatbar/domain/lanSync/LanSyncGroupLoopbackTransport.kt",
            {"legacy": False, "group": True, "cloud": False, "file": False})

    def test_failed_then_narrow_followup_compares_to_actual_success_instead_of_the_previous_push(self):
        failed = run_evidence(12, SECOND)
        proven = run_evidence(11, FIRST)
        api = SyntheticApi([failed, proven], {
            (12, 1): [job_evidence(failed, "legacy", conclusion="failure"),
                      job_evidence(failed, "group", conclusion="skipped", steps=[]),
                      job_evidence(failed, "cloud", conclusion="failure")],
            (11, 1): [job_evidence(proven, "legacy"), job_evidence(proven, "group")],
        })
        git = SyntheticGit({FIRST: [scope.LAN + "LanSyncGroupNativePlanner.kt", next(iter(scope.PURE_FILES))],
                            SECOND: [next(iter(scope.PURE_FILES))]})
        result = self.select(api, git)
        self.assertEqual({"legacy": False, "group": True, "cloud": True, "file": True}, result.required)
        self.assertEqual(FIRST, result.baselines["group"].source_sha)
        self.assertEqual([(FIRST, HEAD)], git.compared)
        query = api.calls[0][1]
        self.assertEqual("completed", query["status"])
        self.assertEqual(BRANCH, query["branch"])
        self.assertEqual("push", query["event"])

    def test_a_successful_large_job_in_an_overall_failed_run_is_valid_evidence(self):
        proven = run_evidence(17, FIRST, run_attempt=2, conclusion="failure")
        api = SyntheticApi([proven], {(17, 2): [job_evidence(proven, kind) for kind in scope.PROOF_JOBS]})
        result = self.select(api)
        self.assertEqual(dict.fromkeys(scope.PROOF_JOBS, False), result.required)
        self.assertTrue(all(baseline.attempt == 2 for baseline in result.baselines.values()))
        self.assertTrue(any("/attempts/2/jobs" in path for path, _ in api.calls))

    def test_each_large_job_keeps_its_own_successful_source_baseline(self):
        file = run_evidence(24, FOURTH)
        cloud = run_evidence(23, THIRD)
        group = run_evidence(22, SECOND)
        legacy = run_evidence(21, FIRST)
        api = SyntheticApi([file, cloud, group, legacy], {
            (24, 1): [job_evidence(file, "file")],
            (23, 1): [job_evidence(cloud, "cloud")],
            (22, 1): [job_evidence(group, "group")],
            (21, 1): [job_evidence(legacy, "legacy")],
        })
        git = SyntheticGit({FIRST: [scope.LAN + "LanSyncRuntime.kt"],
                            SECOND: [next(iter(scope.PURE_FILES))],
                            THIRD: [scope.STORE + "SyncStreamingCipher.kt"],
                            FOURTH: [scope.LAN + "LanSyncGroupCloudSchedule.kt"]})
        result = self.select(api, git)
        self.assertEqual({"legacy": True, "group": False, "cloud": True, "file": False}, result.required)
        self.assertEqual({"legacy": FIRST, "group": SECOND, "cloud": THIRD, "file": FOURTH},
                         {kind: baseline.source_sha for kind, baseline in result.baselines.items()})

    def test_missing_cloud_history_never_reuses_lan_or_group_evidence_for_cloud(self):
        proven = run_evidence(31, FIRST)
        jobs = [job_evidence(proven, kind) for kind in ("legacy", "group", "file")]
        result = self.select(SyntheticApi([proven], {(31, 1): jobs}))
        self.assertEqual({"legacy": False, "group": False, "cloud": True, "file": False}, result.required)
        self.assertNotIn("cloud", result.baselines)
        empty = self.select(SyntheticApi())
        self.assertEqual(dict.fromkeys(scope.PROOF_JOBS, True), empty.required)
        self.assertFalse(empty.baselines)

    def test_missing_file_history_never_reuses_legacy_group_or_cloud_evidence_for_file(self):
        proven = run_evidence(32, FIRST)
        jobs = [job_evidence(proven, kind) for kind in ("legacy", "group", "cloud")]
        result = self.select(SyntheticApi([proven], {(32, 1): jobs}))
        self.assertEqual({"legacy": False, "group": False, "cloud": False, "file": True}, result.required)
        self.assertNotIn("file", result.baselines)

    def test_failed_file_then_narrow_followup_uses_its_last_exact_successful_step(self):
        failed = run_evidence(34, SECOND)
        proven = run_evidence(33, FIRST)
        api = SyntheticApi([failed, proven], {
            (34, 1): [job_evidence(failed, kind) for kind in ("legacy", "group", "cloud")]
                     + [job_evidence(failed, "file", conclusion="failure")],
            (33, 1): [job_evidence(proven, "file")],
        })
        git = SyntheticGit({FIRST: [scope.LAN + "LanSyncGroupEncryptedFilePackage.kt"],
                            SECOND: [next(iter(scope.PURE_FILES))]})
        result = self.select(api, git)
        self.assertEqual({"legacy": False, "group": False, "cloud": False, "file": True}, result.required)
        self.assertEqual(FIRST, result.baselines["file"].source_sha)
        self.assertCountEqual([(SECOND, HEAD), (FIRST, HEAD)], git.compared)

    def test_nonancestor_foreign_branch_repository_and_workflow_sources_cannot_supply_evidence(self):
        proven = run_evidence(41, FIRST)
        rejected = [run_evidence(45, "e" * 40), run_evidence(44, SECOND, head_branch="codex/sdk-control"),
                    run_evidence(43, SECOND, head_repository={"full_name": "synthetic/other"}),
                    run_evidence(42, SECOND, path=".github/workflows/control.yml")]
        api = SyntheticApi([*rejected, proven], {(41, 1): [job_evidence(proven, kind) for kind in scope.PROOF_JOBS]})
        result = self.select(api)
        self.assertEqual(dict.fromkeys(scope.PROOF_JOBS, False), result.required)
        self.assertEqual({41}, {baseline.run_id for baseline in result.baselines.values()})
        self.assertEqual(2, len(api.calls))

    def test_success_without_a_successful_exact_proof_step_or_exact_job_source_stays_unproven(self):
        proven = run_evidence(51, FIRST)
        for kind, expected in scope.PROOF_JOBS.items():
            mutations = [
                {"conclusion": "skipped"}, {"head_sha": SECOND}, {"head_branch": "codex/other"}, {"run_id": 52},
                {"steps": []},
                {"steps": [{"name": expected.step, "status": "completed", "conclusion": "skipped"}]},
                {"steps": [{"name": "different proof", "status": "completed", "conclusion": "success"}]},
                {"steps": [{"name": expected.step, "status": "completed", "conclusion": "success"}] * 2},
            ]
            for mutation in mutations:
                with self.subTest(kind=kind, mutation=mutation):
                    jobs = [job_evidence(proven, other) for other in scope.PROOF_JOBS if other != kind]
                    jobs.append(job_evidence(proven, kind, **mutation))
                    result = self.select(SyntheticApi([proven], {(51, 1): jobs}))
                    self.assertTrue(result.required[kind])
                    self.assertNotIn(kind, result.baselines)
                    self.assertEqual(set(scope.PROOF_JOBS) - {kind}, set(result.baselines))

    def test_file_job_contract_has_its_own_exact_step_environment_artifact_and_scope_output(self):
        workflow = (Path(__file__).resolve().parents[3] / scope.WORKFLOW_PATH).read_text(encoding="utf-8")
        expected = scope.PROOF_JOBS["file"]
        match = re.search(r"^  " + re.escape(expected.name) + r":\n(.*?)(?=^  [A-Za-z][A-Za-z0-9_-]*:|\Z)",
                          workflow, re.MULTILINE | re.DOTALL)
        self.assertIsNotNone(match)
        job = match.group(1)
        for required in (
            "needs: proof_scope", "if: needs.proof_scope.outputs.file_large == 'true'", "timeout-minutes: 60",
            "CCB_GROUP_FILE_LARGE_PROOF: 'true'", "- name: " + expected.step,
            "--tests 'com.example.chatbar.domain.syncStoreProof.SyncGroupFileLargeNativeProofTest'",
            "name: sync-group-file-large-resource-evidence",
        ):
            self.assertIn(required, job)
        self.assertIn("file_large: ${{ steps.proof_scope.outputs.file_large }}", workflow)

    def test_large_jobs_depend_only_on_the_fast_scope_job_and_android_focus_keeps_fixed_compile_and_unit_commands(self):
        workflow = (Path(__file__).resolve().parents[3] / scope.WORKFLOW_PATH).read_text(encoding="utf-8")
        def job(name):
            match = re.search(r"^  " + re.escape(name) + r":\n(.*?)(?=^  [A-Za-z][A-Za-z0-9_-]*:|\Z)",
                              workflow, re.MULTILINE | re.DOTALL)
            self.assertIsNotNone(match)
            return match.group(1)
        for kind, expected in scope.PROOF_JOBS.items():
            body = job(expected.name)
            self.assertIn("    needs: proof_scope\n", body)
            self.assertIn("if: needs.proof_scope.outputs." + kind + "_large == 'true'", body)
            self.assertNotIn("needs: android", body)
        fast = job("proof_scope")
        self.assertIn("actions: read", fast)
        self.assertIn("fetch-depth: 0", fast)
        self.assertIn("python -m unittest discover -s tools/lan-sync/tests -p test_proof_scope.py", fast)
        self.assertIn("android_mode: ${{ steps.proof_scope.outputs.android_mode }}", fast)
        android = job("android")
        self.assertIn("name: Android (${{ needs.proof_scope.outputs.android_mode }})", android)
        self.assertIn("FOCUS FileRecoveryOwnerTest only", android)
        self.assertIn("prior full suite remains failed", android)
        self.assertIn("--tests 'com.example.chatbar.domain.lanSync.FileRecoveryOwnerTest'", android)
        for command in (":app:compileLanPreviewKotlin", ":app:assembleLanPreview", ":app:compileLanPreviewAndroidTestKotlin",
                        ":app:testLanPreviewUnitTest --tests 'com.example.chatbar.lanSync.AndroidLanSyncCloudWorkTest' -PlanSyncDeviceTests=true"):
            self.assertEqual(2, android.count(command), command)
        self.assertNotRegex(android, r"--tests\s+['\"]?\$[({]")

    def test_cloud_and_file_live_logging_is_exactly_scoped_and_preserves_failure_and_timeout_contracts(self):
        workflow = (Path(__file__).resolve().parents[3] / scope.WORKFLOW_PATH).read_text(encoding="utf-8")
        def job(name):
            match = re.search(r"^  " + re.escape(name) + r":\n(.*?)(?=^  [A-Za-z][A-Za-z0-9_-]*:|\Z)",
                              workflow, re.MULTILINE | re.DOTALL)
            self.assertIsNotNone(match)
            return match.group(1)
        expected_init = ("          gradle.projectsEvaluated {\n"
                         "            gradle.rootProject.project(':sharedCore').tasks.named('test', org.gradle.api.tasks.testing.Test) {\n"
                         "              testLogging.showStandardStreams = true\n"
                         "            }\n"
                         "          }\n")
        for kind, minutes, case in (("cloud", 120, "SyncGroupCloudLargeNativeProofTest"),
                                    ("file", 60, "SyncGroupFileLargeNativeProofTest")):
            with self.subTest(kind=kind):
                body = job(scope.PROOF_JOBS[kind].name)
                self.assertIn("    timeout-minutes: " + str(minutes) + "\n", body)
                self.assertIn("CCB_GROUP_" + kind.upper() + "_LARGE_PROOF: 'true'", body)
                self.assertIn('proof_logging_init="$RUNNER_TEMP/ccb-large-proof-logging.init.gradle"', body)
                init = re.search(r'cat > "\$proof_logging_init" <<\x27GROOVY\x27\n(.*?)^          GROOVY$',
                                 body, re.MULTILINE | re.DOTALL)
                self.assertIsNotNone(init)
                self.assertEqual(expected_init, init.group(1))
                command = ('./gradlew :sharedCore:test --init-script "$proof_logging_init" --tests '
                           "'com.example.chatbar.domain.syncStoreProof." + case + "' --no-daemon --stacktrace")
                self.assertIn(command, body)
                self.assertEqual(1, body.count("./gradlew :sharedCore:test"))
                self.assertNotRegex(body, r"ignoreFailures|continue-on-error|--dry-run|--test-dry-run|\|\|\s*true")
        for kind in ("legacy", "group"):
            body = job(scope.PROOF_JOBS[kind].name)
            self.assertIn("    timeout-minutes: 45\n", body)
            self.assertNotIn("proof_logging_init", body)
            self.assertNotIn("--init-script", body)

    def test_api_failures_are_full_proof_and_do_not_echo_exception_secrets_or_urls(self):
        api = SyntheticApi()
        api.failure = RuntimeError("Bearer synthetic-token https://synthetic.invalid/?password=synthetic-password")
        result = self.select(api)
        self.assertEqual(dict.fromkeys(scope.PROOF_JOBS, True), result.required)
        self.assertEqual("PROOF_EVIDENCE_UNAVAILABLE", result.diagnostic)
        self.assertNotIn("synthetic-token", str(result))
        self.assertNotIn("synthetic-password", str(result))
        self.assertNotIn("https:", str(result))

    def test_truncated_or_duplicate_run_pages_and_truncated_jobs_cannot_claim_reuse(self):
        proven = run_evidence(61, FIRST)
        for response in ({"total_count": 2, "workflow_runs": [proven]},
                         {"total_count": 2, "workflow_runs": [proven, proven]}):
            with self.subTest(response=response):
                api = SyntheticApi()
                api.override = lambda path, query, value=response: value
                result = self.select(api)
                self.assertEqual(dict.fromkeys(scope.PROOF_JOBS, True), result.required)
                self.assertFalse(result.baselines)
        jobs = [job_evidence(proven, "cloud")]
        jobs += [{"id": 1000 + index, "name": "unrelated"} for index in range(200)]
        result = self.select(SyntheticApi([proven], {(61, 1): jobs}))
        self.assertEqual(dict.fromkeys(scope.PROOF_JOBS, True), result.required)
        self.assertEqual("PROOF_JOB_LIST_TRUNCATED", result.diagnostic)

    def test_history_window_exhaustion_keeps_every_unproven_kind_full(self):
        proven = run_evidence(75, FIRST)
        old = run_evidence(74, SECOND)
        api = SyntheticApi([proven, old], {(75, 1): [job_evidence(proven, "legacy"), job_evidence(proven, "group")],
                                          (74, 1): [job_evidence(old, "cloud")]})
        with patch.object(scope, "MAX_CANDIDATE_RUNS", 1):
            result = self.select(api)
        self.assertEqual({"legacy": False, "group": False, "cloud": True, "file": True}, result.required)
        self.assertEqual("PROOF_HISTORY_WINDOW_EXHAUSTED", result.diagnostic)
        self.assertNotIn("cloud", result.baselines)

    def test_run_page_limit_never_promotes_a_baseline_outside_the_read_window(self):
        recent = run_evidence(93, FIRST)
        empty = run_evidence(92, SECOND)
        outside = run_evidence(91, THIRD)
        api = SyntheticApi([recent, empty, outside], {
            (93, 1): [job_evidence(recent, "legacy"), job_evidence(recent, "group")],
            (91, 1): [job_evidence(outside, "cloud")],
        })
        with patch.object(scope, "PAGE_SIZE", 2), patch.object(scope, "MAX_RUN_PAGES", 1):
            result = self.select(api)
        self.assertEqual({"legacy": False, "group": False, "cloud": True, "file": True}, result.required)
        self.assertEqual("PROOF_HISTORY_WINDOW_EXHAUSTED", result.diagnostic)
        self.assertFalse(any("/runs/91/" in path for path, _ in api.calls))

    def test_an_unavailable_changed_path_set_invalidates_all_reuse_instead_of_comparing_event_before(self):
        proven = run_evidence(81, FIRST)
        api = SyntheticApi([proven], {(81, 1): [job_evidence(proven, kind) for kind in scope.PROOF_JOBS]})
        git = SyntheticGit({FIRST: scope.EvidenceUnavailable("PROOF_CHANGED_PATHS_UNAVAILABLE")})
        result = self.select(api, git)
        self.assertEqual(dict.fromkeys(scope.PROOF_JOBS, True), result.required)
        self.assertFalse(result.baselines)

    def test_local_diff_keeps_deleted_io_when_a_rename_destination_is_pure_ui(self):
        old = scope.LAN + "LanSyncGroupNativePlanner.kt"
        new = next(iter(scope.PURE_FILES))
        raw = (old + "\0" + new + "\0").encode()
        with patch.object(scope.GitEvidence, "_run", return_value=SimpleNamespace(returncode=0, stdout=raw)) as command:
            paths = scope.GitEvidence().paths(FIRST, HEAD)
        self.assertIn("--no-renames", command.call_args.args[0])
        self.assertEqual((False, True), scope.classify(paths))
        self.assertTrue(scope.classify_cloud(paths))

    def test_truncated_oversized_diff_and_wrong_checkout_fail_closed(self):
        for raw in (b"unterminated", b"12345678\0"):
            with self.subTest(raw=raw), patch.object(scope, "MAX_DIFF_BYTES", 8), \
                    patch.object(scope.GitEvidence, "_run", return_value=SimpleNamespace(returncode=0, stdout=raw)):
                with self.assertRaises(scope.EvidenceUnavailable):
                    scope.GitEvidence().paths(FIRST, HEAD)
        result = scope.select_scope(SyntheticApi(), SyntheticGit(), REPOSITORY, BRANCH, FIRST, 999)
        self.assertEqual(dict.fromkeys(scope.PROOF_JOBS, True), result.required)
        self.assertEqual("PROOF_CHECKOUT_HEAD_MISMATCH", result.diagnostic)

    def test_api_reader_has_a_fixed_redacted_failure_and_never_follows_redirects(self):
        api = scope.GitHubEvidenceApi("https://api.synthetic.invalid", "synthetic-token")
        class BrokenOpener:
            def open(self, request, timeout):
                raise OSError("synthetic-token https://synthetic.invalid/credential")
        api.opener = BrokenOpener()
        with self.assertRaises(scope.EvidenceUnavailable) as raised:
            api.get("/repos/synthetic/ccb/actions/workflows/lan-sync-ci.yml/runs", {})
        self.assertEqual("PROOF_API_UNAVAILABLE", str(raised.exception))
        self.assertIsNone(scope._NoRedirect().redirect_request(None, None, 302, "redirect", {}, "https://other.synthetic.invalid/"))

        body = io.BytesIO(b"synthetic denied response")
        class DeniedOpener:
            def open(self, request, timeout):
                raise scope.HTTPError("https://api.synthetic.invalid/private", 403, "denied", {}, body)
        api.opener = DeniedOpener()
        with self.assertRaises(scope.EvidenceUnavailable) as raised:
            api.get("/metadata", {})
        self.assertEqual("PROOF_API_UNAVAILABLE", str(raised.exception))
        self.assertTrue(body.closed)

    def test_api_reader_refuses_oversized_responses_and_an_exhausted_request_budget(self):
        api = scope.GitHubEvidenceApi("https://api.synthetic.invalid", "synthetic-token")
        class OversizedResponse:
            status = 200
            def __enter__(self): return self
            def __exit__(self, *unused): pass
            def read(self, maximum): return b"x" * maximum
        api.opener = SimpleNamespace(open=lambda request, timeout: OversizedResponse())
        with patch.object(scope, "MAX_API_BYTES", 8):
            with self.assertRaises(scope.EvidenceUnavailable) as raised:
                api.get("/metadata", {})
        self.assertEqual("PROOF_API_RESPONSE_TRUNCATED", str(raised.exception))
        api.requests = scope.MAX_API_REQUESTS
        with self.assertRaises(scope.EvidenceUnavailable) as raised:
            api.get("/metadata", {})
        self.assertEqual("PROOF_API_BUDGET_EXHAUSTED", str(raised.exception))


if __name__ == "__main__":
    unittest.main()
