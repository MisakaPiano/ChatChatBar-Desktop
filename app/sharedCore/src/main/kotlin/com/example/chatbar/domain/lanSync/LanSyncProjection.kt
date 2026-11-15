package com.example.chatbar.domain.lanSync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class LanSyncIdBinding(val type: String, val localId: String, val wireId: String)

/** Device-local projection: physical IDs and references in excluded stores remain untouched. */
@Serializable
data class LanSyncProjection(val bindings: List<LanSyncIdBinding> = emptyList()) {
    fun digest(): String = lanSyncHash(JsonArray(bindings.sortedWith(compareBy({ it.type }, { it.localId })).map {
        buildJsonObject { put("type", it.type); put("localId", it.localId); put("wireId", it.wireId) }
    }))
    fun validate() {
        require(bindings.size <= 80_000 && bindings.all {
            it.type in spaces && it.localId.isNotBlank() && it.wireId.isNotBlank() &&
                it.localId.length <= 256 && it.wireId.length <= 256
        }) { "SYNC_PROJECTION_ID" }
        require(bindings.map { it.type to it.localId }.toSet().size == bindings.size &&
            bindings.map { it.type to it.wireId }.toSet().size == bindings.size) { "SYNC_PROJECTION_ALIAS" }
    }
    internal fun rewrite(records: List<LanSyncRecord>, toLocal: Boolean = false): List<LanSyncRecord> {
        validate()
        if (bindings.isEmpty()) return records
        val identity = LanSyncIdentity()
        bindings.forEach { identity.bind(it.type, if (toLocal) it.wireId else it.localId, if (toLocal) it.localId else it.wireId) }
        val declarations = records.filterNot { it.type == "save_slots" && it.body["packageRef"] is JsonObject }
            .flatMap { identity.declarations(it) }.toSet()
        require(declarations.map { (type, id) -> type to identity.value(type, id) }.toSet().size == declarations.size) { "SYNC_PROJECTION_COLLISION" }
        val remapper = LanSyncGraphRemapper(records, identity)
        return records.map { record ->
            // A v8 library envelope and its archive retain their independent historical namespace.
            if (record.type == "save_slots" && record.body["packageRef"] is JsonObject) record else {
                val body = remapper.rewrite(record)
                record.copy(id = requireNotNull(LanSyncScope.identity(record.type, body)), body = body)
            }
        }.also { require(it.map(LanSyncRecord::key).toSet().size == it.size) { "SYNC_PROJECTION_COLLISION" } }
    }
    companion object {
        private val spaces = setOf("character_cards", "format_cards", "world_books", "characters", "documents", "world_entries")
    }
}

@Serializable
data class LanSyncProjectionOffer(val projection: LanSyncProjection = LanSyncProjection(),
    val sourceHashes: Map<String, String> = emptyMap(), val computerPresets: List<LanSyncRecord> = emptyList())

/** Only unique provenance plus complete semantic equivalence admits an initial identity projection. */
internal object LanSyncPresetProjection {
    private val roots = setOf("character_cards", "format_cards", "world_books")
    fun presets(records: List<LanSyncRecord>) = records.filter { it.type in roots && !it.text("sourcePresetKey").isNullOrBlank() }
    private fun declarations(record: LanSyncRecord) = LanSyncIdentity().declarations(record.copy(body = ordered(record.body).jsonObject))
        .groupBy({ it.first }, { it.second })
    fun propose(computer: List<LanSyncRecord>, phone: List<LanSyncRecord>): LanSyncProjection {
        fun key(record: LanSyncRecord) = record.type to record.text("sourcePresetKey")
        val left = presets(computer).groupBy(::key)
        val right = presets(phone).groupBy(::key)
        var candidates = left.keys.intersect(right.keys).sortedWith(compareBy({ it.first }, { it.second })).mapNotNull { key ->
            val a = left.getValue(key).singleOrNull(); val b = right.getValue(key).singleOrNull()
            if (a == null || b == null || a.body["sourcePresetVersion"] != b.body["sourcePresetVersion"]) null else {
                val aIds = declarations(a); val bIds = declarations(b)
                if (aIds.keys == bIds.keys && aIds.all { (type, ids) -> ids.size == bIds.getValue(type).size }) a to b else null
            }
        }
        fun projection(pairs: List<Pair<LanSyncRecord, LanSyncRecord>>): LanSyncProjection? {
            val bindings = linkedMapOf<Pair<String, String>, LanSyncIdBinding>()
            pairs.forEach { (a, b) ->
                val aIds = declarations(a); val bIds = declarations(b)
                aIds.forEach { (type, ids) -> ids.zip(bIds.getValue(type)).forEach { (wire, local) ->
                    if (wire != local) {
                        val value = LanSyncIdBinding(type, local, wire)
                        if (bindings[type to local]?.let { it != value } == true) return null
                        bindings[type to local] = value
                    }
                } }
            }
            if (bindings.size > 80_000 || bindings.values.map { it.type to it.wireId }.toSet().size != bindings.size) return null
            val value = LanSyncProjection(bindings.values.toList().sortedWith(compareBy({ it.type }, { it.localId })))
            value.validate()
            try { value.rewrite(phone) } catch (failure: IllegalArgumentException) {
                if (failure.message == "SYNC_PROJECTION_COLLISION") return null else throw failure
            }
            return value
        }
        repeat(32) {
            val value = projection(candidates) ?: return LanSyncProjection()
            val projected = value.rewrite(phone).associateBy(LanSyncRecord::key)
            val retained = candidates.filter { (a, b) ->
                val canonicalId = value.bindings.firstOrNull { it.type == b.type && it.localId == b.id }?.wireId ?: b.id
                a.comparisonBody() == projected["${b.type}/$canonicalId"]?.comparisonBody()
            }
            if (retained.size == candidates.size) return value
            candidates = retained
        }
        return LanSyncProjection() // No partial choice if an unusually deep dependency graph cannot settle.
    }
    private fun ordered(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { ordered(it.value) })
        is JsonArray -> JsonArray(value.map(::ordered))
        else -> value
    }
}
