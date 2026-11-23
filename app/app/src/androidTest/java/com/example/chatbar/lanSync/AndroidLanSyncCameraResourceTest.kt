package com.example.chatbar.lanSync

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.domain.lanSync.LanSyncHeifStill
import com.example.chatbar.domain.lanSync.LanSyncInvalidImage
import com.example.chatbar.domain.lanSync.LanSyncRecord
import com.example.chatbar.domain.lanSync.LanSyncResourceStore
import com.example.chatbar.domain.lanSync.LanSyncWorkspace
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidLanSyncCameraResourceTest {
    private val cache get() = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile.toPath()
    private val record = LanSyncRecord("chat_sessions", "synthetic", buildJsonObject { put("id", "synthetic") })

    @Test fun camera48MpJpegRendersSampledAndKeepsExactOriginal() {
        val file = Files.createTempFile(cache, "synthetic-48mp-", ".jpg")
        try {
            writeJpeg(file)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            Files.newInputStream(file).use { BitmapFactory.decodeStream(it, null, bounds) }
            assertEquals(8000, bounds.outWidth); assertEquals(6000, bounds.outHeight)
            assertPreviewAndRetainedOriginal(file)
        } finally { Files.deleteIfExists(file) }
    }

    @Test fun genuineHeifUnderLegacyJpegNameUsesAndroidCodecAndKeepsExactOriginal() {
        val file = cache.resolve("synthetic-r02-heif.jpg")
        val dimensions = requireNotNull(LanSyncHeifStill.inspect(file))
        assertEquals(64, dimensions.width); assertTrue(dimensions.height in 48..64)
        assertPreviewAndRetainedOriginal(file, expectedWidth = 64, expectedHeight = 48)
    }

    @Test fun incompleteRecognizedHeifHoldsOnlyItsOwningChat(): Unit = runBlocking {
        val root = Files.createTempDirectory(cache, "synthetic-invalid-heif-").toRealPath()
        try {
            val data = Files.createDirectory(root.resolve("data"))
            val image = Files.createDirectory(data.resolve("images")).resolve("owned.jpg")
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            try {
                Files.newOutputStream(image).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            } finally { bitmap.recycle() }
            val original = Files.readAllBytes(image)
            val storage = JsonFileStorage(data)
            for (id in listOf("photo", "text")) {
                storage.saveEntity("chat_sessions", id, ChatSession(id, "card", id, createdAt = 1, updatedAt = 1),
                    ChatSession.serializer())
            }
            val photo = ChatMessage("photo-image", "photo", MessageRole.USER, "合成图片",
                images = listOf("images/owned.jpg"), createdAt = 2, updatedAt = 2)
            storage.saveEntity("chat_messages", "photo_photo-image", photo, ChatMessage.serializer())
            val text = ChatMessage("independent-text", "text", MessageRole.USER, "合成独立文字", createdAt = 2, updatedAt = 2)
            storage.saveEntity("chat_messages", "text_independent-text", text, ChatMessage.serializer())
            val workspace = LanSyncWorkspace(data, Files.createDirectory(root.resolve("state")), { data.resolve(it) },
                validateResource = ::validateAndroidLanSyncResource)
            val good = workspace.captureGroup("phone").use { it.snapshot.hashes() }
            val broken = byteArrayOf(0, 0, 0, 20, 102, 116, 121, 112, 104, 101, 105, 99, 0, 0, 0, 0, 109, 105, 102, 49)
            Files.write(image, broken)
            assertTrue(runCatching { workspace.capture("phone").close() }.exceptionOrNull() is LanSyncInvalidImage)
            workspace.captureGroup("phone").use {
                assertEquals(setOf("conversation/photo"), it.blockedResourceUnits)
                assertEquals(good["conversation/text"], it.snapshot.hashes()["conversation/text"])
            }
            assertTrue(broken.contentEquals(Files.readAllBytes(image)))
            assertEquals(photo, storage.loadEntity("chat_messages", "photo_photo-image", ChatMessage.serializer()))
            Files.write(image, original)
            workspace.captureGroup("phone").use {
                assertTrue(it.blockedResourceUnits.isEmpty())
                assertEquals(good, it.snapshot.hashes())
            }
        } finally { check(root.toFile().deleteRecursively()) }
    }

    private fun writeJpeg(file: Path) {
        val width = 8000; val height = 6000
        val camera = YuvImage(ByteArray(width * height * 3 / 2) { 96 }, ImageFormat.NV21, width, height, null)
        Files.newOutputStream(file).use { assertTrue(camera.compressToJpeg(Rect(0, 0, width, height), 85, it)) }
    }
    private fun assertPreviewAndRetainedOriginal(file: Path, expectedWidth: Int? = null, expectedHeight: Int? = null) {
        val length = Files.size(file)
        val sha = LanSyncResourceStore.hashFile(file, length)
        validateAndroidLanSyncResource("images", file, record)
        val preview = androidLanSyncPreviewRaster(file, record)
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(preview, 0, preview.size))
        try {
            assertTrue(bitmap.width in 1..2048 && bitmap.height in 1..2048)
            if (expectedWidth != null) assertEquals(expectedWidth, bitmap.width)
            if (expectedHeight != null) assertEquals(expectedHeight, bitmap.height)
        } finally { bitmap.recycle() }
        assertEquals(length, Files.size(file))
        assertEquals(sha, LanSyncResourceStore.hashFile(file, length))
    }
}
