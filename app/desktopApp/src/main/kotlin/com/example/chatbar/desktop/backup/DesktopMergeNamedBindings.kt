package com.example.chatbar.desktop.backup

import java.text.Normalizer
import java.util.Locale
import kotlinx.serialization.json.*

/** Typed identities of selected owners; no text renaming or source mutation. */
internal object DesktopMergeNamedBindings {
    private val groups = mapOf("characters" to "characters", "customDocuments" to "documents", "entries" to "world_entries",
        "characterBook.entries" to "world_entries", "characterBook" to "world_books")
    private fun name(value: String) = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    private fun objects(value: JsonObject, path: String): List<JsonObject> {
        var current: List<JsonElement> = listOf(value)
        for (part in path.split('.')) current = current.flatMap { item ->
            when (val child = (item as? JsonObject)?.get(part)) {
                is JsonArray -> child.toList()
                null, JsonNull -> emptyList()
                else -> listOf(child)
            }
        }
        return current.map { it.jsonObject }
    }

    fun bind(selection: DesktopMergePreview.Selection, identity: DesktopMergeIdentity, active: () -> Unit,
        forkDeclarations: Set<Pair<String, String>> = emptySet()) {
        fun owner(unit: DesktopMergePreview.Unit, type: String, id: String): JsonObject {
            val value = unit.records.entries.single { DesktopBackupPolicy.entityType(it.key) == type }.value
            require(unit.category == type && value["id"]?.jsonPrimitive?.contentOrNull == id) { "MERGE_ALIAS_SOURCE_IDENTITY" }
            return value
        }
        val reserved = groups.values.distinct().associateWith { hashSetOf<String>() }
        for (unit in (selection.computer + selection.aliases.map { it.target }).distinctBy { it.key }) {
            if (unit.category !in setOf("character_cards", "world_books")) continue
            active()
            val value = owner(unit, unit.category, requireNotNull(unit.primaryId))
            for ((path, type) in groups) objects(value, path).forEach { item ->
                item["id"]?.jsonPrimitive?.contentOrNull?.let { reserved.getValue(type) += it }
            }
        }
        fun children(source: JsonObject, target: JsonObject?) {
            for ((path, type) in groups) {
                val incoming = objects(source, path); val old = target?.let { objects(it, path) }.orEmpty()
                val field = if (path == "customDocuments") "fileName" else "name"
                fun label(item: JsonObject) = name(item[field]?.jsonPrimitive?.contentOrNull.orEmpty())
                val names = old.groupBy(::label); val counts = incoming.groupingBy(::label).eachCount()
                val oldIds = old.mapNotNull { it["id"]?.jsonPrimitive?.contentOrNull }.toSet()
                for (item in incoming) {
                    active()
                    val id = item["id"]?.jsonPrimitive?.contentOrNull ?: continue
                    if (target == null && (type to id) in forkDeclarations) continue
                    val normalized = label(item)
                    val sameName = if (normalized.isNotEmpty() && counts[normalized] == 1) names[normalized]?.singleOrNull()
                        ?.get("id")?.jsonPrimitive?.contentOrNull else null
                    val result = when {
                        id in oldIds -> id
                        sameName != null -> sameName
                        id in reserved.getValue(type) -> identity.fresh(type, id)
                        else -> id
                    }
                    if (id != result) identity.bind(type, id, result)
                }
            }
        }
        for (alias in selection.aliases) {
            active()
            val source = owner(alias.source, alias.type, alias.sourceId)
            val target = owner(alias.target, alias.type, alias.targetId)
            if (alias.sourceId != alias.targetId) identity.bind(alias.type, alias.sourceId, alias.targetId)
            if (alias.type in setOf("character_cards", "world_books")) children(source, target)
        }
        val matched = selection.aliases.mapTo(hashSetOf()) { it.source.key }
        for (unit in selection.phone) {
            active()
            if (unit.category !in setOf("character_cards", "world_books") || unit.key in matched || unit.key in selection.forkIncomingUnits) continue
            children(owner(unit, unit.category, requireNotNull(unit.primaryId)), null)
        }
        val retainedVectorIds = hashSetOf<String>()
        for (unit in selection.computer) for ((path, value) in unit.records) {
            active()
            if (DesktopBackupPolicy.entityType(path) == "vector_chunks")
                value["id"]?.jsonPrimitive?.contentOrNull?.let(retainedVectorIds::add)
        }
        for (unit in selection.phone) for ((path, value) in unit.records) {
            active()
            if (DesktopBackupPolicy.entityType(path) != "vector_chunks" ||
                value["sourceType"]?.jsonPrimitive?.contentOrNull !in setOf("DOCUMENT", "CHARACTER_SETTING")) continue
            val id = requireNotNull(value["id"]?.jsonPrimitive?.contentOrNull)
            if (id in retainedVectorIds && ("vector_chunks" to id) !in forkDeclarations)
                identity.bind("vector_chunks", id, identity.fresh("vector_chunks", id))
        }
    }
}
