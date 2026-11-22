package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ChatDraft
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.domain.lanSync.LanSyncOperationGate
import com.example.chatbar.domain.lanSync.LanSyncPlan
import com.example.chatbar.domain.lanSync.LanSyncWorkspace
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

class ChatRepositorySyncAdmissionTest {
    private fun withRoot(work: suspend (Path) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("synthetic-chat-sync-admission-").toRealPath()
        try { work(root) } finally { check(root.toFile().deleteRecursively()) }
    }

    @Test fun confirmedSyncDeletionRejectsAQueuedStalePageAndKeepsItsDraftAndImage() = withRoot { root ->
        val data = Files.createDirectory(root.resolve("data"))
        val gate = LanSyncOperationGate()
        val storage = JsonFileStorage(data, gate)
        val repo = ChatRepository(storage)
        val workspace = LanSyncWorkspace(data, root.resolve("work"), { Path.of(it) })
        val session = repo.createSession(ChatSession("chat", "card", "合成旧页", createdAt = 1, updatedAt = 1))
        val image = Files.write(Files.createDirectories(data.resolve("images")).resolve("draft.jpg"), byteArrayOf(3, 1, 4))
        repo.updateComposerDraft(ChatDraft("chat", "仍可恢复", imagePaths = listOf(image.toString())))
        val deletion = workspace.captureGroup("device").use { captured ->
            LanSyncPlan(UUID.randomUUID().toString(), captured.snapshot.copy(records = emptyList()), emptyMap()) to captured.snapshot.hashes()
        }
        val removed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val stale = ChatMessage("stale", "chat", MessageRole.USER, "不可写入", createdAt = 2, updatedAt = 2)
        kotlinx.coroutines.coroutineScope {
            val sync = async {
                gate.exclusive {
                    workspace.apply(deletion.first, deletion.second, "device", emptyMap(), isolateLocalImages = true)
                    repo.reloadAfterLanSync()
                    removed.complete(Unit)
                    release.await()
                }
            }
            try {
                withTimeout(5_000) { removed.await() }
                val submit = async(start = CoroutineStart.UNDISPATCHED) {
                    assertFailsWith<ChatSessionUnavailableException> { repo.addMessage(stale) }.missing
                }
                assertFalse(submit.isCompleted)
                release.complete(Unit)
                sync.await()
                assertTrue(submit.await())
            } finally { release.complete(Unit) }
        }
        assertTrue(assertFailsWith<ChatSessionUnavailableException> { repo.updateSession(session) }.missing)
        assertTrue(assertFailsWith<ChatSessionUnavailableException> { repo.addMessageAfter(stale, "old") }.missing)
        assertEquals(JsonFileStorage.EntityReadResult.Missing, repo.readSessionDurable("chat"))
        assertEquals(JsonFileStorage.EntityReadResult.Missing, repo.readMessageDurable("stale", "chat"))
        assertEquals("仍可恢复", repo.getComposerDraft("chat").content)
        assertEquals(listOf(image.toString()), repo.getComposerDraft("chat").imagePaths)
        assertContentEquals(byteArrayOf(3, 1, 4), Files.readAllBytes(image))
        workspace.captureGroup("device").use { assertTrue(it.snapshot.records.isEmpty()) }
    }

    @Test fun durableAppendIsObservablyCommittedBeforeDerivedWorkAndForcesOldDeletionToBeReviewedAgain() = withRoot { root ->
        val data = Files.createDirectory(root.resolve("data"))
        val gate = LanSyncOperationGate()
        val repo = ChatRepository(JsonFileStorage(data, gate))
        val workspace = LanSyncWorkspace(data, root.resolve("work"), { Path.of(it) })
        repo.createSession(ChatSession("chat", "card", "合成", createdAt = 1, updatedAt = 1))
        val deletion = workspace.captureGroup("device").use { captured ->
            LanSyncPlan(UUID.randomUUID().toString(), captured.snapshot.copy(records = emptyList()), emptyMap()) to captured.snapshot.hashes()
        }
        val message = ChatMessage("persisted", "chat", MessageRole.USER, "只写一次", createdAt = 2, updatedAt = 2)
        var receipt: ChatMessage? = null
        assertFailsWith<java.io.IOException> {
            repo.addMessage(message) { committed -> receipt = committed; throw java.io.IOException("synthetic post-commit interruption") }
        }
        assertEquals(requireNotNull(receipt), assertIs<JsonFileStorage.EntityReadResult.Valid<ChatMessage>>(repo.readMessageDurable("persisted", "chat")).value)
        val failure = assertFailsWith<IllegalArgumentException> {
            gate.exclusive { workspace.apply(deletion.first, deletion.second, "device", emptyMap(), isolateLocalImages = true) }
        }
        assertEquals("SYNC_LOCAL_CHANGED_REVIEW_AGAIN", failure.message)
        assertEquals("chat", repo.requireDurableSession("chat").id)
    }
}
