package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageFormatRepairNotice
import com.example.chatbar.data.local.entity.MessageFormatRepairNoticeKind
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.PlayerSetting
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.local.entity.WorldBookEntry
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import java.nio.file.Path
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopPrimaryChatControllerTest {
    @Test
    fun `new chat clears excluding search and selects one persisted session with one greeting`() = runBlocking {
        withContainer { container ->
            val character = CharacterCard.create("New arrival", greeting = "Hello once")
            container.characterRepository.save(character)
            configure(container)
            val controller = container.primaryChatController
            controller.refresh()
            controller.searchSessions("unrelated")
            assertTrue(controller.state.value.sessions.isEmpty())

            controller.createSession(character.id)

            val sessions = container.chatRepository.getAllSessions()
            assertEquals(1, sessions.size)
            val id = sessions.single().id
            assertEquals("", controller.state.value.sessionQuery)
            assertEquals(id, controller.state.value.selectedSession?.id)
            assertEquals(listOf(id), controller.state.value.sessions.map { it.id })
            assertEquals(listOf("Hello once"), container.chatRepository.getMessages(id).map { it.content })
            assertNull(controller.state.value.error)
        }
    }

    @Test
    fun `new chat clears matching search and selects created session`() = runBlocking {
        withContainer { container ->
            val character = CharacterCard.create("Matching title", greeting = "Greeting")
            container.characterRepository.save(character)
            configure(container)
            val controller = container.primaryChatController
            controller.refresh()
            controller.searchSessions("Matching")

            controller.createSession(character.id)

            val id = container.chatRepository.getAllSessions().single().id
            assertEquals("", controller.state.value.sessionQuery)
            assertEquals(id, controller.state.value.selectedSession?.id)
            assertEquals(listOf(id), controller.state.value.sessions.map { it.id })
            assertNull(controller.state.value.error)
        }
    }

    @Test
    fun `programmatic selection uses unfiltered sessions while search stays presentation only`() = runBlocking {
        withContainer { container ->
            val first = CharacterCard.create("First")
            val second = CharacterCard.create("Second")
            container.characterRepository.save(first)
            container.characterRepository.save(second)
            configure(container)
            val firstId = container.characterSessionService.createSessionForCharacter(first.id)
            val secondId = container.characterSessionService.createSessionForCharacter(second.id)
            val controller = container.primaryChatController
            controller.refresh()
            controller.searchSessions("First")
            assertEquals(listOf(firstId), controller.state.value.sessions.map { it.id })

            controller.selectSession(secondId)

            assertEquals(secondId, controller.state.value.selectedSession?.id)
            assertEquals("First", controller.state.value.sessionQuery)
            assertEquals(listOf(firstId), controller.state.value.sessions.map { it.id })
            assertNull(controller.state.value.error)
        }
    }

    @Test
    fun `older suspended search cannot replace newer result`() = runBlocking {
        withContainer { container ->
            val old = CharacterCard.create("Old result")
            val newer = CharacterCard.create("New result")
            container.characterRepository.save(old)
            container.characterRepository.save(newer)
            configure(container)
            container.characterSessionService.createSessionForCharacter(old.id)
            val newId = container.characterSessionService.createSessionForCharacter(newer.id)
            val oldStarted = CompletableDeferred<Unit>()
            val releaseOld = CompletableDeferred<Unit>()
            val controller = DesktopPrimaryChatController(
                characters = container.characterRepository,
                chats = container.chatRepository,
                settings = container.settingsRepository,
                models = container.effectiveModelResolver,
                formats = container.formatCardRepository,
                worldBooks = container.worldBookRepository,
                sessionService = container.characterSessionService,
                characterResources = container.characterResourceStore,
                taskRuntime = container.taskRuntime,
                sessionSearch = { query: String ->
                    if (query == "Old") {
                        oldStarted.complete(Unit)
                        releaseOld.await()
                    }
                    container.chatRepository.searchSessions(query)
                },
            )
            controller.refresh()
            val older = async(start = CoroutineStart.UNDISPATCHED) { controller.searchSessions("Old") }
            oldStarted.await()
            val latest = async(start = CoroutineStart.UNDISPATCHED) { controller.searchSessions("New") }
            releaseOld.complete(Unit)
            older.await()
            latest.await()

            assertEquals("New", controller.state.value.sessionQuery)
            assertEquals(listOf(newId), controller.state.value.sessions.map { it.id })
            controller.searchSessions("")
            assertEquals(container.chatRepository.getAllSessions().map { it.id },
                controller.state.value.sessions.map { it.id })
        }
    }

    @Test
    fun `session search matches title or display override in repository order without writes`() = runBlocking {
        withContainer { container ->
            val first = CharacterCard.create("Search One")
            val second = CharacterCard.create("Search Two")
            container.characterRepository.save(first)
            container.characterRepository.save(second)
            configure(container)
            val firstId = container.characterSessionService.createSessionForCharacter(first.id)
            val secondId = container.characterSessionService.createSessionForCharacter(second.id)
            container.chatRepository.updateSessionDisplayTitle(secondId, "Private alias")
            container.chatRepository.pinSession(firstId)
            val before = container.chatRepository.getAllSessions()
            val controller = container.primaryChatController
            controller.refresh()
            assertEquals(before.map { it.id }, controller.state.value.sessions.map { it.id })
            controller.searchSessions("Search One")
            assertEquals(listOf(firstId), controller.state.value.sessions.map { it.id })
            controller.searchSessions("Private alias")
            assertEquals(listOf(secondId), controller.state.value.sessions.map { it.id })
            controller.searchSessions("Search")
            assertEquals(before.map { it.id }, controller.state.value.sessions.map { it.id })
            assertTrue(controller.state.value.sessions.first().pinned)
            controller.searchSessions("no match")
            assertTrue(controller.state.value.sessions.isEmpty())
            controller.searchSessions("")
            assertEquals(before.map { it.id }, controller.state.value.sessions.map { it.id })
            assertEquals(before, container.chatRepository.getAllSessions())
        }
    }

    @Test
    fun `selected session diagnostic distinguishes follow default explicit and stale fallback`() = runBlocking {
        withContainer { container ->
            val character = CharacterCard.create("Diagnostic")
            container.characterRepository.save(character)
            configure(container)
            val id = container.characterSessionService.createSessionForCharacter(character.id)
            val controller = container.primaryChatController
            controller.refresh()
            val effectiveId = controller.state.value.modelDiagnostic?.effectiveId
            assertEquals(DesktopModelSelection.AUTOMATIC, controller.state.value.modelDiagnostic?.selection)
            assertEquals(DesktopCredentialSource.MODEL_KEY, controller.state.value.modelDiagnostic?.credentialSource)
            val session = container.chatRepository.getSession(id)!!
            container.chatRepository.updateSession(session.copy(modelId = effectiveId))
            controller.refresh()
            assertEquals(DesktopModelSelection.EXPLICIT, controller.state.value.modelDiagnostic?.selection)
            container.chatRepository.updateSession(session.copy(modelId = "missing-model"))
            controller.refresh()
            assertEquals(DesktopModelSelection.STALE_FALLBACK, controller.state.value.modelDiagnostic?.selection)
            assertEquals("missing-model", controller.state.value.modelDiagnostic?.configuredId)
            assertEquals(effectiveId, controller.state.value.modelDiagnostic?.effectiveId)
        }
    }

    @Test
    fun `fresh root construction and initial empty refresh write no business files`() = runBlocking {
        val parent = Files.createTempDirectory("primary-chat-empty-")
        val container = container(parent)
        try {
            val controller = container.primaryChatController
            assertFalse(Files.exists(container.appDataRoot))
            controller.refresh()
            val files = Files.walk(container.appDataRoot).use { it.filter(Files::isRegularFile).toList() }
            assertTrue(files.isEmpty(), "Unexpected business files: $files")
            assertNull(controller.state.value.selectedSession)
            assertFalse(controller.state.value.modelUsable)
            assertEquals("Select or create a session", controller.state.value.configurationMessage)
        } finally {
            container.close()
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `creation gates on default model and service persists greeting once`() = runBlocking {
        withContainer { container ->
            val character = CharacterCard.create("Ada", greeting = "Hello once")
            container.characterRepository.save(character)
            val controller = container.primaryChatController
            controller.refresh()
            controller.createSession(character.id)
            assertTrue(controller.state.value.sessions.isEmpty())
            assertFalse(controller.state.value.modelUsable)
            assertNotNull(controller.state.value.configurationMessage)

            configure(container)
            controller.createSession(character.id)
            val id = assertNotNull(controller.state.value.selectedSession?.id)
            assertEquals(character.id, controller.state.value.selectedSession?.characterCardId)
            assertEquals(listOf("Hello once"), container.chatRepository.getMessages(id).map(ChatMessage::content))
            controller.refresh()
            assertEquals(1, controller.state.value.messages.size)
            assertEquals(1, controller.state.value.totalMessageCount)
        }
    }

    @Test
    fun `failed default-model creation does not disable an existing usable explicit session`() = runBlocking {
        withContainer { container ->
            val character = CharacterCard.create("Explicit")
            container.characterRepository.save(character)
            configure(container)
            val id = container.characterSessionService.createSessionForCharacter(character.id)
            val session = assertNotNull(container.chatRepository.getSession(id))
            container.chatRepository.updateSession(session.copy(modelId = "model"))
            container.modelRepository.saveModel(
                ModelConfig(
                    id = "bad-default", displayName = "Unconfigured", modelName = "fake-model",
                    baseUrl = "https://example.invalid/v1", apiKey = "", createdAt = 2L,
                ),
            )
            container.settingsRepository.saveAppSettings(
                AppSettings(defaultModelId = "bad-default", allowCleartextModelApi = true, ragInjectionMode = "OFF"),
            )
            val controller = container.primaryChatController
            controller.refresh()
            assertTrue(controller.state.value.modelUsable)
            controller.createSession(character.id)
            assertTrue(controller.state.value.modelUsable)
            assertEquals(id, controller.state.value.selectedSession?.id)
            assertEquals(1, controller.state.value.sessions.size)
            assertNotNull(controller.state.value.error)
        }
    }

    @Test
    fun `selection reads bounded latest page and older pages prepend without duplication`() = runBlocking {
        withContainer { container ->
            val character = CharacterCard.create("Paged", greeting = "Greeting")
            container.characterRepository.save(character)
            configure(container)
            val id = container.characterSessionService.createSessionForCharacter(character.id)
            repeat(90) { index ->
                container.chatRepository.addMessage(ChatMessage.create(id, MessageRole.USER, "message-$index"))
            }
            val controller = container.primaryChatController
            controller.refresh()
            val initial = controller.state.value
            assertTrue(initial.hasOlderMessages)
            assertEquals(91, initial.totalMessageCount)
            assertTrue(initial.messages.size < initial.totalMessageCount)
            assertEquals("message-89", initial.messages.last().content)
            val initialIds = initial.messages.map(ChatMessage::id).toSet()

            controller.loadOlder()
            val loaded = controller.state.value.messages
            assertTrue(loaded.size > initial.messages.size)
            assertEquals(loaded.size, loaded.map(ChatMessage::id).toSet().size)
            assertTrue(loaded.map(ChatMessage::id).containsAll(initialIds))
            assertEquals(loaded.sortedWith(ChatMessage.TimelineComparator), loaded)
            assertEquals("Greeting", loaded.first().content)
            assertFalse(controller.state.value.hasOlderMessages)
        }
    }

    @Test
    fun `pin title and missing character keep authoritative session and readable history`() = runBlocking {
        withContainer { container ->
            val first = CharacterCard.create("First", greeting = "First greeting")
            val second = CharacterCard.create("Second", greeting = "Second greeting")
            container.characterRepository.save(first)
            container.characterRepository.save(second)
            configure(container)
            val firstId = container.characterSessionService.createSessionForCharacter(first.id)
            val secondId = container.characterSessionService.createSessionForCharacter(second.id)
            val controller = container.primaryChatController
            controller.refresh()
            controller.togglePin(firstId)
            assertEquals(firstId, controller.state.value.sessions.first().id)
            assertTrue(container.chatRepository.getSession(firstId)?.isPinned == true)
            controller.setDisplayTitle(firstId, "Personal title")
            assertEquals("Personal title", controller.state.value.sessions.first().title)
            assertEquals("First", container.characterRepository.getById(first.id)?.name)
            controller.setDisplayTitle(firstId, "   ")
            assertEquals("First", controller.state.value.sessions.first().title)
            controller.togglePin(firstId)
            assertFalse(container.chatRepository.getSession(firstId)?.isPinned == true)
            assertTrue(controller.state.value.sessions.any { it.id == secondId })

            container.characterRepository.delete(first.id)
            controller.selectSession(firstId)
            assertTrue(controller.state.value.selectedCharacterMissing)
            assertEquals("First greeting", controller.state.value.messages.single().displayContent)
            controller.editComposer("must not send")
            assertNull(controller.send())
            assertTrue(container.taskRuntime.tasks.value.isEmpty())
            assertEquals(1, container.chatRepository.getMessages(firstId).size)
        }
    }

    @Test
    fun `settings merge concurrent fields and leave stale references until explicit edit`() = runBlocking {
        withContainer { container ->
            val character = CharacterCard.create("Settings")
            container.characterRepository.save(character)
            configure(container)
            val id = container.characterSessionService.createSessionForCharacter(character.id)
            val original = assertNotNull(container.chatRepository.getSession(id))
            container.chatRepository.updateSession(original.copy(modelId = "missing-model", formatCardId = "missing-format"))
            val controller = container.primaryChatController
            controller.refresh()
            assertEquals("missing-model", controller.state.value.sessionSettingsDraft?.modelId)
            assertEquals("missing-format", controller.state.value.sessionSettingsDraft?.formatCardId)
            controller.editSessionSettings { it.copy(replyLanguage = "Japanese", playerName = "Player") }
            assertNull(container.chatRepository.getSession(id)?.replyLanguage)
            assertNull(container.chatRepository.getSession(id)?.playerName)
            val concurrent = assertNotNull(container.chatRepository.getSession(id))
            container.chatRepository.updateSession(concurrent.copy(roleplayStyle = "concurrent", contextWindowSize = 77))
            controller.refreshAfterTerminalTask(id)
            controller.saveSessionSettings()
            val saved = assertNotNull(container.chatRepository.getSession(id))
            assertEquals("Japanese", saved.replyLanguage)
            assertEquals("Player", saved.playerName)
            assertEquals("concurrent", saved.roleplayStyle)
            assertEquals(77, saved.contextWindowSize)
            assertEquals("missing-model", saved.modelId)
            assertEquals("missing-format", saved.formatCardId)
        }
    }

    @Test
    fun `session settings draft requires explicit save or discard before leaving`() = runBlocking {
        withContainer { container ->
            val character = CharacterCard.create("Draft guard")
            container.characterRepository.save(character)
            configure(container)
            val id = container.characterSessionService.createSessionForCharacter(character.id)
            val controller = container.primaryChatController
            controller.refresh()
            assertFalse(controller.state.value.sessionSettingsDirty)
            controller.editSessionSettings { it.copy(replyLanguage = "Japanese") }
            assertTrue(controller.state.value.sessionSettingsDirty)
            var left = false
            controller.requestSessionSettingsLeave { left = true }
            assertFalse(left)
            assertTrue(controller.state.value.sessionSettingsLeavePrompt)
            controller.continueSessionSettingsEditing()
            assertFalse(controller.state.value.sessionSettingsLeavePrompt)
            controller.requestSessionSettingsLeave { left = true }
            controller.resolveSessionSettingsLeave(save = false)
            assertTrue(left)
            assertNull(container.chatRepository.getSession(id)?.replyLanguage)
            assertFalse(controller.state.value.sessionSettingsDirty)
            controller.editSessionSettings { it.copy(replyLanguage = "English") }
            controller.requestSessionSettingsLeave { left = true }
            controller.resolveSessionSettingsLeave(save = true)
            assertEquals("English", container.chatRepository.getSession(id)?.replyLanguage)
            assertFalse(controller.state.value.sessionSettingsDirty)
            val savedLength = container.chatRepository.getSession(id)?.replyLength
            controller.editSessionReplyLengthInput("invalid")
            assertTrue(controller.state.value.sessionSettingsDirty)
            controller.saveSessionSettings()
            assertEquals("Reply length must be positive", controller.state.value.error)
            assertEquals(savedLength, container.chatRepository.getSession(id)?.replyLength)
            controller.discardSessionSettings()
            assertFalse(controller.state.value.sessionSettingsDirty)
            val other = CharacterCard.create("Other session")
            container.characterRepository.save(other)
            val otherId = container.characterSessionService.createSessionForCharacter(other.id)
            controller.editSessionSettings { it.copy(playerName = "Unsaved") }
            controller.selectSession(otherId)
            assertTrue(controller.state.value.sessionSettingsLeavePrompt)
            assertEquals(id, controller.state.value.selectedSession?.id)
            controller.resolveSessionSettingsLeave(save = false)
            assertEquals(otherId, controller.state.value.selectedSession?.id)
        }
    }

    @Test
    fun `per-session drafts survive switching and container restart`() = runBlocking {
        val parent = Files.createTempDirectory("primary-chat-draft-")
        var container = container(parent)
        try {
            val first = CharacterCard.create("A")
            val second = CharacterCard.create("B")
            container.characterRepository.save(first)
            container.characterRepository.save(second)
            configure(container)
            val a = container.characterSessionService.createSessionForCharacter(first.id)
            val b = container.characterSessionService.createSessionForCharacter(second.id)
            val controller = container.primaryChatController
            controller.refresh()
            controller.selectSession(a)
            controller.editComposer("draft-A")
            val earlierSave = async { controller.persistComposer() }
            controller.editComposer("draft-A-latest")
            val latestSave = async { controller.persistComposer() }
            earlierSave.await()
            latestSave.await()
            controller.selectSession(b)
            controller.editComposer("draft-B")
            controller.persistComposer()
            controller.selectSession(a)
            assertEquals("draft-A-latest", controller.state.value.composerDraft)
            controller.selectSession(b)
            assertEquals("draft-B", controller.state.value.composerDraft)
            container.close()

            container = container(parent)
            val reopened = container.primaryChatController
            reopened.refresh()
            reopened.selectSession(a)
            assertEquals("draft-A-latest", reopened.state.value.composerDraft)
            reopened.selectSession(b)
            assertEquals("draft-B", reopened.state.value.composerDraft)
        } finally {
            container.close()
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `full and inline presentation edits use durable per-session controller draft`() = runBlocking {
        withContainer { container ->
            val character = CharacterCard.create("Composer fixture")
            container.characterRepository.save(character)
            configure(container)
            val a = container.characterSessionService.createSessionForCharacter(character.id)
            val b = container.characterSessionService.createSessionForCharacter(character.id)
            val controller = container.primaryChatController
            controller.refresh()
            controller.selectSession(a)
            val composer = DesktopComposerInput(a, controller.state.value.composerDraft)
            composer.edit(false, androidx.compose.ui.text.input.TextFieldValue("inline draft"), controller::editComposer)
            composer.open(true)
            composer.edit(true, androidx.compose.ui.text.input.TextFieldValue("full 中文\n日本語 draft"), controller::editComposer)
            composer.close()
            assertEquals("full 中文\n日本語 draft", controller.state.value.composerDraft)
            controller.persistComposer()
            assertEquals(composer.input.text, container.chatRepository.getSessionDraft(a))
            controller.selectSession(b)
            assertEquals("", controller.state.value.composerDraft)
            controller.selectSession(a)
            assertEquals(composer.input.text, controller.state.value.composerDraft)
        }
    }

    @Test
    fun `accepted send clears draft while rejected same-session admission preserves it`() = runBlocking {
        withContainer { container ->
            MockWebServer().use { server ->
                val id = prepare(container, server)
                server.enqueue(success("reply"))
                val controller = container.primaryChatController
                controller.refresh()
                controller.editComposer("first user")
                controller.persistComposer()
                val taskId = assertNotNull(controller.send())
                assertEquals("", controller.state.value.composerDraft)
                assertEquals("", container.chatRepository.getSessionDraft(id))
                awaitTask(container, taskId) { it.status != DesktopTaskStatus.RUNNING }

                server.enqueue(MockResponse().setBodyDelay(3, java.util.concurrent.TimeUnit.SECONDS).setBody("data: [DONE]\n\n"))
                val blocking = assertNotNull(controller.continueReply())
                controller.editComposer("keep after rejection")
                controller.persistComposer()
                assertNull(controller.send())
                assertEquals("keep after rejection", controller.state.value.composerDraft)
                assertEquals("keep after rejection", container.chatRepository.getSessionDraft(id))
                controller.stop(blocking)
                awaitTask(container, blocking) { it.status != DesktopTaskStatus.RUNNING }
            }
        }
    }

    @Test
    fun `continue creates no user entity and terminal refresh shows one persisted assistant`() = runBlocking {
        withContainer { container ->
            MockWebServer().use { server ->
                val id = prepare(container, server)
                val controller = container.primaryChatController
                controller.refresh()
                server.enqueue(success("continued"))
                val taskId = assertNotNull(controller.continueReply())
                awaitTask(container, taskId) { it.status == DesktopTaskStatus.COMPLETED }
                controller.refreshAfterTerminalTask(id)
                controller.refreshAfterTerminalTask(id)
                assertEquals(0, container.chatRepository.getMessages(id).count { it.role == MessageRole.USER })
                assertEquals(1, controller.state.value.messages.count { it.content == "continued" })
                assertEquals(controller.state.value.messages.size, controller.state.value.messages.map(ChatMessage::id).toSet().size)
            }
        }
    }

    @Test
    fun `session and route switching retain independent running previews and user stop persistence`() = runBlocking {
        withContainer { container ->
            HoldingPrimarySseServer("partial-A").use { serverA ->
                HoldingPrimarySseServer("partial-B").use { serverB ->
                    val characterA = CharacterCard.create("A", greeting = "Hello A")
                    val characterB = CharacterCard.create("B", greeting = "Hello B")
                    container.characterRepository.save(characterA)
                    container.characterRepository.save(characterB)
                    configure(container, serverA.baseUrl)
                    container.modelRepository.saveModel(
                        ModelConfig(
                            id = "model-B", displayName = "Model B", modelName = "fake-model",
                            baseUrl = serverB.baseUrl, apiKey = "fake-primary-key-B", createdAt = 2L,
                        ),
                    )
                    val sessionA = container.characterSessionService.createSessionForCharacter(characterA.id)
                    val sessionB = container.characterSessionService.createSessionForCharacter(characterB.id)
                    val sessionBEntity = assertNotNull(container.chatRepository.getSession(sessionB))
                    container.chatRepository.updateSession(sessionBEntity.copy(modelId = "model-B"))
                    val controller = container.primaryChatController
                    controller.refresh()
                    controller.selectSession(sessionA)
                    controller.editComposer("user A")
                    val taskA = assertNotNull(controller.send())
                    awaitTask(container, taskA) { it.contentPreview == "partial-A" }

                    controller.selectSession(sessionB)
                    assertEquals(DesktopTaskStatus.RUNNING, container.taskRuntime.tasks.value.single { it.taskId == taskA }.status)
                    controller.editComposer("user B")
                    val taskB = assertNotNull(controller.send())
                    awaitTask(container, taskB) { it.contentPreview == "partial-B" }
                    controller.selectSession(sessionA)
                    assertEquals("partial-A", container.taskRuntime.tasks.value.single { it.taskId == taskA }.contentPreview)
                    assertEquals(DesktopTaskStatus.RUNNING, container.taskRuntime.tasks.value.single { it.taskId == taskB }.status)

                    val rootState = DesktopDataRootSwitchState.Idle(
                        currentRoot = container.appDataRoot,
                        provenance = DesktopDataRootProvenance.CLI_OVERRIDE,
                        supported = true,
                    )
                    val navigation = DesktopPrimaryNavigationController()
                    DesktopPrimaryRoute.entries.forEach { route ->
                        assertTrue(navigation.navigate(route, rootState))
                        assertEquals(DesktopTaskStatus.RUNNING, container.taskRuntime.tasks.value.single { it.taskId == taskA }.status)
                    }
                    DesktopShellSize.entries.forEach { size ->
                        assertNotNull(size)
                        assertEquals(DesktopTaskStatus.RUNNING, container.taskRuntime.tasks.value.single { it.taskId == taskB }.status)
                    }

                    assertTrue(controller.stop(taskA))
                    assertTrue(controller.stop(taskB))
                    awaitTask(container, taskA) { it.status == DesktopTaskStatus.USER_STOPPED }
                    awaitTask(container, taskB) { it.status == DesktopTaskStatus.USER_STOPPED }
                    controller.selectSession(sessionB)
                    controller.refreshAfterTerminalTask(sessionA)
                    assertEquals(sessionB, controller.state.value.selectedSession?.id)
                    assertTrue(controller.state.value.sessions.any { it.id == sessionA && it.lastMessagePreview?.contains("partial-A") == true })
                    controller.selectSession(sessionA)
                    assertEquals(1, controller.state.value.messages.count { it.content == "partial-A" })
                    assertEquals(1, container.chatRepository.getMessages(sessionA).count { it.role == MessageRole.USER })
                    assertEquals(1, container.chatRepository.getMessages(sessionB).count { it.content == "partial-B" })
                }
            }
        }
    }

    @Test
    fun `assistant alternatives navigate within bounds and selected version survives restart`() = runBlocking {
        val parent = Files.createTempDirectory("primary-chat-alternatives-")
        var container = container(parent)
        try {
            val card = CharacterCard.create("Alternatives")
            container.characterRepository.save(card)
            configure(container)
            val sessionId = container.characterSessionService.createSessionForCharacter(card.id)
            val message = container.chatRepository.addMessage(
                ChatMessage.create(sessionId, MessageRole.ASSISTANT, "two")
                    .copy(alternatives = listOf("one", "two"), currentAlternativeIndex = 1),
            )
            val originalRowCount = container.chatRepository.getMessages(sessionId).size
            val controller = container.primaryChatController
            controller.refresh()
            controller.selectAssistantAlternative(message.id, -1)
            assertEquals(0, container.chatRepository.getMessage(message.id, sessionId)?.currentAlternativeIndex)
            assertEquals("one", controller.state.value.messages.single { it.id == message.id }.displayContent)
            controller.selectAssistantAlternative(message.id, -1)
            assertEquals(0, container.chatRepository.getMessage(message.id, sessionId)?.currentAlternativeIndex)
            controller.selectAssistantAlternative(message.id, 1)
            controller.selectAssistantAlternative(message.id, 1)
            assertEquals(1, container.chatRepository.getMessage(message.id, sessionId)?.currentAlternativeIndex)
            assertEquals("two", container.chatRepository.getSession(sessionId)?.lastMessagePreview)
            assertEquals(originalRowCount, container.chatRepository.getMessages(sessionId).size)
            container.close()
            container = container(parent)
            container.primaryChatController.refresh()
            assertEquals(1, container.primaryChatController.state.value.messages.single { it.id == message.id }.currentAlternativeIndex)
            assertEquals("two", container.primaryChatController.state.value.messages.single { it.id == message.id }.displayContent)
        } finally {
            container.close()
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `loaded older alternative outside active context is hidden and cannot switch`() = runBlocking {
        withContainer { container ->
            val card = CharacterCard.create("Older alternatives")
            container.characterRepository.save(card)
            configure(container)
            val app = container.settingsRepository.getAppSettings()
            container.settingsRepository.saveAppSettings(app.copy(defaultContextWindowSize = 1))
            val sessionId = container.characterSessionService.createSessionForCharacter(card.id)
            val older = container.chatRepository.addMessage(
                ChatMessage.create(sessionId, MessageRole.ASSISTANT, "old two")
                    .copy(alternatives = listOf("old one", "old two"), currentAlternativeIndex = 1),
            )
            repeat(85) { index ->
                container.chatRepository.addMessage(ChatMessage.create(sessionId, MessageRole.USER, "turn $index"))
            }
            val recent = container.chatRepository.addMessage(
                ChatMessage.create(sessionId, MessageRole.ASSISTANT, "new two")
                    .copy(alternatives = listOf("new one", "new two"), currentAlternativeIndex = 1),
            )
            val controller = container.primaryChatController
            controller.refresh()
            assertTrue(controller.state.value.hasOlderMessages)
            controller.loadOlder()
            assertTrue(controller.state.value.messages.any { it.id == older.id })
            assertFalse(older.id in controller.state.value.alternativeEligibleIds)
            assertTrue(recent.id in controller.state.value.alternativeEligibleIds)
            controller.selectAssistantAlternative(older.id, -1)
            assertEquals(1, container.chatRepository.getMessage(older.id, sessionId)?.currentAlternativeIndex)
            controller.selectAssistantAlternative(recent.id, -1)
            assertEquals(0, container.chatRepository.getMessage(recent.id, sessionId)?.currentAlternativeIndex)
        }
    }

    @Test
    fun `active same-session task rejects edit and delete but another session remains independent`() = runBlocking {
        assertTrue(desktopMessageMutationActionsAvailable(null))
        assertFalse(desktopMessageMutationActionsAvailable(DesktopTaskEntry(
            taskId = "running", kind = DesktopTaskKind.REAL_CHAT, sessionId = "session", createdAt = 0L,
        )))
        withContainer { container ->
            HoldingPrimarySseServer("working").use { server ->
                val card = CharacterCard.create("Active")
                container.characterRepository.save(card)
                configure(container, server.baseUrl)
                val firstId = container.characterSessionService.createSessionForCharacter(card.id)
                val first = container.chatRepository.addMessage(
                    ChatMessage.create(firstId, MessageRole.USER, "keep me"),
                )
                val otherId = container.characterSessionService.createSessionForCharacter(card.id)
                val other = container.chatRepository.addMessage(
                    ChatMessage.create(otherId, MessageRole.USER, "other"),
                )
                val controller = container.primaryChatController
                controller.refresh()
                controller.selectSession(firstId)
                val segmentMessage = container.chatRepository.addMessage(ChatMessage.create(firstId,
                    MessageRole.ASSISTANT, "[Protected]()"))
                controller.editComposer("new turn")
                val taskId = assertNotNull(controller.send())
                awaitTask(container, taskId) { it.contentPreview == "working" }
                assertFalse(controller.editMessage(first.id, "changed"))
                assertFalse(controller.deleteMessage(first.id))
                assertFalse(controller.editMessageSegment(segmentMessage.id, 0, segmentMessage.displayContent.length, "changed"))
                assertEquals(segmentMessage.content, container.chatRepository.getMessage(segmentMessage.id, firstId)?.content)
                assertEquals("keep me", container.chatRepository.getMessage(first.id, firstId)?.content)
                controller.selectSession(otherId)
                assertTrue(controller.editMessage(other.id, "changed other"))
                assertEquals("changed other", container.chatRepository.getMessage(other.id, otherId)?.content)
                assertTrue(controller.stop(taskId))
                awaitTask(container, taskId) { it.status == DesktopTaskStatus.USER_STOPPED }
            }
        }
    }

    @Test
    fun `idle SYSTEM error row supports whole-message edit and delete without changing role`() = runBlocking {
        withContainer { container ->
            val card = CharacterCard.create("Error row")
            container.characterRepository.save(card)
            configure(container)
            val sessionId = container.characterSessionService.createSessionForCharacter(card.id)
            val system = container.chatRepository.addMessage(
                ChatMessage.create(sessionId, MessageRole.SYSTEM, "Error: old"),
            )
            val controller = container.primaryChatController
            controller.refresh()
            assertTrue(controller.editMessage(system.id, "Error: corrected"))
            assertEquals(MessageRole.SYSTEM, container.chatRepository.getMessage(system.id, sessionId)?.role)
            assertEquals("Error: corrected", container.chatRepository.getMessage(system.id, sessionId)?.content)
            assertTrue(controller.deleteMessage(system.id))
            assertNull(container.chatRepository.getMessage(system.id, sessionId))
        }
    }

    @Test
    fun `whole message edit preserves identity order and source turn while delete refreshes preview`() = runBlocking {
        withContainer { container ->
            val card = CharacterCard.create("Actions")
            container.characterRepository.save(card)
            configure(container)
            val sessionId = container.characterSessionService.createSessionForCharacter(card.id)
            val first = container.chatRepository.addMessage(ChatMessage.create(sessionId, MessageRole.USER, "first"))
            val second = container.chatRepository.addMessage(
                ChatMessage.create(sessionId, MessageRole.ASSISTANT, "second")
                    .copy(alternatives = listOf("old", "second"), currentAlternativeIndex = 1),
            )
            val controller = container.primaryChatController
            controller.refresh()
            val beforeOrder = controller.state.value.messages.map { it.id }
            assertTrue(controller.editMessage(second.id, "edited"))
            val edited = assertNotNull(container.chatRepository.getMessage(second.id, sessionId))
            assertEquals(second.id, edited.id)
            assertEquals(second.orderKey, edited.orderKey)
            assertEquals(second.sourceTurnId, edited.sourceTurnId)
            assertEquals(second.sourceTurnOrder, edited.sourceTurnOrder)
            assertTrue(edited.alternatives.isEmpty())
            assertEquals("edited", edited.displayContent)
            assertEquals("edited", container.chatRepository.getSession(sessionId)?.lastMessagePreview)
            assertEquals(beforeOrder, controller.state.value.messages.map { it.id })
            assertTrue(controller.deleteMessage(second.id))
            assertEquals(beforeOrder.filterNot { it == second.id }, controller.state.value.messages.map { it.id })
            assertEquals("first", container.chatRepository.getSession(sessionId)?.lastMessagePreview)
            val turnStillPresent = container.chatRepository.getMessages(sessionId).any {
                it.sourceTurnId == second.sourceTurnId && it.role != MessageRole.SYSTEM
            }
            val tombstoned = container.chatRepository.getSession(sessionId)?.sourceTurnTombstones.orEmpty().any {
                it.sourceTurnId == second.sourceTurnId
            }
            assertEquals(!turnStillPresent, tombstoned)
        }
    }

    @Test
    fun `world book draft keeps inherited and stale IDs until explicit save and planner sees extra`() = runBlocking {
        withContainer { container ->
            val inherited = WorldBook("inherited", "Inherited")
            val extra = WorldBook("extra", "Extra", entries = listOf(
                WorldBookEntry(id = "entry", keys = listOf("trigger"), content = "Extra lore"),
            ))
            container.worldBookRepository.save(inherited)
            container.worldBookRepository.save(extra)
            val card = CharacterCard.create("World").copy(worldBookIds = listOf(inherited.id, "missing-inherited"))
            container.characterRepository.save(card)
            configure(container)
            val sessionId = container.characterSessionService.createSessionForCharacter(card.id)
            val original = assertNotNull(container.chatRepository.getSession(sessionId))
            container.chatRepository.updateSession(original.copy(extraWorldBookIds = listOf("missing-extra")))
            val controller = container.primaryChatController
            controller.refresh()
            assertEquals(card.worldBookIds, controller.state.value.selectedCharacter?.worldBookIds)
            assertEquals(listOf("missing-extra"), controller.state.value.sessionSettingsDraft?.extraWorldBookIds)
            assertEquals(listOf("missing-extra"), container.chatRepository.getSession(sessionId)?.extraWorldBookIds)
            assertEquals(setOf("inherited", "extra"), controller.state.value.worldBookChoices.map { it.id }.toSet())
            controller.editSessionSettings { it.copy(extraWorldBookIds = it.extraWorldBookIds + extra.id) }
            controller.saveSessionSettings()
            val saved = assertNotNull(container.chatRepository.getSession(sessionId))
            assertEquals(listOf("missing-extra", extra.id), saved.extraWorldBookIds)
            container.chatRepository.addMessage(ChatMessage.create(sessionId, MessageRole.USER, "trigger"))
            assertEquals("Extra lore", container.worldBookRequestPlanner.plan(card, saved).prompt)
        }
    }

    @Test
    fun `archived session relink keeps session history settings and send eligibility`() = runBlocking {
        withContainer { container ->
            val originalCard = CharacterCard.create("Lost")
            val replacement = CharacterCard.create("Recovered")
            container.characterRepository.save(originalCard)
            container.characterRepository.save(replacement)
            configure(container)
            val sessionId = container.characterSessionService.createSessionForCharacter(originalCard.id)
            val original = assertNotNull(container.chatRepository.getSession(sessionId))
            container.chatRepository.updateSession(original.copy(
                replyLanguage = "Japanese", longTermMemory = "owned memory", extraWorldBookIds = listOf("stale"),
            ))
            val message = container.chatRepository.addMessage(ChatMessage.create(sessionId, MessageRole.USER, "history"))
            container.characterRepository.delete(originalCard.id)
            val controller = container.primaryChatController
            controller.refresh()
            assertTrue(controller.state.value.selectedCharacterMissing)
            assertTrue(controller.state.value.messages.any { it.id == message.id })
            assertTrue(controller.relinkArchivedSession(replacement.id))
            val saved = assertNotNull(container.chatRepository.getSession(sessionId))
            assertEquals(sessionId, saved.id)
            assertEquals(replacement.id, saved.characterCardId)
            assertEquals("Japanese", saved.replyLanguage)
            assertEquals("owned memory", saved.longTermMemory)
            assertEquals(listOf("stale"), saved.extraWorldBookIds)
            assertTrue(container.chatRepository.getMessages(sessionId).any { it.id == message.id })
            assertFalse(controller.state.value.selectedCharacterMissing)
            assertTrue(controller.state.value.modelUsable)
            assertNull(controller.state.value.error)
        }
    }

    @Test
    fun `browser renders title and preview placeholders without changing persisted text or search`() = runBlocking {
        withContainer { container ->
            val card = CharacterCard.create("Card").copy(botName = "Bot")
            container.characterRepository.save(card)
            configure(container)
            container.settingsRepository.savePlayerSetting(PlayerSetting(playerName = "Global"))
            val sessionId = container.characterSessionService.createSessionForCharacter(card.id)
            val original = assertNotNull(container.chatRepository.getSession(sessionId))
            container.chatRepository.updateSession(original.copy(lastMessagePreview = null))
            container.primaryChatController.refresh()
            assertEquals("开始全新对话…", container.primaryChatController.state.value.sessions.single().lastMessagePreview)
            assertNull(container.chatRepository.getSession(sessionId)?.lastMessagePreview)
            container.chatRepository.updateSession(original.copy(
                title = "$" + "botname and $" + "username",
                lastMessagePreview = "From $" + "username to $" + "botname",
            ))
            val controller = container.primaryChatController
            controller.refresh()
            assertEquals("Bot and Global", controller.state.value.sessions.single().title)
            assertEquals("From Global to Bot", controller.state.value.sessions.single().lastMessagePreview)
            controller.searchSessions("$" + "botname")
            assertEquals(1, controller.state.value.sessions.size)
            assertEquals(original.id, controller.state.value.selectedSession?.id)
            val after = assertNotNull(container.chatRepository.getSession(sessionId))
            assertEquals("$" + "botname and $" + "username", after.title)
            assertEquals("From $" + "username to $" + "botname", after.lastMessagePreview)
            container.chatRepository.updateSession(after.copy(playerName = "Local", displayTitleOverride = "Room for $" + "username"))
            controller.refresh()
            assertEquals("Room for Local", controller.state.value.sessions.single().title)
            assertEquals("From Local to Bot", controller.state.value.sessions.single().lastMessagePreview)
        }
    }

    @Test
    fun `relinked archived session sends a controlled continuation on same session`() = runBlocking {
        withContainer { container ->
            MockWebServer().use { server ->
                val lost = CharacterCard.create("Lost")
                val replacement = CharacterCard.create("Replacement")
                container.characterRepository.save(lost)
                container.characterRepository.save(replacement)
                configure(container, server.url("/v1").toString().trimEnd('/'))
                val sessionId = container.characterSessionService.createSessionForCharacter(lost.id)
                val original = assertNotNull(container.chatRepository.getSession(sessionId))
                container.chatRepository.updateSession(original.copy(replyLanguage = "Japanese"))
                val history = container.chatRepository.addMessage(
                    ChatMessage.create(sessionId, MessageRole.USER, "prior history"),
                )
                container.characterRepository.delete(lost.id)
                val controller = container.primaryChatController
                controller.refresh()
                assertTrue(controller.state.value.selectedCharacterMissing)
                assertTrue(controller.relinkArchivedSession(replacement.id))
                server.enqueue(success("continued reply"))
                controller.editComposer("new input")
                val taskId = assertNotNull(controller.send())
                awaitTask(container, taskId) { it.status == DesktopTaskStatus.COMPLETED }
                val saved = assertNotNull(container.chatRepository.getSession(sessionId))
                assertEquals(sessionId, saved.id)
                assertEquals("Japanese", saved.replyLanguage)
                val messages = container.chatRepository.getMessages(sessionId)
                assertTrue(messages.any { it.id == history.id })
                assertTrue(messages.any { it.role == MessageRole.USER && it.content == "new input" })
                assertTrue(messages.any { it.role == MessageRole.ASSISTANT && it.content == "continued reply" })
            }
        }
    }

    @Test fun `segment edit delete and final deletion use shared outcomes and preserve message identity`() = runBlocking {
        withContainer { container ->
            val card = CharacterCard.create("Segments")
            container.characterRepository.save(card)
            configure(container)
            val id = container.characterSessionService.createSessionForCharacter(card.id)
            val message = container.chatRepository.addMessage(ChatMessage.create(id, MessageRole.ASSISTANT,
                "Narration<n=\"Alice\"/>[Hello]()『Thought』").copy(formatRepairNotice =
                MessageFormatRepairNotice(MessageFormatRepairNoticeKind.APPLIED, "old")))
            val controller = container.primaryChatController
            controller.refresh(); controller.selectSession(id)
            val segment = com.example.chatbar.domain.chat.parseRoleplayTextSegments(message.displayContent)[1]
            assertTrue(controller.editMessageSegment(message.id, segment.start, segment.endExclusive, "[Changed]()", message.displayContent))
            val edited = assertNotNull(container.chatRepository.getMessage(message.id, id))
            val expected = com.example.chatbar.domain.chat.editRoleplayMessageSegment(message, segment.start, segment.endExclusive, "[Changed]()").message!!
            assertEquals(expected.displayContent, edited.displayContent)
            assertEquals(message.id, edited.id)
            assertEquals(message.orderKey, edited.orderKey)
            assertEquals(message.sourceTurnId, edited.sourceTurnId)
            assertEquals(message.sourceTurnOrder, edited.sourceTurnOrder)
            assertNull(edited.formatRepairNotice)
            assertFalse(controller.editMessageSegment(message.id, segment.start, segment.endExclusive, "stale", message.displayContent))
            val next = com.example.chatbar.domain.chat.parseRoleplayTextSegments(edited.displayContent)[1]
            assertTrue(controller.editMessageSegment(message.id, next.start, next.endExclusive, ""))
            assertEquals(com.example.chatbar.domain.chat.editRoleplayMessageSegment(edited, next.start, next.endExclusive, "").message?.displayContent,
                container.chatRepository.getMessage(message.id, id)?.displayContent)
            val final = container.chatRepository.addMessage(ChatMessage.create(id, MessageRole.ASSISTANT, "Only text"))
            assertTrue(controller.editMessageSegment(final.id, 0, final.displayContent.length, ""))
            assertNull(container.chatRepository.getMessage(final.id, id))
            assertFalse(controller.state.value.messages.any { it.id == final.id })
            val image = container.chatRepository.addMessage(ChatMessage.create(id, MessageRole.ASSISTANT, "Caption").copy(images = listOf("images/test.png")))
            assertTrue(controller.editMessageSegment(image.id, 0, image.displayContent.length, ""))
            val retained = assertNotNull(container.chatRepository.getMessage(image.id, id))
            assertEquals(image.images, retained.images)
            assertTrue(retained.displayContent.isBlank())
        }
    }

    @Test fun `session rows expose Character avatar and tolerate absent and archived characters`() = runBlocking {
        withContainer { container ->
            val withAvatar = CharacterCard.create("Avatar").copy(avatar = "images/avatar.png")
            val noAvatar = CharacterCard.create("No avatar")
            container.characterRepository.save(withAvatar); container.characterRepository.save(noAvatar)
            val first = container.characterSessionService.createSessionForCharacter(withAvatar.id)
            val second = container.characterSessionService.createSessionForCharacter(noAvatar.id)
            val archived = container.chatRepository.createSession(ChatSession.create("missing", "Archived"))
            val controller = container.primaryChatController
            controller.refresh()
            assertEquals("images/avatar.png", controller.state.value.sessions.first { it.id == first }.avatarReference)
            assertNull(controller.state.value.sessions.first { it.id == second }.avatarReference)
            val missing = controller.state.value.sessions.first { it.id == archived.id }
            assertNull(missing.avatarReference); assertTrue(missing.characterMissing)
            assertEquals("Archived", missing.title)
        }
    }

    private suspend fun prepare(container: DesktopAppContainer, server: MockWebServer): String {
        val character = CharacterCard.create("Network test", greeting = "Greeting")
        container.characterRepository.save(character)
        configure(container, server.url("/v1").toString().trimEnd('/'))
        return container.characterSessionService.createSessionForCharacter(character.id)
    }

    private suspend fun configure(container: DesktopAppContainer, baseUrl: String = "http://127.0.0.1:12345/v1") {
        val model = ModelConfig(
            id = "model", displayName = "Model", modelName = "fake-model", baseUrl = baseUrl,
            apiKey = "fake-primary-key", createdAt = 1L,
        )
        container.modelRepository.saveModel(model)
        container.settingsRepository.saveAppSettings(
            AppSettings(defaultModelId = model.id, allowCleartextModelApi = true, ragInjectionMode = "OFF"),
        )
    }

    private suspend fun awaitTask(
        container: DesktopAppContainer,
        id: String,
        predicate: (DesktopTaskEntry) -> Boolean,
    ): DesktopTaskEntry = withTimeout(7_000) {
        container.taskRuntime.tasks.first { entries -> entries.any { it.taskId == id && predicate(it) } }
            .single { it.taskId == id }
    }

    private fun success(content: String) = MockResponse()
        .addHeader("Content-Type", "text/event-stream")
        .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"$content\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")

    private suspend fun withContainer(block: suspend (DesktopAppContainer) -> Unit) {
        val parent = Files.createTempDirectory("primary-chat-controller-")
        val container = container(parent)
        try { block(container) } finally {
            container.close()
            parent.toFile().deleteRecursively()
        }
    }

    private fun container(parent: Path) = DesktopAppContainer(
        resolvedRoot = DesktopDataRootResolution.Resolved(
            appDataRoot = parent.resolve("app-data"),
            provenance = DesktopDataRootProvenance.CLI_OVERRIDE,
            bootstrapPath = parent.resolve("bootstrap.json"),
        ),
        secretStoreFactory = { InMemoryDesktopSecretStore() },
    )
}

private class HoldingPrimarySseServer(content: String) : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val socket = AtomicReference<Socket?>()
    private val release = CountDownLatch(1)
    val baseUrl = "http://127.0.0.1:${server.localPort}/v1"
    private val worker = thread(name = "holding-primary-chat-sse", isDaemon = true) {
        runCatching {
            server.accept().use { connection ->
                socket.set(connection)
                val reader = connection.getInputStream().bufferedReader(Charsets.UTF_8)
                while (!reader.readLine().isNullOrEmpty()) Unit
                connection.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                    write("data: {\"choices\":[{\"delta\":{\"content\":\"$content\"}}]}\n\n".toByteArray())
                    flush()
                }
                release.await(10, TimeUnit.SECONDS)
            }
        }
    }

    override fun close() {
        release.countDown()
        socket.getAndSet(null)?.close()
        server.close()
        worker.join(1_000)
    }
}
