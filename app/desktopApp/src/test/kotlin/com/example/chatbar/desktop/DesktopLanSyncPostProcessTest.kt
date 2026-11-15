package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.domain.image.*
import com.example.chatbar.domain.lanSync.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopLanSyncPostProcessTest {
    @Test fun actualPaidResultOwnerSurvivesPhoneRetentionConflictForkReturnReopenAndDeletion() = runBlocking {
        val base = Files.createTempDirectory("desktop-lan-processed-").toRealPath()
        try {
            val pc = Files.createDirectory(base.resolve("pc")); val phone = Files.createDirectory(base.resolve("phone"))
            val pcState = Files.createDirectory(base.resolve("pc-state")); val phoneState = Files.createDirectory(base.resolve("phone-state"))
            fun store(root: Path) = DesktopPostProcessResults(JsonFileStorage(root), DesktopCharacterResourceStore(root), DesktopDataOperationCoordinator())
            fun workspace(root: Path, state: Path, desktop: Boolean) = LanSyncWorkspace(root, state,
                { reference -> Path.of(reference).let { if (it.isAbsolute) it else root.resolve(it) } },
                resourceReference = { _, path -> if (desktop) desktopLanSyncResourceReference(root, path) else path.toString() },
                validateResource = { kind, path, row -> if (kind == "images") {
                    val result = requireNotNull(LanSyncPostProcess.record(row))
                    DesktopPostProcessRaster.decode(Files.readAllBytes(path), NovelAiPostProcessTab.valueOf(result.operation), 256).flush()
                } })
            val input = DesktopImageEditing.png(BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB))
            val output = DesktopImageEditing.png(BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB))
            val owner = store(pc)
            val saved = owner.persist(input, output, NovelAiPostProcessTab.UPSCALE, NovelAiEnhanceOptions())
            val left = workspace(pc, pcState, true); val right = workspace(phone, phoneState, false)
            left.capture("pc").use { a -> right.capture("phone").use { b ->
                val plan = LanSyncPlanner.plan(a.snapshot, b.snapshot, LanSyncBaseline("phone", ownerId = "pc"), emptyMap(), UUID.randomUUID().toString())
                right.apply(plan, b.snapshot.hashes(), "phone", a.blobs)
                val imported = requireNotNull(JsonFileStorage(phone).loadEntity(LanSyncPostProcess.TYPE, saved.id, NovelAiPostProcessResultRecord.serializer()))
                assertTrue(Path.of(imported.imagePath).isAbsolute)
                assertContentEquals(output, Files.readAllBytes(Path.of(imported.imagePath)))
                assertEquals(saved, imported.copy(imagePath = saved.imagePath))
                val raw = phone.resolve("entities/${LanSyncPostProcess.TYPE}/${saved.id}.json")
                // Model a reviewed concurrent metadata change: KEEP_BOTH must fork only the record identity.
                Files.writeString(raw, Json.encodeToString(NovelAiPostProcessResultRecord.serializer(), imported.copy(strength = .7f)))
                right.capture("phone").use { changed ->
                    val choices = mapOf("${LanSyncPostProcess.TYPE}/${saved.id}" to LanSyncChoice.KEEP_BOTH)
                    val fork = LanSyncPlanner.plan(a.snapshot, changed.snapshot, LanSyncBaseline("phone", ownerId = "pc"), choices, UUID.randomUUID().toString())
                    assertEquals(2, fork.snapshot.records.size)
                    left.apply(fork, a.snapshot.hashes(), "pc", a.blobs + changed.blobs)
                    right.apply(fork, changed.snapshot.hashes(), "phone", a.blobs + changed.blobs)
                    val reopened = store(pc); reopened.load()
                    assertEquals(setOf(.5f, .7f), reopened.records.value.map { it.strength }.toSet())
                    assertEquals(2, reopened.records.value.map { it.id }.toSet().size)
                    reopened.records.value.forEach { row ->
                        row.validateForImport(); assertTrue(row.imagePath.startsWith("images/"))
                        assertEquals(saved.sourceSha256, row.sourceSha256); assertEquals(saved.imageSha256, row.imageSha256)
                        assertContentEquals(output, reopened.read(row))
                    }
                    left.capture("pc").use { current -> right.capture("phone").use { returned ->
                        assertEquals(current.snapshot.hashes(), returned.snapshot.hashes())
                        val baseline = LanSyncBaseline("phone", ownerId = "pc", revision = "completed", hashes = current.snapshot.hashes())
                        assertTrue(LanSyncCompare.compare(current.snapshot, returned.snapshot, baseline).conflicts.isEmpty())
                        val removed = reopened.records.value.single { it.strength == .7f }
                        Files.delete(phone.resolve("entities/${LanSyncPostProcess.TYPE}/${removed.id}.json"))
                        right.capture("phone").use { deleted ->
                            val deletion = LanSyncPlanner.plan(current.snapshot, deleted.snapshot, baseline, emptyMap(), UUID.randomUUID().toString())
                            left.apply(deletion, current.snapshot.hashes(), "pc", current.blobs + deleted.blobs)
                            val finalOwner = store(pc); finalOwner.load()
                            assertEquals(listOf(saved.id), finalOwner.records.value.map { it.id })
                            assertContentEquals(output, finalOwner.read(finalOwner.records.value.single()))
                        }
                    } }
                }
            } }
        } finally { check(base.toFile().deleteRecursively()) }
    }
}
