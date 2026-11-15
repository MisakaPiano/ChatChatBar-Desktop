package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.util.Collections

data class LanSyncGroupMembershipRequest(val version: SyncVersionRef, val unit: String)
data class LanSyncGroupRecordRemoval(val record: String, val formerUnit: String, val removal: SyncVersionRef)
data class LanSyncGroupRecordGate(val missing: Set<LanSyncGroupMembershipRequest>, val removals: Set<LanSyncGroupRecordRemoval>) {
    val permitsContent: Boolean get() = missing.isEmpty() && removals.isEmpty()
}

/** Fixed-view native record-move/deletion evidence. Never chooses a conflict winner or mutates app data. */
class LanSyncGroupRecordEvidence(history: SyncCausalHistory, private val checkpoint: SyncVersionRef) {
    private val history = history.snapshot()
    private val members = linkedMapOf<LanSyncGroupMembershipRequest, LanSyncGroupMembershipEvidence>()
    fun requireView(history: SyncCausalHistory, checkpoint: SyncVersionRef) {
        require(this.checkpoint == checkpoint && this.history.heads() == history.heads()) { "SYNC_GROUP_RECORD_EVIDENCE_VIEW" }
    }
    fun offer(evidence: LanSyncGroupMembershipEvidence) {
        require(evidence.checkpoint == checkpoint && history.contains(evidence.version)) { "SYNC_GROUP_RECORD_EVIDENCE_VIEW" }
        val version = history.admittedVersions().single { it.reference() == evidence.version }
        val change = requireNotNull(version.changes[evidence.unit]) { "SYNC_GROUP_RECORD_EVIDENCE_UNIT" }
        require(change.content != null && if (change.membership != null) evidence.identity == change.membership else evidence.validatedAgainstNativePayload) {
            "SYNC_GROUP_RECORD_EVIDENCE_BINDING"
        }
        val request = LanSyncGroupMembershipRequest(evidence.version, evidence.unit)
        members[request]?.let { require(it.identity == evidence.identity) { "SYNC_GROUP_RECORD_EVIDENCE_CHANGED" } }
        members[request] = evidence
    }
    fun checkCommitted(version: SyncVersionRef, unit: String): LanSyncGroupRecordGate {
        require(history.contains(version)) { "SYNC_GROUP_RECORD_EVIDENCE_VIEW" }
        val request = LanSyncGroupMembershipRequest(version, unit)
        val evidence = members[request] ?: return LanSyncGroupRecordGate(setOf(request), emptySet())
        val keys = linkedSetOf<String>(); LanSyncGroupMembership.visit(evidence.path, evidence.identity, keys::add)
        return check(unit, keys) { _, removal -> history.isAncestorOrSelf(removal, version) }
    }
    /** Use per-former-unit applied/explicitly reviewed context, never all freshly discovered global heads. */
    fun checkProposal(unit: String, records: Set<String>, acknowledged: (String, SyncVersionRef) -> Boolean): LanSyncGroupRecordGate =
        check(unit, records, acknowledged)

    private fun check(candidateUnit: String, records: Set<String>, acknowledged: (String, SyncVersionRef) -> Boolean): LanSyncGroupRecordGate {
        require(candidateUnit.length in 1..600 && records.all { it.length in 1..600 && it.substringBefore('/') in LanSyncScope.schemas &&
            it.substringAfter('/', "").isNotBlank() && it.none { char -> char.code < 32 } }) { "SYNC_GROUP_RECORD_KEYS" }
        val missing = linkedSetOf<LanSyncGroupMembershipRequest>(); val removals = linkedSetOf<LanSyncGroupRecordRemoval>()
        val versions = history.admittedVersions()
        val units = versions.flatMap { it.changes.keys }.toSet() - candidateUnit
        for (unit in units) {
            val possible = records.filterTo(linkedSetOf()) { couldBelong(unit, it) }
            if (possible.isEmpty()) continue
            for (frontier in history.frontier(unit)) {
                if (acknowledged(unit, frontier.version)) continue
                val formerlyPresent = linkedSetOf<String>(); val currentlyPresent = linkedSetOf<String>()
                // Retain removal evidence across repeated tombstones and later changes that still omit a record.
                for (ancestor in versions) {
                    val change = ancestor.changes[unit] ?: continue
                    if (change.content == null || !history.isAncestorOrSelf(ancestor.reference(), frontier.version)) continue
                    val request = LanSyncGroupMembershipRequest(ancestor.reference(), unit)
                    val evidence = members[request]
                    if (evidence == null) { missing += request; continue }
                    LanSyncGroupMembership.visit(evidence.path, evidence.identity) { if (it in possible) formerlyPresent += it }
                }
                if (frontier.change.content != null) {
                    val request = LanSyncGroupMembershipRequest(frontier.version, unit); val evidence = members[request]
                    if (evidence == null) { missing += request; continue }
                    LanSyncGroupMembership.visit(evidence.path, evidence.identity) { if (it in possible) currentlyPresent += it }
                }
                (formerlyPresent - currentlyPresent).forEach { removals += LanSyncGroupRecordRemoval(it, unit, frontier.version) }
            }
        }
        return LanSyncGroupRecordGate(Collections.unmodifiableSet(missing), Collections.unmodifiableSet(removals))
    }
    /** Disjoint fixed namespaces need no unrelated history; dynamic conversation/Moments owners remain conservative. */
    private fun couldBelong(unit: String, key: String): Boolean {
        val type = key.substringBefore('/'); val id = key.substringAfter('/', "")
        return when {
            type in setOf("character_cards", "format_cards", "world_books", "player_setting") -> unit == key
            type == "chat_sessions" -> unit == "conversation/$id"
            type in LanSyncMoments.types -> unit.startsWith("moment-task/") || unit.startsWith("moment-post/")
            type in LanSyncStudioCurrent.types -> unit == LanSyncStudioCurrent.UNIT
            else -> !unit.startsWith("moment-task/") && !unit.startsWith("moment-post/") && unit != LanSyncStudioCurrent.UNIT
        }
    }
}
