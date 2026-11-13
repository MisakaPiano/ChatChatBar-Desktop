package com.example.chatbar.domain.syncStore

import com.example.chatbar.domain.lanSync.LanSyncTlsIdentity
import java.security.MessageDigest
import java.util.Base64
import java.nio.file.Files
import kotlin.test.*

/** Actual existing device certificates/signatures; native enrollment/key delivery are separate. */
class SyncDeviceLedgerTest {
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    private inner class Group(initialMembers: Int = 4) {
        val devices = List(4) { LanSyncTlsIdentity.create() }
        val group = SyncStoreId.random()
        val members = devices.associate { it.fingerprint to SyncGroupMember(Base64.getEncoder().encodeToString(it.certificate.encoded)) }
        fun sign(record: SyncDeviceLedgerRecord, signer: Int = 0) = record.copy(signature =
            Base64.getEncoder().encodeToString(devices[signer].signSyncGroupPayload(record.signingBytes())))
        val genesis = sign(SyncDeviceLedgerRecord(group, SyncStoreId.random(), null, 1, SyncStoreId.random(),
            members.filterKeys { id -> devices.take(initialMembers).any { it.fingerprint == id } }, emptySet(), "unsigned"))
        val trust = SyncGroupTrust(group, genesis.reference(), members.getValue(devices[0].fingerprint).certificate)
        fun state() = SyncDeviceLedger(trust)
        fun next(parent: SyncDeviceLedgerRecord, nextMembers: Map<String, SyncGroupMember> = parent.members,
            epoch: SyncStoreId = parent.epoch, accepted: Set<SyncVersionRef> = parent.acceptedHeads) =
            sign(SyncDeviceLedgerRecord(group, SyncStoreId.random(), parent.reference(), parent.sequence + 1, epoch, nextMembers, accepted, "unsigned"))
        fun value(text: String, previous: Set<SyncVersionRef> = emptySet()) = SyncUnitChange(hash(text),
            SyncVersionContent(SyncStoreId.random(), CiphertextDescriptor(119, CiphertextSha256.parse(hash("synthetic ciphertext $text")))), previous)
        fun version(actor: Int, ledger: SyncDeviceLedgerRecord = genesis, parents: Set<SyncVersionRef> = emptySet(),
            changes: Map<String, SyncUnitChange> = emptyMap(), previous: SyncCausalVersion? = null): SyncCausalVersion {
            val unsigned = SyncCausalVersion(group, SyncStoreId.random(), ledger.reference(), devices[actor].fingerprint,
                previous?.authorSequence?.plus(1) ?: 1, previous?.reference(), parents, changes, "unsigned")
            return unsigned.copy(signature = Base64.getEncoder().encodeToString(devices[actor].signSyncGroupPayload(unsigned.signingBytes())))
        }
        fun policy(state: SyncDeviceLedger, known: List<SyncCausalVersion>, epochs: Map<SyncVersionRef, SyncStoreId>): SyncVersionAdmission {
            val root = Files.createTempDirectory("sync-ledger-actual-aead-").toRealPath()
            try {
                val codec = SyncGroupObjectCodec(root)
                val backend = FileCiphertextStore(root.resolve("backend"))
                val keys = epochs.values.toSet().associateWith { SyncStreamingCipher.create(group, it) }
                val objects = epochs.map { (reference, epoch) ->
                    val cipher = keys.getValue(epoch)
                    val directory = root.resolve(epoch.value)
                    if (!Files.exists(directory)) Files.createDirectory(directory)
                    val outbox = SyncEncryptedOutbox(directory, cipher)
                    val key = CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random())
                    val operation = SyncStoreId.random()
                    val envelope = SyncGroupEnvelope(1, SyncGroupObjectKind.VERSION, version = known.first { it.reference() == reference })
                    val ready = codec.enqueue(outbox, operation, key, cipher, envelope)
                    outbox.upload(operation, backend)
                    codec.read(backend, cipher, ready.context, ready.request.descriptor, reference)
                }
                return state.admission(known, objects)
            } finally { check(root.toFile().deleteRecursively()) }
        }
    }

    @Test fun pinnedGenesisAndOwnerSignaturesControlOutOfOrderEnrollmentAndExistingDeviceIdentityReopens() {
        val group = Group(2); val state = group.state()
        val enrollment = group.next(group.genesis, group.genesis.members + (group.devices[2].fingerprint to group.members.getValue(group.devices[2].fingerprint)))
        assertEquals(SyncVersionState.WAITING_FOR_PARENTS, state.offer(enrollment))
        assertEquals(SyncVersionState.ADMITTED, state.offer(group.genesis)); assertEquals(enrollment, state.current())
        val version = group.version(2, enrollment, changes = mapOf("card/a" to group.value("新设备内容")))
        val graph = SyncCausalHistory(group.group, group.policy(state, listOf(version), mapOf(version.reference() to enrollment.epoch)))
        assertEquals(SyncVersionState.ADMITTED, graph.offer(version))
        val beforeAdmission = group.version(2, group.genesis)
        assertEquals("SYNC_GROUP_AUTHOR_NOT_ADMITTED", assertFailsWith<IllegalArgumentException> {
            group.policy(state, listOf(beforeAdmission), mapOf(beforeAdmission.reference() to enrollment.epoch)).requireAuthorized(beforeAdmission)
        }.message)
        val reopenedOwner = LanSyncTlsIdentity.decode(group.devices[0].encode())
        assertEquals(group.devices[0].fingerprint, reopenedOwner.fingerprint)
        val certificate = SyncDeviceSignatures.decode(group.trust.ownerCertificate)
        val payload = version.signingBytes(); val signature = Base64.getEncoder().encodeToString(reopenedOwner.signSyncGroupPayload(payload))
        assertTrue(certificate.verifies(payload, signature))
        assertEquals("SYNC_GROUP_SIGNATURE_DOMAIN", assertFailsWith<IllegalArgumentException> {
            reopenedOwner.signSyncGroupPayload("arbitrary request outside group protocols".toByteArray())
        }.message)
        val reopened = group.state(); reopened.offer(enrollment); reopened.offer(group.genesis)
        assertEquals(enrollment.reference(), reopened.current().reference())
    }

    @Test fun revokedDeviceCannotBackdateLateVersionsWhileFencedHistoryAndActiveOfflineBranchesSurvive() {
        val group = Group(); val initial = group.version(0, changes = mapOf("card/a" to group.value("原件")))
        val accepted = group.version(1, parents = setOf(initial.reference()), changes = mapOf("card/a" to group.value("已共同接受", setOf(initial.reference()))))
        val late = group.version(1, parents = setOf(accepted.reference()), changes = mapOf("card/a" to group.value("撤销设备晚到修改", setOf(accepted.reference()))), previous = accepted)
        val offline = group.version(2, parents = setOf(initial.reference()), changes = mapOf("card/b" to group.value("仍可信设备的离线修改")))
        val revoked = group.next(group.genesis, group.members + (group.devices[1].fingerprint to group.members.getValue(group.devices[1].fingerprint).copy(active = false)),
            SyncStoreId.random(), setOf(accepted.reference()))
        val state = group.state(); state.offer(revoked); state.offer(group.genesis)
        val known = listOf(initial, accepted, late, offline)
        val oldProofs = known.associate { it.reference() to group.genesis.epoch }
        val oldPolicy = group.policy(state, known, oldProofs)
        oldPolicy.requireAuthorized(initial); oldPolicy.requireAuthorized(accepted)
        assertEquals("SYNC_GROUP_REVOKED_VERSION", assertFailsWith<IllegalArgumentException> { oldPolicy.requireAuthorized(late) }.message)
        assertEquals("SYNC_GROUP_CURRENT_EPOCH_REQUIRED", assertFailsWith<IllegalArgumentException> { oldPolicy.requireAuthorized(offline) }.message)
        // An active device re-encrypts the same signed offline version under the new epoch;
        // its logical ID, author predecessor and content ancestry remain unchanged.
        val policy = group.policy(state, known, oldProofs + (offline.reference() to revoked.epoch) + (late.reference() to revoked.epoch))
        policy.requireAuthorized(offline)
        assertEquals("SYNC_GROUP_REVOKED_VERSION", assertFailsWith<IllegalArgumentException> { policy.requireAuthorized(late) }.message)
        val graph = SyncCausalHistory(group.group, policy)
        listOf(offline, accepted, initial).forEach(graph::offer)
        assertEquals(accepted.reference(), graph.frontier("card/a").single().version)
        assertEquals(offline.reference(), graph.frontier("card/b").single().version)
    }

    @Test fun revocationRequiresFreshEpochAndNeverReactivatesTheSameCertificateOrDropsEarlierFences() {
        val group = Group(); val state = group.state(); state.offer(group.genesis)
        val member = group.devices[1].fingerprint
        val reduced = group.members + (member to group.members.getValue(member).copy(active = false))
        val unsafe = group.next(group.genesis, reduced)
        assertEquals(SyncVersionState.REJECTED, state.offer(unsafe)); assertEquals("SYNC_GROUP_REVOCATION_EPOCH", state.failure(unsafe.id))
        val accepted = group.version(1)
        val revoked = group.next(group.genesis, reduced, SyncStoreId.random(), setOf(accepted.reference()))
        assertEquals(SyncVersionState.ADMITTED, state.offer(revoked))
        val reactivated = group.next(revoked, group.members)
        assertEquals(SyncVersionState.REJECTED, state.offer(reactivated)); assertEquals("SYNC_GROUP_MEMBER_REACTIVATION", state.failure(reactivated.id))
        val lostFence = group.next(revoked, epoch = SyncStoreId.random(), accepted = emptySet())
        assertEquals(SyncVersionState.REJECTED, state.offer(lostFence)); assertEquals("SYNC_GROUP_FENCE_LOST", state.failure(lostFence.id))
        val reused = group.next(revoked, epoch = group.genesis.epoch)
        assertEquals(SyncVersionState.REJECTED, state.offer(reused)); assertEquals("SYNC_GROUP_EPOCH_REUSED", state.failure(reused.id))
        assertEquals(revoked.reference(), state.current().reference())
    }

    @Test fun wrongOwnerOrGenesisAndADeviceLedgerForkCannotSilentlyChooseTrust() {
        val group = Group(); val state = group.state()
        val forged = group.sign(group.genesis, signer = 1)
        assertEquals("SYNC_GROUP_OWNER_SIGNATURE", assertFailsWith<IllegalArgumentException> { state.offer(forged) }.message)
        val anotherGenesis = group.sign(group.genesis.copy(id = SyncStoreId.random()))
        assertEquals(SyncVersionState.REJECTED, state.offer(anotherGenesis)); assertEquals("SYNC_GROUP_GENESIS_PIN", state.failure(anotherGenesis.id))
        state.offer(group.genesis)
        val a = group.next(group.genesis); val b = group.next(group.genesis)
        state.offer(a); state.offer(b)
        assertEquals("SYNC_GROUP_LEDGER_FORK_OR_MISSING", assertFailsWith<IllegalArgumentException> { state.current() }.message)
        assertEquals("SYNC_GROUP_LEDGER_FORK_OR_MISSING", assertFailsWith<IllegalArgumentException> { state.admission(emptyList(), emptyList()) }.message)
    }

    @Test fun missingFenceBodiesAndForgedContentNeverBecomeImplicitAuthorization() {
        val group = Group(); val initial = group.version(0, changes = mapOf("card/a" to group.value("原件")))
        val accepted = group.version(1, parents = setOf(initial.reference()))
        val revoked = group.next(group.genesis, group.members + (group.devices[1].fingerprint to group.members.getValue(group.devices[1].fingerprint).copy(active = false)),
            SyncStoreId.random(), setOf(accepted.reference()))
        val state = group.state(); state.offer(group.genesis); state.offer(revoked)
        assertEquals("SYNC_GROUP_FENCE_CONTENT_MISSING", assertFailsWith<IllegalArgumentException> {
            state.admission(emptyList(), emptyList()).requireAuthorized(initial)
        }.message)
        val policy = state.admission(listOf(initial, accepted), emptyList())
        policy.requireAuthorized(initial); policy.requireAuthorized(accepted)
        val forged = initial.copy(changes = mapOf("card/a" to group.value("伪造正文")))
        assertEquals("SYNC_GROUP_VERSION_SIGNATURE", assertFailsWith<IllegalArgumentException> { policy.requireAuthorized(forged) }.message)
    }

    @Test fun anAlreadyObservedLedgerCheckpointCannotBeReplacedByAnOlderBackendView() {
        val group = Group(); val latest = group.next(group.genesis)
        val reopened = SyncDeviceLedger(group.trust.copy(checkpoint = latest.reference()))
        reopened.offer(group.genesis)
        assertEquals("SYNC_GROUP_KNOWN_CHECKPOINT_MISSING", assertFailsWith<IllegalArgumentException> { reopened.current() }.message)
        reopened.offer(latest); assertEquals(latest.reference(), reopened.current().reference())
        reopened.offer(group.genesis); assertEquals(latest.reference(), reopened.current().reference())
    }
}
