package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.MemoryCoverageUnit
import com.example.chatbar.data.local.entity.MemoryNode
import com.example.chatbar.domain.memory.MemoryHashes
import java.util.ArrayDeque
import kotlinx.serialization.json.*

/** Rebind program-owned ancestry proofs without retaining the original summary/body trees. */
internal class LanSyncMemoryProofs(records: Collection<LanSyncRecord>, private val identity: LanSyncIdentity,
    private val checkActive: () -> Unit = {}) {
    private data class Location(val record: LanSyncRecord, val path: List<Any>) {
        fun read(body: JsonObject = record.body): MemoryNode {
            var value: JsonElement = body
            path.forEach { step -> value = when (step) {
                is String -> value.jsonObject.getValue(step)
                is Int -> value.jsonArray[step]
                else -> error("MERGE_MEMORY_NODE_PATH")
            } }
            return lanSyncJson.decodeFromJsonElement(MemoryNode.serializer(), value)
        }
    }
    private data class Header(val source: Location, val childIds: List<String>, val sourceTurnIds: List<String>,
        val sourceHash: String, val coverageHash: String, val createdAt: Long, val cost: Long) {
        fun read(body: JsonObject? = null) =
            (if (body == null) source.read() else source.read(body)).copy(createdAt = createdAt)
    }
    private data class Proof(val id: String, val sourceHash: String, val coverageHash: String)
    private val nodes = linkedMapOf<String, Header>()
    private val rebound = linkedMapOf<String, Proof>()

    init {
        val indexLimit = records.firstNotNullOfOrNull(LanSyncRecord::metadataIndexLimit) ?: LanSyncMetadataBudget().maximumIndexCost
        var indexCost = 0L
        var edges = 0L
        val location = mutableListOf<Any>()
        fun visit(record: LanSyncRecord, root: JsonObject, value: JsonElement, path: List<String>) {
            checkActive()
            require(path.size <= 128) { "MERGE_JSON_DEPTH" }
            if (identity.opaque(path)) return
            when (value) {
                is JsonObject -> {
                    if (isNode(record, path)) {
                        val parsed = lanSyncJson.decodeFromJsonElement(MemoryNode.serializer(), value)
                        val old = nodes[parsed.id]
                        require(parsed.id.isNotBlank() && (old != null || nodes.size < 40000)) { "MERGE_MEMORY_NODE_BUDGET_OR_ID" }
                        if (old != null) {
                            require(old.read(root.takeIf { old.source.record === record }) == parsed) { "MERGE_MEMORY_NODE_CONFLICT" }
                        }
                        if (old == null || record.type == "memory_nodes") {
                            if (old == null) {
                                edges = lanSyncAddLength(edges, parsed.childIds.size.toLong())
                                require(edges <= 80000) { "MERGE_MEMORY_EDGE_BUDGET" }
                            }
                            val locationCost = location.sumOf { 32L + (if (it is String) 4L * it.length else 0) }
                            val cost = 512L + 8L * (parsed.id.length.toLong() + parsed.sourceHash.length + parsed.coverageHash.length) +
                                parsed.childIds.sumOf { 72L + 4L * it.length } + parsed.sourceTurnIds.sumOf { 64L + 4L * it.length } + locationCost
                            indexCost = lanSyncAddLength(indexCost - (old?.cost ?: 0L), cost)
                            require(indexCost <= indexLimit) { "SYNC_METADATA_INDEX_BUDGET" }
                            nodes[parsed.id] = Header(Location(record, location.toList()), parsed.childIds, parsed.sourceTurnIds,
                                parsed.sourceHash, parsed.coverageHash, parsed.createdAt, cost)
                        }
                    }
                    value.forEach { (key, child) ->
                        location.add(key)
                        visit(record, root, child, path + key)
                        location.removeAt(location.lastIndex)
                    }
                }
                is JsonArray -> value.forEachIndexed { index, child ->
                    location.add(index)
                    visit(record, root, child, path)
                    location.removeAt(location.lastIndex)
                }
                else -> Unit
            }
        }
        // Inline historical archives form their own proof graph; their one verified record
        // can serve every node locator during initialization.
        val archive = records.singleOrNull()?.takeIf { it.type == "save_slots" }
        val archiveBody = archive?.body
        records.forEach { record ->
            if (record.type.startsWith("memory_") || record.type == "save_slots") {
                val body = if (record === archive) requireNotNull(archiveBody) else record.body
                visit(record, body, body, emptyList())
            }
        }

        val visiting = hashSetOf<String>()
        for (start in nodes.keys) {
            if (start in rebound) continue
            val stack = ArrayDeque<Pair<String, Boolean>>(); stack.push(start to false)
            while (stack.isNotEmpty()) {
                checkActive()
                val (id, leaving) = stack.pop()
                if (id in rebound) continue
                val node = requireNotNull(nodes[id]) { "MERGE_MEMORY_CHILD_MISSING" }
                if (!leaving) {
                    require(visiting.add(id)) { "MERGE_MEMORY_CYCLE" }
                    stack.push(id to true)
                    node.childIds.asReversed().forEach { stack.push(it to false) }
                } else {
                    val targetId = identity.value("memory_nodes", id)
                    val changed = targetId != id || node.childIds.any {
                        val old = nodes.getValue(it); val new = rebound.getValue(it)
                        it != new.id || old.sourceHash != new.sourceHash || old.coverageHash != new.coverageHash
                    }
                    // Coverage validation also applies to an unchanged ancestry.
                    val units = if (node.childIds.isEmpty()) emptyList() else
                        node.read(archiveBody).coverageUnits.map { remapUnit(node, it) }
                    rebound[id] = if (node.childIds.isNotEmpty() && changed) {
                        val children = node.childIds.asSequence().map { childId ->
                            val child = rebound.getValue(childId)
                            MemoryHashes.ParentCoverageChild(child.id, nodes.getValue(childId).sourceTurnIds, child.sourceHash, child.coverageHash)
                        }
                        Proof(targetId, MemoryHashes.text(children.joinToString("\n") { it.id + ":" + it.sourceHash }),
                            MemoryHashes.parentCoverage(children, units))
                    } else Proof(targetId, node.sourceHash, node.coverageHash)
                    visiting.remove(id)
                }
            }
        }
    }

    private fun remapUnit(node: Header, unit: MemoryCoverageUnit): MemoryCoverageUnit {
        require(unit.sourceId in node.childIds) { "MERGE_MEMORY_COVERAGE_NOT_CHILD" }
        val originalChild = requireNotNull(nodes[unit.sourceId]) { "MERGE_MEMORY_COVERAGE_CHILD_MISSING" }
        val child = requireNotNull(rebound[unit.sourceId])
        return unit.copy(sourceId = identity.value("memory_nodes", unit.sourceId),
            text = if (unit.text == originalChild.coverageHash && unit.text.isNotBlank()) child.coverageHash else unit.text)
    }

    /** Apply to the node after ordinary typed identity mapping. Original field presence is retained. */
    fun rewrite(original: JsonObject, mapped: JsonObject): JsonObject {
        val id = original.getValue("id").jsonPrimitive.content
        val node = requireNotNull(nodes[id]); val target = requireNotNull(rebound[id])
        val fields = mapped.toMutableMap()
        if ("sourceHash" in original) fields["sourceHash"] = JsonPrimitive(target.sourceHash)
        if ("coverageHash" in original) fields["coverageHash"] = JsonPrimitive(target.coverageHash)
        if (node.childIds.isNotEmpty() && "coverageUnits" in original) {
            fields["coverageUnits"] = JsonArray(original.getValue("coverageUnits").jsonArray.map { value ->
                val unit = value.jsonObject.toMutableMap()
                val targetUnit = remapUnit(node, lanSyncJson.decodeFromJsonElement(MemoryCoverageUnit.serializer(), value))
                unit["sourceId"] = JsonPrimitive(targetUnit.sourceId); unit["text"] = JsonPrimitive(targetUnit.text)
                JsonObject(unit)
            })
        }
        return JsonObject(fields)
    }

    companion object {
        fun isNode(record: LanSyncRecord, path: List<String>) =
            path.none { it in setOf("extensions", "customParams", "voice", "initialResearch", "research") } &&
                (path.isEmpty() && record.type == "memory_nodes" || path.lastOrNull() == "nodes" &&
                    (record.type.startsWith("memory_") || record.type == "save_slots"))
    }
}
