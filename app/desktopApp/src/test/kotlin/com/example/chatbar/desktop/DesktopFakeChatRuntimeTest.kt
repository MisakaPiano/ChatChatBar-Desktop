package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.data.local.entity.FormatCardUserToolConfig
import com.example.chatbar.data.local.entity.FormatCardUserToolType
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.local.entity.WorldBookEntry
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopFakeChatRuntimeTest {
    @Test
    fun createSessionDelegatesGreetingPersistenceIncludingBlankGreeting() = runTest {
        withContainer { container ->
            val runtime = container.createFakeChatRuntime(CapturingDriver())
            val nonblank = CharacterCard.create("Nonblank", greeting = "Hello")
            val blank = CharacterCard.create("Blank", greeting = "")
            container.characterRepository.save(nonblank)
            container.characterRepository.save(blank)

            val nonblankSession = runtime.createSession(nonblank.id)
            val blankSession = runtime.createSession(blank.id)

            assertEquals("Nonblank", runtime.openSession(nonblankSession).session.title)
            assertEquals(
                listOf(MessageRole.ASSISTANT to "Hello"),
                runtime.openSession(nonblankSession).messages.map { it.role to it.content },
            )
            assertEquals(
                listOf(MessageRole.ASSISTANT to ""),
                runtime.openSession(blankSession).messages.map { it.role to it.content },
            )
        }
    }

    @Test
    fun openSessionReadsPersistedStateWithoutWriting() = runTest {
        withContainerAndRoot { container, root ->
            val character = CharacterCard.create("Open only", greeting = "Greeting")
            container.characterRepository.save(character)
            val runtime = container.createFakeChatRuntime(CapturingDriver())
            val sessionId = runtime.createSession(character.id)
            val before = fileSnapshot(root)

            val opened = runtime.openSession(sessionId)

            assertEquals(sessionId, opened.session.id)
            assertEquals(before, fileSnapshot(root))
        }
    }

    @Test
    fun normalSubmissionPersistsUserBeforeCaptureAndNeverCreatesFakeAssistant() = runTest {
        withContainer { container ->
            val character = CharacterCard.create("Character", greeting = "Greeting")
            container.characterRepository.save(character)
            val sessionId = container.characterSessionService.createSessionForCharacter(character.id)
            var rolesAtCapture: List<MessageRole>? = null
            val driver = DesktopFakeChatDriver { request ->
                rolesAtCapture = container.chatRepository.getMessages(request.sessionId).map(ChatMessage::role)
            }
            val runtime = container.createFakeChatRuntime(driver)

            val request = runtime.submitText(sessionId, "persist-before-capture", inputs())
            val persisted = container.chatRepository.getMessages(sessionId)

            assertEquals(listOf(MessageRole.ASSISTANT, MessageRole.USER), rolesAtCapture)
            assertEquals(listOf(MessageRole.ASSISTANT, MessageRole.USER), persisted.map(ChatMessage::role))
            assertEquals("persist-before-capture", persisted.last().content)
            assertNotNull(persisted.last().sourceTurnId)
            assertNotNull(persisted.last().sourceTurnOrder)
            assertEquals(1, request.contents().count { it == "persist-before-capture" })
            assertEquals(1, persisted.count { it.role == MessageRole.ASSISTANT })
        }
    }

    @Test
    fun missingCharacterFailsBeforeUserPersistence() = runTest {
        withContainer { container ->
            val character = CharacterCard.create("Removed", greeting = "Greeting")
            container.characterRepository.save(character)
            val runtime = container.createFakeChatRuntime(CapturingDriver())
            val sessionId = runtime.createSession(character.id)
            container.characterRepository.delete(character.id)
            val before = container.chatRepository.getMessages(sessionId)

            assertFailsWith<IllegalArgumentException> {
                runtime.submitText(sessionId, "must-not-persist", inputs())
            }

            assertEquals(before, container.chatRepository.getMessages(sessionId))
        }
    }

    @Test
    fun explicitEffectiveContextWindowControlsSelectionInsteadOfSessionField() = runTest {
        withContainer { container ->
            val character = CharacterCard.create("Context", greeting = "Greeting")
            container.characterRepository.save(character)
            val runtime = container.createFakeChatRuntime(CapturingDriver())
            val sessionId = runtime.createSession(character.id)
            addExchange(container, sessionId, "older-user", "older-assistant")
            addExchange(container, sessionId, "previous-user", "previous-assistant")
            val current = container.chatRepository.addMessage(
                ChatMessage.create(sessionId, MessageRole.USER, "current-user"),
            )
            val session = requireNotNull(container.chatRepository.getSession(sessionId))
            container.chatRepository.updateSession(session.copy(contextWindowSize = 99))

            val zero = runtime.rebuildForPersistedUser(
                sessionId,
                current.id,
                inputs(effectiveContextWindowSize = 0),
            )
            container.chatRepository.updateSession(
                requireNotNull(container.chatRepository.getSession(sessionId)).copy(contextWindowSize = 0),
            )
            val one = runtime.rebuildForPersistedUser(
                sessionId,
                current.id,
                inputs(effectiveContextWindowSize = 1),
            )

            assertFalse(zero.contents().contains("older-user"))
            assertTrue(one.contents().contains("older-user"))
            assertEquals(1, zero.contents().count { it == "current-user" })
            assertEquals(1, one.contents().count { it == "current-user" })
        }
    }

    @Test
    fun sharedFormatWorldBookPlayerAndUserToolPoliciesReachCapturedRequest() = runTest {
        withContainer { container ->
            val format = FormatCard(
                id = "format",
                name = "Format",
                content = "format-for-{{user}}",
                userTools = listOf(
                    FormatCardUserToolConfig(
                        type = FormatCardUserToolType.RANDOM_NUMBER,
                        minimum = "7",
                        maximum = "7",
                    ),
                    FormatCardUserToolConfig(
                        type = FormatCardUserToolType.STRONG_PROMPT_SUFFIX,
                        text = "strong-fixture",
                    ),
                ),
                createdAt = 1,
            )
            val book = WorldBook(
                id = "book",
                name = "Book",
                entries = listOf(
                    WorldBookEntry(
                        id = "entry",
                        keys = listOf("trigger"),
                        content = "worldbook-for-\$username",
                        sticky = 2,
                        probability = 100,
                    ),
                ),
                createdAt = 1,
                updatedAt = 1,
            )
            container.formatCardRepository.save(format)
            container.worldBookRepository.save(book)
            val character = CharacterCard.create("Character", greeting = "Greeting").copy(
                botName = "Bot",
                basicSetting = "character-for-\$username",
                defaultFormatCardId = format.id,
                worldBookIds = listOf(book.id),
            )
            container.characterRepository.save(character)
            val driver = CapturingDriver()
            val runtime = container.createFakeChatRuntime(driver)
            val sessionId = runtime.createSession(character.id)
            val createdSession = requireNotNull(container.chatRepository.getSession(sessionId))
            container.chatRepository.updateSession(
                createdSession.copy(
                    playerName = "SessionPlayer",
                    playerSetting = "session-persona",
                ),
            )

            val request = runtime.submitText(
                sessionId,
                "trigger original-user",
                inputs(
                    globalPlayerName = "GlobalPlayer",
                    globalPlayerSetting = "global-persona",
                ),
            )
            val contents = request.contents()
            val persisted = container.chatRepository.getMessages(sessionId).last()
            val timed = requireNotNull(container.chatRepository.getSession(sessionId)).timedWorldInfo

            assertEquals("trigger original-user", persisted.content)
            assertEquals(1, contents.count { it.contains("trigger original-user") })
            assertTrue(contents.any { it.contains("下一轮使用随机数：7") })
            assertTrue(contents.any { it.contains("format-for-SessionPlayer") })
            assertTrue(contents.any { it.contains("worldbook-for-SessionPlayer") })
            assertTrue(contents.any { it.contains("character-for-SessionPlayer") })
            assertFalse(contents.any { it.contains("GlobalPlayer") || it.contains("global-persona") })
            val strongIndex = contents.indexOf("strong-fixture")
            assertTrue(strongIndex > contents.indexOfFirst { it.contains("trigger original-user") })
            assertEquals("assistant", request.messages[strongIndex + 1].role)
            assertEquals("user", request.messages[strongIndex + 2].role)
            assertTrue("book::entry" in timed)
            assertEquals(request, driver.requests.single())
        }
    }

    @Test
    fun staleSessionFormatFallsBackToSuppliedLiveDefault() = runTest {
        withContainer { container ->
            val defaultFormat = FormatCard(
                id = "global-format",
                name = "Global",
                content = "global-format-content",
                createdAt = 1,
            )
            container.formatCardRepository.save(defaultFormat)
            val character = CharacterCard.create("Character", greeting = "Greeting")
            container.characterRepository.save(character)
            val runtime = container.createFakeChatRuntime(CapturingDriver())
            val sessionId = runtime.createSession(character.id)
            container.chatRepository.updateSession(
                requireNotNull(container.chatRepository.getSession(sessionId)).copy(formatCardId = "missing-format"),
            )

            val request = runtime.submitText(
                sessionId,
                "current",
                inputs(defaultFormatCardId = defaultFormat.id),
            )

            assertTrue(request.contents().any { it.contains("global-format-content") })
        }
    }

    @Test
    fun unchangedWorldBookTimedStateDoesNotRewriteSession() = runTest {
        withContainer { container ->
            val book = WorldBook(
                id = "timed-book",
                name = "Timed",
                entries = listOf(
                    WorldBookEntry(
                        id = "timed-entry",
                        keys = listOf("timed-trigger"),
                        content = "timed-lore",
                        sticky = 2,
                        probability = 100,
                    ),
                ),
                createdAt = 1,
                updatedAt = 1,
            )
            container.worldBookRepository.save(book)
            val character = CharacterCard.create("Timed Character", greeting = "Greeting")
                .copy(worldBookIds = listOf(book.id))
            container.characterRepository.save(character)
            val runtime = container.createFakeChatRuntime(CapturingDriver())
            val sessionId = runtime.createSession(character.id)
            runtime.submitText(sessionId, "timed-trigger", inputs())
            val user = container.chatRepository.getMessages(sessionId).last()
            val afterFirstPlan = requireNotNull(container.chatRepository.getSession(sessionId))
            assertTrue(afterFirstPlan.timedWorldInfo.isNotEmpty())
            val sentinelUpdatedAt = Long.MAX_VALUE - 1
            container.chatRepository.createSession(afterFirstPlan.copy(updatedAt = sentinelUpdatedAt))

            runtime.rebuildForPersistedUser(sessionId, user.id, inputs())

            assertEquals(
                sentinelUpdatedAt,
                requireNotNull(container.chatRepository.getSession(sessionId)).updatedAt,
            )
        }
    }

    @Test
    fun persistedUserRebuildAfterContainerRestartIsSemanticallyIdenticalAndWriteFree() = runTest {
        val parent = Files.createTempDirectory("desktop-fake-chat-restart-")
        val root = Files.createDirectory(parent.resolve("app-data"))
        val resolution = resolution(parent, root)
        val inputs = inputs(globalPlayerName = "Player")
        lateinit var sessionId: String
        lateinit var userId: String
        lateinit var firstRequest: DesktopFakeChatRequest
        try {
            val first = DesktopAppContainer(resolution)
            try {
                val book = WorldBook(
                    id = "restart-book",
                    name = "Restart",
                    entries = listOf(
                        WorldBookEntry(
                            id = "restart-entry",
                            keys = listOf("restart-trigger"),
                            content = "restart-lore",
                            sticky = 2,
                            probability = 100,
                        ),
                    ),
                    createdAt = 1,
                    updatedAt = 1,
                )
                first.worldBookRepository.save(book)
                val character = CharacterCard.create("Restart Character", greeting = "Restart greeting")
                    .copy(worldBookIds = listOf(book.id))
                first.characterRepository.save(character)
                val runtime = first.createFakeChatRuntime(CapturingDriver())
                sessionId = runtime.createSession(character.id)
                firstRequest = runtime.submitText(sessionId, "restart-trigger", inputs)
                val messages = first.chatRepository.getMessages(sessionId)
                userId = messages.last().id
                assertEquals(listOf("Restart greeting", "restart-trigger"), messages.map(ChatMessage::content))
            } finally {
                first.close()
            }

            val second = DesktopAppContainer(resolution)
            try {
                val driver = CapturingDriver()
                val runtime = second.createFakeChatRuntime(driver)
                val opened = runtime.openSession(sessionId)
                assertEquals(listOf("Restart greeting", "restart-trigger"), opened.messages.map(ChatMessage::content))
                val beforeCount = opened.messages.size
                val secondRequest = runtime.rebuildForPersistedUser(sessionId, userId, inputs)

                assertEquals(firstRequest.messages, secondRequest.messages)
                assertEquals(firstRequest.promptCacheKey, secondRequest.promptCacheKey)
                assertEquals(beforeCount, second.chatRepository.getMessages(sessionId).size)
                assertEquals(1, secondRequest.contents().count { it == "restart-trigger" })
                assertEquals(secondRequest, driver.requests.single())
            } finally {
                second.close()
            }
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun rebuildRejectsMissingOrNonUserMessage() = runTest {
        withContainer { container ->
            val character = CharacterCard.create("Character", greeting = "Greeting")
            container.characterRepository.save(character)
            val runtime = container.createFakeChatRuntime(CapturingDriver())
            val sessionId = runtime.createSession(character.id)
            val greeting = container.chatRepository.getMessages(sessionId).single()

            assertFailsWith<IllegalArgumentException> {
                runtime.rebuildForPersistedUser(sessionId, greeting.id, inputs())
            }
            assertFailsWith<IllegalArgumentException> {
                runtime.rebuildForPersistedUser(sessionId, "missing", inputs())
            }
        }
    }

    private suspend fun addExchange(
        container: DesktopAppContainer,
        sessionId: String,
        user: String,
        assistant: String,
    ) {
        container.chatRepository.addMessage(ChatMessage.create(sessionId, MessageRole.USER, user))
        container.chatRepository.addMessage(ChatMessage.create(sessionId, MessageRole.ASSISTANT, assistant))
    }

    private fun inputs(
        globalPlayerName: String? = null,
        globalPlayerSetting: String? = null,
        effectiveContextWindowSize: Int = 20,
        defaultFormatCardId: String? = null,
    ) = DesktopFakeChatInputs(
        globalPlayerName = globalPlayerName,
        globalPlayerSetting = globalPlayerSetting,
        effectiveContextWindowSize = effectiveContextWindowSize,
        defaultFormatCardId = defaultFormatCardId,
    )

    private suspend fun withContainer(block: suspend (DesktopAppContainer) -> Unit) =
        withContainerAndRoot { container, _ -> block(container) }

    private suspend fun withContainerAndRoot(
        block: suspend (DesktopAppContainer, Path) -> Unit,
    ) {
        val parent = Files.createTempDirectory("desktop-fake-chat-")
        val root = Files.createDirectory(parent.resolve("app-data"))
        val container = DesktopAppContainer(resolution(parent, root))
        try {
            block(container, root)
        } finally {
            container.close()
            parent.toFile().deleteRecursively()
        }
    }

    private fun resolution(parent: Path, root: Path) = DesktopDataRootResolution.Resolved(
        appDataRoot = root,
        provenance = DesktopDataRootProvenance.BOOTSTRAP_CUSTOM,
        bootstrapPath = parent.resolve("bootstrap.json"),
    )

    private fun DesktopFakeChatRequest.contents(): List<String> =
        messages.map { it.content.jsonPrimitive.content }

    private fun fileSnapshot(root: Path): Map<String, List<Byte>> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) }.iterator().asSequence()
            .associate { path -> root.relativize(path).toString() to Files.readAllBytes(path).toList() }
    }

    private class CapturingDriver : DesktopFakeChatDriver {
        val requests = mutableListOf<DesktopFakeChatRequest>()

        override suspend fun capture(request: DesktopFakeChatRequest) {
            requests += request
        }
    }
}
