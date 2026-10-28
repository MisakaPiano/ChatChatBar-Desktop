package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.memory.MemoryHashes
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class DesktopMergeGraphRemapperTest {
    @Test fun `participation remaps both card key and nested person IDs without rewriting unrelated text`() {
        val source = record("chat_sessions", obj("""{"id":"s","characterCardId":"id","desktopCharacterParticipation":[{"cardId":"id","disabledPersonIds":["p"]}],"title":"id p"}"""))
        val ids = DesktopMergeIdentity().apply { bind("chat_sessions", "s", "fork-s"); bind("character_cards", "id", "fork-c"); bind("characters", "p", "fork-p") }
        assertFalse(ids.declarations(source).any { it.first == "characters" })
        val result = DesktopMergeGraphRemapper(listOf(source), ids).rewrite(source)
        assertEquals("fork-s", result.getValue("id").jsonPrimitive.content)
        assertEquals("fork-c", result.getValue("characterCardId").jsonPrimitive.content)
        val participation = result.getValue("desktopCharacterParticipation").jsonArray.single().jsonObject
        assertEquals("fork-c", participation.getValue("cardId").jsonPrimitive.content)
        assertEquals(listOf("fork-p"), participation.getValue("disabledPersonIds").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("id p", result.getValue("title").jsonPrimitive.content)
    }

    private fun obj(text: String) = backupJson.parseToJsonElement(text).jsonObject
    private fun record(type: String, value: JsonObject) = DesktopMergeRecord("entities/$type/item.json", value)
    private fun record(node: MemoryNode) = record("memory_nodes", backupJson.encodeToJsonElement(MemoryNode.serializer(), node).jsonObject)
    private fun leaf(id: String = "episode") = MemoryNode(id, "s", MemoryTier.EPISODE,
        sourceTurnIds = listOf("source-turn"), content = "Synthetic formal summary", createdAt = 1,
        sourceHashes = mapOf("source-turn" to "source-hash"), sourceHash = "leaf-source", coverageHash = "leaf-coverage",
        coverageUnits = listOf(MemoryCoverageUnit("source-turn", "Original source text")))
    private fun parent(id: String, tier: MemoryTier, child: MemoryNode, natural: Boolean = false): MemoryNode {
        val units = listOf(MemoryCoverageUnit(child.id, if (natural) "Independent coverage prose" else child.coverageHash))
        return MemoryNode(id, "s", tier, childIds = listOf(child.id), sourceTurnIds = child.sourceTurnIds,
            content = "Synthetic $id summary", createdAt = 1, coverageUnits = units,
            sourceHash = MemoryHashes.text("${child.id}:${child.sourceHash}"), coverageHash = MemoryHashes.parentCoverage(listOf(child), units))
    }
    private fun decoded(value: JsonObject) = backupJson.decodeFromJsonElement(MemoryNode.serializer(), value)

    @Test fun `fork keeps original user payload version and source turn identities`() {
        val value = obj("""{"id":"m","sessionId":"s","content":"s m source-turn","messageVersionId":"m","sourceTurnId":"s","alternativeVersions":[{"id":"m","content":"Original alternative"}],"extensions":{"sessionId":"s","staleSourcesByNodeId":{"node":"unchanged"}},"metadata":{"messageIds":"m,m2"}}""")
        val record = record("edit_drafts", value)
        val ids = DesktopMergeIdentity().apply { bind("edit_drafts", "m", "fork-m"); bind("chat_sessions", "s", "fork-s"); bind("chat_messages", "m", "fork-m"); bind("chat_messages", "m2", "fork-m2"); bind("memory_nodes", "node", "fork-node") }
        val mapped = DesktopMergeGraphRemapper(listOf(record), ids).rewrite(record)
        assertEquals(JsonPrimitive("fork-m"), mapped["id"]); assertEquals(JsonPrimitive("fork-s"), mapped["sessionId"])
        for (field in listOf("content", "messageVersionId", "sourceTurnId", "alternativeVersions", "extensions")) assertEquals(value[field], mapped[field])
        assertEquals(JsonPrimitive("fork-m,fork-m2"), mapped.getValue("metadata").jsonObject["messageIds"])
        assertEquals(JsonPrimitive("m"), value["id"])
    }

    @Test fun `three tier fork recalculates ancestry and leaves formal text unchanged`() {
        val episode = leaf(); val arc = parent("arc", MemoryTier.ARC, episode); val era = parent("era", MemoryTier.ERA, arc)
        val records = listOf(era, arc, episode).map(::record)
        val ids = DesktopMergeIdentity().apply { for (id in listOf("episode", "arc", "era")) bind("memory_nodes", id, "fork-$id"); bind("chat_sessions", "s", "fork-s") }
        val mapper = DesktopMergeGraphRemapper(records, ids)
        val (newEra, newArc, newEpisode) = records.map { decoded(mapper.rewrite(it)) }
        assertEquals("fork-episode", newEpisode.id); assertEquals("fork-s", newEpisode.sessionId)
        assertEquals(episode.sourceHash, newEpisode.sourceHash); assertEquals(episode.coverageHash, newEpisode.coverageHash)
        assertEquals(episode.coverageUnits, newEpisode.coverageUnits)
        for ((old, new) in listOf(episode to newEpisode, arc to newArc, era to newEra)) {
            assertEquals(old.content, new.content); assertEquals(old.sourceTurnIds, new.sourceTurnIds); assertEquals(old.sourceHashes, new.sourceHashes)
        }
        assertEquals(MemoryHashes.parentCoverage(listOf(newEpisode), newArc.coverageUnits), newArc.coverageHash)
        assertEquals(newEpisode.coverageHash, newArc.coverageUnits.single().text)
        assertEquals(newArc.coverageHash, newEra.coverageUnits.single().text)
        assertEquals(MemoryHashes.parentCoverage(listOf(newArc), newEra.coverageUnits), newEra.coverageHash)
    }

    @Test fun `changed descendant proof propagates when immediate parent identities stay unchanged`() {
        val episode = leaf(); val arc = parent("arc", MemoryTier.ARC, episode); val era = parent("era", MemoryTier.ERA, arc)
        val records = listOf(episode, arc, era).map(::record)
        val mapper = DesktopMergeGraphRemapper(records, DesktopMergeIdentity().apply { bind("memory_nodes", "episode", "fork-episode") })
        val newArc = decoded(mapper.rewrite(records[1])); val newEra = decoded(mapper.rewrite(records[2]))
        assertNotEquals(arc.coverageHash, newArc.coverageHash)
        assertEquals(MemoryHashes.parentCoverage(listOf(newArc), newEra.coverageUnits), newEra.coverageHash)
    }

    @Test fun `nested snapshot uses same proof but natural coverage text is never replaced`() {
        val episode = leaf(); val arc = parent("arc", MemoryTier.ARC, episode, natural = true)
        val nested = record("save_slots", buildJsonObject { put("id", "slot"); put("memory", buildJsonObject {
            put("nodes", backupJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(MemoryNode.serializer()), listOf(episode, arc))) }) })
        val ids = DesktopMergeIdentity().apply { bind("memory_nodes", "episode", "fork-episode"); bind("memory_nodes", "arc", "fork-arc") }
        val mapper = DesktopMergeGraphRemapper(listOf(record(episode), record(arc), nested), ids)
        val nodes = mapper.rewrite(nested).getValue("memory").jsonObject.getValue("nodes").jsonArray.map { decoded(it.jsonObject) }
        assertEquals("Independent coverage prose", nodes[1].coverageUnits.single().text)
        assertEquals(MemoryHashes.parentCoverage(listOf(nodes[0]), nodes[1].coverageUnits), nodes[1].coverageHash)
    }

    @Test fun `cyclic missing and conflicting nodes are refused with fixed errors`() {
        val a = leaf().copy(childIds = listOf("missing"))
        assertEquals("MERGE_MEMORY_CHILD_MISSING", assertFails { DesktopMergeGraphRemapper(listOf(record(a)), DesktopMergeIdentity()) }.message)
        val cycle = leaf().copy(childIds = listOf("episode"))
        assertEquals("MERGE_MEMORY_CYCLE", assertFails { DesktopMergeGraphRemapper(listOf(record(cycle)), DesktopMergeIdentity()) }.message)
        assertEquals("MERGE_MEMORY_NODE_CONFLICT", assertFails { DesktopMergeGraphRemapper(listOf(record(leaf()), record(leaf().copy(content = "Different"))), DesktopMergeIdentity()) }.message)
    }

    @Test fun `typed spaces collision guards and compact message budget are enforced`() {
        val ids = DesktopMergeIdentity().apply { bind("chat_sessions", "same", "session-new"); bind("characters", "same", "character-new") }
        assertEquals("session-new", ids.value("chat_sessions", "same")); assertEquals("character-new", ids.value("characters", "same"))
        assertFails { ids.bind("chat_sessions", "other", "session-new") }; assertFails { ids.bind("chat_sessions", "same", "other") }
        assertFails { ids.bind("chat_messages", "m", "x".repeat(129)) }
        ids.bind("model_configs", "model", "fork-model")
        assertEquals("fork-model", ids.value("retrieval_model_config", "model"))
    }

    @Test fun `map key collision cancellation and resource paths cannot become silent success`() {
        val record = record("memory_states", obj("""{"sessionId":"s","staleSourcesByNodeId":{"a":[],"b":[]}}"""))
        val ids = DesktopMergeIdentity().apply { bind("memory_nodes", "a", "b") }
        assertEquals("MERGE_OBJECT_KEY_COLLISION", assertFails { DesktopMergeGraphRemapper(listOf(record), ids).rewrite(record) }.message)
        assertFailsWith<CancellationException> { DesktopMergeGraphRemapper(listOf(record), DesktopMergeIdentity()) { throw CancellationException("Synthetic") }.rewrite(record) }
        val image = record("character_cards", obj("""{"id":"c","avatar":"chatbar-backup-file:images/a.png","description":"images/a.png"}"""))
        val mapped = DesktopMergeGraphRemapper(listOf(image), DesktopMergeIdentity()).rewrite(image) { value, _ -> "mapped-$value" }
        assertEquals(image.value["description"], mapped["description"])
        assertEquals(JsonPrimitive("mapped-chatbar-backup-file:images/a.png"), mapped["avatar"])
    }
}
