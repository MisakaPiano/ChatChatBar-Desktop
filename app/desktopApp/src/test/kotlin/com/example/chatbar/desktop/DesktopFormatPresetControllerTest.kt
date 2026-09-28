package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.PresetEntry
import com.example.chatbar.data.local.entity.PresetManifest
import com.example.chatbar.data.local.entity.PresetType
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.card.FormatCardPackage
import com.example.chatbar.domain.card.validateForImport
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

class DesktopFormatPresetControllerTest {
    @Test
    fun `packaged manifest format entries and files decode through shared package contract`() {
        val source = DesktopFormatPresetSource(DesktopBundledAssetReader(), Json { ignoreUnknownKeys = true })
        source.entries.forEach { entry ->
            assertEquals(PresetType.FORMAT, entry.type)
            source.packageFor(entry).validateForImport()
        }
    }

    @Test
    fun `manifest-backed discovery is read-only and explicit recovery handles name conflict`() = runBlocking {
        val parent = Files.createTempDirectory("desktop-format-preset-")
        val root = parent.resolve("data")
        val container = DesktopAppContainer(
            DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE,
                parent.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() },
        )
        try {
            val json = Json { ignoreUnknownKeys = true }
            val entry = PresetEntry("inline-format", PresetType.FORMAT, 2,
                "presets/formats/inline.json", "Inline Format")
            val files = mapOf(
                "presets/manifest.json" to json.encodeToString(PresetManifest(listOf(entry))),
                entry.file to json.encodeToString(FormatCardPackage(name = "Inline Format", content = "Inline content")),
            )
            val source = DesktopFormatPresetSource({ files.getValue(it).encodeToByteArray() }, json)
            val controller = DesktopFormatPresetController(source, container.formatCardRepository,
                container.formatTransfers)
            controller.load()
            assertEquals(listOf(entry), controller.state.value.entries)
            assertTrue(controller.state.value.cards.isEmpty())
            val formatsDir = root.resolve("entities/format_cards")
            if (Files.exists(formatsDir)) {
                assertTrue(Files.walk(formatsDir).use { stream ->
                    stream.filter(Files::isRegularFile).toList().isEmpty()
                })
            }

            controller.recover(entry)
            val first = container.formatCardRepository.getAll().single()
            assertEquals(entry.presetKey, first.sourcePresetKey)
            assertEquals(entry.version, first.sourcePresetVersion)
            assertNull(controller.state.value.pendingEntry)

            controller.recover(entry)
            assertNotNull(controller.state.value.pendingEntry)
            assertEquals(1, container.formatCardRepository.getAll().size)
            controller.resolveConflict(overwrite = false)
            assertEquals(2, container.formatCardRepository.getAll().size)
            assertTrue(container.formatCardRepository.getAll().any { it.name == "Inline Format (2)" })

            controller.recover(entry)
            controller.resolveConflict(overwrite = true)
            assertEquals(2, container.formatCardRepository.getAll().size)
            val character = CharacterCard.create("Format choice")
            container.characterRepository.save(character)
            container.chatRepository.createSession(ChatSession.create(character.id, "Format choice"))
            container.modelSettingsController.loadSettings()
            container.primaryChatController.refresh()
            assertTrue(container.modelSettingsController.state.value.formatCards.any { it.first == first.id })
            assertTrue(container.primaryChatController.state.value.formatChoices.any { it.id == first.id })
        } finally {
            container.close()
            parent.toFile().deleteRecursively()
        }
    }
}
