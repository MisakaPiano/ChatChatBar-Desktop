package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.*
import com.example.chatbar.domain.chat.ContextWindowManager
import com.example.chatbar.domain.memory.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** Uses the unchanged public service API so the exact handed-off product is executable as a control. */
class DesktopActionMemoryPriorRegressionTest {
    @Test fun `actual episode request excludes unchosen action menus`() = runBlocking {
        val root = Files.createTempDirectory("action-memory-control-")
        val gate = DesktopDataOperationCoordinator()
        try {
            val storage = JsonFileStorage(root, gate)
            val chats = ChatRepository(storage)
            val repository = MemoryRepository(storage, true, gate)
            val settings = SettingsRepository(storage)
            val session = ChatSession.create("synthetic-card", "Synthetic memory control").copy(longTermMemoryEnabled = true)
            val model = ModelConfig("synthetic", "Synthetic", "https://invalid.example", "synthetic", "synthetic", createdAt = 1)
            val requests = mutableListOf<String>()
            val ai = object : MemoryAiClient {
                override suspend fun episode(model: ModelConfig, renderedTurns: String, summaryPromptMaxChars: Int,
                    onStreamingSummary: ((String) -> Unit)?, validate: (EpisodeResponse) -> Unit): EpisodeResponse {
                    requests += renderedTurns
                    return EpisodeResponse("旅人在广场与同伴会合，约定次日出发。").also(validate)
                }
                override suspend fun compression(model: ModelConfig, kind: MemoryCompressionKind,
                    forcedConsumedChildIds: List<String>, renderedChildren: String,
                    onStreamingSummary: ((String) -> Unit)?, validate: (CompressionResponse) -> Unit): CompressionResponse = error("No compression expected")
                override suspend fun head(model: ModelConfig, mode: MemoryHeadUpdateMode, throughT: Long,
                    currentHead: String, archive: String, sourceTurns: String, validate: (HeadResponse) -> Unit): HeadResponse = error("No HEAD request expected")
            }
            val service = LongTermMemoryService(chats, repository, settings, ai, ContextWindowManager(), gate,
                DesktopMemorySources(root, storage, chats)::ensure)
            chats.createSession(session)
            settings.saveAppSettings(AppSettings(defaultContextWindowSize = 1, episodeMaxSourceTurns = 1))
            service.ensureMigrated(session.id)
            val raw = "星图展开了。\n[choices]\nChoices\nA. UNCHOSEN_MENU_LEFT\nB. UNCHOSEN_MENU_RIGHT\n[/choices]"
            repeat(4) { index ->
                for (role in listOf(MessageRole.USER, MessageRole.ASSISTANT)) {
                    val time = index * 2L + if (role == MessageRole.USER) 1 else 2
                    chats.addMessage(ChatMessage("message-$time", session.id, role,
                        if (time == 2L) raw else "合成旅程第${index + 1}轮：旅人在广场与同伴会合。",
                        createdAt = time, updatedAt = time, sourceTurnId = "turn-$index", sourceTurnOrder = index.toLong(),
                        actionOptionsFormat = if (time == 2L) ActionOptionsFormat("[choices]", "[/choices]", "Choices", ActionOptionLabels.LETTERS) else null))
                }
            }
            assertEquals(1, service.updateArchiveAfterReply(session.id, model, 1, maxEpisodeBatches = 1).committedEpisodes)
            assertTrue(requests.isNotEmpty())
            assertFalse(requests.any { "UNCHOSEN_MENU" in it }, "Unchosen action menus entered the actual episode request")
            assertEquals(raw, chats.getMessage("message-2", session.id)!!.displayContent)
        } finally { gate.closeAndDrain(); root.toFile().deleteRecursively() }
    }
}
