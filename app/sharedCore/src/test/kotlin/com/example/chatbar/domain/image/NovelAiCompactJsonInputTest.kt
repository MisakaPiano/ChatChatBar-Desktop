package com.example.chatbar.domain.image

import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import kotlin.test.*

class NovelAiCompactJsonInputTest {
    @Test fun `large streamed value becomes a compact reference with original source unchanged`() {
        val root = Files.createTempDirectory("compact-json-large-")
        try {
            val file = root.resolve("source.json"); val block = "AQID".repeat(2048)
            Files.newBufferedWriter(file).use { output ->
                output.write("{\"ordinary\":\"unchanged\",\"encodedVibe\":\"")
                repeat(1024) { output.write(block) }; output.write("\"}")
            }
            fun sha() = Files.newInputStream(file).use { input ->
                val hash = MessageDigest.getInstance("SHA-256"); val bytes = ByteArray(65536)
                while (true) { val n = input.read(bytes); if (n < 0) break; hash.update(bytes, 0, n) }
                hash.digest().toList()
            }
            val before = sha(); val store = NovelAiInlinePayloadStore(root.toFile())
            val compact = store.openCompactJson(file.toFile()).use { it.readBytes() }
            assertTrue(compact.size < 512)
            val decoded = Json.parseToJsonElement(compact.toString(Charsets.UTF_8)).jsonObject
            assertEquals("unchanged", decoded.getValue("ordinary").jsonPrimitive.content)
            val ref = decoded.getValue("encodedVibe").jsonPrimitive.content
            assertTrue(ref.startsWith(NovelAiInlinePayloadStore.PREFIX))
            val payload = root.resolve("novelai-vibe-payloads").resolve(ref.substringAfter(':'))
            assertEquals(8L * 1024 * 1024, Files.size(payload))
            assertEquals(before, sha())
            assertEquals(1L, Files.list(payload.parent).use { it.count() })
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `escaped keys and values compact while ordinary quoted text and Unicode remain exact`() {
        val root = Files.createTempDirectory("compact-json-escape-")
        try {
            val expected = "AQID".repeat(2048)
            val escaped = "\\u0041\\u0051\\u0049\\u0044".repeat(2048)
            val ordinary = "a".repeat(2039) + "😀 contains \"encodedVibe\":\"literal\"\n中文"
            val original = "{\"encoded\\u0056ibe\":\"$escaped\",\"ordinary\":${Json.encodeToJsonElement(ordinary)}}"
            val file = Files.writeString(root.resolve("source.json"), original)
            val store = NovelAiInlinePayloadStore(root.toFile())
            val result = store.openCompactJson(file.toFile()).use { Json.parseToJsonElement(it.readBytes().toString(Charsets.UTF_8)).jsonObject }
            val ref = result.getValue("encodedVibe").jsonPrimitive.content
            assertTrue(ref.startsWith(NovelAiInlinePayloadStore.PREFIX))
            assertEquals(expected, store.resolve(ref))
            assertEquals(ordinary, result.getValue("ordinary").jsonPrimitive.content)
            assertEquals(original, Files.readString(file))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `invalid escapes truncation and cancellation retain source and remove only owned spools`() {
        val root = Files.createTempDirectory("compact-json-interruption-")
        try {
            val store = NovelAiInlinePayloadStore(root.toFile()); val file = root.resolve("source.json")
            for (bad in listOf("{\"encodedVibe\":\"\\x\"}", "{\"encodedVibe\":\"" + "Q".repeat(10000))) {
                Files.writeString(file, bad)
                assertFails { store.openCompactJson(file.toFile()).use { it.readBytes() } }
                assertEquals(bad, Files.readString(file))
            }
            val original = "{\"encodedVibe\":\"" + "Q".repeat(100000) + "\"}"
            Files.writeString(file, original); var checks = 0
            assertFailsWith<CancellationException> {
                store.openCompactJson(file.toFile()) { if (++checks == 3) throw CancellationException("synthetic stop") }.use { it.readBytes() }
            }
            assertEquals(original, Files.readString(file))
            assertTrue(Files.list(root.resolve("novelai-vibe-payloads")).use { it.noneMatch { p -> p.fileName.toString().endsWith(".tmp") } })
        } finally { root.toFile().deleteRecursively() }
    }
}
