package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.domain.syncStore.*
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*
import kotlinx.serialization.json.*

/** Real capture, retained AEAD, author journal and File target; no provider account or seed assets. */
class LanSyncGroupSourceBlobReuseTest {
    private class Counts(private val actual: CiphertextStore) : CiphertextStore by actual {
        val puts = mutableListOf<CiphertextObjectKey>()
        val gets = mutableListOf<CiphertextObjectKey>()
        override fun put(request: CiphertextPutRequest, source: InputStream): CiphertextPutResult {
            puts += request.key; return actual.put(request, source)
        }
        override fun readTo(key: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?): CiphertextObjectMetadata? {
            gets += key; return actual.readTo(key, destination, expected)
        }
    }
    private class Scene(val n: LanSyncGroupSourceWriterTest.Node) {
        val unit = "conversation/session"
        val image = LanSyncPreviewSample.image()
        val imageHash = MessageDigest.getInstance("SHA-256").digest(image).joinToString("") { "%02x".format(it) }
        val target = Counts(n.f.source.backend)
        init {
            n.localCard("card", "合成图片复用角色")
            val path = n.f.target.resolve("entities/chat_sessions/session.json"); Files.createDirectories(path.parent)
            LanSyncJsonFiles.write(path, ChatSession.serializer(), ChatSession("session", "card", "合成会话", createdAt = 1, updatedAt = 1))
            val resource = n.f.target.resolve("images/retained.png"); Files.createDirectories(resource.parent); Files.write(resource, image)
            message("first", listOf(resource.toString()))
        }
        fun message(id: String, images: List<String> = emptyList()): Path {
            val path = n.f.target.resolve("entities/chat_messages/session_$id.json"); Files.createDirectories(path.parent)
            LanSyncJsonFiles.write(path, ChatMessage.serializer(), ChatMessage(id, "session", MessageRole.USER, "合成消息 $id",
                images = images, createdAt = id.length.toLong(), updatedAt = id.length.toLong()))
            return path
        }
        fun publish(first: Boolean = false): LanSyncGroupSourceCommit {
            val selected = if (first) setOf("character_cards/card", unit) else setOf(unit)
            val writer = n.writer()
            val result = writer.commit(n.review(selected).authorize(), SyncStoreId.random())
            writer.upload(result.entry.request.operation, target); n.acknowledge(result)
            return result
        }
        fun raw(blob: LanSyncGroupPublishObject, epoch: SyncStoreId = n.f.source.cipher.epoch): Path =
            n.queues.resolve(epoch.value).resolve(blob.operation.value).resolve("ciphertext.bin")
        fun verifyOriginals(commit: LanSyncGroupSourceCommit) {
            val entry = requireNotNull(n.f.workspace.recovery.read(commit.entry.request.sourceCheckpoint))
            n.f.workspace.recovery.verify(entry)
            var found = false
            n.f.workspace.recovery.originals(entry) { original, file ->
                if (original.relative == "entities/chat_messages/session_first.json") {
                    found = true
                    assertEquals("first", LanSyncJsonFiles.read(file, ChatMessage.serializer())?.id)
                }
            }
            assertTrue(found)
        }
    }
    private fun withScene(work: (Scene) -> Unit) {
        val root = Files.createTempDirectory("synthetic-source-blob-reuse-").toRealPath()
        try { work(Scene(LanSyncGroupSourceWriterTest.Node(root))) } finally { check(root.toFile().deleteRecursively()) }
    }

    @Test fun twoNewMessagesAndColdWritersRetainTheOldImageCiphertextWithoutAnotherTargetPutOrGet() = withScene { s ->
        val first = s.publish(first = true)
        val old = first.publication.blobs.getValue(s.imageHash)
        val raw = Files.readAllBytes(s.raw(old))
        assertEquals(1, s.target.puts.count { it == old.key })
        val firstChange = first.entry.version.changes.getValue(s.unit)
        s.target.puts.clear(); s.target.gets.clear()
        s.message("second")
        val second = s.publish()
        assertEquals(2L, second.entry.version.authorSequence)
        assertEquals(first.entry.version.reference(), second.entry.version.previousByAuthor)
        assertEquals(setOf(first.entry.version.reference()), second.entry.version.changes.getValue(s.unit).previous)
        assertNotEquals(firstChange.content, second.entry.version.changes.getValue(s.unit).content)
        assertNotEquals(firstChange.membership, second.entry.version.changes.getValue(s.unit).membership)
        assertEquals(old, second.publication.blobs.getValue(s.imageHash))
        assertEquals(first.metadata.envelope.blobs.getValue(s.imageHash), second.metadata.envelope.blobs.getValue(s.imageHash))
        assertEquals(0, s.target.puts.count { it == old.key }); assertEquals(0, s.target.gets.count { it == old.key })
        assertContentEquals(raw, Files.readAllBytes(s.raw(old)))

        // Each writer/outbox/journal constructor reopens its retained private state.
        s.target.puts.clear(); s.target.gets.clear(); s.message("third")
        val third = s.publish()
        assertEquals(3L, s.n.journal().state().committedSequence)
        assertEquals(second.entry.version.reference(), third.entry.version.previousByAuthor)
        assertEquals(old, third.publication.blobs.getValue(s.imageHash))
        assertEquals(0, s.target.puts.count { it == old.key }); assertEquals(0, s.target.gets.count { it == old.key })
        assertContentEquals(raw, Files.readAllBytes(s.raw(old)))
        assertEquals(listOf(first.entry, second.entry, third.entry), listOf(first, second, third).map {
            s.n.writer().resume(it.entry.request.operation).entry
        })
        listOf(first, second, third).forEach(s::verifyOriginals)
    }

    @Test fun rotatingTwoVersionsSharesOneNewEpochImageSlotAndPreservesTheOldRawAndAuthorCounters() = withScene { s ->
        val first = s.publish(first = true); s.message("second"); val second = s.publish()
        val old = first.publication.blobs.getValue(s.imageHash); val original = Files.readAllBytes(s.raw(old))
        val key = SyncStreamingCipher.create(s.n.f.source.cipher.group); s.n.keys[key.epoch] = key
        val row = s.n.f.source.sign(s.n.f.source.genesis.copy(id = SyncStoreId.random(), parent = s.n.f.source.genesis.reference(),
            sequence = 2, epoch = key.epoch, keyCommitment = key.keyCommitment()))
        s.n.f.custody().update(listOf(row))
        check(s.n.f.target.startsWith(s.n.root)); check(s.n.f.target.toFile().deleteRecursively())
        val rotatedSecond = s.n.writer().resume(second.entry.request.operation)
        val rotatedFirst = s.n.writer().resume(first.entry.request.operation)
        val next = rotatedSecond.publication.blobs.getValue(s.imageHash)
        assertNotEquals(old.operation, next.operation); assertNotEquals(old.key, next.key)
        assertNotEquals(old.descriptor, next.descriptor)
        assertEquals(next, rotatedFirst.publication.blobs.getValue(s.imageHash))
        assertEquals(first.entry, rotatedFirst.entry); assertEquals(second.entry, rotatedSecond.entry)
        assertEquals(key.epoch, rotatedSecond.metadata.context.epoch)
        assertEquals(2L, s.n.journal().state().committedSequence)
        assertContentEquals(original, Files.readAllBytes(s.raw(old)))
        val nextRaw = Files.readAllBytes(s.raw(next, key.epoch))
        assertEquals(next.plaintext, key.verify(nextRaw.inputStream(), SyncEncryptionContext(key.group, key.epoch, next.key)))
        assertEquals(next, s.n.writer().resume(second.entry.request.operation).publication.blobs.getValue(s.imageHash))
        assertContentEquals(nextRaw, Files.readAllBytes(s.raw(next, key.epoch)))
        s.verifyOriginals(first); s.verifyOriginals(second)
    }

    @Test fun corruptRetainedImageRefusesANewSourceEvenWhenTheCompletePlaintextCacheIsWarm() = withScene { s ->
        val first = s.publish(first = true); val old = first.publication.blobs.getValue(s.imageHash)
        assertNotNull(s.n.f.workspace.resourceStore.completed(s.imageHash, s.image.size.toLong()))
        val source = s.n.sourceRoot.resolve("source-${first.entry.request.operation.value}.json")
        val sourceBytes = Files.readAllBytes(source)
        val bad = Files.readAllBytes(s.raw(old)).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        Files.write(s.raw(old), bad)
        val edit = s.message("second"); val edited = Files.readAllBytes(edit)
        val authorization = s.n.review(setOf(s.unit)).authorize()
        assertEquals("SYNC_OUTBOX_CIPHERTEXT_CHANGED", assertFailsWith<IllegalArgumentException> {
            s.n.writer().commit(authorization, SyncStoreId.random())
        }.message)
        assertEquals(1L, s.n.journal().state().committedSequence); assertNull(s.n.journal().state().pendingApproved)
        assertContentEquals(edited, Files.readAllBytes(edit)); assertContentEquals(bad, Files.readAllBytes(s.raw(old)))
        assertContentEquals(sourceBytes, Files.readAllBytes(source)); s.verifyOriginals(first)
        assertEquals(listOf(first.entry.request.operation), s.n.writer().operations())
    }

    @Test fun aMissingPreviouslyUsedRawImageIsNotRecreatedFromWarmPlaintext() = withScene { s ->
        val first = s.publish(first = true); val old = first.publication.blobs.getValue(s.imageHash)
        assertNotNull(s.n.f.workspace.resourceStore.completed(s.imageHash, s.image.size.toLong()))
        Files.delete(s.raw(old)); val edit = s.message("second"); val edited = Files.readAllBytes(edit)
        val authorization = s.n.review(setOf(s.unit)).authorize()
        assertFailsWith<java.io.IOException> { s.n.writer().commit(authorization, SyncStoreId.random()) }
        assertFalse(Files.exists(s.raw(old)))
        assertEquals(1L, s.n.journal().state().committedSequence); assertNull(s.n.journal().state().pendingApproved)
        assertContentEquals(edited, Files.readAllBytes(edit)); s.verifyOriginals(first)
        assertEquals(listOf(first.entry.request.operation), s.n.writer().operations())
    }

    @Test fun deletingTheOwnedReuseIndexCannotAllocateReplacementCiphertextOrAnAuthorRevision() = withScene { s ->
        val first = s.publish(first = true); val old = first.publication.blobs.getValue(s.imageHash)
        val original = Files.readAllBytes(s.raw(old)); val index = s.n.sourceRoot.resolve("blob-reuse")
        check(index.startsWith(s.n.root)); check(index.toFile().deleteRecursively())
        val edit = s.message("second"); val edited = Files.readAllBytes(edit)
        val authorization = s.n.review(setOf(s.unit)).authorize()
        assertEquals("SYNC_GROUP_BLOB_REUSE_CACHE_MISSING", assertFailsWith<IllegalArgumentException> {
            s.n.writer().commit(authorization, SyncStoreId.random())
        }.message)
        assertFalse(Files.exists(index)); assertContentEquals(original, Files.readAllBytes(s.raw(old)))
        assertEquals(1L, s.n.journal().state().committedSequence); assertNull(s.n.journal().state().pendingApproved)
        assertContentEquals(edited, Files.readAllBytes(edit)); s.verifyOriginals(first)
    }

    @Test fun aLegacyExactRandomSlotIsAdoptedWithoutReplacingItsRetainedCiphertext() = withScene { s ->
        val first = s.publish(first = true); val old = first.publication.blobs.getValue(s.imageHash)
        val raw = Files.readAllBytes(s.raw(old)); val root = Files.createDirectory(s.n.root.resolve("legacy-blob-adoption"))
        val key = s.n.f.source.cipher; val queue = s.n.outbox(key.epoch)
        val legacy = LanSyncGroupBlobSeed(null, old.plaintext, old.operation, old.key, true)
        val pool = LanSyncGroupBlobReuse(root, key.group, s.n.f.source.owner.fingerprint, emptySet())
        val adopted = pool.select(key, old.plaintext, queue, listOf(legacy))
        assertEquals(old.operation, adopted.operation); assertEquals(old.key, adopted.key)
        assertEquals(old.descriptor, pool.entry(adopted, queue, true)?.request?.descriptor)
        val cold = LanSyncGroupBlobReuse(root, key.group, s.n.f.source.owner.fingerprint, setOf(pool.owner))
        assertEquals(adopted, cold.select(key, old.plaintext, s.n.outbox(key.epoch), listOf(legacy.copy(owner = pool.owner))))
        assertContentEquals(raw, Files.readAllBytes(s.raw(old)))
        assertEquals(1L, s.n.journal().state().committedSequence)
    }

    @Test fun aColdUnstartedReservationReusesItsRandomIdsBeforeAnyCiphertextOrAuthorApprovalExists() = withScene { s ->
        val root = Files.createDirectory(s.n.root.resolve("unstarted-blob-reservation")); val key = s.n.f.source.cipher
        val plaintext = SyncPlaintextIdentity(s.image.size.toLong(), s.imageHash)
        val first = LanSyncGroupBlobReuse(root, key.group, s.n.f.source.owner.fingerprint, emptySet())
        val slot = first.select(key, plaintext, s.n.outbox(key.epoch), emptyList())
        assertNull(first.entry(slot, s.n.outbox(key.epoch), false))
        val cold = LanSyncGroupBlobReuse(root, key.group, s.n.f.source.owner.fingerprint, setOf(first.owner))
        assertEquals(slot, cold.select(key, plaintext, s.n.outbox(key.epoch), emptyList()))
        val source = s.n.f.target.resolve("images/retained.png")
        val completed = cold.complete(slot, key, s.n.outbox(key.epoch), source)
        assertEquals(slot.operation, completed.request.operationId); assertEquals(slot.key, completed.request.key)
        assertEquals(plaintext, completed.plaintext)
        assertEquals(completed, cold.entry(slot, s.n.outbox(key.epoch), true))
        assertEquals(0L, s.n.journal().state().committedSequence); assertNull(s.n.journal().state().pendingApproved)
    }

    @Test fun anAbsentLegacyReuseExtensionKeepsItsApprovedSourceHashAndColdDelivery() = withScene { s ->
        val n = s.n; val operation = SyncStoreId.random()
        val authorization = n.review(setOf("character_cards/card", s.unit)).authorize()
        val interrupted = LanSyncGroupSourceWriter(n.sourceRoot, n.f.workspace, n.f.source.owner, n.journal(),
            { n.f.custody().ledger() }, { n.keys.getValue(it) }, n::outbox, n::view,
            { LanSyncGroupNativeState(n.applied, 0, null) }, observe = {
                if (it == "SOURCE_PREPARATION_RETAINED") throw java.io.IOException("SYNTHETIC_BEFORE_SOURCE_ENCRYPTION")
            })
        assertFailsWith<java.io.IOException> { interrupted.commit(authorization, operation) }
        assertEquals(0L, n.journal().state().committedSequence)
        val path = n.sourceRoot.resolve("source-${operation.value}.json")
        val old = JsonObject(lanSyncJson.parseToJsonElement(Files.readString(path)).jsonObject - "blobReuseOwner")
        Files.writeString(path, old.toString())
        val committed = n.writer().commit(authorization, operation)
        val expectedHash = lanSyncHash(JsonObject(old - "restorations" - "resolution"))
        assertEquals(lanSyncHash(buildJsonObject {
            put("review", authorization.binding); put("preparation", expectedHash)
        }), committed.entry.request.approvalBinding)
        Files.writeString(path, JsonObject(old + ("blobReuseOwner" to JsonNull)).toString())
        val cold = n.writer().resume(operation)
        assertEquals(committed.entry, cold.entry); assertEquals(committed.publication, cold.publication)
        assertEquals(1L, n.journal().state().committedSequence); s.verifyOriginals(committed)
    }
}
