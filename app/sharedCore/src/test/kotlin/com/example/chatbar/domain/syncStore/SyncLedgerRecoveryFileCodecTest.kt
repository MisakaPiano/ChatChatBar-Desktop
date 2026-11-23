package com.example.chatbar.domain.syncStore

import com.example.chatbar.domain.lanSync.LanSyncTlsIdentity
import com.example.chatbar.domain.lanSync.lanSyncJson
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SyncLedgerRecoveryFileCodecTest {
    @Test fun oneExactFileCanBeVerifiedAgainstEitherPinnedBranchWithOnlyTheLocalRecipientsKeys() {
        val f = Fixture()
        val file = f.write()
        val left = SyncLedgerRecoveryFileCodec.read(file.inputStream(), f.trust.copy(checkpoint = f.left.reference()), f.leftRecipient)
        val right = SyncLedgerRecoveryFileCodec.read(file.inputStream(), f.trust.copy(checkpoint = f.right.reference()), f.rightRecipient)

        assertEquals(f.rows, left.records)
        assertEquals(left.records, right.records)
        assertEquals(f.recovered, left.recovery)
        assertEquals(f.recovered.reference(), right.recovery.reference())
        assertEquals(f.left.reference(), left.primary)
        assertEquals(left.primary, right.primary)
        assertEquals(left.binding, right.binding)
        assertTrue(left.binding.matches(Regex("[a-f0-9]{64}")))
        assertEquals(f.epochs.map { it.epoch }.toSet(), left.keys.map { it.cipher.epoch }.toSet())
        assertEquals(f.epochs.map { it.epoch }.toSet(), right.keys.map { it.cipher.epoch }.toSet())
        assertTrue(left.keys.all { it.recipient == f.leftRecipient.device })
        assertTrue(right.keys.all { it.recipient == f.rightRecipient.device })
        assertEquals(f.newEpoch.keyCommitment(), right.keys.single { it.cipher.epoch == f.newEpoch.epoch }.cipher.keyCommitment())
        val otherFile = f.write(f.capsules.asReversed())
        assertNotEquals(left.binding, SyncLedgerRecoveryFileCodec.read(otherFile.inputStream(), f.trust, f.leftRecipient).binding)
    }

    @Test fun removingDuplicatingOrAppendingUnreviewedBranchesCannotBecomeAnAcceptedRecovery() {
        val f = Fixture()
        val unrelated = f.sign(f.left.copy(id = SyncStoreId.random(), signature = "unsigned"))
        val absentParent = listOf(f.genesis, f.left, unrelated, f.recovered)
        val reusedEpoch = f.sign(f.recovered.copy(epoch = f.oldEpoch.epoch, keyCommitment = f.oldEpoch.keyCommitment()))
        for (rows in listOf(absentParent, f.rows.dropLast(1) + listOf(unrelated, f.recovered), f.rows + f.recovered,
                f.rows.dropLast(1) + reusedEpoch,
                listOf(f.genesis, f.right, f.recovered, f.left))) {
            assertFails { SyncLedgerRecoveryFileCodec.read(f.raw(rows = rows).inputStream(), f.trust, f.leftRecipient) }
        }
        assertFails {
            SyncLedgerRecoveryFileCodec.read(f.write().inputStream(), f.trust.copy(checkpoint = unrelated.reference()), f.leftRecipient)
        }
    }

    @Test fun wrongTrustOmittedLocalKeyRevocationAndTamperedOrDuplicateCapsulesAreRefused() {
        val f = Fixture()
        val file = f.write()
        val foreignOwner = LanSyncTlsIdentity.create()
        val foreignCertificate = Base64.getEncoder().encodeToString(foreignOwner.certificate.encoded)
        assertFails {
            SyncLedgerRecoveryFileCodec.read(file.inputStream(), f.trust.copy(ownerCertificate = foreignCertificate), f.leftRecipient)
        }
        assertFails {
            SyncLedgerRecoveryFileCodec.read(file.inputStream(), f.trust.copy(group = SyncStoreId.random()), f.leftRecipient)
        }
        assertEquals("SYNC_GROUP_LEDGER_RECOVERY_FILE_RECIPIENT_REVOKED", assertFailsWith<IllegalArgumentException> {
            SyncLedgerRecoveryFileCodec.read(file.inputStream(), f.trust, f.revokedRecipient)
        }.message)
        val notice = SyncLedgerRecoveryFileCodec.read(file.inputStream(), f.trust, f.revokedRecipient, allowRevokedNotice = true)
        assertTrue(notice.recipientRevoked)
        assertTrue(notice.keys.isEmpty())
        assertEquals(f.recovered, notice.recovery)
        val wrongKey = SyncDeviceEncryptionKey.create(f.leftRecipient.device)
        assertFails { SyncLedgerRecoveryFileCodec.read(file.inputStream(), f.trust, wrongKey) }
        val capsule = SyncKeyDeliveryCodec.decode(f.capsules.first())
        val tampered = SyncKeyDeliveryCodec.encode(capsule.copy(signature = "unsigned"))
        assertFails {
            SyncLedgerRecoveryFileCodec.read(f.raw(capsules = listOf(tampered) + f.capsules.drop(1)).inputStream(), f.trust, f.leftRecipient)
        }
        val duplicate = listOf(f.capsules[1]) + f.capsules.drop(1)
        assertEquals("SYNC_GROUP_LEDGER_RECOVERY_FILE_DUPLICATE_CAPSULE", assertFailsWith<IllegalArgumentException> {
            SyncLedgerRecoveryFileCodec.read(f.raw(capsules = duplicate).inputStream(), f.trust, f.leftRecipient)
        }.message)
    }

    @Test fun allRecipientsNeedEveryEpochAndEachSlotMustBeAuthorizedByTheExactSignedRecovery() {
        val f = Fixture()
        for (epoch in listOf(f.oldEpoch, f.newEpoch)) {
            val withoutOtherRecipient = f.capsules.filterNot {
                val role = SyncKeyDeliveryCodec.decode(it).context
                role.recipient == f.rightRecipient.device && role.epoch == epoch.epoch
            }
            assertEquals("SYNC_GROUP_LEDGER_RECOVERY_FILE_CAPSULE_MATRIX", assertFailsWith<IllegalArgumentException> {
                SyncLedgerRecoveryFileCodec.read(f.raw(capsules = withoutOtherRecipient).inputStream(), f.trust, f.leftRecipient)
            }.message)
            assertFails { f.write(withoutOtherRecipient) }
        }
        val staleCapsule = f.staleAuthorityCapsule()
        val stale = f.capsules.map { bytes ->
            val context = SyncKeyDeliveryCodec.decode(bytes).context
            if (context.recipient == f.leftRecipient.device && context.epoch == f.oldEpoch.epoch) staleCapsule else bytes
        }
        assertEquals("SYNC_GROUP_LEDGER_RECOVERY_FILE_CAPSULE_AUTHORITY", assertFailsWith<IllegalArgumentException> {
            SyncLedgerRecoveryFileCodec.read(f.raw(capsules = stale).inputStream(), f.trust, f.leftRecipient)
        }.message)
    }

    @Test fun trailingBytesUnsupportedVersionAndOversizedFrameAreRefused() {
        val f = Fixture()
        val file = f.write()
        val wrongVersion = file.copyOf().also { it["CCB-GROUP-LEDGER-RECOVERY\n".length] = 1 }
        val lengthOffset = "CCB-GROUP-LEDGER-RECOVERY\n".length + 1 + 8
        val oversizedFrame = file.copyOf().also { it[lengthOffset] = 0x7f }
        for (invalid in listOf(wrongVersion, file.copyOf(file.size - 1), file + byteArrayOf(0), oversizedFrame)) {
            assertFails { SyncLedgerRecoveryFileCodec.read(invalid.inputStream(), f.trust, f.leftRecipient) }
        }
    }

    private class Fixture {
        private val owner = LanSyncTlsIdentity.create()
        private val identities = listOf(owner, LanSyncTlsIdentity.create(), LanSyncTlsIdentity.create(), LanSyncTlsIdentity.create())
        private val recipients = identities.map { SyncDeviceEncryptionKey.create(it.fingerprint) }
        val leftRecipient = recipients[1]
        val rightRecipient = recipients[2]
        val revokedRecipient = recipients[3]
        val oldEpoch = SyncStreamingCipher.create()
        val newEpoch = SyncStreamingCipher.create(oldEpoch.group)
        private val members = identities.zip(recipients).associate { (identity, recipient) ->
            identity.fingerprint to SyncGroupMember(Base64.getEncoder().encodeToString(identity.certificate.encoded), recipient.publicKey.encoded)
        }
        val genesis = sign(SyncDeviceLedgerRecord(oldEpoch.group, SyncStoreId.random(), null, 1, oldEpoch.epoch,
            oldEpoch.keyCommitment(), setOf("chat_sessions"), members, emptySet(), "unsigned"))
        private val revokedEpoch = SyncStreamingCipher.create(oldEpoch.group)
        private val revokedMembers = members + (revokedRecipient.device to members.getValue(revokedRecipient.device).copy(active = false))
        val left = sign(genesis.copy(id = SyncStoreId.random(), parent = genesis.reference(), sequence = 2, epoch = revokedEpoch.epoch,
            keyCommitment = revokedEpoch.keyCommitment(), members = revokedMembers))
        val right = sign(genesis.copy(id = SyncStoreId.random(), parent = genesis.reference(), sequence = 2))
        val recovered = sign(left.copy(id = SyncStoreId.random(), parent = null, sequence = 3,
            epoch = newEpoch.epoch, keyCommitment = newEpoch.keyCommitment(),
            recovery = SyncGroupLedgerRecovery(setOf(left.reference(), right.reference()), left.reference())))
        val trust = SyncGroupTrust(oldEpoch.group, genesis.reference(), members.getValue(owner.fingerprint).certificate)
        val rows = listOf(genesis, left, right, recovered)
        private val recoveredGraph = SyncDeviceLedger(trust).also { graph -> rows.forEach { graph.offer(it) } }
        val epochs = listOf(oldEpoch, revokedEpoch, newEpoch)
        val capsules = epochs.flatMap { epoch -> recipients.take(3).map { recipient -> capsule(epoch, recipient) } }

        fun staleAuthorityCapsule(): ByteArray {
            val linear = SyncDeviceLedger(trust).also { it.offer(genesis) }
            return SyncKeyDeliveryCodec.encode(SyncGroupKeyDelivery.create(linear, oldEpoch, leftRecipient.device,
                SyncStoreId.random(), owner::signSyncGroupPayload))
        }

        private fun capsule(cipher: SyncStreamingCipher, recipient: SyncDeviceEncryptionKey): ByteArray =
            SyncKeyDeliveryCodec.encode(SyncGroupKeyDelivery.create(recoveredGraph, cipher, recipient.device, SyncStoreId.random(), owner::signSyncGroupPayload))

        fun sign(row: SyncDeviceLedgerRecord): SyncDeviceLedgerRecord =
            row.copy(signature = Base64.getEncoder().encodeToString(owner.signSyncGroupPayload(row.signingBytes())))

        fun write(keys: List<ByteArray> = capsules): ByteArray = ByteArrayOutputStream().also {
            SyncLedgerRecoveryFileCodec.write(it, trust, rows, keys)
        }.toByteArray()

        // Independent framing for negative cases that the exporter must also refuse.
        fun raw(rows: List<SyncDeviceLedgerRecord> = this.rows, capsules: List<ByteArray> = this.capsules): ByteArray =
            ByteArrayOutputStream().also { result ->
                DataOutputStream(result).apply {
                    write("CCB-GROUP-LEDGER-RECOVERY\n".toByteArray(Charsets.US_ASCII))
                    writeByte(2)
                    writeInt(rows.size)
                    writeInt(capsules.size)
                    val encoded = rows.map { lanSyncJson.encodeToString(SyncDeviceLedgerRecord.serializer(), it).toByteArray(Charsets.UTF_8) }
                    (encoded + capsules).forEach { frame -> writeInt(frame.size); write(frame) }
                }
            }.toByteArray()
    }
}
