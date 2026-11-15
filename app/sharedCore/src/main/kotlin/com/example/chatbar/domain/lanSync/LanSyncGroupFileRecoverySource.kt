package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.io.OutputStream
import java.nio.file.Path

/** Borrowed complete ciphertext stage; caller disposes it only after admitted reads have drained. */
class LanSyncGroupFileRecoverySource(root: Path, private val staged: LanSyncGroupEncryptedFilePackage.Staged,
    checkActive: () -> Unit = {}) : LanSyncGroupRecoveryInput {
    private val core = LanSyncGroupRecoveryInputCore(root, { active -> listOf(object : CiphertextStore by staged.quarantine {
        private fun <T> checked(work: () -> T): T {
            active()
            try { return work().also { active() } }
            catch (failure: Throwable) { active(); throw failure }
        }
        override fun metadata(key: CiphertextObjectKey) = checked { staged.quarantine.metadata(key) }
        override fun readTo(key: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?) =
            checked { staged.quarantine.readTo(key, destination, expected) }
        override fun listCommits(cursor: CommitCursor?, pageSize: Int) = checked { staged.quarantine.listCommits(cursor, pageSize) }
    }) }, ::vaultCandidates, checkActive)
    override val backend get() = core.backend
    override fun prepare(code: String) = core.prepare(code)
    override suspend fun recover(preview: LanSyncGroupCloudRecoveryPreview, code: String,
        management: LanSyncGroupManagementRecovery, admission: LanSyncManagementRecoveryAdmission) = core.recover(preview, code, management, admission)
    override fun includeControl(preview: LanSyncGroupCloudRecoveryPreview, control: LanSyncGroupRecoveryBootstrap) = core.includeControl(preview, control)
    override suspend fun <T> withBackend(work: suspend (CiphertextStore) -> T): T = core.withBackend(work)
    override suspend fun <T> withControl(preview: LanSyncGroupCloudRecoveryPreview, control: LanSyncGroupRecoveryBootstrap,
        work: suspend (CiphertextStore) -> T): T = core.withControl(preview, control, work)
    suspend fun verifyRecoveredInventory(management: LanSyncGroupManagementRecovery): LanSyncGroupEncryptedFilePackage.VerifiedInventory =
        core.withBackend { management.verifyFileInventory(staged) }
    private fun vaultCandidates(key: CiphertextObjectKey, rejected: () -> Unit): Sequence<Pair<CiphertextStore, CiphertextObjectMetadata>> = sequence {
        core.active(); core.ports[0].metadata(key)?.let { yield(core.ports[0] to it) }; core.active()
    }
    override fun close() = core.close()
    override fun toString() = "LanSyncGroupFileRecoverySource(REDACTED)"
}
