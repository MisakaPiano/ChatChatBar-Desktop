package com.example.chatbar.domain.syncStore

import com.example.chatbar.domain.lanSync.LanSyncTlsIdentity
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.*

/** Actual first-root signatures and Tink key; synthetic local protection, no invitation or app acceptance. */
class SyncRootGroupCreationTest {
    private class Fixture(val root: Path) {
        val identity = LanSyncTlsIdentity.create(); val recipient = SyncDeviceEncryptionKey.create(identity.fingerprint)
        val certificate = Base64.getEncoder().encodeToString(identity.certificate.encoded)
        val creation = Files.createDirectory(root.resolve("creation"))
        val request = SyncRootGroupCreateRequest(SyncStoreId.random(), setOf("synthetic_records"), "a".repeat(64))
        fun service(observe: (String) -> Unit = {}) = SyncRootGroupCreation(creation, certificate, recipient, { it.reversedArray() }, { it.reversedArray() }, observe)
    }
    private fun withFixture(work: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("synthetic-first-root-").toRealPath()
        try { work(Fixture(root)) } finally { check(root.toFile().deleteRecursively()) }
    }
    @Test fun anApprovedFirstRootReopensItsExactGenesisAndKeyWithoutResigningAndEnrollsOnlyItsCreator() = withFixture { f ->
        val created = f.service().create(f.request, SyncRootGroupCreateAdmission {}, f.identity::signSyncGroupPayload)
        val reopened = f.service().resume(f.request.operation) { error("SYNTHETIC_NO_RESIGN") }
        assertEquals(created.trust, reopened.trust); assertEquals(created.genesis, reopened.genesis)
        assertEquals(created.cipher.keyCommitment(), reopened.cipher.keyCommitment())
        val custody = SyncGroupCustody(Files.createDirectory(f.root.resolve("custody")), created.trust.group, f.recipient, { it.reversedArray() }, { it.reversedArray() })
        val ledger = reopened.enrollCreator(custody)
        assertEquals(created.genesis.reference(), ledger.current().reference())
        assertEquals(created.cipher.keyCommitment(), custody.key(created.genesis.epoch).keyCommitment())
        assertEquals(created.genesis.reference(), created.enrollCreator(custody).current().reference())
        assertEquals("SYNC_GROUP_CREATE_OPERATION_CHANGED", assertFailsWith<IllegalArgumentException> {
            f.service().create(f.request.copy(coveredTypes = setOf("another_scope")), SyncRootGroupCreateAdmission {}, f.identity::signSyncGroupPayload)
        }.message)
    }
    @Test fun rejectionCreatesNoKeyIntentAndLostIntentOrSignedRepliesResumeTheSameApprovedGroup() = withFixture { f ->
        assertFailsWith<IllegalStateException> { f.service().create(f.request, SyncRootGroupCreateAdmission { error("SYNTHETIC_NOT_APPROVED") },
            { error("SYNTHETIC_SIGNER_MUST_NOT_RUN") }) }
        assertFalse(Files.exists(f.creation.resolve("intent.protected")))
        assertFailsWith<IOException> { f.service { if (it == "APPROVED_GROUP_CREATION_RETAINED") throw IOException("SYNTHETIC_INTENT_REPLY_LOST") }
            .create(f.request, SyncRootGroupCreateAdmission {}, { error("SYNTHETIC_SIGNER_NOT_REACHED") }) }
        var signed = 0
        assertFailsWith<IOException> { f.service { if (it == "GROUP_GENESIS_RETAINED") throw IOException("SYNTHETIC_GENESIS_REPLY_LOST") }
            .resume(f.request.operation) { signed++; f.identity.signSyncGroupPayload(it) } }
        assertEquals(1, signed)
        val recovered = f.service().resume(f.request.operation) { error("SYNTHETIC_NO_RESIGN") }
        assertEquals(recovered.genesis, f.service().create(f.request, SyncRootGroupCreateAdmission { error("SYNTHETIC_NO_REAPPROVAL") },
            { error("SYNTHETIC_NO_RESIGN") }).genesis)
        Files.delete(f.creation.resolve("intent.protected"))
        assertEquals("SYNC_GROUP_CREATE_INTENT_MISSING", assertFailsWith<IllegalArgumentException> {
            f.service().create(f.request, SyncRootGroupCreateAdmission {}, f.identity::signSyncGroupPayload)
        }.message)
    }
    @Test fun aWrongSignerOrReplacedRecipientCannotReplaceTheApprovedGenesisOrItsKey() = withFixture { f ->
        val other = LanSyncTlsIdentity.create()
        assertEquals("SYNC_GROUP_CREATE_SIGNATURE", assertFailsWith<IllegalArgumentException> {
            f.service().create(f.request, SyncRootGroupCreateAdmission {}, other::signSyncGroupPayload)
        }.message)
        assertFalse(Files.exists(f.creation.resolve("genesis.protected")))
        assertEquals("SYNC_GROUP_CREATE_PRIVATE_CONTEXT", assertFailsWith<IllegalArgumentException> {
            SyncRootGroupCreation(f.creation, f.certificate, SyncDeviceEncryptionKey.create(f.identity.fingerprint),
                { it.reversedArray() }, { it.reversedArray() }).resume(f.request.operation, f.identity::signSyncGroupPayload)
        }.message)
        val correct = f.service().resume(f.request.operation, f.identity::signSyncGroupPayload)
        assertEquals(f.identity.fingerprint, SyncDeviceSignatures.decode(correct.trust.ownerCertificate).fingerprint)
    }
}
