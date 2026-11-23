package com.example.chatbar.lanSync

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.domain.image.copyAndroidChatImage
import com.example.chatbar.domain.lanSync.LanSyncHeifStill
import com.example.chatbar.domain.lanSync.LanSyncPlan
import com.example.chatbar.domain.lanSync.LanSyncRecord
import com.example.chatbar.domain.lanSync.LanSyncResourceStore
import com.example.chatbar.domain.lanSync.LanSyncWorkspace
import com.example.chatbar.interop.LanSyncMalformedHeifFixture
import com.example.chatbar.ui.components.createImageShareIntent
import com.example.chatbar.ui.components.writeGalleryImage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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

    @Test fun ordinarySelectionAndLegacyExportsUseVerifiedHeifIdentityWithoutChangingOriginalBytes(): Unit =
        runBlocking(Dispatchers.IO) {
            assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            val bytes = Files.readAllBytes(cache.resolve("synthetic-r02-heif.jpg"))
            assertEquals("heic", String(bytes, 8, 4, Charsets.US_ASCII))
            val genericHeif = bytes.copyOf().apply { "mif1".toByteArray(Charsets.US_ASCII).copyInto(this, 8) }
            assertOrdinaryHeifSelectionAndExports(bytes, LanSyncHeifStill.MediaType.HEIC)
            assertOrdinaryHeifSelectionAndExports(genericHeif, LanSyncHeifStill.MediaType.HEIF)
        }

    private suspend fun assertOrdinaryHeifSelectionAndExports(bytes: ByteArray, type: LanSyncHeifStill.MediaType) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = app.contentResolver
        val images = Files.createDirectories(app.filesDir.toPath().resolve("images"))
        val old = Files.createTempFile(images, "legacy-heif-", ".jpg").toFile()
        val authority = "${app.packageName}.fileprovider"
        var selected: File? = null
        try {
            old.writeBytes(bytes)
            val oldUri = FileProvider.getUriForFile(app, authority, old)
            assertEquals("image/jpeg", resolver.getType(oldUri))
            val copied = withContext(Dispatchers.IO) { copyAndroidChatImage(app, oldUri) }
            selected = copied
            assertEquals(type.extension, copied.extension)
            assertTrue(copied.readBytes().contentEquals(bytes))
            assertSelectedHeifSyncsWithItsNewSuffix(copied, old)

            val share = createImageShareIntent(app, old)
            val shared = requireNotNull(share.clipData?.getItemAt(0)?.uri)
            val fileName = "${old.nameWithoutExtension}.${type.extension}"
            assertEquals(Intent.ACTION_SEND, share.action)
            assertEquals(type.mimeType, share.type)
            assertEquals(type.mimeType, resolver.getType(shared))
            assertTrue(share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            resolver.query(shared, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                assertTrue(it.moveToFirst())
                assertEquals(fileName, it.getString(0))
            } ?: error("Missing FileProvider metadata")
            assertTrue(requireNotNull(resolver.openInputStream(shared)).use { it.readBytes().contentEquals(bytes) })

            val gallery = requireNotNull(writeGalleryImage(app, old))
            try {
                val columns = arrayOf(MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.MIME_TYPE)
                resolver.query(gallery, columns, null, null, null)?.use {
                    assertTrue(it.moveToFirst())
                    assertEquals(fileName, it.getString(0))
                    assertEquals(type.mimeType, it.getString(1))
                } ?: error("Missing MediaStore metadata")
                assertTrue(requireNotNull(resolver.openInputStream(gallery)).use { it.readBytes().contentEquals(bytes) })
            } finally { resolver.delete(gallery, null, null) }
            assertEquals("jpg", old.extension)
            assertTrue(old.readBytes().contentEquals(bytes))
            assertEquals("image/jpeg", resolver.getType(oldUri))
        } finally {
            selected?.delete()
            old.delete()
        }
    }

    private suspend fun assertSelectedHeifSyncsWithItsNewSuffix(selected: File, legacy: File) {
        val root = Files.createTempDirectory(cache, "synthetic-heif-transfer-").toRealPath()
        try {
            val source = Files.createDirectory(root.resolve("source"))
            val target = Files.createDirectory(root.resolve("target"))
            val sourceImages = Files.createDirectory(source.resolve("images"))
            val local = listOf(selected, legacy).map { Files.copy(it.toPath(), sourceImages.resolve(it.name)) }
            val message = ChatMessage("photo", "chat", MessageRole.USER, "合成图片",
                images = local.map { "images/${it.fileName}" }, createdAt = 2, updatedAt = 2)
            JsonFileStorage(source).apply {
                saveEntity("chat_sessions", "chat", ChatSession("chat", "card", "合成会话", createdAt = 1, updatedAt = 1),
                    ChatSession.serializer())
                saveEntity("chat_messages", "chat_photo", message, ChatMessage.serializer())
            }
            val sourceWorkspace = LanSyncWorkspace(source, Files.createDirectory(root.resolve("source-state")), source::resolve,
                validateResource = ::validateAndroidLanSyncResource)
            val targetWorkspace = LanSyncWorkspace(target, Files.createDirectory(root.resolve("target-state")), target::resolve,
                validateResource = ::validateAndroidLanSyncResource)
            sourceWorkspace.capture("android").use { capture ->
                val plan = LanSyncPlan(UUID.randomUUID().toString(), capture.snapshot, emptyMap())
                targetWorkspace.apply(plan, emptyMap(), "target", capture.blobs)
            }
            val received = requireNotNull(JsonFileStorage(target).loadEntity("chat_messages", "chat_photo", ChatMessage.serializer()))
            val originals = received.images.map { target.resolve(it) }
            assertEquals(listOf(selected.extension, "jpg"), originals.map { it.toFile().extension })
            assertTrue(originals.all { Files.readAllBytes(it).contentEquals(selected.readBytes()) })
        } finally { check(root.toFile().deleteRecursively()) }
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
            val malformed = listOf(LanSyncMalformedHeifFixture.withoutMeta(), LanSyncMalformedHeifFixture.withoutPrimaryItem(),
                LanSyncMalformedHeifFixture.truncatedMetadata(), LanSyncMalformedHeifFixture.truncatedFileType(),
                LanSyncMalformedHeifFixture.truncatedFileTypeWithHeicCompatibility())
            val unavailable = malformed.map { it to "SYNC_IMAGE_INVALID" } + listOf(
                byteArrayOf() to "SYNC_RESOURCE_BUDGET",
                LanSyncMalformedHeifFixture.unsupportedMetaVersion() to "SYNC_IMAGE_HEIF_INVALID"
            )
            for ((broken, strictCode) in unavailable) {
                Files.write(image, broken)
                val strictFailure = runCatching { workspace.capture("phone").close() }.exceptionOrNull()
                assertTrue(strictFailure is IllegalArgumentException)
                assertEquals(strictCode, strictFailure?.message)
                workspace.captureGroup("phone").use {
                    assertEquals(setOf("conversation/photo"), it.blockedResourceUnits)
                    assertEquals(good["conversation/text"], it.snapshot.hashes()["conversation/text"])
                }
                assertTrue(broken.contentEquals(Files.readAllBytes(image)))
                assertEquals(photo, storage.loadEntity("chat_messages", "photo_photo-image", ChatMessage.serializer()))
            }
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
