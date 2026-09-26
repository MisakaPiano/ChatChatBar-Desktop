package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.local.entity.WorldBookEntry
import com.example.chatbar.domain.chat.MainChatLogicalMessageTrace
import com.example.chatbar.domain.chat.MainChatLogicalMessageSource
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopPromptInspectorTest {
    @Test
    fun inspectionIsZeroWriteAndExposesAuthoritativeTraceCacheAndWorldBookEvidence() = runTest {
        withContainerAndRoot { container, root ->
            val book = WorldBook(
                id = "inspector-book",
                name = "Inspector Book",
                entries = listOf(
                    WorldBookEntry(
                        id = "inspector-entry",
                        name = "Triggered entry",
                        keys = listOf("inspect-trigger"),
                        content = "evidence-for-\$username",
                        sticky = 2,
                        probability = 100,
                    ),
                ),
                createdAt = 1,
                updatedAt = 2,
            )
            container.worldBookRepository.save(book)
            val character = CharacterCard.create("Inspector", greeting = "Greeting").copy(
                basicSetting = "character-for-\$username",
                worldBookIds = listOf(book.id),
            )
            container.characterRepository.save(character)
            val sessionId = container.characterSessionService.createSessionForCharacter(character.id)
            val originalSession = requireNotNull(container.chatRepository.getSession(sessionId))
            container.chatRepository.updateSession(
                originalSession.copy(playerName = "SessionPlayer", playerSetting = "session-persona"),
            )
            val current = container.chatRepository.addMessage(
                ChatMessage.create(sessionId, MessageRole.USER, "inspect-trigger current-user"),
            )
            val controller = container.createPromptInspectorController()
            controller.refresh()
            controller.updateInputs {
                it.copy(
                    effectiveContextWindowSize = "20",
                    globalPlayerName = "GlobalPlayer",
                    globalPlayerSetting = "global-persona",
                )
            }
            val persistedIndex = root.resolve("entities/chat_message_indexes/$sessionId.json")
            Files.deleteIfExists(persistedIndex)
            val before = fileSnapshot(root)

            controller.inspect()

            val result = assertIs<DesktopPromptInspectorStatus.Ready>(controller.state.value.status).result
            val directPlan = container.chatRequestPlanner.planPersistedUser(
                sessionId = sessionId,
                userMessageId = current.id,
                inputs = DesktopFakeChatInputs(
                    globalPlayerName = "GlobalPlayer",
                    globalPlayerSetting = "global-persona",
                    effectiveContextWindowSize = 20,
                ),
                readOnlyRepositoryAccess = true,
            )
            assertEquals(before, fileSnapshot(root))
            assertFalse(Files.exists(persistedIndex))
            assertEquals(emptyMap(), requireNotNull(container.chatRepository.getSession(sessionId)).timedWorldInfo)
            assertTrue(result.proposedTimedWorldInfo.isNotEmpty())
            assertEquals(directPlan.assembly.messages, result.logicalMessages.map(MainChatLogicalMessageTrace::message))
            assertEquals(
                result.logicalMessages.filter(MainChatLogicalMessageTrace::inStableCachePrefix)
                    .map(MainChatLogicalMessageTrace::message),
                result.stablePrefixMessages,
            )
            assertEquals(directPlan.assembly.promptCacheKey, result.promptCacheKey)
            assertTrue(result.stablePrefixCacheable)
            assertTrue(result.logicalMessages.all { it.source in MainChatLogicalMessageSource.entries })
            assertEquals(
                directPlan.assembly.messageTrace.map(MainChatLogicalMessageTrace::source),
                result.logicalMessages.map(MainChatLogicalMessageTrace::source),
            )
            assertEquals(
                1,
                result.logicalMessages.count {
                    it.message.content.jsonPrimitive.content.contains("inspect-trigger current-user")
                },
            )
            val contents = result.logicalMessages.map { it.message.content.jsonPrimitive.content }
            assertTrue(contents.any { "SessionPlayer" in it })
            assertFalse(contents.any { "GlobalPlayer" in it || "global-persona" in it })
            assertTrue(result.worldBookEvidence.any { "扫描最近" in it })
            assertTrue(result.worldBookEvidence.any { "Inspector Book" in it && "来源" in it })
            assertTrue(result.worldBookEvidence.any { "选入" in it })
        }
    }

    @Test
    fun explicitContextInputOverridesPersistedSessionContextWindow() = runTest {
        withContainerAndRoot { container, _ ->
            val character = CharacterCard.create("Context", greeting = "Greeting")
            container.characterRepository.save(character)
            val sessionId = container.characterSessionService.createSessionForCharacter(character.id)
            addExchange(container, sessionId, "older-user", "older-assistant")
            addExchange(container, sessionId, "previous-user", "previous-assistant")
            val current = container.chatRepository.addMessage(
                ChatMessage.create(sessionId, MessageRole.USER, "current-user"),
            )
            val session = requireNotNull(container.chatRepository.getSession(sessionId))
            container.chatRepository.updateSession(session.copy(contextWindowSize = 99))
            val inspector = controllerFor(container, "0")

            inspector.inspect()
            val zero = ready(inspector).logicalMessages.contents()
            container.chatRepository.updateSession(
                requireNotNull(container.chatRepository.getSession(sessionId)).copy(contextWindowSize = 0),
            )
            inspector.updateInputs { it.copy(effectiveContextWindowSize = "1") }
            inspector.inspect()
            val one = ready(inspector).logicalMessages.contents()

            assertFalse(zero.contains("older-user"))
            assertTrue(one.contains("older-user"))
            assertEquals(1, zero.count { it == "current-user" })
            assertEquals(1, one.count { it == "current-user" })
            assertEquals(current.id, inspector.state.value.selectedUserMessageId)
        }
    }

    @Test
    fun unresolvedStableOutletExposesNonCacheablePrefixWithoutKey() = runTest {
        withContainerAndRoot { container, _ ->
            val character = CharacterCard.create("No cache", greeting = "Greeting").copy(
                basicSetting = "{{outlet::missing}}",
            )
            container.characterRepository.save(character)
            val sessionId = container.characterSessionService.createSessionForCharacter(character.id)
            container.chatRepository.addMessage(ChatMessage.create(sessionId, MessageRole.USER, "current"))
            val controller = controllerFor(container, "20")

            controller.inspect()

            val result = ready(controller)
            assertFalse(result.stablePrefixCacheable)
            assertNull(result.promptCacheKey)
            assertTrue(result.stablePrefixMessages.isNotEmpty())
            assertTrue(result.logicalMessages.any(MainChatLogicalMessageTrace::inStableCachePrefix))
        }
    }

    @Test
    fun controllerRefreshSelectionAndInspectionRemainReadOnly() = runTest {
        withContainerAndRoot { container, root ->
            val character = CharacterCard.create("Controller", greeting = "Greeting")
            container.characterRepository.save(character)
            val firstSession = container.characterSessionService.createSessionForCharacter(character.id)
            container.chatRepository.addMessage(ChatMessage.create(firstSession, MessageRole.USER, "first-user"))
            val secondSession = container.characterSessionService.createSessionForCharacter(character.id)
            container.chatRepository.addMessage(ChatMessage.create(secondSession, MessageRole.USER, "second-user"))
            val controller = container.createPromptInspectorController()
            val before = fileSnapshot(root)

            controller.refresh()
            controller.selectSession(firstSession)
            controller.selectUserMessage(controller.state.value.userMessages.single().id)
            controller.updateInputs { it.copy(effectiveContextWindowSize = "20") }
            controller.inspect()

            assertIs<DesktopPromptInspectorStatus.Ready>(controller.state.value.status)
            assertEquals(before, fileSnapshot(root))
        }
    }

    @Test
    fun controllerRepresentsNoSessionsAndNoUserMessagesWithoutCrashing() = runTest {
        withContainerAndRoot { container, root ->
            val emptyController = container.createPromptInspectorController()
            val emptyBefore = fileSnapshot(root)
            emptyController.refresh()
            assertTrue(emptyController.state.value.sessions.isEmpty())
            emptyController.inspect()
            assertIs<DesktopPromptInspectorStatus.Error>(emptyController.state.value.status)
            assertEquals(emptyBefore, fileSnapshot(root))

            val character = CharacterCard.create("Greeting only", greeting = "Greeting")
            container.characterRepository.save(character)
            container.characterSessionService.createSessionForCharacter(character.id)
            val controller = container.createPromptInspectorController()
            val before = fileSnapshot(root)
            controller.refresh()

            assertTrue(controller.state.value.sessions.isNotEmpty())
            assertTrue(controller.state.value.userMessages.isEmpty())
            controller.updateInputs { it.copy(effectiveContextWindowSize = "20") }
            controller.inspect()
            assertIs<DesktopPromptInspectorStatus.Error>(controller.state.value.status)
            assertEquals(before, fileSnapshot(root))
        }
    }

    private suspend fun controllerFor(
        container: DesktopAppContainer,
        effectiveContextWindowSize: String,
    ): DesktopPromptInspectorController = container.createPromptInspectorController().also { controller ->
        controller.refresh()
        controller.updateInputs { it.copy(effectiveContextWindowSize = effectiveContextWindowSize) }
    }

    private fun ready(controller: DesktopPromptInspectorController): DesktopPromptInspectionResult =
        assertIs<DesktopPromptInspectorStatus.Ready>(controller.state.value.status).result

    private suspend fun addExchange(
        container: DesktopAppContainer,
        sessionId: String,
        user: String,
        assistant: String,
    ) {
        container.chatRepository.addMessage(ChatMessage.create(sessionId, MessageRole.USER, user))
        container.chatRepository.addMessage(ChatMessage.create(sessionId, MessageRole.ASSISTANT, assistant))
    }

    private suspend fun withContainerAndRoot(block: suspend (DesktopAppContainer, Path) -> Unit) {
        val parent = Files.createTempDirectory("desktop-prompt-inspector-")
        val root = Files.createDirectory(parent.resolve("app-data"))
        val container = DesktopAppContainer(
            DesktopDataRootResolution.Resolved(
                appDataRoot = root,
                provenance = DesktopDataRootProvenance.BOOTSTRAP_CUSTOM,
                bootstrapPath = parent.resolve("bootstrap.json"),
            ),
        )
        try {
            block(container, root)
        } finally {
            container.close()
            parent.toFile().deleteRecursively()
        }
    }

    private fun List<MainChatLogicalMessageTrace>.contents(): List<String> =
        map { it.message.content.jsonPrimitive.content }

    private fun fileSnapshot(root: Path): Map<String, List<Byte>> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) }.iterator().asSequence()
            .associate { path -> root.relativize(path).toString() to Files.readAllBytes(path).toList() }
    }
}
