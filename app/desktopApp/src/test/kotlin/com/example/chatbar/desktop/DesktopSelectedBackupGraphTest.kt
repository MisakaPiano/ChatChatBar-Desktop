package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.desktop.backup.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DesktopSelectedBackupGraphTest {
    private class Fixture(val damage: String) : AutoCloseable {
        val base = Files.createTempDirectory("selected-backup-graph-")
        val root = Files.createDirectory(base.resolve("root"))
        val gate = DesktopDataOperationCoordinator()
        val focus = DesktopAudioFocus()
        val resolved = DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE, base.resolve("bootstrap.json"))
        val owner = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(resolved)).ownership
        val service = DesktopFullBackupService(root, gate, DesktopAudioMaintenance({ focus }) {})
        val storage = JsonFileStorage(root)
        val good = AppBackupSelection(characterIds = setOf("a"), sessions = mapOf("sa" to BackupSessionChoice()))
        suspend fun seed() {
            val images = Files.createDirectories(root.resolve("images"))
            for (id in listOf("a", "b")) {
                val image = images.resolve("$id.png")
                ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", image.toFile())
                storage.saveEntity("character_cards", id, CharacterCard(id, "Card $id", avatar = "images/$id.png", createdAt = 1, updatedAt = 1), CharacterCard.serializer())
                storage.saveEntity("chat_sessions", "s$id", ChatSession("s$id", id, "Session $id", createdAt = 1, updatedAt = 1), ChatSession.serializer())
                storage.saveEntity("chat_messages", "s${id}_m$id", ChatMessage("m$id", "s$id", MessageRole.USER,
                    "Synthetic message $id", images = listOf("images/$id.png"), createdAt = 1, updatedAt = 1), ChatMessage.serializer())
            }
            when (damage) {
                "image" -> Files.write(images.resolve("b.png"), byteArrayOf(1, 2, 3))
                "orphan" -> storage.saveEntity("chat_messages", "missing_orphan", ChatMessage("orphan", "missing", MessageRole.USER,
                    "Unselected orphan", createdAt = 1, updatedAt = 1), ChatMessage.serializer())
            }
        }
        override fun close() {
            runBlocking { focus.closeAndDrain(); gate.closeAndDrain() }
            owner.close(); check(base.toFile().deleteRecursively())
        }
    }

    @Test fun `actual service partial export validates only selected graph and authenticated restored subset preserves original bad unselected data`() = runBlocking<Unit> {
        for (damage in listOf("image", "orphan")) Fixture(damage).use { f ->
            f.seed()
            val mapping = DesktopFullBackupMapping(f.root)
            val before = mapping.inventory()
            val output = f.base.resolve("partial.cbbackup")
            f.service.exportModern(output, null, selection = f.good)
            f.service.verifyFile(output, null)
            DesktopFullBackupStaging.prepare(output, null, Files.createDirectory(f.base.resolve("incoming"))).use { incoming ->
                assertEquals(1, incoming.source.manifest.counts["character_cards"])
                assertEquals(1, incoming.source.manifest.counts["chat_sessions"])
                assertEquals(1, incoming.source.manifest.counts["chat_messages"])
                assertTrue("images/a.png" in incoming.files)
                assertFalse("images/b.png" in incoming.files)
                assertTrue(incoming.entities.keys.none { it.endsWith("/b.json") || it.endsWith("/sb.json") || it.endsWith("/sb_mb.json") || it.endsWith("/missing_orphan.json") })
                val target = Files.createDirectory(f.base.resolve("target"))
                DesktopFullBackupMapping(target).restoreMerged(incoming.files, Files.createDirectory(f.base.resolve("mapped")), BackupPreferences()).use { plan ->
                    DesktopFullBackupGraphValidation.validate(plan.directory)
                    for ((relative, source) in plan.files) {
                        val destination = target.resolve(relative); Files.createDirectories(destination.parent); Files.copy(source, destination)
                    }
                    val chats = ChatRepository(JsonFileStorage(target))
                    assertEquals("Synthetic message a", chats.getMessagesReadOnly("sa").single().content)
                    assertNull(chats.getSession("sb"))
                }
            }
            assertEquals(before, mapping.inventory())
            assertFalse(f.service.restartRequired)
        }
    }

    @Test fun `selected bad images full export and explicit all selection remain rejected without publishing output`() = runBlocking<Unit> {
        for (damage in listOf("image", "orphan")) Fixture(damage).use { f ->
            f.seed(); val before = DesktopFullBackupMapping(f.root).inventory()
            val all = f.service.catalog().allSelection()
            val choices = if (damage == "image") listOf(null, all,
                AppBackupSelection(characterIds = setOf("b"), sessions = mapOf("sb" to BackupSessionChoice()))) else listOf(null, all)
            choices.forEachIndexed { index, selection ->
                val output = f.base.resolve("refused-$index.cbbackup")
                assertFails { f.service.exportModern(output, null, selection = selection) }
                assertFalse(Files.exists(output))
            }
            assertEquals(before, DesktopFullBackupMapping(f.root).inventory())
        }
    }

    @Test fun `partial selection does not bypass hidden recovery data or unknown root inventory refusal`() = runBlocking<Unit> {
        for (relative in listOf("images/.recovery/notes.png", "unregistered/notes.txt")) Fixture("none").use { f ->
            f.seed(); val retained = f.root.resolve(relative)
            Files.createDirectories(retained.parent); Files.writeString(retained, "Synthetic unclassified user bytes")
            val output = f.base.resolve("refused.cbbackup")
            assertFails { f.service.exportModern(output, null, selection = f.good) }
            assertFalse(Files.exists(output))
            assertEquals("Synthetic unclassified user bytes", Files.readString(retained))
        }
    }
}
