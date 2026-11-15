package com.example.chatbar.interop

import com.example.chatbar.domain.image.PrivacyPngEncoder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.CRC32

/** Fully written valid PNG; encoded length grows, decoded pixels stay 16x12. No seed assets. */
object LanSyncLargePngFixture {
    const val PIXEL = 0xff326698.toInt()
    fun write(path: Path, mebibyteChunks: Int, largeExif: Boolean = false) {
        require(mebibyteChunks in 0..101)
        val png = ByteArrayOutputStream().apply {
            PrivacyPngEncoder.write(this, 16, 12) { _, row -> row.fill(PIXEL) }
        }.toByteArray()
        DataOutputStream(Files.newOutputStream(path).buffered()).use { output ->
            output.write(png, 0, png.size - 12)
            if (largeExif) {
                val length = 17 * 1024 * 1024
                // Valid big-endian TIFF: orientation plus a bounded-offset opaque MakerNote.
                val header = ByteArrayOutputStream().apply { DataOutputStream(this).apply {
                    writeShort(0x4d4d); writeShort(42); writeInt(8); writeShort(2)
                    writeShort(0x0112); writeShort(3); writeInt(1); writeShort(1); writeShort(0)
                    writeShort(0x927c); writeShort(7); writeInt(length - 38); writeInt(38); writeInt(0)
                } }.toByteArray()
                val type = "eXIf".toByteArray(Charsets.US_ASCII)
                output.writeInt(length); output.write(type)
                val crc = CRC32().apply { update(type); update(header) }; output.write(header)
                val buffer = ByteArray(64 * 1024); var remaining = length - header.size
                while (remaining > 0) { val count = minOf(remaining, buffer.size); output.write(buffer, 0, count); crc.update(buffer, 0, count); remaining -= count }
                output.writeInt(crc.value.toInt())
            }
            val payload = ByteArray(64 * 1024) { (it % 251).toByte() }
            val type = "ruSt".toByteArray(Charsets.US_ASCII)
            repeat(mebibyteChunks) {
                output.writeInt(1024 * 1024); output.write(type)
                val crc = CRC32().apply { update(type) }
                repeat(16) { output.write(payload); crc.update(payload) }
                output.writeInt(crc.value.toInt())
            }
            output.write(png, png.size - 12, 12)
        }
        check(Files.size(path) == png.size.toLong() + mebibyteChunks.toLong() * (1024 * 1024 + 12) + if (largeExif) 17L * 1024 * 1024 + 12 else 0)
    }

    fun hash(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
