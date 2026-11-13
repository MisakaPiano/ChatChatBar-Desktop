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

    @Test fun peerCustodyKeepsTrueSourceIdentityAndRestoresOnItsOwnerWithoutForeignModelBindings() {
        val roots = List(4) { Files.createTempDirectory("lan-peer-custody-").toRealPath() }
        val (localRoot, localState, peerRoot, peerState) = roots
        try {
            val document = peerRoot.resolve("documents/old.bin"); Files.createDirectories(document.parent)
            val bytes = byteArrayOf(3, 1, 4, 1, 5); Files.write(document, bytes)
            card(peerRoot, "card", "对端原件", document)
            write(peerRoot, "chat_sessions", "session", ChatSession.serializer(),
                ChatSession("session", "card", "对端会话", modelId = "peer-private-model", createdAt = 1, updatedAt = 1))
            val local = workspace(localRoot, localState); val peer = workspace(peerRoot, peerState)
            val publication = UUID.randomUUID().toString()
            val entry = peer.capture("peer").use { before ->
                before.blobs.forEach { (hash, file) -> local.resourceStore.retain(file, hash, before.snapshot.resources.getValue(hash)) }
                requireNotNull(local.protectPeerBeforeApply(LanSyncPlan(publication,
                    LanSyncSnapshot(deviceId = "local", records = emptyList()), emptyMap()), before.snapshot, "local"))
            }
            assertEquals("peer", entry.sourceDevice); assertEquals("local", entry.custodyDevice)
            assertEquals(publication, entry.publicationTransaction); assertNotEquals(publication, entry.transaction)
            assertFalse(entry.hasLocalOriginals); assertEquals(0L, entry.originalCount)
            local.recovery.originals(entry) { _, _ -> fail("Portable custody must not invent peer raw files") }
            assertNull(local.recovery.snapshot(entry).records.single { it.type == "chat_sessions" }.body["modelId"])
            assertEquals("SYNC_RECOVERY_DEVICE", assertFailsWith<IllegalArgumentException> {
                local.prepareRecovery(entry.transaction, "conversation/session", "peer")
            }.message)
            check(peerRoot.toFile().deleteRecursively()); check(peerState.toFile().deleteRecursively())
            val reopened = workspace(localRoot, localState); reopened.recovery.verify(entry)
            reopened.applyRecovery(reopened.prepareRecovery(entry.transaction, "character_cards/card", "local"), "local")
            reopened.applyRecovery(reopened.prepareRecovery(entry.transaction, "conversation/session", "local"), "local")
            val restored = lanSyncJson.decodeFromString(CharacterCard.serializer(), Files.readString(localRoot.resolve("entities/character_cards/card.json")))
            assertContentEquals(bytes, Files.readAllBytes(Path.of(restored.customDocuments.single().filePath)))
            val sessionFile = localRoot.resolve("entities/chat_sessions/session.json")
            val session = lanSyncJson.decodeFromString(ChatSession.serializer(), Files.readString(sessionFile))
            assertNull(session.modelId)
            write(localRoot, "chat_sessions", "session", ChatSession.serializer(), session.copy(modelId = "current-local-model", title = "本机后改名"))
            reopened.applyRecovery(reopened.prepareRecovery(entry.transaction, "conversation/session", "local"), "local")
            assertEquals("current-local-model", lanSyncJson.decodeFromString(ChatSession.serializer(), Files.readString(sessionFile)).modelId)
        } finally { roots.forEach { if (Files.exists(it)) check(it.toFile().deleteRecursively()) } }
    }

    @Test fun aLaterLocalModelChoiceGetsAnImmutableRawVariantBeforeTheSamePlanPublishes() {
        val root = Files.createTempDirectory("lan-raw-variant-data-").toRealPath()
        val state = Files.createTempDirectory("lan-raw-variant-state-").toRealPath()
        try {
            card(root, "card", "角色")
            val session = ChatSession("session", "card", "会话", modelId = "first-local-model", createdAt = 1, updatedAt = 1)
            write(root, "chat_sessions", "session", ChatSession.serializer(), session)
            val ws = workspace(root, state); val transaction = UUID.randomUUID().toString()
            val plan = LanSyncPlan(transaction, LanSyncSnapshot(deviceId = "pc", records = emptyList()), emptyMap())
            val first = ws.capture("pc").use { requireNotNull(ws.recovery.protect(root, it, plan)) }
            val sealedFile = state.resolve("recovery/entries/$transaction/entry.json"); val sealedBytes = Files.readAllBytes(sealedFile)
            write(root, "chat_sessions", "session", ChatSession.serializer(), session.copy(modelId = "latest-local-model"))
            val second = ws.capture("pc").use { before ->
                assertEquals(ws.recovery.snapshot(first).hashes(), before.snapshot.hashes())
                requireNotNull(ws.recovery.protect(root, before, plan)).also {
                    assertEquals(it, ws.recovery.protect(root, before, plan))
                    ws.apply(plan, before.snapshot.hashes(), "pc", emptyMap())
                }
            }
            assertNotEquals(first.transaction, second.transaction); assertEquals(transaction, second.publicationTransaction)
            assertNotEquals(first.rawSourceBinding, second.rawSourceBinding)
            assertContentEquals(sealedBytes, Files.readAllBytes(sealedFile)); ws.recovery.verify(first); ws.recovery.verify(second)
            ws.applyRecovery(ws.prepareRecovery(second.transaction, "conversation/session", "pc"), "pc")
            assertEquals("latest-local-model", lanSyncJson.decodeFromString(ChatSession.serializer(),
                Files.readString(root.resolve("entities/chat_sessions/session.json"))).modelId)
            assertEquals("first-local-model", ws.recovery.restoredLocalFields(first, setOf("chat_sessions/session"),
                LanSyncProjection()).getValue("chat_sessions/session")["modelId"]?.jsonPrimitive?.content)
        } finally { check(root.toFile().deleteRecursively()); check(state.toFile().deleteRecursively()) }
    }

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
