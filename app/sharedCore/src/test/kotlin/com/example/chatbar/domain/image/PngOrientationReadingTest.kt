package com.example.chatbar.domain.image

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import kotlin.test.*

class PngOrientationReadingTest {
    @Test fun `memory reader matches existing file reader for every EXIF orientation without changing bytes`() {
        for (orientation in 1..8) {
            val png = ByteArrayOutputStream().use { output ->
                PrivacyPngEncoder.write(output, 3, 2, orientation) { _, row -> row.fill(0xff404040.toInt()) }
                output.toByteArray()
            }
            val original = png.copyOf()
            val path = Files.createTempFile("synthetic-png-direction-", ".png")
            try {
                Files.write(path, png)
                assertEquals(orientation, ImageMetadataStripper.readPngOrientation(png))
                assertEquals(ImageMetadataStripper.readOrientation(path.toFile()), ImageMetadataStripper.readPngOrientation(png))
                assertContentEquals(original, png)
            } finally { Files.deleteIfExists(path) }
        }
    }

    @Test fun `invalid PNG header truncated chunk and oversized EXIF remain explicit failures`() {
        assertFails { ImageMetadataStripper.readPngOrientation(byteArrayOf()) }
        assertFails { ImageMetadataStripper.readPngOrientation(ByteArray(8)) }
        val signature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        assertFails { ImageMetadataStripper.readPngOrientation(signature + byteArrayOf(0, 0, 0)) }
        val oversized = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(signature); output.writeInt(Int.MAX_VALUE); output.writeBytes("eXIf")
            }
            bytes.toByteArray()
        }
        assertFails { ImageMetadataStripper.readPngOrientation(oversized) }
    }
}
