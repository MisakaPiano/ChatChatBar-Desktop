package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import com.example.chatbar.domain.syncStore.webdav.*
import java.nio.file.Path

/** Cloud candidate enumeration only; common vault/input authority lives in RecoveryInputCore. */
class LanSyncGroupCloudRecoverySource(root: Path, private val whole: WeakWebDavAttemptStore,
    private val parts: WeakWebDavChunkAssembly, checkActive: () -> Unit = {},
    incomingCache: WeakWebDavIncomingChunkCache? = null) : LanSyncGroupRecoveryInput {
    init { require(whole.routeId == parts.routeId) { "SYNC_GROUP_CLOUD_RECOVERY_ROUTE_CHANGED" } }
    private val core = LanSyncGroupRecoveryInputCore(root, { active ->
        listOf(LanSyncGroupWeakCloudReadRoute(whole, active),
            LanSyncGroupChunkCloudReadRoute(parts, incomingCache = incomingCache, checkActive = active))
    }, ::vaultCandidates, checkActive)
    override val backend get() = core.backend
    override fun prepare(code: String) = core.prepare(code)
    override suspend fun recover(preview: LanSyncGroupCloudRecoveryPreview, code: String,
        management: LanSyncGroupManagementRecovery, admission: LanSyncManagementRecoveryAdmission) = core.recover(preview, code, management, admission)
    override fun includeControl(preview: LanSyncGroupCloudRecoveryPreview, control: LanSyncGroupRecoveryBootstrap) = core.includeControl(preview, control)
    override suspend fun <T> withBackend(work: suspend (CiphertextStore) -> T): T = core.withBackend(work)
    override suspend fun <T> withControl(preview: LanSyncGroupCloudRecoveryPreview, control: LanSyncGroupRecoveryBootstrap,
        work: suspend (CiphertextStore) -> T): T = core.withControl(preview, control, work)

    private fun vaultCandidates(key: CiphertextObjectKey, rejected: () -> Unit): Sequence<Pair<CiphertextStore, CiphertextObjectMetadata>> = sequence {
        var cursor: WeakWebDavCursor? = null
        val wholeSeen = hashSetOf<String>()
        do {
            core.active(); cursor?.let { require(wholeSeen.add(it.token)) { "SYNC_GROUP_CLOUD_RECOVERY_LISTING_CHANGED" } }
            val page = whole.listCandidates(key, cursor, checkActive = core::active)
            for (candidate in page.entries) yield(core.ports[0] to CiphertextObjectMetadata(key, candidate.descriptor))
            cursor = page.nextCursor
        } while (cursor != null)
        val partsSeen = hashSetOf<String>()
        do {
            core.active(); cursor?.let { require(partsSeen.add(it.token)) { "SYNC_GROUP_CLOUD_RECOVERY_LISTING_CHANGED" } }
            val page = parts.listAssemblies(key, cursor, checkActive = core::active)
            for (candidate in page.entries) {
                val header = try { parts.inspectAssembly(candidate, core::active) }
                catch (failure: CiphertextStoreException) {
                    if (failure.failure !in setOf(CiphertextStoreFailure.INTEGRITY, CiphertextStoreFailure.TRANSIENT)) throw failure
                    rejected(); null
                }
                if (header != null) yield(core.ports[1] to CiphertextObjectMetadata(key, header.originalRequest.descriptor))
            }
            cursor = page.nextCursor
        } while (cursor != null)
    }
    override fun close() = core.close()
    override fun toString() = "LanSyncGroupCloudRecoverySource(REDACTED)"
}
