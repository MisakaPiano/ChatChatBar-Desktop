package com.example.chatbar.lanSync

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chatbar.domain.lanSync.LanSyncRecord
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidLanSyncBmpResourceTest {
    @Test fun bmpSavedWithJpegSuffixKeepsItsBytesAndRendersTheCorrectPreview() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.toPath()
        val root = Files.createTempDirectory(cache, "synthetic-lan-bmp-")
        try {
            val file = root.resolve("background.jpg")
            val bytes = ByteBuffer.allocate(70).order(ByteOrder.LITTLE_ENDIAN).apply {
                put('B'.code.toByte())
                put('M'.code.toByte())
                putInt(70)
                putInt(0)
                putInt(54)
                putInt(40)
                putInt(2)
                putInt(2)
                putShort(1)
                putShort(24)
                putInt(0)
                putInt(16)
                repeat(4) { putInt(0) }
                put(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0))
                put(byteArrayOf(0x66, 0x55, 0x33, 0, 0, 0, 0, 0))
            }.array()
            Files.write(file, bytes)
            val record = LanSyncRecord("chat_sessions", "synthetic", buildJsonObject { put("id", "synthetic") })
            validateAndroidLanSyncResource("images", file, record)
            val preview = androidLanSyncPreviewRaster(file, record)
            requireNotNull(BitmapFactory.decodeByteArray(preview, 0, preview.size)).let { bitmap ->
                try {
                    assertEquals(2, bitmap.width)
                    assertEquals(2, bitmap.height)
                    assertEquals(0xff335566.toInt(), bitmap.getPixel(0, 0))
                } finally { bitmap.recycle() }
            }
            assertTrue(bytes.contentEquals(Files.readAllBytes(file)))
        } finally { check(root.toFile().deleteRecursively()) }
    }
}
