package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.SaveSlot
import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
enum class LanSyncRecordChange { ADD, REPLACE, REMOVE }

data class LanSyncReductionSummary(val recordsRemoved: Long, val liveMessagesRemoved: Long,
    val versionsRemoved: Long, val attachmentReferencesRemoved: Long, val recordsOverwritten: Long)

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
    val removedEmbeddedMessageIds: Set<String> = emptySet(),
) {
    val needsConfirmation: Boolean get() = change == LanSyncRecordChange.REMOVE || removedVersionIds.isNotEmpty() ||
        removedAttachmentReferences > 0 || removedEmbeddedMessageIds.isNotEmpty()
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
    val computerArchivedMessages: Int = 0,
    val phoneArchivedMessages: Int = 0,
) {
    // The old baseline contains hashes, never an invented ancestor's plaintext.
    val ancestorBodyAvailable: Boolean get() = false
    val needsConfirmation: Boolean get() = (computerImpact + phoneImpact).any { it.needsConfirmation }
}

/** Pure, frozen-capture review. Publication still performs the original fresh source CAS. */
object LanSyncImpactReview {
    fun reductionSummary(computer: List<LanSyncRecord>, phone: List<LanSyncRecord>, proposed: List<LanSyncRecord>): LanSyncReductionSummary {
        val impacts = changes(computer, proposed) + changes(phone, proposed)
        fun count(select: (LanSyncRecordImpact) -> Long) = impacts.fold(0L) { total, row -> lanSyncAddLength(total, select(row)) }
        return LanSyncReductionSummary(count { if (it.change == LanSyncRecordChange.REMOVE) 1 else 0 },
            count { if (it.message && it.change == LanSyncRecordChange.REMOVE) 1 else 0 },
            count { it.removedVersionIds.size.toLong() }, count { it.removedAttachmentReferences.toLong() },
            count { if (it.change == LanSyncRecordChange.REPLACE) 1 else 0 })
    }
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
            LanSyncRecordImpact(key, change, (a ?: b)?.type == "chat_messages", versions(a) - versions(b), removedReferences, fields,
                embeddedMessages(a).map { it.id }.toSet() - embeddedMessages(b).map { it.id }.toSet())
        }
    }

    fun automaticReviews(comparison: LanSyncComparison, baseline: LanSyncBaseline): List<LanSyncUnitReview> =
        comparison.automatic.entries.sortedBy { it.key }.map { (unit, computer) ->
            val left = comparison.computerUnits[unit].orEmpty(); val right = comparison.phoneUnits[unit].orEmpty()
            val result = if (computer) left else right
            LanSyncUnitReview(unit, comparison.computerHashes[unit], comparison.phoneHashes[unit], baseline.hashes[unit],
                left.size, right.size, left.count { it.type == "chat_messages" }, right.count { it.type == "chat_messages" },
                changes(left, result), changes(right, result), computer,
                left.sumOf { embeddedMessages(it).size }, right.sumOf { embeddedMessages(it).size })
        }

    fun binding(comparison: LanSyncComparison): String = lanSyncHash(buildJsonObject {
        put("computer", JsonObject(comparison.computerHashes.toSortedMap().mapValues { JsonPrimitive(it.value) }))
        put("phone", JsonObject(comparison.phoneHashes.toSortedMap().mapValues { JsonPrimitive(it.value) }))
    })

    private fun versions(record: LanSyncRecord?): Set<String> {
        if (record?.type == "save_slots") return embeddedMessages(record).flatMap { message ->
            val normalized = MessageAlternativeVersionPolicy.normalize(message)
            (normalized.alternativeVersionIds + listOfNotNull(normalized.currentAlternativeVersionId)).map { "${message.id}/$it" }
        }.toSet()
        if (record?.type != "chat_messages") return emptySet()
        val message = MessageAlternativeVersionPolicy.normalize(lanSyncJson.decodeFromJsonElement(ChatMessage.serializer(), record.body))
        return message.alternativeVersionIds.toSet() + listOfNotNull(message.currentAlternativeVersionId)
    }

    private fun embeddedMessages(record: LanSyncRecord?): List<ChatMessage> {
        if (record?.type != "save_slots") return emptyList()
        val slot = lanSyncJson.decodeFromJsonElement(SaveSlot.serializer(), record.body)
        return if (slot.packageRef == null) slot.messages else emptyList()
    }

    private fun resourceCounts(record: LanSyncRecord?): Map<String, Int> {
        if (record == null) return emptyMap()
        val counts = linkedMapOf<String, Int>()
        LanSyncWorkspace.mapResources(record.comparisonBody()) { value, _ ->
            if (value.startsWith("ccb-lan-content/")) {
                val parts = value.split('/')
                require(parts.size == 5) { "SYNC_REVIEW_RESOURCE" }
                // comparisonBody retains within-record alias groups, independent of device
                // filenames: [A,B] and [C,D] align, while [A,B] to [B,B] loses one group.
                val key = value
                counts[key] = Math.addExact(counts.getOrDefault(key, 0), 1)
            } else if (value.startsWith(com.example.chatbar.domain.chat.SAVE_SLOT_IMAGE_PREFIX) ||
                value.startsWith(com.example.chatbar.domain.chat.SAVE_SLOT_AUDIO_PREFIX)) {
                counts[value] = Math.addExact(counts.getOrDefault(value, 0), 1)
            }
            value
        }
        return counts
    }
}
