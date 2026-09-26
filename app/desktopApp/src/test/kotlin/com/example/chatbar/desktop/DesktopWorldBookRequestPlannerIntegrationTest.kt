package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.local.entity.WorldBookEntry
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class DesktopWorldBookRequestPlannerIntegrationTest {
    @Test
    fun `container planner evaluates repository book against persisted chat messages`() = runTest {
        val parent = Files.createTempDirectory("desktop-world-book-planner-")
        val root = Files.createDirectory(parent.resolve("app-data"))
        val container = DesktopAppContainer(
            DesktopDataRootResolution.Resolved(
                appDataRoot = root,
                provenance = DesktopDataRootProvenance.BOOTSTRAP_CUSTOM,
                bootstrapPath = parent.resolve("bootstrap.json"),
            )
        )
        try {
            val book = WorldBook(
                id = "book",
                name = "Desktop book",
                entries = listOf(
                    WorldBookEntry(id = "entry", keys = listOf("remembered-key"), content = "Desktop lore")
                ),
            )
            container.worldBookRepository.save(book)
            val session = ChatSession(
                id = "session",
                characterCardId = "card",
                title = "Session",
                createdAt = 1,
                updatedAt = 1,
            )
            container.chatRepository.createSession(session)
            container.chatRepository.addMessage(
                ChatMessage(
                    id = "message",
                    sessionId = session.id,
                    role = MessageRole.USER,
                    content = "remembered-key",
                    createdAt = 2,
                    updatedAt = 2,
                )
            )
            val card = CharacterCard(
                id = "card",
                name = "Character",
                worldBookIds = listOf(book.id),
                createdAt = 1,
                updatedAt = 1,
            )

            val plan = container.worldBookRequestPlanner.plan(card, session)

            assertEquals("Desktop lore", plan.prompt)
            assertEquals(emptyMap(), plan.outlets)
        } finally {
            container.close()
            parent.toFile().deleteRecursively()
        }
    }
}
