package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A management-authorized joining anchor, with no invented LAN route or daily identity. */
@Serializable data class LanSyncGroupRecoveryAnchor(val trust: SyncGroupTrust,
    val vault: SyncManagementVaultContext, val vaultDescriptor: CiphertextDescriptor,
    val dataHeads: Set<SyncVersionRef>) {
    init { validate() }
    fun validate() {
        require(vault.group == trust.group && vault.genesis == trust.genesis &&
            vault.owner == SyncDeviceSignatures.decode(trust.ownerCertificate).fingerprint &&
            vaultDescriptor.length in 1..272L * 1024 && dataHeads.map { it.id }.toSet().size == dataHeads.size &&
            dataHeads.all { it.bodyHash.matches(Regex("[a-f0-9]{64}")) }) { "SYNC_GROUP_RECOVERY_ANCHOR" }
    }
}

/** Encrypted control bytes only; normal joining still authenticates ledger, capsules and vault. */
class LanSyncGroupRecoveryBootstrap internal constructor(val anchor: LanSyncGroupRecoveryAnchor,
    val source: CiphertextStore)

/** Not a wire DTO. Only possession of the actual local daily TLS and HPKE private keys creates one. */
class LanSyncGroupRecoveryDeviceProof private constructor(val trust: SyncGroupTrust, val device: String,
    val name: String, val member: SyncGroupMember, private val nonce: String,
    private val response: String, private val signature: String) {
    internal val binding: String get() = lanSyncHash(buildJsonObject {
        put("context", contextHash(trust, device, name, member, nonce)); put("response", response)
    })
    internal fun requireFor(expected: SyncGroupTrust) {
        require(trust == expected && member.active && name.isNotBlank() && name.length <= 160 &&
            nonce.matches(Regex("[a-f0-9]{64}")) && response.matches(Regex("[a-f0-9]{64}")) &&
            SyncDeviceSignatures.decode(member.certificate).let { it.fingerprint == device &&
                it.verifies(payload(binding), signature) }) { "SYNC_GROUP_RECOVERY_DEVICE_PROOF_CHANGED" }
        require(SyncRecipientPublicKey.parse(member.recipientKey).encoded == member.recipientKey) {
            "SYNC_GROUP_RECOVERY_DEVICE_RECIPIENT"
        }
    }
    override fun toString() = "LanSyncGroupRecoveryDeviceProof(REDACTED)"

    companion object {
        internal fun create(trust: SyncGroupTrust, identity: LanSyncTlsIdentity,
            recipient: SyncDeviceEncryptionKey, name: String): LanSyncGroupRecoveryDeviceProof {
            require(recipient.device == identity.fingerprint && name.isNotBlank() && name.length <= 160) {
                "SYNC_GROUP_RECOVERY_DEVICE_KEYS"
            }
            val member = SyncGroupMember(Base64.getEncoder().encodeToString(identity.certificate.encoded), recipient.publicKey.encoded)
            val nonce = random().joinToString("") { "%02x".format(it) }
            val context = ("CCB-SYNC-RECOVERY-DEVICE-HPKE/1\n" +
                contextHash(trust, identity.fingerprint, name, member, nonce)).toByteArray(Charsets.US_ASCII)
            val challenge = random()
            try {
                val answer = recipient.decrypt(recipient.publicKey.encrypt(challenge, context), context)
                try {
                    require(MessageDigest.isEqual(challenge, answer)) { "SYNC_GROUP_RECOVERY_DEVICE_KEY_PROOF" }
                    val response = lanSyncHash(context + answer)
                    val binding = lanSyncHash(buildJsonObject {
                        put("context", contextHash(trust, identity.fingerprint, name, member, nonce)); put("response", response)
                    })
                    return LanSyncGroupRecoveryDeviceProof(trust, identity.fingerprint, name, member, nonce, response,
                        Base64.getEncoder().encodeToString(identity.signSyncGroupPayload(payload(binding)))).also { it.requireFor(trust) }
                } finally { answer.fill(0) }
            } finally { challenge.fill(0) }
        }
        private fun random() = ByteArray(32).also(SecureRandom()::nextBytes)
        private fun payload(hash: String) = ("CCB-SYNC-RECOVERY-DEVICE-SIGNATURE/1\n" + hash).toByteArray(Charsets.US_ASCII)
        private fun contextHash(trust: SyncGroupTrust, device: String, name: String, member: SyncGroupMember,
            nonce: String) = lanSyncHash(buildJsonObject {
            put("group", trust.group.value); put("genesis", trust.genesis.id.value); put("genesisHash", trust.genesis.bodyHash)
            put("rootCertificate", trust.ownerCertificate); put("checkpoint", trust.checkpoint.id.value)
            put("checkpointHash", trust.checkpoint.bodyHash); put("device", device); put("certificate", member.certificate)
            put("recipient", member.recipientKey); put("name", name); put("nonce", nonce)
        })
    }
}

class LanSyncGroupRecoveryDevicePreview internal constructor(val device: String, val name: String,
    val request: SyncRootLedgerRequest, internal val proof: LanSyncGroupRecoveryDeviceProof) {
    fun acknowledge(binding: String): LanSyncGroupRecoveryDeviceApproval {
        require(binding == request.approvalBinding) { "SYNC_GROUP_RECOVERY_DEVICE_CONFIRMATION_REQUIRED" }
        return LanSyncGroupRecoveryDeviceApproval(this)
    }
    override fun toString() = "LanSyncGroupRecoveryDevicePreview(REDACTED)"
}
class LanSyncGroupRecoveryDeviceApproval internal constructor(internal val preview: LanSyncGroupRecoveryDevicePreview)
