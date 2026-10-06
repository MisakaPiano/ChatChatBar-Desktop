package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.PresetEntry
import com.example.chatbar.data.local.entity.PresetImportState
import com.example.chatbar.data.local.entity.PresetManifest
import com.example.chatbar.data.local.entity.PresetType
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.local.entity.WorldBookEntry
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.WorldBookRepository
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.card.CharacterCardPackage
import com.example.chatbar.domain.card.PackagedCharacterCard
import com.example.chatbar.domain.card.WorldBookPackage
import com.example.chatbar.domain.card.CharacterCardTransferCore
import com.example.chatbar.domain.card.AuthoritativeCharacterTransferPromptPolicy
import com.example.chatbar.interop.CharacterTransferPostCommitCancellationFixture
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class DesktopPresetSuiteRestoreServiceTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val world = PresetEntry("world-a", PresetType.WORLD_BOOK, 3,
        "presets/world_books/a.json", "Bundled World")
    private val character = PresetEntry("character-a", PresetType.CHARACTER, 4,
        "presets/characters/a.json", "Bundled Character", listOf(world.presetKey))

    private fun fixture(entries: List<PresetEntry> = listOf(world, character),
        overrideAssets: Map<String, ByteArray> = emptyMap(),
        block: suspend (Fixture) -> Unit) = runBlocking {
        val parent = Files.createTempDirectory("desktop-suite-restore-")
        val files = mutableMapOf("presets/manifest.json" to json.encodeToString(PresetManifest(entries)).encodeToByteArray())
        entries.forEach { entry -> files[entry.file] = when (entry.type) {
            PresetType.CHARACTER -> json.encodeToString(CharacterCardPackage(
                card = PackagedCharacterCard(name = entry.displayName))).encodeToByteArray()
            PresetType.WORLD_BOOK -> json.encodeToString(WorldBookPackage(book = WorldBook.create(entry.displayName,
                "bundled content").copy(entries = listOf(WorldBookEntry.create(content = "bundled entry"))))).encodeToByteArray()
            else -> "{}".encodeToByteArray()
        } }
        files.putAll(overrideAssets)
        val root = parent.resolve("data")
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(root,
            DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { files.getValue(it) })
        try { block(Fixture(app, root, files)) } finally { app.close(); parent.toFile().deleteRecursively() }
    }

    private inner class Fixture(val app: DesktopAppContainer, val root: Path, val assets: Map<String, ByteArray>) {
        fun freshContainer() = DesktopAppContainer(DesktopDataRootResolution.Resolved(root,
            DesktopDataRootProvenance.CLI_OVERRIDE, root.parent.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { assets.getValue(it) })
        suspend fun card(key: String = character.presetKey, version: Int = character.version,
            name: String = "Local character", bindings: List<String> = emptyList()): CharacterCard =
            CharacterCard.create(name, "user greeting").copy(sourcePresetKey = key,
                sourcePresetVersion = version, worldBookIds = bindings).also { app.characterRepository.save(it) }
        suspend fun book(key: String = world.presetKey, version: Int = world.version,
            name: String = "Local world"): WorldBook = WorldBook.create(name, "user changed content")
            .copy(sourcePresetKey = key, sourcePresetVersion = version,
                entries = listOf(WorldBookEntry.create(content = "user entry"))).also { app.worldBookRepository.save(it) }
        fun service(
            beforeWorld: suspend (PresetEntry) -> Unit = {},
            afterWorld: suspend (WorldBook) -> Unit = {},
            afterCharacter: suspend (CharacterCard) -> Unit = {},
            beforeBinding: suspend (CharacterCard) -> Unit = {},
            afterBindingCommit: () -> Unit = {},
            saveBinding: suspend (CharacterCard, () -> Unit) -> Unit = app.characterRepository::saveObserved,
            readCharacter: suspend (String) -> JsonFileStorage.EntityReadResult<CharacterCard> = app.characterRepository::readDurable,
        scanCharacters: suspend () -> List<JsonFileStorage.EntityFileRead<CharacterCard>> = app.characterRepository::scanDurable,
        scanWorlds: suspend () -> List<JsonFileStorage.EntityFileRead<WorldBook>> = app.worldBookRepository::scanDurable,
        characterTransfers: CharacterCardTransferCore = app.characterTransfers,
        ) = DesktopPresetSuiteRestoreService(app.presetSource, app.characterRepository, app.worldBookRepository,
            characterTransfers, app.worldBookTransfers, app.dataOperationCoordinator,
            beforeWorld, afterWorld, afterCharacter, beforeBinding, afterBindingCommit, saveBinding,
            readCharacterDurable = readCharacter, scanCharactersDurable = scanCharacters,
            scanWorldsDurable = scanWorlds)
        fun ledgerPath() = root.resolve("entities/preset_import_state.json")
    }

    @Test fun `existing edited Character and WorldBook only regain missing binding and keep unrelated binding and versions`() = fixture { f ->
        val extra = WorldBook.create("Unrelated").also { f.app.worldBookRepository.save(it) }
        val card = f.card(version = 1, bindings = listOf(extra.id))
        val book = f.book(version = 1)
        val persistedBook = f.app.worldBookRepository.getById(book.id)
        val result = f.service().restore(character)
        assertEquals(DesktopPresetSuiteRestoreResult(false, 0, 1), result)
        assertEquals(card.copy(worldBookIds = listOf(extra.id, book.id)), f.app.characterRepository.getById(card.id))
        assertEquals(persistedBook, f.app.worldBookRepository.getById(book.id))
        assertEquals(1, f.app.characterRepository.getById(card.id)?.sourcePresetVersion)
        assertEquals(1, f.app.worldBookRepository.getById(book.id)?.sourcePresetVersion)
        assertFalse(Files.exists(f.ledgerPath()))
    }

    @Test fun `missing WorldBook is imported once and attached to unchanged Character`() = fixture { f ->
        val card = f.card()
        val result = f.service().restore(character)
        val book = f.app.worldBookRepository.getAll().single()
        assertEquals(DesktopPresetSuiteRestoreResult(false, 1, 1), result)
        assertEquals(world.presetKey, book.sourcePresetKey)
        assertEquals(world.version, book.sourcePresetVersion)
        assertEquals(card.copy(worldBookIds = listOf(book.id)), f.app.characterRepository.getById(card.id))
    }

    @Test fun `missing Character is imported once and linked to existing WorldBook`() = fixture { f ->
        val book = f.book()
        val persistedBook = f.app.worldBookRepository.getById(book.id)
        assertEquals(DesktopPresetSuiteRestoreResult(true, 0, 1), f.service().restore(character))
        val card = f.app.characterRepository.getAll().single()
        assertEquals(character.presetKey, card.sourcePresetKey)
        assertEquals(character.version, card.sourcePresetVersion)
        assertEquals(listOf(book.id), card.worldBookIds)
        assertEquals(persistedBook, f.app.worldBookRepository.getById(book.id))
    }

    @Test fun `both missing import once and second restore is no-op with byte-identical entities`() = fixture { f ->
        val service = f.service()
        assertEquals(DesktopPresetSuiteRestoreResult(true, 1, 1), service.restore(character))
        val card = f.app.characterRepository.getAll().single()
        val book = f.app.worldBookRepository.getAll().single()
        val cardBytes = Files.readAllBytes(f.root.resolve("entities/character_cards/${card.id}.json"))
        val bookBytes = Files.readAllBytes(f.root.resolve("entities/world_books/${book.id}.json"))
        assertEquals(listOf(book.id), card.worldBookIds)
        assertTrue(service.restore(character).alreadyComplete)
        assertContentEquals(cardBytes, Files.readAllBytes(f.root.resolve("entities/character_cards/${card.id}.json")))
        assertContentEquals(bookBytes, Files.readAllBytes(f.root.resolve("entities/world_books/${book.id}.json")))
        assertEquals(1, f.app.characterRepository.getAll().size)
        assertEquals(1, f.app.worldBookRepository.getAll().size)
        assertFalse(Files.exists(f.ledgerPath()))
    }

    @Test fun `same-name custom entities remain untouched while preset-origin copies get distinct IDs`() = fixture { f ->
        val customCard = CharacterCard.create(character.displayName, "custom greeting").also { f.app.characterRepository.save(it) }
        val customBook = WorldBook.create(world.displayName, "custom world").also { f.app.worldBookRepository.save(it) }
        val persistedCustomBook = f.app.worldBookRepository.getById(customBook.id)
        assertEquals(DesktopPresetSuiteRestoreResult(true, 1, 1), f.service().restore(character))
        val presetCard = f.app.characterRepository.getAll().single { it.sourcePresetKey == character.presetKey }
        val presetBook = f.app.worldBookRepository.getAll().single { it.sourcePresetKey == world.presetKey }
        assertNotEquals(customCard.id, presetCard.id)
        assertNotEquals(customBook.id, presetBook.id)
        assertEquals(listOf(presetBook.id), presetCard.worldBookIds)
        assertEquals(customCard, f.app.characterRepository.getById(customCard.id))
        assertEquals(persistedCustomBook, f.app.worldBookRepository.getById(customBook.id))
    }

    @Test fun `strict discovery refreshes stale name caches before transfer NamePolicy runs`() = fixture { f ->
        f.app.characterRepository.getAll()
        f.app.worldBookRepository.getAll()
        val externalStorage = JsonFileStorage(f.root)
        val customCard = CharacterCard.create(character.displayName).also {
            CharacterRepository(externalStorage).save(it)
        }
        val customBook = WorldBook.create(world.displayName).also {
            WorldBookRepository(externalStorage).save(it)
        }
        assertEquals(DesktopPresetSuiteRestoreResult(true, 1, 1), f.service().restore(character))
        val presetCard = f.app.characterRepository.getAll().single { it.sourcePresetKey == character.presetKey }
        val presetBook = f.app.worldBookRepository.getAll().single { it.sourcePresetKey == world.presetKey }
        assertNotEquals(customCard.name, presetCard.name)
        assertNotEquals(customBook.name, presetBook.name)
        assertEquals(character.displayName, f.app.characterRepository.getById(customCard.id)?.name)
        assertEquals(world.displayName, f.app.worldBookRepository.getById(customBook.id)?.name)
    }

    @Test fun `duplicate Character provenance aborts before importing a missing WorldBook`() = fixture { f ->
        f.card(name = "First"); f.card(name = "Second")
        val failure = assertFailsWith<IllegalStateException> { f.service().restore(character) }
        assertTrue(failure.message!!.contains("Multiple Characters"))
        assertTrue(f.app.worldBookRepository.getAll().isEmpty())
        assertEquals(2, f.app.characterRepository.getAll().size)
    }

    @Test fun `duplicate WorldBook provenance aborts before importing a missing Character`() = fixture { f ->
        f.book(name = "First"); f.book(name = "Second")
        val failure = assertFailsWith<IllegalStateException> { f.service().restore(character) }
        assertTrue(failure.message!!.contains("Multiple WorldBooks"))
        assertTrue(f.app.characterRepository.getAll().isEmpty())
    }

    @Test fun `manifest missing later key aborts before first valid WorldBook import`() {
        val requested = character.copy(worldBookPresetKeys = listOf(world.presetKey, "missing"))
        fixture(listOf(world, requested)) { f ->
            assertTrue(assertFailsWith<IllegalArgumentException> { f.service().restore(requested) }
                .message!!.contains("missing"))
            assertTrue(f.app.worldBookRepository.getAll().isEmpty())
            assertTrue(f.app.characterRepository.getAll().isEmpty())
        }
    }

    @Test fun `manifest wrong-type and duplicate referenced key abort before write`() {
        val wrong = PresetEntry("wrong", PresetType.FORMAT, 1, "presets/formats/wrong.json", "Wrong")
        val requested = character.copy(worldBookPresetKeys = listOf(world.presetKey, wrong.presetKey))
        fixture(listOf(world, wrong, requested)) { f ->
            assertTrue(assertFailsWith<IllegalArgumentException> { f.service().restore(requested) }
                .message!!.contains("not a WorldBook"))
            assertTrue(f.app.worldBookRepository.getAll().isEmpty())
        }
        fixture(listOf(world, world.copy(file = "duplicate.json"), character)) { f ->
            assertTrue(assertFailsWith<IllegalArgumentException> { f.service().restore(character) }
                .message!!.contains("ambiguous"))
            assertTrue(f.app.characterRepository.getAll().isEmpty())
        }
    }

    @Test fun `unknown and no-relationship Character requests fail before write`() = fixture { f ->
        assertFailsWith<IllegalArgumentException> { f.service().restore(character.copy(presetKey = "unknown")) }
        assertFailsWith<IllegalArgumentException> { f.service().restore(character.copy(worldBookPresetKeys = emptyList())) }
        assertTrue(f.app.characterRepository.getAll().isEmpty())
        assertTrue(f.app.worldBookRepository.getAll().isEmpty())
    }

    @Test fun `failure after first of two WorldBooks commits resumes without duplicate`() {
        val second = world.copy(presetKey = "world-b", file = "presets/world_books/b.json", displayName = "World B")
        val requested = character.copy(worldBookPresetKeys = listOf(world.presetKey, second.presetKey))
        fixture(listOf(world, second, requested)) { f ->
            var once = true
            val service = f.service(afterWorld = { if (once) { once = false; error("after first durable WorldBook") } })
            assertFailsWith<IllegalStateException> { service.restore(requested) }
            val firstId = f.app.worldBookRepository.getAll().single().id
            assertEquals(DesktopPresetSuiteRestoreResult(true, 1, 2), service.restore(requested))
            assertEquals(2, f.app.worldBookRepository.getAll().size)
            assertEquals(firstId, f.app.worldBookRepository.getAll().single { it.sourcePresetKey == world.presetKey }.id)
        }
    }

    @Test fun `failure after Character commits retries by provenance instead of making a copy`() = fixture { f ->
        var once = true
        val service = f.service(afterCharacter = { if (once) { once = false; error("after durable Character") } })
        assertFailsWith<IllegalStateException> { service.restore(character) }
        val original = f.app.characterRepository.getAll().single()
        assertEquals(emptyList(), original.worldBookIds)
        assertEquals(DesktopPresetSuiteRestoreResult(false, 0, 1), service.restore(character))
        assertEquals(listOf(original.id), f.app.characterRepository.getAll().map { it.id })
    }

    @Test fun `binding callback failure after durable commit is recognized as success`() = fixture { f ->
        val card = f.card(); val book = f.book()
        val service = f.service(afterBindingCommit = { error("cache publication interrupted") })
        assertEquals(DesktopPresetSuiteRestoreResult(false, 0, 1), service.restore(character))
        assertEquals(listOf(book.id), f.app.characterRepository.readDurable(card.id)
            .let { (it as JsonFileStorage.EntityReadResult.Valid).value.worldBookIds })
        assertTrue(service.restore(character).alreadyComplete)
    }

    @Test fun `binding precommit failure is retryable and leaves prior bytes intact`() = fixture { f ->
        val card = f.card(); f.book()
        val original = Files.readAllBytes(f.root.resolve("entities/character_cards/${card.id}.json"))
        var once = true
        val service = f.service(beforeBinding = { if (once) { once = false; error("before save") } })
        assertFailsWith<IllegalStateException> { service.restore(character) }
        assertContentEquals(original, Files.readAllBytes(f.root.resolve("entities/character_cards/${card.id}.json")))
        assertEquals(1, service.restore(character).bindingsAdded)
    }

    @Test fun `repository binding save precommit failure leaves links unchanged and retries safely`() = fixture { f ->
        val card = f.card(); val book = f.book()
        var once = true
        val service = f.service(saveBinding = { updated, committed ->
            if (once) { once = false; error("storage refused binding save") }
            f.app.characterRepository.saveObserved(updated, committed)
        })
        assertTrue(assertFailsWith<IllegalStateException> { service.restore(character) }
            .message!!.contains("not committed"))
        assertEquals(emptyList(), f.app.characterRepository.getById(card.id)?.worldBookIds)
        assertEquals(DesktopPresetSuiteRestoreResult(false, 0, 1), service.restore(character))
        assertEquals(listOf(book.id), f.app.characterRepository.getById(card.id)?.worldBookIds)
    }

    @Test fun `binding rereads durable Character after a concurrent user edit before saving`() = fixture { f ->
        val card = f.card(); val book = f.book()
        val extra = WorldBook.create("extra").also { f.app.worldBookRepository.save(it) }
        val service = f.service(beforeBinding = {
            f.app.characterRepository.save(it.copy(name = "Edited during operation", worldBookIds = listOf(extra.id)))
        })
        assertEquals(1, service.restore(character).bindingsAdded)
        val durable = f.app.characterRepository.getById(card.id)!!
        assertEquals("Edited during operation", durable.name)
        assertEquals(listOf(extra.id, book.id), durable.worldBookIds)
    }

    @Test fun `indeterminate binding blocks further writes until strict target read succeeds`() = fixture { f ->
        val card = f.card(); f.book()
        var unreadable = true
        var once = true
        var callbackTriggered = false
        val service = f.service(
            afterBindingCommit = { callbackTriggered = true; if (once) { once = false; error("postcommit") } },
            readCharacter = { id -> if (callbackTriggered && unreadable) JsonFileStorage.EntityReadResult.ReadError(IOException("unreadable"))
                else f.app.characterRepository.readDurable(id) })
        assertFailsWith<DesktopPresetSuiteIndeterminateException> { service.restore(character) }
        val originalBytes = Files.readAllBytes(f.root.resolve("entities/character_cards/${card.id}.json"))
        assertFailsWith<DesktopPresetSuiteIndeterminateException> { service.restore(character) }
        assertContentEquals(originalBytes, Files.readAllBytes(f.root.resolve("entities/character_cards/${card.id}.json")))
        unreadable = false
        assertTrue(service.restore(character).alreadyComplete)
    }

    @Test fun `pre-mutation cancellation propagates same object without business writes`() = fixture { f ->
        val cancelled = CancellationException("cancel before import")
        val service = f.service(beforeWorld = { throw cancelled })
        assertSame(cancelled, assertFailsWith<CancellationException> { service.restore(character) })
        assertTrue(f.app.worldBookRepository.getAll().isEmpty())
        assertTrue(f.app.characterRepository.getAll().isEmpty())
    }

    @Test fun `post-WorldBook cancellation reconciles and retry never duplicates durable book`() = fixture { f ->
        val cancelled = CancellationException("cancel after book")
        var once = true
        val service = f.service(afterWorld = { if (once) { once = false; throw cancelled } })
        assertSame(cancelled, assertFailsWith<CancellationException> { service.restore(character) })
        val first = f.app.worldBookRepository.getAll().single()
        assertEquals(DesktopPresetSuiteRestoreResult(true, 0, 1), service.restore(character))
        assertEquals(first.id, f.app.worldBookRepository.getAll().single().id)
    }

    @Test fun `real Job cancellation after WorldBook commit reconciles before a retry`() = fixture { f -> kotlinx.coroutines.coroutineScope {
        val committed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val service = f.service(afterWorld = { committed.complete(Unit); release.await() })
        val restore = async { service.restore(character) }
        withTimeout(10_000) { committed.await() }
        val firstId = f.app.worldBookRepository.getAll().single().id
        restore.cancel(CancellationException("cancel after durable WorldBook commit"))
        release.complete(Unit)
        assertFailsWith<CancellationException> { restore.await() }
        assertTrue(restore.isCancelled)
        assertEquals(DesktopPresetSuiteRestoreResult(true, 0, 1), service.restore(character))
        assertEquals(firstId, f.app.worldBookRepository.getAll().single().id)
    } }

    @Test fun `post-Character cancellation reconciles and retry never duplicates durable Character`() = fixture { f ->
        val cancelled = CancellationException("cancel after Character")
        var once = true
        val service = f.service(afterCharacter = { if (once) { once = false; throw cancelled } })
        assertSame(cancelled, assertFailsWith<CancellationException> { service.restore(character) })
        val first = f.app.characterRepository.getAll().single()
        assertEquals(DesktopPresetSuiteRestoreResult(false, 0, 1), service.restore(character))
        assertEquals(first.id, f.app.characterRepository.getAll().single().id)
    }

    @Test fun `post-binding cancellation preserves original exception and committed links`() = fixture { f ->
        val card = f.card(); val book = f.book()
        val cancelled = CancellationException("cancel in binding callback")
        val service = f.service(afterBindingCommit = { throw cancelled })
        assertSame(cancelled, assertFailsWith<CancellationException> { service.restore(character) })
        assertEquals(listOf(book.id), f.app.characterRepository.readDurable(card.id)
            .let { (it as JsonFileStorage.EntityReadResult.Valid).value.worldBookIds })
        assertTrue(service.restore(character).alreadyComplete)
    }

    @Test fun `ordinary management restore still does not add manifest relationship`() = fixture { f ->
        val book = f.book()
        val transfer = f.app.createTypedTransferController()
        val management = f.app.createManagementController(transfer)
        management.recoverPreset(DesktopTransferKind.CHARACTER, character)
        val card = f.app.characterRepository.getAll().single()
        assertFalse(book.id in card.worldBookIds)
        assertEquals(emptyList(), card.worldBookIds)
        assertFalse(Files.exists(f.ledgerPath()))
    }

    @Test fun `embedded Package WorldBook and manifest relation remain separate and both are linked`() {
        val embedded = WorldBook.create("Embedded").copy(entries = listOf(WorldBookEntry.create(content = "embedded")))
        val packageBytes = json.encodeToString(CharacterCardPackage(
            card = PackagedCharacterCard(name = character.displayName),
            worldBooks = listOf(embedded))).encodeToByteArray()
        fixture(overrideAssets = mapOf(character.file to packageBytes)) { f ->
            f.service().restore(character)
            val card = f.app.characterRepository.getAll().single()
            val books = f.app.worldBookRepository.getAll()
            val manifest = books.single { it.sourcePresetKey == world.presetKey }
            val packageBook = books.single { it.name == "Embedded" }
            assertEquals(setOf(manifest.id, packageBook.id), card.worldBookIds.toSet())
            assertEquals("embedded", packageBook.entries.single().content)
            assertTrue(f.service().restore(character).alreadyComplete)
        }
    }

    @Test fun `whole suite holds normal-operation gate against root migration and management stays busy`() = fixture { f -> kotlinx.coroutines.coroutineScope {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val service = f.service(beforeWorld = { entered.complete(Unit); release.await() })
        val management = f.app.createManagementController(f.app.createTypedTransferController(), service)
        val restore = async { management.restoreCompletePreset(character) }
        withTimeout(10_000) { entered.await() }
        assertTrue(management.state.value.busy)
        assertFalse(management.canRestoreCompletePreset())
        management.recoverPreset(DesktopTransferKind.CHARACTER, character)
        assertFalse(management.transfer.state.value.busy)
        val maintenanceDone = CompletableDeferred<Unit>()
        val maintenanceQueued = CompletableDeferred<Unit>()
        val maintenance = async {
            maintenanceQueued.complete(Unit)
            f.app.dataOperationCoordinator.withExclusiveMaintenance { maintenanceDone.complete(Unit) }
        }
        withTimeout(10_000) { maintenanceQueued.await() }
        assertEquals(DesktopDataOperationCoordinatorState.EXCLUSIVE, f.app.dataOperationCoordinator.state)
        assertFalse(maintenanceDone.isCompleted)
        release.complete(Unit)
        restore.await()
        maintenance.await()
        assertTrue(maintenanceDone.isCompleted)
        assertFalse(management.state.value.busy)
        assertNull(management.state.value.error)
    } }

    @Test fun `pending typed conflict and open Character editor block suite operation`() = fixture { f ->
        val book = f.book()
        val persistedBook = f.app.worldBookRepository.getById(book.id)
        val custom = CharacterCard.create(character.displayName).also { f.app.characterRepository.save(it) }
        val transfer = f.app.createTypedTransferController()
        val management = f.app.createManagementController(transfer)
        transfer.recoverPreset(character)
        assertNotNull(transfer.state.value.pendingConflict)
        assertFalse(management.canRestoreCompletePreset())
        management.restoreCompletePreset(character)
        assertNotNull(management.state.value.error)
        assertEquals(listOf(custom.id), f.app.characterRepository.getAll().map { it.id })
        assertEquals(persistedBook, f.app.worldBookRepository.getById(book.id))
        transfer.resolveConflict(DesktopTransferConflictAction.CANCEL)
        f.app.characterEditorController.openNew()
        assertFalse(management.canRestoreCompletePreset())
        management.restoreCompletePreset(character)
        assertNotNull(management.state.value.error)
        assertEquals(listOf(custom.id), f.app.characterRepository.getAll().map { it.id })
    }

    @Test fun `suite action stays distinct in UI and is available only for related Character presets`() = fixture { f ->
        val noWorld = character.copy(presetKey = "rupa", worldBookPresetKeys = emptyList())
        assertTrue(showCompletePresetAction(DesktopTransferKind.CHARACTER, character))
        assertFalse(showCompletePresetAction(DesktopTransferKind.CHARACTER, noWorld))
        assertFalse(showCompletePresetAction(DesktopTransferKind.FORMAT, world))
        assertFalse(showCompletePresetAction(DesktopTransferKind.WORLD_BOOK, world))
        val transfer = f.app.createTypedTransferController()
        val management = f.app.createManagementController(transfer)
        assertTrue(management.canRestoreCompletePreset())
        val controls = Files.readString(Path.of("src/main/kotlin/com/example/chatbar/desktop/DesktopManagementControls.kt"))
        assertTrue(controls.contains("controller.recoverPreset(kind, row.entry)"))
        assertTrue(controls.contains("controller.restoreCompletePreset(row.entry)"))
        assertTrue(controls.contains("controller.canRestoreCompletePreset()"))
        assertFalse(controls.contains("worldTransfers.importNew("))
        val characterPanel = Files.readString(Path.of("src/main/kotlin/com/example/chatbar/desktop/DesktopCharacterEditorPanel.kt"))
        // R2 uses an explicit Start Chat action instead of making the whole summary an editor link.
        assertTrue(characterPanel.contains("if (!managementState.busy) onStartChat(card.id)"))
        assertTrue(characterPanel.contains("enabled = !managementState.busy"))
        val service = Files.readString(Path.of("src/main/kotlin/com/example/chatbar/desktop/DesktopPresetSuiteRestoreService.kt"))
        assertFalse(service.contains("preset_import_state"))
        assertFalse(service.contains("seenVersions"))
        assertFalse(Files.exists(f.ledgerPath()))
    }

    @Test fun `management suite result is separate from ordinary recovery status`() = fixture { f ->
        val management = f.app.createManagementController(f.app.createTypedTransferController())
        management.restoreCompletePreset(character)
        assertEquals(DesktopPresetSuiteRestoreResult(true, 1, 1), management.state.value.suiteResult)
        assertNull(management.state.value.error)
        management.refresh()
        assertNotNull(management.state.value.suiteResult)
        assertFalse(Files.exists(f.ledgerPath()))
    }

    @Test fun `fresh service refuses corrupt preset Character instead of importing duplicate`() = fixture { f ->
        val card = f.card()
        val file = f.root.resolve("entities/character_cards/${card.id}.json")
        val original = Files.readAllBytes(file)
        Files.writeString(file, "{invalid")
        val fresh = f.freshContainer()
        try {
            assertFailsWith<Exception> { fresh.presetSuiteRestore.restore(character) }
            assertEquals(1, Files.list(file.parent).use { it.filter { path -> path.fileName.toString().endsWith(".json") }.count() })
            assertTrue(fresh.worldBookRepository.getAll().isEmpty())
        } finally { fresh.close(); Files.write(file, original) }
        val restored = f.freshContainer()
        try {
            assertEquals(DesktopPresetSuiteRestoreResult(false, 1, 1), restored.presetSuiteRestore.restore(character))
            assertEquals(card.id, restored.characterRepository.getAll().single().id)
        } finally { restored.close() }
    }

    @Test fun `fresh service refuses corrupt preset WorldBook instead of importing duplicate`() = fixture { f ->
        val book = f.book()
        val file = f.root.resolve("entities/world_books/${book.id}.json")
        val original = Files.readAllBytes(file)
        Files.writeString(file, "{invalid")
        val fresh = f.freshContainer()
        try {
            assertFailsWith<Exception> { fresh.presetSuiteRestore.restore(character) }
            assertEquals(1, Files.list(file.parent).use { it.filter { path -> path.fileName.toString().endsWith(".json") }.count() })
            assertTrue(fresh.characterRepository.getAll().isEmpty())
        } finally { fresh.close(); Files.write(file, original) }
        val restored = f.freshContainer()
        try {
            assertEquals(DesktopPresetSuiteRestoreResult(true, 0, 1), restored.presetSuiteRestore.restore(character))
            assertEquals(book.id, restored.worldBookRepository.getAll().single().id)
        } finally { restored.close() }
    }

    @Test fun `final WorldBook provenance mismatch prevents stale Character binding`() = fixture { f ->
        val card = f.card()
        val book = f.book()
        val file = f.root.resolve("entities/world_books/${book.id}.json")
        val service = f.service(beforeBinding = {
            Files.writeString(file, json.encodeToString(WorldBook.serializer(), book.copy(sourcePresetKey = "changed")))
        })
        assertFailsWith<Exception> { service.restore(character) }
        assertEquals(emptyList(), f.app.characterRepository.readDurable(card.id)
            .let { (it as JsonFileStorage.EntityReadResult.Valid).value.worldBookIds })
    }

    @Test fun `complete suite excludes a concurrent normal Character save across final binding`() = fixture { f -> kotlinx.coroutines.coroutineScope {
        val card = f.card()
        val book = f.book()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val service = f.service(saveBinding = { updated, committed ->
            entered.complete(Unit)
            release.await()
            f.app.characterRepository.saveObserved(updated, committed)
        })
        val restore = async { service.restore(character) }
        withTimeout(10_000) { entered.await() }
        assertEquals(DesktopDataOperationCoordinatorState.EXCLUSIVE, f.app.dataOperationCoordinator.state)
        val edit = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            val latest = requireNotNull(f.app.characterRepository.getById(card.id))
            f.app.characterRepository.save(latest.copy(name = "Concurrent edit"))
        }
        assertFalse(edit.isCompleted)
        release.complete(Unit)
        restore.await()
        edit.await()
        val latest = requireNotNull(f.app.characterRepository.getById(card.id))
        assertEquals("Concurrent edit", latest.name)
        assertEquals(listOf(book.id), latest.worldBookIds)
    } }

    @Test fun `strict ReadError blocks suite before any import`() = fixture { f ->
        val failure = assertFailsWith<DesktopPresetSuiteIndeterminateException> {
            f.service(scanCharacters = {
                listOf(JsonFileStorage.EntityFileRead("unreadable",
                    JsonFileStorage.EntityReadResult.ReadError(IOException("injected read error"))))
            }).restore(character)
        }
        assertTrue(failure.message!!.contains("unreadable"))
        assertTrue(f.app.characterRepository.getAll().isEmpty())
        assertTrue(f.app.worldBookRepository.getAll().isEmpty())
    }

    @Test fun `storage filename and decoded Character ID mismatch aborts before writes`() = fixture { f ->
        val card = f.card()
        val original = f.root.resolve("entities/character_cards/${card.id}.json")
        val wrong = original.parent.resolve("other-id.json")
        Files.move(original, wrong)
        assertTrue(assertFailsWith<IllegalStateException> { f.service().restore(character) }
            .message!!.contains("storage ID mismatch"))
        assertTrue(f.app.worldBookRepository.getAll().isEmpty())
    }

    @Test fun `storage filename and decoded WorldBook ID mismatch aborts before writes`() = fixture { f ->
        val book = f.book()
        val original = f.root.resolve("entities/world_books/${book.id}.json")
        val wrong = original.parent.resolve("other-id.json")
        Files.move(original, wrong)
        assertTrue(assertFailsWith<IllegalStateException> { f.service().restore(character) }
            .message!!.contains("storage ID mismatch"))
        assertTrue(f.app.characterRepository.getAll().isEmpty())
    }

    @Test fun `exclusive suite waits for already registered normal work to drain`() = fixture { f -> kotlinx.coroutines.coroutineScope {
        val normalEntered = CompletableDeferred<Unit>()
        val normalRelease = CompletableDeferred<Unit>()
        val suiteEntered = CompletableDeferred<Unit>()
        val normal = async {
            f.app.dataOperationCoordinator.withNormalOperation {
                normalEntered.complete(Unit)
                normalRelease.await()
            }
        }
        withTimeout(10_000) { normalEntered.await() }
        val suite = async { f.service(beforeWorld = { suiteEntered.complete(Unit) }).restore(character) }
        withTimeout(10_000) {
            while (f.app.dataOperationCoordinator.state != DesktopDataOperationCoordinatorState.MAINTENANCE_PENDING) yield()
        }
        assertFalse(suiteEntered.isCompleted)
        normalRelease.complete(Unit)
        normal.await()
        withTimeout(10_000) { suiteEntered.await() }
        suite.await()
        assertEquals(DesktopDataOperationCoordinatorState.OPEN, f.app.dataOperationCoordinator.state)
        assertFalse(f.app.dataOperationCoordinator.isRestartRequired)
    } }

    @Test fun `WorldBook delete and duplicate provenance write wait until suite binding commits`() = fixture { f -> kotlinx.coroutines.coroutineScope {
        f.card()
        val book = f.book()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var bindingCommitted = false
        val service = f.service(beforeBinding = { entered.complete(Unit); release.await() },
            afterBindingCommit = { bindingCommitted = true })
        val suite = async { service.restore(character) }
        withTimeout(10_000) { entered.await() }
        assertEquals(DesktopDataOperationCoordinatorState.EXCLUSIVE, f.app.dataOperationCoordinator.state)
        val deletion = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            f.app.worldBookRepository.delete(book.id)
        }
        val duplicate = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            f.app.worldBookRepository.save(book.copy(id = "duplicate-preset-world"))
        }
        assertFalse(deletion.isCompleted)
        assertFalse(duplicate.isCompleted)
        release.complete(Unit)
        suite.await()
        assertTrue(bindingCommitted)
        deletion.await()
        duplicate.await()
        assertEquals(DesktopDataOperationCoordinatorState.OPEN, f.app.dataOperationCoordinator.state)
        assertFalse(f.app.dataOperationCoordinator.isRestartRequired)
    } }

    @Test fun `exclusive releases after failure and after cancellation without restart requirement`() = fixture { f ->
        assertFailsWith<IllegalStateException> {
            f.service(beforeWorld = { error("injected precommit failure") }).restore(character)
        }
        assertEquals(DesktopDataOperationCoordinatorState.OPEN, f.app.dataOperationCoordinator.state)
        assertFalse(f.app.dataOperationCoordinator.isRestartRequired)
        val cancelled = CancellationException("cancel after durable WorldBook")
        assertSame(cancelled, assertFailsWith<CancellationException> {
            f.service(afterWorld = { throw cancelled }).restore(character)
        })
        assertEquals(DesktopDataOperationCoordinatorState.OPEN, f.app.dataOperationCoordinator.state)
        assertFalse(f.app.dataOperationCoordinator.isRestartRequired)
        f.app.worldBookRepository.save(WorldBook.create("normal write after cancel"))
    }

    @Test fun `shared Character transfer postcommit wrapper preserves original cancellation and stops binding`() = fixture { f ->
        val book = f.book()
        val cancelled = CancellationException("cancel in Character storage publication")
        var committedCallback = false
        val core = CharacterTransferPostCommitCancellationFixture.create(
            f.app.characterRepository, f.app.worldBookRepository, f.app.formatCardRepository,
            f.app.characterResourceStore, AuthoritativeCharacterTransferPromptPolicy,
            f.app.transferJson, f.app.dataOperationCoordinator, cancelled,
            afterDurableCommit = { committedCallback = true })
        assertSame(cancelled, assertFailsWith<CancellationException> {
            f.service(characterTransfers = core).restore(character)
        })
        assertTrue(committedCallback)
        val durable = f.app.characterRepository.getAll().single()
        assertEquals(character.presetKey, durable.sourcePresetKey)
        assertEquals(emptyList(), durable.worldBookIds)
        assertEquals(DesktopPresetSuiteRestoreResult(false, 0, 1), f.service().restore(character))
        assertEquals(listOf(durable.id), f.app.characterRepository.getAll().map { it.id })
        assertEquals(listOf(book.id), f.app.characterRepository.getById(durable.id)?.worldBookIds)
    }
}
