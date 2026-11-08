package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class DesktopRetainedPrimaryModelChoicesTest {
    @Test fun `retained chat observes model and default changes without resetting composer settings or message window`() = runBlocking {
        val root = Files.createTempDirectory("retained-primary-models-")
        val c = DesktopAppContainer(DesktopDataRootResolution.Resolved(root.resolve("data"),
            DesktopDataRootProvenance.CLI_OVERRIDE, root.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() })
        var observation: Job? = null
        try {
            withTimeout(15000) {
                val first = ModelConfig("first", "First", "https://fixture.invalid/v1", "synthetic-primary-key", "first", createdAt = 1)
                val second = first.copy(id = "second", displayName = "Second", modelName = "second")
                c.modelRepository.saveModel(first); c.modelRepository.saveModel(second)
                c.settingsRepository.updateAppSettings { it.copy(defaultModelId = "first") }
                c.characterRepository.save(CharacterCard.create("Fixture").copy(id = "card"))
                c.chatRepository.createSession(ChatSession(id = "session", characterCardId = "card", title = "Fixture", createdAt = 1, updatedAt = 1))
                val message = c.chatRepository.addMessage(ChatMessage.create("session", MessageRole.ASSISTANT, "Synthetic story"))
                val controller = c.primaryChatController
                controller.refresh(); controller.selectSession("session")
                controller.editComposer("Unsaved composer")
                controller.editSessionSettings { it.copy(modelId = "explicit-unsaved", title = "Unsaved settings") }
                val before = controller.state.value
                val persisted = c.chatRepository.getSession("session")
                observation = launch { controller.observeModelChoices("session") }
                c.modelRepository.saveModel(first.copy(displayName = "Renamed first"))
                controller.state.first { it.modelChoices.any { choice -> choice.id == "first" && choice.label.contains("Renamed first") } }
                c.settingsRepository.updateAppSettings { it.copy(defaultModelId = "second", defaultImageModelId = "second") }
                controller.state.first { it.modelDiagnostic?.effectiveId == "second" && it.effectiveModels.chat?.id == "second" }
                c.modelRepository.deleteModel("first")
                controller.state.first { it.modelChoices.none { choice -> choice.id == "first" } }
                c.modelRepository.deleteModel("second")
                val after = controller.state.first { it.modelChoices.none { choice -> choice.id == "second" } }
                assertEquals(before.composerDraft, after.composerDraft)
                assertEquals(before.sessionSettingsDraft, after.sessionSettingsDraft)
                assertEquals(before.sessionReplyLengthInput, after.sessionReplyLengthInput)
                assertTrue(after.sessionSettingsDirty)
                assertEquals(before.messages, after.messages)
                assertEquals(message.id, after.messages.single().id)
                assertEquals(before.messageWindowRevision, after.messageWindowRevision)
                assertEquals(before.readingPosition, after.readingPosition)
                assertEquals(persisted, c.chatRepository.getSession("session"))
                assertFalse(after.toString().contains("synthetic-primary-key"))
                assertFalse(after.modelChoicesReadFailed)
            }
        } finally {
            withContext(NonCancellable) { observation?.cancelAndJoin(); c.close() }
            root.toFile().deleteRecursively()
        }
    }
}
