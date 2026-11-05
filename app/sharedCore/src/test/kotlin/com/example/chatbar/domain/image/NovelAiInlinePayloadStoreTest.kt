package com.example.chatbar.domain.image

import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.*

class NovelAiInlinePayloadStoreTest {
    @Test fun `portable stream exchange preserves payload bytes and failures cancel without replacing stored content`() {
        val root = Files.createTempDirectory("vibe-payload-stream-")
        val other = Files.createTempDirectory("vibe-payload-import-")
        try {
            val store = NovelAiInlinePayloadStore(root.toFile())
            val bytes = "AQID".repeat(32768).toByteArray()
            val ref = store.retainStream(bytes.inputStream())
            val output = java.io.ByteArrayOutputStream(); store.copyResolved(ref, output)
            assertContentEquals(bytes, output.toByteArray())
            val imported = NovelAiInlinePayloadStore(other.toFile())
            assertEquals(ref, imported.retainStream(output.toByteArray().inputStream()))
            assertEquals(ref, imported.retainStream(output.toByteArray().inputStream()))
            val file = root.resolve("novelai-vibe-payloads").resolve(ref.substringAfter(':'))
            val original = Files.readAllBytes(file)
            assertEquals("AQID", imported.retainStream("AQID".byteInputStream()))
            assertFails { imported.retainStream((bytes + byteArrayOf(0xc0.toByte(), 0xaf.toByte())).inputStream()) }
            var checks = 0
            assertFailsWith<CancellationException> { imported.retainStream(bytes.inputStream()) {
                if (++checks == 3) throw CancellationException("synthetic cancellation")
            } }
            assertFailsWith<java.io.IOException> { store.copyResolved(ref, object : java.io.OutputStream() {
                override fun write(value: Int) { throw java.io.IOException("synthetic write refusal") }
            }) }
            assertContentEquals(original, Files.readAllBytes(file))
            assertEquals(1L, Files.list(other.resolve("novelai-vibe-payloads")).use { it.count() })
            assertEquals(String(bytes, Charsets.UTF_8), imported.resolve(ref))
        } finally { root.toFile().deleteRecursively(); other.toFile().deleteRecursively() }
    }
    @Test fun `legacy inline stays inline while large payload is retained once and resolves exactly`() = runBlocking {
        val root = Files.createTempDirectory("vibe-payload-")
        try {
            val store = NovelAiInlinePayloadStore(root.toFile())
            assertEquals("AQID", store.retain("AQID")); assertEquals("AQID", store.resolve("AQID"))
            assertFalse(Files.exists(root.resolve("novelai-vibe-payloads")))
            val value = "AQID".repeat(4096)
            val refs = (1..5).map { async(Dispatchers.IO) { NovelAiInlinePayloadStore(root.toFile()).retain(value) } }.awaitAll()
            assertEquals(1, refs.distinct().size)
            assertTrue(refs.first().startsWith(NovelAiInlinePayloadStore.PREFIX))
            assertEquals(value, store.resolve(refs.first()))
            assertEquals(refs.first(), store.retain(refs.first()))
            assertEquals(1L, Files.list(root.resolve("novelai-vibe-payloads")).use { it.count() })
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `invalid missing and altered references fail without replacing original bytes`() {
        val root = Files.createTempDirectory("vibe-payload-refusal-")
        try {
            val store = NovelAiInlinePayloadStore(root.toFile())
            for (suffix in listOf("../outside", "A".repeat(64), "x", "")) {
                assertFails { store.resolve(NovelAiInlinePayloadStore.PREFIX + suffix) }
            }
            assertFails { store.resolve(NovelAiInlinePayloadStore.PREFIX + "0".repeat(64)) }
            assertFalse(Files.exists(root.resolve("novelai-vibe-payloads")))
            val ref = store.retain("AQID".repeat(1024))
            val file = root.resolve("novelai-vibe-payloads").resolve(ref.substringAfter(':'))
            Files.writeString(file, "altered")
            assertFails { store.resolve(ref) }
            assertFails { store.retain("AQID".repeat(1024)) }
            assertEquals("altered", Files.readString(file))
        } finally { root.toFile().deleteRecursively() }
    }
}
