package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

/** Separate source/route ownership over actual journals, immutable files and AEAD; protection is synthetic. */
class LanSyncGroupLocalSourceTest {
    private fun fixture(work: suspend (LanSyncGroupNodeTest.Group) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("synthetic-local-group-source-").toRealPath()
        try { work(LanSyncGroupNodeTest.Group(root)) } finally { check(root.toFile().deleteRecursively()) }
    }
    private fun node(g: LanSyncGroupNodeTest.Group, end: LanSyncGroupNodeTest.Group.End, store: CiphertextStore) =
        LanSyncGroupNode(end.runtime, end.workspace, end.platform, g.identities[end.index], g.trust, end.custody(), g.recipients[end.index],
            store, { it.reversedArray() }, { it.reversedArray() }, { emptySet() }, { _, _, _ -> })

    @Test fun blockedRemoteDiscoveryDoesNotHoldLocalInspectionCommitOrLocalReplicaPublication() = fixture { g ->
        val sender = g.End(0); val sending = sender.enroll()
        val daily = g.End(1); daily.enroll()
        sender.card("incoming", "remote incoming original")
        sender.send(sending, sending.commit(sending.inspect().review(setOf("character_cards/incoming"))))
        val local = FileCiphertextStore(g.root.resolve("daily-replica"))
        val reader = LanSyncGroupReplicaReader(local, g.root.resolve("daily-reader")) { _, failure -> throw failure }
        val node = node(g, daily, reader)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val listings = AtomicInteger()
        val blocked = object : CiphertextStore by g.backend {
            override fun listCommits(cursor: CommitCursor?, pageSize: Int): CommitPage {
                listings.incrementAndGet(); entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "SYNTHETIC_DISCOVERY_NOT_RELEASED" }
                return g.backend.listCommits(cursor, pageSize)
            }
        }
        reader.routes(listOf(LanSyncGroupReplicaReader.Route("blocked", blocked)))
        val pool = Executors.newFixedThreadPool(2)
        try {
            val discovering = pool.submit<SyncInboxDiscovery> { runBlocking { node.discover(blocked) } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            daily.card("local", "local original while discovery waits")
            val source = pool.submit<LanSyncGroupSourceCommit> { runBlocking {
                var localCursor: CommitCursor? = null
                do { localCursor = node.discoverLocal(localCursor).nextCursor } while (localCursor != null)
                val inspection = node.inspectLocal(recoverNative = false)
                val commit = node.commit(inspection.review(setOf("character_cards/local")), recoverNative = false)
                node.upload(commit.entry.request.operation, local, SyncDeliveryTarget(SyncDeliveryKind.LOCAL_REPLICA, SyncStoreId.random()), recoverNative = false)
                commit
            } }.get(5, TimeUnit.SECONDS)
            assertEquals(1L, source.entry.version.authorSequence)
            assertEquals(1, listings.get())
            assertEquals(1L, release.count)
            assertFalse(discovering.isDone)
            assertEquals("local original while discovery waits", daily.name("local"))
            assertNotNull(local.metadata(source.publication.metadata.key))
            assertNull(daily.name("incoming"))
            release.countDown(); discovering.get(10, TimeUnit.SECONDS)
        } finally { release.countDown(); pool.shutdownNow(); check(pool.awaitTermination(15, TimeUnit.SECONDS)) }
    }

    @Test fun blockedBlobPrefetchDoesNotHoldLocalCommitAndLaterWarmsOnlyImmutableDependencyCaches() = fixture { g ->
        val sender = g.End(0); val sending = sender.enroll()
        val daily = g.End(1); daily.enroll()
        sender.card("incoming", "verified incoming body")
        val incoming = sending.commit(sending.inspect().review(setOf("character_cards/incoming")))
        sender.send(sending, incoming)
        val local = FileCiphertextStore(g.root.resolve("daily-replica"))
        val reader = LanSyncGroupReplicaReader(local, g.root.resolve("daily-reader")) { _, failure -> throw failure }
        val node = node(g, daily, reader)
        var cursor: CommitCursor? = null
        do { cursor = node.discover(reader.discoveryFrom(g.backend), cursor).nextCursor } while (cursor != null)
        // Membership already arrived; the larger unit body remains an independent missing BLOB.
        val membership = requireNotNull(incoming.entry.version.changes.getValue("character_cards/incoming").membership)
        val membershipBlob = incoming.metadata.envelope.blobs.getValue(membership.sha256)
        val membershipBytes = ByteArrayOutputStream()
        assertNotNull(g.backend.readTo(membershipBlob.key, membershipBytes, membershipBlob.descriptor))
        local.put(CiphertextPutRequest(membershipBlob.key, SyncStoreId.random(), membershipBlob.descriptor), membershipBytes.toByteArray().inputStream())
        val view = node.reconcileForPrefetch()
        assertTrue(requireNotNull(view.history).contains(incoming.entry.version.reference()))
        assertEquals(0, daily.reloads)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val reads = AtomicInteger()
        val blocked = object : CiphertextStore by g.backend {
            override fun readTo(key: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?): CiphertextObjectMetadata? {
                if (key.kind == CiphertextObjectKind.BLOB) {
                    reads.incrementAndGet(); entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "SYNTHETIC_PREFETCH_NOT_RELEASED" }
                }
                return g.backend.readTo(key, destination, expected)
            }
        }
        reader.routes(listOf(LanSyncGroupReplicaReader.Route("blocked", blocked)))
        val pool = Executors.newFixedThreadPool(2)
        try {
            val prefetching = pool.submit { runBlocking { node.prefetch(view) } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            daily.card("local", "committed while body transfer waits")
            val source = pool.submit<LanSyncGroupSourceCommit> { runBlocking {
                val inspection = node.inspectLocal(recoverNative = false)
                assertEquals(LanSyncGroupNativeStage.WAITING_DEPENDENCY, inspection.plan?.units?.get("character_cards/incoming")?.stage)
                node.commit(inspection.review(setOf("character_cards/local")), recoverNative = false)
            } }.get(5, TimeUnit.SECONDS)
            assertEquals(1L, source.entry.version.authorSequence)
            assertEquals(1L, release.count)
            assertFalse(prefetching.isDone)
            assertEquals(0, daily.reloads)
            assertNull(daily.name("incoming"))
            release.countDown(); prefetching.get(15, TimeUnit.SECONDS)
            val warmed = node.inspectLocal(recoverNative = false)
            assertEquals(LanSyncGroupNativeStage.READY_CONTENT, warmed.plan?.units?.get("character_cards/incoming")?.stage)
            assertTrue(reads.get() >= 1)
            assertEquals("committed while body transfer waits", daily.name("local"))
            assertNull(daily.name("incoming"))
            assertEquals(0, daily.reloads)
            assertEquals(1L, daily.author().state().committedSequence)
        } finally { release.countDown(); pool.shutdownNow(); check(pool.awaitTermination(15, TimeUnit.SECONDS)) }
    }

    @Test fun sourceOnlyShutdownRefusesAuthorizedWorkspaceWalWithoutApplyingOrDiscardingIt() = fixture { g ->
        val daily = g.End(0); val node = daily.enroll()
        daily.card("a", "before authorized WAL")
        val original = Files.readAllBytes(daily.data.resolve("entities/character_cards/a.json"))
        val review = node.inspectLocal(recoverNative = false).review(setOf("character_cards/a"))
        val transaction = UUID.randomUUID().toString()
        daily.journalHook = { stage, _ -> if (stage == "AUTHORIZED") throw IOException("SYNTHETIC_AUTHORIZED_WAL_INTERRUPTION") }
        daily.workspace.capture(g.identities[0].fingerprint).use { capture ->
            val target = capture.snapshot.copy(records = capture.snapshot.records.map { record ->
                record.copy(body = JsonObject(record.body + ("name" to JsonPrimitive("after ordinary WAL recovery"))))
            })
            assertFailsWith<IOException> { daily.workspace.apply(LanSyncPlan(transaction, target, emptyMap()), capture.snapshot.hashes(),
                g.identities[0].fingerprint, capture.blobs) }
        }
        daily.journalHook = { _, _ -> }
        val wal = daily.base.resolve("workspace/transaction-$transaction")
        val retained = Files.list(wal).use { paths -> paths.filter { Files.isRegularFile(it) }.toList().associate { it.fileName.toString() to Files.readAllBytes(it) } }
        assertTrue(Files.isRegularFile(wal.resolve("authorized")))
        assertFalse(Files.exists(wal.resolve("applied")))
        assertEquals("SYNC_GROUP_SOURCE_RECOVERY_REQUIRED", assertFailsWith<IllegalArgumentException> { node.inspectLocal(recoverNative = false) }.message)
        assertEquals("SYNC_GROUP_SOURCE_RECOVERY_REQUIRED", assertFailsWith<IllegalArgumentException> { node.commit(review, recoverNative = false) }.message)
        assertContentEquals(original, Files.readAllBytes(daily.data.resolve("entities/character_cards/a.json")))
        assertFalse(Files.exists(wal.resolve("applied")))
        retained.forEach { (name, bytes) -> assertContentEquals(bytes, Files.readAllBytes(wal.resolve(name))) }
        assertTrue(node.sourceOperations().isEmpty())
        assertEquals(0L, daily.author().state().committedSequence)
        // Ordinary inspection preserves its existing recovery behavior.
        node.inspect()
        assertEquals("after ordinary WAL recovery", daily.name("a"))
        assertTrue(Files.isRegularFile(wal.resolve("applied")))
        daily.workspace.requireSettledForSourceOnly()
    }
}
