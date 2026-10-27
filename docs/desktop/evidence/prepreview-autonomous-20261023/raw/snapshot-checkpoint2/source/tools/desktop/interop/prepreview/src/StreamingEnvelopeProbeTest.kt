package com.example.chatbar.desktop.backup

import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import org.junit.Test
import kotlin.test.*

class StreamingEnvelopeProbeTest {
    private fun encrypted(): ByteArray = ByteArrayOutputStream().also { output ->
        AppBackupCodec.encrypting(output, "SYNTHETIC_PASSWORD_ONLY".toCharArray()).use { encrypted ->
            repeat(34) { encrypted.write(ByteArray(65536) { 7 }) }
        }
    }.toByteArray()

    @Test fun `stream authenticates multiple segments without archive semantic claim`() {
        val bytes = encrypted()
        val result = StreamingEnvelopeProbe.consume(bytes.inputStream(), "SYNTHETIC_PASSWORD_ONLY".toCharArray())
        assertTrue(result.encrypted); assertEquals(1, result.version); assertEquals(34L * 65536, result.bytes)
    }
    @Test fun `wrong password and late authentication failure never return result`() {
        val bytes = encrypted()
        assertFails { StreamingEnvelopeProbe.consume(bytes.inputStream(), "SYNTHETIC_WRONG_PASSWORD".toCharArray()) }
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertFails { StreamingEnvelopeProbe.consume(bytes.inputStream(), "SYNTHETIC_PASSWORD_ONLY".toCharArray()) }
    }
    @Test fun `budget failure closes input and does not publish result`() {
        var closed = false
        val input = object : ByteArrayInputStream(encrypted()) { override fun close() { closed = true; super.close() } }
        assertFails { StreamingEnvelopeProbe.consume(input, "SYNTHETIC_PASSWORD_ONLY".toCharArray(), 1) }
        assertTrue(closed)
    }
    @Test fun `plain input is never called cryptographically authenticated`() {
        val output = ByteArrayOutputStream()
        AppBackupCodec.encrypting(output, null).use { it.write(byteArrayOf(1, 2, 3)) }
        assertFalse(StreamingEnvelopeProbe.consume(output.toByteArray().inputStream(), null).encrypted)
    }
}
