package com.example.chatbar.domain.syncStore

import com.example.chatbar.domain.lanSync.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.*

/** Actual encrypted metadata/keys; content identities are placeholders, never native readiness. */
class SyncGroupInboxTest {
    internal class Group(val root: Path) {
        fun dir(name: String) = Files.createDirectory(root.resolve(name))
        val owner = LanSyncTlsIdentity.create(); val peer = LanSyncTlsIdentity.create()
        val recipient = SyncDeviceEncryptionKey.create(peer.fingerprint)
        val ownerRecipient = SyncDeviceEncryptionKey.create(owner.fingerprint)
        val initialKey = SyncStreamingCipher.create()
        val keys = linkedMapOf(initialKey.epoch to initialKey)
        val members = listOf(owner to ownerRecipient, peer to recipient).associate { (id, key) -> id.fingerprint to
            SyncGroupMember(Base64.getEncoder().encodeToString(id.certificate.encoded), key.publicKey.encoded) }
        fun sign(row: SyncDeviceLedgerRecord) = row.copy(signature = Base64.getEncoder().encodeToString(owner.signSyncGroupPayload(row.signingBytes())))
        val genesis = sign(SyncDeviceLedgerRecord(initialKey.group, SyncStoreId.random(), null, 1, initialKey.epoch, initialKey.keyCommitment(),
            setOf("synthetic_records"), members, emptySet(), "unsigned"))
        val trust = SyncGroupTrust(initialKey.group, genesis.reference(), members.getValue(owner.fingerprint).certificate)
        val ledger = SyncDeviceLedger(trust).also { it.offer(genesis) }
        val work = dir("work"); val publications = dir("publications"); val keyRoot = dir("keys"); val noticeRoot = dir("notices")
        val queueRoots = linkedMapOf<SyncStoreId, Path>()
        fun outbox(key: SyncStreamingCipher) = SyncEncryptedOutbox(queueRoots.getOrPut(key.epoch) { dir("queue-${key.epoch.value}") }, key)
        val codec = SyncGroupObjectCodec(work); val backend = FileCiphertextStore(root.resolve("backend"))
        val receiverRoot = dir("receiver"); val custodyRoot = dir("custody")
        fun custody() = SyncGroupCustody(custodyRoot, initialKey.group, recipient, { it.reversedArray() }, { it.reversedArray() })
        init { custody().enroll(trust, listOf(genesis)) }
        fun inbox(store: CiphertextStore = backend) = SyncGroupInbox(receiverRoot, store, custody(), recipient)
        fun publishLedger(): LanSyncGroupPublishPlan {
            val current = ledger.current(); val currentKey = keys.getValue(current.epoch)
            val queue = SyncKeyDeliveryQueue(keyRoot, trust, owner.fingerprint, { ledger })
            val operations = keys.values.flatMap { key -> current.members.filterValues { it.active }.keys.map { recipient ->
                "${key.epoch.value}:$recipient" to SyncStoreId.random().also { queue.prepare(it, key, recipient, owner::signSyncGroupPayload) }
            } }.toMap()
            val outbox = outbox(currentKey)
            val metadata = codec.enqueue(outbox, SyncStoreId.random(), CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random()), currentKey,
                SyncGroupEnvelope(2, SyncGroupObjectKind.DEVICE_LEDGER, deviceLedger = current, ledgerHistory = ledger.history().dropLast(1)))
            val notices = SyncLedgerNoticeQueue(noticeRoot, trust, owner.fingerprint, { ledger })
            val noticeOperations = SyncLedgerNotice.revocations(ledger).keys.associateWith { recipient ->
                SyncStoreId.random().also { notices.prepare(it, recipient, owner::signSyncGroupPayload) }
            }
            val publisher = LanSyncGroupPublicationQueue(publications, ledger, outbox, owner.fingerprint, keyQueue = queue, noticeQueue = notices)
            val plan = publisher.prepare(metadata, emptyList(), emptyMap(), emptyList(), operations, noticeOperations)
            publisher.upload(plan.metadata.operation, backend)
            return plan
        }
        fun version(unit: String, sequence: Long = 1, parent: SyncCausalVersion? = null, peerAuthor: Boolean = false): SyncCausalVersion {
            val author = if (peerAuthor) peer else owner
            val hash = MessageDigest.getInstance("SHA-256").digest("$unit:$sequence".toByteArray()).joinToString("") { "%02x".format(it) }
            val prior = parent?.let { setOf(it.reference()) }.orEmpty()
            return SyncCausalVersion(initialKey.group, SyncStoreId.random(), ledger.current().reference(), author.fingerprint, sequence, parent?.reference(), prior,
                mapOf(unit to SyncUnitChange(hash, SyncPlaintextIdentity(1, hash), prior)), "unsigned").let { row ->
                row.copy(signature = Base64.getEncoder().encodeToString(author.signSyncGroupPayload(row.signingBytes())))
            }
        }
        fun publish(version: SyncCausalVersion, key: SyncStreamingCipher = keys.getValue(ledger.current().epoch),
            id: SyncStoreId = SyncStoreId.random()): SyncPreparedGroupObject {
            val outbox = outbox(key)
            val prepared = codec.enqueue(outbox, SyncStoreId.random(), CiphertextObjectKey(CiphertextObjectKind.COMMIT, id), key,
                SyncGroupEnvelope(2, SyncGroupObjectKind.VERSION, version = version))
            outbox.upload(prepared.request.operationId, backend); return prepared
        }
        fun scan(inbox: SyncGroupInbox = inbox()): SyncInboxReconciliation {
            var cursor: CommitCursor? = null
            do { cursor = inbox.discover(cursor, 1).nextCursor } while (cursor != null)
            return inbox.reconcile()
        }
    }
    private fun withGroup(work: (Group) -> Unit) {
        val root = Files.createTempDirectory("synthetic-group-inbox-").toRealPath()
        try { work(Group(root)) } finally { check(root.toFile().deleteRecursively()) }
    }

    @Test fun missingParentsWaitWhileIndependentVersionsAdvanceAndAFreshRescanRecoversEarlierIds() = withGroup { g ->
        g.publishLedger(); val parent = g.version("synthetic/one"); val child = g.version("synthetic/one", 2, parent)
        val independent = g.version("synthetic/two", peerAuthor = true)
        val childObject = g.publish(child); g.publish(independent)
        val first = g.scan(); assertFalse(first.trustBlocked)
        assertEquals(SyncVersionState.WAITING_FOR_PARENTS, first.history?.state(child.id))
        assertEquals(SyncVersionState.ADMITTED, first.history?.state(independent.id))
        assertEquals(SyncInboxStage.WAITING_FOR_PARENTS, first.observations.single { it.key == childObject.request.key }.stage)
        g.publish(parent, id = SyncStoreId.parse("0".repeat(31) + "1"))
        val reopened = g.scan(g.inbox())
        assertEquals(SyncVersionState.ADMITTED, reopened.history?.state(child.id))
        assertEquals(setOf(child.reference(), independent.reference()), reopened.history?.heads())
        assertEquals(g.genesis.reference(), reopened.ledgerCheckpoint)
        val unavailable = object : CiphertextStore by g.backend {
            override fun listCommits(cursor: CommitCursor?, pageSize: Int): CommitPage = throw IOException("SYNTHETIC_BACKEND_UNAVAILABLE")
        }
        assertFailsWith<IOException> { g.inbox(unavailable).discover() }
        assertEquals(reopened.history?.heads(), g.inbox(unavailable).reconcile().history?.heads())
    }

    @Test fun malformedAndPhysicalContextSubstitutionDoNotBlockOtherValidMetadataOrOverwriteMirroredEvidence() = withGroup { g ->
        g.publishLedger(); val version = g.version("synthetic/one"); val valid = g.publish(version)
        val bytes = ByteArrayOutputStream().also { g.backend.readTo(valid.request.key, it) }.toByteArray()
        val fakeKey = CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random())
        g.backend.put(CiphertextPutRequest(fakeKey, SyncStoreId.random(), SyncKeyDeliveryCodec.descriptor(bytes)), bytes.inputStream())
        val malformed = ByteArray(271) { 17 }; val malformedKey = CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random())
        g.backend.put(CiphertextPutRequest(malformedKey, SyncStoreId.random(), SyncKeyDeliveryCodec.descriptor(malformed)), malformed.inputStream())
        val first = g.scan(); assertEquals(SyncVersionState.ADMITTED, first.history?.state(version.id))
        assertEquals("SYNC_INBOX_CONTEXT", first.observations.single { it.key == fakeKey }.code)
        assertEquals(SyncInboxStage.INVALID_OBJECT, first.observations.single { it.key == malformedKey }.stage)
        val changed = object : CiphertextStore by g.backend {
            override fun listCommits(cursor: CommitCursor?, pageSize: Int): CommitPage {
                val page = g.backend.listCommits(cursor, pageSize)
                return page.copy(entries = page.entries.map { if (it.key == valid.request.key) it.copy(descriptor = SyncKeyDeliveryCodec.descriptor(byteArrayOf(9))) else it })
            }
        }
        val discovery = g.inbox(changed).discover(pageSize = 100)
        assertEquals(SyncInboxStage.INVALID_OBJECT, discovery.observations.single { it.key == valid.request.key }.stage)
        assertEquals(SyncKeyDeliveryCodec.descriptor(byteArrayOf(9)), discovery.observations.single { it.key == valid.request.key }.descriptor)
        assertEquals(SyncVersionState.ADMITTED, g.inbox().reconcile().history?.state(version.id))
    }

    @Test fun badFirstSameIdCiphertextCannotPoisonAReopenedReaderOrInboxAgainstAGoodPeer() = withGroup { g ->
        g.publishLedger(); val version = g.version("synthetic/candidate-version"); val published = g.publish(version)
        val metadata = CiphertextObjectMetadata(published.request.key, published.request.descriptor)
        val badBytes = corruptTail(raw(g.backend, metadata))
        val bad = copiedPeer(g, "bad-version-peer", mapOf(metadata.key to badBytes))
        val badMetadata = requireNotNull(bad.metadata(metadata.key))
        val firstReader = candidateReader(g, "version-reader", bad)
        val first = g.scan(g.inbox(firstReader.discoveryFrom(bad)))
        assertNull(first.history?.state(version.id))
        assertObservation(first, badMetadata, SyncInboxStage.INVALID_OBJECT)
        assertEquals(SyncCiphertextHeader.peek(badBytes.inputStream()), SyncCiphertextHeader.peek(raw(g.backend, metadata).inputStream()))

        val reopened = candidateReader(g, "version-reader", g.backend)
        val recovered = g.scan(g.inbox(reopened.discoveryFrom(g.backend)))
        assertEquals(SyncVersionState.ADMITTED, recovered.history?.state(version.id))
        assertObservation(recovered, metadata, SyncInboxStage.CAUSAL_ADMITTED)
        assertObservation(recovered, badMetadata, SyncInboxStage.INVALID_OBJECT)
        assertEquals(metadata, recovered.authenticatedVersions.single { it.envelope.version?.id == version.id }.metadata)
        assertContentEquals(badBytes, raw(reopened, badMetadata))
        assertContentEquals(raw(g.backend, metadata), raw(reopened, metadata))
        assertContentEquals(badBytes, raw(bad, badMetadata))
        assertEquals(setOf(badMetadata, metadata), all(reopened).filter { it.key == metadata.key }.toSet())
        assertNull(FileCiphertextStore(g.root.resolve("version-reader-local")).metadata(metadata.key))
    }

    @Test fun aLaterBadSameIdCandidateNeverDisplacesAnAlreadyAuthenticatedVersion() = withGroup { g ->
        g.publishLedger(); val version = g.version("synthetic/already-admitted"); val published = g.publish(version)
        val metadata = CiphertextObjectMetadata(published.request.key, published.request.descriptor)
        val original = raw(g.backend, metadata)
        val firstReader = candidateReader(g, "good-first-reader", g.backend)
        assertEquals(SyncVersionState.ADMITTED, g.scan(g.inbox(firstReader.discoveryFrom(g.backend))).history?.state(version.id))
        val badBytes = corruptTail(original)
        val bad = copiedPeer(g, "later-bad-peer", mapOf(metadata.key to badBytes))
        val reopened = candidateReader(g, "good-first-reader", bad)
        val result = g.scan(g.inbox(reopened.discoveryFrom(bad)))
        assertFalse(result.trustBlocked)
        assertEquals(SyncVersionState.ADMITTED, result.history?.state(version.id))
        assertEquals(setOf(version.reference()), result.history?.heads())
        assertObservation(result, metadata, SyncInboxStage.CAUSAL_ADMITTED)
        assertObservation(result, requireNotNull(bad.metadata(metadata.key)), SyncInboxStage.INVALID_OBJECT)
        assertEquals(metadata, result.authenticatedVersions.single { it.envelope.version?.id == version.id }.metadata)
        assertContentEquals(original, raw(reopened, metadata))
        assertContentEquals(badBytes, raw(reopened, requireNotNull(bad.metadata(metadata.key))))
    }

    @Test fun sameIdCandidatesWaitForActualRootKeysThenOnlyTheAuthenticCiphertextIsAdmitted() = withGroup { g ->
        val version = g.version("synthetic/key-delayed"); val published = g.publish(version)
        val metadata = CiphertextObjectMetadata(published.request.key, published.request.descriptor)
        val badBytes = corruptTail(raw(g.backend, metadata))
        val bad = copiedPeer(g, "no-key-bad-peer", mapOf(metadata.key to badBytes))
        var reader = candidateReader(g, "waiting-reader", bad)
        val badWaiting = g.scan(g.inbox(reader.discoveryFrom(bad)))
        assertObservation(badWaiting, requireNotNull(bad.metadata(metadata.key)), SyncInboxStage.WAITING_FOR_KEY)
        reader = candidateReader(g, "waiting-reader", g.backend)
        val bothWaiting = g.scan(g.inbox(reader.discoveryFrom(g.backend)))
        assertObservation(bothWaiting, metadata, SyncInboxStage.WAITING_FOR_KEY)
        assertObservation(bothWaiting, requireNotNull(bad.metadata(metadata.key)), SyncInboxStage.WAITING_FOR_KEY)
        assertNull(bothWaiting.history?.state(version.id))

        g.publishLedger()
        reader = candidateReader(g, "waiting-reader", g.backend)
        val admitted = g.scan(g.inbox(reader.discoveryFrom(g.backend)))
        assertEquals(SyncVersionState.ADMITTED, admitted.history?.state(version.id))
        assertObservation(admitted, metadata, SyncInboxStage.CAUSAL_ADMITTED)
        assertObservation(admitted, requireNotNull(bad.metadata(metadata.key)), SyncInboxStage.INVALID_OBJECT)
        assertEquals(g.initialKey.keyCommitment(), g.custody().key(g.initialKey.epoch).keyCommitment())
        assertContentEquals(badBytes, raw(reader, requireNotNull(bad.metadata(metadata.key))))
    }

    @Test fun sameIdLedgerAndRecipientCapsuleCandidatesRecoverFromBadFirstControlBytes() {
        for (kind in listOf("ledger", "capsule")) withGroup { g ->
            val publication = g.publishLedger(); val version = g.version("synthetic/control-candidate"); g.publish(version)
            val control = if (kind == "ledger") CiphertextObjectMetadata(publication.metadata.key, publication.metadata.descriptor)
                else publication.keyDeliveries.getValue("${g.initialKey.epoch.value}:${g.peer.fingerprint}").let {
                    CiphertextObjectMetadata(it.key, it.descriptor)
                }
            val badBytes = corruptTail(raw(g.backend, control))
            val bad = copiedPeer(g, "bad-$kind-peer", mapOf(control.key to badBytes))
            var reader = candidateReader(g, "control-reader", bad)
            val invalid = g.scan(g.inbox(reader.discoveryFrom(bad)))
            assertObservation(invalid, requireNotNull(bad.metadata(control.key)), SyncInboxStage.INVALID_OBJECT)
            if (kind == "capsule") assertNull(invalid.history?.state(version.id))

            reader = candidateReader(g, "control-reader", g.backend)
            val recovered = g.scan(g.inbox(reader.discoveryFrom(g.backend)))
            assertFalse(recovered.trustBlocked)
            assertEquals(SyncVersionState.ADMITTED, recovered.history?.state(version.id))
            assertObservation(recovered, control, if (kind == "ledger") SyncInboxStage.LEDGER_ADMITTED else SyncInboxStage.KEY_ADMITTED)
            assertObservation(recovered, requireNotNull(bad.metadata(control.key)), SyncInboxStage.INVALID_OBJECT)
            assertContentEquals(badBytes, raw(reader, requireNotNull(bad.metadata(control.key))))
        }
    }

    @Test fun badFirstSameIdRevocationNoticeDoesNotSuppressTheAuthenticNoticeOnReopen() = withGroup { g ->
        g.publishLedger(); g.scan()
        val nextKey = SyncStreamingCipher.create(g.initialKey.group); g.keys[nextKey.epoch] = nextKey
        val revoked = g.sign(g.genesis.copy(id = SyncStoreId.random(), parent = g.genesis.reference(), sequence = 2,
            epoch = nextKey.epoch, keyCommitment = nextKey.keyCommitment(), members = g.members +
                (g.peer.fingerprint to g.members.getValue(g.peer.fingerprint).copy(active = false))))
        g.ledger.offer(revoked)
        val publication = g.publishLedger()
        val control = publication.revocationNotices.getValue(g.peer.fingerprint).let { CiphertextObjectMetadata(it.key, it.descriptor) }
        val badBytes = corruptTail(raw(g.backend, control))
        val bad = copiedPeer(g, "bad-notice-peer", mapOf(control.key to badBytes))
        var reader = candidateReader(g, "notice-reader", bad)
        val invalid = g.scan(g.inbox(reader.discoveryFrom(bad)))
        assertObservation(invalid, requireNotNull(bad.metadata(control.key)), SyncInboxStage.INVALID_OBJECT)
        assertTrue(g.custody().ledger().current().members.getValue(g.peer.fingerprint).active)

        reader = candidateReader(g, "notice-reader", g.backend)
        val recovered = g.scan(g.inbox(reader.discoveryFrom(g.backend)))
        assertObservation(recovered, control, SyncInboxStage.DEVICE_REVOKED)
        assertObservation(recovered, requireNotNull(bad.metadata(control.key)), SyncInboxStage.INVALID_OBJECT)
        assertEquals(revoked.reference(), recovered.ledgerCheckpoint)
        assertFalse(g.custody().ledger().current().members.getValue(g.peer.fingerprint).active)
        assertFailsWith<IllegalArgumentException> { g.custody().key(nextKey.epoch) }
        assertContentEquals(badBytes, raw(reader, requireNotNull(bad.metadata(control.key))))
    }

    private fun candidateReader(g: Group, name: String, source: CiphertextStore): LanSyncGroupReplicaReader =
        LanSyncGroupReplicaReader(FileCiphertextStore(g.root.resolve("$name-local")), g.root.resolve("$name-work")) { _, failure -> throw failure }
            .also { it.routes(listOf(LanSyncGroupReplicaReader.Route("actual-file-peer", source))) }
    private fun all(store: CiphertextStore): List<CiphertextObjectMetadata> {
        val entries = mutableListOf<CiphertextObjectMetadata>(); var cursor: CommitCursor? = null
        do { val page = store.listCommits(cursor, 1); entries += page.entries; cursor = page.nextCursor } while (cursor != null)
        return entries
    }
    private fun raw(store: CiphertextStore, metadata: CiphertextObjectMetadata): ByteArray = ByteArrayOutputStream().also {
        assertEquals(metadata, store.readTo(metadata.key, it, metadata.descriptor))
    }.toByteArray()
    private fun corruptTail(bytes: ByteArray) = bytes.copyOf().also {
        assertTrue(it.isNotEmpty()); it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
    }
    private fun copiedPeer(g: Group, name: String, replacement: Map<CiphertextObjectKey, ByteArray>): FileCiphertextStore {
        val store = FileCiphertextStore(g.root.resolve(name))
        all(g.backend).forEach { metadata ->
            val bytes = replacement[metadata.key] ?: raw(g.backend, metadata)
            store.put(CiphertextPutRequest(metadata.key, SyncStoreId.random(), SyncKeyDeliveryCodec.descriptor(bytes)), bytes.inputStream())
        }
        return store
    }
    private fun assertObservation(view: SyncInboxReconciliation, metadata: CiphertextObjectMetadata, stage: SyncInboxStage) {
        assertEquals(stage, view.observations.single { it.key == metadata.key && it.descriptor == metadata.descriptor }.stage)
    }

    @Test fun newEpochContentWaitsUntilOwnerSignedCapsulesAndCompleteLedgerPermitProtectedKeyAdmission() = withGroup { g ->
        g.publishLedger(); val parent = g.version("synthetic/one"); g.publish(parent)
        assertEquals(SyncVersionState.ADMITTED, g.scan().history?.state(parent.id))
        val key = SyncStreamingCipher.create(g.initialKey.group, SyncStoreId.random()); g.keys[key.epoch] = key
        val next = g.sign(g.genesis.copy(id = SyncStoreId.random(), sequence = 2, parent = g.genesis.reference(), epoch = key.epoch,
            keyCommitment = key.keyCommitment(), acceptedHeads = setOf(parent.reference())))
        g.ledger.offer(next)
        val child = g.version("synthetic/one", 2, parent); val pending = g.publish(child)
        val before = g.scan()
        assertEquals(SyncInboxStage.WAITING_FOR_KEY, before.observations.single { it.key == pending.request.key }.stage)
        assertEquals(SyncVersionState.ADMITTED, before.history?.state(parent.id))
        g.publishLedger()
        val complete = g.scan(g.inbox())
        assertEquals(SyncVersionState.ADMITTED, complete.history?.state(child.id))
        assertEquals(setOf(child.reference()), complete.history?.heads())
        assertEquals(next.reference(), g.custody().ledger().trustAnchor.checkpoint)
        assertEquals(key.keyCommitment(), g.custody().key(key.epoch).keyCommitment())
        assertEquals(g.initialKey.keyCommitment(), g.custody().key(g.initialKey.epoch).keyCommitment())
    }

    @Test fun aLocalProtectionFailureIsVisibleAndCannotBeMisreportedAsAMissingRemoteKey() = withGroup { g ->
        g.publishLedger(); val version = g.version("synthetic/one"); g.publish(version)
        g.scan()
        val unavailable = SyncGroupCustody(g.custodyRoot, g.initialKey.group, g.recipient, { it.reversedArray() },
            { throw IOException("SYNTHETIC_LOCAL_PROTECTION_UNAVAILABLE") })
        val inbox = SyncGroupInbox(g.receiverRoot, g.backend, unavailable, g.recipient)
        assertTrue(inbox.discover(pageSize = 100).observations.all { it.stage == SyncInboxStage.MIRRORED })
        assertEquals("SYNC_INBOX_LOCAL_CUSTODY", assertFailsWith<IllegalStateException> { inbox.reconcile() }.message)
        assertEquals(SyncVersionState.ADMITTED, g.inbox().reconcile().history?.state(version.id))
    }

    @Test fun offlineQueuedVersionsKeepTheirExactAuthorChainWithoutCreatingLedgerTrustOrPretendingTheyWereUploaded() = withGroup { g ->
        g.publishLedger(); val independent = g.version("synthetic/remote"); g.publish(independent); g.scan()
        val parent = g.version("synthetic/local", peerAuthor = true)
        val child = g.version("synthetic/local", 2, parent, peerAuthor = true)
        val outbox = g.outbox(g.initialKey)
        fun queued(envelope: SyncGroupEnvelope): SyncAuthenticatedGroupObject {
            val prepared = g.codec.enqueue(outbox, SyncStoreId.random(), CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random()),
                g.initialKey, envelope)
            assertNull(g.backend.metadata(prepared.request.key))
            return g.codec.readQueued(outbox, prepared.request.operationId)
        }
        val parentObject = queued(SyncGroupEnvelope(2, SyncGroupObjectKind.VERSION, version = parent))
        val childObject = queued(SyncGroupEnvelope(2, SyncGroupObjectKind.VERSION, version = child))
        val ledgerObject = queued(SyncGroupEnvelope(2, SyncGroupObjectKind.DEVICE_LEDGER, deviceLedger = g.genesis))
        val unavailable = object : CiphertextStore by g.backend {
            override fun listCommits(cursor: CommitCursor?, pageSize: Int): CommitPage = throw IOException("SYNTHETIC_OFFLINE")
        }
        val inbox = g.inbox(unavailable)
        assertEquals(SyncVersionState.WAITING_FOR_PARENTS, inbox.reconcile(listOf(childObject)).history?.state(child.id))
        val complete = inbox.reconcile(listOf(childObject, parentObject, ledgerObject))
        assertEquals(setOf(independent.reference(), child.reference()), complete.history?.heads())
        assertEquals(SyncInboxStage.INVALID_OBJECT, complete.observations.single { it.key == ledgerObject.context.key }.stage)
        assertEquals(g.genesis.reference(), complete.ledgerCheckpoint)
        assertNull(g.backend.metadata(parentObject.context.key)); assertNull(g.backend.metadata(childObject.context.key))
        assertEquals(complete.history?.heads(), g.inbox(unavailable).reconcile(listOf(parentObject, childObject)).history?.heads())
    }

    @Test fun anOfflineRevokedRecipientPersistsItsRevocationWithoutReceivingTheNewGroupKeyOrFutureRoster() = withGroup { g ->
        g.publishLedger(); g.scan()
        assertEquals(g.initialKey.keyCommitment(), g.custody().key(g.initialKey.epoch).keyCommitment())
        val nextKey = SyncStreamingCipher.create(g.initialKey.group); g.keys[nextKey.epoch] = nextKey
        val revoked = g.sign(g.genesis.copy(id = SyncStoreId.random(), parent = g.genesis.reference(), sequence = 2,
            epoch = nextKey.epoch, keyCommitment = nextKey.keyCommitment(), members = g.members +
                (g.peer.fingerprint to g.members.getValue(g.peer.fingerprint).copy(active = false))))
        g.ledger.offer(revoked)
        // The revoked device missed this publication; only a later root revision reaches the backend.
        val later = g.sign(revoked.copy(id = SyncStoreId.random(), parent = revoked.reference(), sequence = 3))
        g.ledger.offer(later); val plan = g.publishLedger()
        assertTrue(plan.keyDeliveries.keys.none { it.endsWith(g.peer.fingerprint) }); assertEquals(1, plan.revocationNotices.size)
        val result = g.scan(g.inbox())
        assertFalse(result.trustBlocked); assertEquals(revoked.reference(), result.ledgerCheckpoint)
        assertEquals(SyncInboxStage.DEVICE_REVOKED, result.observations.single { it.key == plan.revocationNotices.getValue(g.peer.fingerprint).key }.stage)
        assertEquals(SyncInboxStage.WAITING_FOR_KEY, result.observations.single { it.key == plan.metadata.key }.stage)
        val reopened = g.custody(); assertFalse(reopened.ledger().current().members.getValue(g.peer.fingerprint).active)
        assertEquals(revoked.reference(), reopened.trust().checkpoint)
        assertEquals(g.initialKey.keyCommitment(), reopened.key(g.initialKey.epoch).keyCommitment())
        assertEquals("SYNC_GROUP_CUSTODY_KEY_MISSING", assertFailsWith<IllegalArgumentException> { reopened.key(nextKey.epoch) }.message)
        assertEquals(revoked.reference(), g.inbox().reconcile().ledgerCheckpoint)
    }
}
