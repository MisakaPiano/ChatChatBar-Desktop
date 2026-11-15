package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.image.PrivacyPngEncoder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.util.zip.CRC32
import kotlin.test.*

class LanSyncRasterPolicyTest {
    private fun chunk(name: String, length: Int): ByteArray = ByteArrayOutputStream().apply {
        val type = name.toByteArray(Charsets.US_ASCII); val payload = ByteArray(length)
        DataOutputStream(this).apply {
            writeInt(length); write(type); write(payload)
            writeInt(CRC32().apply { update(type); update(payload) }.value.toInt())
        }
    }.toByteArray()
    @Test fun paletteStructureIsBoundedBeforeNativeDecodersWhileIgnoredAncillaryChunksStillHaveTheirCrcChecked() {
        val root = Files.createTempDirectory("lan-png-structure-").toRealPath()
        try {
            val png = ByteArrayOutputStream().apply { PrivacyPngEncoder.write(this, 2, 2) { _, row -> row.fill(0xff326698.toInt()) } }.toByteArray()
            val path = root.resolve("synthetic.png")
            fun atHeader(value: ByteArray) = png.copyOfRange(0, 33) + value + png.copyOfRange(33, png.size)
            Files.write(path, atHeader(chunk("PLTE", 3))); LanSyncRasterPolicy.checkPng(path, true)
            val invalid = listOf(atHeader(chunk("PLTE", 0)), atHeader(chunk("PLTE", 2)), atHeader(chunk("PLTE", 771)),
                atHeader(chunk("PLTE", 3) + chunk("PLTE", 3)),
                png.copyOfRange(0, png.size - 12) + chunk("PLTE", 3) + png.copyOfRange(png.size - 12, png.size))
            invalid.forEach { Files.write(path, it); assertFailsWith<IllegalArgumentException> { LanSyncRasterPolicy.checkPng(path, false) } }
            val ancillary = chunk("ruSt", 100)
            Files.write(path, atHeader(ancillary)); LanSyncRasterPolicy.checkPng(path, false)
            ancillary[10] = (ancillary[10].toInt() xor 1).toByte()
            Files.write(path, atHeader(ancillary)); assertFailsWith<IllegalArgumentException> { LanSyncRasterPolicy.checkPng(path, false) }
            Files.write(path, atHeader(chunk("acTL", 8)))
            assertEquals("SYNC_IMAGE_ANIMATED_RESULT", assertFailsWith<IllegalArgumentException> { LanSyncRasterPolicy.checkPng(path, true) }.message)
        } finally { check(root.toFile().deleteRecursively()) }
    }
}
