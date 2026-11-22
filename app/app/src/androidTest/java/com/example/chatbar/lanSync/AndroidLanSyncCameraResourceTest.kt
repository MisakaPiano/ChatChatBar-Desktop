package com.example.chatbar.lanSync

import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chatbar.domain.lanSync.LanSyncHeifStill
import com.example.chatbar.domain.lanSync.LanSyncRecord
import com.example.chatbar.domain.lanSync.LanSyncResourceStore
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidLanSyncCameraResourceTest {
    private val cache get() = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.toPath()
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
        assertEquals(64, dimensions.width); assertEquals(48, dimensions.height)
        assertPreviewAndRetainedOriginal(file)
    }

    private fun writeJpeg(file: Path) {
        val width = 8000; val height = 6000
        val camera = YuvImage(ByteArray(width * height * 3 / 2) { 96 }, ImageFormat.NV21, width, height, null)
        Files.newOutputStream(file).use { assertTrue(camera.compressToJpeg(Rect(0, 0, width, height), 85, it)) }
    }
    private fun assertPreviewAndRetainedOriginal(file: Path) {
        val length = Files.size(file)
        val sha = LanSyncResourceStore.hashFile(file, length)
        validateAndroidLanSyncResource("images", file, record)
        val preview = androidLanSyncPreviewRaster(file, record)
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(preview, 0, preview.size))
        try { assertTrue(bitmap.width in 1..2048 && bitmap.height in 1..2048) } finally { bitmap.recycle() }
        assertEquals(length, Files.size(file))
        assertEquals(sha, LanSyncResourceStore.hashFile(file, length))
    }
}
