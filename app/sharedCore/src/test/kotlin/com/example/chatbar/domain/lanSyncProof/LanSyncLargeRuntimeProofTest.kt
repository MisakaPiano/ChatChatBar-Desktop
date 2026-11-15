package com.example.chatbar.domain.lanSyncProof

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.lanSync.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import kotlin.test.*

/** Explicit cloud-only proof. All bytes are synthetic, fully written, and excluded from artifacts. */
class LanSyncLargeRuntimeProofTest {
    private suspend fun until(check: () -> Boolean) = withTimeout(15 * 60_000L) {
        while (!check()) delay(100)
    }
    private suspend fun completed(a: LanSyncRuntimeTest.End, b: LanSyncRuntimeTest.End, previous: String? = null) = until {
        val left = a.store.baseline(b.id, a.id).revision; val right = b.store.baseline(a.id, b.id).revision
        left != null && left == right && left != previous && a.store.pending(b.id) == null && b.store.pending(a.id) == null
    }
    private fun <T> write(root: Path, type: String, id: String, serializer: KSerializer<T>, value: T) {
        val file = root.resolve("entities/$type/$id.json"); Files.createDirectories(file.parent)
        LanSyncJsonFiles.write(file, serializer, value)
    }
    private fun document(path: Path, length: Long, seed: Int): String {
        Files.createDirectories(path.parent)
        val digest = MessageDigest.getInstance("SHA-256")
        FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            val buffer = ByteArray(256 * 1024) { ((it + seed) % 251).toByte() }
            var written = 0L
            while (written < length) {
                val size = minOf(buffer.size.toLong(), length - written).toInt()
                val block = ByteBuffer.wrap(buffer, 0, size)
                while (block.hasRemaining()) channel.write(block)
                digest.update(buffer, 0, size); written += size
            }
            channel.force(true); assertEquals(length, channel.size())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun archive(root: Path): Pair<SaveSlot, String> {
        val folder = root.resolve("save_slot_packages"); Files.createDirectories(folder)
        val lines = folder.resolve("fully-written-messages.jsonl")
        val count = 4096
        val content = "x".repeat(65_536)
        val crc = CRC32()
        Files.newOutputStream(lines, StandardOpenOption.CREATE_NEW).buffered(64 * 1024).use { output ->
            repeat(count) { index ->
                val message = ChatMessage("archived-$index", "session", MessageRole.USER, content,
                    createdAt = index + 1L, updatedAt = index + 1L)
                val bytes = (lanSyncJson.encodeToString(ChatMessage.serializer(), message) + "\n").toByteArray(Charsets.UTF_8)
                output.write(bytes); crc.update(bytes)
            }
        }
        val slot = SaveSlot(schemaVersion = 8, id = "large-slot", sessionId = "session", name = "合成大存档",
            imagePolicy = SaveSlotImagePolicy.NONE, includeAudio = false,
            packageRef = SaveSlotPackageRef("large.cbsave", messageCount = count), createdAt = 1)
        val file = folder.resolve("large.cbsave")
        ZipOutputStream(Files.newOutputStream(file, StandardOpenOption.CREATE_NEW)).use { zip ->
            val manifest = lanSyncJson.encodeToString(SaveSlot.serializer(), slot).toByteArray(Charsets.UTF_8)
            fun entry(name: String, size: Long, checksum: Long, write: () -> Unit) {
                zip.putNextEntry(ZipEntry(name).apply { method = ZipEntry.STORED; this.size = size; compressedSize = size; this.crc = checksum })
                write(); zip.closeEntry()
            }
            entry("manifest.json", manifest.size.toLong(), CRC32().apply { update(manifest) }.value) { zip.write(manifest) }
            entry("messages.jsonl", Files.size(lines), crc.value) { Files.newInputStream(lines).use { it.copyTo(zip, 64 * 1024) } }
            entry("rag.jsonl", 0, 0) {}
        }
        Files.delete(lines)
        assertTrue(Files.size(file) > 256L * 1024 * 1024)
        val outer = slot.copy(packageRef = slot.packageRef!!.copy(byteLength = Files.size(file)))
        LanSyncSlotPackages.validate(file, lanSyncJson.encodeToJsonElement(SaveSlot.serializer(), outer).jsonObject)
        return outer to LanSyncResourceStore.hashFile(file, Files.size(file))
    }

    @Test fun realRuntimeTransmitsAppliesAndReopensOverOneGiBWithLargeV8AndIncrementalRounds() = runBlocking {
        org.junit.Assume.assumeTrue("requires explicit large proof runner", System.getenv("CCB_LAN_LARGE_PROOF") == "true")
        val network = LanSyncRuntimeTest.SyntheticLan()
        val sent = AtomicLong(); val received = AtomicLong()
        val progress: (String, Long) -> Unit = { stage, count -> when (stage) {
            "RESOURCE_CHUNK_SENT_BYTES" -> sent.addAndGet(count)
            "RESOURCE_CHUNK_RECEIVED_BYTES" -> received.addAndGet(count)
        }; Unit }
        val a = LanSyncRuntimeTest.End("large-computer", network, progress = progress)
        val b = LanSyncRuntimeTest.End("large-phone", network, progress = progress)
        val host = a.runtime(); val phone = b.runtime()
        val began = System.nanoTime()
        var largestHeap = 0L; var minimumFree = Files.getFileStore(a.root).usableSpace
        val sample = launch(Dispatchers.IO) { while (isActive) {
            val runtime = Runtime.getRuntime(); largestHeap = maxOf(largestHeap, runtime.totalMemory() - runtime.freeMemory())
            minimumFree = minOf(minimumFree, Files.getFileStore(a.root).usableSpace); delay(100)
        } }
        try {
            require(minimumFree >= 10L * 1024 * 1024 * 1024) { "SYNTHETIC_PROOF_NEEDS_FREE_DISK" }
            val length = 256L * 1024 * 1024 + 65_536
            val expected = linkedMapOf<String, Long>()
            val docs = (0..3).map { index ->
                val path = a.root.resolve("documents/full-$index.bin")
                expected[document(path, length, index + 1)] = length
                DocumentInfo("document-$index", path.fileName.toString(), path.toString(), "application/octet-stream", addedAt = 1)
            }
            a.writeCard(CharacterCard("card", "完全写入的合成大资料", customDocuments = docs, createdAt = 1, updatedAt = 1))
            write(a.root, "chat_sessions", "session", ChatSession.serializer(), ChatSession("session", "card", "大资料验证", createdAt = 1, updatedAt = 1))
            val (slot, packageHash) = archive(a.root)
            expected[packageHash] = slot.packageRef!!.byteLength
            write(a.root, "save_slots", slot.id, SaveSlot.serializer(), slot)
            val total = expected.values.fold(0L, Math::addExact)
            assertTrue(total > 1024L * 1024 * 1024)
            host.startHost(); until { host.state.value.addresses.isNotEmpty() }
            host.pairOffer("192.168.1.2"); phone.pair(requireNotNull(host.state.value.offer).code())
            until { a.store.peers().size == 1 && b.store.peers().size == 1 }
            phone.startPhone(); phone.requestSync(); completed(a, b)
            assertEquals(total, sent.get()); assertEquals(total, received.get())
            val firstBytes = sent.get()
            var previous = a.store.baseline(b.id, a.id).revision
            sent.set(0); received.set(0); phone.requestSync(); completed(a, b, previous)
            assertEquals(0L, sent.get()); assertEquals(0L, received.get())
            val changed = Path.of(docs[0].filePath)
            FileChannel.open(changed, StandardOpenOption.WRITE).use { channel -> channel.write(ByteBuffer.wrap(byteArrayOf(99))); channel.force(true) }
            val oldHash = expected.keys.first(); expected.remove(oldHash)
            expected[LanSyncResourceStore.hashFile(changed, length)] = length
            previous = a.store.baseline(b.id, a.id).revision
            sent.set(0); received.set(0); host.requestSync(); completed(a, b, previous)
            assertEquals(length, sent.get()); assertEquals(length, received.get())
            val thirdBytes = sent.get()
            val queued = requireNotNull(a.store.reviewQueue(b.id, a.id)).held.single { it.dependencyOf.isEmpty() }
            assertEquals("character_cards/card", queued.unit)
            previous = a.store.baseline(b.id, a.id).revision
            sent.set(0); received.set(0); host.confirmReview(queued.unit, queued.binding); completed(a, b, previous)
            assertEquals(0L, sent.get()); assertEquals(0L, received.get())
            host.closeAndDrain(); phone.closeAndDrain()
            for (end in listOf(a, b)) {
                val reopened = LanSyncWorkspace(end.root, end.stateRoot.resolve("workspace"), { Path.of(it) })
                reopened.recover()
                reopened.capture(end.id).use { capture ->
                    assertEquals(expected, capture.snapshot.resources)
                    capture.blobs.forEach { (hash, file) -> assertEquals(hash, LanSyncResourceStore.hashFile(file, expected.getValue(hash))) }
                }
                val reopenedStorage = com.example.chatbar.data.local.JsonFileStorage(end.root)
                val loaded = reopenedStorage.loadAll("save_slots", SaveSlot.serializer()).single()
                val packageFile = end.root.resolve("save_slot_packages").resolve(loaded.packageRef!!.fileName)
                LanSyncSlotPackages.validate(packageFile, lanSyncJson.encodeToJsonElement(SaveSlot.serializer(), loaded).jsonObject)
                assertEquals(packageHash, LanSyncResourceStore.hashFile(packageFile, Files.size(packageFile)))
            }
            sample.cancelAndJoin()
            println("CCB_LARGE_PROOF " + buildJsonObject {
                put("protocol", LAN_SYNC_PROTOCOL); put("fullyWrittenResourceBytes", total); put("v8Bytes", slot.packageRef!!.byteLength)
                put("largestOrdinaryResourceBytes", length); put("firstWireBytes", firstBytes); put("secondWireBytes", 0L); put("thirdWireBytes", thirdBytes)
                put("explicitConfirmationWireBytes", 0L)
                put("reopenedEndpoints", 2); put("wholeResourceHashesVerified", true); put("elapsedMillis", (System.nanoTime() - began) / 1_000_000)
                put("sampledPeakHeapBytes", largestHeap); put("minimumSampledFreeDiskBytes", minimumFree)
            })
        } finally {
            sample.cancelAndJoin(); host.closeAndDrain(); phone.closeAndDrain()
            for (root in listOf(a.root, a.stateRoot, b.root, b.stateRoot)) check(root.toFile().deleteRecursively())
        }
    }
}
