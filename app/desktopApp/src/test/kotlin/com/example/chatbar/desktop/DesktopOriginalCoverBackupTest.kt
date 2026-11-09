package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.EditorDraftRepository
import com.example.chatbar.desktop.backup.*
import com.example.chatbar.domain.card.PackagedImage
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DesktopOriginalCoverBackupTest {
    private fun image() = ByteArrayOutputStream().use {
        check(ImageIO.write(BufferedImage(3, 7, BufferedImage.TYPE_INT_RGB), "png", it)); it.toByteArray()
    }

    @Test fun `full and selected authenticated backup restore preserve original and reopenable cover draft bytes`() = runBlocking {
        for (selected in listOf(false, true)) {
            val base = Files.createTempDirectory("original-cover-backup-")
            val root = Files.createDirectory(base.resolve("source"))
            val resolved = DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE, base.resolve("bootstrap.json"))
            val owner = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(resolved)).ownership
            val gate = DesktopDataOperationCoordinator()
            val focus = DesktopAudioFocus()
            try {
                val bytes = image()
                val storage = JsonFileStorage(root)
                val resources = DesktopCharacterResourceStore(root)
                val drafts = DesktopCharacterDraftResources(root, resources)
                val cover = resources.materializeCoverImage(PackagedImage("original.png", Base64.getEncoder().encodeToString(bytes)), 1, "original")
                val voice = FishAudioVoiceBinding("synthetic-voice", "Synthetic voice", coverImage = "model-artwork/relative-cover.webp")
                val card = CharacterCard("card", "Original cover", createdAt = 1, updatedAt = 1, coverImage = cover,
                    characters = listOf(CharacterInfo("person", "Synthetic person", fishAudioVoice = voice)))
                CharacterRepository(storage).save(card)
                val pending = drafts.copyAsCover("cover-draft", cover)
                val draft = EditorDraft(EditorDraftRepository.draftId(EditorDraftType.CHARACTER_CARD, card.id), EditorDraftType.CHARACTER_CARD,
                    EditorDraftMode.EDIT, card.id, "cover-draft", characterPayload = card.copy(coverImage = pending), draftAssetPaths = listOf(pending),
                    createdAt = 1, updatedAt = 2)
                EditorDraftRepository(storage).save(draft)
                if (selected) CharacterRepository(storage).save(CharacterCard("unselected", "Omitted missing cover",
                    createdAt = 1, updatedAt = 1, coverImage = "images/character-covers/missing.png"))
                val before = DesktopFullBackupMapping(root).inventory()
                val service = DesktopFullBackupService(root, gate, DesktopAudioMaintenance({ focus }) {})
                val selection = if (selected) AppBackupSelection(characterIds = setOf(card.id)) else null
                val archive = base.resolve("cover.cbbackup")
                service.exportModern(archive, null, selection = selection)
                service.verifyFile(archive, null)
                DesktopFullBackupStaging.prepare(archive, null, Files.createDirectory(base.resolve("incoming"))).use { incoming ->
                    assertContentEquals(bytes, Files.readAllBytes(incoming.files.getValue(cover)))
                    val target = Files.createDirectory(base.resolve("target"))
                    DesktopFullBackupMapping(target).restoreMerged(incoming.files, Files.createDirectory(base.resolve("mapped")), BackupPreferences()).use { plan ->
                        DesktopFullBackupGraphValidation.validate(plan.directory)
                        plan.files.forEach { (relative, source) ->
                            val destination = target.resolve(relative); Files.createDirectories(destination.parent); Files.copy(source, destination)
                        }
                        val reopened = JsonFileStorage(target)
                        val restored = assertNotNull(CharacterRepository(reopened).getById(card.id))
                        assertEquals(voice, restored.characters.single().fishAudioVoice)
                        assertNull(CharacterRepository(reopened).getById("unselected"))
                        assertTrue(restored.coverImage!!.startsWith("images/character-covers/"))
                        val targetResources = DesktopCharacterResourceStore(target)
                        assertContentEquals(bytes, targetResources.readBytes(restored.coverImage!!))
                        val restoredDraft = assertNotNull(EditorDraftRepository(reopened).getForTarget(EditorDraftType.CHARACTER_CARD, card.id))
                        val payload = assertNotNull(restoredDraft.characterPayload)
                        val targetDrafts = DesktopCharacterDraftResources(target, targetResources)
                        assertTrue(targetDrafts.isDraft(payload.coverImage!!))
                        assertContentEquals(bytes, targetDrafts.readImage(payload.coverImage!!))
                        val (materialized, created) = targetDrafts.materialize(payload)
                        assertTrue(materialized.coverImage!!.startsWith("images/character-covers/"))
                        assertContentEquals(bytes, targetResources.readBytes(materialized.coverImage!!))
                        targetDrafts.rollback(created, java.io.IOException("synthetic test rollback"))
                        assertContentEquals(bytes, targetResources.readBytes(restored.coverImage!!))
                    }
                }
                assertEquals(before, DesktopFullBackupMapping(root).inventory())
                assertContentEquals(bytes, resources.readBytes(cover))
                assertFalse(service.restartRequired)
            } finally {
                focus.closeAndDrain(); gate.closeAndDrain(); owner.close()
                check(base.toFile().deleteRecursively())
            }
        }
    }

    @Test fun `incoming cover merge planning preserves dedicated storage and portable resource classification`() {
        val cover = "images/character-covers/original.png"
        val card = CharacterCard("card", "Original", createdAt = 1, updatedAt = 1, coverImage = DesktopBackupPolicy.PREFIX + cover)
        val entity = backupJson.encodeToJsonElement(CharacterCard.serializer(), card) as kotlinx.serialization.json.JsonObject
        val media = mapOf(cover to "1".repeat(64))
        val preview = DesktopMergePreview.create(emptyMap(), mapOf("entities/character_cards/card.json" to entity), emptyMap(), media)
        DesktopMergeDestinationPlan.create(preview.select(emptyMap()), emptyMap(), media).use { plan ->
            assertTrue(plan.resources.keys.single().startsWith("images/character-covers/"))
            assertEquals(cover, plan.resources.values.single().original)
            assertEquals("1".repeat(64), plan.resources.values.single().sha256)
        }
        assertTrue(BackupDataRegistry.isResourceField("coverImage", emptyList()))
        assertFalse(BackupDataRegistry.isResourceField("coverImage", listOf("metadata")))
    }

    @Test fun `Fish artwork stays opaque in card voice and nested SaveSlot records while real resources remain mapped`() {
        val raw = """{"coverImage":"images/character-covers/original.png","characters":[{"fishAudioVoice":{"coverImage":"model-artwork/relative-cover.webp"}}],"voice":{"coverImage":"model-artwork/clip.webp"},"voiceMessages":[{"voice":{"coverImage":"model-artwork/slot.webp"},"audioPath":"audio/clip.mp3"}],"metadata":{"coverImage":"opaque/value"}}"""
        val paths = mutableListOf<String>()
        val output = java.io.StringWriter()
        BackupJsonStream(raw.reader(), output, mapPath = { path, _ -> paths += path; "mapped/$path" }).copy()
        assertEquals(listOf("images/character-covers/original.png", "audio/clip.mp3"), paths)
        val result = kotlinx.serialization.json.Json.parseToJsonElement(output.toString()).toString()
        listOf("model-artwork/relative-cover.webp", "model-artwork/clip.webp", "model-artwork/slot.webp", "opaque/value").forEach {
            assertTrue(result.contains(it))
        }
        for (path in listOf("../outside.png", "images/../outside.png", "unregistered/cover.png")) {
            assertFailsWith<IllegalArgumentException> {
                BackupJsonStream("""{"coverImage":"$path"}""".reader(), java.io.StringWriter(),
                    mapPath = { value, _ -> DesktopBackupPolicy.path(value); value }).copy()
            }
        }
    }
}
