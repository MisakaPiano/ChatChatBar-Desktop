package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
enum class LanSyncRecordChange { ADD, REPLACE, REMOVE }

@Serializable
data class LanSyncFieldChange(val field: String, val before: JsonElement?, val after: JsonElement?)

@Serializable
data class LanSyncRecordImpact(
    val key: String,
    val change: LanSyncRecordChange,
    val message: Boolean,
    val removedVersionIds: Set<String> = emptySet(),
    val removedAttachmentReferences: Int = 0,
    val fields: List<LanSyncFieldChange> = emptyList(),
) {
    val needsConfirmation: Boolean get() = change == LanSyncRecordChange.REMOVE || removedVersionIds.isNotEmpty() || removedAttachmentReferences > 0
    val needsProtection: Boolean get() = change != LanSyncRecordChange.ADD
}

@Serializable
data class LanSyncUnitReview(
    val unit: String,
    val computerHash: String?,
    val phoneHash: String?,
    val ancestorHash: String?,
    val computerRecords: Int,
    val phoneRecords: Int,
    val computerMessages: Int,
    val phoneMessages: Int,
    val computerImpact: List<LanSyncRecordImpact>,
    val phoneImpact: List<LanSyncRecordImpact>,
    val automaticComputer: Boolean?,
) {
    // The old baseline contains hashes, never an invented ancestor's plaintext.
    val ancestorBodyAvailable: Boolean get() = false
    val needsConfirmation: Boolean get() = (computerImpact + phoneImpact).any { it.needsConfirmation }
}

/** Pure, frozen-capture review. Publication still performs the original fresh source CAS. */
object LanSyncImpactReview {
    fun changes(before: List<LanSyncRecord>, after: List<LanSyncRecord>): List<LanSyncRecordImpact> {
        val old = before.associateBy { it.key }; val desired = after.associateBy { it.key }
        require(old.size == before.size && desired.size == after.size) { "SYNC_REVIEW_IDENTITIES" }
        return (old.keys + desired.keys).sorted().mapNotNull { key ->
            val a = old[key]; val b = desired[key]
            if (a?.comparisonBody() == b?.comparisonBody()) return@mapNotNull null
            val change = when { a == null -> LanSyncRecordChange.ADD; b == null -> LanSyncRecordChange.REMOVE; else -> LanSyncRecordChange.REPLACE }
            val oldBody = a?.body.orEmpty(); val newBody = b?.body.orEmpty()
            val fields = (oldBody.keys + newBody.keys).sorted().filter { oldBody[it] != newBody[it] }
                .map { LanSyncFieldChange(it, oldBody[it], newBody[it]) }
            val oldResources = resourceCounts(a); val newResources = resourceCounts(b)
            val removedReferences = oldResources.entries.sumOf { (identity, count) -> maxOf(0, count - newResources.getOrDefault(identity, 0)) }
            LanSyncRecordImpact(key, change, (a ?: b)?.type == "chat_messages", versions(a) - versions(b), removedReferences, fields)
        }
    }

    fun automaticReviews(comparison: LanSyncComparison, baseline: LanSyncBaseline): List<LanSyncUnitReview> =
        comparison.automatic.entries.sortedBy { it.key }.map { (unit, computer) ->
            val left = comparison.computerUnits[unit].orEmpty(); val right = comparison.phoneUnits[unit].orEmpty()
            val result = if (computer) left else right
            LanSyncUnitReview(unit, comparison.computerHashes[unit], comparison.phoneHashes[unit], baseline.hashes[unit],
                left.size, right.size, left.count { it.type == "chat_messages" }, right.count { it.type == "chat_messages" },
                changes(left, result), changes(right, result), computer)
        }

    fun binding(comparison: LanSyncComparison): String = lanSyncHash(buildJsonObject {
        put("computer", JsonObject(comparison.computerHashes.toSortedMap().mapValues { JsonPrimitive(it.value) }))
        put("phone", JsonObject(comparison.phoneHashes.toSortedMap().mapValues { JsonPrimitive(it.value) }))
    })

    private fun versions(record: LanSyncRecord?): Set<String> {
        if (record?.type != "chat_messages") return emptySet()
        val message = MessageAlternativeVersionPolicy.normalize(lanSyncJson.decodeFromJsonElement(ChatMessage.serializer(), record.body))
        return message.alternativeVersionIds.toSet() + listOfNotNull(message.currentAlternativeVersionId)
    }

    private fun resourceCounts(record: LanSyncRecord?): Map<String, Int> {
        if (record == null) return emptyMap()
        val counts = linkedMapOf<String, Int>()
        LanSyncWorkspace.mapResources(record.body) { value, _ ->
            if (value.startsWith("ccb-lan-v1/")) {
                val parts = value.split('/')
                require(parts.size == 5) { "SYNC_REVIEW_RESOURCE" }
                val key = "${parts[1]}/${parts[3]}/${parts[4]}"
                counts[key] = Math.addExact(counts.getOrDefault(key, 0), 1)
            }
            value
        }
        return counts
    }
}
