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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class DesktopPresetBootstrapTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `formal bundled manifest packages and imports all entity types without creating chat models`() = runBlocking {
        val reader = DesktopBundledAssetReader()
        val manifest = json.decodeFromString(PresetManifest.serializer(), reader("presets/manifest.json").decodeToString())
        assertEquals(listOf(PresetType.WORLD_BOOK, PresetType.CHARACTER, PresetType.CHARACTER,
            PresetType.FORMAT, PresetType.FORMAT, PresetType.FORMAT, PresetType.MODEL_CATALOG),
            manifest.entries.map { it.type })
        manifest.entries.forEach { reader(it.file) }
        val parent = Files.createTempDirectory("desktop-preset-baseline-")
        try {
            val first = container(parent)
            try {
                val source = first.presetSource
                source.entries(PresetType.CHARACTER).forEach(source::characterPackage)
                source.entries(PresetType.FORMAT).forEach(source::formatPackage)
                source.entries(PresetType.WORLD_BOOK).forEach(source::worldBookPackage)
                first.initializePersistentState()
                assertEquals(2, first.characterRepository.getAll().size)
                assertEquals(3, first.formatCardRepository.getAll().size)
                assertTrue(first.formatCardRepository.getAll().none { it.isDefault })
                assertEquals(1, first.worldBookRepository.getAll().size)
                assertTrue(first.modelRepository.getAllModels().isEmpty())
                val ledger = first.jsonFileStorage.loadSingleton("preset_import_state", PresetImportState.serializer())!!
                assertEquals(manifest.entries.associate { it.presetKey to it.version }, ledger.seenVersions)
                val world = first.worldBookRepository.getAll().single()
                val linked = first.characterRepository.getAll().single { it.sourcePresetKey == "MujicaMyGO" }
                assertEquals(listOf(world.id), linked.worldBookIds)
                assertEquals("mujica-mygo-world-book", world.sourcePresetKey)
                assertTrue(linked.customDocuments.all { it.filePath.startsWith("documents/") })
                assertTrue(linked.customDocuments.all { Files.isRegularFile(first.appDataRoot.resolve(it.filePath)) })
                val imageReferences = first.characterRepository.getAll().flatMap { card ->
                    listOfNotNull(card.avatar, card.chatBackground) + card.characters.mapNotNull { it.appearanceImage }
                }
                assertTrue(imageReferences.isNotEmpty())
                assertTrue(imageReferences.all { it.startsWith("images/") &&
                    Files.isRegularFile(first.appDataRoot.resolve(it)) })
            } finally { first.close() }
            val second = container(parent)
            try {
                val before = second.characterRepository.getAll().associateBy { it.id }
                second.initializePersistentState()
                assertEquals(before, second.characterRepository.getAll().associateBy { it.id })
                assertEquals(3, second.formatCardRepository.getAll().size)
                assertEquals(1, second.worldBookRepository.getAll().size)
            } finally { second.close() }
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
