package com.example.chatbar.desktop

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlin.test.*

class DesktopLanSyncGroupInvitationQrTest {
    @Test fun `dense v1 text retains its exact QR payload and white quiet zone at physical integer sizes`() {
        val prefix = "CCB-GROUP-JOIN1:"
        val code = prefix + "a".repeat(2200 - prefix.length)
        val matrix = assertNotNull(desktopGroupInvitationQrMatrix(code))
        assertEquals(165, matrix.width) // Version 35 plus four modules of white on each side.
        assertEquals(matrix.width, matrix.height)
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (x < 4 || y < 4 || x >= matrix.width - 4 || y >= matrix.height - 4)
                assertFalse(matrix[x, y])
        }
        for (availablePixels in listOf(330, 350, 420, 560, 840)) {
            val image = assertNotNull(desktopGroupInvitationQrImage(matrix, availablePixels))
            assertTrue(image.width <= availablePixels)
            assertEquals(0, image.width % matrix.width)
            val scale = image.width / matrix.width
            assertTrue(scale >= 2)
            val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
            for (y in 0 until image.height) for (x in 0 until image.width) {
                val expected = if (matrix[x / scale, y / scale]) 0 else 0xffffff
                assertEquals(expected, pixels[y * image.width + x] and 0xffffff)
            }
            val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(
                RGBLuminanceSource(image.width, image.height, pixels)))).text
            assertTrue(decoded == code, "QR payload changed") // Never print the invitation text on failure.
        }
    }

    @Test fun `narrow viewport keeps a whole two pixel minimum instead of squeezing a scan target`() {
        val matrix = assertNotNull(desktopGroupInvitationQrMatrix("CCB-GROUP-JOIN1:" + "a".repeat(2185)))
        for (availablePixels in listOf(0, matrix.width - 1, matrix.width, matrix.width * 2 - 1))
            assertNull(desktopGroupInvitationQrImage(matrix, availablePixels))
        assertEquals(matrix.width * 2, assertNotNull(
            desktopGroupInvitationQrImage(matrix, matrix.width * 2)).width)
    }

    @Test fun `text beyond QR capacity returns the copy only presentation`() {
        assertNull(desktopGroupInvitationQrMatrix("CCB-GROUP-JOIN1:" + "a".repeat(4000)))
    }
}
