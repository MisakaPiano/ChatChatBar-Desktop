package com.example.chatbar.domain.lanSync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.UUID

const val LAN_SYNC_PROTOCOL = 3

/** Protocol identities are independent of filenames, clocks, IP addresses and display names. */
@Serializable
data class LanSyncRecord(val type: String, val id: String, val body: JsonObject) {
    internal val value get() = body
    internal val singleton get() = false
    internal fun text(field: String) = (body[field] as? JsonPrimitive)?.contentOrNull
    internal fun comparisonBody(): JsonObject {
        val ignored = if (!text("sourcePresetKey").isNullOrBlank()) setOf("updatedAt", "createdAt") else setOf("updatedAt")
        val aliases = linkedMapOf<String, Int>()
        return LanSyncWorkspace.mapResources(JsonObject(body.filterKeys { it !in ignored })) { reference, _ ->
            if (reference.startsWith("ccb-lan-v1/")) {
                val parts = reference.split('/')
                if (parts.size == 5) {
                    val ordinal = aliases.getOrPut("${parts[1]}/${parts[2]}") { aliases.size }
                    "ccb-lan-content/${parts[1]}/${parts[3]}/${parts[4]}/$ordinal"
                } else reference
            } else reference
        }
    }
    val key: String get() = "$type/$id"
    val unit: String get() = when {
        type == "chat_sessions" -> "conversation/$id"
        type == "vector_chunks" && text("sourceType") == "CHAT_MEMORY" -> "conversation/${text("sourceId")}"
        type == "vector_chunks" -> "character_cards/${text("sourceId")}"
        body["sessionId"] is JsonPrimitive -> "conversation/${body.getValue("sessionId").jsonPrimitive.content}"
        else -> key
    }
}

@Serializable
data class LanSyncSnapshot(
    val protocol: Int = LAN_SYNC_PROTOCOL,
    val deviceId: String,
    val records: List<LanSyncRecord>,
    val resources: Map<String, Long> = emptyMap(),
    val excludedTypes: Set<String> = emptySet(),
    val coveredTypes: Set<String> = LanSyncScope.schemas.keys,
) {
    fun validate() {
        require(protocol == LAN_SYNC_PROTOCOL && deviceId.isNotBlank()) { "SYNC_PROTOCOL" }
        require(records.size <= 100_000 && records.map { it.key }.toSet().size == records.size) { "SYNC_IDENTITIES" }
        records.forEach {
            require(it.type.matches(Regex("[a-z_]+")) && it.id.isNotBlank() && it.id.length <= 256) { "SYNC_ID" }
            require(it.type in coveredTypes && LanSyncScope.identity(it.type, it.body) == it.id) { "SYNC_BODY_ID" }
        }
        require(resources.keys.all { it.matches(Regex("[0-9a-f]{64}")) } && resources.values.all { it in 1..MAX_RESOURCE }) { "SYNC_RESOURCE" }
    }

    fun units(): Map<String, List<LanSyncRecord>> = records.groupBy { it.unit }
    fun hashes(): Map<String, String> = units().mapValues { (_, records) ->
        lanSyncHash(JsonArray(records.sortedBy { it.key }.map {
            buildJsonObject { put("type", it.type); put("id", it.id); put("body", it.comparisonBody()) }
        }))
    }

    companion object { const val MAX_RESOURCE: Long = 256L * 1024 * 1024 }
}

/** Missing entries after a shared baseline are tombstones, never fresh additions. */
@Serializable
data class LanSyncBaseline(
    val peerId: String,
    val revision: String? = null,
    val hashes: Map<String, String> = emptyMap(),
    val receipts: List<String> = emptyList(),
    val ownerId: String? = null,
    val coveredTypes: Set<String> = LanSyncScope.schemas.keys,
    val localProjectionHash: String? = null,
)

@Serializable
enum class LanSyncChoice { KEEP_COMPUTER, KEEP_PHONE, KEEP_BOTH }

@Serializable
data class LanSyncConflict(
    val unit: String,
    val title: String,
    val computerDeleted: Boolean,
    val phoneDeleted: Boolean,
    val computerRecords: Int,
    val phoneRecords: Int,
)

data class LanSyncComparison(
    val automatic: Map<String, Boolean>, // true: computer, false: phone
    val conflicts: List<LanSyncConflict>,
    val computerHashes: Map<String, String>,
    val phoneHashes: Map<String, String>,
)

/** Three-way comparison deliberately has no last-write-wins or timestamp tie breaker. */
object LanSyncCompare {
    internal fun requireCoverage(computer: LanSyncSnapshot, phone: LanSyncSnapshot, baseline: LanSyncBaseline) {
        require(computer.coveredTypes == phone.coveredTypes && computer.coveredTypes.containsAll(baseline.coveredTypes)) { "SYNC_SCOPE_MISMATCH" }
    }
    fun compare(computer: LanSyncSnapshot, phone: LanSyncSnapshot, baseline: LanSyncBaseline): LanSyncComparison {
        computer.validate(); phone.validate()
        requireCoverage(computer, phone, baseline)
        require(baseline.peerId == phone.deviceId && (baseline.ownerId == null && baseline.revision == null || baseline.ownerId == computer.deviceId)) { "SYNC_BASELINE_DEVICE" }
        val left = computer.hashes(); val right = phone.hashes()
        val automatic = linkedMapOf<String, Boolean>()
        val conflicts = mutableListOf<LanSyncConflict>()
        val leftUnits = computer.units(); val rightUnits = phone.units()
        (left.keys + right.keys + baseline.hashes.keys).sorted().forEach { key ->
            val a = left[key]; val b = right[key]; val old = baseline.hashes[key]
            when {
                a == b -> automatic[key] = true
                a == old -> automatic[key] = false
                b == old -> automatic[key] = true
                else -> {
                    val l = leftUnits[key].orEmpty(); val r = rightUnits[key].orEmpty()
                    val owner = (l + r).firstOrNull { it.type == "chat_sessions" } ?: (l + r).first()
                    val title = listOf("displayTitleOverride", "title", "name").firstNotNullOfOrNull {
                        (owner.body[it] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                    } ?: owner.type
                    conflicts += LanSyncConflict(key, title.take(160), a == null, b == null, l.size, r.size)
                }
            }
        }
        return LanSyncComparison(automatic, conflicts, left, right)
    }
}

internal fun lanSyncCanonical(value: JsonElement): String = when (value) {
    is JsonObject -> value.toSortedMap().entries.joinToString(",", "{", "}") { (key, child) -> "${JsonPrimitive(key)}:${lanSyncCanonical(child)}" }
    is JsonArray -> value.joinToString(",", "[", "]", transform = ::lanSyncCanonical)
    else -> value.toString()
}

fun lanSyncHash(value: JsonElement): String = lanSyncHash(lanSyncCanonical(value).toByteArray(Charsets.UTF_8))
fun lanSyncHash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
internal fun lanSyncDerivedId(transaction: String, source: String): String =
    UUID.nameUUIDFromBytes("ccb-lan-v1\u0000$transaction\u0000$source".toByteArray(Charsets.UTF_8)).toString()

internal val lanSyncJson = Json { encodeDefaults = true; ignoreUnknownKeys = false }
