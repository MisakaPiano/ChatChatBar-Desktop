package com.example.chatbar.desktop

import com.example.chatbar.desktop.backup.AppBackupCodec
import java.nio.file.Files
import java.nio.charset.CharacterCodingException
import kotlin.test.*

class DesktopTypedImportBytesTest {
    @Test fun `known backup and ZIP headers never reach the whole file reader even with card suffixes`() {
        val root = Files.createTempDirectory("typed-archive-prefix-")
        try {
            val headers = listOf(AppBackupCodec.magic) + listOf(3 to 4, 5 to 6, 7 to 8, 6 to 6, 6 to 7)
                .map { byteArrayOf(0x50, 0x4b, it.first.toByte(), it.second.toByte()) }
            for ((index, head) in headers.withIndex()) for (extension in listOf("json", "png", "cbbackup", "cbsave")) {
                val bytes = head + ByteArray(20)
                val path = Files.write(root.resolve("synthetic-$index.$extension"), bytes)
                var wholeReads = 0
                val failure = assertFailsWith<IllegalStateException> {
                    DesktopTypedImportBytes.read(path) { wholeReads++; error("Must not read an archive body") }
                }
                assertTrue(failure.message.orEmpty().contains("数据 → 备份与导入"))
                assertEquals(0, wholeReads)
                assertContentEquals(bytes, Files.readAllBytes(path))
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `legal content reaches original reader unchanged regardless of filename or short JSON length`() {
        val root = Files.createTempDirectory("typed-normal-prefix-")
        try {
            for ((index, bytes) in listOf("{}".toByteArray(), "\uFEFF  {\"name\":\"合成角色\"}".toByteArray(),
                byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) + ByteArray(20)).withIndex()) {
                val path = Files.write(root.resolve("legal-$index.cbbackup"), bytes)
                var reads = 0
                assertContentEquals(bytes, DesktopTypedImportBytes.read(path) { reads++; Files.readAllBytes(it) })
                assertEquals(1, reads)
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `JSON-only reader preserves strict UTF8 and does not reinterpret malformed input`() {
        val root = Files.createTempDirectory("typed-utf8-")
        try {
            val path = Files.writeString(root.resolve("card.json"), "\uFEFF{\"text\":\"中文\"}")
            assertEquals(Files.readString(path), DesktopTypedImportBytes.text(path))
            Files.write(path, byteArrayOf(0x7b, 0xc3.toByte(), 0x28, 0x7d))
            assertFailsWith<CharacterCodingException> { DesktopTypedImportBytes.text(path) }
        } finally { root.toFile().deleteRecursively() }
    }
}
