package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.*
import kotlinx.serialization.json.*
import java.nio.file.*
import java.util.UUID
import kotlin.test.*

class LanSyncWorkspaceTest {
    private fun root() = Files.createTempDirectory("ccb-lan-synthetic-").toRealPath()
    private fun workspace(root: Path, state: Path = root().resolve("workspace"), observer: (String, Int) -> Unit = { _, _ -> }) =
        LanSyncWorkspace(root, state, { raw -> Path.of(raw).let { if (it.isAbsolute) it else root.resolve(it) } }, observeJournal = observer)
    private fun card(id: String, name: String, image: String? = null) = LanSyncRecord("character_cards", id,
        lanSyncJson.encodeToJsonElement(CharacterCard.serializer(), CharacterCard(id, name, avatar = image, createdAt = 1, updatedAt = 1)).jsonObject)
    private fun write(root: Path, record: LanSyncRecord) {
        val path = root.resolve("entities/${record.type}/${record.id}.json"); Files.createDirectories(path.parent)
        Files.writeString(path, record.body.toString())
    }
    private fun snapshot(vararg records: LanSyncRecord) = LanSyncSnapshot(deviceId = "synthetic", records = records.toList())

    @Test fun normalizedReferencesTrackBytesAndIgnoreDevicePaths() {
        val a = root(); val b = root()
        val leftImage = a.resolve("images/a.png"); val rightImage = b.resolve("images/different.png")
        Files.createDirectories(leftImage.parent); Files.createDirectories(rightImage.parent)
        Files.write(leftImage, byteArrayOf(1, 2, 3)); Files.write(rightImage, byteArrayOf(1, 2, 3))
        write(a, card("a", "角色", leftImage.toString())); write(b, card("a", "角色", rightImage.toString()))
        val left = workspace(a).capture("synthetic"); val same = workspace(b).capture("synthetic")
        assertEquals(left.snapshot.hashes(), same.snapshot.hashes())
        Files.write(rightImage, byteArrayOf(1, 2, 4))
        assertNotEquals(left.snapshot.hashes(), workspace(b).capture("synthetic").snapshot.hashes())
    }
    @Test fun actualApplyReopenRepeatAndDeletionPreserveUncoveredStores() {
        val root = root(); val state = root().resolve("workspace")
        val ws = workspace(root, state)
        val uncovered = root.resolve("entities/device_preferences.json")
        Files.createDirectories(uncovered.parent); Files.writeString(uncovered, "synthetic-local-device-record")
        write(root, card("old", "旧卡"))
        val before = ws.capture("synthetic")
        val transaction = UUID.randomUUID().toString()
        val plan = LanSyncPlan(transaction, snapshot(card("new", "新卡")), emptyMap())
        ws.apply(plan, before.snapshot.hashes(), "synthetic", emptyMap())
        val reopened = workspace(root, state)
        reopened.recover()
        assertTrue(reopened.applied(transaction))
        assertEquals(plan.snapshot.hashes(), reopened.capture("synthetic").snapshot.hashes())
        assertFalse(Files.exists(root.resolve("entities/character_cards/old.json")))
        assertEquals("synthetic-local-device-record", Files.readString(uncovered))
        assertTrue(LanSyncCompare.compare(plan.snapshot, reopened.capture("synthetic").use { it.snapshot },
            LanSyncBaseline("synthetic", transaction, plan.snapshot.hashes(), ownerId = "synthetic")).conflicts.isEmpty())
    }
    @Test fun changesAfterCaptureAreRefusedWithoutOverwriting() {
        val root = root(); val ws = workspace(root)
        write(root, card("a", "原卡")); val before = ws.capture("synthetic")
        write(root, card("a", "同步时编辑"))
        assertFailsWith<IllegalArgumentException> {
            ws.apply(LanSyncPlan(UUID.randomUUID().toString(), snapshot(card("a", "对端卡")), emptyMap()), before.snapshot.hashes(), "synthetic", emptyMap())
        }
        assertEquals("同步时编辑", lanSyncJson.parseToJsonElement(Files.readString(root.resolve("entities/character_cards/a.json"))).jsonObject["name"]?.jsonPrimitive?.content)
    }
    @Test fun malformedSourceTextRefusesBeforeReplacingAnyOriginalBytes() {
        val root = root(); val state = root().resolve("workspace")
        write(root, card("a", "valid"))
        val file = root.resolve("entities/character_cards/a.json")
        val text = Files.readString(file)
        val marker = text.indexOf("valid")
        assertTrue(marker >= 0)
        val bytes = text.substring(0, marker).toByteArray(Charsets.UTF_8) + byteArrayOf(0xc3.toByte(), 0x28) +
            text.substring(marker + 5).toByteArray(Charsets.UTF_8)
        Files.write(file, bytes)
        val failure = assertFailsWith<IllegalArgumentException> { workspace(root, state).capture("synthetic") }
        assertEquals("SYNC_INVALID_UTF8", failure.message)
        assertContentEquals(bytes, Files.readAllBytes(file))
        assertFalse(Files.newDirectoryStream(state, "capture-*").use { it.iterator().hasNext() })
    }
    @Test fun corruptLastPayloadIsRejectedBeforeAnyEntryChanges() {
        val root = root(); val state = root().resolve("workspace")
        write(root, card("a", "A旧")); write(root, card("b", "B旧"))
        val originalA = Files.readAllBytes(root.resolve("entities/character_cards/a.json"))
        val transaction = UUID.randomUUID().toString()
        val ws = workspace(root, state) { stage, _ -> if (stage == "AUTHORIZED") error("synthetic-power-loss") }
        val before = ws.capture("synthetic")
        assertFails { ws.apply(LanSyncPlan(transaction, snapshot(card("a", "A新"), card("b", "B新")), emptyMap()), before.snapshot.hashes(), "synthetic", emptyMap()) }
        Files.writeString(state.resolve("transaction-$transaction/1.new"), "corrupt synthetic tail")
        assertFails { workspace(root, state).recover() }
        assertContentEquals(originalA, Files.readAllBytes(root.resolve("entities/character_cards/a.json")))
    }
    @Test fun interruptedPublicationRollsForwardAndForeignEditsRefuse() {
        fun prepare(foreignEdit: Boolean) {
            val root = root(); val state = root().resolve("workspace")
            write(root, card("a", "A旧")); write(root, card("b", "B旧"))
            val transaction = UUID.randomUUID().toString()
            val ws = workspace(root, state) { stage, index -> if (stage == "ENTRY" && index == 0) error("synthetic-process-exit") }
            val before = ws.capture("synthetic")
            val plan = LanSyncPlan(transaction, snapshot(card("a", "A新"), card("b", "B新")), emptyMap())
            assertFails { ws.apply(plan, before.snapshot.hashes(), "synthetic", emptyMap()) }
            if (foreignEdit) {
                write(root, card("b", "用户新编辑"))
                assertFails { workspace(root, state).recover() }
                assertFalse(workspace(root, state).applied(transaction))
            } else {
                val fresh = workspace(root, state); fresh.recover()
                assertTrue(fresh.applied(transaction)); assertEquals(plan.snapshot.hashes(), fresh.capture("synthetic").snapshot.hashes())
            }
        }
        prepare(false); prepare(true)
    }
    @Test fun linksInsideOwnedRootAreRejected() {
        val root = root(); val other = root()
        val link = root.resolve("entities")
        try { Files.createSymbolicLink(link, other) } catch (_: Exception) { return }
        assertFails { workspace(root).capture("synthetic") }
    }
    @Test fun opaqueAndFishArtworkNeverBecomeFileReads() {
        val value = buildJsonObject {
            putJsonObject("voice") { put("coverImage", "cdn/remote-art") }
            putJsonObject("metadata") { put("path", "opaque-user-text") }
            put("content", "images/ordinary-prose")
            put("avatar", "real-avatar")
        }
        val seen = mutableListOf<String>()
        val mapped = LanSyncWorkspace.mapResources(value) { text, _ -> seen += text; "mapped" }
        assertEquals(listOf("real-avatar"), seen)
        assertEquals("cdn/remote-art", mapped["voice"]?.jsonObject?.get("coverImage")?.jsonPrimitive?.content)
    }
}
