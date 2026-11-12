package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.*
import kotlinx.serialization.json.*
import java.nio.file.*
import java.util.UUID
import kotlin.test.*

class LanSyncProjectionTest {
    private fun graph(prefix: String, time: Long): List<LanSyncRecord> {
        val card = CharacterCard("$prefix-card", "同源角色", characters = listOf(CharacterInfo("$prefix-person", "人物")),
            worldBookIds = listOf("$prefix-book"), defaultFormatCardId = "$prefix-format",
            sourcePresetKey = "synthetic/card", sourcePresetVersion = 1, createdAt = time, updatedAt = time)
        val book = WorldBook("$prefix-book", "同源世界书", entries = listOf(WorldBookEntry("$prefix-entry", content = "合成资料")),
            sourcePresetKey = "synthetic/book", sourcePresetVersion = 1, createdAt = time, updatedAt = time)
        val format = FormatCard("$prefix-format", "同源格式", "合成格式内容", sourcePresetKey = "synthetic/format", sourcePresetVersion = 1, createdAt = time)
        return listOf(LanSyncRecord("character_cards", card.id, lanSyncJson.encodeToJsonElement(CharacterCard.serializer(), card).jsonObject),
            LanSyncRecord("world_books", book.id, lanSyncJson.encodeToJsonElement(WorldBook.serializer(), book).jsonObject),
            LanSyncRecord("format_cards", format.id, lanSyncJson.encodeToJsonElement(FormatCard.serializer(), format).jsonObject))
    }
    @Test fun uniqueEquivalentProvenanceMapsRootsChildrenAndDependenciesWithoutNamesAsIdentity() {
        val computer = graph("pc", 1); val phone = graph("phone", 99)
        val projection = LanSyncPresetProjection.propose(computer, phone)
        assertEquals(5, projection.bindings.size)
        assertEquals(computer.associate { it.key to it.comparisonBody() }, projection.rewrite(phone).associate { it.key to it.comparisonBody() })
        assertEquals(phone.associate { it.key to it.comparisonBody() }, projection.rewrite(projection.rewrite(phone), toLocal = true).associate { it.key to it.comparisonBody() })
        val noSource = phone.map { it.copy(body = JsonObject(it.body - "sourcePresetKey")) }
        assertTrue(LanSyncPresetProjection.propose(computer, noSource).bindings.isEmpty())
        val otherVersion = phone.map { it.copy(body = JsonObject(it.body + ("sourcePresetVersion" to JsonPrimitive(2)))) }
        assertTrue(LanSyncPresetProjection.propose(computer, otherVersion).bindings.isEmpty())
    }
    @Test fun duplicateProvenanceAndDivergentInitialContentAreNeverSilentlySelected() {
        val computer = graph("pc", 1); val phone = graph("phone", 2)
        val duplicate = phone.first().let { it.copy(id = "another", body = JsonObject(it.body + ("id" to JsonPrimitive("another")))) }
        val ambiguous = LanSyncPresetProjection.propose(computer, phone + duplicate)
        assertTrue(ambiguous.bindings.none { it.type == "character_cards" || it.type == "characters" })
        val divergent = phone.map { if (it.type == "character_cards") it.copy(body = JsonObject(it.body + ("basicSetting" to JsonPrimitive("独立编辑")))) else it }
        assertTrue(LanSyncPresetProjection.propose(computer, divergent).bindings.none { it.type == "character_cards" || it.type == "characters" })
    }
    @Test fun physicalApplyKeepsLocalIdsExcludedReferencesAndCanonicalComparisonAcrossReopen() {
        val root = Files.createTempDirectory("lan-projection-data-").toRealPath()
        val state = Files.createTempDirectory("lan-projection-state-").toRealPath()
        val phone = graph("phone", 99); val computer = graph("pc", 1)
        fun write(record: LanSyncRecord) {
            val file = root.resolve("entities/${record.type}/${record.id}.json"); Files.createDirectories(file.parent); Files.writeString(file, record.body.toString())
        }
        phone.forEach(::write)
        val excluded = root.resolve("entities/moment_posts/local.json"); Files.createDirectories(excluded.parent)
        val retained = "{\"characterCardId\":\"phone-card\",\"senderCharacterId\":\"phone-person\"}"
        Files.writeString(excluded, retained)
        val projection = LanSyncPresetProjection.propose(computer, phone)
        val workspace = LanSyncWorkspace(root, state, { root.resolve(it) })
        val updated = computer.map { if (it.type == "character_cards") it.copy(body = JsonObject(it.body + ("name" to JsonPrimitive("同步后的编辑")))) else it }
        val snapshot = LanSyncSnapshot(deviceId = "pc", records = updated)
        workspace.capture("phone", projection).use { before ->
            workspace.apply(LanSyncPlan(UUID.randomUUID().toString(), snapshot, emptyMap()), before.snapshot.hashes(), "phone", emptyMap(), projection)
        }
        assertFalse(Files.exists(root.resolve("entities/character_cards/pc-card.json")))
        val card = lanSyncJson.decodeFromString(CharacterCard.serializer(), Files.readString(root.resolve("entities/character_cards/phone-card.json")))
        assertEquals("phone-card", card.id); assertEquals("phone-person", card.characters.single().id)
        assertEquals(listOf("phone-book"), card.worldBookIds); assertEquals("phone-format", card.defaultFormatCardId)
        assertEquals(retained, Files.readString(excluded))
        LanSyncWorkspace(root, state, { root.resolve(it) }).capture("phone", projection).use { assertEquals(snapshot.hashes(), it.snapshot.hashes()) }
    }
    @Test fun persistedPendingAndCompletedBaselineRefuseLostProjectionState() {
        val root = Files.createTempDirectory("lan-projection-owner-").toRealPath()
        val store = LanSyncStateStore(root, { it }, { it }); val owner = store.identity().fingerprint; val peer = "a".repeat(64)
        store.trust(LanSyncPeer(peer, "synthetic"))
        val projection = LanSyncPresetProjection.propose(graph("pc", 1), graph("phone", 2))
        store.admitProjection(peer, owner, projection)
        assertEquals(projection, LanSyncStateStore(root, { it }, { it }).projection(peer, owner))
        val plan = LanSyncPlan(UUID.randomUUID().toString(), LanSyncSnapshot(deviceId = owner, records = graph("pc", 1)), emptyMap())
        store.pending(LanSyncPending(peer, plan, emptyMap(), emptyMap()))
        val path = root.resolve("projection-$peer.json"); val original = Files.readAllBytes(path)
        Files.delete(path); assertFailsWith<IllegalArgumentException> { store.pending(peer) }
        Files.write(path, original); store.complete(peer, owner, plan); store.clearPending(peer, plan.transaction)
        Files.delete(path); assertFailsWith<IllegalArgumentException> { store.baseline(peer, owner) }
    }
}
