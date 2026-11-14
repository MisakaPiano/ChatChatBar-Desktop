package com.example.chatbar.domain.syncStore.webdav

import com.example.chatbar.domain.lanSync.LanSyncGroupPublicationQueueTest
import com.example.chatbar.domain.lanSync.LanSyncGroupRootPublication
import com.example.chatbar.domain.syncStore.*
import java.nio.file.Files
import java.nio.file.Path
import okhttp3.mockwebserver.MockResponse
import kotlin.test.*

/** Real existing publication/AEAD owners, synthetic native records, loopback WebDAV only. */
class WebDavPublicationContractTest {
    @Test fun resourcesPrecedeEncryptedMetadataAndLostNetworkReplyResumesRetainedOutbox() = davFixture { dav ->
        val fixture = LanSyncGroupPublicationQueueTest.Fixture(Files.createDirectory(dav.root.resolve("native")))
        val prepared = fixture.prepare(); val publisher = fixture.publisher()
        val plan = publisher.prepare(prepared.metadata, listOf(prepared.unit), prepared.operations, emptyList())
        val queuedBefore = queuedHashes(fixture.queue)
        val target = SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, dav.route)
        dav.dav.overrideRequest = { request ->
            if (request.method == "PUT" && request.requestUrl!!.encodedPath == davPath(plan.metadata.key)) {
                for (blob in plan.blobs.values) {
                    val bytes = assertNotNull(dav.dav.files[davPath(blob.key)])
                    val input = bytes.inputStream()
                    assertEquals(blob.descriptor, WebDavStoreRecord.readHeader(input, blob.key).descriptor)
                    WebDavStoreRecord.transfer(input, WebDavStoreRecord.discard, blob.descriptor, 8192)
                }
            }
            null
        }
        dav.dav.loseObjectReplyOnce = true
        davFailure(CiphertextStoreFailure.TRANSIENT) { publisher.upload(plan.metadata.operation, dav.store(), target) }
        assertNull(publisher.receipt(plan.metadata.operation, target))
        assertFalse(dav.dav.files.containsKey(davPath(plan.metadata.key)))
        assertEquals(queuedBefore, queuedHashes(fixture.queue))
        check(fixture.data.toFile().deleteRecursively())
        val reopened = fixture.publisher()
        val receipt = reopened.upload(plan.metadata.operation, dav.store(), target)
        assertEquals(3L, receipt.resourceCount)
        assertEquals(target, receipt.target)
        assertEquals(davPath(plan.metadata.key), dav.dav.putPaths.last())
        assertEquals(queuedBefore, queuedHashes(fixture.queue))
        val read = fixture.codec.read(dav.store(), fixture.cipher, prepared.metadata.context,
            prepared.metadata.request.descriptor, plan.reference)
        assertEquals(prepared.metadata.envelope, read.envelope)
        val requests = dav.server.requestCount
        assertEquals(receipt, reopened.upload(plan.metadata.operation, dav.store(), target))
        assertEquals(requests, dav.server.requestCount, "A recorded historical receipt does not retransmit the operation")
        // Separately inspect remote objects: this invokes real HEADs, not the local receipt shortcut.
        repeat(2) {
            val before = dav.receivedGetBytes.get()
            for (blob in plan.blobs.values) assertEquals(blob.descriptor, dav.store().metadata(blob.key)?.descriptor)
            assertEquals(before, dav.receivedGetBytes.get())
        }
    }

    @Test fun quotaFailureBeforeMetadataKeepsAllQueuedBytesAndNoCompletionReceipt() = davFixture { dav ->
        val fixture = LanSyncGroupPublicationQueueTest.Fixture(Files.createDirectory(dav.root.resolve("native")))
        val prepared = fixture.prepare(); val publisher = fixture.publisher()
        val plan = publisher.prepare(prepared.metadata, listOf(prepared.unit), prepared.operations, emptyList())
        val original = queuedHashes(fixture.queue)
        val target = SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, dav.route)
        dav.dav.overrideRequest = { request ->
            if (request.method == "PUT" && request.requestUrl!!.encodedPath == davPath(plan.metadata.key)) {
                MockResponse().setResponseCode(507).setBody("synthetic quota failure")
            } else null
        }
        davFailure(CiphertextStoreFailure.QUOTA) { publisher.upload(plan.metadata.operation, dav.store(), target) }
        assertNull(publisher.receipt(plan.metadata.operation, target))
        assertFalse(dav.dav.files.containsKey(davPath(plan.metadata.key)))
        assertTrue(plan.blobs.values.all { dav.dav.files.containsKey(davPath(it.key)) })
        assertEquals(original, queuedHashes(fixture.queue))
        dav.dav.overrideRequest = null
        val receipt = fixture.publisher().upload(plan.metadata.operation, dav.store(), target)
        assertEquals(3L, receipt.resourceCount)
        assertEquals(original, queuedHashes(fixture.queue))
    }

    @Test fun freshRecipientBootstrapsItsMissingGroupKeyFromHttpHpkeCapsulesBeforeAuthenticatingAVersion() = davFixture { dav ->
        val fixture = LanSyncGroupPublicationQueueTest.Fixture(Files.createDirectory(dav.root.resolve("native")))
        val rootOwner = Files.createDirectory(dav.root.resolve("root-delivery"))
        val epochQueues = Files.createDirectory(dav.root.resolve("root-epoch-queues"))
        fun rootOutbox(epoch: SyncStoreId): SyncEncryptedOutbox {
            require(epoch == fixture.cipher.epoch)
            val directory = epochQueues.resolve(epoch.value)
            if (!Files.exists(directory)) Files.createDirectory(directory)
            return SyncEncryptedOutbox(directory, fixture.cipher)
        }
        val rootPublisher = LanSyncGroupRootPublication(rootOwner, fixture.owner, { fixture.ledger },
            { epoch -> require(epoch == fixture.cipher.epoch); fixture.cipher }, ::rootOutbox)
        val rootPlan = rootPublisher.prepare()
        assertEquals(2, rootPlan.keyDeliveries.size)
        dav.dav.overrideRequest = { request ->
            if (request.method == "PUT" && request.requestUrl!!.encodedPath == davPath(rootPlan.metadata.key)) {
                rootPlan.keyDeliveries.values.forEach { capsule ->
                    val input = assertNotNull(dav.dav.files[davPath(capsule.key)]).inputStream()
                    assertEquals(capsule.descriptor, WebDavStoreRecord.readHeader(input, capsule.key).descriptor)
                    WebDavStoreRecord.transfer(input, WebDavStoreRecord.discard, capsule.descriptor, 8192)
                }
            }
            null
        }
        val target = SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, dav.route)
        assertEquals(2L, rootPublisher.upload(dav.store(), target).keyDeliveryCount)
        assertEquals(davPath(rootPlan.metadata.key), dav.dav.putPaths.last())
        val recipient = fixture.recipients.getValue(fixture.sender.fingerprint)
        val custodyRoot = Files.createDirectory(dav.root.resolve("fresh-recipient-custody"))
        // Synthetic OS-protection boundary only; HPKE and streaming AEAD below are real existing owners.
        fun custody() = SyncGroupCustody(custodyRoot, fixture.cipher.group, recipient,
            { it.reversedArray() }, { it.reversedArray() })
        custody().enroll(fixture.ledger.trustAnchor, listOf(fixture.genesis))
        assertEquals("SYNC_GROUP_CUSTODY_KEY_MISSING", assertFailsWith<IllegalArgumentException> {
            custody().key(fixture.cipher.epoch)
        }.message)
        val receiverBackend = dav.store(Files.createDirectory(dav.root.resolve("fresh-recipient-http")),
            WebDavWriteSupport.UNVERIFIED_READ_ONLY)
        val inboxRoot = Files.createDirectory(dav.root.resolve("fresh-recipient-inbox"))
        fun scan(): SyncInboxReconciliation {
            val inbox = SyncGroupInbox(inboxRoot, receiverBackend, custody(), recipient)
            var cursor: CommitCursor? = null
            do {
                val page = inbox.discover(cursor, 1)
                assertTrue(page.observations.all { it.stage == SyncInboxStage.MIRRORED })
                cursor = page.nextCursor
            } while (cursor != null)
            return inbox.reconcile()
        }
        val bootstrapped = scan()
        assertFalse(bootstrapped.trustBlocked)
        assertEquals(fixture.genesis.reference(), bootstrapped.ledgerCheckpoint)
        assertEquals(fixture.cipher.keyCommitment(), custody().key(fixture.cipher.epoch).keyCommitment())
        dav.dav.overrideRequest = null
        val prepared = fixture.prepare()
        val publisher = fixture.publisher()
        val versionPlan = publisher.prepare(prepared.metadata, listOf(prepared.unit), prepared.operations, emptyList())
        publisher.upload(versionPlan.metadata.operation, dav.store(), target)
        val received = scan() // Restart discovery from null, including earlier random ciphertext IDs.
        assertFalse(received.trustBlocked)
        assertEquals(SyncVersionState.ADMITTED, received.history?.state(versionPlan.reference.id))
        assertTrue(received.authenticatedVersions.any { it.envelope.reference() == versionPlan.reference })
        assertEquals(fixture.cipher.keyCommitment(), custody().key(fixture.cipher.epoch).keyCommitment())
    }

    private fun queuedHashes(root: Path): Map<String, String> = Files.walk(root).use { stream ->
        stream.filter { it.fileName.toString() == "ciphertext.bin" }.toList().associate { file ->
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            Files.newInputStream(file).use { input ->
                val buffer = ByteArray(8192)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            root.relativize(file).toString() to digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}
