package com.example.chatbar.domain.syncStore

import com.google.crypto.tink.BinaryKeysetWriter
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.hybrid.HpkeParameters
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.*

/** Real Tink HPKE with synthetic keys; native enrollment/sender signatures remain separate. */
class SyncDeviceEncryptionKeyTest {
    private fun device(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test fun recipientKeyReopensAndTransfersTheActualStreamingGroupSecretWithoutChangingItsCommitment() {
        val id = device("synthetic recipient")
        val recipient = SyncDeviceEncryptionKey.create(id)
        val protected = recipient.protectedKeyset { it.reversedArray() }; val frozen = protected.copyOf()
        val reopened = SyncDeviceEncryptionKey.open(id, protected) { it.reversedArray() }
        assertContentEquals(frozen, protected); assertEquals(recipient.publicKey.encoded, reopened.publicKey.encoded)
        val groupKey = SyncStreamingCipher.create()
        val context = "CCB-SYNC-KEY-DELIVERY/1\n${groupKey.group.value}\n${groupKey.epoch.value}\n$id".toByteArray(Charsets.US_ASCII)
        val public = SyncRecipientPublicKey.parse(recipient.publicKey.encoded)
        val wrapped = groupKey.protectedKeyset { public.encrypt(it, context) }
        val opened = SyncStreamingCipher.open(groupKey.group, groupKey.epoch, wrapped) { reopened.decrypt(it, context) }
        assertEquals(groupKey.keyCommitment(), opened.keyCommitment())
        assertEquals("SYNC_HPKE_DEVICE", assertFailsWith<IllegalArgumentException> {
            SyncDeviceEncryptionKey.open(device("another identity"), protected) { it.reversedArray() }
        }.message)
    }

    @Test fun onlyTheRecipientAndExactContextOpenAWrappedSecretAndCiphertextIsRandomized() {
        val recipient = SyncDeviceEncryptionKey.create(device("recipient")); val other = SyncDeviceEncryptionKey.create(device("other"))
        val secret = ByteArray(109) { (it % 97).toByte() }; val context = "opaque synthetic binding".toByteArray()
        val first = recipient.publicKey.encrypt(secret, context); val second = recipient.publicKey.encrypt(secret, context)
        assertFalse(first.contentEquals(second))
        assertContentEquals(secret, recipient.decrypt(first, context))
        assertFails { other.decrypt(first, context) }
        assertFails { recipient.decrypt(first, "different binding".toByteArray()) }
        val damaged = first.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertFails { recipient.decrypt(damaged, context) }
        assertFails { recipient.decrypt(first.copyOf(first.size - 1), context) }
    }

    @Test fun publicKeyParsingRejectsSecretKeyMaterialAndUnsupportedHpkeParameters() {
        SyncDeviceEncryptionKey.create(device("register supported parameters"))
        fun encoded(handle: KeysetHandle): String = Base64.getEncoder().encodeToString(ByteArrayOutputStream().also {
            CleartextKeysetHandle.write(handle, BinaryKeysetWriter.withOutputStream(it))
        }.toByteArray())
        val parameters = HpkeParameters.builder().setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
            .setKdfId(HpkeParameters.KdfId.HKDF_SHA256).setAeadId(HpkeParameters.AeadId.AES_256_GCM)
            .setVariant(HpkeParameters.Variant.NO_PREFIX).build()
        val privateHandle = KeysetHandle.generateNew(parameters)
        assertFails { SyncRecipientPublicKey.parse(encoded(privateHandle)) }
        val unsupported = KeysetHandle.generateNew(HpkeParameters.builder().setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
            .setKdfId(HpkeParameters.KdfId.HKDF_SHA256).setAeadId(HpkeParameters.AeadId.AES_128_GCM)
            .setVariant(HpkeParameters.Variant.NO_PREFIX).build())
        assertEquals("SYNC_HPKE_PARAMETERS", assertFailsWith<IllegalArgumentException> {
            SyncRecipientPublicKey.parse(encoded(unsupported.publicKeysetHandle))
        }.message)
    }
}
