package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.entity.MemoryNode
import com.example.chatbar.domain.memory.MemoryHashes
import java.util.ArrayDeque
import kotlinx.serialization.json.*

/** Rebind program-owned ancestry proofs using shared hashes; never alter formal summary/body text.
 * Selected official147 BackupMergeMemory supplies the placeholder and immutable-node semantics.
 * This bounded in-memory form consumes already bounded verified entities; no scratch deletion or IO.
 */
internal class DesktopMergeMemoryProofs(records: Collection<DesktopMergeRecord>, private val identity: DesktopMergeIdentity) {
    private val nodes = linkedMapOf<String, MemoryNode>()
    private val rebound = linkedMapOf<String, MemoryNode>()

    init {
        fun visit(record: DesktopMergeRecord, value: JsonElement, path: List<String>) {
            when (value) {
                is JsonObject -> {
                    val node = isNode(record, path)
                    if (node) {
                        val parsed = backupJson.decodeFromJsonElement(MemoryNode.serializer(), value)
                        require(parsed.id.isNotBlank() && nodes.size < 40000) { "MERGE_MEMORY_NODE_BUDGET_OR_ID" }
                        nodes[parsed.id]?.let { old ->
                            require(old.body == parsed.body && old.childIds == parsed.childIds && old.sourceTurnIds == parsed.sourceTurnIds &&
                                old.coverageUnits == parsed.coverageUnits && old.sourceHash == parsed.sourceHash && old.coverageHash == parsed.coverageHash) {
                                "MERGE_MEMORY_NODE_CONFLICT"
                            }
                        }
                        if (parsed.id !in nodes || record.type == "memory_nodes") nodes[parsed.id] = parsed
                    }
                    value.forEach { (key, child) -> visit(record, child, path + key) }
                }
                is JsonArray -> value.forEach { visit(record, it, path) }
                else -> Unit
            }
        }
        records.forEach { if (it.type.startsWith("memory_") || it.type == "save_slots") visit(it, it.value, emptyList()) }
        require(nodes.values.sumOf { it.childIds.size.toLong() } <= 80000) { "MERGE_MEMORY_EDGE_BUDGET" }
        val visiting = hashSetOf<String>()
        for (start in nodes.keys) {
            if (start in rebound) continue
            val stack = ArrayDeque<Pair<String, Boolean>>(); stack.push(start to false)
            while (stack.isNotEmpty()) {
                val (id, leaving) = stack.pop()
                if (id in rebound) continue
                val node = requireNotNull(nodes[id]) { "MERGE_MEMORY_CHILD_MISSING" }
                if (!leaving) {
                    require(visiting.add(id)) { "MERGE_MEMORY_CYCLE" }
                    stack.push(id to true)
                    node.childIds.asReversed().forEach { stack.push(it to false) }
                } else {
                    val children = node.childIds.map { requireNotNull(rebound[it]) }
                    val units = node.coverageUnits.map { unit ->
                        if (node.childIds.isEmpty()) unit else {
                            val originalChild = requireNotNull(nodes[unit.sourceId]) { "MERGE_MEMORY_COVERAGE_CHILD_MISSING" }
                            val child = requireNotNull(rebound[unit.sourceId])
                            unit.copy(sourceId = identity.value("memory_nodes", unit.sourceId),
                                text = if (unit.text == originalChild.coverageHash && unit.text.isNotBlank()) child.coverageHash else unit.text)
                        }
                    }
                    val changed = identity.value("memory_nodes", id) != id || node.childIds.any { identity.value("memory_nodes", it) != it }
                    rebound[id] = node.copy(id = identity.value("memory_nodes", id), childIds = children.map { it.id }, coverageUnits = units,
                        sourceHash = if (children.isNotEmpty() && changed) MemoryHashes.text(children.joinToString("\n") { "${it.id}:${it.sourceHash}" }) else node.sourceHash,
                        coverageHash = if (children.isNotEmpty() && changed) MemoryHashes.parentCoverage(children, units) else node.coverageHash)
                    visiting.remove(id)
                }
            }
        }
    }

    /** Apply to the node after ordinary typed identity mapping. Original field presence is retained. */
    fun rewrite(original: JsonObject, mapped: JsonObject): JsonObject {
        val id = original.getValue("id").jsonPrimitive.content
        val node = requireNotNull(nodes[id]); val target = requireNotNull(rebound[id])
        val fields = mapped.toMutableMap()
        if ("sourceHash" in original) fields["sourceHash"] = JsonPrimitive(target.sourceHash)
        if ("coverageHash" in original) fields["coverageHash"] = JsonPrimitive(target.coverageHash)
        if (node.childIds.isNotEmpty() && "coverageUnits" in original) {
            fields["coverageUnits"] = JsonArray(original.getValue("coverageUnits").jsonArray.mapIndexed { index, value ->
                val unit = value.jsonObject.toMutableMap(); val targetUnit = target.coverageUnits[index]
                unit["sourceId"] = JsonPrimitive(targetUnit.sourceId); unit["text"] = JsonPrimitive(targetUnit.text)
                JsonObject(unit)
            })
        }
        return JsonObject(fields)
    }

    companion object {
        fun isNode(record: DesktopMergeRecord, path: List<String>) =
            path.none { it in setOf("extensions", "customParams", "voice", "initialResearch", "research") } &&
                (path.isEmpty() && record.type == "memory_nodes" || path.lastOrNull() == "nodes" &&
                    (record.type.startsWith("memory_") || record.type == "save_slots"))
    }
}
