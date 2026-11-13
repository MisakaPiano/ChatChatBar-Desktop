package com.example.chatbar.domain.syncStore

import com.example.chatbar.domain.lanSync.LanSyncMetadataBudget
import com.example.chatbar.domain.lanSync.LanSyncTlsIdentity
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.*

class SyncGroupObjectCodecTest {
    @Test fun serializedOpaqueIdentifiersRetainTheirConstructorValidation() {
        assertFails { kotlinx.serialization.json.Json.decodeFromString(SyncStoreId.serializer(), "\"../escape\"") }
        assertFails { kotlinx.serialization.json.Json.decodeFromString(CiphertextSha256.serializer(), "\"untrusted digest\"") }
    }
    private fun withRoot(work: (Path) -> Unit) {
        val root = Files.createTempDirectory("sync-envelope-synthetic-").toRealPath()
        try { work(root) } finally { check(root.toFile().deleteRecursively()) }
    }
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    private inner class Fixture(val root: Path) {
        val directory = Files.createDirectory(root.resolve("preparation"))
        val queue = Files.createDirectory(root.resolve("queue"))
        val cipher = SyncStreamingCipher.create()
        val codec = SyncGroupObjectCodec(directory)
        val outbox = SyncEncryptedOutbox(queue, cipher)
        val backend = FileCiphertextStore(root.resolve("backend"))
        val device = LanSyncTlsIdentity.create()
        val member = SyncGroupMember(Base64.getEncoder().encodeToString(device.certificate.encoded))
        val ledger = SyncDeviceLedgerRecord(cipher.group, SyncStoreId.random(), null, 1, cipher.epoch, cipher.keyCommitment(),
            mapOf(device.fingerprint to member), emptySet(), "unsigned").let {
            it.copy(signature = Base64.getEncoder().encodeToString(device.signSyncGroupPayload(it.signingBytes())))
        }
        val marker = "仅在加密内容里的合成标记"
        val version = SyncCausalVersion(cipher.group, SyncStoreId.random(), ledger.reference(), device.fingerprint, 1,
            null, emptySet(), mapOf(marker to SyncUnitChange(hash("合成正文"), SyncVersionContent(123,
                hash("synthetic unit plaintext")), emptySet())), "unsigned").let {
            it.copy(signature = Base64.getEncoder().encodeToString(device.signSyncGroupPayload(it.signingBytes())))
        }
        fun publish(envelope: SyncGroupEnvelope): SyncPreparedGroupObject {
            val operation = SyncStoreId.random(); val key = CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random())
            val ready = codec.enqueue(outbox, operation, key, cipher, envelope); outbox.upload(operation, backend); return ready
        }
        fun emptyPreparation() = Files.newDirectoryStream(directory).use { !it.iterator().hasNext() }
    }

    @Test fun signedLedgerAndVersionPassActualEncryptionQueueBackendDecodeAndEpochAdmission() = withRoot { root ->
        val f = Fixture(root)
        val a = f.publish(SyncGroupEnvelope(2, SyncGroupObjectKind.DEVICE_LEDGER, deviceLedger = f.ledger))
        val b = f.publish(SyncGroupEnvelope(2, SyncGroupObjectKind.VERSION, version = f.version))
        assertNotEquals(f.version.id, b.context.key.id)
        val ledgerObject = f.codec.read(f.backend, f.cipher, a.context, a.request.descriptor, f.ledger.reference())
        val versionObject = f.codec.read(f.backend, f.cipher, b.context, b.request.descriptor, f.version.reference())
        assertEquals(f.ledger, ledgerObject.envelope.deviceLedger); assertEquals(f.version, versionObject.envelope.version)
        val state = SyncDeviceLedger(SyncGroupTrust(f.cipher.group, f.ledger.reference(), f.member.certificate))
        state.offer(requireNotNull(ledgerObject.envelope.deviceLedger))
        assertEquals("SYNC_GROUP_CURRENT_EPOCH_REQUIRED", assertFailsWith<IllegalArgumentException> {
            state.admission(listOf(f.version), emptyList()).requireAuthorized(f.version)
        }.message)
        val graph = SyncCausalHistory(f.cipher.group, state.admission(listOf(f.version), listOf(versionObject)))
        assertEquals(SyncVersionState.ADMITTED, graph.offer(requireNotNull(versionObject.envelope.version)))
        assertEquals(f.version.reference(), graph.frontier(f.marker).single().version)
        assertFails { (versionObject.envelope.version!!.changes as MutableMap<String, SyncUnitChange>).clear() }
        assertTrue(f.emptyPreparation())
        val marker = f.marker.toByteArray(Charsets.UTF_8)
        Files.walk(root.resolve("backend")).use { paths -> paths.filter { Files.isRegularFile(it) }.forEach { path ->
            val bytes = Files.readAllBytes(path)
            assertFalse((0..bytes.size - marker.size).any { start -> marker.indices.all { bytes[start + it] == marker[it] } })
        } }
    }

    @Test fun expectedReferencesObjectContextAndMetadataBudgetRefuseWithoutLeavingPlaintext() = withRoot { root ->
        val f = Fixture(root); val ready = f.publish(SyncGroupEnvelope(2, SyncGroupObjectKind.VERSION, version = f.version))
        assertEquals("SYNC_GROUP_ENVELOPE_CONTEXT", assertFailsWith<IllegalArgumentException> {
            f.codec.read(f.backend, f.cipher, ready.context, expectedReference = f.version.reference().copy(bodyHash = hash("wrong reference")))
        }.message)
        assertTrue(f.emptyPreparation())
        val bounded = SyncGroupObjectCodec(f.directory, LanSyncMetadataBudget(maximumDecodedCost = 1))
        assertEquals("SYNC_METADATA_WORKING_BUDGET", assertFailsWith<IllegalArgumentException> { bounded.read(f.backend, f.cipher, ready.context) }.message)
        assertTrue(f.emptyPreparation())
        val alias = ready.context.copy(key = ready.context.key.copy(id = SyncStoreId.random()))
        val aliased = object : CiphertextStore by f.backend {
            override fun readTo(key: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?): CiphertextObjectMetadata? =
                f.backend.readTo(ready.context.key, destination, expected)?.copy(key = key)
        }
        assertFails { f.codec.read(aliased, f.cipher, alias) }; assertTrue(f.emptyPreparation())
    }

    @Test fun aDifferentSecretWithTheSameGroupAndEpochLabelsCannotCreateCurrentEpochAuthority() = withRoot { root ->
        val f = Fixture(root)
        val wrong = SyncStreamingCipher.create(f.cipher.group, f.cipher.epoch)
        val queue = Files.createDirectory(root.resolve("wrong-key-queue")); val outbox = SyncEncryptedOutbox(queue, wrong)
        val prepared = f.codec.enqueue(outbox, SyncStoreId.random(), CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random()),
            wrong, SyncGroupEnvelope(2, SyncGroupObjectKind.VERSION, version = f.version))
        val ledger = SyncDeviceLedger(SyncGroupTrust(f.cipher.group, f.ledger.reference(), f.member.certificate)); ledger.offer(f.ledger)
        assertNotEquals(f.ledger.keyCommitment, prepared.authenticated.keyCommitment)
        assertEquals("SYNC_GROUP_CURRENT_EPOCH_REQUIRED", assertFailsWith<IllegalArgumentException> {
            ledger.admission(listOf(f.version), listOf(prepared.authenticated)).requireAuthorized(f.version)
        }.message)
    }

    @Test fun validStorageMetadataCannotAuthorizeTamperedCiphertextOrMalformedAuthenticatedJson() = withRoot { root ->
        val f = Fixture(root); val ready = f.publish(SyncGroupEnvelope(2, SyncGroupObjectKind.VERSION, version = f.version))
        val bytes = ByteArrayOutputStream().also { f.backend.readTo(ready.context.key, it) }.toByteArray()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        val tampered = object : CiphertextStore by f.backend {
            override fun readTo(key: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?): CiphertextObjectMetadata {
                destination.write(bytes); return CiphertextObjectMetadata(key, ready.request.descriptor)
            }
        }
        assertFails { f.codec.read(tampered, f.cipher, ready.context) }; assertTrue(f.emptyPreparation())
        for (invalid in listOf("{\"format\":3,\"kind\":\"VERSION\"}".toByteArray(), byteArrayOf(0xC0.toByte(), 0xAF.toByte()))) {
            val source = root.resolve("malformed.json"); Files.write(source, invalid)
            val operation = SyncStoreId.random(); val context = SyncEncryptionContext(f.cipher.group, f.cipher.epoch,
                CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random()))
            f.outbox.prepare(operation, context, source); f.outbox.upload(operation, f.backend)
            assertFails { f.codec.read(f.backend, f.cipher, context) }; assertTrue(f.emptyPreparation())
            Files.delete(source)
        }
    }
}
