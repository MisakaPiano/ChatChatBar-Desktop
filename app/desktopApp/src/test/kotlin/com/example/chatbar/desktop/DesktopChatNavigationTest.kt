package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatScrollPosition
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DesktopChatNavigationTest {
    @Test fun `selection loads a bounded window around saved historical anchor rather than latest`() = runBlocking {
        withContainer { container ->
            val (id, messages) = longSession(container)
            val anchor = messages[25].id
            container.chatRepository.updateScrollPosition(ChatScrollPosition(id, anchor, 25, 31, 100))
            container.primaryChatController.selectSession(id)
            val state = container.primaryChatController.state.value
            assertTrue(state.messages.any { it.id == anchor }, "Saved anchor must be in the initially loaded window")
            assertTrue(state.messages.size < messages.size)
            assertTrue(state.hasNewerMessages)
            assertEquals(31, state.readingPosition?.scrollOffset)
        }
    }

    @Test fun `no saved position loads latest and load older remains available`() = runBlocking {
        withContainer { container ->
            val (id, messages) = longSession(container)
            val controller = container.primaryChatController
            controller.selectSession(id)
            val latest = controller.state.value
            assertEquals(messages.last().id, latest.messages.last().id)
            assertFalse(latest.hasNewerMessages)
            assertTrue(latest.hasOlderMessages)
            assertNull(latest.readingPosition)
            controller.loadOlder()
            assertTrue(controller.state.value.messages.size > latest.messages.size)
            assertEquals(latest.messages.last().id, controller.state.value.messages.last().id)
        }
    }
    @Test fun `first and latest navigation replace with bounded windows and newer pages append`() = runBlocking {
        withContainer { container ->
            val (id, messages) = longSession(container)
            val controller = container.primaryChatController
            controller.selectSession(id)
            assertEquals(messages.first().id, controller.loadFirstMessageWindow(id))
            val first = controller.state.value
            assertFalse(first.hasOlderMessages)
            assertTrue(first.hasNewerMessages)
            assertTrue(first.messages.size <= 120)
            controller.loadNewer()
            assertTrue(controller.state.value.messages.size > first.messages.size)
            assertEquals(first.messages.first().id, controller.state.value.messages.first().id)
            assertEquals(messages.last().id, controller.loadLatestMessageWindow(id))
            assertTrue(controller.state.value.messages.size <= 80)
            assertFalse(controller.state.value.hasNewerMessages)
            assertNull(controller.loadFirstMessageWindow("not-selected"))
        }
    }
    @Test fun `A B A reading positions and composer drafts remain independent`() = runBlocking {
        withContainer { container ->
            val (a, messages) = longSession(container)
            val b = container.characterSessionService.createSessionForCharacter(container.characterRepository.getAll().first().id)
            val bMessage = container.chatRepository.addMessage(ChatMessage.create(b, MessageRole.USER, "inline B"))
            val controller = container.primaryChatController
            controller.selectSession(a)
            controller.editComposer("draft A")
            controller.persistReadingPosition(ChatScrollPosition(a, messages[40].id, 40, 17, 0))
            controller.selectSession(b)
            controller.editComposer("draft B")
            controller.persistReadingPosition(ChatScrollPosition(b, bMessage.id, 0, 9, 0))
            controller.selectSession(a)
            assertEquals(messages[40].id, controller.state.value.readingPosition?.anchorMessageId)
            assertEquals(17, controller.state.value.readingPosition?.scrollOffset)
            assertEquals("draft A", controller.state.value.composerDraft)
            controller.selectSession(b)
            assertEquals(bMessage.id, controller.state.value.readingPosition?.anchorMessageId)
            assertEquals("draft B", controller.state.value.composerDraft)
        }
    }
    @Test fun `container restart restores durable anchor window and offset`() = runBlocking {
        val parent = Files.createTempDirectory("desktop-reading-restart-")
        try {
            val original = container(parent)
            val (id, messages) = longSession(original)
            original.primaryChatController.selectSession(id)
            original.primaryChatController.persistReadingPosition(ChatScrollPosition(id, messages[20].id, 20, 44, 0))
            original.close()
            val reopened = container(parent)
            try {
                reopened.primaryChatController.selectSession(id)
                val state = reopened.primaryChatController.state.value
                assertTrue(state.messages.any { it.id == messages[20].id })
                assertEquals(44, state.readingPosition?.scrollOffset)
            } finally { reopened.close() }
        } finally { parent.toFile().deleteRecursively() }
    }
    @Test fun `deleted anchor falls back and next stable snapshot replaces it`() = runBlocking {
        withContainer { container ->
            val (id, messages) = longSession(container)
            val controller = container.primaryChatController
            container.chatRepository.updateScrollPosition(ChatScrollPosition(id, messages[20].id, 20, 7, 1))
            container.chatRepository.deleteMessage(messages[20].id, id)
            controller.selectSession(id)
            val state = controller.state.value
            val map = DesktopChatTimelineMapping(state.messages.map { it.id }, hasOlder = state.hasOlderMessages)
            val target = assertNotNull(map.initialTarget(state.readingPosition))
            val captured = assertNotNull(map.captureStable(id,
                listOf(DesktopVisibleTimelineItem(target.index, map.keys[target.index], -7, 60)), 0, true, false, false))
            controller.persistReadingPosition(captured)
            assertNotEquals(messages[20].id, container.chatRepository.getScrollPosition(id)?.anchorMessageId)
            container.chatRepository.deleteSession(id)
            assertNull(container.chatRepository.getScrollPosition(id))
        }
    }

    private suspend fun longSession(container: DesktopAppContainer): Pair<String, List<ChatMessage>> {
        val card = CharacterCard.create("Inline navigation")
        container.characterRepository.save(card)
        val id = container.characterSessionService.createSessionForCharacter(card.id)
        val messages = (0 until 320).map { i ->
            ChatMessage.create(id, MessageRole.USER, "inline-$i").copy(
                id = "message-$i", createdAt = i.toLong() + 1, orderKey = i.toLong() + 1,
                sourceTurnId = "turn-$i", sourceTurnOrder = i.toLong() + 1,
            )
        }
        container.chatRepository.replaceMessagesForSession(id, messages)
        return id to messages
    }

    private suspend fun withContainer(block: suspend (DesktopAppContainer) -> Unit) {
        val parent = Files.createTempDirectory("desktop-chat-navigation-")
        val container = container(parent)
        try { block(container) } finally { container.close(); parent.toFile().deleteRecursively() }
    }

    private fun container(parent: Path) = DesktopAppContainer(
        DesktopDataRootResolution.Resolved(parent.resolve("data"),
            DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")),
        secretStoreFactory = { InMemoryDesktopSecretStore() },
    )
}
