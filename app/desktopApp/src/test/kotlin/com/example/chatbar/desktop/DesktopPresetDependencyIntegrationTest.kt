package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.card.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class DesktopPresetDependencyIntegrationTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val character = PresetEntry("character", PresetType.CHARACTER, 1, "character.json", "Synthetic character",
        worldBookPresetKeys = listOf("world"), defaultFormatPresetKey = "format")
    private val world = PresetEntry("world", PresetType.WORLD_BOOK, 2, "world.json", "Synthetic world")
    private val format = PresetEntry("format", PresetType.FORMAT, 3, "format.json", "Synthetic format")
    private fun assets(includeFormat: Boolean = true) = mapOf(
        "presets/manifest.json" to json.encodeToString(PresetManifest(listOf(character, world) + if (includeFormat) listOf(format) else emptyList())),
        character.file to json.encodeToString(CharacterCardPackage(card = PackagedCharacterCard(character.displayName))),
        world.file to json.encodeToString(WorldBookPackage(book = WorldBook("bundled-world", world.displayName))),
        format.file to json.encodeToString(FormatCardPackage(name = format.displayName, content = "synthetic format body")),
    ).mapValues { it.value.encodeToByteArray() }
    private fun createApp(parent: Path, files: Map<String, ByteArray>) = DesktopAppContainer(
        DesktopDataRootResolution.Resolved(parent.resolve("data"), DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")),
        secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { files.getValue(it) })
    private fun suite(app: DesktopAppContainer) = DesktopPresetSuiteRestoreService(app.presetSource,
        app.characterRepository, app.worldBookRepository, app.characterTransfers, app.worldBookTransfers, app.dataOperationCoordinator)

    @Test fun `bootstrap loads declared format and reuses edited world while preserving format conflicts across reopen`() = runBlocking {
        val parent = Files.createTempDirectory("preset-dependency-bootstrap-")
        val files = assets()
        var app = createApp(parent, files)
        try {
            val existingWorld = WorldBook("local-world", world.displayName, description = "local edit",
                sourcePresetKey = world.presetKey, sourcePresetVersion = 1)
            val existingFormat = FormatCard("local-format", format.displayName, "local custom content", createdAt = 1)
            app.worldBookRepository.save(existingWorld); app.formatCardRepository.save(existingFormat)
            val savedWorld = app.worldBookRepository.getAll().single()
            val worldPath = parent.resolve("data/entities/world_books/${savedWorld.id}.json")
            val worldBytes = Files.readAllBytes(worldPath)
            val packaged = app.presetSource.characterPackage(character)
            assertEquals("world", packaged.worldBooks.single().sourcePresetKey)
            assertEquals("format", packaged.defaultFormatCard!!.sourcePresetKey)
            app.initializePersistentState()
            val card = app.characterRepository.getAll().single()
            val importedFormat = app.formatCardRepository.getAll().single { it.id == card.defaultFormatCardId }
            assertEquals(listOf(existingWorld.id), card.worldBookIds)
            assertEquals(savedWorld, app.worldBookRepository.getAll().single())
            assertContentEquals(worldBytes, Files.readAllBytes(worldPath))
            assertEquals("format", importedFormat.sourcePresetKey)
            assertEquals(3, importedFormat.sourcePresetVersion)
            assertEquals(existingFormat, app.formatCardRepository.getAll().single { it.id == existingFormat.id })
            assertEquals(2, app.formatCardRepository.getAll().size)
            app.close(); app = createApp(parent, files)
            app.initializePersistentState()
            assertEquals(card.id, app.characterRepository.getAll().single().id)
            assertEquals(importedFormat.id, app.characterRepository.getAll().single().defaultFormatCardId)
            assertEquals(2, app.formatCardRepository.getAll().size)
            assertEquals(savedWorld, app.worldBookRepository.getAll().single())
            assertContentEquals(worldBytes, Files.readAllBytes(worldPath))
        } finally { app.close(); parent.toFile().deleteRecursively() }
    }

    @Test fun `typed recovery and complete suite use the same resolved dependencies without duplicate members on retry`() = runBlocking {
        for (complete in listOf(false, true)) {
            val parent = Files.createTempDirectory("preset-dependency-ingress-")
            val app = createApp(parent, assets())
            try {
                val controller = app.createTypedTransferController()
                if (complete) suite(app).restore(character) else controller.recoverPreset(character)
                val card = app.characterRepository.getAll().single()
                val importedWorld = app.worldBookRepository.getAll().single()
                val importedFormat = app.formatCardRepository.getAll().single()
                assertEquals(listOf(importedWorld.id), card.worldBookIds)
                assertEquals(importedFormat.id, card.defaultFormatCardId)
                assertEquals("world", importedWorld.sourcePresetKey)
                assertEquals("format", importedFormat.sourcePresetKey)
                if (complete) suite(app).restore(character) else {
                    controller.recoverPreset(character)
                    assertNotNull(controller.state.value.pendingConflict)
                    controller.resolveConflict(DesktopTransferConflictAction.CANCEL)
                }
                assertEquals(listOf(card), app.characterRepository.getAll())
                assertEquals(listOf(importedWorld), app.worldBookRepository.getAll())
                assertEquals(listOf(importedFormat), app.formatCardRepository.getAll())
            } finally { app.close(); parent.toFile().deleteRecursively() }
        }
    }

    @Test fun `missing declared format refuses source typed recovery and suite before any member is written`() = runBlocking {
        val parent = Files.createTempDirectory("preset-dependency-missing-")
        val app = createApp(parent, assets(includeFormat = false))
        try {
            assertFailsWith<IllegalArgumentException> { app.presetSource.characterPackage(character) }
            assertFailsWith<IllegalArgumentException> { suite(app).restore(character) }
            val controller = app.createTypedTransferController()
            controller.recoverPreset(character)
            assertNotNull(controller.state.value.error)
            assertTrue(app.characterRepository.getAll().isEmpty())
            assertTrue(app.worldBookRepository.getAll().isEmpty())
            assertTrue(app.formatCardRepository.getAll().isEmpty())
        } finally { app.close(); parent.toFile().deleteRecursively() }
    }
}
