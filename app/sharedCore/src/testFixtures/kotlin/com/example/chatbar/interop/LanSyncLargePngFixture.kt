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
    fun write(path: Path, mebibyteChunks: Int) {
        require(mebibyteChunks in 0..101)
        val png = ByteArrayOutputStream().apply {
            PrivacyPngEncoder.write(this, 16, 12) { _, row -> row.fill(PIXEL) }
        }.toByteArray()
        DataOutputStream(Files.newOutputStream(path).buffered()).use { output ->
            output.write(png, 0, png.size - 12)
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
        check(Files.size(path) == png.size.toLong() + mebibyteChunks.toLong() * (1024 * 1024 + 12))
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
