package com.example.chatbar.lanSync

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.image.NovelAiPostProcessResultRecord
import com.example.chatbar.domain.lanSync.*
import com.example.chatbar.interop.LanSyncLargePngFixture
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class LanSyncLargeImageTest {
    @Test fun fullyWrittenLargePngOriginalsSurviveNativeValidationTransferReopenAndPreview() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("lanSyncLargeImageFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = Files.createTempDirectory(context.cacheDir.canonicalFile.toPath(), "lan-large-image-").toRealPath()
        try {
            val data = Files.createDirectory(root.resolve("source"))
            val source = LanSyncWorkspace(data, root.resolve("source-state"), data::resolve,
                validateResource = ::validateAndroidLanSyncResource, imagePreviewRaster = ::androidLanSyncPreviewRaster)
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
                validateResource = ::validateAndroidLanSyncResource, imagePreviewRaster = ::androidLanSyncPreviewRaster)
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
                    val bitmap = requireNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                    try { assertEquals(16, bitmap.width); assertEquals(12, bitmap.height); assertEquals(LanSyncLargePngFixture.PIXEL, bitmap.getPixel(7, 5)) }
                    finally { bitmap.recycle() }
                    assertEquals(original.second, LanSyncLargePngFixture.hash(original.first))
                    assertArrayEquals(original.third, Files.readAllBytes(data.resolve("entities/${record.type}/${record.id}.json")))
                }
                val post = capture.snapshot.records.single { it.type == LanSyncPostProcess.TYPE }
                assertEquals("ENHANCE", LanSyncPostProcess.record(post)!!.operation)
                assertEquals(originals[1].second, LanSyncPostProcess.record(post)!!.imageSha256)
            }
            println("LARGE_IMAGE_NATIVE_TRANSFER_REOPEN_PREVIEW_PASS")
        } finally { check(root.toFile().deleteRecursively()) }
    }
}
