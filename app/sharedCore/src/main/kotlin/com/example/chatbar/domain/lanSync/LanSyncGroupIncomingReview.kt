package com.example.chatbar.domain.lanSync

import java.util.Collections
import kotlinx.serialization.json.*

data class LanSyncGroupIncomingUnitReview(val unit: String, val binding: String,
    val current: List<LanSyncRecord>, val proposed: List<LanSyncRecord>, val impact: List<LanSyncRecordImpact>)

/** Exact local confirmation of already signed incoming facts; never a new source author approval. */
class LanSyncGroupIncomingApproval internal constructor(internal val review: LanSyncGroupIncomingReview,
    internal val confirmations: Map<String, String>)

/** Default reductions stay at their old native receipts, including complete linked components. */
class LanSyncGroupIncomingReview private constructor(internal val native: LanSyncGroupNativePlan,
    val reductions: Map<String, LanSyncGroupIncomingUnitReview>) {
    val requiredBindings: Map<String, String> get() = Collections.unmodifiableMap(reductions.mapValues { it.value.binding })
    val defaultPlan: LanSyncGroupNativePlan get() = LanSyncGroupNativePlanner.restrict(native, reductions.keys)
    fun acknowledge(confirmations: Map<String, String>): LanSyncGroupIncomingApproval {
        require(confirmations.isNotEmpty() && confirmations.all { (unit, binding) -> reductions[unit]?.binding == binding }) {
            "SYNC_GROUP_INCOMING_CONFIRMATION_REQUIRED"
        }
        val remaining = reductions.keys - confirmations.keys
        require(confirmations.keys.none { native.components.getValue(it).intersect(remaining).isNotEmpty() }) {
            "SYNC_GROUP_INCOMING_COMPONENT_CONFIRMATION_REQUIRED"
        }
        return LanSyncGroupIncomingApproval(this, Collections.unmodifiableMap(confirmations.toMap()))
    }
    internal fun confirmedPlan(approval: LanSyncGroupIncomingApproval): LanSyncGroupNativePlan {
        require(approval.review === this) { "SYNC_GROUP_INCOMING_REVIEW_CHANGED" }
        acknowledge(approval.confirmations)
        return LanSyncGroupNativePlanner.restrict(native, reductions.keys - approval.confirmations.keys)
    }
    companion object {
        fun prepare(native: LanSyncGroupNativePlan): LanSyncGroupIncomingReview {
            val before = native.before.units(); val after = native.plan.snapshot.units()
            val context = lanSyncHash(buildJsonObject {
                put("device", native.before.deviceId)
                put("checkpoint", native.checkpoint.id.value); put("checkpointHash", native.checkpoint.bodyHash)
                put("heads", JsonArray(native.heads.sortedBy { it.id.value }.map {
                    buildJsonObject { put("id", it.id.value); put("hash", it.bodyHash) }
                }))
                put("source", JsonObject(native.sourceFiles.toSortedMap().mapValues { JsonPrimitive(it.value) }))
                put("before", JsonObject(native.expected.toSortedMap().mapValues { JsonPrimitive(it.value) }))
                put("target", JsonObject(native.plan.snapshot.hashes().toSortedMap().mapValues { JsonPrimitive(it.value) }))
            })
            val rows = native.advances.keys.sorted().mapNotNull { unit ->
                val current = before[unit].orEmpty(); val proposed = after[unit].orEmpty()
                val impact = LanSyncImpactReview.changes(current, proposed)
                if (impact.none { it.needsConfirmation }) return@mapNotNull null
                val binding = lanSyncHash(buildJsonObject {
                    put("context", context); put("unit", unit)
                    put("applied", JsonArray(native.appliedBefore[unit]?.frontiers.orEmpty().sortedBy { it.id.value }.map {
                        buildJsonObject { put("id", it.id.value); put("hash", it.bodyHash) }
                    }))
                    put("incoming", JsonArray(native.advances.getValue(unit).frontiers.sortedBy { it.id.value }.map {
                        buildJsonObject { put("id", it.id.value); put("hash", it.bodyHash) }
                    }))
                })
                unit to LanSyncGroupIncomingUnitReview(unit, binding, Collections.unmodifiableList(current.toList()),
                    Collections.unmodifiableList(proposed.toList()), Collections.unmodifiableList(impact))
            }.toMap()
            return LanSyncGroupIncomingReview(native, Collections.unmodifiableMap(rows))
        }
    }
}
