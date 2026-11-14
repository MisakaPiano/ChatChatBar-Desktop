package com.example.chatbar.domain.syncStore

import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.util.Base64

/** Existing pinned device certificate, not an external CA or a device name supplied by a manifest. */
internal data class SyncPinnedDeviceCertificate(val encoded: String, val fingerprint: String, val key: PublicKey) {
    fun verifies(payload: ByteArray, encodedSignature: String): Boolean {
        if (!SyncDeviceSignatures.isPayload(payload) || encodedSignature.length !in 1..256) return false
        return try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key); update(payload); verify(Base64.getDecoder().decode(encodedSignature))
            }
        } catch (_: GeneralSecurityException) { false } catch (_: IllegalArgumentException) { false }
    }
}

object SyncDeviceSignatures {
    fun isPayload(payload: ByteArray): Boolean = payload.size in 1..128 &&
        String(payload, Charsets.US_ASCII).matches(Regex("CCB-SYNC-(VERSION|DEVICE-LEDGER|KEY-DELIVERY|LEDGER-NOTICE|RECOVERY-DEVICE)-SIGNATURE/1\n[a-f0-9]{64}"))

    internal fun decode(encoded: String): SyncPinnedDeviceCertificate {
        require(encoded.length in 1..8192) { "SYNC_GROUP_CERTIFICATE" }
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size in 1..4096) { "SYNC_GROUP_CERTIFICATE" }
        val input = bytes.inputStream()
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(input) as X509Certificate
        require(input.read() == -1 && bytes.contentEquals(certificate.encoded) &&
            certificate.sigAlgOID == "1.2.840.10045.4.3.2") { "SYNC_GROUP_CERTIFICATE" }
        val key = certificate.publicKey as? ECPublicKey ?: error("SYNC_GROUP_CERTIFICATE")
        val expected = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
        require(key.params.curve == expected.curve && key.params.generator == expected.generator &&
            key.params.order == expected.order && key.params.cofactor == expected.cofactor) { "SYNC_GROUP_CERTIFICATE" }
        certificate.verify(key)
        // Historical signatures outlive certificate dates. Live enrollment validates its TLS peer.
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return SyncPinnedDeviceCertificate(Base64.getEncoder().encodeToString(bytes), hash, key)
    }
}
