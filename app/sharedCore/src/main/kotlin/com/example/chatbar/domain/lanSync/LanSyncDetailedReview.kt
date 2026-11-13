package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.SaveSlot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class LanSyncReviewHeader(val unit: String, val computerHash: String?, val phoneHash: String?,
    val ancestorHash: String?, val computerKeys: Set<String>, val phoneKeys: Set<String>)

@Serializable
data class LanSyncReviewDescription(val binding: String, val conflicts: List<LanSyncConflict>,
    val headers: List<LanSyncReviewHeader>)

@Serializable
data class LanSyncReviewVote(val binding: String, val choices: Map<String, LanSyncChoice> = emptyMap())

@Serializable
data class LanSyncReviewContext(val ancestor: LanSyncBaseline, val description: LanSyncReviewDescription)

data class LanSyncDetailedUnit(val header: LanSyncReviewHeader, val computer: List<LanSyncRecord>,
    val phone: List<LanSyncRecord>) {
    val recordKeys get() = (computer.map { it.key } + phone.map { it.key }).distinct().sorted()
    val computerMessages get() = computer.count { it.type == "chat_messages" }
    val phoneMessages get() = phone.count { it.type == "chat_messages" }
    private fun archived(rows: List<LanSyncRecord>): Long = rows.filter { it.type == "save_slots" }.fold(0L) { count, record ->
        val slot = lanSyncJson.decodeFromJsonElement(SaveSlot.serializer(), record.body)
        lanSyncAddLength(count, (slot.packageRef?.messageCount ?: slot.messages.size).toLong())
    }
    val computerArchivedMessages get() = archived(computer)
    val phoneArchivedMessages get() = archived(phone)
    fun record(key: String, computerSide: Boolean) = (if (computerSide) computer else phone).firstOrNull { it.key == key }
    fun fields(key: String) = (record(key, true)?.body.orEmpty().keys + record(key, false)?.body.orEmpty().keys).sorted()
    fun fieldPage(key: String, field: String, computerSide: Boolean, page: Int): Pair<String, Int> {
        require(page >= 0)
        val value = record(key, computerSide)?.body?.get(field)?.let { if (it is JsonPrimitive && it.isString) it.content else it.toString() }
            ?: return "此端无此字段" to 1
        val pages = maxOf(1, (value.length.toLong() + 8191) / 8192).toInt()
        if (page >= pages) return "此端正文已结束" to pages
        val base = page * 8192
        val start = if (base > 0 && value[base].isLowSurrogate() && value[base - 1].isHighSurrogate()) base - 1 else base
        var end = minOf(base.toLong() + 8192, value.length.toLong()).toInt()
        if (end < value.length && value[end - 1].isHighSurrogate() && value[end].isLowSurrogate()) end--
        return value.substring(start, end) to pages
    }
}

object LanSyncDetailedReview {
    fun description(comparison: LanSyncComparison, baseline: LanSyncBaseline): LanSyncReviewDescription {
        val headers = comparison.conflicts.sortedBy { it.unit }.map { conflict ->
            LanSyncReviewHeader(conflict.unit, comparison.computerHashes[conflict.unit], comparison.phoneHashes[conflict.unit],
                comparison.ancestorHashes[conflict.unit], comparison.computerUnits[conflict.unit].orEmpty().map { it.key }.toSet(),
                comparison.phoneUnits[conflict.unit].orEmpty().map { it.key }.toSet())
        }
        val binding = lanSyncHash(buildJsonObject {
            put("captures", LanSyncImpactReview.binding(comparison))
            put("ancestor", JsonObject(baseline.hashes.toSortedMap().mapValues { JsonPrimitive(it.value) }))
            put("review", lanSyncJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(LanSyncReviewHeader.serializer()), headers))
        })
        return LanSyncReviewDescription(binding, comparison.conflicts, headers)
    }
    fun details(description: LanSyncReviewDescription, comparison: LanSyncComparison): List<LanSyncDetailedUnit> = description.headers.map { header ->
        val left = comparison.computerUnits[header.unit].orEmpty(); val right = comparison.phoneUnits[header.unit].orEmpty()
        require(header.computerKeys == left.map { it.key }.toSet() && header.phoneKeys == right.map { it.key }.toSet() &&
            header.computerHash == comparison.computerHashes[header.unit] && header.phoneHash == comparison.phoneHashes[header.unit]) { "SYNC_REVIEW_BINDING" }
        LanSyncDetailedUnit(header, left, right)
    }
}
