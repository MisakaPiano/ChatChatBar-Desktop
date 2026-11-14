"""Choose only affected large-byte proofs. Unknown source/config paths stay conservative."""
from __future__ import annotations

import os
from pathlib import Path
import re
import subprocess

LAN = "app/sharedCore/src/main/kotlin/com/example/chatbar/domain/lanSync/"
STORE = "app/sharedCore/src/main/kotlin/com/example/chatbar/domain/syncStore/"
CLOUD_LAN = {
    "LanSyncGroupCloudRuntime.kt", "LanSyncGroupCloudSchedule.kt", "LanSyncGroupCloudRoutes.kt",
    "LanSyncGroupCloudSettingsState.kt", "LanSyncGroupTargetExport.kt", "LanSyncGroupTargetPublication.kt",
    "LanSyncGroupChunkCloudReadRoute.kt", "LanSyncGroupWeakCloudReadRoute.kt",
    "LanSyncGroupCloudRecoverySource.kt", "LanSyncGroupCloudRecoveryOwner.kt", "LanSyncGroupCoordinator.kt",
}
CLOUD_STORE = {
    "webdav/WeakWebDavAttemptStore.kt", "webdav/WeakWebDavChunkAssembly.kt", "webdav/WeakWebDavXmlListing.kt",
    "webdav/WebDavCiphertextStore.kt", "webdav/WebDavHttpTransport.kt", "webdav/WebDavStoreRecord.kt", "webdav/WebDavXmlListing.kt",
}
# These app callers are compiled/checked by their native jobs; neither old large shared proof runs them.
APP_CONTROLS = {
    "app/app/src/main/java/com/example/chatbar/lanSync/" + name for name in (
        "AndroidLanSync.kt", "AndroidLanSyncCloudWork.kt", "AndroidLanSyncLifecycle.kt",
        "LanSyncGroupForegroundService.kt", "LanSyncGroupCloudTransferService.kt", "LanSyncActivity.kt",
        "LanSyncGroupCloudSettings.kt", "LanSyncGroupCloudRecovery.kt")
} | {
    "app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/lanSync/" + name for name in (
        "DesktopLanSync.kt", "DesktopLanSyncPanel.kt", "DesktopLanSyncCloudSettings.kt", "DesktopLanSyncCloudRecovery.kt")
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
    "LanSyncGroupCiphertextTransport.kt", "LanSyncGroupDependencies.kt", "LanSyncGroupEnrollment.kt",
    "LanSyncGroupIncomingReview.kt", "LanSyncGroupInvitation.kt", "LanSyncGroupMembership.kt",
    "LanSyncGroupNativeApplyOwner.kt", "LanSyncGroupNativePlanner.kt", "LanSyncGroupNode.kt",
    "LanSyncGroupPeerStore.kt", "LanSyncGroupPublicationQueue.kt", "LanSyncGroupRecordEvidence.kt",
    "LanSyncGroupResolution.kt", "LanSyncGroupResolutionOwner.kt", "LanSyncGroupRootPublication.kt",
    "LanSyncGroupSourceReview.kt", "LanSyncGroupSourceWriter.kt", "LanSyncGroupUnitCodec.kt",
    "LanSyncGroupJoining.kt", "LanSyncGroupManagementRecovery.kt", "LanSyncGroupRecoveryDeviceProof.kt",
}
STORE_IO = {
    "CiphertextStore.kt", "FileCiphertextStore.kt", "SyncAuthorJournal.kt", "SyncCausalHistory.kt",
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


def classify(paths: list[str]) -> tuple[bool, bool]:
    legacy = group = False
    for path in paths:
        if path in APP_CONTROLS or path.startswith("app/app/src/test/java/com/example/chatbar/lanSync/"):
            continue
        if path in PURE_FILES or path.startswith(PURE_PREFIXES) or path.startswith(".agents/skills/") and path.endswith(".md"):
            continue
        if path.startswith(LAN) and path[len(LAN):] in CLOUD_LAN or path.startswith(STORE) and path[len(STORE):] in CLOUD_STORE:
            continue
        if path.startswith(LEGACY_PROOF):
            legacy = True
        elif path.startswith(GROUP_PROOF):
            if path[len(GROUP_PROOF):] not in CLOUD_PROOF_NAMES:
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


def main() -> None:
    base = os.environ.get("CCB_SYNC_DIFF_BASE", "")
    head = os.environ.get("GITHUB_SHA", "")
    available = bool(re.fullmatch("[0-9a-f]{40}", base)) and base != "0" * 40
    available = available and bool(re.fullmatch("[0-9a-f]{40}", head))
    available = available and subprocess.run(["git", "cat-file", "-e", base + "^{commit}"], capture_output=True).returncode == 0
    legacy = group = cloud = True
    if available:
        raw = subprocess.check_output(["git", "diff", "--name-only", "-z", base, head])
        paths = [os.fsdecode(path) for path in raw.split(b"\0") if path]
        legacy, group = classify(paths)
        cloud = classify_cloud(paths)
    else:
        print("DIFF_BASE_UNAVAILABLE_FULL_TRANSPORT_PROOF_REQUIRED")
    print("LEGACY_TRANSPORT_PROOF_REQUIRED" if legacy else "UNCHANGED_LEGACY_BYTE_PATH_REUSE_EXISTING_PROOF")
    print("GROUP_RESOURCE_PROOF_REQUIRED" if group else "UNCHANGED_GROUP_BYTE_PATH_REUSE_EXISTING_PROOF")
    print("CLOUD_RESOURCE_PROOF_REQUIRED" if cloud else "UNCHANGED_CLOUD_BYTE_PATH_REUSE_EXISTING_PROOF")
    with Path(os.environ["GITHUB_OUTPUT"]).open("a", encoding="utf-8") as output:
        output.write("legacy_large=" + str(legacy).lower() + "\n")
        output.write("group_large=" + str(group).lower() + "\n")
        output.write("cloud_large=" + str(cloud).lower() + "\n")


if __name__ == "__main__":
    main()
