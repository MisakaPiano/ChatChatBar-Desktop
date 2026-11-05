package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.*
import com.example.chatbar.desktop.backup.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopPlayerImagesTest {
    @Test fun `avatar saves are independent of persona edits and cleanup retains a shared session image`(): Unit = runBlocking {
        val root = Files.createTempDirectory("desktop-player-images-")
        val gate = DesktopDataOperationCoordinator()
        try {
            val storage = JsonFileStorage(root, gate)
            val settings = SettingsRepository(storage)
            val chats = ChatRepository(storage)
            settings.savePlayerSetting(PlayerSetting(playerName = "Original", globalPersona = "Keep persona"))
            val resources = DesktopCharacterResourceStore(root)
            val images = DesktopPlayerImages(settings, resources, UnconfiguredDesktopFilePicker, gate,
                DesktopOwnedImageCleanup(root, resources, gate)::deleteUnreferenced)
            val png = DesktopImageEditing.png(BufferedImage(12, 8, BufferedImage.TYPE_INT_RGB))
            coroutineScope {
                launch { images.replace(png) }
                launch { settings.updatePlayerSetting { it.copy(playerName = "Edited name") } }
            }
            val first = settings.getPlayerSetting()
            assertEquals("Edited name", first.playerName)
            assertEquals("Keep persona", first.globalPersona)
            val reference = assertNotNull(first.desktopAvatar)
            assertContentEquals(png, resources.readBytes(reference))
            val session = chats.createSession(ChatSession.create("card", "Chat").copy(desktopPlayerAvatar = reference))
            images.replace(null)
            assertNull(settings.getPlayerSetting().desktopAvatar)
            assertEquals("Edited name", settings.getPlayerSetting().playerName)
            assertContentEquals(png, resources.readBytes(reference), "Shared session still owns this image")
            val sessionImages = DesktopChatImages(resources, chats, gate,
                DesktopOwnedImageCleanup(root, resources, gate)::deleteUnreferenced)
            sessionImages.replacePlayerAvatar(session.id, png)
            assertNotEquals(reference, chats.getSession(session.id)!!.desktopPlayerAvatar)
            sessionImages.replacePlayerAvatar(session.id, null)
            assertNull(chats.getSession(session.id)!!.desktopPlayerAvatar)
        } finally { gate.closeAndDrain(); root.toFile().deleteRecursively() }
    }

    @Test fun `modern backup maps both player avatar scopes and explicit role namespaces without exposing source paths`(): Unit = runBlocking {
        val base = Files.createTempDirectory("desktop-player-backup-")
        val gate = DesktopDataOperationCoordinator()
        try {
            val source = Files.createDirectory(base.resolve("source"))
            val target = Files.createDirectory(base.resolve("target"))
            val work = Files.createDirectory(base.resolve("work"))
            val storage = JsonFileStorage(source, gate)
            val settings = SettingsRepository(storage)
            val resources = DesktopCharacterResourceStore(source)
            val images = DesktopPlayerImages(settings, resources, UnconfiguredDesktopFilePicker, gate)
            val png = DesktopImageEditing.png(BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB))
            images.replace(png)
            val player = settings.getPlayerSetting()
            val session = ChatSession.create("card", "Chat").copy(desktopPlayerAvatar = player.desktopAvatar,
                desktopPlayerRole = SessionPlayerRole("card", "person", followAvatar = false))
            ChatRepository(storage).createSession(session)
            DesktopFullBackupMapping(source).export(work, wireVersionCode = 86).use { exported ->
                val playerWire = backupJson.parseToJsonElement(Files.readString(exported.files.getValue("entities/player_setting.json"))).jsonObject
                assertTrue(DesktopBackupPolicy.hasDesktopParticipation(playerWire))
                assertTrue(playerWire.getValue("desktopAvatar").jsonPrimitive.content.startsWith(DesktopBackupPolicy.PREFIX))
                DesktopFullBackupMapping(target).restoreMerged(exported.files, work, exported.preferences).use { restored ->
                    val loaded = backupJson.decodeFromString(PlayerSetting.serializer(), Files.readString(restored.files.getValue("entities/player_setting.json")))
                    val relative = assertNotNull(DesktopBackupPolicy.sourceRelative(loaded.desktopAvatar!!, target))
                    assertContentEquals(png, Files.readAllBytes(restored.files.getValue(relative)))
                    val loadedSession = backupJson.decodeFromString(ChatSession.serializer(), Files.readString(restored.files.getValue("entities/chat_sessions/${session.id}.json")))
                    assertEquals(session.desktopPlayerRole, loadedSession.desktopPlayerRole)
                    assertEquals(loaded.desktopAvatar, loadedSession.desktopPlayerAvatar)
                }
                val record = DesktopMergeRecord("entities/chat_sessions/${session.id}.json",
                    backupJson.encodeToJsonElement(ChatSession.serializer(), session).jsonObject)
                val ids = DesktopMergeIdentity()
                ids.bind("character_cards", "card", "copied-card")
                ids.bind("characters", "person", "copied-person")
                assertEquals("copied-card", ids.string(record, "card", listOf("desktopPlayerRole", "cardId")))
                assertEquals("copied-person", ids.string(record, "person", listOf("desktopPlayerRole", "personId")))
            }
            assertContentEquals(png, resources.readBytes(player.desktopAvatar!!))
        } finally { gate.closeAndDrain(); base.toFile().deleteRecursively() }
    }
}
