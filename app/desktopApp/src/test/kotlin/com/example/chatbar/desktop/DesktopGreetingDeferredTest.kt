package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

class DesktopGreetingDeferredTest {
    @Test fun `settings continuation cannot create after cancel or resurrect an identical reopened card request`() = runBlocking {
        for (reopen in listOf(false, true)) withApp { app, card, oldId ->
            val primary = app.primaryChatController
            val original = assertNotNull(primary.state.value.greetingSelection)
            primary.editSessionSettings { it.copy(supplementarySetting = "Unsaved settings") }
            assertNull(primary.confirmSessionGreeting()); assertTrue(primary.state.value.sessionSettingsLeavePrompt)
            primary.cancelSessionGreeting()
            if (reopen) {
                primary.discardSessionSettings(); primary.createSession(card.id)
                assertNotEquals(original.requestId, assertNotNull(primary.state.value.greetingSelection).requestId)
            }
            primary.resolveSessionSettingsLeave(save = false)
            assertEquals(listOf(oldId), app.chatRepository.getAllSessions().map { it.id })
            assertEquals(oldId, primary.state.value.selectedSession?.id)
            assertNull(primary.state.value.completedGreetingSessionId)
            assertEquals(reopen, primary.state.value.greetingSelection != null)
        }
    }

    @Test fun `changed choice invalidates deferred settings confirmation until a new explicit confirm`() = runBlocking {
        withApp { app, _, oldId ->
            val primary = app.primaryChatController
            var pending = assertNotNull(primary.state.value.greetingSelection)
            primary.selectSessionGreeting(pending.choices[1])
            primary.editSessionSettings { it.copy(supplementarySetting = "Unsent") }
            assertNull(primary.confirmSessionGreeting())
            pending = assertNotNull(primary.state.value.greetingSelection)
            primary.selectSessionGreeting(pending.choices[2])
            primary.resolveSessionSettingsLeave(save = false)
            assertEquals(listOf(oldId), app.chatRepository.getAllSessions().map { it.id })
            assertEquals("B exact  ", primary.state.value.greetingSelection!!.selected.text)
            val id = assertNotNull(primary.confirmSessionGreeting())
            assertEquals("B exact  ", app.chatRepository.getMessages(id).single().content)
            assertEquals(2, app.chatRepository.getAllSessions().size)
        }
    }

    @Test fun `normal deferred settings save creates once and publishes a consumable navigation completion`() = runBlocking {
        withApp { app, _, oldId ->
            val primary = app.primaryChatController
            primary.selectSessionGreeting(primary.state.value.greetingSelection!!.choices[1])
            primary.editSessionSettings { it.copy(supplementarySetting = "Saved existing settings") }
            assertNull(primary.confirmSessionGreeting())
            assertEquals(1, app.chatRepository.getAllSessions().size)
            primary.resolveSessionSettingsLeave(save = true)
            val id = assertNotNull(primary.state.value.completedGreetingSessionId)
            assertEquals("A", app.chatRepository.getMessages(id).single().content)
            assertEquals("Saved existing settings", app.chatRepository.getSession(oldId)!!.supplementarySetting)
            assertTrue(primary.consumeGreetingSessionCreated(id)); assertFalse(primary.consumeGreetingSessionCreated(id))
            primary.resolveSessionSettingsLeave(save = true)
            assertEquals(2, app.chatRepository.getAllSessions().size)
        }
    }

    @Test fun `real Memory continuation cannot create after cancelled or replaced greeting request`() = runBlocking {
        for (reopen in listOf(false, true)) withApp { app, card, oldId ->
            val primary = app.primaryChatController
            val original = primary.state.value.greetingSelection!!.requestId
            dirtyMemory(app, oldId)
            assertNull(primary.confirmSessionGreeting()); assertTrue(app.memoryController.state.value.leavePrompt)
            primary.cancelSessionGreeting()
            if (reopen) {
                app.memoryController.cancelEditing(); primary.createSession(card.id)
                assertNotEquals(original, primary.state.value.greetingSelection!!.requestId)
            }
            val statusBefore = primary.state.value.status
            app.memoryController.resolveLeave(discard = true)
            // Await the actual old continuation's visible refusal, not an arbitrary sleep.
            withTimeout(10_000) { primary.state.first { it.status != statusBefore } }
            assertEquals(listOf(oldId), app.chatRepository.getAllSessions().map { it.id })
            assertEquals(oldId, primary.state.value.selectedSession?.id)
            assertNull(primary.state.value.completedGreetingSessionId)
            assertEquals(reopen, primary.state.value.greetingSelection != null)
        }
    }

    @Test fun `normal real Memory resolution preserves selected opening and emits one completion`() = runBlocking {
        withApp { app, _, oldId ->
            val primary = app.primaryChatController
            primary.selectSessionGreeting(primary.state.value.greetingSelection!!.choices.last())
            dirtyMemory(app, oldId)
            assertNull(primary.confirmSessionGreeting()); assertTrue(app.memoryController.state.value.leavePrompt)
            app.memoryController.resolveLeave(discard = true)
            val completed = withTimeout(10_000) { primary.state.first { it.completedGreetingSessionId != null } }
            val id = assertNotNull(completed.completedGreetingSessionId)
            assertEquals("B exact  ", app.chatRepository.getMessages(id).single().content)
            assertEquals(2, app.chatRepository.getAllSessions().size)
            assertTrue(primary.consumeGreetingSessionCreated(id)); assertFalse(primary.consumeGreetingSessionCreated(id))
        }
    }

    private suspend fun dirtyMemory(app: DesktopAppContainer, id: String) {
        val memory = app.memoryController
        memory.open(id)
        withTimeout(10_000) { while (memory.state.value.memory == null || memory.state.value.busy || memory.state.value.operationBusy) delay(15) }
        memory.beginHeadEdit()
        memory.headDraft(assertNotNull(memory.state.value.headDraft).copy(location = "Unsaved synthetic location"))
        assertTrue(memory.state.value.dirty)
    }

    @Test fun `resumed Memory and settings continuations reserve submission through the real service boundary`() = runBlocking {
        for (memoryGuard in listOf(false, true)) withApp { app, card, oldId -> coroutineScope {
            val enteredService = CompletableDeferred<Unit>()
            val releaseService = CompletableDeferred<Unit>()
            val primary = DesktopPrimaryChatController(
                characters = app.characterRepository, chats = app.chatRepository,
                settings = app.settingsRepository, models = app.effectiveModelResolver,
                formats = app.formatCardRepository, worldBooks = app.worldBookRepository,
                sessionService = app.characterSessionService, characterResources = app.characterResourceStore,
                taskRuntime = app.taskRuntime, memory = app.memoryController,
                createCharacterSession = { id, choice ->
                    enteredService.complete(Unit)
                    releaseService.await()
                    app.characterSessionService.createSessionForCharacter(id, choice)
                },
            )
            try {
                primary.refresh(); primary.selectSession(oldId); primary.createSession(card.id)
                primary.selectSessionGreeting(primary.state.value.greetingSelection!!.choices.last())
                if (memoryGuard) dirtyMemory(app, oldId)
                else primary.editSessionSettings { it.copy(supplementarySetting = "Guard draft") }
                assertNull(primary.confirmSessionGreeting())
                assertFalse(primary.state.value.greetingSelection!!.submitting)
                val resumed = if (memoryGuard) {
                    app.memoryController.resolveLeave(discard = true)
                    null
                } else async { primary.resolveSessionSettingsLeave(save = false) }
                withTimeout(10_000) { enteredService.await() }
                val reserved = assertNotNull(primary.state.value.greetingSelection)
                assertTrue(reserved.submitting)
                assertNotNull(reserved.submissionId)
                assertEquals(1, app.chatRepository.getAllSessions().size)
                primary.cancelSessionGreeting()
                primary.selectSessionGreeting(reserved.choices.first())
                primary.createSession(card.id)
                assertEquals(reserved, primary.state.value.greetingSelection)
                releaseService.complete(Unit)
                resumed?.await()
                val done = withTimeout(10_000) { primary.state.first { it.completedGreetingSessionId != null } }
                val id = assertNotNull(done.completedGreetingSessionId)
                assertEquals("B exact  ", app.chatRepository.getMessages(id).single().content)
                assertEquals(2, app.chatRepository.getAllSessions().size)
                assertTrue(primary.consumeGreetingSessionCreated(id)); assertFalse(primary.consumeGreetingSessionCreated(id))
            } finally {
                releaseService.complete(Unit)
                primary.closeDraftPersistence()
            }
        } }
    }

    private suspend fun withApp(block: suspend (DesktopAppContainer, CharacterCard, String) -> Unit) {
        val parent = Files.createTempDirectory("greeting-deferred-")
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(parent.resolve("data"),
            DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { path ->
                check(path == "presets/manifest.json"); """{"entries":[]}""".toByteArray()
            })
        try {
            app.modelRepository.saveModel(ModelConfig(id = "synthetic", displayName = "Synthetic", modelName = "fixture",
                baseUrl = "http://127.0.0.1:12345/v1", apiKey = "synthetic-no-request", createdAt = 1))
            app.settingsRepository.saveAppSettings(AppSettings(defaultModelId = "synthetic", allowCleartextModelApi = true, ragInjectionMode = "OFF"))
            val card = CharacterCard.create("Synthetic", "Main").copy(alternateGreetings = listOf("A", "B exact  "))
            app.characterRepository.save(card)
            val oldId = app.characterSessionService.createSessionForCharacter(card.id)
            app.primaryChatController.refresh(); app.primaryChatController.selectSession(oldId)
            app.primaryChatController.createSession(card.id)
            assertNotNull(app.primaryChatController.state.value.greetingSelection)
            block(app, card, oldId)
        } finally { app.close(); check(parent.toFile().deleteRecursively()) }
    }
}
