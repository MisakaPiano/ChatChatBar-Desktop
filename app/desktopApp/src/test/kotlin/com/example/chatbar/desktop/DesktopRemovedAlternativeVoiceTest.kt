package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.VoiceMessageRepository
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.data.operation.AppDataOperationGate
import com.example.chatbar.desktop.backup.AppBackupSelection
import com.example.chatbar.desktop.backup.BackupSessionChoice
import com.example.chatbar.desktop.backup.DesktopBackupRestoreMode
import com.example.chatbar.desktop.backup.MergeRootSyntheticProtector
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy
import com.example.chatbar.domain.chat.parseRoleplayTextSegments
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class DesktopRemovedAlternativeVoiceTest {
    private suspend fun exercise(editLastSegment: Boolean, cancelAfterCommit: Boolean = false,
        ambiguousLegacy: Boolean = false, summaryFailure: Boolean = false,
        preMigratedLegacy: Boolean = false, restoreBeforeDelete: Boolean = false) {
        val parent = Files.createTempDirectory("removed-alternative-voice-")
        val resolution = DesktopDataRootResolution.Resolved(Files.createDirectory(parent.resolve("data")),
            DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json"))
        val ownership = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(resolution)).ownership
        val app = DesktopAppContainer(resolution, secretStoreFactory = { InMemoryDesktopSecretStore() },
            bundledAssets = { """{"entries":[]}""".toByteArray() })
        var customController: DesktopPrimaryChatController? = null
        try {
            val card = CharacterCard("card", "Synthetic voice deletion", createdAt = 1, updatedAt = 1)
            app.characterRepository.save(card)
            app.chatRepository.createSession(ChatSession("session", card.id, "Synthetic voice deletion", longTermMemoryEnabled = false,
                createdAt = 1, updatedAt = 1))
            val deletedText = "Deleted source text" + if (ambiguousLegacy) "\nAlice：『Shared line』" else ""
            val retainedText = "Retained source text" + if (ambiguousLegacy) "\nAlice：『Shared line』" else ""
            val target = app.chatRepository.addMessage(ChatMessage("target", "session", MessageRole.ASSISTANT, deletedText,
                alternatives = listOf(deletedText, retainedText), alternativeVersionIds = listOf("deleted", "retained"),
                currentAlternativeIndex = 0, currentAlternativeVersionId = "deleted", createdAt = 1, updatedAt = 1, orderKey = if (summaryFailure) 3 else 1))
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
                audioPath = path, durationMs = 1000, byteLength = Files.size(Path.of(path)), createdAt = 1, updatedAt = 1,
                desktopVersionOwnershipUncertain = false)
            app.voiceRepository.save(voice("deleted-unique", "deleted", unique.path).copy(messageVersionId = if (preMigratedLegacy) "deleted" else null,
                desktopVersionOwnershipUncertain = null,
                anchorId = if (ambiguousLegacy) null else deletedAnchor.anchor.id,
                sourceText = if (ambiguousLegacy) "Shared line" else "Deleted source text",
                sourceSegmentKind = if (ambiguousLegacy) "DIALOGUE" else "NARRATION"))
            app.voiceRepository.save(voice("deleted-shared", "deleted", shared.path))
            app.voiceRepository.save(voice("retained-shared", "retained", shared.path))
            app.voiceRepository.save(voice("historical-shared", "historical", shared.path).copy(anchorId = null))
            val explicitUnique = if (ambiguousLegacy) app.voiceAudioStore.persist("session", "explicit-deleted-owned", fishPreviewMp3().inputStream()) else null
            if (explicitUnique != null) {
                app.voiceRepository.save(voice("deleted-explicit-unique", "deleted", explicitUnique.path))
                if (restoreBeforeDelete) app.voiceRepository.restoreForSession("session", app.voiceRepository.listForSession("session"), listOf(target, separate))
                // Ordinary playback migrates before confirmation. Cold read must retain uncertainty.
                app.voiceRepository.placementsForMessage(target)
                val coldLegacy = VoiceMessageRepository(JsonFileStorage(app.appDataRoot), strictReads = true).get("deleted-unique")
                assertEquals("deleted", assertNotNull(coldLegacy).messageVersionId)
            }
            val cancellationArmed = AtomicBoolean()
            val cancellationEntered = AtomicBoolean()
            val cancellationTailContinued = AtomicBoolean()
            var mutationJob: Job? = null
            val summaryArmed = AtomicBoolean()
            val summaryEntered = AtomicBoolean()
            val observingGate = object : AppDataOperationGate {
                override suspend fun <T> withNormalOperation(operation: suspend () -> T): T = app.dataOperationCoordinator.withNormalOperation {
                    val result = operation()
                    if (summaryArmed.get() && !summaryEntered.get()) {
                        val file = app.appDataRoot.resolve("entities/chat_sessions/session.json")
                        val durable = runCatching { Json.decodeFromString(ChatSession.serializer(), Files.readString(file)) }.getOrNull()
                        if (durable?.lastMessagePreview?.startsWith("Retained source text") == true && summaryEntered.compareAndSet(false, true)) {
                            throw java.io.IOException("Synthetic session summary publication failure after durable source")
                        }
                    }
                    if (cancellationArmed.get() && !cancellationEntered.get()) {
                        val file = app.appDataRoot.resolve("entities/chat_messages/session_target.json")
                        val durable = runCatching { Json.decodeFromString(ChatMessage.serializer(), Files.readString(file)) }.getOrNull()
                        if (durable?.alternativeVersionIds == listOf("retained") && cancellationEntered.compareAndSet(false, true)) {
                            // A real storage operation has completed the message write, before CAS returns.
                            mutationJob!!.cancel(CancellationException("Synthetic caller cancellation after source write"))
                            yield()
                            cancellationTailContinued.set(true)
                        }
                    }
                    result
                }
            }
            val controller = if (!cancelAfterCommit && !summaryFailure) app.primaryChatController else DesktopPrimaryChatController(
                characters = app.characterRepository, chats = ChatRepository(JsonFileStorage(app.appDataRoot, observingGate)),
                settings = app.settingsRepository, models = app.effectiveModelResolver,
                formats = app.formatCardRepository, worldBooks = app.worldBookRepository,
                sessionService = app.characterSessionService, characterResources = app.characterResourceStore,
                taskRuntime = app.taskRuntime, voices = app.chatVoiceRuntime,
            ).also { customController = it }
            controller.refresh(); controller.selectSession("session")
            controller.editComposer("Synthetic retained voice draft")
            controller.persistComposer(); controller.flushComposerForSnapshot()
            // Stale editor/confirmation data never authorizes voice or anchor deletion.
            val stale = target.copy(updatedAt = target.updatedAt + 1)
            if (editLastSegment) {
                val segment = parseRoleplayTextSegments(target.displayContent).single()
                assertFalse(controller.editMessageSegment(target.id, segment.start, segment.endExclusive, "", target.displayContent, stale))
            } else assertFalse(controller.deleteAssistantAlternative(stale, "deleted"))
            assertEquals(if (ambiguousLegacy) 5 else 4, app.voiceRepository.listForMessage(target.id).size)
            assertTrue(Files.exists(Path.of(unique.path)))
            assertEquals(target, app.chatRepository.getMessage(target.id, target.sessionId))
            suspend fun mutate(): Boolean = if (editLastSegment) {
                val segment = parseRoleplayTextSegments(target.displayContent).single()
                controller.editMessageSegment(target.id, segment.start, segment.endExclusive, "", target.displayContent, target)
            } else controller.deleteAssistantAlternative(target, "deleted")
            summaryArmed.set(summaryFailure)
            if (!cancelAfterCommit) assertTrue(mutate()) else coroutineScope {
                cancellationArmed.set(true)
                mutationJob = launch(start = CoroutineStart.LAZY) { mutate() }
                mutationJob!!.start()
                withTimeout(10_000) { mutationJob!!.join() }
                assertTrue(mutationJob!!.isCancelled)
                assertTrue(cancellationEntered.get(), "Cancellation must occur after the actual durable replacement")
                assertTrue(cancellationTailContinued.get(), "The bounded commit tail must survive caller cancellation")
                cancellationArmed.set(false)
            }
            val updated = assertNotNull(ChatRepository(JsonFileStorage(app.appDataRoot)).getMessage(target.id, target.sessionId))
            assertEquals(listOf(retainedText), updated.alternatives)
            assertEquals("retained", updated.currentAlternativeVersionId)
            val cold = VoiceMessageRepository(JsonFileStorage(app.appDataRoot), strictReads = true)
            val expectedVoices = setOf("retained-shared", "historical-shared") + if (ambiguousLegacy) setOf("deleted-unique") else emptySet()
            assertEquals(expectedVoices, cold.listForMessage(target.id).map { it.id }.toSet(),
                "Removed alternative retains durable voice metadata after ${if (editLastSegment) "last-segment empty edit" else "delete button"}")
            assertEquals(setOf("retained", "historical") + if (ambiguousLegacy) setOf("deleted") else emptySet(), cold.snapshotAnchorsForMessages(setOf(target.id)).map { it.messageVersionId }.toSet())
            assertEquals(ambiguousLegacy, Files.exists(Path.of(unique.path)), "Only proven version ownership authorizes audio release")
            if (explicitUnique != null) assertFalse(Files.exists(Path.of(explicitUnique.path)))
            assertTrue(Files.exists(Path.of(shared.path)), "A sibling version's shared recording must stay")
            assertEquals(separate, app.chatRepository.getMessage(separate.id, "session"))
            assertEquals("Synthetic retained voice draft", app.chatRepository.getSessionDraft("session"))
            if (summaryFailure) {
                assertTrue(summaryEntered.get(), "Failure must be injected at actual session summary publication")
                assertTrue(controller.state.value.status.orEmpty().contains("首次写入"))
                assertEquals(retainedText.take(100), assertNotNull(ChatRepository(JsonFileStorage(app.appDataRoot)).getSession("session")).lastMessagePreview)
                assertEquals(updated, controller.state.value.messages.single { it.id == target.id })
            }

            // Use the actual Archive owner and inspect its portable voice record stream.
            app.saveSlotController.openFileContext("session")!!.join()
            app.saveSlotController.create("After version deletion", SaveSlotImagePolicy.NONE, includeAudio = true)
            withTimeout(15_000) { while (app.saveSlotController.state.value.busy) delay(20) }
            assertNull(app.saveSlotController.state.value.error)
            val summary = app.saveSlotController.state.value.slots.single()
            val slotPath = app.appDataRoot.resolve("entities/save_slots/${summary.id}.json")
            val slot = Json { ignoreUnknownKeys = true }.decodeFromString(SaveSlot.serializer(), Files.readString(slotPath))
            assertEquals(expectedVoices.size, slot.packageRef!!.audioCount)
            ZipFile(app.appDataRoot.resolve("save_slot_packages/${slot.packageRef!!.fileName}").toFile()).use { zip ->
                val voices = zip.getInputStream(zip.getEntry("voices.jsonl")).bufferedReader().use { reader ->
                    reader.lineSequence().filter { it.isNotBlank() }.map { Json.decodeFromString(GeneratedVoiceMessage.serializer(), it) }.toList()
                }
                assertEquals(expectedVoices, voices.map { it.id }.toSet())
                assertEquals(setOf("retained", "historical") + if (ambiguousLegacy) setOf(null) else emptySet(), voices.map { it.messageVersionId }.toSet())
                if (ambiguousLegacy) {
                    val retained = voices.single { it.id == "deleted-unique" }
                    assertEquals(if (preMigratedLegacy) null else true, retained.desktopVersionOwnershipUncertain)
                    assertEquals("deleted", retained.desktopRetiredVersionId)
                }
            }
        } finally { customController?.closeDraftPersistence(); app.close(); ownership.close(); parent.toFile().deleteRecursively() }
    }

    @Test fun `last segment empty edit removes only the removed version voices and anchors through cold archive read`() = runBlocking {
        exercise(editLastSegment = true)
    }
    @Test fun `alternative delete button removes only the removed version voices and anchors through cold archive read`() = runBlocking {
        exercise(editLastSegment = false)
    }
    @Test fun `caller cancellation after durable segment replacement still finishes exact version cleanup`() = runBlocking {
        exercise(editLastSegment = true, cancelAfterCommit = true)
    }
    @Test fun `caller cancellation after durable alternative deletion still finishes exact version cleanup`() = runBlocking {
        exercise(editLastSegment = false, cancelAfterCommit = true)
    }
    @Test fun `same dialogue legacy playback inference never authorizes deletion of its unique audio`() = runBlocking {
        exercise(editLastSegment = false, ambiguousLegacy = true)
    }
    @Test fun `durable source with failed session summary still refreshes and cleans proven version audio`() = runBlocking {
        exercise(editLastSegment = false, summaryFailure = true)
    }
    @Test fun `pre upgrade persisted version guesses remain protected after upgrade and archive creation`() = runBlocking {
        exercise(editLastSegment = false, ambiguousLegacy = true, preMigratedLegacy = true)
    }
    @Test fun `archive restoration of ambiguous versionless voice retains deletion protection through primary mutation`() = runBlocking {
        exercise(editLastSegment = false, ambiguousLegacy = true, restoreBeforeDelete = true)
    }

    @Test fun `locally retired and text matched old recordings survive full and selected backup restore to empty roots`() = runBlocking {
        val parent = Files.createTempDirectory("ccb-retained-legacy-voice-")
        val root = Files.createDirectory(parent.resolve("recorded"))
        val resolution = DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("unused.json"))
        val owner = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(resolution)).ownership
        val app = DesktopAppContainer(resolution, secretStoreFactory = { InMemoryDesktopSecretStore() },
            bundledAssets = { """{"entries":[]}""".toByteArray() })
        try {
            val card = CharacterCard("card", "Synthetic voice backup", createdAt = 1, updatedAt = 1)
            app.characterRepository.save(card)
            app.chatRepository.createSession(ChatSession("session", card.id, "Synthetic session", createdAt = 1, updatedAt = 1))
            val sharedLine = "Alice：『Shared line』"
            val original = app.chatRepository.addMessage(ChatMessage("target", "session", MessageRole.ASSISTANT, sharedLine,
                alternatives = listOf(sharedLine, sharedLine), alternativeVersionIds = listOf("a", "b"),
                currentAlternativeIndex = 1, currentAlternativeVersionId = "b", createdAt = 1, updatedAt = 1))
            suspend fun legacy(id: String, version: String?, text: String): GeneratedVoiceMessage {
                val audio = app.voiceAudioStore.persist("session", id, fishPreviewMp3().inputStream())
                return GeneratedVoiceMessage(id, "session", original.id, version, sourceOrder = 0, sourceSegmentKind = "DIALOGUE",
                    sourceSpeakerName = "Alice", sourceText = text, taggedText = text, characterId = card.id, characterName = card.name,
                    voice = FishAudioVoiceBinding("synthetic", "Synthetic voice"), fishModelId = "synthetic",
                    audioPath = audio.path, durationMs = audio.durationMs, byteLength = audio.byteLength, createdAt = 1, updatedAt = 1)
            }
            val oldA = legacy("old-a", "a", "Shared line")
            val oldB = legacy("old-b", "b", "Shared line")
            // This take was made on B when both versions contained the same line.
            val sourceBWithoutVersion = legacy("old-b-versionless", null, "Shared line")
            listOf(oldA, oldB, sourceBWithoutVersion).forEach { app.voiceRepository.save(it) }
            val edited = MessageAlternativeVersionPolicy.editCurrentContent(original, "Alice：『Edited line』")
            val selectedA = MessageAlternativeVersionPolicy.select(edited, 0)
            assertNotNull(app.chatRepository.updateMessageIfUnchangedReturningSnapshot(original, selectedA))
            val reopened = VoiceMessageRepository(JsonFileStorage(root), strictReads = true)
            val controller = app.primaryChatController
            controller.refresh(); controller.selectSession("session")
            val beforeDelete = assertNotNull(app.chatRepository.getMessage(original.id, original.sessionId))
            assertEquals(listOf(sharedLine, "Alice：『Edited line』"), beforeDelete.alternatives)
            assertEquals("a", reopened.listForCurrentVersion(beforeDelete).single { it.id == sourceBWithoutVersion.id }.messageVersionId)
            assertEquals(true, assertNotNull(reopened.get(sourceBWithoutVersion.id)).desktopVersionOwnershipUncertain)
            app.voiceRepository.reload()
            assertTrue(controller.deleteAssistantAlternative(beforeDelete, "a"))

            val backup = DesktopFullBackupService(root, app.dataOperationCoordinator, DesktopAudioMaintenance({ DesktopAudioFocus() }) {})
            val archives = listOf(
                parent.resolve("full.cbbackup") to null,
                parent.resolve("selected.cbbackup") to AppBackupSelection(characterIds = setOf(card.id),
                    sessions = mapOf(original.sessionId to BackupSessionChoice(images = false, voice = true, archives = false)))
            )
            for ((index, archive) in archives.withIndex()) {
                backup.exportModern(archive.first, null, selection = archive.second)
                assertTrue(backup.verifyFile(archive.first, null).desktopOnly)
                val restoredRoot = restoreInEmptyRoot(parent.resolve("restore-$index"), archive.first)
                val storage = JsonFileStorage(restoredRoot)
                val restored = VoiceMessageRepository(storage, strictReads = true)
                val current = assertNotNull(ChatRepository(storage).getMessage(original.id, original.sessionId))
                assertEquals(listOf("Alice：『Edited line』"), current.alternatives)
                assertEquals(listOf(oldB.id), restored.listForCurrentVersion(current).map { it.id })
                val voices = restored.listForSession(original.sessionId).associateBy { it.id }
                assertEquals(setOf(oldA.id, oldB.id, sourceBWithoutVersion.id), voices.keys)
                for (source in listOf(oldA, sourceBWithoutVersion)) {
                    val retained = voices.getValue(source.id)
                    assertNull(retained.messageVersionId)
                    assertEquals("a", retained.desktopRetiredVersionId)
                    assertContentEquals(Files.readAllBytes(Path.of(source.audioPath)), Files.readAllBytes(Path.of(retained.audioPath)))
                }
                assertEquals("b", voices.getValue(oldB.id).messageVersionId)
                assertTrue(Files.exists(Path.of(sourceBWithoutVersion.audioPath)))
            }
        } finally { app.close(); owner.close(); parent.toFile().deleteRecursively() }
    }

    private suspend fun restoreInEmptyRoot(base: Path, archive: Path): Path {
        Files.createDirectory(base)
        val source = DesktopDataRootResolution.Resolved(Files.createDirectory(base.resolve("empty")),
            DesktopDataRootProvenance.BOOTSTRAP_CUSTOM, base.resolve("bootstrap.json"))
        val bootstrap = DesktopBootstrapSettingsStore(source.bootstrapPath)
        bootstrap.save((bootstrap.load() as DesktopBootstrapLoadResult.Missing).document, DesktopDataRootSelection.custom(source.appDataRoot))
        val keys = Files.createDirectory(base.resolve("keys"))
        val unavailable = DesktopApplicationHomeResult.Unavailable(DesktopApplicationHomeUnavailableReason.PROPERTY_ABSENT)
        val gate = DesktopDataOperationCoordinator()
        val owner = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(source)).ownership
        val service = DesktopMergeImportService(source, gate, InMemoryDesktopSecretStore(), {}, { it() },
            DesktopMigrationDestinationPreparer({ unavailable }, DesktopDataRootOwnership::acquire),
            DesktopBootstrapAuthorityTransaction(applicationHomeResolver = { unavailable }),
            freshAuthority = { DesktopFreshRootAuthority(it, keys, MergeRootSyntheticProtector) })
        val destination = base.resolve("published")
        try {
            val preview = service.inspect(archive, null)
            assertEquals(DesktopMergeImportService.Publication.COMMITTED, service.commitModeWithProgress(preview, destination,
                emptyMap(), emptyMap(), true, false, DesktopBackupRestoreMode.REPLACE_SELECTED) {})
            DesktopFreshRootAuthority(destination, keys, MergeRootSyntheticProtector).verifyAndResolve()
        } finally { service.close(); gate.closeAndDrain(); owner.close() }
        return destination
    }
}
