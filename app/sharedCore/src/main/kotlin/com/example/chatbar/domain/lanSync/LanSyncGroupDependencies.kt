package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.io.InterruptedIOException
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CancellationException
import kotlinx.serialization.json.*

enum class LanSyncGroupDependencyStage {
    UNIT_CONTENT_AVAILABLE, DELETION_REVIEW_REQUIRED, WAITING_FOR_RESOURCE, WAITING_FOR_KEY,
    RECONCILE_AGAIN, AUTHOR_FORK, UNSUPPORTED_SCOPE, LOCAL_CUSTODY_BLOCKED, INVALID_CONTENT
}
class LanSyncGroupAvailableUnit internal constructor(val version: SyncVersionRef, val checkpoint: SyncVersionRef,
    val decoded: LanSyncDecodedGroupUnit, val resources: Map<String, Path>)
data class LanSyncGroupDependencyResult(val stage: LanSyncGroupDependencyStage, val available: LanSyncGroupAvailableUnit? = null,
    val code: String? = null)

/** Per-unit content readiness only; full mixed graph, reduction review/custody and native admission still precede apply. */
class LanSyncGroupDependencies(private val group: SyncStoreId, private val backend: CiphertextStore,
    private val custody: SyncGroupCustody, private val privateDirectory: Path, private val cache: LanSyncResourceStore,
    private val validateResource: (String, Path, LanSyncRecord) -> Unit,
    private val metadataBudget: LanSyncMetadataBudget = LanSyncMetadataBudget()) {
    fun inspect(reconciled: SyncInboxReconciliation, metadata: SyncAuthenticatedGroupObject, unit: String): LanSyncGroupDependencyResult {
        try {
            val ledger = localCustody { custody.ledger() }; val latest = ledger.current(); val checkpoint = latest.reference()
            if (reconciled.trustBlocked || reconciled.ledgerCheckpoint != checkpoint) return LanSyncGroupDependencyResult(LanSyncGroupDependencyStage.RECONCILE_AGAIN)
            require(latest.group == group && metadata.context.group == group &&
                reconciled.authenticatedVersions.any { it === metadata }) { "SYNC_GROUP_DEPENDENCY_WITNESS" }
            val version = requireNotNull(metadata.envelope.version) { "SYNC_GROUP_DEPENDENCY_VERSION" }
            require(reconciled.history?.contains(version.reference()) == true) { "SYNC_GROUP_DEPENDENCY_NOT_ADMITTED" }
            if (version.author in requireNotNull(reconciled.history).authorForks()) return LanSyncGroupDependencyResult(LanSyncGroupDependencyStage.AUTHOR_FORK)
            // Recheck the fixed current policy, not a caller-provided CAUSAL_ADMITTED label.
            ledger.admission(reconciled.authenticatedVersions.map { requireNotNull(it.envelope.version) }, reconciled.authenticatedVersions).requireAuthorized(version)
            val change = requireNotNull(version.changes[unit]) { "SYNC_GROUP_DEPENDENCY_UNIT" }
            if (change.content == null) return LanSyncGroupDependencyResult(LanSyncGroupDependencyStage.DELETION_REVIEW_REQUIRED)
            val scope = requireNotNull(ledger.record(version.deviceLedger)) { "SYNC_GROUP_LEDGER_MISSING" }.coveredTypes
            if (!LanSyncScope.schemas.keys.containsAll(scope)) return LanSyncGroupDependencyResult(LanSyncGroupDependencyStage.UNSUPPORTED_SCOPE)
            fun fetch(identity: SyncPlaintextIdentity): Path {
                val binding = requireNotNull(metadata.envelope.blobs[identity.sha256]) { "SYNC_GROUP_DEPENDENCY_BINDING_MISSING" }
                require(binding.plaintext == identity) { "SYNC_GROUP_DEPENDENCY_BINDING_CHANGED" }
                cache.completed(identity.sha256, identity.length)?.let { return it }
                val key = localCustody { custody.key(binding.epoch) }
                SyncGroupBlobStore(group, privateDirectory).read(backend, key, binding, identity).use { plaintext ->
                    return cache.retain(plaintext.path, identity.sha256, identity.length)
                }
            }
            fetch(change.content)
            val decoded = LanSyncGroupUnitCodec(privateDirectory, scope, metadataBudget).readCached(cache, unit, change)
            val resources = decoded.dependencies.associate { it.sha256 to fetch(it) }
            val checked = hashSetOf<Triple<String, String, String>>()
            decoded.payload.records.forEach { record -> LanSyncWorkspace.mapResources(record.body) { reference, field ->
                if (reference.startsWith("ccb-lan-v1/")) {
                    val parts = reference.split('/')
                    require(parts.size == 5) { "SYNC_RESOURCE_REFERENCE" }
                    val path = requireNotNull(resources[parts[3]]) { "SYNC_RESOURCE_MISSING" }
                    if (parts[1] == "images") LanSyncPostProcess.validateImageHash(record, field, parts[3])
                    if (checked.add(Triple(parts[1], parts[3], record.key))) {
                        if (parts[1] == "packages") {
                            val ref = requireNotNull(record.body["packageRef"]).jsonObject
                            val library = JsonObject(record.body + ("packageRef" to JsonObject(ref + ("fileName" to JsonPrimitive("${record.id}.cbsave")))))
                            LanSyncSlotPackages.validate(path, library)
                        }
                        validateResource(parts[1], path, record)
                    }
                }
                reference
            } }
            if (localCustody { custody.ledger().current().reference() } != checkpoint) return LanSyncGroupDependencyResult(LanSyncGroupDependencyStage.RECONCILE_AGAIN)
            return LanSyncGroupDependencyResult(LanSyncGroupDependencyStage.UNIT_CONTENT_AVAILABLE,
                LanSyncGroupAvailableUnit(version.reference(), checkpoint, decoded, Collections.unmodifiableMap(resources)))
        } catch (failure: Exception) {
            if (failure is CancellationException || failure is InterruptedException || failure is InterruptedIOException) throw failure
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("SYNC_GROUP_DEPENDENCY_INTERRUPTED")
            val code = failure.message?.takeIf { it.matches(Regex("SYNC_[A-Z0-9_]{1,100}")) }
                ?: if (failure is CiphertextStoreException) "SYNC_GROUP_DEPENDENCY_STORE_${failure.failure}" else "SYNC_GROUP_DEPENDENCY_INVALID"
            return LanSyncGroupDependencyResult(when (code) {
                "SYNC_GROUP_CUSTODY_KEY_MISSING" -> LanSyncGroupDependencyStage.WAITING_FOR_KEY
                "SYNC_GROUP_BLOB_MISSING" -> LanSyncGroupDependencyStage.WAITING_FOR_RESOURCE
                "SYNC_GROUP_LEDGER_FORK_OR_MISSING" -> LanSyncGroupDependencyStage.RECONCILE_AGAIN
                "SYNC_GROUP_DEPENDENCY_LOCAL_CUSTODY" -> LanSyncGroupDependencyStage.LOCAL_CUSTODY_BLOCKED
                else -> LanSyncGroupDependencyStage.INVALID_CONTENT
            }, code = code)
        }
    }
    private fun <T> localCustody(work: () -> T): T = try { work() } catch (failure: Exception) {
        if (failure is CancellationException || failure is InterruptedException || failure is InterruptedIOException) throw failure
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("SYNC_GROUP_DEPENDENCY_INTERRUPTED")
        if (failure.message in setOf("SYNC_GROUP_CUSTODY_KEY_MISSING", "SYNC_GROUP_LEDGER_FORK_OR_MISSING")) throw failure
        throw IllegalStateException("SYNC_GROUP_DEPENDENCY_LOCAL_CUSTODY", failure)
    }
}
