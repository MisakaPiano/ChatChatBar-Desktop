import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("proof_scope", Path(__file__).resolve().parents[1] / "select-proof-scope.py")
scope = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scope)


class ProofScopeTest(unittest.TestCase):
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

    def test_pure_files_cannot_mask_an_independent_io_change_in_the_same_push(self):
        self.assertEqual((False, True), scope.classify(list(scope.PURE_FILES) + [scope.LAN + "LanSyncGroupNativePlanner.kt"]))


if __name__ == "__main__":
    unittest.main()
