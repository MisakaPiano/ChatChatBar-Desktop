package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.TimedEffectState
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.local.entity.WorldBookEntry
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.card.CharacterCardPackage
import com.example.chatbar.domain.card.PackagedCharacterCard
import com.example.chatbar.domain.chat.MainChatLogicalMessageTrace
import com.example.chatbar.domain.prompt.MainChatPromptAuthority
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopPhase5VerticalAcceptanceTest {
    @Test
    fun `imported character runs secure real chat then restarts and continues without another user`() {
        val parent = Files.createTempDirectory("desktop-phase5-vertical-")
        val root = Files.createDirectory(parent.resolve("app-data"))
        val secrets = InMemoryDesktopSecretStore()
        val fakeKey = "fake-phase5-vertical-secret"
        val requestCount = AtomicInteger()
        val requestBodies = mutableListOf<String>()
        val authorizationAtFirstRequest = AtomicReference<String?>()
        val firstContainer = AtomicReference<DesktopAppContainer?>()
        val sessionAtRequest = AtomicReference<String?>()
        val timedAtFirstRequest = AtomicReference<Map<String, TimedEffectState>?>(null)
        val rolesAtFirstRequest = AtomicReference<List<MessageRole>?>(null)

        try {
            MockWebServer().use { server ->
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val number = requestCount.incrementAndGet()
                        synchronized(requestBodies) { requestBodies += request.body.readUtf8() }
                        if (number == 1) {
                            authorizationAtFirstRequest.set(request.getHeader("Authorization"))
                            val container = requireNotNull(firstContainer.get())
                            val sessionId = requireNotNull(sessionAtRequest.get())
                            runBlocking {
                                timedAtFirstRequest.set(
                                    requireNotNull(container.chatRepository.getSession(sessionId)).timedWorldInfo,
                                )
                                rolesAtFirstRequest.set(container.chatRepository.getMessages(sessionId).map { it.role })
                            }
                        }
                        return when (number) {
                            1 -> sse(
                                """{"choices":[{"delta":{"reasoning_content":"Reason {{char}}"}}]}""",
                                """{"choices":[{"delta":{"content":"Alpha raw {{user}}"},"finish_reason":"stop"}]}""",
                            )
                            2 -> sse("""{"choices":[{"delta":{"content":"Second raw assistant"},"finish_reason":"stop"}]}""")
                            else -> error("Unexpected provider request $number")
                        }
                    }
                }

                var importedCharacterId = ""
                var sessionId = ""
                lateinit var firstRuntime: DesktopTaskRuntime
                withOwnedContainer(root, parent, secrets) { container ->
                    firstContainer.set(container)
                    val worldBook = WorldBook(
                        id = "vertical-book",
                        name = "Vertical lore",
                        entries = listOf(
                            WorldBookEntry(
                                id = "vertical-entry",
                                keys = listOf("trigger-lore"),
                                content = "vertical-worldbook-evidence",
                                sticky = 2,
                            ),
                        ),
                    )
                    val packageData = CharacterCardPackage(
                        card = PackagedCharacterCard(name = "Vertical Character", greeting = "Opening greeting"),
                        worldBooks = listOf(worldBook),
                    )
                    val packagePath = Files.writeString(
                        parent.resolve("vertical-character.json"),
                        container.transferJson.encodeToString(CharacterCardPackage.serializer(), packageData),
                    )
                    val transfer = container.createTypedTransferController()
                    transfer.importCharacter(packagePath)
                    assertEquals(null, transfer.state.value.error)
                    val imported = container.characterRepository.getAll().single()
                    importedCharacterId = imported.id
                    assertEquals("Vertical Character", imported.name)
                    assertEquals(1, imported.worldBookIds.size)
                    assertEquals("vertical-worldbook-evidence", container.worldBookRepository
                        .getById(imported.worldBookIds.single())?.entries?.single()?.content)

                    val model = ModelConfig(
                        id = "vertical-model",
                        displayName = "Vertical local model",
                        baseUrl = server.url("/v1").toString().trimEnd('/'),
                        apiKey = fakeKey,
                        modelName = "fake-local-model",
                        createdAt = 1,
                    )
                    container.modelRepository.saveModel(model)
                    container.settingsRepository.saveAppSettings(
                        AppSettings(
                            defaultModelId = model.id,
                            allowCleartextModelApi = true,
                            defaultContextWindowSize = 20,
                            ragInjectionMode = "OFF",
                        ),
                    )
                    assertEquals(fakeKey, container.modelRepository.getModel(model.id)?.apiKey)
                    assertNoFakeSecretInJson(root, fakeKey)

                    val alpha = container.alphaChatController
                    alpha.refresh()
                    alpha.createSession(imported.id)
                    sessionId = requireNotNull(alpha.state.value.selectedSessionId)
                    sessionAtRequest.set(sessionId)
                    val session = requireNotNull(container.chatRepository.getSession(sessionId))
                    assertEquals(imported.id, session.characterCardId)
                    assertEquals(listOf(MessageRole.ASSISTANT), alpha.state.value.messages.map { it.role })
                    assertEquals("Opening greeting", alpha.state.value.messages.single().content)

                    firstRuntime = container.taskRuntime
                    val taskId = requireNotNull(alpha.send("trigger-lore from user"))
                    val terminal = awaitCompleted(firstRuntime, taskId)
                    val persisted = container.chatRepository.getMessages(sessionId)
                    val user = persisted.single { it.role == MessageRole.USER }
                    val assistant = persisted.last()
                    val diagnostic = firstRuntime.diagnostics.entries.value.single { it.taskId == taskId }
                    val wireBody = synchronized(requestBodies) { requestBodies.single() }
                    val wireMessages = Json.parseToJsonElement(wireBody).jsonObject.getValue("messages").jsonArray
                    val wireContents = wireMessages.map { it.jsonObject.getValue("content").jsonPrimitive.content }

                    assertNotNull(terminal.startedAt)
                    assertNotNull(terminal.completedAt)
                    assertEquals(DesktopTaskStatus.COMPLETED, terminal.status)
                    assertEquals(listOf(MessageRole.ASSISTANT, MessageRole.USER), rolesAtFirstRequest.get())
                    assertTrue(requireNotNull(timedAtFirstRequest.get()).isNotEmpty())
                    assertEquals(timedAtFirstRequest.get(), requireNotNull(container.chatRepository.getSession(sessionId)).timedWorldInfo)
                    assertEquals(1, persisted.count { it.role == MessageRole.USER })
                    assertTrue(wireContents.any { "vertical-worldbook-evidence" in it })
                    assertEquals("Alpha raw {{user}}", assistant.content)
                    assertEquals("Reason {{char}}", assistant.reasoningContent)
                    assertEquals(user.sourceTurnId, assistant.sourceTurnId)
                    assertEquals(user.sourceTurnOrder, assistant.sourceTurnOrder)
                    assertEquals(wireBody, diagnostic.serializedRequestBody)
                    assertEquals("Bearer $fakeKey", authorizationAtFirstRequest.get())
                    assertEquals(DesktopTaskStatus.COMPLETED, diagnostic.status)
                    assertEquals("stop", diagnostic.finishReason)
                    assertTrue(diagnostic.chunks.any { it.reasoningPreview == "Reason {{char}}" })
                    assertTrue(diagnostic.chunks.any { it.contentPreview == "Alpha raw {{user}}" })
                    assertFalse(diagnostic.toString().contains(fakeKey))
                    assertFalse(diagnostic.toString().contains("Bearer"))
                    assertFalse(diagnostic.toString().contains("Authorization"))

                    val inspector = container.createPromptInspectorController()
                    inspector.refresh()
                    inspector.updateInputs {
                        it.copy(effectiveContextWindowSize = "20", formatPromptPosition = model.formatPromptPosition)
                    }
                    inspector.inspect()
                    val logical = assertIs<DesktopPromptInspectorStatus.Ready>(inspector.state.value.status).result
                    val directPlan = container.chatRequestPlanner.planPersistedUser(
                        sessionId = sessionId,
                        userMessageId = user.id,
                        inputs = DesktopFakeChatInputs(
                            effectiveContextWindowSize = 20,
                            formatPromptPosition = model.formatPromptPosition,
                        ),
                        readOnlyRepositoryAccess = true,
                    )
                    assertEquals(directPlan.assembly.messages, logical.logicalMessages.map(MainChatLogicalMessageTrace::message))
                    assertTrue(logical.logicalMessages.any {
                        "vertical-worldbook-evidence" in it.message.content.jsonPrimitive.content
                    })
                    val logicalRoles = logical.logicalMessages.map { it.message.role }
                    val wireRoles = wireMessages.map { it.jsonObject.getValue("role").jsonPrimitive.content }
                    assertEquals(logicalRoles.size, wireRoles.size)
                    assertTrue(logicalRoles.zip(wireRoles).any { (logicalRole, wireRole) ->
                        logicalRole == "system" && wireRole == "assistant"
                    })
                    assertNoFakeSecretInJson(root, fakeKey)
                }
                assertEquals(DesktopTaskStatus.COMPLETED, firstRuntime.tasks.value.single().status)
                assertEquals(DesktopDataOperationCoordinatorState.CLOSED,
                    requireNotNull(firstContainer.get()).dataOperationCoordinator.state)
                assertOwnershipAvailable(root, parent)
                firstContainer.set(null)

                withOwnedContainer(root, parent, secrets) { container ->
                    assertEquals(fakeKey, container.modelRepository.getModel("vertical-model")?.apiKey)
                    assertEquals(importedCharacterId, container.characterRepository.getAll().single().id)
                    assertTrue(container.taskRuntime.tasks.value.isEmpty())
                    assertTrue(container.taskRuntime.diagnostics.entries.value.isEmpty())
                    val alpha = container.alphaChatController
                    alpha.refresh()
                    alpha.selectSession(sessionId)
                    val before = container.chatRepository.getMessages(sessionId)
                    val userCountBefore = before.count { it.role == MessageRole.USER }
                    val priorAssistant = before.last()
                    assertEquals("Alpha raw {{user}}", priorAssistant.content)

                    val continueTask = requireNotNull(alpha.send(""))
                    assertEquals(DesktopTaskStatus.COMPLETED, awaitCompleted(container.taskRuntime, continueTask).status)
                    val after = container.chatRepository.getMessages(sessionId)
                    val secondBody = synchronized(requestBodies) { requestBodies[1] }
                    val secondContents = Json.parseToJsonElement(secondBody).jsonObject.getValue("messages").jsonArray
                        .map { it.jsonObject.getValue("content").jsonPrimitive.content }

                    assertEquals(1, userCountBefore)
                    assertEquals(userCountBefore, after.count { it.role == MessageRole.USER })
                    assertFalse(after.any {
                        it.role == MessageRole.USER && it.content == MainChatPromptAuthority.continueGenerationUserPrompt()
                    })
                    assertEquals(3, after.count { it.role == MessageRole.ASSISTANT })
                    assertEquals("Second raw assistant", after.last().content)
                    assertEquals(priorAssistant.sourceTurnId, after.last().sourceTurnId)
                    assertEquals(priorAssistant.sourceTurnOrder, after.last().sourceTurnOrder)
                    assertEquals(1, secondContents.count { it == MainChatPromptAuthority.continueGenerationUserPrompt() })
                    assertTrue(secondContents.any { "Alpha raw" in it })
                    assertEquals(secondBody, container.taskRuntime.diagnostics.entries.value.single().serializedRequestBody)
                    assertEquals(DesktopTaskStatus.COMPLETED, container.taskRuntime.diagnostics.entries.value.single().status)
                    assertFalse(container.taskRuntime.diagnostics.entries.value.single().toString().contains(fakeKey))
                    assertFalse(container.taskRuntime.diagnostics.entries.value.single().toString().contains("Bearer"))
                    assertNoFakeSecretInJson(root, fakeKey)
                }
                assertOwnershipAvailable(root, parent)
                assertEquals(2, requestCount.get())
            }
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    private fun withOwnedContainer(
        root: Path,
        parent: Path,
        secrets: InMemoryDesktopSecretStore,
        body: suspend (DesktopAppContainer) -> Unit,
    ) {
        val resolved = DesktopDataRootResolution.Resolved(
            appDataRoot = root,
            provenance = DesktopDataRootProvenance.CLI_OVERRIDE,
            bootstrapPath = parent.resolve("bootstrap.json"),
        )
        runDesktopApplicationWithDataRootOwnership(resolved) {
            val container = DesktopAppContainer(resolved, secretStoreFactory = { secrets })
            runDesktopApplicationLifecycle(
                initialize = {},
                applicationBody = { runBlocking { body(container) } },
                close = { container.close() },
            )
        }
    }

    private fun assertOwnershipAvailable(root: Path, parent: Path) {
        val result = DesktopDataRootOwnership.acquire(
            DesktopDataRootResolution.Resolved(
                appDataRoot = root,
                provenance = DesktopDataRootProvenance.CLI_OVERRIDE,
                bootstrapPath = parent.resolve("bootstrap.json"),
            ),
        )
        assertIs<DesktopDataRootOwnershipResult.Acquired>(result).ownership.close()
    }

    private fun assertNoFakeSecretInJson(root: Path, fakeKey: String) {
        Files.walk(root).use { paths ->
            val json = paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }
                .map(Files::readString)
                .toList()
            assertTrue(json.isNotEmpty())
            assertFalse(json.any { fakeKey in it })
        }
    }

    private suspend fun awaitCompleted(runtime: DesktopTaskRuntime, taskId: String): DesktopTaskEntry = withTimeout(10_000) {
        runtime.tasks.first { tasks -> tasks.any { it.taskId == taskId && it.status != DesktopTaskStatus.RUNNING } }
            .single { it.taskId == taskId }
    }

    private fun sse(vararg payloads: String): MockResponse = MockResponse()
        .addHeader("Content-Type", "text/event-stream")
        .setBody((payloads.toList() + "[DONE]").joinToString("") { "data: $it\n\n" })
}
