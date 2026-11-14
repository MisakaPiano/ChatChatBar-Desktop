package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.image.NovelAiPostProcessResultRecord
import com.example.chatbar.domain.lanSync.*
import com.example.chatbar.interop.LanSyncLargePngFixture
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.*

class DesktopLanSyncLargeImageTest {
    @Test fun `orientation metadata working limit holds only the preview while the original still transfers`() {
        val root = Files.createTempDirectory("lan-exif-original-").toRealPath()
        try {
            val data = Files.createDirectory(root.resolve("source")); val image = data.resolve("large-exif.png")
            LanSyncLargePngFixture.write(image, 0, largeExif = true)
            val hash = LanSyncLargePngFixture.hash(image)
            val body = Json.encodeToJsonElement(CharacterCard.serializer(), CharacterCard("card", "synthetic", avatar = image.toString(), createdAt = 1, updatedAt = 1)).jsonObject
            val entity = data.resolve("entities/character_cards/card.json"); Files.createDirectories(entity.parent); Files.writeString(entity, body.toString())
            val source = LanSyncWorkspace(data, root.resolve("source-state"), data::resolve, validateResource = ::validateDesktopLanSyncResource)
            val targetData = Files.createDirectory(root.resolve("target"))
            val target = LanSyncWorkspace(targetData, root.resolve("target-state"), targetData::resolve,
                validateResource = ::validateDesktopLanSyncResource, imagePreviewRaster = DesktopLanSyncRaster::preview)
            source.capture("source").use { capture ->
                target.apply(LanSyncPlan(UUID.randomUUID().toString(), capture.snapshot, emptyMap()), emptyMap(), "target", capture.blobs)
            }
            target.capture("target").use { capture ->
                val record = capture.snapshot.records.single(); val ref = LanSyncReviewImages.references(record).single()
                val preview = target.previewImage(record, ref.reference)
                assertFailsWith<IllegalArgumentException> { target.readImagePreview(preview) }
                assertEquals(hash, LanSyncLargePngFixture.hash(capture.blobs.getValue(hash)))
            }
            assertEquals(hash, LanSyncLargePngFixture.hash(image))
        } finally { check(root.toFile().deleteRecursively()) }
    }
    @Test fun `fully written large PNG originals pass actual sync capture apply reopen and bounded frozen preview`() {
        val root = Files.createTempDirectory("lan-large-images-").toRealPath()
        try {
            val data = Files.createDirectory(root.resolve("source"))
            val source = LanSyncWorkspace(data, root.resolve("source-state"), data::resolve,
                validateResource = ::validateDesktopLanSyncResource, imagePreviewRaster = DesktopLanSyncRaster::preview)
            val originals = listOf(33, 101).mapIndexed { index, chunks ->
                val image = data.resolve("images/image-$index.png"); Files.createDirectories(image.parent)
                LanSyncLargePngFixture.write(image, chunks)
                assertTrue(Files.size(image) > (if (index == 0) 32L else 100L) * 1024 * 1024)
                val hash = LanSyncLargePngFixture.hash(image)
                val record = if (index == 0) LanSyncRecord("character_cards", "card",
                    Json.encodeToJsonElement(CharacterCard.serializer(), CharacterCard("card", "synthetic", avatar = image.toString(), createdAt = 1, updatedAt = 1)).jsonObject)
                else LanSyncRecord(LanSyncPostProcess.TYPE, "result", Json.encodeToJsonElement(NovelAiPostProcessResultRecord.serializer(),
                    NovelAiPostProcessResultRecord("result", "a".repeat(64), "ENHANCE", "ORIGINAL", .5f, 0f, image.toString(), hash, 1)).jsonObject)
                val entity = data.resolve("entities/${record.type}/${record.id}.json"); Files.createDirectories(entity.parent)
                Files.writeString(entity, record.body.toString())
                Triple(image, hash, Files.readAllBytes(entity))
            }
            val targetData = Files.createDirectory(root.resolve("target"))
            fun target() = LanSyncWorkspace(targetData, root.resolve("target-state"), targetData::resolve,
                validateResource = ::validateDesktopLanSyncResource, imagePreviewRaster = DesktopLanSyncRaster::preview)
            source.capture("source").use { capture ->
                assertEquals(2, capture.snapshot.resources.size)
                target().apply(LanSyncPlan(UUID.randomUUID().toString(), capture.snapshot, emptyMap()), emptyMap(), "target", capture.blobs)
            }
            val reopened = target()
            reopened.capture("target").use { capture ->
                capture.snapshot.records.forEach { record ->
                    val ref = LanSyncReviewImages.references(record).single()
                    val original = originals.single { it.second == ref.hash }
                    val received = capture.blobs.getValue(ref.hash)
                    assertEquals(Files.size(original.first), Files.size(received))
                    assertEquals(ref.hash, LanSyncLargePngFixture.hash(received))
                    val preview = reopened.previewImage(record, ref.reference)
                    val bytes = reopened.readImagePreview(preview)
                    assertTrue(bytes.size < 1024 * 1024)
                    ImageIO.read(ByteArrayInputStream(bytes)).let { bitmap ->
                        try { assertEquals(16, bitmap.width); assertEquals(12, bitmap.height); assertEquals(LanSyncLargePngFixture.PIXEL, bitmap.getRGB(7, 5)) }
                        finally { bitmap.flush() }
                    }
                    assertEquals(original.second, LanSyncLargePngFixture.hash(original.first))
                    val entity = data.resolve("entities/${record.type}/${record.id}.json")
                    assertContentEquals(original.third, Files.readAllBytes(entity))
                }
                val post = capture.snapshot.records.single { it.type == LanSyncPostProcess.TYPE }
                assertEquals("ENHANCE", LanSyncPostProcess.record(post)!!.operation)
                assertEquals(originals[1].second, LanSyncPostProcess.record(post)!!.imageSha256)
            }
            // A checksum failure in even an ignored private ancillary chunk stays a validation failure.
            java.io.RandomAccessFile(originals[1].first.toFile(), "rw").use { file ->
                val offset = file.length() - 20; file.seek(offset); val value = file.read(); file.seek(offset); file.write(value xor 1)
            }
            assertFails { validateDesktopLanSyncResource("images", originals[1].first,
                LanSyncRecord("character_cards", "card", buildJsonObject {})) }
        } finally { check(root.toFile().deleteRecursively()) }
    }
}
