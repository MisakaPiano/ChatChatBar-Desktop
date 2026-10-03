package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.card.*
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class DesktopTransferClosureTest {
    @Test fun `A1 import preserves original postcommit cancellation through management`() = runBlocking {
        originalCancellation(DesktopTransferConflictAction.IMPORT_AS_NEW)
    }

    @Test fun `A1 overwrite preserves original postcommit cancellation through management`() = runBlocking {
        originalCancellation(DesktopTransferConflictAction.OVERWRITE)
    }

    @Test fun `management reconciliation cancellation cannot replace the original transfer cancellation`() = runBlocking {
        fixture { f ->
            f.app.characterTransfers.importNew(f.characterPackage)
            val original = CancellationException("original")
            val secondary = CancellationException("final reconcile")
            val transfer = f.app.createTypedTransferController(afterCharacterCommit = { _, _ -> throw original })
            transfer.recoverPreset(f.character)
            val management = DesktopManagementController(f.app.characterRepository, f.app.formatCardRepository,
                f.app.worldBookRepository, f.app.chatRepository, f.app.editorDraftRepository,
                f.app.characterTransfers, f.app.formatTransfers, f.app.worldBookTransfers, transfer,
                f.app.characterEditorController, f.app.formatCardEditorController, f.app.worldBookEditorController,
                discardAssets = {}, afterReconcile = { throw secondary })
            val caught = runCatching { management.resolveConflict(DesktopTransferConflictAction.IMPORT_AS_NEW) }.exceptionOrNull()
            assertSame(original, caught)
            assertTrue(original.suppressed.any { it === secondary })
            assertFalse(management.state.value.busy)
            assertFalse(transfer.state.value.busy)
            assertNull(transfer.state.value.pendingConflict)
        }
    }

    private suspend fun originalCancellation(action: DesktopTransferConflictAction) = fixture { f ->
        f.app.characterTransfers.importNew(f.characterPackage)
        val original = CancellationException("original caller cancellation")
        val controller = f.app.createTypedTransferController(afterCharacterCommit = { _, _ -> throw original })
        controller.recoverPreset(f.character)
        val management = f.app.createManagementController(controller)
        val caught = runCatching { management.resolveConflict(action) }.exceptionOrNull()
        assertSame(original, caught)
        assertNull(controller.state.value.pendingConflict)
        assertNotNull(controller.state.value.committedNotice)
        assertFalse(controller.state.value.busy)
        assertFalse(management.state.value.busy)
        val count = f.app.characterRepository.getAll().size
        management.resolveConflict(action)
        assertEquals(count, f.app.characterRepository.getAll().size)
        f.assertResources()
    }

    @Test fun `A2 real cancellation at import dispatcher return consumes committed decision`() = runBlocking {
        cancelledReturn(DesktopTransferConflictAction.IMPORT_AS_NEW)
    }

    @Test fun `A2 real cancellation at overwrite dispatcher return consumes committed decision`() = runBlocking {
        cancelledReturn(DesktopTransferConflictAction.OVERWRITE)
    }

    private suspend fun cancelledReturn(action: DesktopTransferConflictAction) = fixture { f ->
        f.app.characterTransfers.importNew(f.characterPackage)
        var returned = false
        val controller = f.app.createTypedTransferController(afterCharacterCommit = { _, _ -> returned = true })
        controller.recoverPreset(f.character)
        val dispatcher = QueuedCallerDispatcher()
        val cancellation = CancellationException("cancel while committed result is queued")
        var caught: Throwable? = null
        val job = CoroutineScope(currentCoroutineContext()).launch(dispatcher) {
            try { controller.resolveConflict(action) } catch (error: Throwable) { caught = error }
        }
        dispatcher.next().run() // Start the caller; shared transfer now runs on its real IO dispatcher.
        val returnContinuation = dispatcher.next() // Handshake: IO finished; caller has not resumed.
        assertFalse(returned)
        val expectedCount = if (action == DesktopTransferConflictAction.IMPORT_AS_NEW) 2 else 1
        assertEquals(expectedCount, f.app.characterRepository.getAll().size)
        f.assertResources()
        job.cancel(cancellation)
        returnContinuation.run()
        while (!job.isCompleted) dispatcher.next().run()
        // Coroutine stack-trace recovery may copy Job's exception at a dispatcher boundary.
        assertEquals(cancellation.message, assertIs<CancellationException>(caught).message)
        assertFalse(controller.state.value.busy)
        assertNull(controller.state.value.pendingConflict)
        controller.resolveConflict(action)
        assertEquals(expectedCount, f.app.characterRepository.getAll().size)
        f.assertResources()
    }

    @Test fun `unresolved Format survives refresh navigation recovery and file import`() = runBlocking {
        unresolvedCannotReplay(DesktopTransferKind.FORMAT)
    }

    @Test fun `unresolved WorldBook survives refresh navigation recovery and file import`() = runBlocking {
        unresolvedCannotReplay(DesktopTransferKind.WORLD_BOOK)
    }

    private suspend fun unresolvedCannotReplay(kind: DesktopTransferKind) = fixture { f ->
        var attempts = 0
        val controller = f.app.createTypedTransferController(
            afterTypedPrepared = { _, _ -> if (++attempts == 1) error("uncertain save") },
            readFormatDurable = { JsonFileStorage.EntityReadResult.ReadError(IOException("unavailable")) },
            readWorldDurable = { JsonFileStorage.EntityReadResult.ReadError(IOException("unavailable")) },
        )
        val entry = if (kind == DesktopTransferKind.FORMAT) f.format else f.world
        controller.recoverPreset(entry)
        assertTrue(controller.state.value.typedNotice!!.indeterminate)
        controller.refresh()
        val management = f.app.createManagementController(controller)
        management.refresh() // Same controller retained when leaving/re-entering Manage.
        management.recoverPreset(kind, entry)
        if (kind == DesktopTransferKind.FORMAT) controller.importFormat(f.formatPath)
        else controller.importWorldBook(f.worldPath)
        controller.resolveConflict(DesktopTransferConflictAction.IMPORT_AS_NEW)
        assertEquals(1, attempts, "Unverified target must block every importer write entry")
        assertTrue(controller.state.value.typedNotice!!.indeterminate)
        assertNull(controller.state.value.pendingConflict)
        assertTrue(f.app.formatCardRepository.getAll().isEmpty())
        assertTrue(f.app.worldBookRepository.getAll().isEmpty())
        assertFalse(controller.state.value.busy)
    }

    @Test fun `Format verification confirms committed new and overwrite without writing`() = runBlocking {
        verification(DesktopTransferKind.FORMAT, Resolution.COMMITTED)
    }
    @Test fun `WorldBook verification confirms committed new and overwrite without writing`() = runBlocking {
        verification(DesktopTransferKind.WORLD_BOOK, Resolution.COMMITTED)
    }
    @Test fun `Format verification releases proven precommit without replay`() = runBlocking {
        verification(DesktopTransferKind.FORMAT, Resolution.PRECOMMIT)
    }
    @Test fun `WorldBook verification releases proven precommit without replay`() = runBlocking {
        verification(DesktopTransferKind.WORLD_BOOK, Resolution.PRECOMMIT)
    }
    @Test fun `Format corrupt readerror and mismatched target remain unresolved`() = runBlocking {
        verification(DesktopTransferKind.FORMAT, Resolution.INDETERMINATE)
    }
    @Test fun `WorldBook corrupt readerror and mismatched target remain unresolved`() = runBlocking {
        verification(DesktopTransferKind.WORLD_BOOK, Resolution.INDETERMINATE)
    }

    @Test fun `cancelled strict verification retains evidence and releases busy`() = runBlocking {
        fixture { f ->
            var verifying = false
            val entered = CompletableDeferred<Unit>()
            val suspended = CompletableDeferred<Unit>()
            val controller = f.app.createTypedTransferController(
                afterTypedPrepared = { _, _ -> error("uncertain") },
                readFormatDurable = {
                    if (verifying) { entered.complete(Unit); suspended.await() }
                    JsonFileStorage.EntityReadResult.ReadError(IOException("unavailable"))
                })
            controller.recoverPreset(f.format)
            val evidence = assertNotNull(controller.state.value.unresolvedTransfer)
            verifying = true
            val job = launch { controller.recheckUnresolvedTransfer() }
            entered.await()
            job.cancel()
            job.join()
            assertEquals(evidence, controller.state.value.unresolvedTransfer)
            assertTrue(controller.state.value.typedNotice!!.indeterminate)
            assertFalse(controller.state.value.busy)
        }
    }

    private enum class Resolution { COMMITTED, PRECOMMIT, INDETERMINATE }

    private suspend fun verification(kind: DesktopTransferKind, resolution: Resolution) {
        for (action in listOf(DesktopTransferConflictAction.IMPORT_AS_NEW, DesktopTransferConflictAction.OVERWRITE)) fixture { f ->
            val priorFormat = f.app.formatTransfers.importNew(FormatCardPackage(name = "Format", content = "local edit"))
                .copy(isDefault = true)
            f.app.formatCardRepository.save(priorFormat)
            f.app.worldBookTransfers.importNew(WorldBookPackage(book = WorldBook.create("World").copy(description = "local edit")))
            var available = false
            var prepared = false
            var attempts = 0
            val readIds = mutableListOf<String>()
            val controller = f.app.createTypedTransferController(
                afterTypedPrepared = { _, _ -> prepared = true; if (++attempts == 1) error("uncertain") },
                readFormatDurable = { id ->
                    readIds += id
                    if (prepared && !available) JsonFileStorage.EntityReadResult.ReadError(IOException("unavailable"))
                    else f.app.formatCardRepository.readDurable(id)
                },
                readWorldDurable = { id ->
                    readIds += id
                    if (prepared && !available) JsonFileStorage.EntityReadResult.ReadError(IOException("unavailable"))
                    else f.app.worldBookRepository.readDurable(id)
                },
            )
            val entry = if (kind == DesktopTransferKind.FORMAT) f.format else f.world
            controller.recoverPreset(entry)
            controller.resolveConflict(action)
            val evidence = assertNotNull(controller.state.value.unresolvedTransfer)
            assertEquals(kind, evidence.kind)
            assertEquals(action, evidence.action)
            val entityType = if (kind == DesktopTransferKind.FORMAT) "format_cards" else "world_books"
            val target = f.root.resolve("entities/$entityType/${evidence.targetId}.json")
            if (resolution == Resolution.COMMITTED) {
                when (val expected = evidence.expected) {
                    is FormatCard -> f.app.jsonFileStorage.saveEntity(entityType, expected.id, expected, FormatCard.serializer())
                    is WorldBook -> f.app.jsonFileStorage.saveEntity(entityType, expected.id, expected, WorldBook.serializer())
                }
            }
            available = resolution != Resolution.INDETERMINATE
            val before = f.entityBytes()
            readIds.clear()
            controller.recheckUnresolvedTransfer()
            assertEquals(listOf(evidence.targetId), readIds)
            assertEquals(before, f.entityBytes(), "Verification may read but must not persist entities")
            assertEquals(1, attempts)
            assertNull(controller.state.value.pendingConflict)
            assertFalse(controller.state.value.busy)
            when (resolution) {
                Resolution.COMMITTED -> {
                    assertNull(controller.state.value.unresolvedTransfer)
                    assertFalse(controller.state.value.typedNotice!!.indeterminate)
                    controller.resolveConflict(action)
                    assertEquals(1, attempts)
                    if (kind == DesktopTransferKind.FORMAT && action == DesktopTransferConflictAction.OVERWRITE)
                        assertTrue(f.app.formatCardRepository.getById(priorFormat.id)!!.isDefault)
                }
                Resolution.PRECOMMIT -> {
                    assertNull(controller.state.value.unresolvedTransfer)
                    assertNull(controller.state.value.typedNotice)
                    controller.resolveConflict(action)
                    assertEquals(1, attempts)
                    controller.recoverPreset(entry)
                    controller.resolveConflict(action)
                    assertEquals(2, attempts, "Only a new explicit action may retry")
                    assertNull(controller.state.value.unresolvedTransfer)
                }
                Resolution.INDETERMINATE -> {
                    assertEquals(evidence, controller.state.value.unresolvedTransfer)
                    available = true
                    Files.createDirectories(target.parent)
                    Files.writeString(target, "{broken")
                    controller.recheckUnresolvedTransfer()
                    assertEquals("{broken", Files.readString(target))
                    assertEquals(evidence, controller.state.value.unresolvedTransfer)
                    when (val expected = evidence.expected) {
                        is FormatCard -> f.app.jsonFileStorage.saveEntity(entityType, expected.id,
                            expected.copy(content = "unexpected"), FormatCard.serializer())
                        is WorldBook -> f.app.jsonFileStorage.saveEntity(entityType, expected.id,
                            expected.copy(description = "unexpected"), WorldBook.serializer())
                    }
                    val mismatched = f.entityBytes()
                    controller.recheckUnresolvedTransfer()
                    assertEquals(evidence, controller.state.value.unresolvedTransfer)
                    assertEquals(mismatched, f.entityBytes())
                    controller.recoverPreset(entry)
                    assertEquals(1, attempts)
                }
            }
        }
    }

    private class QueuedCallerDispatcher : CoroutineDispatcher() {
        private val queue = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.put(block) }
        fun next(): Runnable = checkNotNull(queue.poll(10, TimeUnit.SECONDS)) { "Missing dispatcher handshake" }
    }

    private class Fixture(val root: Path, val app: DesktopAppContainer, val character: PresetEntry,
        val format: PresetEntry, val world: PresetEntry, val characterPackage: CharacterCardPackage,
        val formatPath: Path, val worldPath: Path) {
        fun entityBytes(): Map<String, List<Byte>> = Files.walk(root.resolve("entities")).use { paths ->
            paths.filter(Files::isRegularFile).toList().associate {
                root.relativize(it).toString() to Files.readAllBytes(it).toList()
            }
        }
        suspend fun assertResources() {
            val reopened = JsonFileStorage(root)
            for (card in app.characterRepository.getAll()) {
                val durable = assertIs<JsonFileStorage.EntityReadResult.Valid<CharacterCard>>(
                    reopened.readEntityStrict("character_cards", card.id, CharacterCard.serializer())).value
                assertTrue(Files.isRegularFile(root.resolve(requireNotNull(durable.avatar))))
                durable.customDocuments.forEach { assertTrue(Files.isRegularFile(root.resolve(it.filePath))) }
            }
        }
    }

    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        val parent = Files.createTempDirectory("desktop-transfer-r2-")
        val root = Files.createDirectory(parent.resolve("data"))
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val character = PresetEntry("character", PresetType.CHARACTER, 1, "character.json", "Character")
        val format = PresetEntry("format", PresetType.FORMAT, 1, "format.json", "Format")
        val world = PresetEntry("world", PresetType.WORLD_BOOK, 1, "world.json", "World")
        val characterPackage = CharacterCardPackage(card = PackagedCharacterCard(name = "Character", avatarResourceId = "avatar"),
            images = mapOf("avatar" to PackagedImage("avatar.png", "aW1hZ2U=")),
            documents = listOf(PackagedDocument("notes.txt", "txt", "inline document")))
        val assets = mapOf(
            "presets/manifest.json" to json.encodeToString(PresetManifest(listOf(character, format, world))).encodeToByteArray(),
            character.file to json.encodeToString(characterPackage).encodeToByteArray(),
            format.file to json.encodeToString(FormatCardPackage(name = "Format", content = "inline")).encodeToByteArray(),
            world.file to json.encodeToString(WorldBookPackage(book = WorldBook.create("World"))).encodeToByteArray(),
        )
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(root,
            DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { assets.getValue(it) })
        try {
            block(Fixture(root, app, character, format, world, characterPackage,
                Files.write(parent.resolve("format.json"), assets.getValue(format.file)),
                Files.write(parent.resolve("world.json"), assets.getValue(world.file))))
        } finally { app.close(); parent.toFile().deleteRecursively() }
    }
}
