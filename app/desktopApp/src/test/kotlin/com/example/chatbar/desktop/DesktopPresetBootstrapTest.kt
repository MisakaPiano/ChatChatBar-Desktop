package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.PresetEntry
import com.example.chatbar.data.local.entity.PresetImportState
import com.example.chatbar.data.local.entity.PresetManifest
import com.example.chatbar.data.local.entity.PresetType
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.card.CharacterCardPackage
import com.example.chatbar.domain.card.FormatCardPackage
import com.example.chatbar.domain.card.PackagedCharacterCard
import com.example.chatbar.domain.card.PackagedDocument
import com.example.chatbar.domain.card.WorldBookPackage
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.local.JsonFileStorage
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class DesktopPresetBootstrapTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `bundled manifest resources are reachable and decodable without fixed inventory assertions`() = runBlocking {
        val reader = DesktopBundledAssetReader()
        val manifest = json.decodeFromString(PresetManifest.serializer(), reader("presets/manifest.json").decodeToString())
        assertTrue(manifest.entries.isNotEmpty())
        val parent = Files.createTempDirectory("desktop-preset-baseline-")
        try {
            val first = container(parent)
            try {
                val source = first.presetSource
                assertEquals(manifest.entries, source.entries)
                manifest.entries.forEach { entry ->
                    assertTrue(reader(entry.file).isNotEmpty())
                    when (entry.type) {
                        PresetType.CHARACTER -> source.characterPackage(entry)
                        PresetType.FORMAT -> source.formatPackage(entry)
                        PresetType.WORLD_BOOK -> source.worldBookPackage(entry)
                        PresetType.MODEL_CATALOG -> first.presetModelCatalogSource.catalog
                    }
                }
            } finally { first.close() }
        } finally { parent.toFile().deleteRecursively() }
    }

    @Test
    fun `version advancement preserves user edits and missing ledger reuses source key`() = runBlocking {
        val parent = Files.createTempDirectory("desktop-preset-ledger-")
        val entry = PresetEntry("character-a", PresetType.CHARACTER, 1, "presets/characters/a.json", "A")
        try {
            val first = container(parent, fakeAssets(listOf(entry)))
            val id: String
            try {
                first.initializePersistentState()
                val card = first.characterRepository.getAll().single()
                id = card.id
                first.characterRepository.save(card.copy(name = "Local edit"))
            } finally { first.close() }
            val updated = container(parent, fakeAssets(listOf(entry.copy(version = 2))))
            try {
                updated.initializePersistentState()
                assertEquals(listOf(id), updated.characterRepository.getAll().map { it.id })
                assertEquals("Local edit", updated.characterRepository.getAll().single().name)
                assertEquals(1, updated.characterRepository.getAll().single().sourcePresetVersion)
                assertEquals(2, updated.jsonFileStorage.loadSingleton("preset_import_state",
                    PresetImportState.serializer())!!.seenVersions[entry.presetKey])
                updated.jsonFileStorage.deleteSingleton("preset_import_state")
            } finally { updated.close() }
            val recovered = container(parent, fakeAssets(listOf(entry.copy(version = 2))))
            try {
                recovered.initializePersistentState()
                assertEquals(listOf(id), recovered.characterRepository.getAll().map { it.id })
                assertEquals("Local edit", recovered.characterRepository.getAll().single().name)
            } finally { recovered.close() }
        } finally { parent.toFile().deleteRecursively() }
    }

    @Test
    fun `failed unseen import remains unseen while later entries import and next startup retries`() = runBlocking {
        val parent = Files.createTempDirectory("desktop-preset-retry-")
        val broken = PresetEntry("broken", PresetType.FORMAT, 1, "presets/formats/broken.json", "Broken")
        val later = PresetEntry("later", PresetType.FORMAT, 1, "presets/formats/later.json", "Later")
        try {
            val files = fakeAssetMap(listOf(broken, later)).toMutableMap()
            files.remove(broken.file)
            val first = container(parent) { files.getValue(it) }
            try {
                first.initializePersistentState()
                assertEquals(listOf("later"), first.formatCardRepository.getAll().mapNotNull { it.sourcePresetKey })
                val seen = first.jsonFileStorage.loadSingleton("preset_import_state", PresetImportState.serializer())!!.seenVersions
                assertFalse("broken" in seen)
                assertEquals(1, seen["later"])
            } finally { first.close() }
            files[broken.file] = json.encodeToString(FormatCardPackage(name = "Broken", content = "Recovered")).encodeToByteArray()
            val second = container(parent) { files.getValue(it) }
            try {
                second.initializePersistentState()
                assertEquals(setOf("broken", "later"), second.formatCardRepository.getAll().mapNotNull { it.sourcePresetKey }.toSet())
            } finally { second.close() }
        } finally { parent.toFile().deleteRecursively() }
    }

    @Test
    fun `missing character ledger reconciles world book binding without duplicating committed card`() = runBlocking {
        val parent = Files.createTempDirectory("desktop-preset-reconcile-")
        val world = PresetEntry("world-a", PresetType.WORLD_BOOK, 1, "presets/world_books/a.json", "World")
        val character = PresetEntry("character-a", PresetType.CHARACTER, 1,
            "presets/characters/a.json", "Character", worldBookPresetKeys = listOf(world.presetKey))
        try {
            val app = container(parent, fakeAssets(listOf(world, character)))
            try {
                val existingWorld = WorldBook.create("World").copy(sourcePresetKey = world.presetKey,
                    sourcePresetVersion = 1)
                app.worldBookRepository.save(existingWorld)
                val committed = CharacterCard.create("User's edited preset").copy(
                    sourcePresetKey = character.presetKey, sourcePresetVersion = 1)
                app.characterRepository.save(committed)
                app.initializePersistentState()
                assertEquals(listOf(committed.id), app.characterRepository.getAll().map { it.id })
                assertEquals(listOf(existingWorld.id), app.characterRepository.getAll().single().worldBookIds)
                assertEquals("User's edited preset", app.characterRepository.getAll().single().name)
                assertEquals(2, app.jsonFileStorage.loadSingleton("preset_import_state",
                    PresetImportState.serializer())!!.seenVersions.size)
            } finally { app.close() }
        } finally { parent.toFile().deleteRecursively() }
    }

    @Test
    fun `post-commit character binding failure retries linkage by durable source key without duplicate`() = runBlocking {
        val parent = Files.createTempDirectory("desktop-preset-committed-")
        val world = PresetEntry("world-a", PresetType.WORLD_BOOK, 1, "presets/world_books/a.json", "World")
        val character = PresetEntry("character-a", PresetType.CHARACTER, 1,
            "presets/characters/a.json", "Character", worldBookPresetKeys = listOf(world.presetKey))
        val assets = fakeAssets(listOf(world, character))
        try {
            val first = container(parent, assets)
            val committedId: String
            try {
                val interrupted = DesktopPresetBootstrap(first.presetSource, first.jsonFileStorage,
                    first.characterRepository, first.formatCardRepository, first.worldBookRepository,
                    first.characterTransfers, first.formatTransfers, first.worldBookTransfers,
                    first.characterResourceStore, beforeCharacterWorldBookBinding = { error("injected after commit") })
                interrupted.initialize()
                committedId = first.characterRepository.getAll().single().id
                assertTrue(first.characterRepository.getAll().single().worldBookIds.isEmpty())
                assertFalse(character.presetKey in first.jsonFileStorage.loadSingleton("preset_import_state",
                    PresetImportState.serializer())!!.seenVersions)
                assertTrue(interrupted.failures.any { it.contains(character.presetKey) })
            } finally { first.close() }
            val second = container(parent, assets)
            try {
                second.initializePersistentState()
                assertEquals(listOf(committedId), second.characterRepository.getAll().map { it.id })
                assertEquals(listOf(second.worldBookRepository.getAll().single().id),
                    second.characterRepository.getAll().single().worldBookIds)
                assertEquals(character.version, second.jsonFileStorage.loadSingleton("preset_import_state",
                    PresetImportState.serializer())!!.seenVersions[character.presetKey])
            } finally { second.close() }
        } finally { parent.toFile().deleteRecursively() }
    }

    @Test
    fun `missing bundled world book leaves committed character unseen until binding can complete`() = runBlocking {
        val parent = Files.createTempDirectory("desktop-preset-world-retry-")
        val world = PresetEntry("world-a", PresetType.WORLD_BOOK, 1, "presets/world_books/a.json", "World")
        val character = PresetEntry("character-a", PresetType.CHARACTER, 1,
            "presets/characters/a.json", "Character", worldBookPresetKeys = listOf(world.presetKey))
        val files = fakeAssetMap(listOf(world, character)).toMutableMap()
        files.remove(world.file)
        try {
            val first = container(parent) { files.getValue(it) }
            val id: String
            try {
                first.initializePersistentState()
                id = first.characterRepository.getAll().single().id
                assertTrue(first.characterRepository.getAll().single().worldBookIds.isEmpty())
                val ledger = first.jsonFileStorage.loadSingleton("preset_import_state", PresetImportState.serializer())
                assertTrue(ledger == null || character.presetKey !in ledger.seenVersions)
            } finally { first.close() }
            files[world.file] = json.encodeToString(WorldBookPackage(book = WorldBook.create("World"))).encodeToByteArray()
            val second = container(parent) { files.getValue(it) }
            try {
                second.initializePersistentState()
                assertEquals(listOf(id), second.characterRepository.getAll().map { it.id })
                assertEquals(listOf(second.worldBookRepository.getAll().single().id),
                    second.characterRepository.getAll().single().worldBookIds)
            } finally { second.close() }
        } finally { parent.toFile().deleteRecursively() }
    }

    @Test
    fun `partial manifest binding uses existing subset and missing unimportable key is not permanent failure`() = runBlocking {
        val parent = Files.createTempDirectory("desktop-preset-partial-binding-")
        val world = PresetEntry("world-a", PresetType.WORLD_BOOK, 1, "presets/world_books/a.json", "World")
        val character = PresetEntry("character-a", PresetType.CHARACTER, 1,
            "presets/characters/a.json", "Character", worldBookPresetKeys = listOf(world.presetKey, "unlisted"))
        try {
            val app = container(parent, fakeAssets(listOf(world, character)))
            val id: String
            try {
                app.initializePersistentState()
                id = app.characterRepository.getAll().single().id
                assertEquals(listOf(app.worldBookRepository.getAll().single().id),
                    app.characterRepository.getAll().single().worldBookIds)
                assertEquals(1, app.jsonFileStorage.loadSingleton("preset_import_state",
                    PresetImportState.serializer())!!.seenVersions[character.presetKey])
                app.worldBookRepository.delete(app.worldBookRepository.getAll().single().id)
            } finally { app.close() }
            val restarted = container(parent, fakeAssets(listOf(world, character)))
            try {
                restarted.initializePersistentState()
                assertTrue(restarted.worldBookRepository.getAll().isEmpty())
                assertEquals(listOf(id), restarted.characterRepository.getAll().map { it.id })
            } finally { restarted.close() }
        } finally { parent.toFile().deleteRecursively() }
    }

    @Test
    fun `missing bundled document is rematerialized without touching valid documents or images`() = runBlocking {
        val parent = Files.createTempDirectory("desktop-preset-document-")
        val entry = PresetEntry("character-a", PresetType.CHARACTER, 1, "presets/characters/a.json", "Character")
        val packageData = CharacterCardPackage(card = PackagedCharacterCard(name = "Character"), documents = listOf(
            PackagedDocument("lost.txt", "text/plain", "Recover me"),
            PackagedDocument("keep.txt", "text/plain", "Keep me"),
        ))
        val files = fakeAssetMap(listOf(entry)).toMutableMap()
        files[entry.file] = json.encodeToString(packageData).encodeToByteArray()
        try {
            val first = container(parent) { files.getValue(it) }
            val original: CharacterCard
            try {
                first.initializePersistentState()
                original = first.characterRepository.getAll().single()
                val lost = original.customDocuments.single { it.fileName == "lost.txt" }
                Files.delete(first.appDataRoot.resolve(lost.filePath))
            } finally { first.close() }
            val second = container(parent) { files.getValue(it) }
            try {
                second.initializePersistentState()
                val repaired = second.characterRepository.getAll().single()
                val oldLost = original.customDocuments.single { it.fileName == "lost.txt" }
                val newLost = repaired.customDocuments.single { it.fileName == "lost.txt" }
                val kept = original.customDocuments.single { it.fileName == "keep.txt" }
                assertTrue(newLost.filePath.startsWith("documents/"))
                assertTrue(newLost.filePath != oldLost.filePath)
                assertEquals("Recover me", Files.readString(second.appDataRoot.resolve(newLost.filePath)))
                assertEquals(kept, repaired.customDocuments.single { it.fileName == "keep.txt" })
                assertEquals(original.avatar, repaired.avatar)
                assertEquals(original.chatBackground, repaired.chatBackground)
                assertEquals("PENDING", newLost.ragStatus)
                assertEquals("NOT_INDEXED", repaired.ragIndexStatus)
            } finally { second.close() }
        } finally { parent.toFile().deleteRecursively() }
    }

    @Test
    fun `ledger preserves unknown envelope fields and unrelated seen keys without needless rewrite`() = runBlocking {
        val parent = Files.createTempDirectory("desktop-preset-envelope-")
        val entry = PresetEntry("format-a", PresetType.FORMAT, 2, "presets/formats/a.json", "Format")
        try {
            val app = container(parent, fakeAssets(listOf(entry)))
            try {
                val envelope = json.parseToJsonElement("""{"seenVersions":{"format-a":1,"other":8},"future":{"keep":[1,2]}}""").jsonObject
                app.jsonFileStorage.saveSingleton("preset_import_state", envelope, JsonObject.serializer())
                val file = app.appDataRoot.resolve("entities/preset_import_state.json")
                app.initializePersistentState()
                val updated = app.jsonFileStorage.loadSingleton("preset_import_state", JsonObject.serializer())!!
                assertEquals(envelope["future"], updated["future"])
                assertEquals(8, json.decodeFromJsonElement(PresetImportState.serializer(), updated).seenVersions["other"])
                assertEquals(2, json.decodeFromJsonElement(PresetImportState.serializer(), updated).seenVersions[entry.presetKey])
                val bytes = Files.readAllBytes(file)
                val second = DesktopPresetBootstrap(app.presetSource, app.jsonFileStorage,
                    app.characterRepository, app.formatCardRepository, app.worldBookRepository,
                    app.characterTransfers, app.formatTransfers, app.worldBookTransfers, app.characterResourceStore)
                second.initialize()
                assertTrue(bytes.contentEquals(Files.readAllBytes(file)))
            } finally { app.close() }
        } finally { parent.toFile().deleteRecursively() }
    }

    @Test
    fun `corrupt ledger is not overwritten by default`() = runBlocking {
        val parent = Files.createTempDirectory("desktop-preset-corrupt-")
        try {
            val app = container(parent, fakeAssets(emptyList()))
            try {
                val file = app.appDataRoot.resolve("entities/preset_import_state.json")
                Files.createDirectories(file.parent)
                Files.writeString(file, "{broken")
                assertFailsWith<JsonFileStorage.SingletonReadException> { app.presetBootstrap.initialize() }
                assertEquals("{broken", Files.readString(file))
            } finally { app.close() }
        } finally { parent.toFile().deleteRecursively() }
    }

    @Test
    fun `document repair keeps durable committed resource after cancellation and rolls back only precommit`() = runBlocking {
        val parent = Files.createTempDirectory("desktop-preset-doc-commit-")
        val entry = PresetEntry("character-a", PresetType.CHARACTER, 1, "presets/characters/a.json", "Character")
        val files = fakeAssetMap(listOf(entry)).toMutableMap()
        files[entry.file] = json.encodeToString(CharacterCardPackage(
            card = PackagedCharacterCard(name = "Character"),
            documents = listOf(PackagedDocument("doc.txt", "text/plain", "bundled")),
        )).encodeToByteArray()
        try {
            val app = container(parent) { files.getValue(it) }
            try {
                app.initializePersistentState()
                val original = app.characterRepository.getAll().single()
                Files.delete(app.appDataRoot.resolve(original.customDocuments.single().filePath))
                val committed = DesktopPresetBootstrap(app.presetSource, app.jsonFileStorage,
                    app.characterRepository, app.formatCardRepository, app.worldBookRepository,
                    app.characterTransfers, app.formatTransfers, app.worldBookTransfers,
                    app.characterResourceStore, operationGate = app.dataOperationCoordinator,
                    saveRepairedCharacter = { card, callback ->
                        app.characterRepository.saveObserved(card) {
                            callback()
                            throw CancellationException("after durable rename, before cache refresh")
                        }
                    })
                assertFailsWith<CancellationException> { committed.repairCharacterDocuments(original) }
                val durable = app.characterRepository.readDurable(original.id) as JsonFileStorage.EntityReadResult.Valid
                val newPath = durable.value.customDocuments.single().filePath
                assertNotEquals(original.customDocuments.single().filePath, newPath)
                assertTrue(Files.isRegularFile(app.appDataRoot.resolve(newPath)))

                Files.delete(app.appDataRoot.resolve(newPath))
                val before = Files.list(app.appDataRoot.resolve("documents")).use { it.count() }
                val precommit = DesktopPresetBootstrap(app.presetSource, app.jsonFileStorage,
                    app.characterRepository, app.formatCardRepository, app.worldBookRepository,
                    app.characterTransfers, app.formatTransfers, app.worldBookTransfers,
                    app.characterResourceStore, operationGate = app.dataOperationCoordinator,
                    saveRepairedCharacter = { _, _ -> throw IOException("before durable save") })
                assertFailsWith<IOException> { precommit.repairCharacterDocuments(durable.value) }
                assertEquals(before, Files.list(app.appDataRoot.resolve("documents")).use { it.count() })
            } finally { app.close() }
        } finally { parent.toFile().deleteRecursively() }
    }

    @Test
    fun `document repair retains new resource when exact durable state is indeterminate`() = runBlocking {
        val parent = Files.createTempDirectory("desktop-preset-doc-unknown-")
        val entry = PresetEntry("character-a", PresetType.CHARACTER, 1, "presets/characters/a.json", "Character")
        val files = fakeAssetMap(listOf(entry)).toMutableMap()
        files[entry.file] = json.encodeToString(CharacterCardPackage(card = PackagedCharacterCard(name = "Character"),
            documents = listOf(PackagedDocument("doc.txt", "text/plain", "bundled")))).encodeToByteArray()
        try {
            val app = container(parent) { files.getValue(it) }
            try {
                app.initializePersistentState()
                val original = app.characterRepository.getAll().single()
                Files.delete(app.appDataRoot.resolve(original.customDocuments.single().filePath))
                val unknown = DesktopPresetBootstrap(app.presetSource, app.jsonFileStorage,
                    app.characterRepository, app.formatCardRepository, app.worldBookRepository,
                    app.characterTransfers, app.formatTransfers, app.worldBookTransfers,
                    app.characterResourceStore, operationGate = app.dataOperationCoordinator,
                    saveRepairedCharacter = { _, _ -> throw IOException("save uncertain") },
                    readRepairedCharacter = { JsonFileStorage.EntityReadResult.ReadError(IOException("read denied")) })
                assertFailsWith<IOException> { unknown.repairCharacterDocuments(original) }
                assertEquals(1, Files.list(app.appDataRoot.resolve("documents")).use { it.count() })
            } finally { app.close() }
        } finally { parent.toFile().deleteRecursively() }
    }

    @Test
    fun `unreadable existing document and removed document row are not rematerialized`() = runBlocking {
        val parent = Files.createTempDirectory("desktop-preset-doc-unreadable-")
        val entry = PresetEntry("character-a", PresetType.CHARACTER, 1, "presets/characters/a.json", "Character")
        val files = fakeAssetMap(listOf(entry)).toMutableMap()
        files[entry.file] = json.encodeToString(CharacterCardPackage(card = PackagedCharacterCard(name = "Character"),
            documents = listOf(PackagedDocument("doc.txt", "text/plain", "bundled")))).encodeToByteArray()
        try {
            val app = container(parent) { files.getValue(it) }
            try {
                app.initializePersistentState()
                val original = app.characterRepository.getAll().single()
                val file = app.appDataRoot.resolve(original.customDocuments.single().filePath)
                Files.write(file, byteArrayOf(0xC3.toByte(), 0x28))
                val repair = app.presetBootstrap
                assertFailsWith<IOException> { repair.repairCharacterDocuments(original) }
                assertTrue(byteArrayOf(0xC3.toByte(), 0x28).contentEquals(Files.readAllBytes(file)))
                val readFailure = DesktopPresetBootstrap(app.presetSource, app.jsonFileStorage,
                    app.characterRepository, app.formatCardRepository, app.worldBookRepository,
                    app.characterTransfers, app.formatTransfers, app.worldBookTransfers,
                    app.characterResourceStore,
                    probeDocument = { DesktopDocumentProbe.ReadError(IOException("attribute read denied")) })
                assertFailsWith<IOException> { readFailure.repairCharacterDocuments(original) }
                val unsafe = DesktopPresetBootstrap(app.presetSource, app.jsonFileStorage,
                    app.characterRepository, app.formatCardRepository, app.worldBookRepository,
                    app.characterTransfers, app.formatTransfers, app.worldBookTransfers,
                    app.characterResourceStore,
                    probeDocument = { DesktopDocumentProbe.Unsafe(IllegalArgumentException("traversal")) })
                assertFailsWith<IOException> { unsafe.repairCharacterDocuments(original) }
                val removed = original.copy(customDocuments = emptyList())
                app.characterRepository.save(removed)
                assertEquals(removed, repair.repairCharacterDocuments(removed))
                assertTrue(app.characterRepository.readDurable(removed.id) is JsonFileStorage.EntityReadResult.Valid)
                assertTrue(app.characterRepository.getAll().single().customDocuments.isEmpty())
            } finally { app.close() }
        } finally { parent.toFile().deleteRecursively() }
    }

    @Test
    fun `exclusive maintenance waits for document repair commit and gate releases`() = runBlocking {
        val parent = Files.createTempDirectory("desktop-preset-doc-gate-")
        val entry = PresetEntry("character-a", PresetType.CHARACTER, 1, "presets/characters/a.json", "Character")
        val files = fakeAssetMap(listOf(entry)).toMutableMap()
        files[entry.file] = json.encodeToString(CharacterCardPackage(card = PackagedCharacterCard(name = "Character"),
            documents = listOf(PackagedDocument("doc.txt", "text/plain", "bundled")))).encodeToByteArray()
        try {
            val app = container(parent) { files.getValue(it) }
            try {
                app.initializePersistentState()
                val original = app.characterRepository.getAll().single()
                Files.delete(app.appDataRoot.resolve(original.customDocuments.single().filePath))
                val saving = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val exclusiveEntered = CompletableDeferred<Unit>()
                val repair = DesktopPresetBootstrap(app.presetSource, app.jsonFileStorage,
                    app.characterRepository, app.formatCardRepository, app.worldBookRepository,
                    app.characterTransfers, app.formatTransfers, app.worldBookTransfers,
                    app.characterResourceStore, operationGate = app.dataOperationCoordinator,
                    saveRepairedCharacter = { card, callback ->
                        saving.complete(Unit)
                        release.await()
                        app.characterRepository.saveObserved(card, callback)
                    })
                val repairJob = launch { repair.repairCharacterDocuments(original) }
                saving.await()
                val exclusiveJob = launch(start = CoroutineStart.UNDISPATCHED) {
                    app.dataOperationCoordinator.withExclusiveMaintenance { exclusiveEntered.complete(Unit) }
                }
                assertFalse(exclusiveEntered.isCompleted)
                release.complete(Unit)
                repairJob.join()
                exclusiveEntered.await()
                exclusiveJob.join()
                val durable = app.characterRepository.readDurable(original.id) as JsonFileStorage.EntityReadResult.Valid
                assertTrue(Files.isRegularFile(app.appDataRoot.resolve(durable.value.customDocuments.single().filePath)))
                Files.delete(app.appDataRoot.resolve(durable.value.customDocuments.single().filePath))
                val savingAgain = CompletableDeferred<Unit>()
                val neverRelease = CompletableDeferred<Unit>()
                val afterCancelExclusive = CompletableDeferred<Unit>()
                val cancellable = DesktopPresetBootstrap(app.presetSource, app.jsonFileStorage,
                    app.characterRepository, app.formatCardRepository, app.worldBookRepository,
                    app.characterTransfers, app.formatTransfers, app.worldBookTransfers,
                    app.characterResourceStore, operationGate = app.dataOperationCoordinator,
                    saveRepairedCharacter = { _, _ -> savingAgain.complete(Unit); neverRelease.await() })
                val cancelledJob = launch { cancellable.repairCharacterDocuments(durable.value) }
                savingAgain.await()
                val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
                    app.dataOperationCoordinator.withExclusiveMaintenance {
                        assertEquals(0, Files.list(app.appDataRoot.resolve("documents")).use { it.count() })
                        afterCancelExclusive.complete(Unit)
                    }
                }
                assertFalse(afterCancelExclusive.isCompleted)
                cancelledJob.cancel()
                cancelledJob.join()
                afterCancelExclusive.await()
                waiter.join()
            } finally { app.close() }
        } finally { parent.toFile().deleteRecursively() }
    }

    private fun container(parent: Path, assets: (String) -> ByteArray = DesktopBundledAssetReader()) =
        DesktopAppContainer(DesktopDataRootResolution.Resolved(parent.resolve("data"),
            DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = assets)

    private fun fakeAssets(entries: List<PresetEntry>): (String) -> ByteArray {
        val files = fakeAssetMap(entries)
        return { files.getValue(it) }
    }

    private fun fakeAssetMap(entries: List<PresetEntry>): Map<String, ByteArray> = buildMap {
        put("presets/manifest.json", json.encodeToString(PresetManifest(entries)).encodeToByteArray())
        entries.forEach { entry ->
            val value = when (entry.type) {
                PresetType.CHARACTER -> json.encodeToString(CharacterCardPackage(card = PackagedCharacterCard(name = entry.displayName)))
                PresetType.FORMAT -> json.encodeToString(FormatCardPackage(name = entry.displayName, content = "Bundled"))
                PresetType.WORLD_BOOK -> json.encodeToString(WorldBookPackage(book = WorldBook.create(entry.displayName)))
                PresetType.MODEL_CATALOG -> "{}"
            }
            put(entry.file, value.encodeToByteArray())
        }
    }
}
