package com.example.chatbar.domain.syncStore

import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.BinaryKeysetWriter
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.hybrid.HpkeParameters
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Base64

/** Public recipient key only. HPKE supplies confidentiality; the delivery owner authenticates sender. */
class SyncRecipientPublicKey private constructor(val encoded: String, val hash: String, private val primitive: HybridEncrypt) {
    fun encrypt(plaintext: ByteArray, context: ByteArray): ByteArray {
        require(plaintext.size in 1..32 * 1024 && context.size in 1..2048) { "SYNC_HPKE_BUDGET" }
        return primitive.encrypt(plaintext, context).also { require(it.size <= 64 * 1024) { "SYNC_HPKE_BUDGET" } }
    }
    companion object {
        fun parse(encoded: String): SyncRecipientPublicKey {
            require(encoded.length in 1..32 * 1024) { "SYNC_HPKE_PUBLIC_KEY" }
            val bytes = Base64.getDecoder().decode(encoded); require(bytes.size in 1..16 * 1024) { "SYNC_HPKE_PUBLIC_KEY" }
            HybridConfig.register()
            val handle = CleartextKeysetHandle.read(BinaryKeysetReader.withBytes(bytes))
            SyncDeviceEncryptionKey.requireParameters(handle)
            val canonical = ByteArrayOutputStream().also { handle.writeNoSecret(BinaryKeysetWriter.withOutputStream(it)) }.toByteArray()
            val digest = MessageDigest.getInstance("SHA-256").apply {
                update("CCB-SYNC-HPKE-PUBLIC/1\n".toByteArray(Charsets.US_ASCII)); update(canonical)
            }.digest().joinToString("") { "%02x".format(it) }
            return SyncRecipientPublicKey(Base64.getEncoder().encodeToString(canonical), digest,
                handle.getPrimitive(RegistryConfiguration.get(), HybridEncrypt::class.java))
        }
    }
}

/** Separate per-device recipient key, protected locally and bound to the existing device identity. */
class SyncDeviceEncryptionKey private constructor(val device: String, private val handle: KeysetHandle) {
    val publicKey: SyncRecipientPublicKey = SyncRecipientPublicKey.parse(Base64.getEncoder().encodeToString(
        ByteArrayOutputStream().also { handle.publicKeysetHandle.writeNoSecret(BinaryKeysetWriter.withOutputStream(it)) }.toByteArray()))
    private val primitive = handle.getPrimitive(RegistryConfiguration.get(), HybridDecrypt::class.java)

    fun decrypt(ciphertext: ByteArray, context: ByteArray): ByteArray {
        require(ciphertext.size in 1..64 * 1024 && context.size in 1..2048) { "SYNC_HPKE_BUDGET" }
        return primitive.decrypt(ciphertext, context).also { if (it.size !in 1..32 * 1024) { it.fill(0); error("SYNC_HPKE_BUDGET") } }
    }
    fun protectedKeyset(protect: (ByteArray) -> ByteArray): ByteArray {
        val key = ByteArrayOutputStream().also { CleartextKeysetHandle.write(handle, BinaryKeysetWriter.withOutputStream(it)) }.toByteArray()
        try {
            require(key.size in 1..32 * 1024) { "SYNC_HPKE_PRIVATE_KEY" }
            val envelope = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use {
                it.write(MAGIC); it.write(device.toByteArray(Charsets.US_ASCII)); it.writeInt(key.size); it.write(key)
            } }.toByteArray()
            try { return protect(envelope).copyOf().also { require(it.size in 1..64 * 1024) { "SYNC_HPKE_PRIVATE_KEY" } } }
            finally { envelope.fill(0) }
        } finally { key.fill(0) }
    }

    companion object {
        private val MAGIC = "CCB-SYNC-RECIPIENT/1\n".toByteArray(Charsets.US_ASCII)
        private val PARAMETERS = HpkeParameters.builder().setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
            .setKdfId(HpkeParameters.KdfId.HKDF_SHA256).setAeadId(HpkeParameters.AeadId.AES_256_GCM)
            .setVariant(HpkeParameters.Variant.NO_PREFIX).build()
        internal fun requireParameters(handle: KeysetHandle) {
            require(handle.size() == 1 && handle.primary.key.parameters == PARAMETERS) { "SYNC_HPKE_PARAMETERS" }
        }
        fun create(device: String): SyncDeviceEncryptionKey {
            require(device.matches(Regex("[a-f0-9]{64}"))) { "SYNC_HPKE_DEVICE" }
            HybridConfig.register()
            return SyncDeviceEncryptionKey(device, KeysetHandle.generateNew(PARAMETERS))
        }
        fun open(device: String, protected: ByteArray, unprotect: (ByteArray) -> ByteArray): SyncDeviceEncryptionKey {
            require(device.matches(Regex("[a-f0-9]{64}")) && protected.size in 1..64 * 1024) { "SYNC_HPKE_PRIVATE_KEY" }
            val borrowed = protected.copyOf()
            val envelope = try { unprotect(borrowed) } catch (failure: Throwable) { borrowed.fill(0); throw failure }
            try {
                require(envelope.size in 1..64 * 1024) { "SYNC_HPKE_PRIVATE_KEY" }
                DataInputStream(envelope.inputStream()).use { input ->
                    require(ByteArray(MAGIC.size).also(input::readFully).contentEquals(MAGIC)) { "SYNC_HPKE_PRIVATE_KEY" }
                    require(String(ByteArray(64).also(input::readFully), Charsets.US_ASCII) == device) { "SYNC_HPKE_DEVICE" }
                    val size = input.readInt(); require(size in 1..32 * 1024) { "SYNC_HPKE_PRIVATE_KEY" }
                    val bytes = ByteArray(size).also(input::readFully)
                    try {
                        require(input.read() == -1) { "SYNC_HPKE_PRIVATE_KEY" }
                        HybridConfig.register()
                        val handle = CleartextKeysetHandle.read(BinaryKeysetReader.withBytes(bytes)); requireParameters(handle)
                        return SyncDeviceEncryptionKey(device, handle)
                    } finally { bytes.fill(0) }
                }
            } finally { borrowed.fill(0); envelope.fill(0) }
        }
    }
}
