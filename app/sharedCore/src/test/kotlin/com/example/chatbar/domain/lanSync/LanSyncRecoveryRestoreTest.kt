package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.*
import java.nio.file.*
import java.util.UUID
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import kotlin.test.*

class LanSyncRecoveryRestoreTest {
    private fun <T> write(root: Path, type: String, id: String, serializer: KSerializer<T>, value: T) {
        val path = root.resolve("entities/$type/$id.json"); Files.createDirectories(path.parent)
        LanSyncJsonFiles.write(path, serializer, value)
    }
    private fun card(root: Path, id: String, name: String, document: Path? = null) = write(root, "character_cards", id,
        CharacterCard.serializer(), CharacterCard(id, name, customDocuments = document?.let { listOf(
            DocumentInfo("document", "restorable.bin", it.toString(), "application/octet-stream", addedAt = 1)) }.orEmpty(), createdAt = 1, updatedAt = 1))
    private fun workspace(root: Path, state: Path, observe: (String, Int) -> Unit = { _, _ -> }) =
        LanSyncWorkspace(root, state, { Path.of(it) }, observeJournal = observe)

    @Test fun selectedUnitRestoresAsANewRevisionWithOriginalModelAndMediaWithoutRewindingUnrelatedRecords() {
        val root = Files.createTempDirectory("lan-restore-data-").toRealPath()
        val state = Files.createTempDirectory("lan-restore-state-").toRealPath()
        try {
            val resource = root.resolve("documents/original.bin"); Files.createDirectories(resource.parent)
            val content = ByteArray(4099) { (it % 251).toByte() }; Files.write(resource, content)
            card(root, "card", "原角色", resource); card(root, "independent", "原独立内容")
            val session = ChatSession("session", "card", "原会话", modelId = "same-device-model", createdAt = 1, updatedAt = 1)
            write(root, "chat_sessions", "session", ChatSession.serializer(), session)
            val message = ChatMessage("message", "session", MessageRole.ASSISTANT, "完整原正文",
                alternatives = listOf("旧版本", "完整原正文"), alternativeVersionIds = listOf("version-0", "version-1"),
                currentAlternativeIndex = 1, currentAlternativeVersionId = "version-1", createdAt = 1, updatedAt = 1)
            write(root, "chat_messages", "session_message", ChatMessage.serializer(), message)
            val ws = workspace(root, state)
            val deletion = UUID.randomUUID().toString()
            ws.capture("pc").use { before ->
                val retained = before.snapshot.records.filter { it.id == "independent" }
                ws.apply(LanSyncPlan(deletion, before.snapshot.copy(records = retained, resources = emptyMap()), emptyMap()), before.snapshot.hashes(), "pc", before.blobs)
            }
            ws.retire(deletion); Files.delete(resource); card(root, "independent", "之后独立编辑")
            val conversation = ws.prepareRecovery(deletion, "conversation/session", "pc")
            assertTrue(conversation.hasChanges); assertNotEquals(deletion, conversation.id)
            val restored = ws.applyRecovery(conversation, "pc")
            assertTrue(restored.applied); assertEquals(restored, ws.applyRecovery(conversation, "pc"))
            val restoredSession = lanSyncJson.decodeFromString(ChatSession.serializer(), Files.readString(root.resolve("entities/chat_sessions/session.json")))
            assertEquals("same-device-model", restoredSession.modelId)
            assertEquals(message, lanSyncJson.decodeFromString(ChatMessage.serializer(), Files.readString(root.resolve("entities/chat_messages/session_message.json"))))
            assertFalse(Files.exists(root.resolve("entities/character_cards/card.json")))
            val cardPreview = ws.prepareRecovery(deletion, "character_cards/card", "pc")
            ws.applyRecovery(cardPreview, "pc")
            val restoredCard = lanSyncJson.decodeFromString(CharacterCard.serializer(), Files.readString(root.resolve("entities/character_cards/card.json")))
            assertContentEquals(content, Files.readAllBytes(Path.of(restoredCard.customDocuments.single().filePath)))
            assertEquals("之后独立编辑", lanSyncJson.decodeFromString(CharacterCard.serializer(),
                Files.readString(root.resolve("entities/character_cards/independent.json"))).name)
            val reopened = workspace(root, state); reopened.recover()
            assertTrue(requireNotNull(LanSyncJsonFiles.read(state.resolve("restore-intent-${conversation.id}.json"), LanSyncRestoreRevision.serializer())).applied)
            assertEquals(setOf("character_cards/card", "conversation/session"), reopened.recovery.affected(requireNotNull(reopened.recovery.read(deletion))))
        } finally { check(root.toFile().deleteRecursively()); check(state.toFile().deleteRecursively()) }
    }

    @Test fun authorizedRestoreCrashRecoversItsNewRevisionAndAStalePreviewNeverOverwritesLaterEdits() {
        val root = Files.createTempDirectory("lan-restore-crash-data-").toRealPath()
        val state = Files.createTempDirectory("lan-restore-crash-state-").toRealPath()
        try {
            card(root, "card", "原正文")
            var crash = false
            val ws = workspace(root, state) { stage, _ -> if (crash && stage == "AUTHORIZED") error("synthetic restore power loss") }
            val deletion = UUID.randomUUID().toString()
            ws.capture("pc").use { before -> ws.apply(LanSyncPlan(deletion, before.snapshot.copy(records = emptyList()), emptyMap()), before.snapshot.hashes(), "pc", emptyMap()) }
            val preview = ws.prepareRecovery(deletion, "character_cards/card", "pc")
            crash = true; assertFails { ws.applyRecovery(preview, "pc") }
            assertFalse(Files.exists(root.resolve("entities/character_cards/card.json")))
            val reopened = workspace(root, state); reopened.recover()
            assertTrue(reopened.applied(preview.id))
            assertTrue(requireNotNull(LanSyncJsonFiles.read(state.resolve("restore-intent-${preview.id}.json"), LanSyncRestoreRevision.serializer())).applied)
            card(root, "card", "恢复后的新编辑")
            val stale = reopened.prepareRecovery(deletion, "character_cards/card", "pc")
            card(root, "card", "预览后的新编辑")
            assertEquals("SYNC_RECOVERY_SOURCE_CHANGED", assertFailsWith<IllegalArgumentException> { reopened.applyRecovery(stale, "pc") }.message)
            assertEquals("预览后的新编辑", lanSyncJson.decodeFromString(CharacterCard.serializer(),
                Files.readString(root.resolve("entities/character_cards/card.json"))).name)
        } finally { check(root.toFile().deleteRecursively()); check(state.toFile().deleteRecursively()) }
    }

    @Test fun aMessageIdentityReusedByAnotherConversationAndAChangedProjectionRefuseBeforeWriting() {
        val root = Files.createTempDirectory("lan-restore-collision-data-").toRealPath()
        val state = Files.createTempDirectory("lan-restore-collision-state-").toRealPath()
        try {
            card(root, "card", "角色")
            write(root, "chat_sessions", "old", ChatSession.serializer(), ChatSession("old", "card", "旧会话", createdAt = 1, updatedAt = 1))
            write(root, "chat_messages", "old_message", ChatMessage.serializer(), ChatMessage("message", "old", MessageRole.USER, "旧正文", createdAt = 1, updatedAt = 1))
            val ws = workspace(root, state); val deletion = UUID.randomUUID().toString()
            ws.capture("pc").use { before -> ws.apply(LanSyncPlan(deletion,
                before.snapshot.copy(records = before.snapshot.records.filter { it.type == "character_cards" }), emptyMap()), before.snapshot.hashes(), "pc", emptyMap()) }
            write(root, "chat_sessions", "new", ChatSession.serializer(), ChatSession("new", "card", "新会话", createdAt = 1, updatedAt = 1))
            write(root, "chat_messages", "new_message", ChatMessage.serializer(), ChatMessage("message", "new", MessageRole.USER, "其他会话正文", createdAt = 1, updatedAt = 1))
            assertEquals("SYNC_RECOVERY_ID_COLLISION", assertFailsWith<IllegalArgumentException> { ws.prepareRecovery(deletion, "conversation/old", "pc") }.message)
            val projection = LanSyncProjection(listOf(LanSyncIdBinding("character_cards", "card", "different-wire-id")))
            assertEquals("SYNC_RECOVERY_PROJECTION", assertFailsWith<IllegalArgumentException> { ws.prepareRecovery(deletion, "conversation/old", "pc", projection) }.message)
            assertEquals("其他会话正文", lanSyncJson.decodeFromString(ChatMessage.serializer(), Files.readString(root.resolve("entities/chat_messages/new_message.json"))).content)
            assertFalse(Files.exists(root.resolve("entities/chat_sessions/old.json")))
        } finally { check(root.toFile().deleteRecursively()); check(state.toFile().deleteRecursively()) }
    }
}
