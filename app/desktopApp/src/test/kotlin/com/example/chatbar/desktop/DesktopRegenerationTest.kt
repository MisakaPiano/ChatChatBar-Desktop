package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.prompt.MainChatPromptAuthority
import com.example.chatbar.domain.chat.MainChatLogicalMessageSource
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import kotlin.test.*

class DesktopRegenerationTest {
    @Test fun `regeneration preserves same row and current user while excluding target from prompt and worldbook`() = runBlocking {
        fixture { container, server, session, target ->
            val book = WorldBook(id = "book", name = "book", entries = listOf(
                WorldBookEntry(id = "entry", keys = listOf("old-answer"), content = "excluded-lore", probability = 100),
            ), createdAt = 1, updatedAt = 1)
            container.worldBookRepository.save(book)
            val originalSession = container.chatRepository.getSession(session)!!
            val card = container.characterRepository.getById(originalSession.characterCardId)!!
            container.characterRepository.save(card.copy(worldBookIds = listOf(book.id)))
            val controller = container.primaryChatController
            controller.refresh()
            repeat(2) { index ->
                server.enqueue(success("new-$index"))
                val result = container.createRealChatRuntime().regenerate(session, target.id)
                val body = server.takeRequest().body.readUtf8()
                val logical = Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray
                val texts = logical.map { it.jsonObject["content"]!!.jsonPrimitive.content }
                assertEquals(1, texts.count { it == "real-user" })
                assertFalse(texts.any { it.contains("old-answer") || it.contains("excluded-lore") })
                assertFalse(texts.contains(MainChatPromptAuthority.continueGenerationUserPrompt()))
                val saved = container.chatRepository.getMessage(target.id, session)!!
                assertEquals(target.id, result.assistant.id)
                assertEquals(target.orderKey, saved.orderKey)
                assertEquals(target.sourceTurnId, saved.sourceTurnId)
                assertEquals(target.sourceTurnOrder, saved.sourceTurnOrder)
                assertEquals(target.timelineTurn, saved.timelineTurn)
                assertEquals(target.createdAt, saved.createdAt)
                assertEquals(target.images, saved.images)
                assertEquals(target.generatedImageMetadata, saved.generatedImageMetadata)
                assertNull(saved.formatRepairNotice)
                assertNull(saved.reasoningContent)
                assertEquals(index + 2, saved.alternatives.size)
                assertEquals("old-answer", saved.alternatives.first())
                assertEquals("new-$index", saved.displayContent)
                assertEquals(1, container.chatRepository.getMessages(session).count { it.role == MessageRole.USER })
                assertEquals(2, container.chatRepository.getMessages(session).count { it.role == MessageRole.ASSISTANT })
            }
            controller.refreshAfterTerminalTask(session)
            assertEquals("new-1", controller.state.value.messages.single { it.id == target.id }.displayContent)
            assertEquals(1, controller.state.value.messages.count { it.id == target.id })
        }
    }

    @Test fun `failure retains target and retry deletes only mapped system error`() = runBlocking {
        fixture { container, server, session, target ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("denied"))
            val failed = container.createRealChatRuntime().regenerate(session, target.id)
            assertNotNull(failed.failureMessage)
            assertEquals(target, container.chatRepository.getMessage(target.id, session))
            val error = container.chatRepository.getMessages(session).single { it.role == MessageRole.SYSTEM }
            assertTrue(error.content.startsWith("错误:"))
            container.primaryChatController.refresh()
            server.enqueue(success("retried"))
            container.createRealChatRuntime().regenerate(session, error.id)
            assertNull(container.chatRepository.getMessage(error.id, session))
            container.primaryChatController.refreshAfterTerminalTask(session)
            assertFalse(container.primaryChatController.state.value.messages.any { it.id == error.id })
            assertEquals(listOf("old-answer", "retried"), container.chatRepository.getMessage(target.id, session)!!.alternatives)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun `old direct-context reply is rejected without message mutation or request`() = runBlocking {
        fixture { container, server, session, target ->
            repeat(8) {
                container.chatRepository.addMessage(ChatMessage.create(session, MessageRole.USER, "user-$it"))
                container.chatRepository.addMessage(ChatMessage.create(session, MessageRole.ASSISTANT, "answer-$it"))
            }
            container.settingsRepository.updateAppSettings { it.copy(defaultContextWindowSize = 0) }
            val before = container.chatRepository.getMessages(session)
            val index = container.appDataRoot.resolve("entities/chat_message_indexes/$session.json")
            Files.deleteIfExists(index)
            assertFailsWith<IllegalStateException> { container.createRealChatRuntime().regenerate(session, target.id) }
            assertEquals(before, container.chatRepository.getMessagesReadOnly(session))
            assertFalse(Files.exists(index))
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun `stop partial regeneration appends exactly once and excludes overlapping send`() = runBlocking {
        fixture { container, _, session, target ->
            ControlledDesktopSseServer("""{"choices":[{"delta":{"content":"partial"}}]}""").use { stream ->
                container.modelRepository.saveModel(container.modelRepository.getModel("model")!!.copy(baseUrl = stream.baseUrl))
                val task = container.taskRuntime.launchRegeneration(session, target.id)
                withTimeout(10_000) { container.taskRuntime.tasks.first { list -> list.any { it.taskId == task && it.contentPreview == "partial" } } }
                assertFailsWith<DesktopTaskAdmissionException> { container.taskRuntime.launchChat(session, "no extra user") }
                assertFailsWith<DesktopTaskAdmissionException> { container.taskRuntime.launchRegeneration(session, target.id) }
                container.taskRuntime.requestUserStop(task)
                withTimeout(10_000) { container.taskRuntime.tasks.first { list -> list.any { it.taskId == task && it.status == DesktopTaskStatus.USER_STOPPED } } }
                assertEquals(listOf("old-answer", "partial"), container.chatRepository.getMessage(target.id, session)!!.alternatives)
                assertEquals(1, container.chatRepository.getMessages(session).count { it.role == MessageRole.USER })
            }
        }
    }

    @Test fun `stop before output keeps original without empty alternative`() = runBlocking {
        fixture { container, server, session, target ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val task = container.taskRuntime.launchRegeneration(session, target.id)
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(10, TimeUnit.SECONDS)) }
            container.taskRuntime.requestUserStop(task)
            withTimeout(10_000) { container.taskRuntime.tasks.first { list -> list.any { it.taskId == task && it.status == DesktopTaskStatus.USER_STOPPED } } }
            assertEquals(target, container.chatRepository.getMessage(target.id, session))
        }
    }

    @Test fun `regeneration planner retains current-turn roles positions tools placeholders and stable cache`() = runBlocking {
        fixture { container, _, session, target ->
            val format = FormatCard.create("Inline", "format for ${'$'}username").copy(userTools = listOf(
                FormatCardUserToolConfig(FormatCardUserToolType.RANDOM_NUMBER, minimum = "7", maximum = "7"),
                FormatCardUserToolConfig(FormatCardUserToolType.STRONG_PROMPT_SUFFIX, text = "fixture-suffix"),
            ))
            container.formatCardRepository.save(format)
            val user = container.chatRepository.getMessages(session).single { it.role == MessageRole.USER }
            FormatPromptPosition.entries.forEach { position ->
                val inputs = DesktopFakeChatInputs(effectiveContextWindowSize = 20,
                    globalPlayerName = "FixturePlayer", defaultFormatCardId = format.id, formatPromptPosition = position)
                val normal = container.chatRequestPlanner.planPersistedUser(session, user, inputs, true)
                val regen = container.chatRequestPlanner.planRegeneration(session, target.id, inputs)
                assertEquals(normal.assembly.messages, regen.assembly.messages)
                assertEquals(normal.assembly.messageTrace, regen.assembly.messageTrace)
                assertEquals(normal.assembly.promptCacheKey, regen.assembly.promptCacheKey)
                assertEquals(normal.assembly.stablePrefixMessages, regen.assembly.stablePrefixMessages)
                assertEquals(user.id, regen.currentUser.id)
                val trace = regen.assembly.messageTrace
                assertEquals(position != FormatPromptPosition.END,
                    trace.any { it.source == MainChatLogicalMessageSource.START_REQUIREMENTS })
                assertEquals(position != FormatPromptPosition.START,
                    trace.any { it.source == MainChatLogicalMessageSource.POST_HISTORY_END_REQUIREMENTS &&
                        it.message.content.toString().contains("format for FixturePlayer") })
                assertEquals("system", trace.single { it.source == MainChatLogicalMessageSource.STRONG_PROMPT_SUFFIX }.message.role)
                assertTrue(trace.any { it.message.content.toString().contains("FixturePlayer") })
            }
        }
    }

    @Test fun `greeting regeneration with no user omits current user and user tools`() = runBlocking {
        fixture { container, _, session, _ ->
            val cardId = container.chatRepository.getSession(session)!!.characterCardId
            val greetingSession = container.characterSessionService.createSessionForCharacter(cardId)
            val greeting = container.chatRepository.getMessages(greetingSession).single()
            val plan = container.chatRequestPlanner.planRegeneration(greetingSession, greeting.id,
                DesktopFakeChatInputs(effectiveContextWindowSize = 20))
            assertFalse(plan.currentUserPersisted)
            assertFalse(plan.assembly.messageTrace.any { it.source == MainChatLogicalMessageSource.CURRENT_USER ||
                it.source == MainChatLogicalMessageSource.STRONG_PROMPT_SUFFIX })
            assertFalse(plan.assembly.messages.any { it.content.toString().contains(greeting.content) })
            assertEquals(1, container.chatRepository.getMessages(greetingSession).size)
        }
    }

    @Test fun `regenerating an earlier active reply uses the latest contextual user as upstream does`() = runBlocking {
        fixture { container, _, session, target ->
            val current = container.chatRepository.addMessage(ChatMessage.create(session, MessageRole.USER, "latest-user"))
            val plan = container.chatRequestPlanner.planRegeneration(session, target.id,
                DesktopFakeChatInputs(effectiveContextWindowSize = 20))
            assertEquals(current.id, plan.currentUser.id)
            assertEquals("latest-user", plan.assembly.messageTrace.single {
                it.source == MainChatLogicalMessageSource.CURRENT_USER
            }.message.content.jsonPrimitive.content)
            assertFalse(plan.assembly.messages.any { it.content.toString().contains("old-answer") })
        }
    }

    @Test fun `an active send also excludes regeneration admission`() = runBlocking {
        fixture { container, server, session, target ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val task = container.taskRuntime.launchChat(session, "new-user")
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(10, TimeUnit.SECONDS)) }
            assertFailsWith<DesktopTaskAdmissionException> { container.taskRuntime.launchRegeneration(session, target.id) }
            container.taskRuntime.requestUserStop(task)
            withTimeout(8_000) { container.taskRuntime.tasks.first { tasks -> tasks.any { it.taskId == task && it.status == DesktopTaskStatus.USER_STOPPED } } }
            assertEquals(target, container.chatRepository.getMessage(target.id, session))
        }
    }

    @Test fun `reasoning-only user stop appends same-id interrupted version`() = runBlocking {
        fixture { container, _, session, target ->
            ControlledDesktopSseServer("""{"choices":[{"delta":{"reasoning_content":"partial-thought"}}]}""").use { stream ->
                container.modelRepository.saveModel(container.modelRepository.getModel("model")!!.copy(baseUrl = stream.baseUrl))
                val task = container.taskRuntime.launchRegeneration(session, target.id)
                withTimeout(8_000) { container.taskRuntime.tasks.first { tasks -> tasks.any { it.taskId == task && it.reasoningPreview == "partial-thought" } } }
                container.taskRuntime.requestUserStop(task)
                withTimeout(8_000) { container.taskRuntime.tasks.first { tasks -> tasks.any { it.taskId == task && it.status == DesktopTaskStatus.USER_STOPPED } } }
                val saved = container.chatRepository.getMessage(target.id, session)!!
                assertEquals(listOf("old-answer", ""), saved.alternatives)
                assertEquals("partial-thought", saved.reasoningContent)
                assertEquals(target.orderKey, saved.orderKey)
            }
        }
    }

    @Test fun `other session can send while regeneration runs and shutdown discards its partial draft`() = runBlocking {
        fixture { container, server, session, target ->
            ControlledDesktopSseServer("""{"choices":[{"delta":{"content":"not-stopped"}}]}""").use { stream ->
                val model = container.modelRepository.getModel("model")!!
                container.modelRepository.saveModel(model.copy(id = "other-model"))
                container.modelRepository.saveModel(model.copy(baseUrl = stream.baseUrl))
                val other = container.characterSessionService.createSessionForCharacter(container.chatRepository.getSession(session)!!.characterCardId)
                container.chatRepository.updateSession(container.chatRepository.getSession(other)!!.copy(modelId = "other-model"))
                val task = container.taskRuntime.launchRegeneration(session, target.id)
                withTimeout(8_000) { container.taskRuntime.tasks.first { tasks -> tasks.any { it.taskId == task && it.contentPreview == "not-stopped" } } }
                server.enqueue(success("independent"))
                val send = container.taskRuntime.launchChat(other, "other-user")
                withTimeout(8_000) { container.taskRuntime.tasks.first { tasks -> tasks.any { it.taskId == send && it.status == DesktopTaskStatus.COMPLETED } } }
                container.close()
                assertEquals(DesktopTaskStatus.CANCELLED, container.taskRuntime.tasks.value.single { it.taskId == task }.status)
                val persisted = com.example.chatbar.data.local.JsonFileStorage(container.appDataRoot)
                    .loadEntity("chat_messages", "${session}_${target.id}", ChatMessage.serializer())
                assertEquals(target, persisted)
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun `primary actions use shared target mapping and hide only running target`() {
        val user = ChatMessage.create("session", MessageRole.USER, "user")
        val assistant = ChatMessage.create("session", MessageRole.ASSISTANT, "reply")
        val error = ChatMessage.create("session", MessageRole.SYSTEM, "错误: failed")
        val messages = listOf(user, assistant, error)
        assertEquals(DesktopRegenerationAction.REGENERATE, desktopRegenerationAction(messages, assistant, null))
        assertEquals(DesktopRegenerationAction.RETRY, desktopRegenerationAction(messages, error, null))
        assertNull(desktopRegenerationAction(messages, user, null))
        val ordinary = ChatMessage.create("session", MessageRole.SYSTEM, "status")
        assertNull(desktopRegenerationAction(messages + ordinary, ordinary, null))
        val running = DesktopTaskEntry("task", DesktopTaskKind.REAL_CHAT, "session", 1,
            operation = DesktopChatOperation.REGENERATE, targetMessageId = assistant.id)
        assertNull(desktopRegenerationAction(messages, assistant, running))
        assertEquals(listOf(user, error), desktopVisibleMessages(messages, running))
        assertEquals(messages, desktopVisibleMessages(messages, null))
        assertEquals(listOf(DesktopMessageAction.COPY, DesktopMessageAction.EDIT,
            DesktopMessageAction.DELETE, DesktopMessageAction.REGENERATE),
            desktopMessageActions(messages, assistant, null))
        assertEquals(listOf(DesktopMessageAction.COPY, DesktopMessageAction.EDIT,
            DesktopMessageAction.DELETE, DesktopMessageAction.RETRY),
            desktopMessageActions(messages, error, null))
        assertEquals(listOf(DesktopMessageAction.COPY), desktopMessageActions(messages, assistant, running))
        assertEquals(listOf(DesktopMessageAction.COPY, DesktopMessageAction.REGENERATE),
            desktopFooterMessageActions(desktopMessageActions(messages, assistant, null)))
        assertEquals(listOf(DesktopMessageAction.COPY, DesktopMessageAction.RETRY),
            desktopFooterMessageActions(desktopMessageActions(messages, error, null)))
        assertEquals(listOf(DesktopMessageAction.EDIT, DesktopMessageAction.DELETE),
            desktopOverflowMessageActions(desktopMessageActions(messages, assistant, null)))
        assertTrue(desktopOverflowMessageActions(desktopMessageActions(messages, assistant, running)).isEmpty())
        val versions = assistant.copy(alternatives = listOf("one", "two", "three"), currentAlternativeIndex = 1)
        val navigation = desktopAlternativeNavigation(versions, setOf(versions.id))!!
        assertEquals(2, navigation.current)
        assertEquals(3, navigation.total)
        assertTrue(navigation.canPrevious && navigation.canNext)
        assertNull(desktopAlternativeNavigation(versions, emptySet()))
    }

    private suspend fun fixture(block: suspend (DesktopAppContainer, MockWebServer, String, ChatMessage) -> Unit) {
        val parent = Files.createTempDirectory("desktop-regeneration-")
        val container = DesktopAppContainer(
            DesktopDataRootResolution.Resolved(parent.resolve("data"), DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() },
        )
        try {
            MockWebServer().use { server ->
                container.modelRepository.saveModel(ModelConfig(id = "model", displayName = "Fake",
                    modelName = "fake", baseUrl = server.url("/v1").toString().trimEnd('/'), apiKey = "fake-key", createdAt = 1))
                container.settingsRepository.saveAppSettings(AppSettings(defaultModelId = "model",
                    allowCleartextModelApi = true, defaultContextWindowSize = 20, ragInjectionMode = "OFF"))
                val card = CharacterCard.create("Character", greeting = "Greeting")
                container.characterRepository.save(card)
                val session = container.characterSessionService.createSessionForCharacter(card.id)
                container.chatRepository.addMessage(ChatMessage.create(session, MessageRole.USER, "real-user"))
                val target = container.chatRepository.addMessage(ChatMessage.create(session, MessageRole.ASSISTANT, "old-answer",
                    images = listOf("fake-image.png"), reasoningContent = "old-reasoning").copy(
                    formatRepairNotice = MessageFormatRepairNotice(MessageFormatRepairNoticeKind.APPLIED, "old-answer")))
                block(container, server, session, target)
            }
        } finally {
            container.close()
            parent.toFile().deleteRecursively()
        }
    }

    private fun success(content: String) = MockResponse().addHeader("Content-Type", "text/event-stream")
        .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"$content\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
}
