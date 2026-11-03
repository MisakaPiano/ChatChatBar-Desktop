package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DesktopMomentVersionBackupTest {
    @Test fun `selected backup authenticates all Moment versions and restored store can switch old images while image omission removes every reference`() = runBlocking {
        val base = Files.createTempDirectory("moment-backup-")
        val root = Files.createDirectory(base.resolve("source"))
        val gate = DesktopDataOperationCoordinator()
        try {
            val storage = JsonFileStorage(root, gate)
            val card = CharacterCard("card", "Synthetic card", greeting = "Hello", createdAt = 1, updatedAt = 1)
            val session = ChatSession("session", card.id, "Synthetic session", createdAt = 1, updatedAt = 1)
            storage.saveEntity("character_cards", card.id, card, CharacterCard.serializer())
            storage.saveEntity("chat_sessions", session.id, session, ChatSession.serializer())
            var post = MomentPost("post", card.id, session.id, senderName = "Synthetic", text = "Original text", scheduledAt = 1, generatedAt = 1)
            Files.createDirectory(root.resolve("images"))
            val originalBytes = mutableMapOf<String, ByteArray>()
            for (i in 0..2) {
                val path = "images/v$i.png"
                val pixels = BufferedImage(3, 3, BufferedImage.TYPE_INT_ARGB).apply { setRGB(1, 1, 0xff000000.toInt() or i) }
                val bytes = DesktopImageEditing.png(pixels); originalBytes[path] = bytes
                Files.write(root.resolve(path), bytes)
                post = DesktopMomentImageVersions.append(post, DesktopMomentImageResult(path,
                    GeneratedImageMetadata(path, "Scene $i", negativePrompt = "Negative $i", sizePreset = "SQUARE", width = 3, height = 3),
                    "Prompt $i", "Brief $i"), i + 1L)
            }
            assertEquals("images/v0.png", post.imagePath)
            post = DesktopMomentImageVersions.select(post, post.desktopImageVersions.last().id)
            storage.saveEntity("moment_posts", post.id, post, MomentPost.serializer())
            val task = MomentTask.create(card.id, session.id, 1).copy(status = MomentTaskStatus.RUNNING, postId = post.id)
            storage.saveEntity("moment_tasks", task.id, task, MomentTask.serializer())
            val sourceBytes = Files.readAllBytes(root.resolve("entities/moment_posts/post.json"))
            for (includeImages in listOf(true, false)) {
                val work = Files.createDirectory(base.resolve("export-$includeImages"))
                val selection = AppBackupSelection(characterIds = setOf(card.id), sessions = mapOf(session.id to BackupSessionChoice()),
                    moments = true, momentsImages = includeImages)
                DesktopFullBackupMapping(root).export(work, 86, StrictBackupZipReader.Limits().expanded, selection = selection).use { snapshot ->
                    val file = base.resolve("synthetic-$includeImages.cbbackup")
                    DesktopFullBackupWriter.export(snapshot.files, file, null, selection = selection)
                    DesktopFullBackupStaging.prepare(file, null, Files.createDirectory(base.resolve("inspect-$includeImages"))).use { staged ->
                        assertTrue(staged.hasDesktopParticipation)
                        assertEquals(includeImages, staged.source.manifest.scope!!.momentsImages)
                        val record = staged.entities.getValue("entities/moment_posts/post.json")
                        assertEquals(if (includeImages) 3 else 0, DesktopMomentBackupExtension.versions(record).size)
                        if (!includeImages) assertTrue(staged.files.keys.none { it.startsWith("images/") })
                        val preview = DesktopMergePreview.create(emptyMap(), staged.entities, emptyMap(),
                            staged.source.inventory.filterKeys { !it.startsWith("entities/") }.mapValues { it.value.sha256 }, staged.source.manifest.scope)
                        val selected = preview.select(emptyMap())
                        assertTrue(selected.phone.any { it.category == "moment_posts" })
                        assertTrue(selected.phone.none { it.category == "moment_tasks" })
                        val selectedRecords = selected.phone.flatMap { it.records.keys }.toSet()
                        val selectedFiles = staged.files.filterKeys { !it.startsWith("entities/") || it in selectedRecords }
                        val mapper = DesktopFullBackupMapping(root)
                        mapper.restoreMerged(selectedFiles, Files.createDirectory(base.resolve("restore-$includeImages")), BackupPreferences()).use { mapped ->
                            val live = Files.createDirectory(base.resolve("live-$includeImages"))
                            for ((relative, source) in mapped.files) {
                                val target = live.resolve(relative); Files.createDirectories(target.parent); Files.copy(source, target)
                            }
                            DesktopFullBackupGraphValidation.validate(live)
                            val restoredGate = DesktopDataOperationCoordinator()
                            try {
                                val restored = DesktopMomentStore(JsonFileStorage(live, restoredGate), restoredGate)
                                val loaded = restored.posts().single()
                                assertEquals(post.text, loaded.text)
                                if (includeImages) {
                                    assertEquals(3, loaded.desktopImageVersions.size)
                                    assertEquals("Prompt 2", loaded.imagePrompt)
                                    assertEquals("Brief 2", loaded.imageBrief)
                                    assertEquals(loaded.desktopImageVersions.last().imagePath, loaded.imagePath)
                                    assertEquals(loaded.desktopImageVersions.last().generatedImageMetadata, loaded.generatedImageMetadata)
                                    for ((index, version) in loaded.desktopImageVersions.withIndex()) {
                                        assertContentEquals(originalBytes.getValue("images/v$index.png"), Files.readAllBytes(live.resolve(version.imagePath)))
                                    }
                                    restored.selectImage(loaded, loaded.desktopImageVersions.first().id)
                                    val reopened = DesktopMomentStore(JsonFileStorage(live, restoredGate), restoredGate).posts().single()
                                    assertEquals("Prompt 0", reopened.imagePrompt)
                                    assertEquals(reopened.imagePath, reopened.generatedImageMetadata!!.imagePath)
                                } else { assertNull(loaded.imagePath); assertTrue(loaded.desktopImageVersions.isEmpty()) }
                                assertTrue(restored.tasks().isEmpty())
                            } finally { restoredGate.closeAndDrain() }
                        }
                    }
                }
                assertContentEquals(sourceBytes, Files.readAllBytes(root.resolve("entities/moment_posts/post.json")))
            }
        } finally { gate.closeAndDrain(); check(base.toFile().deleteRecursively()) }
    }
}
