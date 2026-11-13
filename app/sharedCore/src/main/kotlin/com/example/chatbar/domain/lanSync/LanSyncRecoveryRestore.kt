package com.example.chatbar.domain.lanSync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.UUID

data class LanSyncRestorePreview(val id: String, val sourceTransaction: String, val sourceSnapshotHash: String,
    val unit: String, val restoredUnits: Set<String>, val expected: Map<String, String>, val plan: LanSyncPlan,
    val currentRecords: List<LanSyncRecord>, val restoredRecords: List<LanSyncRecord>, val binding: String,
    val hasChanges: Boolean)

@Serializable
data class LanSyncRestoreRevision(val revision: String, val sourceTransaction: String, val sourceSnapshotHash: String,
    val unit: String, val sourceDevice: String, val previewBinding: String, val createdAt: Long,
    val applied: Boolean = false)

/** Replaces only the selected historical unit and its required missing dependencies. */
object LanSyncRecoveryRestore {
    fun prepare(entry: LanSyncRecoveryEntry, old: LanSyncSnapshot, affected: Set<String>, current: LanSyncSnapshot,
        unit: String, revision: String = UUID.randomUUID().toString()): LanSyncRestorePreview {
        require(revision.matches(Regex("[a-f0-9-]{36}")) && revision != entry.transaction) { "SYNC_RECOVERY_REVISION" }
        require(entry.sourceDevice == old.deviceId && current.deviceId == (entry.custodyDevice ?: entry.sourceDevice)) { "SYNC_RECOVERY_DEVICE" }
        old.validate(); current.validate(); LanSyncScope.validateGraph(old); LanSyncScope.validateGraph(current)
        require(unit in affected) { "SYNC_RECOVERY_UNIT" }
        val retained = requireNotNull(old.units()[unit]) { "SYNC_RECOVERY_UNIT" }.toMutableList()
        val retainedKeys = retained.map { it.key }.toSet()
        current.records.filter { it.key in retainedKeys && it.type !in LanSyncMoments.types }.forEach {
            require(it.unit == unit) { "SYNC_RECOVERY_ID_COLLISION" }
        }
        val replacing = current.units().filter { (key, rows) -> key == unit || rows.any { it.key in retainedKeys } }
        val replacingKeys = replacing.values.flatten().map { it.key }.toSet()
        val independent = current.records.filterNot { it.key in replacingKeys }
        val independentKeys = independent.map { it.key }.toSet()
        require(retainedKeys.none { it in independentKeys }) { "SYNC_RECOVERY_ID_COLLISION" }
        val restoredUnits = linkedSetOf(unit)
        retained.filter { it.type == "chat_sessions" }.toList().forEach { session ->
            val id = session.text("formatCardId") ?: return@forEach
            val key = "format_cards/$id"
            if (independent.none { it.key == key } && retained.none { it.key == key }) {
                retained += requireNotNull(old.records.firstOrNull { it.key == key }) { "SYNC_RECOVERY_DEPENDENCY" }
                restoredUnits += key
            }
        }
        val records = independent + retained
        val resourceIds = LanSyncScope.referencedResources(records)
        val resources = (current.resources + old.resources).filterKeys(resourceIds::contains)
        val snapshot = current.copy(records = records, resources = resources)
        snapshot.validate(); LanSyncScope.validateGraph(snapshot)
        val expected = current.hashes()
        val restoredHashes = snapshot.hashes()
        val binding = lanSyncHash(buildJsonObject {
            put("revision", revision); put("source", entry.transaction); put("sourceSnapshot", entry.snapshotHash); put("unit", unit)
            put("current", JsonObject(expected.toSortedMap().mapValues { JsonPrimitive(it.value) }))
            put("restored", JsonObject(restoredHashes.toSortedMap().mapValues { JsonPrimitive(it.value) }))
        })
        return LanSyncRestorePreview(revision, entry.transaction, entry.snapshotHash, unit, restoredUnits, expected,
            LanSyncPlan(revision, snapshot, emptyMap()), replacing.values.flatten(), retained, binding, expected != restoredHashes)
    }
}
