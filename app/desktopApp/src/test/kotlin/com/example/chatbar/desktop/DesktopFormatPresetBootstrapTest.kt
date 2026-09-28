package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.PresetEntry
import com.example.chatbar.data.local.entity.PresetManifest
import com.example.chatbar.data.local.entity.PresetType
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.card.FormatCardPackage
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class DesktopFormatPresetBootstrapTest {
    @Test
    fun `fresh root imports inline manifest formats and restart keeps selector choices`() = runBlocking {
        val parent = Files.createTempDirectory("desktop-format-bootstrap-")
        val root = parent.resolve("data")
        val entry = PresetEntry("inline-format", PresetType.FORMAT, 1,
            "presets/formats/inline.json", "Inline Format")
        val assets = fakeAssets(entry, "Bundled content")
        try {
            val first = container(parent, root, assets)
            try {
                assertFalse(Files.exists(root.resolve("entities/format_cards")))
                first.initializePersistentState()
                val imported = first.formatCardRepository.getAll().single()
                assertEquals(entry.presetKey, imported.sourcePresetKey)
                assertEquals(entry.version, imported.sourcePresetVersion)
                assertEquals("Bundled content", imported.content)
                val character = CharacterCard.create("Inline character")
                first.characterRepository.save(character)
                first.characterSessionService.createSessionForCharacter(character.id)
                first.modelSettingsController.loadSettings()
                first.primaryChatController.refresh()
                assertTrue(first.modelSettingsController.state.value.formatCards.any { it.first == imported.id })
                assertTrue(first.primaryChatController.state.value.formatChoices.any { it.id == imported.id })
            } finally { first.close() }

            val second = container(parent, root, assets)
            try {
                second.initializePersistentState()
                assertEquals(1, second.formatCardRepository.getAll().size)
            } finally { second.close() }
        } finally { parent.toFile().deleteRecursively() }
    }

    @Test
    fun `existing user and modified preset cards survive manifest version increase`() = runBlocking {
        val parent = Files.createTempDirectory("desktop-format-update-")
        val root = parent.resolve("data")
        val old = PresetEntry("inline-format", PresetType.FORMAT, 1,
            "presets/formats/inline.json", "Inline Format")
        try {
            val first = container(parent, root, fakeAssets(old, "Original"))
            val presetId: String
            try {
                first.formatPresetBootstrap.initialize()
                val imported = first.formatCardRepository.getAll().single()
                presetId = imported.id
                first.formatCardRepository.save(imported.copy(content = "User edited"))
                first.formatCardRepository.save(
                    com.example.chatbar.data.local.entity.FormatCard.create("User card", "User content"))
            } finally { first.close() }

            val updated = container(parent, root, fakeAssets(old.copy(version = 2), "New bundled content"))
            try {
                updated.formatPresetBootstrap.initialize()
                val cards = updated.formatCardRepository.getAll()
                assertEquals(2, cards.size)
                assertEquals("User edited", cards.single { it.id == presetId }.content)
                assertEquals(1, cards.single { it.id == presetId }.sourcePresetVersion)
                assertEquals("User content", cards.single { it.name == "User card" }.content)
            } finally { updated.close() }
        } finally { parent.toFile().deleteRecursively() }
    }

    @Test
    fun `missing ledger does not duplicate existing preset and name conflict leaves user card alone`() = runBlocking {
        val parent = Files.createTempDirectory("desktop-format-existing-")
        val root = parent.resolve("data")
        val existing = PresetEntry("existing-key", PresetType.FORMAT, 3,
            "presets/formats/existing.json", "Existing")
        val newEntry = PresetEntry("new-key", PresetType.FORMAT, 1,
            "presets/formats/new.json", "User card")
        val json = Json { encodeDefaults = true }
        val files = mapOf(
            "presets/manifest.json" to json.encodeToString(PresetManifest(listOf(existing, newEntry))),
            existing.file to json.encodeToString(FormatCardPackage(name = "Existing", content = "Bundled")),
            newEntry.file to json.encodeToString(FormatCardPackage(name = "User card", content = "New bundled")),
        )
        val container = container(parent, root) { files.getValue(it).encodeToByteArray() }
        try {
            container.formatCardRepository.save(
                com.example.chatbar.data.local.entity.FormatCard.create("Existing", "Modified")
                    .copy(sourcePresetKey = existing.presetKey, sourcePresetVersion = 1))
            container.formatCardRepository.save(
                com.example.chatbar.data.local.entity.FormatCard.create("User card", "User content"))
            container.formatPresetBootstrap.initialize()
            val cards = container.formatCardRepository.getAll()
            assertEquals(3, cards.size)
            assertEquals("Modified", cards.single { it.sourcePresetKey == existing.presetKey }.content)
            assertEquals("User content", cards.single { it.name == "User card" }.content)
            assertEquals("New bundled", cards.single { it.sourcePresetKey == newEntry.presetKey }.content)
        } finally {
            container.close()
            parent.toFile().deleteRecursively()
        }
    }

    private fun container(parent: java.nio.file.Path, root: java.nio.file.Path,
        assets: (String) -> ByteArray) = DesktopAppContainer(
        DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE,
            parent.resolve("bootstrap.json")),
        secretStoreFactory = { InMemoryDesktopSecretStore() },
        bundledAssets = assets,
    )

    private fun fakeAssets(entry: PresetEntry, content: String): (String) -> ByteArray {
        val json = Json { encodeDefaults = true }
        val files = mapOf(
            "presets/manifest.json" to json.encodeToString(PresetManifest(listOf(entry))),
            entry.file to json.encodeToString(FormatCardPackage(name = entry.displayName, content = content)),
        )
        return { path -> files.getValue(path).encodeToByteArray() }
    }
}
