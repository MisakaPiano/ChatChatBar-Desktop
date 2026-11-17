package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DesktopSessionGreetingTest {
    @Test fun `choosing and cancelling an alternate never writes a session card or existing composer`() = runBlocking {
        withApp { app ->
            val card = CharacterCard.create("Synthetic", "Main").copy(alternateGreetings = listOf("  ", " Alt\n exact  "))
            app.characterRepository.save(card)
            val oldId = app.characterSessionService.createSessionForCharacter(card.id)
            val controller = app.primaryChatController; controller.refresh(); controller.selectSession(oldId)
            controller.editComposer("Retained unsent draft")
            controller.createSession(card.id)
            val chooser = assertNotNull(controller.state.value.greetingSelection)
            assertEquals(listOf(0, 2), chooser.choices.map { it.position }); assertEquals(0, chooser.selected.position)
            controller.selectSessionGreeting(chooser.choices.last()); controller.cancelSessionGreeting()
            assertNull(controller.state.value.greetingSelection)
            assertEquals(listOf(oldId), app.chatRepository.getAllSessions().map { it.id })
            assertEquals("Retained unsent draft", controller.state.value.composerDraft)
            assertEquals(card, app.characterRepository.getById(card.id))
            assertEquals("Main", app.chatRepository.getMessages(oldId).single().content)
        }
    }

    @Test fun `confirming selected original opening creates one exact assistant message and leaves old history intact`() = runBlocking {
        withApp { app ->
            val card = CharacterCard.create("Synthetic", "Main").copy(alternateGreetings = listOf("", " Alt\n exact  "))
            app.characterRepository.save(card)
            val oldId = app.characterSessionService.createSessionForCharacter(card.id)
            val oldMessages = app.chatRepository.getMessages(oldId)
            val controller = app.primaryChatController; controller.refresh(); controller.selectSession(oldId)
            controller.createSession(card.id)
            val selected = assertNotNull(controller.state.value.greetingSelection).choices.last()
            controller.selectSessionGreeting(selected)
            val id = assertNotNull(controller.confirmSessionGreeting())
            assertEquals(2, app.chatRepository.getAllSessions().size)
            val opening = app.chatRepository.getMessages(id).single()
            assertEquals(MessageRole.ASSISTANT, opening.role); assertEquals(selected.text, opening.content)
            assertEquals(oldMessages, app.chatRepository.getMessages(oldId))
            assertEquals(card, app.characterRepository.getById(card.id)); assertNull(controller.state.value.greetingSelection)
        }
    }

    @Test fun `changed main and reordered selected alternate require fresh explicit choice before writing`() = runBlocking {
        withApp { app ->
            val card = CharacterCard.create("Synthetic", "Main").copy(alternateGreetings = listOf("A", "B"))
            app.characterRepository.save(card)
            val controller = app.primaryChatController; controller.refresh(); controller.createSession(card.id)
            app.characterRepository.save(card.copy(greeting = "New main"))
            assertNull(controller.confirmSessionGreeting())
            assertTrue(app.chatRepository.getAllSessions().isEmpty())
            assertNotNull(controller.state.value.error)
            var pending = assertNotNull(controller.state.value.greetingSelection)
            assertEquals("New main", pending.selected.text)
            controller.selectSessionGreeting(pending.choices.last())
            val changed = card.copy(greeting = "New main", alternateGreetings = listOf("B", "A"))
            app.characterRepository.save(changed)
            assertNull(controller.confirmSessionGreeting())
            assertTrue(app.chatRepository.getAllSessions().isEmpty())
            pending = assertNotNull(controller.state.value.greetingSelection)
            controller.selectSessionGreeting(pending.choices.last())
            val id = assertNotNull(controller.confirmSessionGreeting())
            assertEquals("A", app.chatRepository.getMessages(id).single().content)
            assertEquals(changed, app.characterRepository.getById(card.id))
        }
    }

    @Test fun `legacy card and blank-only alternatives retain direct main path while picker default stays main`() = runBlocking {
        withApp { app ->
            val card = CharacterCard.create("Legacy", "Main exact  ").copy(alternateGreetings = listOf("", "\n"))
            app.characterRepository.save(card)
            val controller = app.primaryChatController; controller.refresh(); controller.createSession(card.id)
            assertNull(controller.state.value.greetingSelection)
            val first = assertNotNull(controller.state.value.selectedSession)
            assertEquals(card.greeting, app.chatRepository.getMessages(first.id).single().content)
            app.characterRepository.save(card.copy(alternateGreetings = listOf("Alternate")))
            controller.createSession(card.id)
            assertEquals(1, app.chatRepository.getAllSessions().size)
            val second = assertNotNull(controller.confirmSessionGreeting())
            assertEquals(card.greeting, app.chatRepository.getMessages(second).single().content)
        }
    }

    private suspend fun withApp(block: suspend (DesktopAppContainer) -> Unit) {
        val parent = Files.createTempDirectory("greeting-flow-")
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(parent.resolve("data"),
            DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { path ->
                check(path == "presets/manifest.json"); """{"entries":[]}""".toByteArray()
            })
        try {
            app.modelRepository.saveModel(ModelConfig(id = "synthetic-model", displayName = "Synthetic", modelName = "fixture",
                baseUrl = "http://127.0.0.1:12345/v1", apiKey = "synthetic-no-request", createdAt = 1))
            app.settingsRepository.saveAppSettings(AppSettings(defaultModelId = "synthetic-model", allowCleartextModelApi = true, ragInjectionMode = "OFF"))
            block(app)
        } finally { app.close(); check(parent.toFile().deleteRecursively()) }
    }
}
