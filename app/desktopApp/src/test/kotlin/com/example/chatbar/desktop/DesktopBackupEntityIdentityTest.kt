package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.backup.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DesktopBackupEntityIdentityTest {
    private class Fixture : AutoCloseable {
        val base = Files.createTempDirectory("backup-entity-identity-")
        val root = Files.createDirectory(base.resolve("root"))
        val gate = DesktopDataOperationCoordinator()
        val focus = DesktopAudioFocus()
        val resolved = DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE, base.resolve("bootstrap.json"))
        val owner = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(resolved)).ownership
        val service = DesktopFullBackupService(root, gate, DesktopAudioMaintenance({ focus }) {})
        val storage = JsonFileStorage(root)
        suspend fun card(key: String, id: String) = storage.saveEntity("character_cards", key,
            CharacterCard(id, "Synthetic $key", createdAt = 1, updatedAt = 1), CharacterCard.serializer())
        override fun close() {
            runBlocking { focus.closeAndDrain(); gate.closeAndDrain() }
            owner.close(); check(base.toFile().deleteRecursively())
        }
    }

    @Test fun `actual service refuses blank and duplicate card identities before publishing without changing source`() = runBlocking<Unit> {
        for (damage in listOf("duplicate", "blank")) Fixture().use { f ->
            f.card("first", if (damage == "blank") " " else "same")
            if (damage == "duplicate") f.card("second", "same")
            val before = DesktopFullBackupMapping(f.root).inventory()
            val output = f.base.resolve("refused.cbbackup")
            assertFails { f.service.exportModern(output, null) }
            assertFalse(Files.exists(output))
            assertEquals(before, DesktopFullBackupMapping(f.root).inventory())
            assertFalse(f.service.restartRequired)
        }
    }

    @Test fun `partial selection ignores unselected duplicate identities and equal ids in distinct families are independent`() = runBlocking<Unit> {
        Fixture().use { f ->
            f.card("first", "same"); f.card("second", "same"); f.card("good", "good")
            f.storage.saveEntity("format_cards", "good", FormatCard("good", "Synthetic format", "Synthetic template", createdAt = 1), FormatCard.serializer())
            f.storage.saveEntity("world_books", "good", WorldBook("good", "Synthetic world"), WorldBook.serializer())
            val before = DesktopFullBackupMapping(f.root).inventory()
            val output = f.base.resolve("partial.cbbackup")
            f.service.exportModern(output, null, selection = AppBackupSelection(characterIds = setOf("good")))
            f.service.verifyFile(output, null)
            assertEquals(before, DesktopFullBackupMapping(f.root).inventory())
            Files.delete(f.root.resolve("entities/character_cards/first.json"))
            Files.delete(f.root.resolve("entities/character_cards/second.json"))
            f.service.exportModern(f.base.resolve("distinct-families.cbbackup"), null)
            f.service.verifyFile(f.base.resolve("distinct-families.cbbackup"), null)
        }
    }

    @Test fun `actual service refuses duplicate inline slot messages but separate slots may retain the same historical message`() = runBlocking<Unit> {
        Fixture().use { f ->
            f.card("card", "card")
            f.storage.saveEntity("chat_sessions", "session", ChatSession("session", "card", "Synthetic session", createdAt = 1, updatedAt = 1), ChatSession.serializer())
            val message = ChatMessage("message", "session", MessageRole.USER, "Synthetic history", createdAt = 1, updatedAt = 1)
            val slot = SaveSlot(schemaVersion = 7, id = "first", sessionId = "session", name = "Synthetic slot", messages = listOf(message, message), createdAt = 1)
            f.storage.saveEntity("save_slots", slot.id, slot, SaveSlot.serializer())
            val before = DesktopFullBackupMapping(f.root).inventory()
            val refused = f.base.resolve("inline-refused.cbbackup")
            assertFails { f.service.exportModern(refused, null) }
            assertFalse(Files.exists(refused))
            assertEquals(before, DesktopFullBackupMapping(f.root).inventory())
            val valid = slot.copy(messages = listOf(message))
            f.storage.saveEntity("save_slots", valid.id, valid, SaveSlot.serializer())
            f.storage.saveEntity("save_slots", "second", valid.copy(id = "second"), SaveSlot.serializer())
            val output = f.base.resolve("separate-slots.cbbackup")
            f.service.exportModern(output, null)
            f.service.verifyFile(output, null)
        }
    }

    @Test fun `actual service refuses malformed inline media data and retains valid original base64 without decoding or rewriting it`() = runBlocking<Unit> {
        Fixture().use { f ->
            f.card("card", "card")
            val original = SaveSlot(schemaVersion = 7, id = "slot", sessionId = "s", name = "Synthetic media", createdAt = 1)
            for (audio in listOf(false, true)) for (data in listOf("", "not-base64!", "A===", "AA==A")) {
                val slot = if (audio) original.copy(audioResources = mapOf("resource" to SaveSlotAudioResource("resource.mp3", data)))
                    else original.copy(imageResources = mapOf("resource" to SaveSlotImageResource("resource.png", data)))
                f.storage.saveEntity("save_slots", slot.id, slot, SaveSlot.serializer())
                val before = DesktopFullBackupMapping(f.root).inventory()
                val refused = f.base.resolve("bad-media.cbbackup")
                assertFails { f.service.exportModern(refused, null) }
                assertFalse(Files.exists(refused)); assertEquals(before, DesktopFullBackupMapping(f.root).inventory())
            }
            val valid = original.copy(imageResources = mapOf("resource" to SaveSlotImageResource("resource.png", "YQ==")),
                audioResources = mapOf("sound" to SaveSlotAudioResource("resource.mp3", "Yg==")))
            f.storage.saveEntity("save_slots", valid.id, valid, SaveSlot.serializer())
            val before = DesktopFullBackupMapping(f.root).inventory()
            val output = f.base.resolve("valid-media.cbbackup")
            f.service.exportModern(output, null); f.service.verifyFile(output, null)
            DesktopFullBackupStaging.prepare(output, null, Files.createDirectory(f.base.resolve("incoming-media"))).use { incoming ->
                val restored = backupJson.decodeFromJsonElement(SaveSlot.serializer(), incoming.entities.getValue("entities/save_slots/slot.json"))
                assertEquals(valid.imageResources, restored.imageResources); assertEquals(valid.audioResources, restored.audioResources)
            }
            assertEquals(before, DesktopFullBackupMapping(f.root).inventory())
        }
    }
}
