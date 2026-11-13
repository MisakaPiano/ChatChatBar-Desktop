package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.util.Collections
import java.util.UUID
import kotlinx.serialization.Serializable

/** Per-unit native receipt, not a peer baseline or a global 'everything seen was applied' watermark. */
@Serializable data class LanSyncGroupAppliedUnit(val valueHash: String?, val frontiers: Set<SyncVersionRef>) {
    init { require((valueHash == null || valueHash.matches(Regex("[a-f0-9]{64}"))) && frontiers.isNotEmpty()) { "SYNC_GROUP_APPLIED_UNIT" } }
}
enum class LanSyncGroupNativeStage {
    UNCHANGED, READY_CONTENT, READY_DELETION, READY_OBSERVATION,
    LOCAL_PROPOSAL, LOCAL_REDUCTION_REVIEW, CONFLICT, AUTHOR_FORK,
    WAITING_DEPENDENCY, RECORD_REVIEW, DEPENDENCY_HELD, INVALID_MIXED_GRAPH
}
data class LanSyncGroupNativeUnit(val unit: String, val stage: LanSyncGroupNativeStage,
    val currentHash: String?, val applied: LanSyncGroupAppliedUnit?, val frontiers: Set<SyncUnitFrontier>,
    val incoming: Map<SyncVersionRef, LanSyncGroupAvailableUnit>, val available: LanSyncGroupAvailableUnit? = null,
    val records: LanSyncGroupRecordGate? = null, val code: String? = null)
class LanSyncGroupNativePlan internal constructor(val before: LanSyncSnapshot, val plan: LanSyncPlan, val checkpoint: SyncVersionRef,
    val heads: Set<SyncVersionRef>, val expected: Map<String, String>, val sourceFiles: Map<String, String>,
    val units: Map<String, LanSyncGroupNativeUnit>, val appliedBefore: Map<String, LanSyncGroupAppliedUnit>,
    val advances: Map<String, LanSyncGroupAppliedUnit>, val components: Map<String, Set<String>>)

/** Frozen native planning only. Source proposals stay unsigned; OS admission/custody/WAL still own mutation. */
object LanSyncGroupNativePlanner {
    fun prepare(current: LanSyncCapture, reconciled: SyncInboxReconciliation,
        applied: Map<String, LanSyncGroupAppliedUnit>, dependencies: Map<LanSyncGroupMembershipRequest, LanSyncGroupDependencyResult>,
        recordEvidence: LanSyncGroupRecordEvidence, freshLocalRevisions: Set<String> = emptySet(),
        transaction: String = UUID.randomUUID().toString()): LanSyncGroupNativePlan {
        val source = current.snapshot.let { it.copy(records = Collections.unmodifiableList(it.records.toList()),
            resources = Collections.unmodifiableMap(it.resources.toMap()), coveredTypes = Collections.unmodifiableSet(it.coveredTypes.toSet()),
            excludedTypes = Collections.unmodifiableSet(it.excludedTypes.toSet())) }
        source.validate(); LanSyncScope.validateGraph(source)
        require(!reconciled.trustBlocked && reconciled.history != null && reconciled.ledgerCheckpoint != null) { "SYNC_GROUP_NATIVE_TRUST_VIEW" }
        val history = reconciled.history.snapshot(); val checkpoint = reconciled.ledgerCheckpoint
        recordEvidence.requireView(history, checkpoint)
        val localUnits = source.units(); val hashes = source.hashes(); val versions = history.admittedVersions().associateBy { it.reference() }
        val allUnits = (localUnits.keys + applied.keys + versions.values.flatMap { it.changes.keys }).toSortedSet()
        require(freshLocalRevisions.all { it in allUnits }) { "SYNC_GROUP_LOCAL_REVISION_UNIT" }
        applied.forEach { (unit, state) ->
            require(unit.length in 1..600 && state.frontiers.all { reference ->
                val change = versions[reference]?.changes?.get(unit)
                change != null && change.valueHash == state.valueHash
            }) { "SYNC_GROUP_APPLIED_EVIDENCE_MISSING" }
        }
        val rows = linkedMapOf<String, LanSyncGroupNativeUnit>()
        val forks = history.authorForks()
        for (unit in allUnits) {
            val before = applied[unit]; val local = hashes[unit]; val frontier = history.frontier(unit)
            val references = frontier.map { it.version }.toSet()
            val incomingViews = frontier.mapNotNull { item ->
                val result = dependencies[LanSyncGroupMembershipRequest(item.version, unit)]
                val available = result?.available
                if (result?.stage != LanSyncGroupDependencyStage.UNIT_CONTENT_AVAILABLE || available == null) null else {
                    require(available.checkpoint == checkpoint && available.version == item.version && available.decoded.payload.unit == unit &&
                        available.decoded.plaintext == item.change.content && available.decoded.valueHash == item.change.valueHash &&
                        available.decoded.payload.coveredTypes == source.coveredTypes) { "SYNC_GROUP_NATIVE_DEPENDENCY_VIEW" }
                    item.version to available
                }
            }.toMap()
            fun row(stage: LanSyncGroupNativeStage, available: LanSyncGroupAvailableUnit? = null,
                records: LanSyncGroupRecordGate? = null, code: String? = null) =
                LanSyncGroupNativeUnit(unit, stage, local, before, Collections.unmodifiableSet(frontier), Collections.unmodifiableMap(incomingViews), available, records, code)
            val ownCommittedValue = unit !in freshLocalRevisions && frontier.singleOrNull()?.let { fact ->
                versions.getValue(fact.version).author == source.deviceId && fact.change.valueHash == local
            } == true
            val dirty = !ownCommittedValue && (unit in freshLocalRevisions || if (before == null) local != null else local != before.valueHash)
            if (dirty) {
                val gate = recordEvidence.checkProposal(unit, localUnits[unit].orEmpty().map { it.key }.toSet()) { former, removal ->
                    applied[former]?.frontiers?.any { history.isAncestorOrSelf(removal, it) } == true
                }
                rows[unit] = when {
                    source.deviceId in forks -> row(LanSyncGroupNativeStage.AUTHOR_FORK, code = "SYNC_GROUP_LOCAL_AUTHOR_FORK")
                    !gate.permitsContent -> row(LanSyncGroupNativeStage.RECORD_REVIEW, records = gate, code = "SYNC_GROUP_SOURCE_RECORD_REVIEW")
                    local == null && before?.valueHash != null -> row(LanSyncGroupNativeStage.LOCAL_REDUCTION_REVIEW, code = "SYNC_GROUP_UNSIGNED_LOCAL_DELETION")
                    frontier.isNotEmpty() && references != before?.frontiers -> row(LanSyncGroupNativeStage.CONFLICT, code = "SYNC_GROUP_LOCAL_AND_INCOMING_CHANGED")
                    else -> row(LanSyncGroupNativeStage.LOCAL_PROPOSAL, code = "SYNC_GROUP_UNSIGNED_LOCAL_CHANGE")
                }
                continue
            }
            if (references == before?.frontiers || frontier.isEmpty()) { rows[unit] = row(LanSyncGroupNativeStage.UNCHANGED); continue }
            if (frontier.any { versions.getValue(it.version).author in forks }) { rows[unit] = row(LanSyncGroupNativeStage.AUTHOR_FORK); continue }
            if (frontier.size != 1) { rows[unit] = row(LanSyncGroupNativeStage.CONFLICT, code = "SYNC_GROUP_MULTIPLE_UNIT_FRONTIERS"); continue }
            val incoming = frontier.single()
            if (incoming.change.content == null) {
                rows[unit] = row(if (local == null) LanSyncGroupNativeStage.READY_OBSERVATION else LanSyncGroupNativeStage.READY_DELETION)
                continue
            }
            val gate = recordEvidence.checkCommitted(incoming.version, unit)
            if (!gate.permitsContent) { rows[unit] = row(LanSyncGroupNativeStage.RECORD_REVIEW, records = gate); continue }
            val result = dependencies[LanSyncGroupMembershipRequest(incoming.version, unit)]
            val available = incomingViews[incoming.version]
            if (available == null) {
                rows[unit] = row(LanSyncGroupNativeStage.WAITING_DEPENDENCY, code = result?.code); continue
            }
            rows[unit] = row(if (local == incoming.change.valueHash) LanSyncGroupNativeStage.READY_OBSERVATION else LanSyncGroupNativeStage.READY_CONTENT, available)
        }

        // A record move, strict live-format reference or immutable media identity is one native component.
        val links = allUnits.associateWith { linkedSetOf<String>() }
        fun link(owners: Set<String>) { owners.forEach { unit -> links.getValue(unit).addAll(owners - unit) } }
        val records = localUnits.flatMap { (unit, records) -> records.map { unit to it } } +
            rows.values.flatMap { row -> row.incoming.values.flatMap { candidate -> candidate.decoded.payload.records.map { row.unit to it } } }
        val owners = records.groupBy({ it.second.key }, { it.first }).mapValues { it.value.toSet() }
        owners.values.forEach(::link)
        records.filter { it.second.type == "chat_sessions" }.forEach { (unit, session) ->
            session.text("formatCardId")?.let { format ->
                val key = "format_cards/$format"
                link(owners[key].orEmpty() + listOfNotNull(key.takeIf { it in allUnits }) + unit)
            }
        }
        val mediaOwners = linkedMapOf<String, MutableSet<String>>()
        records.forEach { (unit, record) -> LanSyncWorkspace.mapResources(record.body) { reference, _ ->
            if (reference.startsWith("ccb-lan-v1/")) {
                val parts = reference.split('/'); require(parts.size == 5) { "SYNC_RESOURCE_REFERENCE" }
                mediaOwners.getOrPut("${parts[1]}/${parts[2]}") { linkedSetOf() }.add(unit)
            }; reference
        } }
        mediaOwners.values.forEach { link(it) }
        val remaining = allUnits.toMutableSet(); val processed = linkedSetOf<String>()
        val components = linkedMapOf<String, Set<String>>()
        var target = source
        while (remaining.isNotEmpty()) {
            val component = linkedSetOf<String>(); val queue = java.util.ArrayDeque<String>(); queue.add(remaining.first())
            while (queue.isNotEmpty()) { val unit = queue.removeFirst(); if (component.add(unit)) queue.addAll(links.getValue(unit)) }
            remaining.removeAll(component)
            val frozen = Collections.unmodifiableSet(component.toSet()); component.forEach { components[it] = frozen }
            val ready = component.filter { rows.getValue(it).stage in readyStages }.toSet()
            if (ready.isEmpty()) continue
            if (component.any { rows.getValue(it).stage !in readyStages + LanSyncGroupNativeStage.UNCHANGED }) {
                ready.forEach { rows[it] = rows.getValue(it).copy(stage = LanSyncGroupNativeStage.DEPENDENCY_HELD, code = "SYNC_GROUP_NATIVE_COMPONENT_HELD") }
                continue
            }
            try {
                val candidate = replace(target, ready, rows)
                candidate.validate(); LanSyncScope.validateGraph(candidate)
                val expectedHashes = target.hashes().toMutableMap()
                ready.forEach { unit -> rows.getValue(unit).frontiers.single().change.valueHash.let { if (it == null) expectedHashes.remove(unit) else expectedHashes[unit] = it } }
                require(candidate.hashes() == expectedHashes) { "SYNC_GROUP_NATIVE_COMPONENT_REGROUPED" }
                target = candidate; processed += ready
            } catch (failure: IllegalArgumentException) {
                val code = failure.message?.takeIf { it.matches(Regex("SYNC_[A-Z0-9_]{1,100}")) } ?: "SYNC_GROUP_NATIVE_MIXED_GRAPH"
                ready.forEach { rows[it] = rows.getValue(it).copy(stage = LanSyncGroupNativeStage.INVALID_MIXED_GRAPH, code = code) }
            }
        }
        val advances = processed.associateWith { unit ->
            val frontier = rows.getValue(unit).frontiers.single()
            LanSyncGroupAppliedUnit(frontier.change.valueHash, setOf(frontier.version))
        }
        return LanSyncGroupNativePlan(source, LanSyncPlan(transaction, target, emptyMap()), checkpoint, Collections.unmodifiableSet(history.heads()),
            Collections.unmodifiableMap(hashes), Collections.unmodifiableMap(current.sourceFiles.toMap()), Collections.unmodifiableMap(rows),
            Collections.unmodifiableMap(applied.mapValues { (_, value) -> value.copy(frontiers = Collections.unmodifiableSet(value.frontiers.toSet())) }),
            Collections.unmodifiableMap(advances), Collections.unmodifiableMap(components))
    }
    private val readyStages = setOf(LanSyncGroupNativeStage.READY_CONTENT, LanSyncGroupNativeStage.READY_DELETION, LanSyncGroupNativeStage.READY_OBSERVATION)
    private fun replace(current: LanSyncSnapshot, units: Set<String>, rows: Map<String, LanSyncGroupNativeUnit>): LanSyncSnapshot {
        val available = units.mapNotNull { rows.getValue(it).available }
        val records = current.units().filterKeys { it !in units }.values.flatten() + available.flatMap { it.decoded.payload.records }
        val references = LanSyncScope.referencedResources(records)
        val resources = current.resources.toMutableMap()
        available.forEach { item -> item.decoded.payload.resources.forEach { (hash, size) ->
            require(resources[hash]?.let { it == size } != false) { "SYNC_RESOURCE_MANIFEST" }; resources[hash] = size
        } }
        return current.copy(records = Collections.unmodifiableList(records), resources = Collections.unmodifiableMap(resources.filterKeys(references::contains)))
    }
}
