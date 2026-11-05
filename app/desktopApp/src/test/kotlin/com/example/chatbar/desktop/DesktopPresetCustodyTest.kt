package com.example.chatbar.desktop

import com.example.chatbar.domain.image.NovelAiInlinePayloadStore
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopPresetCustodyTest {
    private class Fixture {
        val root = Files.createTempDirectory("preset-custody-").also { Files.createDirectory(it.resolve("entities")) }
        val gate = DesktopDataOperationCoordinator()
        val payloads = NovelAiInlinePayloadStore(root.toFile())
        val custody = DesktopPresetCustody(root)
        val cleanup = DesktopOwnedImageCleanup(root, DesktopCharacterResourceStore(root), gate)
        val value = "AQID".repeat(4096)
        val claims = mutableSetOf<String>()
        fun publish(after: (String) -> Unit = {}): String {
            var claim: DesktopPresetCustodyRecord? = null
            return payloads.retainStream(value.byteInputStream(), beforePublishNew = { reference ->
                claim = custody.beforePayload(reference, value.toByteArray().size.toLong()).also { claims += it.id }
            }, onPublished = { reference, created -> claim?.let { custody.published(it, created) }; after(reference) })
        }
        suspend fun close() { gate.closeAndDrain(); check(root.toFile().deleteRecursively()) }
    }
    @Test fun `only newly published proven unreferenced encoding is removed after cancellation and preexisting same hash is retained`() = runBlocking {
        val f = Fixture()
        try {
            var reference = ""
            assertFailsWith<CancellationException> { f.publish { reference = it; throw CancellationException("synthetic cancellation after publication") } }
            assertTrue(Files.exists(f.root.resolve("novelai-vibe-payloads/${reference.substringAfter(':')}")))
            assertTrue(f.custody.finishPayloads(f.cleanup::deleteUnreferenced, f.claims))
            assertFalse(Files.exists(f.root.resolve("novelai-vibe-payloads/${reference.substringAfter(':')}")))
            assertTrue(f.custody.records().isEmpty())
            val existing = f.payloads.retain(f.value)
            val original = Files.readAllBytes(f.root.resolve("novelai-vibe-payloads/${existing.substringAfter(':')}"))
            f.claims.clear(); assertEquals(existing, f.publish()); assertTrue(f.claims.isEmpty())
            assertTrue(f.custody.finishPayloads(f.cleanup::deleteUnreferenced, f.claims))
            assertContentEquals(original, Files.readAllBytes(f.root.resolve("novelai-vibe-payloads/${existing.substringAfter(':')}")))
        } finally { f.close() }
    }

    @Test fun `other durable references and unverified archive authorities retain new encoding with exact receipts`() = runBlocking {
        val f = Fixture()
        try {
            val reference = f.publish()
            Files.writeString(f.root.resolve("entities/another.json"), """{"encodedVibe":"$reference"}""")
            assertTrue(f.custody.finishPayloads(f.cleanup::deleteUnreferenced, f.claims))
            assertEquals("RETAINED", f.custody.records().single().phase)
            assertEquals(f.value, f.payloads.resolve(reference))
            Files.writeString(f.root.resolve("entities/another.json"), "{}")
            val archive = Files.write(f.root.resolve("backups/other.cbsave"), byteArrayOf(1, 2, 3))
            assertFalse(f.custody.finishPayloads(f.cleanup::deleteUnreferenced, f.claims))
            assertEquals("UNCONFIRMED", f.custody.records().single().phase)
            assertEquals(f.value, f.payloads.resolve(reference)); assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(archive))
        } finally { f.close() }
    }

    @Test fun `publication uncertainty never grants deletion while race winner is not attributed as this imports creation`() = runBlocking {
        val f = Fixture()
        try {
            var claim: DesktopPresetCustodyRecord? = null
            assertFails {
                f.payloads.retainStream(f.value.byteInputStream(), beforePublishNew = {
                    claim = f.custody.beforePayload(it, f.value.toByteArray().size.toLong())
                }, onPublished = { _, _ -> error("synthetic receipt completion failure") })
            }
            val pending = assertNotNull(claim)
            assertFalse(f.custody.finishPayloads(f.cleanup::deleteUnreferenced, setOf(pending.id)))
            assertEquals("PUBLISH_ATTEMPTED", f.custody.records().single().phase)
            assertEquals(f.value, f.payloads.resolve(NovelAiInlinePayloadStore.PREFIX + pending.hash))
            // Clear only this fixture's uncertain file to construct an independent same-hash race.
            Files.delete(f.root.resolve("novelai-vibe-payloads/${pending.hash}")); f.custody.remove(pending)
            var racedClaim: DesktopPresetCustodyRecord? = null
            val value = f.payloads.retainStream(f.value.byteInputStream(), beforePublishNew = {
                racedClaim = f.custody.beforePayload(it, f.value.toByteArray().size.toLong())
                f.payloads.retain(f.value)
            }, onPublished = { _, created -> assertFalse(created); f.custody.published(assertNotNull(racedClaim), created) })
            assertTrue(f.custody.records().isEmpty()); assertEquals(f.value, f.payloads.resolve(value))
        } finally { f.close() }
    }
}
