package com.example.chatbar.domain.syncStore

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.Serializable

@Serializable data class SyncKeyDeliveryContext(val format: Int, val objectId: SyncStoreId, val group: SyncStoreId,
    val epoch: SyncStoreId, val keyCommitment: String, val authorizingLedger: SyncVersionRef,
    val recipient: String, val recipientKeyHash: String) {
    init {
        val hex = Regex("[a-f0-9]{64}")
        require(format == 1 && keyCommitment.matches(hex) && recipient.matches(hex) && recipientKeyHash.matches(hex)) { "SYNC_KEY_DELIVERY_CONTEXT" }
    }
    fun bytes(): ByteArray = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { out ->
        out.writeUTF("CCB-SYNC-KEY-DELIVERY/1"); out.writeUTF(objectId.value); out.writeUTF(group.value); out.writeUTF(epoch.value)
        out.writeUTF(keyCommitment); out.writeUTF(authorizingLedger.id.value); out.writeUTF(authorizingLedger.bodyHash)
        out.writeUTF(recipient); out.writeUTF(recipientKeyHash)
    } }.toByteArray()
}

/** Root signature authenticates the sender; HPKE alone would not. Key bytes stay recipient-only. */
@Serializable data class SyncGroupKeyDelivery(val context: SyncKeyDeliveryContext, val wrappedKey: String, val signature: String) {
    init {
        require(wrappedKey.length in 1..96 * 1024 && wrappedKey.matches(Regex("[A-Za-z0-9+/]+={0,2}")) &&
            signature.length in 1..256) { "SYNC_KEY_DELIVERY_BUDGET" }
    }
    fun signingBytes(): ByteArray {
        val wrapped = wrappedBytes()
        val digest = MessageDigest.getInstance("SHA-256").apply { update(context.bytes()); update(wrapped) }.digest()
        return ("CCB-SYNC-KEY-DELIVERY-SIGNATURE/1\n" + digest.joinToString("") { "%02x".format(it) }).toByteArray(Charsets.US_ASCII)
    }
    private fun wrappedBytes() = Base64.getDecoder().decode(wrappedKey).also {
        require(it.size in 1..64 * 1024) { "SYNC_KEY_DELIVERY_BUDGET" }
    }
    fun verifyOwner(trust: SyncGroupTrust, objectKey: CiphertextObjectKey) {
        require(context.group == trust.group && objectKey == CiphertextObjectKey(CiphertextObjectKind.COMMIT, context.objectId)) { "SYNC_KEY_DELIVERY_CONTEXT" }
        require(SyncDeviceSignatures.decode(trust.ownerCertificate).verifies(signingBytes(), signature)) { "SYNC_KEY_DELIVERY_SIGNATURE" }
    }
    /** Tentative keys can read a missing signed ledger, but cannot yet enter native key custody. */
    fun openTentative(trust: SyncGroupTrust, recipient: SyncDeviceEncryptionKey, objectKey: CiphertextObjectKey): SyncTentativeGroupKey {
        require(context.group == trust.group && objectKey == CiphertextObjectKey(CiphertextObjectKind.COMMIT, context.objectId) &&
            context.recipient == recipient.device && context.recipientKeyHash == recipient.publicKey.hash) { "SYNC_KEY_DELIVERY_RECIPIENT" }
        verifyOwner(trust, objectKey)
        val key = SyncStreamingCipher.open(context.group, context.epoch, wrappedBytes()) { recipient.decrypt(it, context.bytes()) }
        require(key.keyCommitment() == context.keyCommitment) { "SYNC_KEY_DELIVERY_KEY_CHANGED" }
        return SyncTentativeGroupKey(context, key, trust)
    }
    companion object {
        fun create(ledger: SyncDeviceLedger, cipher: SyncStreamingCipher, recipient: String, objectId: SyncStoreId,
            sign: (ByteArray) -> ByteArray): SyncGroupKeyDelivery {
            val current = ledger.current()
            require(cipher.group == current.group && ledger.hasKey(cipher.epoch, cipher.keyCommitment())) { "SYNC_KEY_DELIVERY_KEY_NOT_ADMITTED" }
            val member = requireNotNull(current.members[recipient]?.takeIf { it.active }) { "SYNC_KEY_DELIVERY_RECIPIENT_REVOKED" }
            val public = SyncRecipientPublicKey.parse(member.recipientKey)
            val context = SyncKeyDeliveryContext(1, objectId, current.group, cipher.epoch, cipher.keyCommitment(), current.reference(), recipient, public.hash)
            val wrapped = cipher.protectedKeyset { public.encrypt(it, context.bytes()) }
            val unsigned = SyncGroupKeyDelivery(context, Base64.getEncoder().encodeToString(wrapped), "unsigned")
            val signed = unsigned.copy(signature = Base64.getEncoder().encodeToString(sign(unsigned.signingBytes())))
            require(SyncDeviceSignatures.decode(ledger.ownerCertificate).verifies(signed.signingBytes(), signed.signature)) { "SYNC_KEY_DELIVERY_SIGNATURE" }
            return signed
        }
    }
}

class SyncAdmittedGroupKey internal constructor(val cipher: SyncStreamingCipher, val recipient: String, val authorizingLedger: SyncVersionRef)
class SyncTentativeGroupKey internal constructor(val context: SyncKeyDeliveryContext, val cipher: SyncStreamingCipher,
    private val trust: SyncGroupTrust) {
    fun admit(ledger: SyncDeviceLedger): SyncAdmittedGroupKey {
        val current = ledger.current()
        require(ledger.trustAnchor.group == trust.group && ledger.trustAnchor.genesis == trust.genesis &&
            ledger.trustAnchor.ownerCertificate == trust.ownerCertificate) { "SYNC_KEY_DELIVERY_TRUST_CHANGED" }
        val authorizing = ledger.record(context.authorizingLedger)
        require(current.group == context.group && authorizing != null &&
            ledger.hasKey(context.epoch, context.keyCommitment, context.authorizingLedger)) { "SYNC_KEY_DELIVERY_LEDGER_MISSING" }
        require(authorizing.members[context.recipient]?.let { it.active &&
            SyncRecipientPublicKey.parse(it.recipientKey).hash == context.recipientKeyHash } == true) { "SYNC_KEY_DELIVERY_RECIPIENT" }
        val member = requireNotNull(current.members[context.recipient]?.takeIf { it.active }) { "SYNC_KEY_DELIVERY_RECIPIENT_REVOKED" }
        require(SyncRecipientPublicKey.parse(member.recipientKey).hash == context.recipientKeyHash) { "SYNC_KEY_DELIVERY_RECIPIENT" }
        return SyncAdmittedGroupKey(cipher, context.recipient, context.authorizingLedger)
    }
}
