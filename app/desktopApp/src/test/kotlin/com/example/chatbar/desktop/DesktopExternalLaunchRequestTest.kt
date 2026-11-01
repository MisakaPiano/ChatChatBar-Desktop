package com.example.chatbar.desktop

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*

class DesktopExternalLaunchRequestTest {
    @Test fun `ASCII invocation reads an exact ordered UTF8 batch without changing original files or request`() = fixture { root, home, request, env ->
        val files = listOf("ASCII & source.json", "中文 source.json", "中文 & source.json", "日本語 😀 source.json")
            .map { Files.writeString(root.resolve(it), "SYNTHETIC_ORIGINAL") }
        val body = encode(files.map { it.toString().toByteArray() }); Files.write(request, body)
        val args = arrayOf("--open-request", nonce, hash(body))
        assertTrue(args.all { text -> text.all { it.code < 128 } })
        assertEquals(files, DesktopExternalLaunchRequest.paths(args, home, env))
        assertContentEquals(body, Files.readAllBytes(request))
        files.forEach { assertEquals("SYNTHETIC_ORIGINAL", Files.readString(it)) }
    }

    @Test fun `request binding hash UTF8 framing and ordinary path guards refuse before queue admission`() = fixture { root, home, request, env ->
        val valid = encode(listOf(root.resolve("中文 & source.json").toString().toByteArray()))
        Files.write(request, valid)
        assertFails { DesktopExternalLaunchRequest.paths(arrayOf("--open-request", nonce, "0".repeat(64)), home, env) }
        assertFails { DesktopExternalLaunchRequest.paths(arrayOf("--open-request", nonce, hash(valid)), home,
            env + ("CCB_DESKTOP_ACTIVATION_NONCE" to "b".repeat(32))) }
        assertFails { DesktopExternalLaunchRequest.paths(arrayOf("--open-request", nonce, hash(valid)), root, env) }
        for (body in listOf(encode(listOf(byteArrayOf(0xc3.toByte(), 0x28))),
            encode(listOf("relative.json".toByteArray())), encode(emptyList()), valid + byteArrayOf(1), ByteArray(1048577))) {
            Files.write(request, body)
            assertFails { DesktopExternalLaunchRequest.paths(arrayOf("--open-request", nonce, hash(body)), home, env) }
            assertContentEquals(body, Files.readAllBytes(request))
        }
    }

    private fun encode(paths: List<ByteArray>): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(0x43434232); output.writeInt(paths.size)
            paths.forEach { output.writeInt(it.size); output.write(it) }
        }
        bytes.toByteArray()
    }
    private val nonce = "a".repeat(32)
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun fixture(body: (Path, Path, Path, Map<String, String>) -> Unit) {
        val root = Files.createTempDirectory("external-request-test-")
        try {
            val home = Files.createDirectories(root.resolve("versions/release"))
            val receipts = Files.createDirectory(root.resolve("receipts"))
            body(root, home, receipts.resolve("$nonce.open-request"), mapOf(
                "CCB_DESKTOP_ACTIVATION_NONCE" to nonce, "CCB_DESKTOP_ACTIVATION_PATH" to receipts.resolve("$nonce.activation").toString()))
        } finally { root.toFile().deleteRecursively() }
    }
}
