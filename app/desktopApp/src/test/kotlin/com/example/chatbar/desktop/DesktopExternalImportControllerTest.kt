package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.backup.CensusWireFixtures
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.card.FormatCardPackage
import com.example.chatbar.domain.card.ModelTemplatePackage
import java.awt.image.BufferedImage
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.imageio.ImageIO
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class DesktopExternalImportControllerTest {
    @Test fun `actual typed conflicts hold the FIFO and cancellation advances only the selected head`() = runBlocking { fixture { f ->
        f.app.formatTransfers.importNew(FormatCardPackage(name = "Conflict", content = "Retained"))
        val a = f.format("a.png", "Conflict", "Incoming")
        val b = f.format("b.json", "Second", "Second content")
        val original = Files.readAllBytes(a)
        f.submit(a, b); val external = f.open()
        external.await(DesktopExternalImportPhase.READY); assertEquals(DesktopExternalImportKind.FORMAT, external.state.value.kind)
        val first = external.state.value.item!!.id
        external.import(); external.await(DesktopExternalImportPhase.CONFLICT)
        assertEquals(2, f.queue.state.value.files.size)
        assertEquals("Retained", f.app.formatCardRepository.getAll().single().content)
        external.resolve(DesktopTransferConflictAction.CANCEL)
        withTimeout(5000) { external.state.first { it.item?.id != first && it.phase == DesktopExternalImportPhase.READY && !it.busy } }
        assertEquals(b, external.state.value.item!!.path)
        external.import(); external.await(DesktopExternalImportPhase.COMPLETE)
        assertNotNull(external.state.value.targetId)
        assertEquals(setOf("Conflict", "Second"), f.app.formatCardRepository.getAll().map { it.name }.toSet())
        assertEquals(1, f.queue.state.value.files.size)
        external.next(); withTimeout(5000) { f.queue.state.first { it.files.isEmpty() } }
        assertContentEquals(original, Files.readAllBytes(a))
    } }

    @Test fun `cancelled A cannot be confirmed by an unrelated manual B import`() = runBlocking { fixture { f ->
        var cancelA = true
        val typed = f.app.createTypedTransferController(afterTypedPrepared = { _, _ ->
            if (cancelA) { cancelA = false; throw CancellationException("synthetic precommit cancellation") }
        })
        val a = f.format("a.json", "A", "A")
        f.submit(a); val external = f.open(typed)
        external.await(DesktopExternalImportPhase.READY)
        external.import(); external.await(DesktopExternalImportPhase.UNCERTAIN)
        assertNull(typed.state.value.unresolvedTransfer)
        typed.importFormat(f.format("b.json", "B", "B"))
        assertEquals("B", f.app.formatCardRepository.getAll().single().name)
        external.recheck(); external.await(DesktopExternalImportPhase.UNCERTAIN)
        assertNull(external.state.value.targetId)
        assertEquals(a, f.queue.state.value.files.single().path)
        assertEquals("B", f.app.formatCardRepository.getAll().single().name)
    } }

    @Test fun `same captured unresolved target can be strictly confirmed without importing again`() = runBlocking { fixture { f ->
        var unreadable = true
        val typed = f.app.createTypedTransferController(afterTypedPrepared = { _, _ -> error("synthetic uncertain write") },
            readFormatDurable = { id -> if (unreadable) JsonFileStorage.EntityReadResult.ReadError(IOException("synthetic unavailable read"))
                else f.app.formatCardRepository.readDurable(id) })
        f.submit(f.format("a.json", "A", "A")); val external = f.open(typed)
        external.await(DesktopExternalImportPhase.READY)
        external.import(); external.await(DesktopExternalImportPhase.UNCERTAIN)
        val expected = assertNotNull(typed.state.value.unresolvedTransfer).expected as FormatCard
        // Simulate the delayed durable result while the original read-back was unavailable.
        f.app.formatCardRepository.save(expected); unreadable = false
        external.recheck(); external.await(DesktopExternalImportPhase.COMPLETE)
        assertEquals(expected.id, external.state.value.targetId)
        assertEquals(listOf(expected.id), f.app.formatCardRepository.getAll().map { it.id })
        assertNull(typed.state.value.unresolvedTransfer)
    } }

    @Test fun `recovered attempted resources of every route remain uncertain with fresh controllers`() = runBlocking {
        for (kind in listOf(DesktopExternalImportKind.FORMAT, DesktopExternalImportKind.MODEL_TEMPLATE,
            DesktopExternalImportKind.IMAGE, DesktopExternalImportKind.BACKUP, DesktopExternalImportKind.SAVE_SLOT)) fixture { f ->
            val file = when (kind) {
                DesktopExternalImportKind.FORMAT -> f.format("a.json", "A", "A")
                DesktopExternalImportKind.MODEL_TEMPLATE -> Files.writeString(f.root.resolve("model.json"), f.app.transferJson.encodeToString(
                    ModelTemplatePackage.serializer(), ModelTemplatePackage(displayName = "Synthetic", baseUrl = "https://synthetic.invalid/v1", modelName = "synthetic",
                        isMultimodal = false, templateType = ModelTemplate.OPENAI, customParams = emptyMap())))
                DesktopExternalImportKind.IMAGE -> f.image()
                DesktopExternalImportKind.BACKUP -> Files.write(f.root.resolve("synthetic.cbbackup"), CensusWireFixtures.bytes())
                else -> SyntheticSaveSlotCorpus.archive(f.root.resolve("synthetic.cbsave"))
            }
            f.submit(file); f.queue.markAttempted(f.queue.state.value.files.single().id)
            val external = f.open(); external.await(DesktopExternalImportPhase.UNCERTAIN)
            assertEquals(kind, external.state.value.kind)
            external.recheck(); external.await(DesktopExternalImportPhase.UNCERTAIN)
            assertNull(external.state.value.targetId)
            assertEquals(1, f.queue.state.value.files.size)
            assertTrue(f.app.formatCardRepository.getAll().isEmpty())
            external.skip(confirmedUncertain = true)
            withTimeout(5000) { f.queue.state.first { it.files.isEmpty() } }
        }
    }

    @Test fun `encrypted full backup stays at its password and explicit conflict preview without committing`() = runBlocking { fixture { f ->
        val bytes = CensusWireFixtures.bytes(encrypted = true)
        val file = Files.write(f.root.resolve("synthetic.cbbackup"), bytes)
        val next = f.format("next.json", "Next", "Not imported")
        val settings = f.app.settingsRepository.getAppSettings()
        f.submit(file, next); val external = f.open(); external.await(DesktopExternalImportPhase.READY)
        external.import(); external.await(DesktopExternalImportPhase.ERROR)
        assertEquals(2, f.queue.state.value.files.size)
        external.retryInspection(); external.await(DesktopExternalImportPhase.READY)
        val password = CensusWireFixtures.PASSWORD.toCharArray()
        external.import(password = password); external.await(DesktopExternalImportPhase.PREVIEW, 20000)
        assertTrue(password.all { it == '\u0000' })
        assertTrue(f.app.mergeImportController.state.value.ready)
        assertFalse(f.app.mergeImportController.state.value.restartRequired)
        assertEquals(settings, f.app.settingsRepository.getAppSettings())
        assertTrue(f.app.formatCardRepository.getAll().isEmpty())
        assertContentEquals(bytes, Files.readAllBytes(file))
        assertEquals(file, f.queue.state.value.files.first().path)
        external.skip()
        withTimeout(5000) { external.state.first { it.item?.path == next && !it.busy } }
        assertFalse(f.app.mergeImportController.state.value.ready)
    } }

    @Test fun `archive file uses the explicit receiving session without restoring its messages or draft`() = runBlocking { fixture { f ->
        val session = ChatSession.create("synthetic-card", "Target").copy(longTermMemoryEnabled = false)
        f.app.chatRepository.createSession(session)
        val retained = SyntheticSaveSlotCorpus.message("retained", "Retained chat").copy(sessionId = session.id)
        f.app.chatRepository.addMessage(retained)
        f.app.chatRepository.updateSessionDraft(session.id, "Retained unsent draft")
        val source = SyntheticSaveSlotCorpus.archive(f.root.resolve("source.dat"))
        val bytes = Files.readAllBytes(source)
        f.submit(source); val external = f.open(); external.await(DesktopExternalImportPhase.READY)
        external.import(); external.await(DesktopExternalImportPhase.ERROR)
        assertEquals(1, f.queue.state.value.files.size)
        external.retryInspection(); external.await(DesktopExternalImportPhase.READY)
        external.import(sessionId = session.id); external.await(DesktopExternalImportPhase.COMPLETE, 15000)
        assertEquals(session.id, f.app.saveSlotController.state.value.sessionId)
        assertEquals(1, f.app.saveSlotController.state.value.slots.size)
        assertEquals(listOf(retained), f.app.chatRepository.getMessagesReadOnly(session.id))
        assertEquals("Retained unsent draft", f.app.chatRepository.getSessionDraft(session.id))
        assertContentEquals(bytes, Files.readAllBytes(source))
    } }

    @Test fun `busy Studio waits without cancelling its actual image task then persists guidance without generation`() = runBlocking { fixture { f ->
        val image = f.image(); val bytes = Files.readAllBytes(image)
        val release = CompletableDeferred<Unit>()
        val task = f.app.taskRuntime.launchNovelAi("Synthetic held image work") { release.await() }
        f.submit(image); val external = f.open(); external.await(DesktopExternalImportPhase.READY)
        try {
            external.import(); external.await(DesktopExternalImportPhase.ERROR)
            assertEquals(DesktopTaskStatus.RUNNING, f.app.taskRuntime.tasks.value.single { it.taskId == task }.status)
            assertFalse(f.queue.state.value.files.single().attempted)
        } finally { release.complete(Unit) }
        withTimeout(5000) { f.app.taskRuntime.tasks.first { rows -> rows.single { it.taskId == task }.status == DesktopTaskStatus.COMPLETED } }
        external.retryInspection(); external.await(DesktopExternalImportPhase.READY)
        external.import(); external.await(DesktopExternalImportPhase.COMPLETE)
        assertNotNull(f.app.novelAiStudioController.repository.loadDraft().imageGuidance.baseImage)
        assertEquals(1, f.app.taskRuntime.tasks.value.size)
        assertContentEquals(bytes, Files.readAllBytes(image))
    } }

    @Test fun `restart required refuses dispatch before changing the attempted marker or current entities`() = runBlocking { fixture { f ->
        val file = f.format("a.json", "A", "A")
        f.submit(file); val external = f.open(); external.await(DesktopExternalImportPhase.READY)
        f.app.dataOperationCoordinator.withExclusiveMaintenance { requireRestart() }
        external.import(); external.await(DesktopExternalImportPhase.ERROR)
        assertFalse(f.queue.state.value.files.single().attempted)
        assertEquals(DesktopDataOperationCoordinatorState.RESTART_REQUIRED, f.app.dataOperationCoordinator.state)
        assertFalse(Files.exists(f.app.appDataRoot.resolve("entities/format_cards")))
    } }

    @Test fun `attempt publication failure cannot reach the actual typed importer`() = runBlocking { fixture { f ->
        f.submit(f.format("a.json", "A", "A")); val external = f.open(); external.await(DesktopExternalImportPhase.READY)
        val journal = f.root.resolve("pending.json")
        Files.move(journal, f.root.resolve("retained-pending.json")); Files.createDirectory(journal)
        external.import(); external.await(DesktopExternalImportPhase.ERROR)
        assertFalse(f.queue.state.value.files.single().attempted)
        assertTrue(f.app.formatCardRepository.getAll().isEmpty())
        assertNull(external.typed.state.value.externalImportReceipt)
    } }

    private suspend fun DesktopExternalImportController.await(phase: DesktopExternalImportPhase, timeout: Long = 5000) {
        withTimeout(timeout) { state.first { !it.busy && it.phase == phase } }
    }
    private suspend fun fixture(work: suspend (Fixture) -> Unit) {
        val f = Fixture()
        try { f.initialize(); f.app.settingsRepository.saveAppSettings(AppSettings()); work(f) }
        finally { f.external?.closeAndDrain(); f.app.close(); f.root.toFile().deleteRecursively() }
    }
    private class Fixture {
        val root: Path = Files.createTempDirectory("external-import-controller-")
        val queue = DesktopExternalFileQueue(root.resolve("pending.json"))
        private val secrets = InMemoryDesktopSecretStore()
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(Files.createDirectory(root.resolve("data")),
            DesktopDataRootProvenance.BOOTSTRAP_CUSTOM, root.resolve("bootstrap.json")), secretStoreFactory = { secrets },
            bundledAssets = { """{"entries":[]}""".toByteArray() },
            filePicker = object : DesktopFilePicker {
                override fun pickOpenFile(type: DesktopFileType): Path? = error("External paths must not invoke the picker")
                override fun pickSaveFile(type: DesktopFileType, suggestedName: String): Path? = error("No external export requested")
            }, directoryPicker = DesktopDirectoryPicker { error("No destination before explicit merge commit") })
        suspend fun initialize() {
            val store = DesktopBootstrapSettingsStore(root.resolve("bootstrap.json"))
            store.save((store.load() as DesktopBootstrapLoadResult.Missing).document, DesktopDataRootSelection.custom(app.appDataRoot))
        }
        var external: DesktopExternalImportController? = null
        fun open(typed: DesktopTypedTransferController = app.createTypedTransferController()): DesktopExternalImportController =
            DesktopExternalImportController(queue, app.dataOperationCoordinator, typed, app.createModelTemplateTransferController(),
                app.mergeImportController, app.saveSlotController, app.novelAiStudioController,
                imageBusy = { app.taskRuntime.tasks.value.any { it.kind == DesktopTaskKind.NOVELAI && it.status == DesktopTaskStatus.RUNNING } },
                dispatcher = Dispatchers.Unconfined).also { external = it }
        fun submit(vararg files: Path) { check(queue.submit(UUID.randomUUID().toString(), files.toList())) }
        fun format(name: String, title: String, content: String): Path = Files.writeString(root.resolve(name), app.transferJson.encodeToString(
            FormatCardPackage.serializer(), FormatCardPackage(name = title, content = content)))
        fun image(): Path = root.resolve("synthetic.png").also { path ->
            val image = BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB)
            try { ImageIO.write(image, "png", path.toFile()) } finally { image.flush() }
        }
    }
}
