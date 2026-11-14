package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.util.Collections
import kotlinx.serialization.json.*

data class LanSyncGroupSourceUnitReview(val unit: String, val binding: String, val current: List<LanSyncRecord>,
    val versions: Map<SyncVersionRef, List<LanSyncRecord>>, val missingBodies: Set<LanSyncGroupMembershipRequest>,
    val impact: Map<SyncVersionRef, List<LanSyncRecordImpact>>, val records: LanSyncGroupRecordGate,
    val dependencies: Set<String>, val needsConfirmation: Boolean)
class LanSyncGroupSourceAuthorization internal constructor(val native: LanSyncGroupNativePlan,
    val units: Map<String, LanSyncGroupSourceUnitReview>, val binding: String,
    internal val resolution: LanSyncGroupResolvedLocalApproval? = null)

/** Frozen review of actual signed before-bodies; hashes alone never become invented ancestor text. */
class LanSyncGroupSourceReview private constructor(private val native: LanSyncGroupNativePlan,
    val units: Map<String, LanSyncGroupSourceUnitReview>, val binding: String) {
    val requiredBindings: Map<String, String> get() = units.filterValues { it.needsConfirmation }.mapValues { it.value.binding }
    fun authorize(confirmations: Map<String, String> = emptyMap()): LanSyncGroupSourceAuthorization {
        require(units.values.all { it.missingBodies.isEmpty() && it.records.missing.isEmpty() }) { "SYNC_GROUP_SOURCE_REVIEW_EVIDENCE_MISSING" }
        require(units.values.all { it.dependencies.isEmpty() }) { "SYNC_GROUP_SOURCE_DEPENDENCY_REVIEW_REQUIRED" }
        require(confirmations == requiredBindings) { "SYNC_GROUP_SOURCE_CONFIRMATION_REQUIRED" }
        return LanSyncGroupSourceAuthorization(native, units, binding)
    }
    /** Bridge only a verified installed decision to a real freshly captured source review. */
    internal fun authorizeResolved(approval: LanSyncGroupResolvedLocalApproval): LanSyncGroupSourceAuthorization {
        require(native.checkpoint == approval.checkpoint && native.heads == approval.heads && native.expected == approval.targetHashes &&
            native.sourceFiles == approval.postFiles && native.appliedBefore == approval.appliedBefore && units.keys == approval.resolvedUnits &&
            units.keys == approval.previous.keys && units.keys.all { unit ->
                native.units.getValue(unit).frontiers.map { it.version }.toSet() == approval.previous.getValue(unit)
            }) { "SYNC_GROUP_RESOLUTION_SOURCE_REVIEW_CHANGED" }
        val actual = authorize(requiredBindings)
        val bound = lanSyncHash(buildJsonObject {
            put("review", actual.binding); put("resolution", approval.binding); put("operation", approval.revision)
        })
        return LanSyncGroupSourceAuthorization(native, actual.units, bound, approval)
    }
    companion object {
        fun prepare(native: LanSyncGroupNativePlan, reconciled: SyncInboxReconciliation, selected: Set<String>,
            dependencies: Map<LanSyncGroupMembershipRequest, LanSyncGroupDependencyResult>,
            recordEvidence: LanSyncGroupRecordEvidence): LanSyncGroupSourceReview {
            require(selected.isNotEmpty() && selected.all { it in native.units }) { "SYNC_GROUP_SOURCE_SELECTION" }
            require(!reconciled.trustBlocked && reconciled.ledgerCheckpoint == native.checkpoint && reconciled.history?.heads() == native.heads) { "SYNC_GROUP_SOURCE_REVIEW_VIEW" }
            val history = requireNotNull(reconciled.history).snapshot(); recordEvidence.requireView(history, native.checkpoint)
            val versions = history.admittedVersions().associateBy { it.reference() }; val current = native.before.units()
            val result = selected.sorted().associateWith { unit ->
                val row = native.units.getValue(unit)
                require(row.stage in setOf(LanSyncGroupNativeStage.LOCAL_PROPOSAL, LanSyncGroupNativeStage.LOCAL_REDUCTION_REVIEW,
                    LanSyncGroupNativeStage.CONFLICT, LanSyncGroupNativeStage.RECORD_REVIEW,
                    LanSyncGroupNativeStage.DEPENDENCY_HELD)) { "SYNC_GROUP_SOURCE_NOT_A_PROPOSAL" }
                require(row.currentHash != null || row.frontiers.isNotEmpty()) { "SYNC_GROUP_SOURCE_EMPTY_TOMBSTONE" }
                val references = row.applied?.frontiers.orEmpty() + row.frontiers.map { it.version }
                val before = linkedMapOf<SyncVersionRef, List<LanSyncRecord>>(); val missing = linkedSetOf<LanSyncGroupMembershipRequest>()
                for (reference in references) {
                    val change = requireNotNull(versions[reference]?.changes?.get(unit)) { "SYNC_GROUP_SOURCE_REVIEW_VERSION" }
                    if (change.content == null) { before[reference] = emptyList(); continue }
                    val request = LanSyncGroupMembershipRequest(reference, unit); val dependency = dependencies[request]
                    val available = dependency?.available
                    if (dependency?.stage != LanSyncGroupDependencyStage.UNIT_CONTENT_AVAILABLE || available == null) { missing += request; continue }
                    require(available.version == reference && available.checkpoint == native.checkpoint && available.decoded.payload.unit == unit &&
                        available.decoded.plaintext == change.content && available.decoded.valueHash == change.valueHash) { "SYNC_GROUP_SOURCE_REVIEW_BODY_CHANGED" }
                    before[reference] = available.decoded.payload.records
                }
                val proposed = current[unit].orEmpty()
                val impacts = before.mapValues { (_, records) -> LanSyncImpactReview.changes(records, proposed) }
                val gate = recordEvidence.checkProposal(unit, proposed.map { it.key }.toSet()) { former, removal ->
                    native.appliedBefore[former]?.frontiers?.any { history.isAncestorOrSelf(removal, it) } == true
                }
                val conflict = row.frontiers.isNotEmpty() && row.frontiers.map { it.version }.toSet() != row.applied?.frontiers
                val related = native.components.getValue(unit).filterTo(linkedSetOf()) {
                    it !in selected && native.units.getValue(it).stage != LanSyncGroupNativeStage.UNCHANGED
                }
                val needsConfirmation = conflict || related.isNotEmpty() || gate.removals.isNotEmpty() || missing.isNotEmpty() ||
                    impacts.values.flatten().any { it.needsConfirmation }
                val unitBinding = lanSyncHash(buildJsonObject {
                    put("unit", unit); put("current", row.currentHash?.let(::JsonPrimitive) ?: JsonNull)
                    put("applied", refs(row.applied?.frontiers.orEmpty())); put("frontiers", refs(row.frontiers.map { it.version }.toSet()))
                    put("bodies", JsonArray(references.sortedBy { it.id.value }.map { reference ->
                        val change = versions.getValue(reference).changes.getValue(unit)
                        buildJsonObject { put("version", refJson(reference)); put("hash", change.valueHash?.let(::JsonPrimitive) ?: JsonNull)
                            put("plaintext", change.content?.let { buildJsonObject { put("length", it.length); put("sha256", it.sha256) } } ?: JsonNull) }
                    }))
                    put("removals", JsonArray(gate.removals.sortedWith(compareBy({ it.record }, { it.formerUnit }, { it.removal.id.value })).map {
                        buildJsonObject { put("record", it.record); put("unit", it.formerUnit); put("removal", refJson(it.removal)) }
                    }))
                    put("requiresConfirmation", needsConfirmation)
                    put("dependencies", JsonArray(related.sorted().map(::JsonPrimitive)))
                })
                LanSyncGroupSourceUnitReview(unit, unitBinding, Collections.unmodifiableList(proposed.toList()), Collections.unmodifiableMap(before),
                    Collections.unmodifiableSet(missing), Collections.unmodifiableMap(impacts), gate, Collections.unmodifiableSet(related), needsConfirmation)
            }
            val binding = lanSyncHash(buildJsonObject {
                put("checkpoint", refJson(native.checkpoint)); put("heads", refs(native.heads))
                put("source", JsonObject(native.expected.toSortedMap().mapValues { JsonPrimitive(it.value) }))
                put("raw", JsonObject(native.sourceFiles.toSortedMap().mapValues { JsonPrimitive(it.value) }))
                put("selected", JsonObject(result.mapValues { JsonPrimitive(it.value.binding) }))
            })
            return LanSyncGroupSourceReview(native, Collections.unmodifiableMap(result), binding)
        }
        private fun refJson(value: SyncVersionRef) = buildJsonObject { put("id", value.id.value); put("bodyHash", value.bodyHash) }
        private fun refs(values: Set<SyncVersionRef>) = JsonArray(values.sortedBy { it.id.value }.map(::refJson))
    }
}
