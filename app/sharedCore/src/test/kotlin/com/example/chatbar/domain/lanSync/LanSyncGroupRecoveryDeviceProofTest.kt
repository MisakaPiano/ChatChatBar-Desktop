package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.syncStore.*
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** Inline data and actual TLS/HPKE keys; no account, LAN listener, bundled asset or OS protector. */
class LanSyncGroupRecoveryDeviceProofTest {
    private fun fixture(work: suspend (LanSyncGroupAdministrationTest.Fixture) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("synthetic-local-recovery-proof-").toRealPath()
        try {
            LanSyncGroupAdministrationTest.Fixture(root).use { founder -> founder.seed(); work(founder) }
        } finally { check(root.toFile().deleteRecursively()) }
    }
    private fun files(root: Path): Map<String, ByteArray> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) }.toList().associate { root.relativize(it).toString() to Files.readAllBytes(it) }
    }
    private fun unchanged(expected: Map<String, ByteArray>, root: Path) {
        val actual = files(root); assertEquals(expected.keys, actual.keys)
        expected.forEach { (name, value) -> assertContentEquals(value, actual.getValue(name), name) }
    }
    private fun counter(root: Path, code: String, vault: ByteArray): SyncRootLedgerState =
        SyncManagementSession.open(code, vault).use { session ->
            SyncRootLedgerJournal(root.resolve("root-actions"), session.trust, session.ownerCertificate,
                session::protectJournal, session::unprotectJournal).state()
        }

    @Test fun localProofUsesItsOwnSignatureDomainAndBindsTheExactRootCheckpointAndActualKeys() = fixture { f ->
        val trust = f.custody().ledger().let { it.trustAnchor.copy(checkpoint = it.current().reference()) }
        val identity = LanSyncTlsIdentity.create()
        val recipient = SyncDeviceEncryptionKey.create(identity.fingerprint)
        val first = LanSyncGroupRecoveryDeviceProof.create(trust, identity, recipient, "synthetic new daily")
        val second = LanSyncGroupRecoveryDeviceProof.create(trust, identity, recipient, "synthetic new daily")
        first.requireFor(trust)
        assertNotEquals(first.binding, second.binding)
        assertEquals(identity.fingerprint, first.device)
        assertEquals(recipient.publicKey.encoded, first.member.recipientKey)
        assertFailsWith<IllegalArgumentException> { first.requireFor(trust.copy(group = SyncStoreId.random())) }
        assertFailsWith<IllegalArgumentException> {
            first.requireFor(trust.copy(checkpoint = SyncVersionRef(SyncStoreId.random(), "7".repeat(64))))
        }
        assertFailsWith<IllegalArgumentException> {
            LanSyncGroupRecoveryDeviceProof.create(trust, identity,
                SyncDeviceEncryptionKey.create(LanSyncTlsIdentity.create().fingerprint), "wrong private recipient")
        }
        val payload = ("CCB-SYNC-RECOVERY-DEVICE-SIGNATURE/1\n" + "8".repeat(64)).toByteArray()
        assertTrue(SyncDeviceSignatures.isPayload(payload))
        val signature = java.util.Base64.getEncoder().encodeToString(identity.signSyncGroupPayload(payload))
        val certificate = SyncDeviceSignatures.decode(first.member.certificate)
        assertTrue(certificate.verifies(payload, signature))
        assertFalse(certificate.verifies(("CCB-SYNC-KEY-DELIVERY-SIGNATURE/1\n" + "8".repeat(64)).toByteArray(), signature))
    }

    @Test fun explicitLocalApprovalContinuesTheOriginalRootAndBootstrapsEveryHistoricalKeyWithoutTheOldDevice() = fixture { f ->
        f.rotateBeforeJoin()
        val card = f.data.resolve("entities/character_cards/retained.json"); Files.createDirectories(card.parent)
        LanSyncJsonFiles.write(card, CharacterCard.serializer(), CharacterCard("retained", "synthetic original", createdAt = 1, updatedAt = 1))
        val source = f.node.commit(f.node.inspect().review(setOf("character_cards/retained")))
        val target = requireNotNull(f.service.profile()).localReplica
        f.node.upload(source.entry.request.operation, FileCiphertextStore(f.profile.resolve("local-replica")), target)
        val original = files(f.data)
        val sourceOrigin = Files.readAllBytes(f.profile.resolve("node/author/origin.protected"))
        val vault = f.service.encryptedManagementBackup()
        val before = f.custody().ledger().current()
        val root = Files.createDirectory(f.root.resolve("recovered-manager"))
        val manager = LanSyncGroupManagementRecovery(root)
        try {
            manager.recover(f.code, vault.bytes(), FileCiphertextStore(f.profile.resolve("local-replica")),
                LanSyncManagementRecoveryAdmission { assertEquals(vault.context.group, it.group) })
            f.service.close()
            val originalProfile = files(f.profile)
            val retainedOld = Files.move(f.profile, f.root.resolve("offline-old-profile"))
            val managerOrigin = Files.readAllBytes(root.resolve("root-actions/origin.protected"))
            val stateBefore = counter(root, f.code, vault.bytes())
            val identity = LanSyncTlsIdentity.create(); val key = SyncDeviceEncryptionKey.create(identity.fingerprint)
            val preview = manager.prepareRecoveryDevice(identity, key, "new daily after loss")
            assertEquals(before, manager.ledger().current())
            assertEquals(stateBefore, counter(root, f.code, vault.bytes()))
            assertFailsWith<IllegalArgumentException> { preview.acknowledge("0".repeat(64)) }
            val approved = preview.acknowledge(preview.request.approvalBinding)
            val receipt = manager.addRecoveryDevice(approved)
            assertEquals(before.sequence + 1, receipt.ledger.sequence)
            assertEquals(before.epoch, receipt.ledger.epoch)
            assertEquals(before.group, receipt.ledger.group)
            assertEquals(before.members + (identity.fingerprint to preview.proof.member), receipt.ledger.members)
            assertEquals(stateBefore.completedSequence + 1, counter(root, f.code, vault.bytes()).completedSequence)
            assertContentEquals(managerOrigin, Files.readAllBytes(root.resolve("root-actions/origin.protected")))
            assertFailsWith<IllegalArgumentException> { manager.addRecoveryDevice(approved) }

            val bootstrap = manager.recoveryBootstrap()
            bootstrap.anchor.validate()
            assertEquals(bootstrap.anchor, lanSyncJson.decodeFromString(LanSyncGroupRecoveryAnchor.serializer(),
                lanSyncJson.encodeToString(LanSyncGroupRecoveryAnchor.serializer(), bootstrap.anchor)))
            assertEquals(receipt.ledger.reference(), bootstrap.anchor.trust.checkpoint)
            assertEquals(setOf(source.entry.version.reference()), bootstrap.anchor.dataHeads)
            assertEquals(vault.context, bootstrap.anchor.vault)
            val vaultCopy = ByteArrayOutputStream()
            assertEquals(bootstrap.anchor.vaultDescriptor,
                bootstrap.source.readTo(vault.key, vaultCopy, bootstrap.anchor.vaultDescriptor)?.descriptor)
            assertContentEquals(vault.bytes(), vaultCopy.toByteArray())
            assertFailsWith<IllegalStateException> {
                bootstrap.source.put(CiphertextPutRequest(vault.key, SyncStoreId.random(), bootstrap.anchor.vaultDescriptor), vault.bytes().inputStream())
            }
            val custody = SyncGroupCustody(Files.createDirectory(f.root.resolve("new-daily-custody")), before.group,
                key, { it.reversedArray() }, { it.reversedArray() })
            val inbox = SyncGroupInbox(Files.createDirectory(f.root.resolve("new-daily-inbox")), bootstrap.source, custody, key)
            var cursor: CommitCursor? = null
            do { cursor = inbox.discover(cursor, 1).nextCursor } while (cursor != null)
            val inspection = inbox.prepareEnrollment(bootstrap.anchor.trust)
            assertEquals(SyncGroupBootstrapStage.READY, inspection.stage)
            val evidence = requireNotNull(inspection.evidence)
            val admitted = custody.enrollWithKeys(evidence.trust, evidence.records, evidence.keys)
            assertEquals(receipt.ledger, admitted.current())
            admitted.history().distinctBy { it.epoch }.forEach { row -> assertEquals(row.keyCommitment, custody.key(row.epoch).keyCommitment()) }
            assertContentEquals(sourceOrigin, Files.readAllBytes(retainedOld.resolve("node/author/origin.protected")))
            unchanged(originalProfile, retainedOld)
            unchanged(original, f.data)
        } finally { manager.close() }
    }

    @Test fun lockReplacementCheckpointAdvanceAndColdReopenRefuseOldApprovalsAndBorrowedSources() = fixture { f ->
        val vault = f.service.encryptedManagementBackup()
        val root = Files.createDirectory(f.root.resolve("review-lifecycle-manager"))
        var manager = LanSyncGroupManagementRecovery(root)
        try {
            manager.recover(f.code, vault.bytes(), FileCiphertextStore(f.profile.resolve("local-replica")), LanSyncManagementRecoveryAdmission { })
            val firstIdentity = LanSyncTlsIdentity.create(); val firstKey = SyncDeviceEncryptionKey.create(firstIdentity.fingerprint)
            val first = manager.prepareRecoveryDevice(firstIdentity, firstKey, "first review")
            val firstApproval = first.acknowledge(first.request.approvalBinding)
            val borrowed = manager.recoveryBootstrap()
            manager.lock()
            assertFailsWith<IllegalArgumentException> { manager.addRecoveryDevice(firstApproval) }
            assertFailsWith<IllegalStateException> { borrowed.source.listCommits() }
            manager.unlock(f.code)
            assertFailsWith<IllegalArgumentException> { manager.addRecoveryDevice(firstApproval) }
            val replaced = manager.prepareRecoveryDevice(firstIdentity, firstKey, "replacement review")
            val staleSource = manager.recoveryBootstrap()
            val secondIdentity = LanSyncTlsIdentity.create(); val secondKey = SyncDeviceEncryptionKey.create(secondIdentity.fingerprint)
            val current = manager.prepareRecoveryDevice(secondIdentity, secondKey, "latest review")
            assertFailsWith<IllegalArgumentException> { manager.addRecoveryDevice(replaced.acknowledge(replaced.request.approvalBinding)) }
            val origin = Files.readAllBytes(root.resolve("root-actions/origin.protected"))
            val added = manager.addRecoveryDevice(current.acknowledge(current.request.approvalBinding))
            assertFailsWith<IllegalArgumentException> { staleSource.source.listCommits() }
            assertFailsWith<IllegalArgumentException> { manager.prepareRecoveryDevice(secondIdentity, secondKey, "known member") }
            assertFailsWith<IllegalArgumentException> {
                manager.prepareRecoveryDevice(LanSyncTlsIdentity.create(), secondKey, "reused recipient")
            }
            val state = counter(root, f.code, vault.bytes())
            manager.close(); manager = LanSyncGroupManagementRecovery(root)
            manager.unlock(f.code)
            assertEquals(added.ledger, manager.ledger().current())
            assertEquals(state, counter(root, f.code, vault.bytes()))
            assertContentEquals(origin, Files.readAllBytes(root.resolve("root-actions/origin.protected")))
            assertFailsWith<IllegalArgumentException> { manager.addRecoveryDevice(firstApproval) }
            val fresh = manager.recoveryBootstrap()
            var chunks = 0
            assertFailsWith<IllegalStateException> {
                fresh.source.readTo(vault.key, object : java.io.OutputStream() {
                    override fun write(value: Int) { chunks++; manager.lock() }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) { chunks++; manager.lock() }
                }, fresh.anchor.vaultDescriptor)
            }
            assertEquals(1, chunks)
            assertEquals(state, counter(root, f.code, vault.bytes()))
        } finally { manager.close() }
    }
}
