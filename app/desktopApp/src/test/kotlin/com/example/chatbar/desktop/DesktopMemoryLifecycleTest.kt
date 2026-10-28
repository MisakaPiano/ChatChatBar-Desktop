package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.*
import com.example.chatbar.domain.chat.ContextWindowManager
import com.example.chatbar.domain.memory.*
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopMemoryLifecycleTest {
    @Test fun `format repair source CAS invalidates old memory before injection without an extra AI request`(): Unit = runBlocking {
        val f = Fixture()
        try {
            f.setup()
            assertEquals(1, f.service.updateArchiveAfterReply(f.session.id, f.model, 1, maxEpisodeBatches = 1).committedEpisodes)
            val node = f.service.activeNodes(f.session.id).single()
            assertTrue(f.service.promptView(f.session.id).archive.contains(node.body))
            val source = f.chats.getMessages(f.session.id).first { it.sourceTurnId in node.sourceTurnIds && it.role == MessageRole.ASSISTANT }
            val before = f.calls
            val repaired = "合成历史改为旅人在车站与同伴会合。"
            val result = assertIs<ConditionalMessageUpdateResult.Success>(f.chats.updateMessageForFormatRepair(
                f.session.id, source.id, source, repaired,
                MessageFormatRepairNotice(MessageFormatRepairNoticeKind.APPLIED, repaired, source.displayContent)))
            assertEquals(source.sourceTurnId, result.message.sourceTurnId)
            assertFalse(f.service.promptView(f.session.id).archive.contains(node.body))
            assertTrue(f.repository.getState(f.session.id)!!.staleSourcesByNodeId.isNotEmpty())
            assertNotNull(f.repository.getNode(node.id))
            assertEquals(source.displayContent, result.message.formatRepairNotice?.originalContent)
            assertEquals(before, f.calls)
        } finally { f.close() }
    }

    @Test fun `memory patch cannot restore a deleted message preview or lose a newer setting`(): Unit = runBlocking {
        val f = Fixture()
        try {
            f.setup()
            val baselineReady = CompletableDeferred<ChatSession>()
            val permitMemoryWrite = CompletableDeferred<Unit>()
            val memoryWrite = launch {
                val baseline = f.chats.getSession(f.session.id)!!
                baselineReady.complete(baseline)
                permitMemoryWrite.await()
                f.chats.saveSessionSettingsDraft(baseline, baseline.copy(longTermMemory = "synthetic memory", memoryStateRevision = 7))
            }
            val stale = baselineReady.await()
            val last = f.chats.getMessages(f.session.id).last()
            f.chats.deleteMessage(last.id, f.session.id)
            val afterDeletion = f.chats.getSession(f.session.id)!!
            f.chats.saveSessionSettingsDraft(afterDeletion, afterDeletion.copy(playerName = "Updated player"))
            permitMemoryWrite.complete(Unit); memoryWrite.join()
            val actual = f.chats.getSession(f.session.id)!!
            assertEquals(afterDeletion.lastMessageRole, actual.lastMessageRole)
            assertEquals(afterDeletion.lastMessageTime, actual.lastMessageTime)
            assertNotEquals(stale.lastMessageTime, actual.lastMessageTime)
            assertEquals("Updated player", actual.playerName)
            assertEquals("synthetic memory", actual.longTermMemory)
            assertEquals(7L, actual.memoryStateRevision)
        } finally { f.close() }
    }
    private class Fixture(private val turns: Int = 4) {
        val root = Files.createTempDirectory("desktop-memory-lifecycle-")
        val gate = DesktopDataOperationCoordinator()
        val storage = JsonFileStorage(root, gate)
        val chats = ChatRepository(storage)
        val settings = SettingsRepository(storage)
        val repository = MemoryRepository(storage, true, gate)
        val session = ChatSession.create("synthetic-card", "Synthetic").copy(longTermMemoryEnabled = true)
        val model = ModelConfig("synthetic", "Synthetic", "https://invalid.example", "synthetic", "synthetic", createdAt = 1)
        var calls = 0
        var compressionCalls = 0
        var beforeEpisode: suspend () -> Unit = {}
        val ai = object : MemoryAiClient {
            override suspend fun episode(model: ModelConfig, renderedTurns: String, summaryPromptMaxChars: Int,
                onStreamingSummary: ((String) -> Unit)?, validate: (EpisodeResponse) -> Unit): EpisodeResponse {
                calls++; beforeEpisode()
                return EpisodeResponse("旅人在广场与同伴会合，约定次日出发。").also(validate)
            }
            override suspend fun compression(model: ModelConfig, kind: MemoryCompressionKind,
                forcedConsumedChildIds: List<String>, renderedChildren: String,
                onStreamingSummary: ((String) -> Unit)?, validate: (CompressionResponse) -> Unit): CompressionResponse {
                compressionCalls++
                val ids = forcedConsumedChildIds.ifEmpty {
                    Regex("\\[childId=([^｜]+)｜").findAll(renderedChildren).take(3).map { it.groupValues[1] }.toList()
                }
                return CompressionResponse(true, ids, "旅人与同伴确认旅程路线，在广场整理装备，并约定次日共同前往车站；各人分担物资，保留沿途约定，准备继续旅程。").also(validate)
            }
            override suspend fun head(model: ModelConfig, mode: MemoryHeadUpdateMode, throughT: Long,
                currentHead: String, archive: String, sourceTurns: String, validate: (HeadResponse) -> Unit): HeadResponse {
                calls++; return HeadResponse(throughT, location = "合成广场", goals = "准备旅程").also(validate)
            }
        }
        val service = LongTermMemoryService(chats, repository, settings, ai, ContextWindowManager(), gate,
            DesktopMemorySources(root, storage, chats)::ensure)
        suspend fun setup() {
            chats.createSession(session)
            settings.saveAppSettings(AppSettings(defaultContextWindowSize = 1, episodeMaxSourceTurns = 1))
            service.ensureMigrated(session.id)
            repeat(turns) { index ->
                val turn = "turn-$index"
                for (role in listOf(MessageRole.USER, MessageRole.ASSISTANT)) {
                    val time = index * 2L + if (role == MessageRole.USER) 1 else 2
                    chats.addMessage(ChatMessage("message-$time", session.id, role,
                        "合成旅程第${index + 1}轮：旅人在广场与同伴会合。", createdAt = time, updatedAt = time,
                        sourceTurnId = turn, sourceTurnOrder = index.toLong()))
                }
            }
        }
        suspend fun close() { gate.closeAndDrain(); root.toFile().deleteRecursively() }
    }

    @Test fun `budget decision compresses an official continuous episode prefix and retains child evidence`(): Unit = runBlocking {
        val f = Fixture(turns = 7)
        try {
            f.setup()
            f.service.updateArchiveAfterReply(f.session.id, f.model, 1, maxEpisodeBatches = 4)
            val episodes = f.service.activeNodes(f.session.id)
            assertEquals(4, episodes.size)
            episodes.take(3).forEach { f.service.editNode(f.session.id, it.id, "合成旅程事实与人物约定。".repeat(65)) }
            val before = f.service.activeNodes(f.session.id)
            f.service.updateArchiveAfterReply(f.session.id, f.model, 1, maxEpisodeBatches = 1)
            assertNotNull(f.repository.getState(f.session.id)!!.pendingDecision)
            assertEquals(0, f.compressionCalls)
            f.service.resolveCompressionDecision(f.session.id, expand = false)
            f.service.updateArchiveAfterReply(f.session.id, f.model, 1, maxEpisodeBatches = 1)
            val active = f.service.activeNodes(f.session.id)
            val parent = active.single { it.tier == MemoryTier.ARC }
            assertEquals(1, f.compressionCalls)
            assertEquals(before.take(3).map { it.id }, parent.childIds)
            parent.childIds.forEach { assertNotNull(f.repository.getNode(it)) }
            assertTrue(f.service.promptView(f.session.id).archive.contains(parent.body))
            assertEquals(14, f.chats.getMessages(f.session.id).size)
        } finally { f.close() }
    }

    @Test fun `official archive head revisions and source invalidation work on strict Desktop storage`(): Unit = runBlocking {
        val f = Fixture()
        try {
            f.setup()
            val updated = f.service.updateArchiveAfterReply(f.session.id, f.model, contextWindowSize = 1, maxEpisodeBatches = 1)
            assertEquals(1, updated.committedEpisodes)
            val original = f.service.activeNodes(f.session.id).single()
            assertEquals(MemoryAuthor.AI, original.author)
            assertTrue(f.service.promptView(f.session.id).archive.contains(original.body))
            f.service.maintainHeadAutomatically(f.session.id, f.model)
            assertTrue(f.service.promptView(f.session.id).headPresent)
            f.service.editNode(f.session.id, original.id, "旅人与同伴约定在合成广场会合。")
            assertEquals(MemoryAuthor.USER, f.service.activeNodes(f.session.id).single().author)
            val editedRevision = f.repository.getState(f.session.id)!!.episodePage.currentRevisionId!!
            val oldRevision = f.repository.getRevision(editedRevision)!!.parentRevisionId!!
            f.service.restoreTierRevision(f.session.id, oldRevision)
            assertEquals(original.id, f.service.activeNodes(f.session.id).single().id)
            val beforeMutation = f.calls
            val source = f.chats.getMessages(f.session.id).first { it.sourceTurnId in original.sourceTurnIds && it.role == MessageRole.USER }
            f.chats.updateMessage(source.copy(content = "历史事实改为在车站会合。", updatedAt = 100))
            val changed = f.service.refreshForCurrentConditions(f.session.id)
            assertFalse(changed.archive.contains(original.body))
            assertEquals(beforeMutation, f.calls)
            assertTrue(f.repository.getState(f.session.id)!!.staleSourcesByNodeId.isNotEmpty())
            assertNotNull(f.repository.getNode(original.id))
            f.service.startSourceRepair(f.session.id, f.model)
            assertTrue(f.repository.getState(f.session.id)!!.staleSourcesByNodeId.isEmpty())
            assertNotEquals(original.id, f.service.activeNodes(f.session.id).single().id)
            f.service.resetForFullRegeneration(f.session.id)
            assertTrue(f.service.activeNodes(f.session.id).isEmpty())
            assertTrue(f.repository.getState(f.session.id)!!.fullRegenerationPending)
            assertEquals(8, f.chats.getMessages(f.session.id).size)
            f.service.startBackfill(f.session.id, f.model)
            f.service.markFullRegenerationCompleteAfterBackfill(f.session.id)
            assertFalse(f.repository.getState(f.session.id)!!.fullRegenerationPending)
            assertTrue(f.service.activeNodes(f.session.id).isNotEmpty())
            assertTrue(f.service.promptView(f.session.id).headPresent)
        } finally { f.close() }
    }

    @Test fun `cancelled in flight memory keeps sources and releases real maintenance admission`(): Unit = runBlocking {
        val f = Fixture()
        try {
            f.setup()
            val entered = CompletableDeferred<Unit>()
            f.beforeEpisode = { entered.complete(Unit); awaitCancellation() }
            val work = launch { f.service.updateArchiveAfterReply(f.session.id, f.model, contextWindowSize = 1) }
            withTimeout(5000) { entered.await() }
            val exclusiveEntered = CompletableDeferred<Unit>()
            val maintenance = launch(start = CoroutineStart.UNDISPATCHED) {
                f.gate.withExclusiveMaintenance { exclusiveEntered.complete(Unit) }
            }
            assertFalse(exclusiveEntered.isCompleted)
            work.cancelAndJoin(); withTimeout(5000) { maintenance.join() }
            assertTrue(exclusiveEntered.isCompleted)
            assertEquals(8, f.chats.getMessages(f.session.id).size)
            assertTrue(f.service.activeNodes(f.session.id).isEmpty())
            assertTrue(f.repository.getState(f.session.id)!!.pendingSourceTurnIds.isNotEmpty())
            assertEquals(DesktopDataOperationCoordinatorState.OPEN, f.gate.state)
        } finally { f.close() }
    }
}
