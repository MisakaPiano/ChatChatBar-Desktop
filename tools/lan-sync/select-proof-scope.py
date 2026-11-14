"""Compare each byte proof with its own successful, ancestral Actions evidence."""
from __future__ import annotations

import json
import hashlib
import os
from pathlib import Path
import re
import subprocess
import time
from typing import Callable, NamedTuple
from urllib.error import HTTPError
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

LAN = "app/sharedCore/src/main/kotlin/com/example/chatbar/domain/lanSync/"
STORE = "app/sharedCore/src/main/kotlin/com/example/chatbar/domain/syncStore/"
CLOUD_LAN = {
    "LanSyncGroupCloudRuntime.kt", "LanSyncGroupCloudSchedule.kt", "LanSyncGroupCloudRoutes.kt",
    "LanSyncGroupCloudSettingsState.kt", "LanSyncGroupCloudTargetReceipt.kt", "LanSyncGroupTargetExport.kt", "LanSyncGroupTargetPublication.kt",
    "LanSyncGroupPartialTargetExport.kt", "LanSyncGroupPartialTargetPublication.kt",
    "LanSyncGroupChunkCloudReadRoute.kt", "LanSyncGroupWeakCloudReadRoute.kt",
    "LanSyncGroupCloudRecoverySource.kt", "LanSyncGroupCloudRecoveryOwner.kt", "LanSyncGroupCoordinator.kt",
}
CLOUD_STORE = {
    "webdav/WeakWebDavAttemptStore.kt", "webdav/WeakWebDavChunkAssembly.kt", "webdav/WeakWebDavXmlListing.kt",
    "webdav/WeakWebDavIncomingChunkCache.kt", "webdav/WebDavCloudClient.kt", "webdav/WebDavConnectOnlyOperation.kt",
    "webdav/WebDavCiphertextStore.kt", "webdav/WebDavHttpTransport.kt", "webdav/WebDavStoreRecord.kt", "webdav/WebDavXmlListing.kt",
}
FILE_LAN = {
    "LanSyncGroupEncryptedFilePackage.kt", "LanSyncGroupEncryptedFileExportOwner.kt",
    "LanSyncGroupFileRecoverySource.kt", "LanSyncGroupFileRecoveryOwner.kt",
}
# These shared callers/input owners traverse both cloud and file proof paths. Other
# CLOUD_LAN entries exercise cloud delivery/recovery rather than the encrypted-file path.
FILE_SHARED_LAN = {
    "LanSyncGroupCoordinator.kt", "LanSyncGroupRuntimeAccess.kt", "LanSyncGroupRecoveryInput.kt",
    "RecoveryControlSnapshot.kt", "LanSyncGroupTargetExport.kt", "LanSyncGroupTargetPublication.kt",
}
# The older group proof composes its lower-level owners directly. These exact app/group
# owners belong to the newer cloud/file paths, not to that proof or the legacy Runtime.
CLOUD_FILE_OWNERS = {
    "LanSyncGroupEnrollment.kt", "LanSyncGroupInvitation.kt", "LanSyncGroupNode.kt",
    "LanSyncGroupRootPublication.kt", "LanSyncGroupJoining.kt", "LanSyncGroupManagementRecovery.kt",
    "LanSyncGroupRecoveryDeviceProof.kt", "LanSyncGroupResolution.kt", "LanSyncGroupResolutionOwner.kt",
    "LanSyncGroupIncomingReview.kt", "LanSyncGroupRuntimeAccess.kt", "LanSyncGroupRecoveryInput.kt",
    "RecoveryControlSnapshot.kt", "LanSyncGroupManagementBackup.kt",
}
GROUP_CONTROL_STORE = {"SyncManagementVault.kt", "SyncRootGroupCreation.kt", "SyncRootLedgerJournal.kt"}
# These app callers are compiled/checked by their native jobs; neither old large shared proof runs them.
APP_CONTROLS = {
    "app/app/src/main/java/com/example/chatbar/lanSync/" + name for name in (
        "AndroidLanSync.kt", "AndroidLanSyncCloudWork.kt", "AndroidLanSyncLifecycle.kt",
        "LanSyncGroupForegroundService.kt", "LanSyncGroupCloudTransferService.kt", "LanSyncActivity.kt",
        "LanSyncGroupCloudSettings.kt", "LanSyncGroupCloudRecovery.kt", "LanSyncGroupFileActions.kt")
} | {
    "app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/lanSync/" + name for name in (
        "DesktopLanSync.kt", "DesktopLanSyncPanel.kt", "DesktopLanSyncCloudSettings.kt", "DesktopLanSyncCloudRecovery.kt",
        "DesktopLanSyncFileActions.kt")
} | {
    "app/app/src/androidTest/java/com/example/chatbar/lanSync/LanSyncGroupFileForegroundTest.kt",
    "app/app/src/androidTest/java/com/example/chatbar/lanSync/LanSyncGroupCloudForegroundTest.kt",
    "app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/Main.kt",
    "app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/DesktopPrimaryShell.kt",
    "app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/DesktopDataWorkspace.kt",
}
COMMON_IO = {
    "LanSyncBlobs.kt", "LanSyncFramedJson.kt", "LanSyncGraphRemapper.kt", "LanSyncIdentity.kt",
    "LanSyncImpactReview.kt", "LanSyncJsonFiles.kt", "LanSyncMetadataBudget.kt", "LanSyncModel.kt",
    "LanSyncOperationGate.kt", "LanSyncPlan.kt", "LanSyncPostProcess.kt", "LanSyncProjection.kt",
    "LanSyncRecoveryRestore.kt", "LanSyncRecoveryVault.kt", "LanSyncResourceBudget.kt", "LanSyncResourceStore.kt",
    "LanSyncScope.kt", "LanSyncScratch.kt", "LanSyncSlotPackages.kt", "LanSyncStateStore.kt",
    "LanSyncStudioCurrent.kt", "LanSyncTls.kt", "LanSyncVibePayloads.kt", "LanSyncWorkspace.kt",
}
LEGACY_IO = {
    "LanSyncRuntime.kt", "LanSyncTransfer.kt", "LanSyncRebase.kt", "LanSyncPartialReview.kt",
    "LanSyncNetwork.kt", "LanSyncDiscovery.kt", "LanSyncMoments.kt", "LanSyncMemoryProofs.kt",
}
GROUP_IO = {
    "LanSyncGroupBlobReuse.kt",
    "LanSyncGroupCiphertextTransport.kt", "LanSyncGroupDependencies.kt", "LanSyncGroupEnrollment.kt",
    "LanSyncGroupIncomingReview.kt", "LanSyncGroupInvitation.kt", "LanSyncGroupMembership.kt",
    "LanSyncGroupNativeApplyOwner.kt", "LanSyncGroupNativePlanner.kt", "LanSyncGroupNode.kt",
    "LanSyncGroupPeerStore.kt", "LanSyncGroupPublicationQueue.kt", "LanSyncGroupRecordEvidence.kt",
    "LanSyncGroupResolution.kt", "LanSyncGroupResolutionOwner.kt", "LanSyncGroupRootPublication.kt",
    "LanSyncGroupSourceReview.kt", "LanSyncGroupSourceWriter.kt", "LanSyncGroupUnitCodec.kt",
    "LanSyncGroupJoining.kt", "LanSyncGroupManagementRecovery.kt", "LanSyncGroupRecoveryDeviceProof.kt",
}
STORE_IO = {
    "CiphertextStore.kt", "FileCiphertextStore.kt", "SyncAuthorJournal.kt", "SyncCausalHistory.kt", "SyncCiphertextCandidates.kt",
    "SyncCiphertextHeader.kt", "SyncDeliveryTarget.kt", "SyncDeviceEncryptionKey.kt", "SyncDeviceKeyCustody.kt",
    "SyncDeviceLedger.kt", "SyncDeviceSignatures.kt", "SyncEncryptedOutbox.kt", "SyncGroupBlobStore.kt",
    "SyncGroupCustody.kt", "SyncGroupInbox.kt", "SyncGroupKeyDelivery.kt", "SyncGroupObjectCodec.kt",
    "SyncKeyDeliveryQueue.kt", "SyncLedgerNotice.kt", "SyncLedgerNoticeQueue.kt", "SyncManagementVault.kt",
    "SyncRootGroupCreation.kt", "SyncRootLedgerJournal.kt", "SyncStreamingCipher.kt",
    "webdav/WebDavCiphertextStore.kt", "webdav/WebDavHttpTransport.kt", "webdav/WebDavStoreRecord.kt", "webdav/WebDavXmlListing.kt",
}
PURE_FILES = {
    LAN + "LanSyncGroupPanelState.kt", LAN + "LanSyncDetailedReview.kt", LAN + "LanSyncUiText.kt",
    "app/app/src/main/java/com/example/chatbar/lanSync/LanSyncGroupPanel.kt",
    "app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/lanSync/DesktopLanSyncGroupPanel.kt",
    "app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/lanSync/DesktopLanSyncSensitiveClipboard.kt",
    "app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/DesktopManagePanel.kt",
}
PURE_PREFIXES = (
    "app/sharedCore/src/test/kotlin/com/example/chatbar/domain/lanSync/",
    "app/sharedCore/src/test/kotlin/com/example/chatbar/domain/syncStore/",
    "app/desktopApp/src/test/kotlin/",
)
LEGACY_PROOF = "app/sharedCore/src/test/kotlin/com/example/chatbar/domain/lanSyncProof/"
GROUP_PROOF = "app/sharedCore/src/test/kotlin/com/example/chatbar/domain/syncStoreProof/"
CLOUD_PROOF_NAMES = {"CloudFileDav.kt", "SyncGroupCloudLargeNativeProofTest.kt"}
FILE_PROOF_NAMES = {"LargeGroupFileSyntheticInputs.kt", "SyncGroupFileLargeNativeProofTest.kt"}
GROUP_PROOF_NAMES = {"SyncGroupLargeNativeProofTest.kt"}
# Exact executable fixture dependencies, before the ordinary-test prefix exemption.
# Tuple order is legacy, group-TLS, cloud, file; the recovery proofs share Administration.Fixture.
LARGE_PROOF_HELPERS = {
    "app/sharedCore/src/test/kotlin/com/example/chatbar/domain/lanSync/LanSyncRuntimeTest.kt": (True, False, False, False),
    "app/sharedCore/src/test/kotlin/com/example/chatbar/domain/lanSync/LanSyncGroupLoopbackTransport.kt": (False, True, False, False),
    "app/sharedCore/src/test/kotlin/com/example/chatbar/domain/lanSync/LanSyncGroupAdministrationTest.kt": (False, False, True, True),
}


def classify(paths: list[str]) -> tuple[bool, bool]:
    legacy = group = False
    for path in paths:
        helper = LARGE_PROOF_HELPERS.get(path)
        if helper is not None:
            legacy = legacy or helper[0]
            group = group or helper[1]
            continue
        if path in APP_CONTROLS or path.startswith("app/app/src/test/java/com/example/chatbar/lanSync/"):
            continue
        if path in PURE_FILES or path.startswith(PURE_PREFIXES) or path.startswith(".agents/skills/") and path.endswith(".md"):
            continue
        if path.startswith(LAN) and path[len(LAN):] in FILE_LAN:
            continue
        if (path.startswith(LAN) and path[len(LAN):] in CLOUD_FILE_OWNERS
                or path.startswith(STORE) and path[len(STORE):] in GROUP_CONTROL_STORE):
            continue
        if path.startswith(LAN) and path[len(LAN):] in CLOUD_LAN or path.startswith(STORE) and path[len(STORE):] in CLOUD_STORE:
            continue
        if path.startswith(LEGACY_PROOF):
            legacy = True
        elif path.startswith(GROUP_PROOF):
            if path[len(GROUP_PROOF):] not in CLOUD_PROOF_NAMES | FILE_PROOF_NAMES:
                group = True
        elif path.startswith(LAN) and path[len(LAN):] in COMMON_IO:
            legacy = group = True
        elif path.startswith(LAN) and path[len(LAN):] in LEGACY_IO:
            legacy = True
        elif path.startswith(LAN) and path[len(LAN):] in GROUP_IO:
            group = True
        elif path.startswith(STORE) and path[len(STORE):] in STORE_IO:
            group = True
        else:
            # New/renamed/deleted unknown paths never silently opt out of byte proofs.
            legacy = group = True
    return legacy, group


def classify_cloud(paths: list[str]) -> bool:
    for path in paths:
        helper = LARGE_PROOF_HELPERS.get(path)
        if helper is not None:
            if helper[2]:
                return True
            continue
        if path.startswith(GROUP_PROOF) and path[len(GROUP_PROOF):] in FILE_PROOF_NAMES:
            continue
        if path.startswith(LAN) and path[len(LAN):] in FILE_LAN:
            continue
        if (path.startswith(LAN) and path[len(LAN):] in CLOUD_FILE_OWNERS
                or path.startswith(STORE) and path[len(STORE):] in GROUP_CONTROL_STORE):
            return True
        if path.startswith(GROUP_PROOF) and path[len(GROUP_PROOF):] in CLOUD_PROOF_NAMES:
            return True
        if path.startswith(LAN) and path[len(LAN):] in CLOUD_LAN or path.startswith(STORE) and path[len(STORE):] in CLOUD_STORE:
            return True
        if path in APP_CONTROLS:
            continue
        # The cloud proof also traverses the shared group crypto/native byte path.
        if classify([path])[1]:
            return True
    return False


def classify_file(paths: list[str]) -> bool:
    for path in paths:
        helper = LARGE_PROOF_HELPERS.get(path)
        if helper is not None:
            if helper[3]:
                return True
            continue
        if path.startswith(GROUP_PROOF) and path[len(GROUP_PROOF):] in FILE_PROOF_NAMES:
            return True
        if (path.startswith(LAN) and path[len(LAN):] in FILE_LAN | FILE_SHARED_LAN | CLOUD_FILE_OWNERS
                or path.startswith(STORE) and path[len(STORE):] in GROUP_CONTROL_STORE):
            return True
        if (path.startswith(LEGACY_PROOF)
                or path.startswith(GROUP_PROOF) and path[len(GROUP_PROOF):] in CLOUD_PROOF_NAMES | GROUP_PROOF_NAMES
                or path.startswith(LAN) and path[len(LAN):] in CLOUD_LAN
                or path.startswith(STORE) and path[len(STORE):] in CLOUD_STORE
                or path in APP_CONTROLS):
            continue
        # File recovery shares group crypto, membership, source export and native apply.
        # Unknown paths remain conservative through the existing group classifier.
        if classify([path])[1]:
            return True
    return False


class ProofJob(NamedTuple):
    name: str
    step: str


PROOF_JOBS = {
    "legacy": ProofJob("large-resources", "Fully written large Runtime and v8 proof"),
    "group": ProofJob("group-large-resources", "Fully written encrypted group and native v8 proof"),
    "cloud": ProofJob("cloud-large-resources", "Fully written cloud chunk resume and native activation proof"),
    "file": ProofJob("file-large-resources", "Fully written encrypted file recovery and native activation proof"),
}
WORKFLOW_PATH = ".github/workflows/lan-sync-ci.yml"
SCOPE_SCRIPT = "tools/lan-sync/select-proof-scope.py"
SCOPE_TEST = "tools/lan-sync/tests/test_proof_scope.py"
FOCUS_REQUEST = ".github/lan-sync-ci-focus.json"
CI_CONTROL_FILES = {WORKFLOW_PATH, SCOPE_SCRIPT, SCOPE_TEST, FOCUS_REQUEST}
ANDROID_BUILD_PATH = "app/app/build.gradle.kts"
CONTRACT_CHECKED_FILES = CI_CONTROL_FILES | {ANDROID_BUILD_PATH}
ANDROID_FOCUS_BASELINE = "78d399e037ae5b4f6a88e722261caba8d96ba146"
ANDROID_FOCUS_MODE = "file-recovery-owner"
ANDROID_FOCUS_FILES = CI_CONTROL_FILES | {
    LAN + "LanSyncGroupFileRecoveryOwner.kt",
    "app/sharedCore/src/test/kotlin/com/example/chatbar/domain/lanSync/FileRecoveryOwnerTest.kt",
}
MAX_SOURCE_BYTES = 1024 * 1024
PAGE_SIZE = 100
MAX_RUN_PAGES = 3
MAX_JOB_PAGES = 2
MAX_CANDIDATE_RUNS = 60
MAX_API_REQUESTS = 80
MAX_API_BYTES = 4 * 1024 * 1024
MAX_DIFF_BYTES = 4 * 1024 * 1024


class EvidenceUnavailable(Exception):
    """Only fixed codes cross the CLI boundary; HTTP exceptions may contain credentials/URLs."""


class EvidenceBaseline(NamedTuple):
    run_id: int
    source_sha: str
    job_id: int
    attempt: int


class ProofScope(NamedTuple):
    required: dict[str, bool]
    baselines: dict[str, EvidenceBaseline]
    diagnostic: str | None = None
    verified_controls: tuple[str, ...] = ()
    changed_contracts: tuple[str, ...] = ()


class AndroidScope(NamedTuple):
    mode: str
    diagnostic: str


def workflow_proof_contract(source: str, kind: str) -> str:
    """Narrow parser for this workflow; unknown shared runtime structure fails closed."""
    if "\t" in source or re.search(r"(?:^|[\s:\[,])[&*][A-Za-z_][\w-]*(?=[\s\],#]|$)", source):
        raise EvidenceUnavailable("PROOF_WORKFLOW_STRUCTURE_UNAVAILABLE")
    lines = source.splitlines()
    starts = [index for index, line in enumerate(lines) if line == "jobs:"]
    if len(starts) != 1:
        raise EvidenceUnavailable("PROOF_WORKFLOW_STRUCTURE_UNAVAILABLE")
    start = starts[0]
    if any(line and not line.startswith((" ", "#")) and not re.match(r"^[A-Za-z][\w-]*:", line)
           for line in lines[:start]):
        raise EvidenceUnavailable("PROOF_WORKFLOW_STRUCTURE_UNAVAILABLE")
    jobs: dict[str, list[str]] = {}
    active = None
    for line in lines[start + 1:]:
        match = re.fullmatch(r"  ([A-Za-z][\w-]*):", line)
        if match:
            active = match.group(1)
            if active in jobs:
                raise EvidenceUnavailable("PROOF_WORKFLOW_STRUCTURE_UNAVAILABLE")
            jobs[active] = []
        elif line.strip() and not line.lstrip().startswith("#") and (active is None or not line.startswith("    ")):
            raise EvidenceUnavailable("PROOF_WORKFLOW_STRUCTURE_UNAVAILABLE")
        elif active is not None:
            jobs[active].append(line)
    known = {proof.name for proof in PROOF_JOBS.values()} | {"android", "desktop", "proof_scope"}
    expected = PROOF_JOBS[kind]
    if set(jobs) - known or expected.name not in jobs:
        raise EvidenceUnavailable("PROOF_WORKFLOW_STRUCTURE_UNAVAILABLE")
    body = []
    needs_owner = if_owner = None
    for line in jobs[expected.name]:
        needs_match = re.fullmatch(r"    needs: (android|proof_scope)", line)
        if_match = re.fullmatch(r"    if: needs\.(android|proof_scope)\.outputs\." + kind + r"_large == 'true'", line)
        if needs_match:
            if needs_owner is not None:
                raise EvidenceUnavailable("PROOF_WORKFLOW_STRUCTURE_UNAVAILABLE")
            needs_owner = needs_match.group(1)
        elif if_match:
            if if_owner is not None:
                raise EvidenceUnavailable("PROOF_WORKFLOW_STRUCTURE_UNAVAILABLE")
            if_owner = if_match.group(1)
        else:
            body.append(line)
    if needs_owner is None or needs_owner != if_owner:
        raise EvidenceUnavailable("PROOF_WORKFLOW_STRUCTURE_UNAVAILABLE")
    # Missing unrelated known kinds is allowed: older successful legacy/group runs
    # predate file-large-resources. Only this kind's own complete execution body is reused.
    contract = "\n".join(lines[:start] + ["jobs:", expected.name + ":"] + body).rstrip() + "\n"
    return hashlib.sha256(contract.encode("utf-8")).hexdigest()


def android_build_contract(source: str) -> str:
    # The app project is configured by Gradle even for sharedCore:test. Only the exact
    # isolated native-preview numeric allowlist may differ without changing its contract.
    source = source.replace("\r\n", "\n")
    pattern = (r'(?m)^([ \t]*)require\(lanSyncPreviewSeries == "next" && raw in setOf\('
               r'"[0-9]+"(?:, "[0-9]+")*\)\) \{ "LAN_PREVIEW_SERIES_OR_CODE_INVALID" \}$')
    if len(re.findall(pattern, source)) != 1:
        raise EvidenceUnavailable("PROOF_ANDROID_CONFIG_STRUCTURE_UNAVAILABLE")
    normalized = re.sub(pattern, r'\1require(NATIVE_PREVIEW_CODE_ALLOWLIST)', source)
    return hashlib.sha256(normalized.replace("\r\n", "\n").encode("utf-8")).hexdigest()


def select_android_scope(git, head: str, request: object) -> AndroidScope:
    try:
        if (not isinstance(request, dict) or set(request) != {"schema", "mode", "baseline"}
                or type(request["schema"]) is not int or request["schema"] != 1
                or request["mode"] != ANDROID_FOCUS_MODE or request["baseline"] != ANDROID_FOCUS_BASELINE):
            return AndroidScope("full", "ANDROID_FOCUS_REQUEST_INVALID")
        git.verify_head(head)
        if not git.is_ancestor(ANDROID_FOCUS_BASELINE, head):
            return AndroidScope("full", "ANDROID_FOCUS_BASELINE_UNAVAILABLE")
        paths = git.paths(ANDROID_FOCUS_BASELINE, head)
        if any(path not in ANDROID_FOCUS_FILES and not (path.startswith(".agents/skills/") and path.endswith(".md")) for path in paths):
            return AndroidScope("full", "ANDROID_FOCUS_OUTSIDE_ALLOWED_CHANGE_SCOPE")
        return AndroidScope(ANDROID_FOCUS_MODE, "ANDROID_FOCUS_ONLY_PRIOR_FULL_SUITE_REMAINS_FAILED")
    except Exception:
        return AndroidScope("full", "ANDROID_FOCUS_SCOPE_UNAVAILABLE")


def _positive_int(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value > 0


def _sha(value: object) -> bool:
    return isinstance(value, str) and re.fullmatch("[0-9a-f]{40}", value) is not None and value != "0" * 40


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, file, code, message, headers, new_url):
        # Never forward the bearer token to a redirected origin.
        return None


class GitHubEvidenceApi:
    def __init__(self, base: str, token: str):
        url = urlsplit(base)
        if (url.scheme != "https" or not url.hostname or url.username is not None or url.password is not None
                or url.query or url.fragment or not token):
            raise EvidenceUnavailable("PROOF_API_CONFIGURATION_UNAVAILABLE")
        self.base = base.rstrip("/")
        self.token = token
        self.opener = build_opener(_NoRedirect())
        self.requests = 0
        self.deadline = time.monotonic() + 90

    def get(self, path: str, query: dict[str, object]) -> dict:
        remaining = self.deadline - time.monotonic()
        if self.requests >= MAX_API_REQUESTS or remaining <= 0:
            raise EvidenceUnavailable("PROOF_API_BUDGET_EXHAUSTED")
        self.requests += 1
        request = Request(self.base + path + "?" + urlencode(query), headers={
            "Accept": "application/vnd.github+json",
            "Authorization": "Bearer " + self.token,
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "ccb-lan-sync-proof-scope",
        })
        try:
            with self.opener.open(request, timeout=min(10, remaining)) as response:
                if response.status != 200:
                    raise EvidenceUnavailable("PROOF_API_UNAVAILABLE")
                raw = response.read(MAX_API_BYTES + 1)
            if len(raw) > MAX_API_BYTES:
                raise EvidenceUnavailable("PROOF_API_RESPONSE_TRUNCATED")
            result = json.loads(raw)
            if not isinstance(result, dict):
                raise EvidenceUnavailable("PROOF_API_RESPONSE_INVALID")
            return result
        except EvidenceUnavailable:
            raise
        except HTTPError as failure:
            try:
                failure.close()
            finally:
                raise EvidenceUnavailable("PROOF_API_UNAVAILABLE") from None
        except Exception:
            raise EvidenceUnavailable("PROOF_API_UNAVAILABLE") from None


def _pages(api, path: str, query: dict[str, object], field: str, maximum: int) -> tuple[list[dict], bool]:
    rows: list[dict] = []
    total = None
    ids: set[int] = set()
    for page in range(1, maximum + 1):
        result = api.get(path, dict(query, per_page=PAGE_SIZE, page=page))
        count = result.get("total_count")
        values = result.get(field)
        if (not isinstance(count, int) or isinstance(count, bool) or count < 0 or not isinstance(values, list)
                or len(values) > PAGE_SIZE or total is not None and total != count):
            raise EvidenceUnavailable("PROOF_API_RESPONSE_INVALID")
        total = count
        for value in values:
            if not isinstance(value, dict) or not _positive_int(value.get("id")) or value["id"] in ids:
                raise EvidenceUnavailable("PROOF_API_RESPONSE_INVALID")
            ids.add(value["id"])
            rows.append(value)
        if len(rows) > total or len(values) < PAGE_SIZE and len(rows) != total:
            raise EvidenceUnavailable("PROOF_API_RESPONSE_TRUNCATED")
        if len(rows) == total:
            return rows, True
    return rows, False


def find_baselines(api, repository: str, branch: str, head: str, current_run: int,
                   is_ancestor: Callable[[str, str], bool]) -> tuple[dict[str, EvidenceBaseline], bool]:
    # The list is completed, not success: one large job may have succeeded in an otherwise failed run.
    runs, complete = _pages(api, f"/repos/{repository}/actions/workflows/lan-sync-ci.yml/runs",
                            {"branch": branch, "event": "push", "status": "completed", "exclude_pull_requests": "true"},
                            "workflow_runs", MAX_RUN_PAGES)
    baselines: dict[str, EvidenceBaseline] = {}
    examined = 0
    for run in runs:
        if run["id"] == current_run:
            continue
        source = run.get("head_sha")
        origin = run.get("head_repository")
        if (run.get("status") != "completed" or run.get("event") != "push" or run.get("head_branch") != branch
                or not isinstance(origin, dict) or origin.get("full_name", "").lower() != repository.lower()
                or run.get("path") not in (WORKFLOW_PATH, WORKFLOW_PATH + "@refs/heads/" + branch)
                or not _sha(source) or not _positive_int(run.get("run_attempt"))):
            continue
        if not is_ancestor(source, head):
            continue
        if examined >= MAX_CANDIDATE_RUNS:
            return baselines, False
        examined += 1
        attempt = run["run_attempt"]
        jobs, jobs_complete = _pages(api, f"/repos/{repository}/actions/runs/{run['id']}/attempts/{attempt}/jobs",
                                     {}, "jobs", MAX_JOB_PAGES)
        if not jobs_complete:
            raise EvidenceUnavailable("PROOF_JOB_LIST_TRUNCATED")
        for kind, expected in PROOF_JOBS.items():
            if kind in baselines:
                continue
            matches = [job for job in jobs if job.get("name") == expected.name]
            if len(matches) != 1:
                continue
            job = matches[0]
            steps = job.get("steps")
            if (job.get("run_id") != run["id"] or job.get("head_sha") != source or job.get("head_branch") != branch
                    or job.get("status") != "completed" or job.get("conclusion") != "success" or not isinstance(steps, list)):
                continue
            proof_steps = [step for step in steps if isinstance(step, dict) and step.get("name") == expected.step]
            if (len(proof_steps) == 1 and proof_steps[0].get("status") == "completed"
                    and proof_steps[0].get("conclusion") == "success"):
                baselines[kind] = EvidenceBaseline(run["id"], source, job["id"], attempt)
        if len(baselines) == len(PROOF_JOBS):
            return baselines, True
    return baselines, complete


class GitEvidence:
    @staticmethod
    def _run(arguments: list[str]):
        try:
            return subprocess.run(["git", *arguments], capture_output=True, timeout=30, check=False)
        except Exception:
            raise EvidenceUnavailable("PROOF_GIT_UNAVAILABLE") from None

    def verify_head(self, head: str) -> None:
        result = self._run(["rev-parse", "HEAD"])
        if result.returncode != 0 or result.stdout.decode("ascii", errors="replace").strip() != head:
            raise EvidenceUnavailable("PROOF_CHECKOUT_HEAD_MISMATCH")

    def is_ancestor(self, source: str, head: str) -> bool:
        if self._run(["cat-file", "-e", source + "^{commit}"]).returncode != 0:
            return False  # Missing/force-pushed history is never evidence.
        result = self._run(["merge-base", "--is-ancestor", source, head])
        if result.returncode not in (0, 1):
            raise EvidenceUnavailable("PROOF_ANCESTRY_UNAVAILABLE")
        return result.returncode == 0

    def paths(self, source: str, head: str) -> list[str]:
        # Full local history avoids the REST compare API's file cap. A rename includes both paths.
        result = self._run(["diff", "--no-renames", "--name-only", "-z", source, head, "--"])
        raw = result.stdout
        if result.returncode != 0 or len(raw) > MAX_DIFF_BYTES or raw and not raw.endswith(b"\0"):
            raise EvidenceUnavailable("PROOF_CHANGED_PATHS_UNAVAILABLE")
        return [os.fsdecode(path) for path in raw.split(b"\0") if path]

    def read_file(self, source: str, path: str) -> str:
        if not _sha(source) or path not in {WORKFLOW_PATH, FOCUS_REQUEST, ANDROID_BUILD_PATH}:
            raise EvidenceUnavailable("PROOF_SOURCE_UNAVAILABLE")
        result = self._run(["show", source + ":" + path])
        if result.returncode != 0 or len(result.stdout) > MAX_SOURCE_BYTES:
            raise EvidenceUnavailable("PROOF_SOURCE_UNAVAILABLE")
        try:
            return result.stdout.decode("utf-8")
        except UnicodeError:
            raise EvidenceUnavailable("PROOF_SOURCE_UNAVAILABLE") from None


def select_scope(api, git, repository: str, branch: str, head: str, current_run: int) -> ProofScope:
    required = dict.fromkeys(PROOF_JOBS, True)
    try:
        if (not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository)
                or not branch.startswith("codex/lan-sync-") or not _sha(head) or not _positive_int(current_run)):
            raise EvidenceUnavailable("PROOF_CONTEXT_UNAVAILABLE")
        git.verify_head(head)
        baselines, complete = find_baselines(api, repository, branch, head, current_run, git.is_ancestor)
        changes: dict[str, list[str]] = {}
        workflows: dict[str, str] = {}
        android_builds: dict[str, str] = {}
        verified_controls = []
        changed_contracts = []
        for kind, baseline in baselines.items():
            if baseline.source_sha not in changes:
                changes[baseline.source_sha] = git.paths(baseline.source_sha, head)
            paths = changes[baseline.source_sha]
            if any(path in CONTRACT_CHECKED_FILES for path in paths):
                # CI controls are never blanket-ignored. Compare this proof's complete
                # execution contract at the actual successful ancestor and current HEAD.
                for source in (baseline.source_sha, head):
                    if source not in workflows:
                        workflows[source] = git.read_file(source, WORKFLOW_PATH)
                before = workflow_proof_contract(workflows[baseline.source_sha], kind)
                after = workflow_proof_contract(workflows[head], kind)
                if before != after:
                    required[kind] = True
                    changed_contracts.append(kind)
                    continue
                if ANDROID_BUILD_PATH in paths:
                    for source in (baseline.source_sha, head):
                        if source not in android_builds:
                            android_builds[source] = git.read_file(source, ANDROID_BUILD_PATH)
                    if android_build_contract(android_builds[baseline.source_sha]) != android_build_contract(android_builds[head]):
                        required[kind] = True
                        changed_contracts.append(kind)
                        continue
                verified_controls.append(kind)
                paths = [path for path in paths if path not in CONTRACT_CHECKED_FILES]
            if kind == "cloud":
                required[kind] = classify_cloud(paths)
            elif kind == "file":
                required[kind] = classify_file(paths)
            else:
                required[kind] = classify(paths)[0 if kind == "legacy" else 1]
        # A bounded search may reuse independently verified evidence; every unresolved kind stays full.
        return ProofScope(required, baselines, None if complete else "PROOF_HISTORY_WINDOW_EXHAUSTED",
                          tuple(verified_controls), tuple(changed_contracts))
    except EvidenceUnavailable as failure:
        return ProofScope(dict.fromkeys(PROOF_JOBS, True), {}, str(failure))
    except Exception:
        return ProofScope(dict.fromkeys(PROOF_JOBS, True), {}, "PROOF_EVIDENCE_UNAVAILABLE")


def main() -> None:
    git = GitEvidence()
    head = os.environ.get("GITHUB_SHA", "")
    try:
        api = GitHubEvidenceApi(os.environ.get("GITHUB_API_URL", "https://api.github.com"), os.environ.get("GITHUB_TOKEN", ""))
        result = select_scope(api, git, os.environ.get("GITHUB_REPOSITORY", ""),
                              os.environ.get("GITHUB_REF_NAME", ""), head,
                              int(os.environ.get("GITHUB_RUN_ID", "0")))
    except Exception:
        result = ProofScope(dict.fromkeys(PROOF_JOBS, True), {}, "PROOF_EVIDENCE_UNAVAILABLE")
    if result.diagnostic:
        print(result.diagnostic)
    try:
        request = git.read_file(head, FOCUS_REQUEST)
        android = select_android_scope(git, head, json.loads(request)) if len(request) <= 8192 else AndroidScope("full", "ANDROID_FOCUS_REQUEST_INVALID")
    except Exception:
        android = AndroidScope("full", "ANDROID_FOCUS_REQUEST_UNAVAILABLE")
    print(android.diagnostic)
    print("ANDROID_SHARED_TEST_SCOPE=" + android.mode)
    scope_notice = ("FOCUS: FileRecoveryOwnerTest only, plus the existing Android compilation and AndroidLanSyncCloudWorkTest command. "
                    "This is not a full-suite result. Baseline78 full suite remains 682 cases / 681 passed / 1 failed; "
                    "the retained prior 44-case shared focus result is 43 passed / 1 failed. Original failure evidence is retained.")
    if android.mode == "full":
        scope_notice = "FULL shared protocol suite and the existing Android compilation/unit gate command are required."
    print(scope_notice)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a", encoding="utf-8") as summary:
            summary.write(scope_notice + "\n\nAndroid scope baseline: `" + ANDROID_FOCUS_BASELINE + "`.\n")
    for kind in PROOF_JOBS:
        baseline = result.baselines.get(kind)
        if baseline:
            print(f"{kind.upper()}_PROOF_BASELINE run={baseline.run_id} attempt={baseline.attempt} job={baseline.job_id} source={baseline.source_sha}")
        else:
            print(f"{kind.upper()}_SUCCESSFUL_ANCESTRAL_PROOF_UNAVAILABLE")
        if kind in result.verified_controls:
            print(f"{kind.upper()}_CI_CONTROL_EXECUTION_CONTRACT_VERIFIED")
        if kind in result.changed_contracts:
            print(f"{kind.upper()}_PROOF_EXECUTION_CONTRACT_CHANGED")
        print(f"{kind.upper()}_RESOURCE_PROOF_" + ("REQUIRED" if result.required[kind] else "REUSE_VERIFIED_BASELINE"))
    with Path(os.environ["GITHUB_OUTPUT"]).open("a", encoding="utf-8") as output:
        for kind in PROOF_JOBS:
            output.write(kind + "_large=" + str(result.required[kind]).lower() + "\n")
        output.write("android_mode=" + android.mode + "\n")


if __name__ == "__main__":
    main()
