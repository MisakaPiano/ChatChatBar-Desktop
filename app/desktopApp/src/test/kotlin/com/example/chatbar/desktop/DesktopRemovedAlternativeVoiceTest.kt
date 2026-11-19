package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.VoiceMessageRepository
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.chat.parseRoleplayTextSegments
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.*

class DesktopRemovedAlternativeVoiceTest {
    private suspend fun exercise(editLastSegment: Boolean) {
        val parent = Files.createTempDirectory("removed-alternative-voice-")
        val resolution = DesktopDataRootResolution.Resolved(Files.createDirectory(parent.resolve("data")),
            DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json"))
        val ownership = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(resolution)).ownership
        val app = DesktopAppContainer(resolution, secretStoreFactory = { InMemoryDesktopSecretStore() },
            bundledAssets = { """{"entries":[]}""".toByteArray() })
        try {
            val card = CharacterCard("card", "Synthetic voice deletion", createdAt = 1, updatedAt = 1)
            app.characterRepository.save(card)
            app.chatRepository.createSession(ChatSession("session", card.id, "Synthetic voice deletion", longTermMemoryEnabled = false,
                createdAt = 1, updatedAt = 1))
            val target = app.chatRepository.addMessage(ChatMessage("target", "session", MessageRole.ASSISTANT, "Deleted source text",
                alternatives = listOf("Deleted source text", "Retained source text"), alternativeVersionIds = listOf("deleted", "retained"),
                currentAlternativeIndex = 0, currentAlternativeVersionId = "deleted", createdAt = 1, updatedAt = 1, orderKey = 1))
            val separate = app.chatRepository.addMessage(ChatMessage("separate", "session", MessageRole.ASSISTANT, "Unrelated reply",
                createdAt = 2, updatedAt = 2, orderKey = 2))
            val deletedAnchor = app.voiceRepository.ensureAnchorsForVersion(target, "deleted", "Deleted source text").single()
            val retainedAnchor = app.voiceRepository.ensureAnchorsForVersion(target, "retained", "Retained source text").single()
            app.voiceRepository.ensureAnchorsForVersion(target, "historical", "Retained historical source")
            val unique = app.voiceAudioStore.persist("session", "deleted-owned", fishPreviewMp3().inputStream())
            val shared = app.voiceAudioStore.persist("session", "shared-owned", fishPreviewMp3().inputStream())
            fun voice(id: String, version: String, path: String) = GeneratedVoiceMessage(
                id, "session", target.id, version,
                if (version == "deleted") deletedAnchor.anchor.id else retainedAnchor.anchor.id,
                0, "NARRATION", "", if (version == "deleted") "Deleted source text" else "Retained source text",
                taggedText = "Synthetic tagged text", characterId = card.id, characterName = card.name,
                voice = FishAudioVoiceBinding("synthetic-reference", "Synthetic voice"), fishModelId = "synthetic-model",
                audioPath = path, durationMs = 1000, byteLength = Files.size(Path.of(path)), createdAt = 1, updatedAt = 1)
            app.voiceRepository.save(voice("deleted-unique", "deleted", unique.path).copy(messageVersionId = null))
            app.voiceRepository.save(voice("deleted-shared", "deleted", shared.path))
            app.voiceRepository.save(voice("retained-shared", "retained", shared.path))
            app.voiceRepository.save(voice("historical-shared", "historical", shared.path).copy(anchorId = null))
            val controller = app.primaryChatController
            controller.refresh(); controller.selectSession("session")
            controller.editComposer("Synthetic retained voice draft")
            controller.persistComposer(); controller.flushComposerForSnapshot()
            // Stale editor/confirmation data never authorizes voice or anchor deletion.
            val stale = target.copy(updatedAt = target.updatedAt + 1)
            if (editLastSegment) {
                val segment = parseRoleplayTextSegments(target.displayContent).single()
                assertFalse(controller.editMessageSegment(target.id, segment.start, segment.endExclusive, "", target.displayContent, stale))
            } else assertFalse(controller.deleteAssistantAlternative(stale, "deleted"))
            assertEquals(4, app.voiceRepository.listForMessage(target.id).size)
            assertTrue(Files.exists(Path.of(unique.path)))
            assertEquals(target, app.chatRepository.getMessage(target.id, target.sessionId))
            if (editLastSegment) {
                val segment = parseRoleplayTextSegments(target.displayContent).single()
                assertTrue(controller.editMessageSegment(target.id, segment.start, segment.endExclusive, "", target.displayContent, target))
            } else assertTrue(controller.deleteAssistantAlternative(target, "deleted"))
            val updated = assertNotNull(app.chatRepository.getMessage(target.id, target.sessionId))
            assertEquals(listOf("Retained source text"), updated.alternatives)
            assertEquals("retained", updated.currentAlternativeVersionId)
            val cold = VoiceMessageRepository(JsonFileStorage(app.appDataRoot), strictReads = true)
            assertEquals(setOf("retained-shared", "historical-shared"), cold.listForMessage(target.id).map { it.id }.toSet(),
                "Removed alternative retains durable voice metadata after ${if (editLastSegment) "last-segment empty edit" else "delete button"}")
            assertEquals(setOf("retained", "historical"), cold.snapshotAnchorsForMessages(setOf(target.id)).map { it.messageVersionId }.toSet())
            assertFalse(Files.exists(Path.of(unique.path)), "Unreferenced receipt-owned deleted-version audio must be released")
            assertTrue(Files.exists(Path.of(shared.path)), "A sibling version's shared recording must stay")
            assertEquals(separate, app.chatRepository.getMessage(separate.id, "session"))
            assertEquals("Synthetic retained voice draft", app.chatRepository.getSessionDraft("session"))

            // Use the actual Archive owner and inspect its portable voice record stream.
            app.saveSlotController.openFileContext("session")!!.join()
            app.saveSlotController.create("After version deletion", SaveSlotImagePolicy.NONE, includeAudio = true)
            withTimeout(15_000) { while (app.saveSlotController.state.value.busy) delay(20) }
            assertNull(app.saveSlotController.state.value.error)
            val summary = app.saveSlotController.state.value.slots.single()
            val slotPath = app.appDataRoot.resolve("entities/save_slots/${summary.id}.json")
            val slot = Json { ignoreUnknownKeys = true }.decodeFromString(SaveSlot.serializer(), Files.readString(slotPath))
            assertEquals(2, slot.packageRef!!.audioCount)
            ZipFile(app.appDataRoot.resolve("save_slot_packages/${slot.packageRef!!.fileName}").toFile()).use { zip ->
                val voices = zip.getInputStream(zip.getEntry("voices.jsonl")).bufferedReader().use { reader ->
                    reader.lineSequence().filter { it.isNotBlank() }.map { Json.decodeFromString(GeneratedVoiceMessage.serializer(), it) }.toList()
                }
                assertEquals(setOf("retained-shared", "historical-shared"), voices.map { it.id }.toSet())
                assertEquals(setOf("retained", "historical"), voices.map { it.messageVersionId }.toSet())
            }
        } finally { app.close(); ownership.close(); parent.toFile().deleteRecursively() }
    }

    @Test fun `last segment empty edit removes only the removed version voices and anchors through cold archive read`() = runBlocking {
        exercise(editLastSegment = true)
    }
    @Test fun `alternative delete button removes only the removed version voices and anchors through cold archive read`() = runBlocking {
        exercise(editLastSegment = false)
    }
}
