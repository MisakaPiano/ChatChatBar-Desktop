package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.syncStore.SyncStoreId
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.KSerializer
import kotlin.test.*

class LanSyncGroupSourceCheckpointTest {
    private fun <T> write(root: Path, type: String, id: String, serializer: KSerializer<T>, value: T) {
        val path = root.resolve("entities/$type/$id.json"); Files.createDirectories(path.parent)
        LanSyncJsonFiles.write(path, serializer, value)
    }
    private fun withRoot(work: (Path, Path) -> Unit) {
        val root = Files.createTempDirectory("synthetic-group-source-data-").toRealPath()
        val state = Files.createTempDirectory("synthetic-group-source-state-").toRealPath()
        try { work(root, state) } finally { check(root.toFile().deleteRecursively()); check(state.toFile().deleteRecursively()) }
    }
    private fun card(root: Path) = write(root, "character_cards", "card", CharacterCard.serializer(), CharacterCard("card", "合成已分享原件", createdAt = 1, updatedAt = 1))

    @Test fun checkpointRetainsActualRawChoicesAndResourcesBeforeLaterLocalDeletionAndRestoresANewRevision() = withRoot { root, state ->
        val document = root.resolve("documents/old.bin"); Files.createDirectories(document.parent)
        val bytes = ByteArray(8197) { (it % 251).toByte() }; Files.write(document, bytes)
        write(root, "character_cards", "card", CharacterCard.serializer(), CharacterCard("card", "合成原件", customDocuments = listOf(
            DocumentInfo("document", "old.bin", document.toString(), "application/octet-stream", addedAt = 1)), createdAt = 1, updatedAt = 1))
        write(root, "chat_sessions", "session", ChatSession.serializer(), ChatSession("session", "card", "原会话", modelId = "private-local-choice", createdAt = 1, updatedAt = 1))
        val raw = Files.readAllBytes(root.resolve("entities/chat_sessions/session.json"))
        val workspace = LanSyncWorkspace(root, state, { Path.of(it) }); val group = SyncStoreId.random(); val revision = UUID.randomUUID().toString()
        val entry = workspace.capture("local").use { capture -> requireNotNull(workspace.recovery.checkpointSource(root, capture, group, revision)).also {
            assertEquals(it, workspace.recovery.checkpointSource(root, capture, group, revision))
        } }
        assertEquals(group.value, entry.sourceCheckpointGroup); assertEquals(entry.sourceBinding, entry.targetBinding)
        assertEquals(setOf("character_cards/card", "conversation/session"), workspace.recovery.affected(entry))
        val entryPath = state.resolve("recovery/entries/$revision/entry.json"); val sealed = Files.readAllBytes(entryPath)
        Files.delete(root.resolve("entities/chat_sessions/session.json")); Files.delete(root.resolve("entities/character_cards/card.json")); Files.delete(document)
        write(root, "character_cards", "unrelated", CharacterCard.serializer(), CharacterCard("unrelated", "不回退", createdAt = 2, updatedAt = 2))
        val reopened = LanSyncWorkspace(root, state, { Path.of(it) }); reopened.recovery.verify(entry)
        var found = false
        reopened.recovery.originals(entry) { original, path -> if (original.relative == "entities/chat_sessions/session.json") {
            found = true; assertContentEquals(raw, Files.readAllBytes(path))
        } }
        assertTrue(found)
        val first = reopened.applyRecovery(reopened.prepareRecovery(revision, "character_cards/card", "local"), "local")
        val second = reopened.applyRecovery(reopened.prepareRecovery(revision, "conversation/session", "local"), "local")
        assertNotEquals(revision, first.revision); assertNotEquals(first.revision, second.revision)
        val session = LanSyncJsonFiles.read(root.resolve("entities/chat_sessions/session.json"), ChatSession.serializer())!!
        assertEquals("private-local-choice", session.modelId)
        val restored = LanSyncJsonFiles.read(root.resolve("entities/character_cards/card.json"), CharacterCard.serializer())!!
        assertContentEquals(bytes, Files.readAllBytes(Path.of(restored.customDocuments.single().filePath)))
        assertEquals("不回退", LanSyncJsonFiles.read(root.resolve("entities/character_cards/unrelated.json"), CharacterCard.serializer())!!.name)
        assertContentEquals(sealed, Files.readAllBytes(entryPath))
    }

    @Test fun rawOnlyChangesCreateImmutableCheckpointVariantsAndAnotherGroupCannotReuseTheRevision() = withRoot { root, state ->
        card(root)
        val session = ChatSession("session", "card", "会话", modelId = "first-local-model", createdAt = 1, updatedAt = 1)
        write(root, "chat_sessions", "session", ChatSession.serializer(), session)
        val workspace = LanSyncWorkspace(root, state, { Path.of(it) }); val group = SyncStoreId.random(); val revision = UUID.randomUUID().toString()
        val first = workspace.capture("local").use { requireNotNull(workspace.recovery.checkpointSource(root, it, group, revision)) }
        val path = state.resolve("recovery/entries/$revision/entry.json"); val original = Files.readAllBytes(path)
        write(root, "chat_sessions", "session", ChatSession.serializer(), session.copy(modelId = "later-local-model"))
        val second = workspace.capture("local").use { capture ->
            assertEquals(workspace.recovery.snapshot(first).hashes(), capture.snapshot.hashes())
            assertEquals("SYNC_RECOVERY_SOURCE_CHECKPOINT", assertFailsWith<IllegalArgumentException> {
                workspace.recovery.checkpointSource(root, capture, SyncStoreId.random(), revision)
            }.message)
            requireNotNull(workspace.recovery.checkpointSource(root, capture, group, revision))
        }
        assertNotEquals(first.transaction, second.transaction); assertEquals(group.value, second.sourceCheckpointGroup)
        assertNotEquals(first.rawSourceBinding, second.rawSourceBinding); assertContentEquals(original, Files.readAllBytes(path))
        workspace.recovery.verify(first); workspace.recovery.verify(second)
    }

    @Test fun failedCheckpointSealHasNoAuthorityAndRetryPreservesTheExactSource() = withRoot { root, state ->
        card(root); val workspace = LanSyncWorkspace(root, state, { Path.of(it) }); val revision = UUID.randomUUID().toString(); val group = SyncStoreId.random()
        val vault = LanSyncRecoveryVault(state.resolve("recovery"), observe = { if (it == "BEFORE_SEAL") throw IOException("SYNTHETIC_SOURCE_CHECKPOINT_FAILURE") },
            objectStore = workspace.resourceStore)
        workspace.capture("local").use { capture ->
            assertFailsWith<IOException> { vault.checkpointSource(root, capture, group, revision) }
            assertNull(vault.read(revision)); assertTrue(vault.list().isEmpty())
            assertTrue(Files.exists(root.resolve("entities/character_cards/card.json")))
            val entry = requireNotNull(workspace.recovery.checkpointSource(root, capture, group, revision))
            assertEquals(group.value, entry.sourceCheckpointGroup); workspace.recovery.verify(entry)
        }
    }
}
