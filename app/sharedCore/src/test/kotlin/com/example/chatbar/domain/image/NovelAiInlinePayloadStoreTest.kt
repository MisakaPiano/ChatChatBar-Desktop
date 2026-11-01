package com.example.chatbar.domain.image

import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.*

class NovelAiInlinePayloadStoreTest {
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
