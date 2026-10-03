package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.PresetEntry
import com.example.chatbar.data.local.entity.PresetManifest
import com.example.chatbar.data.local.entity.PresetType
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.card.CharacterCardPackage
import com.example.chatbar.domain.card.FormatCardPackage
import com.example.chatbar.domain.card.PackagedCharacterCard
import com.example.chatbar.domain.card.WorldBookPackage
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class DesktopPresetRecoveryTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `manual format and world book recovery use typed conflict and retain preset metadata`() = runBlocking {
        val parent = Files.createTempDirectory("desktop-preset-manual-")
        val format = PresetEntry("format-a", PresetType.FORMAT, 2, "presets/formats/a.json", "Format")
        val world = PresetEntry("world-a", PresetType.WORLD_BOOK, 3, "presets/world_books/a.json", "World")
        val app = container(parent, listOf(format, world))
        try {
            val transfer = app.createTypedTransferController()
            transfer.recoverPreset(format)
            val importedFormat = app.formatCardRepository.getAll().single()
            assertEquals(format.presetKey, importedFormat.sourcePresetKey)
            assertEquals(format.version, importedFormat.sourcePresetVersion)
            transfer.recoverPreset(format)
            assertTrue(transfer.state.value.pendingConflict is DesktopPendingTransferConflict.Format)
            transfer.resolveConflict(DesktopTransferConflictAction.CANCEL)
            assertNull(transfer.state.value.pendingConflict)
            assertEquals(1, app.formatCardRepository.getAll().size)
            transfer.recoverPreset(format)
            transfer.resolveConflict(DesktopTransferConflictAction.OVERWRITE)
            assertEquals(importedFormat.id, app.formatCardRepository.getAll().single().id)
            transfer.recoverPreset(format)
            transfer.resolveConflict(DesktopTransferConflictAction.IMPORT_AS_NEW)
            assertEquals(2, app.formatCardRepository.getAll().size)
            assertTrue(app.formatCardRepository.getAll().all { it.sourcePresetKey == format.presetKey })

            transfer.recoverPreset(world)
            val importedWorld = app.worldBookRepository.getAll().single()
            assertEquals(world.presetKey, importedWorld.sourcePresetKey)
            assertEquals(world.version, importedWorld.sourcePresetVersion)
            transfer.recoverPreset(world)
            assertTrue(transfer.state.value.pendingConflict is DesktopPendingTransferConflict.WorldBookConflict)
            transfer.resolveConflict(DesktopTransferConflictAction.OVERWRITE)
            assertEquals(importedWorld.id, app.worldBookRepository.getAll().single().id)
            transfer.recoverPreset(world)
            transfer.resolveConflict(DesktopTransferConflictAction.IMPORT_AS_NEW)
            assertEquals(2, app.worldBookRepository.getAll().size)
            assertTrue(app.worldBookRepository.getAll().all { it.sourcePresetKey == world.presetKey })
        } finally { app.close(); parent.toFile().deleteRecursively() }
    }

    @Test
    fun `manual character conflict blocks community overwrite and committed failure cannot replay`() = runBlocking {
        val parent = Files.createTempDirectory("desktop-preset-character-")
        val entry = PresetEntry("character-a", PresetType.CHARACTER, 1, "presets/characters/a.json", "Character")
        val app = container(parent, listOf(entry))
        try {
            val community = CharacterCard.create("Character").copy(communityItemId = "community-id")
            app.characterRepository.save(community)
            val transfer = app.createTypedTransferController()
            transfer.recoverPreset(entry)
            val conflict = transfer.state.value.pendingConflict as DesktopPendingTransferConflict.Character
            assertFalse(conflict.overwriteAllowed)
            transfer.resolveConflict(DesktopTransferConflictAction.CANCEL)
            transfer.recoverPreset(entry)
            transfer.resolveConflict(DesktopTransferConflictAction.IMPORT_AS_NEW)
            assertEquals(2, app.characterRepository.getAll().size)
            val preset = app.characterRepository.getAll().single { it.sourcePresetKey == entry.presetKey }
            assertEquals(entry.version, preset.sourcePresetVersion)
            assertEquals("community-id", app.characterRepository.getById(community.id)?.communityItemId)

            val failing = app.createTypedTransferController(afterCharacterCommit = { _, _ -> error("post-commit status failed") })
            failing.recoverPreset(entry)
            assertNotNull(failing.state.value.pendingConflict)
            failing.resolveConflict(DesktopTransferConflictAction.IMPORT_AS_NEW)
            assertNull(failing.state.value.pendingConflict)
            assertNotNull(failing.state.value.committedNotice)
            assertEquals(3, app.characterRepository.getAll().size)
            failing.resolveConflict(DesktopTransferConflictAction.IMPORT_AS_NEW)
            assertEquals(3, app.characterRepository.getAll().size)
        } finally { app.close(); parent.toFile().deleteRecursively() }
    }

    @Test
    fun `preset presentation only matches source key and version`() {
        val entry = PresetEntry("character-a", PresetType.CHARACTER, 3, "a.json", "Same name")
        assertEquals(DesktopPresetAvailability.RECOVERABLE,
            desktopManagementPresetRows(listOf(entry), listOf(null to null)).single().availability)
        assertEquals(DesktopPresetAvailability.UPDATE_AVAILABLE,
            desktopManagementPresetRows(listOf(entry), listOf(entry.presetKey to 2)).single().availability)
        assertEquals(DesktopPresetAvailability.PRESENT,
            desktopManagementPresetRows(listOf(entry), listOf(entry.presetKey to 3)).single().availability)
        assertEquals(DesktopUiText.RECOVER_PRESET_CHARACTER, DesktopTransferKind.CHARACTER.presetSectionTitle())
        assertEquals(DesktopUiText.BUILT_IN_FORMATS, DesktopTransferKind.FORMAT.presetSectionTitle())
        assertEquals(DesktopUiText.RECOVER_PRESET_WORLD_BOOK, DesktopTransferKind.WORLD_BOOK.presetSectionTitle())
    }

    private fun container(parent: java.nio.file.Path, entries: List<PresetEntry>): DesktopAppContainer {
        val files = mutableMapOf<String, ByteArray>(
            "presets/manifest.json" to json.encodeToString(PresetManifest(entries)).encodeToByteArray())
        entries.forEach { entry ->
            val raw = when (entry.type) {
                PresetType.CHARACTER -> json.encodeToString(CharacterCardPackage(card = PackagedCharacterCard(name = entry.displayName)))
                PresetType.FORMAT -> json.encodeToString(FormatCardPackage(name = entry.displayName, content = "Bundled"))
                PresetType.WORLD_BOOK -> json.encodeToString(WorldBookPackage(book = WorldBook.create(entry.displayName)))
                PresetType.MODEL_CATALOG -> "{}"
            }
            files[entry.file] = raw.encodeToByteArray()
        }
        return DesktopAppContainer(DesktopDataRootResolution.Resolved(parent.resolve("data"),
            DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { files.getValue(it) })
    }
}
