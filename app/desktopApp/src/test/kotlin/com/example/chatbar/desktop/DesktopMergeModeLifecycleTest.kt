package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.desktop.backup.*
import com.example.chatbar.desktop.security.*
import java.nio.file.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopMergeModeLifecycleTest {
    private val unavailable = DesktopApplicationHomeResult.Unavailable(DesktopApplicationHomeUnavailableReason.PROPERTY_ABSENT)
    private fun records(name: String, content: String): Map<String, ByteArray> = mapOf(
        "entities/character_cards/c.json" to backupJson.encodeToString(CharacterCard.serializer(), CharacterCard("c", name, greeting = "Synthetic", createdAt = 1, updatedAt = 1)).toByteArray(),
        "entities/chat_sessions/s.json" to backupJson.encodeToString(ChatSession.serializer(), ChatSession("s", "c", "Chat", modelId = "m", createdAt = 1, updatedAt = 1)).toByteArray(),
        "entities/chat_messages/s_m.json" to backupJson.encodeToString(ChatMessage.serializer(), ChatMessage("m", "s", MessageRole.USER, content, createdAt = 1, updatedAt = 1)).toByteArray(),
        "entities/model_configs/m.json" to backupJson.encodeToString(ModelConfig.serializer(), ModelConfig("m", "$name model",
            "https://synthetic.invalid/v1", if (name == "Phone") "FAKE_PHONE_MODEL" else "", "synthetic", createdAt = 1)).toByteArray())

    private suspend fun fixture(beforeMode: suspend (DesktopBackupRestoreMode) -> Unit = {},
        action: suspend (Path, DesktopDataRootResolution.Resolved, Path, DesktopMergeImportService, Map<String, ByteArray>, Path) -> Unit) {
        val base = Files.createTempDirectory("ccb-modern-mode-lifecycle-")
        val root = Files.createDirectory(base.resolve("pc")); val keys = Files.createDirectory(base.resolve("keys"))
        val source = DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.BOOTSTRAP_CUSTOM, base.resolve("bootstrap.json"))
        val bootstrap = DesktopBootstrapSettingsStore(source.bootstrapPath)
        bootstrap.save((bootstrap.load() as DesktopBootstrapLoadResult.Missing).document, DesktopDataRootSelection.custom(root))
        val pc = records("Computer", "Original") + mapOf(
            "entities/character_cards/retained.json" to backupJson.encodeToString(CharacterCard.serializer(), CharacterCard("retained", "Retained", createdAt = 1, updatedAt = 1)).toByteArray(),
            "entities/world_books/unused.json" to backupJson.encodeToString(WorldBook.serializer(), WorldBook("unused", "Unused", createdAt = 1, updatedAt = 1)).toByteArray())
        pc.forEach { (relative, bytes) -> val target = root.resolve(relative); Files.createDirectories(target.parent); Files.write(target, bytes) }
        val incoming = records("Phone", "Incoming edited content")
        val scope = OfficialWireScope(setOf("resources", "models"), 1, 1, 1, 1, 1, false, false, false, false,
            sessionDefaultChoice = OfficialWireSessionChoice())
        val archive = Files.write(base.resolve("phone.cbbackup"), CensusWireFixtures.bytes(prefs = "{}", files = incoming, code = 88, version = 2, scope = scope))
        val originalArchive = Files.readAllBytes(archive)
        val gate = DesktopDataOperationCoordinator()
        val owner = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(source)).ownership
        val oldStore = InMemoryDesktopSecretStore().apply { save(DesktopCredentialKey.ModelApiKey("m"), "FAKE_PC_MODEL") }
        val service = DesktopMergeImportService(source, gate, oldStore, {}, { it() },
            DesktopMigrationDestinationPreparer({ unavailable }, DesktopDataRootOwnership::acquire),
            DesktopBootstrapAuthorityTransaction(storeFactory = { DesktopBootstrapSettingsStore(it) },
                applicationHomeResolver = { unavailable }, portableRootResolver = DesktopPortableRootResolver()),
            freshAuthority = { DesktopFreshRootAuthority(it, keys, MergeRootSyntheticProtector) }, beforeModePlan = beforeMode)
        try { action(base, source, archive, service, pc, keys) }
        finally {
            service.close(); gate.closeAndDrain(); owner.close()
            pc.forEach { (relative, bytes) -> assertContentEquals(bytes, Files.readAllBytes(root.resolve(relative))) }
            assertContentEquals(originalArchive, Files.readAllBytes(archive))
            assertEquals("FAKE_PC_MODEL", oldStore.load(DesktopCredentialKey.ModelApiKey("m")))
            assertTrue(base.toFile().deleteRecursively())
        }
    }

    @Test fun `actual authenticated mode commits publish an independent new root and reopened repositories preserve original sides`() = runBlocking<Unit> {
        for (mode in listOf(DesktopBackupRestoreMode.UPDATE_EXISTING, DesktopBackupRestoreMode.ADD_COPIES, DesktopBackupRestoreMode.REPLACE_SELECTED)) fixture { base, source, archive, service, _, keys ->
            val preview = service.inspect(archive, null)
            val modePreview = service.previewMode(preview, mode)
            val destination = base.resolve("prepared")
            assertEquals(DesktopMergeImportService.Publication.COMMITTED, service.commitModeWithProgress(preview, destination,
                emptyMap(), emptyMap(), true, false, mode) {})
            assertTrue(service.restartRequired)
            val namespace = DesktopFreshRootAuthority(destination, keys, MergeRootSyntheticProtector).verifyAndResolve()
            val newStore = WindowsSecretStore(namespace, MergeRootSyntheticProtector)
            val storage = JsonFileStorage(destination); val chats = ChatRepository(storage)
            val cards = mutableListOf<CharacterCard>()
            storage.visitEntitiesStrict("character_cards", CharacterCard.serializer()) { _, card -> cards += card }
            assertTrue(cards.any { it.id == "retained" })
            if (mode == DesktopBackupRestoreMode.ADD_COPIES) {
                assertEquals("Computer", cards.single { it.id == "c" }.name)
                val copy = cards.single { it.id != "c" && it.id != "retained" }
                assertNotEquals("c", copy.id)
                assertEquals("Phone（副本）", copy.name)
                val importedSession = modePreview.selection.phone.single { it.category == "chat_sessions" }.primaryId
                assertEquals("s", importedSession)
                val sessions = chats.getAllSessions()
                assertEquals(2, sessions.size)
                assertEquals("Original", chats.getMessagesReadOnly("s").single().content)
                assertEquals("Incoming edited content", chats.getMessagesReadOnly(sessions.single { it.id != "s" }.id).single().content)
                val model = assertNotNull(sessions.single { it.id != "s" }.modelId)
                assertNotEquals("m", model)
                assertEquals("FAKE_PHONE_MODEL", newStore.load(DesktopCredentialKey.ModelApiKey(model)))
                assertEquals("FAKE_PC_MODEL", newStore.load(DesktopCredentialKey.ModelApiKey("m")))
            } else {
                assertEquals("Phone", cards.single { it.id == "c" }.name)
                assertEquals("Incoming edited content", chats.getMessagesReadOnly("s").single().content)
                assertEquals("FAKE_PHONE_MODEL", newStore.load(DesktopCredentialKey.ModelApiKey("m")))
            }
            assertEquals(mode != DesktopBackupRestoreMode.REPLACE_SELECTED, Files.exists(destination.resolve("entities/world_books/unused.json")))
            val bootstrap = DesktopBootstrapSettingsStore(source.bootstrapPath).load() as DesktopBootstrapLoadResult.Loaded
            assertEquals(destination, bootstrap.document.selection.customRoot)
        }
    }

    @Test fun `actual controller mode previews run off caller cancel old projections surface failure and release frozen sources only after owner completion`() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
        fixture(beforeMode = { mode ->
            if (mode == DesktopBackupRestoreMode.ADD_COPIES) {
                entered.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
            if (mode == DesktopBackupRestoreMode.REPLACE_SELECTED) error("Synthetic mode read refusal")
        }) { _, _, archive, service, _, _ ->
            val picker = object : DesktopFilePicker {
                override fun pickOpenFile(type: DesktopFileType) = archive
                override fun pickSaveFile(type: DesktopFileType, suggestedName: String): Path? = null
            }
            val controller = DesktopMergeImportController(service, picker, DesktopDirectoryPicker { null }, dispatcher = Dispatchers.Unconfined)
            try {
                controller.inspectFile(archive, null)
                withTimeout(10000) { controller.state.first { it.ready && !it.busy } }
                controller.selectMode(DesktopBackupRestoreMode.ADD_COPIES)
                withTimeout(10000) { entered.await() }
                assertTrue(controller.state.value.modePlanning)
                assertNull(controller.state.value.modeSummary)
                controller.selectMode(DesktopBackupRestoreMode.UPDATE_EXISTING)
                withTimeout(10000) { cancelled.await() }
                val completed = withTimeout(10000) { controller.state.first { it.mode == DesktopBackupRestoreMode.UPDATE_EXISTING && it.modeSummary != null } }
                assertFalse(completed.modePlanning); assertTrue(completed.modeSummary!!.updated >= 2)
                controller.selectMode(DesktopBackupRestoreMode.REPLACE_SELECTED)
                val refused = withTimeout(10000) { controller.state.first { it.modeError != null } }
                assertTrue(refused.ready); assertNull(refused.modeSummary); assertFalse(refused.modePlanning)
                assertNotNull(refused.failureDetails)
                controller.selectMode(DesktopBackupRestoreMode.MERGE_CHOICES)
                assertNull(controller.state.value.modeError)
                controller.discardExternal()?.join()
                assertFalse(controller.state.value.ready)
            } finally { controller.closeAndDrain() }
        }
    }
}
