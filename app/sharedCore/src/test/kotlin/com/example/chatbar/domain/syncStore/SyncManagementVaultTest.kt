package com.example.chatbar.domain.syncStore

import com.example.chatbar.domain.lanSync.LanSyncTlsIdentity
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.*

/** Real Tink AEAD/private key recovery; synthetic daily protection, no saved recovery secrets. */
class SyncManagementVaultTest {
    private fun withRoot(work: (Path) -> Unit) {
        val root = Files.createTempDirectory("synthetic-management-").toRealPath()
        try { work(root) } finally { check(root.toFile().deleteRecursively()) }
    }
    private fun certificate(identity: LanSyncTlsIdentity) = Base64.getEncoder().encodeToString(identity.certificate.encoded)
    private fun trust(session: SyncManagementSession) = SyncGroupTrust(SyncStoreId.random(),
        SyncVersionRef(SyncStoreId.random(), "a".repeat(64)), session.ownerCertificate)

    @Test fun recoveryReopensIndependentAdminKeysAndReusesTheExactImmutableVault() {
        val original = SyncManagementSession.create()
        val owner = original.ownerCertificate; val recipient = original.recipientPublicKey
        val trust = trust(original); val blob = original.seal(trust); val code = original.recoveryCode()
        assertEquals(blob.context, SyncManagementSession.locate(code))
        val pendingJournal = original.protectJournal("synthetic approved intent".toByteArray())
        assertContentEquals(blob.bytes(), original.seal(trust).bytes())
        val copy = blob.bytes(); copy.fill(0); assertFalse(blob.bytes().all { it == 0.toByte() })
        original.close(); assertFalse(original.isUnlocked)
        val recovered = SyncManagementSession.open(code, blob.bytes())
        try {
            assertEquals(owner, recovered.ownerCertificate); assertEquals(recipient.encoded, recovered.recipientPublicKey.encoded)
            assertEquals(trust, recovered.trust); assertEquals(code, recovered.recoveryCode())
            assertContentEquals(blob.bytes(), recovered.seal(trust).bytes())
            assertContentEquals("synthetic approved intent".toByteArray(), recovered.unprotectJournal(pendingJournal))
            val row = SyncDeviceLedgerRecord(trust.group, trust.genesis.id, null, 1, SyncStoreId.random(), "b".repeat(64),
                setOf("synthetic_records"), mapOf(SyncDeviceSignatures.decode(owner).fingerprint to SyncGroupMember(owner, recipient.encoded)), emptySet(), "unsigned")
            val signature = Base64.getEncoder().encodeToString(recovered.signRootPayload(row.signingBytes()))
            assertTrue(SyncDeviceSignatures.decode(owner).verifies(row.signingBytes(), signature))
            assertEquals("SYNC_MANAGEMENT_SIGNATURE_DOMAIN", assertFailsWith<IllegalArgumentException> {
                recovered.signRootPayload(("CCB-SYNC-VERSION-SIGNATURE/1\n" + "a".repeat(64)).toByteArray())
            }.message)
        } finally { recovered.close() }
    }

    @Test fun creationIntentIsCodeProtectedBeforeGenesisAndReopensAfterTheDailyProcessIsGone() = withRoot { root ->
        val session = SyncManagementSession.create(); val daily = LanSyncTlsIdentity.create()
        val dailyRecipient = SyncDeviceEncryptionKey.create(daily.fingerprint)
        val creationRoot = Files.createDirectory(root.resolve("creation"))
        val owner = session.ownerCertificate; val adminRecipient = session.recipientPublicKey
        val request = SyncRootGroupCreateRequest(SyncStoreId.random(), setOf("synthetic_records"), "c".repeat(64))
        val creation = SyncRootGroupCreation.managed(creationRoot, owner, adminRecipient, certificate(daily), dailyRecipient,
            session::protectJournal, session::unprotectJournal)
        val created = creation.create(request, SyncRootGroupCreateAdmission {}, session::signRootPayload)
        val vault = session.seal(created.trust); val code = session.recoveryCode(); session.close()
        val reopened = SyncManagementSession.open(code, vault.bytes())
        try {
            val resumed = SyncRootGroupCreation.managed(creationRoot, owner, adminRecipient, certificate(daily), dailyRecipient,
                reopened::protectJournal, reopened::unprotectJournal).resume(request.operation) { error("SYNTHETIC_NO_RESIGN") }
            assertEquals(created.genesis, resumed.genesis)
            val custody = SyncGroupCustody(Files.createDirectory(root.resolve("daily-custody")), created.trust.group, dailyRecipient,
                { it.reversedArray() }, { it.reversedArray() })
            val initial = resumed.enrollCreator(custody)
            val journalRoot = Files.createDirectory(root.resolve("admin-journal"))
            val journal = SyncRootLedgerJournal(journalRoot, resumed.trust, owner, reopened::protectJournal, reopened::unprotectJournal)
            journal.initializeNewEnrollment(initial)
            val change = SyncRootLedgerRequest(SyncStoreId.random(), initial.current().reference(), initial.current().members,
                initial.current().coveredTypes + "another_scope", emptySet(), false, "d".repeat(64))
            val signed = journal.commit(change, custody::ledger, SyncRootLedgerAdmission {}, reopened::signRootPayload)
            journal.applyToCustody(signed, custody)
            reopened.close()
            SyncManagementSession.open(code, vault.bytes()).use { again ->
                val recovered = SyncRootLedgerJournal(journalRoot, resumed.trust, owner, again::protectJournal, again::unprotectJournal)
                    .commit(change, custody::ledger, SyncRootLedgerAdmission { error("SYNTHETIC_NO_REAPPROVAL") }) { error("SYNTHETIC_NO_RESIGN") }
                assertEquals(signed.entry, recovered.entry); assertEquals(signed.records, recovered.records)
            }
            assertNotEquals(SyncDeviceSignatures.decode(owner).fingerprint, daily.fingerprint)
        } finally { reopened.close() }
    }

    @Test fun anotherCodeChangedContextAndDamagedCiphertextNeverRecoverPrivateAuthority() {
        SyncManagementSession.create().use { session ->
            val bound = trust(session); val blob = session.seal(bound); val code = session.recoveryCode()
            SyncManagementSession.create().use { other ->
                val otherBlob = other.seal(trust(other)); val otherCode = other.recoveryCode()
                assertFails { SyncManagementSession.open(otherCode, blob.bytes()) }
                assertFails { SyncManagementSession.open(code, otherBlob.bytes()) }
                assertFails { other.unprotectJournal(session.protectJournal(byteArrayOf(1, 2, 3))) }
            }
            val damaged = blob.bytes(); damaged[damaged.lastIndex] = (damaged.last().toInt() xor 1).toByte()
            assertFails { SyncManagementSession.open(code, damaged) }
            assertFails { SyncManagementSession.open(code, blob.bytes().dropLast(1).toByteArray()) }
            assertFails { SyncManagementSession.open(code, blob.bytes() + byteArrayOf(0)) }
            assertFails { session.unprotectJournal(blob.bytes()) }
            assertEquals("SYNC_MANAGEMENT_GROUP_CHANGED", assertFailsWith<IllegalArgumentException> {
                session.seal(bound.copy(group = SyncStoreId.random()))
            }.message)
        }
    }

    @Test fun lockAndMonotonicExpiryRevokePreviouslyCapturedCallbacks() {
        var now = 5L
        val session = SyncManagementSession.create(lifetimeSeconds = 1, now = { now })
        val protect = session::protectJournal; val sign = session::signRootPayload
        val blob = session.seal(trust(session)); val code = session.recoveryCode()
        val encrypted = protect(byteArrayOf(1)); now += 1_000_000_000
        assertFalse(session.isUnlocked)
        assertEquals("SYNC_MANAGEMENT_SESSION_LOCKED", assertFailsWith<IllegalStateException> { protect(byteArrayOf(2)) }.message)
        assertFailsWith<IllegalStateException> { sign(byteArrayOf(3)) }
        assertFailsWith<IllegalStateException> { session.recoveryCode() }
        SyncManagementSession.open(code, blob.bytes()).use { recovered ->
            assertContentEquals(byteArrayOf(1), recovered.unprotectJournal(encrypted))
            val decrypt = recovered::unprotectJournal; recovered.close()
            assertFailsWith<IllegalStateException> { decrypt(encrypted) }
        }
        assertEquals("SyncManagementSession(REDACTED)", session.toString())
        assertEquals("SyncManagementVaultBlob(encrypted)", blob.toString())
    }
    @Test fun openingRetainsTheSameFrozenBytesItAuthenticatedWhenTheCallerMutatesItsInput() {
        SyncManagementSession.create().use { session ->
            val bound = trust(session); val blob = session.seal(bound); val code = session.recoveryCode()
            val callerOwned = blob.bytes(); val before = callerOwned.copyOf()
            SyncManagementSession.open(code, callerOwned, now = {
                callerOwned[callerOwned.lastIndex] = (callerOwned.last().toInt() xor 1).toByte(); 0L
            }).use { reopened ->
                assertFalse(callerOwned.contentEquals(before))
                assertContentEquals(before, reopened.seal(bound).bytes())
                assertFalse(callerOwned.all { it == 0.toByte() })
            }
        }
    }
}
