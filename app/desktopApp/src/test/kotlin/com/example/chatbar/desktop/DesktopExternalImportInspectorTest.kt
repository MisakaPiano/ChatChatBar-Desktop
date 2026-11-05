package com.example.chatbar.desktop

import com.example.chatbar.domain.card.FormatCardPackage
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.zip.CRC32
import javax.imageio.ImageIO
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlin.test.*

class DesktopExternalImportInspectorTest {
    @Test fun `content inspection freezes a small typed file without relying on its extension`() = runBlocking {
        val root = Files.createTempDirectory("external-inspect-")
        try {
            val raw = Json.encodeToString(FormatCardPackage.serializer(), FormatCardPackage(name = "Synthetic", content = "Retained content"))
            val source = Files.writeString(root.resolve("misleading.png"), raw)
            val prepared = DesktopExternalImportInspector.prepare(source) { Files.createDirectory(root.resolve("staging")) }
            assertEquals(DesktopExternalImportKind.FORMAT, prepared.kind)
            Files.writeString(source, "changed only by this test")
            DesktopExternalImportInspector.requireTyped(prepared, DesktopExternalImportKind.FORMAT)
            assertEquals(raw, Files.readString(prepared.file))
            DesktopExternalImportInspector.release(prepared)
            assertFalse(Files.exists(root.resolve("staging")))
            assertEquals("changed only by this test", Files.readString(source))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `oversized image metadata failure releases only its owned preparation copy`() = runBlocking {
        val root = Files.createTempDirectory("external-inspect-failure-")
        try {
            val source = root.resolve("synthetic.png")
            val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
            try { ImageIO.write(image, "png", source.toFile()) } finally { image.flush() }
            val bytes = Files.readAllBytes(source)
            ByteBuffer.wrap(bytes).putInt(16, 100000).putInt(20, 100000)
            val crc = CRC32().apply { update(bytes, 12, 17) }.value
            ByteBuffer.wrap(bytes).putInt(29, crc.toInt()); Files.write(source, bytes)
            assertFails { DesktopExternalImportInspector.prepare(source) { Files.createDirectory(root.resolve("staging")) } }
            assertFalse(Files.exists(root.resolve("staging")))
            assertContentEquals(bytes, Files.readAllBytes(source))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `copy cancellation drains its owned temporary directory while preserving source bytes`() = runBlocking {
        val root = Files.createTempDirectory("external-inspect-cancel-")
        try {
            val bytes = ByteArray(65536) { (it % 121).toByte() }
            val source = Files.write(root.resolve("synthetic.json"), bytes)
            val work = launch {
                val current = currentCoroutineContext()[Job]!!
                DesktopExternalImportInspector.prepare(source) {
                    Files.createDirectory(root.resolve("staging")).also { current.cancel() }
                }
            }
            work.join(); assertTrue(work.isCancelled)
            assertFalse(Files.exists(root.resolve("staging")))
            assertContentEquals(bytes, Files.readAllBytes(source))
        } finally { root.toFile().deleteRecursively() }
    }
}
